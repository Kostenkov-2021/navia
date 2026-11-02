# Visual place recognition — method

How the experimental "Place photos" feature (branch `feature/visual-place-recognition`) recognises
a saved place from the camera and tells the user how far to turn to face it. Evaluation numbers are
in `docs/vpr-robustness-eval.md`.

## 1. Model

- **MegaLoc**: a DINOv2 ViT-B/14 backbone, SALAD aggregation, and a final linear layer
  (16640 → 8448). Licence MIT. It was exported to ONNX by `tools/vpr/export_megaloc.py` and runs
  through ONNX Runtime on the CPU.
- **Input**: 224×224 RGB. The ImageNet normalisation is done inside the graph.
- **Outputs**, both from one forward pass:
  - `global`: an L2-normalised 8448-d place descriptor.
  - `patches`: a 16×16 grid of L2-normalised 768-d DINOv2 patch tokens.
- **Quantisation ("hybrid")**: ORT dynamic int8 everywhere except the MLP `fc2` layers, which are
  quantised weight-only int8.
  - Plain dynamic int8 destroys DINOv2: descriptor cosine to fp32 is about 0.2, because of outlier
    activations in `fc2`.
  - The hybrid stays at a cosine of about 0.985 to fp32.
  - File `megaloc_full_224_hq.onnx`, 230 MB.
  - On the S21: about 274 ms per frame.
- **Why 224 and not 322**: 322 was slightly better on real photos (top-1 54.6 % vs 50.9 %) but takes
  about 3× as long per frame (852 ms). PCA reduction of the descriptor hurt on real photos, so the
  full 8448-d descriptor is kept.

## 2. Frame preparation (orientation invariance)

The user may hold the phone portrait, landscape or upside down.

1. **Upright frames.** `CameraOrientation` listens to an `OrientationEventListener` and sets the
   `ImageAnalysis` target rotation from the phone's physical orientation, in 90° steps with 45°
   boundaries. This is needed because the record screen is locked to portrait.
   `VprFrames.upright()` then rotates each frame by `rotationDegrees`, so the scene is always
   upright. Front-camera frames are left unmirrored, so they show the scene as it is.
2. **Centre square.** `VprModel.embedFull()` crops the largest centred square before scaling it to
   224. A landscape and a portrait shot of the same view therefore feed the model the same content.
   Descriptors are stored under the key `megaloc_full_224_hq/sq`; frames with another key (made
   before the crop was added) are ignored.

## 3. Camera heading (compass)

- **Source.** `CameraOrientation.heading()` reads `TYPE_ROTATION_VECTOR`.
  - The direction the lens looks is the device's −Z axis for the back camera and +Z for the front
    camera. Its horizontal part (−r[2], −r[5] in the rotation matrix, sign flipped for the front
    camera) gives the azimuth.
  - The app's `AngleManager` azimuth follows the phone's top edge. That is wrong for a phone held
    upright, so it is not used here.
- **When it is valid.** The heading is only reported when the lens axis is at least 0.3 horizontal.
  It is low-pass filtered (factor 0.15) on the unit circle.
- **Same frame of reference for both cameras.** The heading is always the lens direction, so
  headings recorded with one camera can be compared with live headings from the other.

## 4. Recording a place

`RecordVisualPlaceActivity` always uses the back camera.

- **Capture.** The user films for 5 s, with a beep each second. In every 1-second slot, the
  sharpest frame is kept, measured by the variance of a Laplacian on a downscaled copy
  (`VprFrames.sharpness`). This gives up to 5 frames per recording.
- **What is saved for each frame:**
  - the global descriptor;
  - the camera heading at that moment;
  - the camera used;
  - a JPEG (quality 92) under `files/visual_places/<place>/<recording>_<i>.jpg`.
- **Location.** The best GPS fix of the last 30 s is saved with the recording.
- **Several recordings per place.** A place can have several recordings, for example one per
  direction of approach (database `visual_places.db`, tables `places`, `recordings`, `frames`).
  `EditVisualPlaceActivity` renames a place and lists, deletes and adds its recordings.

## 5. Recognition (visual check)

