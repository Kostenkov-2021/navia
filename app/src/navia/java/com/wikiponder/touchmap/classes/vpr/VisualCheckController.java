package com.wikiponder.touchmap.classes.vpr;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.camera2.CameraCharacteristics;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.SizeF;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.Camera;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.wikiponder.touchmap.MyApplication;
import com.wikiponder.touchmap.R;
import com.wikiponder.touchmap.classes.AudioClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The live visual check on the map screen: take a frame, compare its place descriptor with the
 * saved places within {@link #RADIUS_M}, announce a match, wait {@link #PAUSE_MS}, repeat.
 * Runs only while the map screen is in the foreground (CameraX is bound to its lifecycle).
 *
 * <p>Turn guidance: on a match, how far to turn to see the place as in the best-matching saved
 * frame — from the image (VprAlign, precise, needs the place partly in view), else from the compass
 * (the saved frame's heading against the live one; indoors it can be off by ~20°). Without a match
 * the compass alone says roughly where the nearest place is, as filmed from the recording spot
 * closest to the user.
 */
public class VisualCheckController {
	private static final String TAG = "VisualCheck";
	public static final double RADIUS_M = 50;
	private static final long PAUSE_MS = 2000;
	/** Cosine similarity above which a frame counts as "this place". From tools/vpr/eval_robustness.py
	 * (2026-11-01, megaloc_full_224_hq, 2000 distinct landmarks): different places scored 0.12 at
	 * the 99.9th percentile; the same place at night, 1.6x closer or half out of frame had medians
	 * of 0.47–0.59. Still to be checked against real captures. */
	static final float MATCH_THRESHOLD = 0.3f;
	/** The same match is not announced again within this time. */
	private static final long REPEAT_MS = 10_000;
	/** A turn smaller than this counts as facing the place. */
	private static final float ALIGNED_DEG = 10;
	/** An image-measured turn smaller than this vibrates twice, a larger one once (aligned:
	 * three times). Compass estimates never vibrate. */
	private static final float NEAR_DEG = 30;
	/** Without a match, the compass hint is given only for turns larger than this, ... */
	private static final float HINT_MIN_DEG = 30;
	/** ... and at most this often. */
	private static final long HINT_MS = 6000;
	/** Field of view across the centre square if the camera does not report its optics. */
	private static final float DEFAULT_FOV_DEG = 55;

	public interface Listener {
		/** The check stopped by itself (no saved place nearby any more, or an error). */
		void onVisualCheckStopped(String reason);
	}

	private final AppCompatActivity activity;
	private final Listener listener;
	private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
	private final ExecutorService modelExecutor = Executors.newSingleThreadExecutor();
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final AtomicBoolean wantFrame = new AtomicBoolean(false);
	private final CameraOrientation orientation;
	private ProcessCameraProvider provider;
	private ImageAnalysis analysis;
	private volatile boolean running = false;
	private volatile Location location;
	private boolean frontCamera;
	private long lastAnnouncedPlace = -1;
	private long lastAnnouncedAt;
	private boolean wasAligned;
	private long lastHintAt;
	private volatile float fovDeg = DEFAULT_FOV_DEG;
	/** Patch tokens of saved frames, computed from their JPEGs when first needed. */
	private final Map<Long, float[]> savedPatches = new HashMap<>();

	public VisualCheckController(AppCompatActivity activity, Listener listener) {
		this.activity = activity;
		this.listener = listener;
		orientation = new CameraOrientation(activity);
		frontCamera = VprFrames.useFrontCamera(activity);
	}

	public boolean isFrontCamera() {
		return frontCamera;
	}

	/** Switches between the back and front camera, also while the check runs; remembered. */
	public void setFrontCamera(boolean front) {
		if (front == frontCamera) return;
		frontCamera = front;
		VprFrames.setUseFrontCamera(activity, front);
		orientation.setFrontCamera(front);
		if (running && provider != null && analysis != null) {
			try {
				provider.unbind(analysis);
				fovDeg = fovDegrees(provider.bindToLifecycle(activity, VprFrames.selector(front), analysis));
			} catch (Exception e) {
				Log.e(TAG, "switch camera", e);
				stopWithReason(activity.getString(R.string.camera_unavailable));
			}
		}
	}

	public boolean isRunning() {
		return running;
	}

	public void setLocation(Location location) {
		this.location = location;
	}

	public void start() {
		if (running) return;
		running = true;
		// Nearby-place announcements would talk over the check's own guidance.
		MyApplication.getAnnouncementManager(activity).setAutomaticSuppressed(this, true);
		lastAnnouncedPlace = -1;
		wasAligned = false;
		lastHintAt = 0;
		ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(activity);
		future.addListener(() -> {
			try {
				provider = future.get();
				analysis = new ImageAnalysis.Builder()
						.setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
						.setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
						.build();
				orientation.attach(analysis);
				analysis.setAnalyzer(analysisExecutor, image -> {
					try {
						if (running && wantFrame.compareAndSet(true, false)) {
							Bitmap frame = VprFrames.upright(image);
							float heading = orientation.heading();
							modelExecutor.execute(() -> analyze(frame, heading));
						}
					} catch (Exception e) {
						Log.w(TAG, "frame", e);
					} finally {
						image.close();
					}
				});
				if (!running) return;
				orientation.setFrontCamera(frontCamera);
				fovDeg = fovDegrees(provider.bindToLifecycle(activity, VprFrames.selector(frontCamera), analysis));
				orientation.start();
				MyApplication.getAudioClient(activity).speak(activity.getString(R.string.visual_check_started));
				wantFrame.set(true);
			} catch (Exception e) {
				Log.e(TAG, "camera", e);
				stopWithReason(activity.getString(R.string.camera_unavailable));
			}
		}, ContextCompat.getMainExecutor(activity));
	}

	public void stop() {
		running = false;
		MyApplication.getAnnouncementManager(activity).setAutomaticSuppressed(this, false);
		wantFrame.set(false);
		handler.removeCallbacksAndMessages(null);
		orientation.stop();
		if (provider != null && analysis != null) provider.unbind(analysis);
		modelExecutor.execute(() -> {
			savedPatches.clear();
			VprModel.release();
		});
	}

	public void shutdown() {
		stop();
		analysisExecutor.shutdown();
		modelExecutor.shutdown();
	}

	private void stopWithReason(String reason) {
		handler.post(() -> {
			if (!running) return;
			stop();
			listener.onVisualCheckStopped(reason);
		});
	}

	/** Field of view (degrees) across the camera's shorter sensor side, which is what the centre
	 * square fed to the model spans however the phone is held (ImageAnalysis frames are 4:3, the
	 * sensor's own aspect). */
	@OptIn(markerClass = ExperimentalCamera2Interop.class)
	private static float fovDegrees(Camera camera) {
		try {
			Camera2CameraInfo info = Camera2CameraInfo.from(camera.getCameraInfo());
			SizeF size = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
			float[] focal = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
			if (size != null && focal != null && focal.length > 0 && focal[0] > 0) {
				float shortSide = Math.min(size.getWidth(), size.getHeight());
				float fov = (float) Math.toDegrees(2 * Math.atan(shortSide / 2 / focal[0]));
				Log.d(TAG, "fov " + fov);
				if (fov > 20 && fov < 120) return fov;
			}
		} catch (Exception e) {
			Log.w(TAG, "fov", e);
		}
		return DEFAULT_FOV_DEG;
	}

	private void analyze(Bitmap frame, float liveHeading) {
		try {
			if (!running) return;
			Location here = location;
			List<VisualPlacesDb.Place> places = here == null ? List.of()
					: VisualPlacesDb.get(activity).placesNear(here.getLatitude(), here.getLongitude(), RADIUS_M);
			if (places.isEmpty()) {
				stopWithReason(activity.getString(R.string.visual_check_no_places));
				return;
			}
			VprModel model = VprModel.get(activity);
			if (model == null) {
				stopWithReason(activity.getString(R.string.visual_model_missing));
				return;
			}
			VprModel.Embedding live = model.embedFull(frame);
			VisualPlacesDb.Place best = null;
			VisualPlacesDb.SavedFrame bestFrame = null;
			float bestSim = -1;
			for (VisualPlacesDb.Place p : places) {
				for (VisualPlacesDb.SavedFrame f : p.frames) {
					float s = VprModel.similarity(live.global, f.descriptor);
					if (s > bestSim) {
						bestSim = s;
						best = p;
						bestFrame = f;
					}
				}
			}
			AudioClient audio = MyApplication.getAudioClient(activity);
			long now = SystemClock.elapsedRealtime();
			if (best != null && bestSim >= MATCH_THRESHOLD) {
				float turn = VprAlign.turnDegrees(live, patchesOf(model, bestFrame), fovDeg);
				boolean fromImage = !Float.isNaN(turn);
				if (!fromImage) turn = angleDiff(bestFrame.heading, liveHeading);
				Log.d(TAG, "best " + best.name + " " + bestSim + " turn " + turn + (fromImage ? " image" : " compass"));
				boolean fresh = best.id != lastAnnouncedPlace || now - lastAnnouncedAt > REPEAT_MS;
				if (fromImage) {
					float abs = Math.abs(turn);
					MyApplication.getAnnouncementManager(activity)
							.vibratePulses(abs < ALIGNED_DEG ? 3 : abs < NEAR_DEG ? 2 : 1);
				}
				if (Float.isNaN(turn) || Math.abs(turn) < ALIGNED_DEG) {
					if (fresh || !wasAligned) {
						audio.speak(activity.getString(R.string.visual_check_seen, best.name));
						lastAnnouncedAt = now;
					}
					wasAligned = true;
				} else {
					String instruction = turnInstruction(turn);
					if (fresh) {
						audio.speak(activity.getString(R.string.visual_check_seen_turn, best.name, instruction));
						lastAnnouncedAt = now;
					} else {
						audio.speak(instruction);
					}
					wasAligned = false;
				}
				lastAnnouncedPlace = best.id;
			} else {
				Log.d(TAG, "best " + (best == null ? "-" : best.name) + " " + bestSim);
				lastAnnouncedPlace = -1;
				wasAligned = false;
				float hint = angleDiff(compassTarget(places.get(0), here), liveHeading);
				if (!Float.isNaN(hint) && Math.abs(hint) >= HINT_MIN_DEG && now - lastHintAt >= HINT_MS) {
					lastHintAt = now;
					audio.speak(activity.getString(hint > 0 ? R.string.visual_compass_hint_right
							: R.string.visual_compass_hint_left, places.get(0).name, roundTo5(Math.abs(hint))));
				} else {
					// A soft tick per frame tells the user the check is still looking.
					audio.playSound(AudioClient.SOUND_AIM_TICK, -0.5, 0, 1.0);
				}
			}
		} catch (Exception e) {
			Log.e(TAG, "analyze", e);
		} finally {
			frame.recycle();
			if (running) handler.postDelayed(() -> wantFrame.set(true), PAUSE_MS);
		}
	}

	private float[] patchesOf(VprModel model, VisualPlacesDb.SavedFrame f) {
		float[] p = savedPatches.get(f.id);
		if (p != null || f.imagePath == null) return p;
		Bitmap saved = BitmapFactory.decodeFile(f.imagePath);
		if (saved == null) return null;
		try {
			p = model.embedFull(saved).patches;
			savedPatches.put(f.id, p);
		} catch (Exception e) {
			Log.w(TAG, "saved patches", e);
		} finally {
			saved.recycle();
		}
		return p;
	}

	/** Mean compass heading of the place's frames from the recording made nearest to the user,
	 * NaN if none has a heading. */
	private static float compassTarget(VisualPlacesDb.Place place, Location here) {
		long nearestRecording = -1;
		float nearest = Float.MAX_VALUE;
		float[] dist = new float[1];
		for (VisualPlacesDb.SavedFrame f : place.frames) {
			Location.distanceBetween(here.getLatitude(), here.getLongitude(), f.lat, f.lon, dist);
			if (dist[0] < nearest) {
				nearest = dist[0];
				nearestRecording = f.recordingId;
			}
		}
		double east = 0, north = 0;
		for (VisualPlacesDb.SavedFrame f : place.frames) {
			if (f.recordingId != nearestRecording || Float.isNaN(f.heading)) continue;
			east += Math.sin(Math.toRadians(f.heading));
			north += Math.cos(Math.toRadians(f.heading));
		}
		if (east == 0 && north == 0) return Float.NaN;
		return (float) Math.toDegrees(Math.atan2(east, north));
	}

	/** target âˆ’ current in (âˆ’180, 180], positive = clockwise; NaN if either is unknown. */
	private static float angleDiff(float target, float current) {
		if (Float.isNaN(target) || Float.isNaN(current)) return Float.NaN;
		float d = (target - current) % 360;
		if (d > 180) d -= 360;
		if (d <= -180) d += 360;
		return d;
	}

	private static int roundTo5(float deg) {
		return Math.max(5, Math.round(deg / 5) * 5);
	}

	private String turnInstruction(float turn) {
		return activity.getString(turn > 0 ? R.string.visual_turn_right : R.string.visual_turn_left,
				roundTo5(Math.abs(turn)));
	}
}