`VisualCheckController`, started from the map screen when a recording lies within 50 m:

1. **Nearby frames.** Places within 50 m (judged by each recording's location) are loaded with all
   their frames.
2. **Live frame.** One frame is taken, made upright, and embedded (global + patches). Then the
   check pauses 2 s, so it runs about one cycle every 2.5 s.
3. **Best match.** The best saved frame is the one with the highest cosine similarity of global
   descriptors.
4. **Threshold.** It is a match if the similarity is at least **0.3**.
   - In the synthetic test, different places scored 0.12 at the 99.9th percentile.
   - Indoors, the same place scores only about 0.3–0.6. This is the model's natural range for
     indoor scenes, since it was trained on outdoor imagery.
   - The false-positive rate between different rooms has not been measured yet.
5. **Silence.** While the check runs, the automatic nearby-place announcements are suppressed
   (`AnnouncementManager.setAutomaticSuppressed`). Back stops the check.

## 6. Turn guidance

### 6.1 From the image (preferred) — `VprAlign`

Given the live patch grid and the matched saved frame's patch grid:

1. **Saved patches.** These are computed on demand from the saved JPEG and cached for the session.
2. **Mutual nearest neighbours.** All 256×256 patch cosine similarities are computed. A pair is kept
   when each patch is the other's best match and the similarity is at least 0.5.
3. **Angle of each patch.** A patch column is converted to a horizontal angle with a pinhole model:
   `u = (col + 0.5) / 16 · 2 − 1`, `angle = atan(u · tan(fov / 2))`.
   - `fov` is the field of view across the centre square, i.e. across the sensor's short side. It is
     computed from Camera2 `SENSOR_INFO_PHYSICAL_SIZE` and `LENS_INFO_AVAILABLE_FOCAL_LENGTHS`: 53.5°
     on the S21 main camera, with 55° as the fallback.
4. **Turn.** The turn is the **median** over the matched pairs of (live angle − saved angle).
   Positive means turn right (clockwise). With fewer than 30 matched pairs, no image turn is
   reported. Real matches gave 85–174 pairs.

Offline check on two recordings of a room: within one recording the image turn agreed with the
compass to about 3°. Across the two recordings it stayed consistent where the indoor compass had
drifted by up to 20°. On the S21 it takes about 154 ms per pair.

### 6.2 From the compass (fallback)

If the image matched but too few patches aligned, the turn is the difference between the matched
frame's saved heading and the live heading, wrapped to (−180°, 180°].

### 6.3 No match

The target is the circular mean heading of the frames in the nearest recording of the nearest place.
If the live heading is 30° or more away from it, the app speaks a hint such as "X is about N degrees
to your right", at most every 6 s. Otherwise a soft tick tells the user the check is still running.

## 7. Feedback to the user

| Situation | Speech | Vibration |
|---|---|---|
| Image turn < 10° | "Found X, N%" (spoken again only on a new place, after 10 s, or after the user turns away and back) | 3 buzzes |
| Image turn 10–30° | "Found X … turn N degrees right/left" (the place name again only on a new place or after 10 s; otherwise just the turn) | 2 buzzes |
| Image turn ≥ 30° | same as above | 1 buzz |
| Match, but the turn comes from the compass | same speech as above | none |
| No match | compass hint (≥ 30°, every 6 s) or a tick | none |

- Spoken angles are rounded to 5°, with a minimum of 5°.
- Each buzz lasts 120 ms, with 150 ms gaps. The strength follows the user's vibration-intensity
  setting.
- **Front camera:** during the check, a button next to Stop switches between the back and front
  camera. The choice is remembered (preference `vpr_front_camera`). The heading convention in
  section 3 keeps the compass comparisons valid for either camera.

## 8. Known limits and open points

- Indoors, similarity is low (0.3–0.6), and the false-positive level between rooms is unknown.
- Only left/right turning is given. Closer/farther (from the scale of matched patches) is not
  implemented.
- Alignment uses the single best frame. Combining several frames, or using 322 input for guidance,
  might be more stable.
- The model is pushed with adb for now; hosting and download are not built yet.
