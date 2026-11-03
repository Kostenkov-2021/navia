# Soundscape simulation: how sounds are chosen and played

The map's point menu has a **Simulate the sound here** item. It plays a 3D, moving soundscape of the
touched place, built only from the downloaded map data around it. This document describes the rules,
as implemented in `app/src/main/java/com/wikiponder/touchmap/classes/soundscape/`:

| File | Role |
|---|---|
| `SoundscapeData` | Reads the map around the point and converts it to listener-relative metres |
| `SceneBuilder` | **Selection rules**: decides which sounds exist, where, how loud, how often |
| `Scene` | The result: beds, spots and lanes (pure data) |
| `SoundscapePlayer` | **Playback rules**: animates vehicles, fires one-shots, follows the compass |
| `SpatialRenderer` + `cpp/soundscape_jni.cpp` | OpenAL Soft (HRTF) rendering and Ogg decoding |
| `tools/generate_soundscape_bank.py` | Generates the sound bank with Stable Audio Open |

Coordinates are metres relative to the listener: **east = +x, north = +y**. Distances below are
straight-line distances from the touched point.

---

## 1. Input: what is read from the map

The listener stands at the touched position (for a street, the point on the street nearest the
finger).

| Query | Radius | Limit |
|---|---|---|
| All feature nodes (not addresses, not intersections), nearest first | 180 m | 1500 |
| Nodes of far-carrying types only (see below), nearest first | 450 m | 300 |
| Ways (streets, paths, platforms, barriers…) whose bounding box overlaps | 240 m | — |

**Far-carrying types** (`SceneBuilder.FAR_TYPES`): waterfall, rapids, weir, dam, lock_gate, river,
stream, canal, spring, hot_spring, water, wetland, beach, bay, cape, cliff, reef, islet,
ferry_terminal, marina, slipway, lighthouse, pier, peak, saddle, viewpoint, volcano, cave_entrance,
wood, camp_site, place_of_worship, station, bus_station, cattle_grid, farm, dairy, animal_breeding,
horse_riding, animal_shelter, dog_park.

### What the map data does *not* contain

The box databases keep only highways, barriers, platforms and addressed buildings as ways. There are
**no rail lines, rivers, lakes, coastlines, parks or land-use areas**. Those are inferred from points
(see each section). OpenStreetMap also never maps animals; they are inferred from the places that
imply them.

If the area has no map data at all, nothing is played and the user is told so.

---

## 2. Scene structure

A scene has three kinds of sound:

- **Bed**: a non-directional background sitting on the listener's head (city murmur, distant
  traffic, countryside, wind).
- **Spot**: a sound fixed at one place. Either a **loop** (plays continuously) or a **one-shot**
  repeated at random intervals between *min* and *max* seconds.
- **Lane**: a path (street, cycleway, track) that **vehicles drive along** as moving sources.

Each spot has a *reference distance*. Within it the sound plays at full gain; beyond it, it fades
with the inverse-distance law (see §6). A larger reference distance means the sound carries further.
At most **2 spots of the same clip** are created from points of the same kind.

---

## 3. Selection rules

### 3.1 Roads and traffic

Every road within 180 m becomes a candidate lane, clipped to the stretch within 240 m of the
listener, so vehicles appear and vanish out of earshot.

| Road class | Vehicles / min | Speed (m/s) | Trucks | Buses | Motorcycles |
|---|---|---|---|---|---|
| motorway | 40 | 30 | 18 % | 2 % | 3 % |
| trunk | 30 | 22 | 14 % | 3 % | 4 % |
| primary | 18 | 14 | 8 % | 6 % | 6 % |
| secondary | 12 | 13 | 6 % | 5 % | 6 % |
| tertiary | 7 | 11 | 4 % | 4 % | 5 % |
| unclassified | 3 | 10 | 4 % | 1 % | 5 % |
| road | 2 | 9 | 3 % | 1 % | 5 % |
| residential | 2 | 8 | 2 % | 0 | 5 % |
| service | 0.6 | 5 | 5 % | 0 | 3 % |
| living_street | 0.4 | 4 | 0 | 0 | 3 % |
| busway | 1.5 | 10 | 0 | 100 % | 0 |

The remaining share is cars. Adjustments:

- `*_link` roads carry 30 % of their parent class's traffic.
- **Lanes/width:** traffic × `lanes / 2`, or × `width / 7 m` if there is no `lanes` tag, clamped to
  0.5–3.
- **Speed:** `maxspeed` × 0.85 (traffic mostly drives below the limit), clamped to 3–36 m/s. mph is
  converted.
- **One-way:** `oneway=yes/1`, motorways and roundabouts drive in one direction only; others in both.
- **Buses:** at least 8 % within 60 m of a bus stop; at least 30 % on `bus=designated` or `psv=yes`.
- **Unpaved surface** (unpaved, gravel, fine_gravel, dirt, ground, earth, compacted, sand, grass,
  pebblestone, mud): all vehicles use the **car_gravel** sound (tyres on gravel), with at least 5 %
  motorcycles, at most 12 m/s.

Only the **5 loudest lanes** are kept, ranked by `vehicles/min ÷ (1 + distance/25 m)`. In addition the
**nearest cycleway within 60 m** always gets its own lane: bicycles at 4/min, 5 m/s.

**Traffic load** = Σ `vehicles/min ÷ (1 + distance/40 m)` over all roads. It drives the beds and
car horns below.

### 3.2 Rail (trains and trams)

Rail lines are not in the data, so the nearest track is reconstructed:

1. **Seed:** the nearest railway node within 180 m that sits on a track (switch, signal,
   level_crossing, crossing, railway_crossing, tram_level_crossing, tram_crossing, tram_stop,
   station, halt, stop, buffer_stop, milestone, derail, …; subway stations excluded).
2. **Cluster:** all such nodes within 140 m of the seed, plus the vertices of railway platform ways
   (`railway=*`, `train=yes` or `tram=yes`) within 140 m.
3. **Fit:** a straight line through the cluster along its principal axis. The line runs 450 m
   either side of its point nearest the listener. It needs at least 2 points spread over more than
   about 5 m.
4. **Tram or train:**
   - Tram evidence: `railway=tram*`, `tram=yes`, tram platforms (counted double).
   - Train evidence: `train=yes`, level_crossing, railway_crossing, station, halt, signal,
     buffer_stop, milestone, train platforms (counted double).
   - Switches and plain crossings count for neither.
   - Tram wins only if its evidence outweighs the train's.

| | Sound | Speed | Frequency | Extra |
|---|---|---|---|---|
| Tram | tram | 8 m/s | 1.2 / min | **tram_bell** once as each tram passes nearest the listener (if within 70 m) |
| Train | train | 16 m/s | 0.6 / min | **crossing_bell** at the nearest level crossing (within 150 m) rings while a train is within 220 m of it |

### 3.3 People and shopping streets

| Clip | When | Where | Gain |
|---|---|---|---|
| footsteps | footway, steps or living street within 60 m (pedestrian streets count ×3); score ≥ 2 | nearest such path | score/10, 0.25–0.8 |
| market | ≥ 4 shops within 70 m | centre of those shops | shops/15, 0.25–0.7 |

### 3.4 Trees and parks

- **Trees** = tree, shrub, tree_row or wood nodes within 90 m.
- **Park points** = leisure park, garden, nature_reserve or picnic_table within 90 m.
- **Park inference:** ≥ 6 benches/picnic tables within 100 m, ≥ 8 footpaths and ≤ 1 motor road
  within 80 m adds `2 + benches/6` park points. Big parks often have few mapped trees but are thick
  with benches and paths.
- **Greenery** = trees + 6 × park points. Nothing is added below 3.

| Clip | Where | Gain |
|---|---|---|
| leaves | centre of the trees | greenery/30, 0.15–0.55 |
| birds | slightly off the trees | greenery/20, 0.15–0.7 |
| pigeons (one-shot, 25–60 s) | near the trees, only if greenery > 25 | 0.5 |

### 3.5 Places

Checked nearest first. A node matches the **first** row that fits. Loops have no interval.

| Map feature | Clip | Max distance | Ref. dist. | Interval (s) |
|---|---|---|---|---|
| amenity cafe, ice_cream | cafe | 70 m | 6 m | loop |
| amenity restaurant, fast_food, food_court | restaurant | 70 m | 6 m | loop |
| amenity nightclub | nightclub | 70 m | 6 m | loop |
| amenity pub, bar, biergarten | pub | 70 m | 6 m | loop |
| shop supermarket, mall, department_store | supermarket | 60 m | 6 m | loop |
| amenity marketplace | market | 120 m | 12 m | loop |
| amenity fuel | fuel_station | 90 m | 8 m | loop |
| amenity car_wash | car_wash | 70 m | 6 m | loop |
| shop car_repair, tyres, car_parts, motorcycle_repair | garage | 70 m | 6 m | loop |
| railway/public_transport station, amenity bus_station | station_hall | 130 m | 15 m | loop |
| tourism camp_site, amenity bbq, leisure firepit | campfire | 50 m | 3 m | loop |
| any other shop within 40 m | shop_bell | 40 m | 3 m | 18–45 |
| amenity school, kindergarten, childcare | schoolyard | 140 m | 15 m | loop |
| leisure playground | playground | 110 m | 10 m | loop |
| leisure pitch, sports_centre, stadium | sports | 140 m | 15 m | loop |
| amenity fountain | fountain | 60 m | 4 m | loop |
| place_of_worship, religion buddhist / shinto / hindu | temple_bell | 300 m | 30 m | 45–120 |
| place_of_worship, religion christian or untagged | church_bells | 350 m | 40 m | 50–110 |
| amenity hospital, fire_station, emergency ambulance_station | siren | 180 m | 50 m | 40–90 |
| bus_stop within 60 m | bus_doors | 60 m | 6 m | 35–70 |
| traffic_signals / crossing with `traffic_signals:sound=yes` | ped_signal | 40 m | 2 m | loop |
| construction tag or landuse | construction | 140 m | 15 m | loop |

Places of worship of other religions (Muslim, Jewish, …) are deliberately **left silent** rather than
given a sound that isn't theirs.

### 3.6 Water and coast

| Map feature | Clip | Max distance | Ref. dist. |
|---|---|---|---|
| waterway waterfall | waterfall | 350 m | 30 m |
| waterway rapids, weir, dam | river | 250 m | 20 m |
| waterway river (label node) | river | 200 m | 15 m |
| waterway canal, lock_gate | harbor (lapping) | 150 m | 10 m |
| natural spring, hot_spring, waterway stream | stream | 80 m | 5 m |
| natural wetland | frogs | 220 m | 20 m |
| natural water | harbor (quiet lapping) | 150 m | 10 m |
| natural cave_entrance | cave_drips | 40 m | 4 m |

**Coast** (all within 450 m):

- **Coast evidence:** beach, bay, reef, islet, marina, slipway, ferry terminal, lighthouse, or a
  ferry platform way.
- **Beach** → **sea_waves** at the beach (ref. 45 m).
- **Cliff, cape or reef** → **rocky_shore**, *only if there is coast evidence*. Many cliffs are
  inland.
- **Marina, slipway, lighthouse or pier** (else a ferry terminal) → **harbor** loop.
- **Seagulls** (one-shot, 15–50 s) at the harbour; otherwise at the beach or rocky coast.
- **Ferry terminal** node, or a platform way with `ferry=yes` / `amenity=ferry_terminal` within
  180 m → **ship_horn** (one-shot, 60–160 s).

**Bridges:** only if none of the above water sounds was found. The nearest bridge within 70 m that
is not a motorway or trunk and is at least 8 m long gets a quiet water sound under it:

- **harbor** lapping in town (a shop within 120 m),
- **stream** in the countryside.

This is a guess, so its gain is low (0.35).

### 3.7 Landscape

| Rule | Clip |
|---|---|
| peak, saddle or volcano within 400 m, or viewpoint within 150 m; traffic load < 6 | **wind_open** bed |
| wood node within 300 m, or ≥ 40 trees within 120 m; traffic load < 4 | **forest** loop |

### 3.8 Animals

All within 300 m. Sounds are placed a little off the point, in the field rather than on the gate.

| Map feature | Clips (one-shots) |
|---|---|
| barrier cattle_grid | cows (40–110 s), sheep (45–120 s) |
| shop farm, shop dairy, amenity animal_breeding | cows, goats (45–120 s), rooster (60–160 s) |
| leisure horse_riding, sport equestrian / horse_racing | horses (35–90 s) |
| amenity animal_shelter / animal_boarding (within 200 m) | kennel_dogs (30–80 s) |
| leisure dog_park (within 120 m) | dog_bark (25–60 s) |

### 3.9 Street life

| Rule | Clip |
|---|---|
| ≥ 2 residential / living streets within 80 m | dog_bark (one-shot, 45–120 s) |
| traffic load > 6 | car_horn (one-shot, 20–55 s) near the busiest street |
| a cycleway lane exists | bicycle_bell (one-shot, 30–80 s) on it |

### 3.10 Background beds

A place is **rural** if all of these hold:

- traffic load ≤ 5;
- ≤ 3 shops/amenities within 180 m (benches and bins don't count);
- at least as many unpaved roads as paved ones.

| Situation | Beds and spots |
|---|---|
| rural | **countryside** bed (0.3), **crickets** loop, **rooster** one-shot (60–160 s) |
| otherwise | **city_hum** bed, gain 0.12 + traffic load × 0.01 (max +0.25) |
| traffic load > 4 | also **traffic_far** bed, gain 0.08 + traffic load × 0.006 (max 0.45) |

---

## 4. Playback rules

### 4.1 Audio pipeline

1. The scene is built off the main thread.
2. Only the clips it uses are decoded, in parallel. Decoding uses stb_vorbis, mono 16-bit, about
   0.1–0.2 s for a typical scene.
3. **OpenAL Soft** renders the scene in loopback mode at **48 kHz** in **20 ms blocks**, with:
   - HRTF: per-ear impulse responses, so each ear gets its own **time delay (interaural phase
     difference)** and spectrum, not just a pan;
   - inverse-distance-clamped attenuation;
   - Doppler with a speed of sound of 343 m/s.
4. The stereo result goes through a soft tanh limiter above 0.7, then to an `AudioTrack` on the
   **Accessibility stream**, like every other sound in the app.

### 4.2 Listener

The listener stands at the touched point and faces the phone's **compass heading** (`AngleManager`
azimuth, the phone's top edge). The heading is updated every block while playing, so turning the
phone turns the scene.

### 4.3 Distance model

```
gain = refDistance / (refDistance + rolloff × (distance − refDistance))   (distance ≥ refDistance)
```

`rolloff` = 1 for every positioned source. Beds are head-relative and never attenuate.

| Source | Ref. distance | Height |
|---|---|---|
| vehicles | 6 m | 0.8 m |
| spots | per rule above | 1.5 m |
| one-shots | per rule above | 2 m |

### 4.4 Loops

Every loop starts at a random offset within its first 5 s, so two sources sharing a clip never play
in lockstep.

### 4.5 Vehicles

- **Arrivals:** random, a Poisson process at the lane's rate. The time to the next vehicle is
  exponential with mean `60 / vehicles-per-minute` s.
- **At start:** each lane is seeded with up to 3 vehicles already on the road, as if the user had
  been standing there a while. The count is drawn from a Poisson distribution with mean
  `rate × lane length / speed`.
- **Vehicle type:** picked by the lane's shares.
- **Direction:** random on two-way lanes; it enters at the matching end.
- **Pitch:** a random 0.92–1.08, so not every car sounds the same.
- **Movement:** each vehicle moves along the polyline at the lane speed. Its velocity is passed to
  OpenAL, which derives the Doppler shift.
- **Edge fade:** gain fades in over the first 40 m and out over the last 40 m of the path, in case a
  lane starts or ends within earshot.
- **Leaving:** the vehicle is removed at the end of the path.
- **Cap:** at most **12 vehicles** at once. Arrivals beyond that are skipped.
- **Pass events** (tram bell): fire once per vehicle when its distance stops shrinking, if its
  closest approach was under 70 m.
- **Warning bell** (level crossing): fades in or out at 2 /s (max gain 0.7) while any train on that
  lane is within 220 m of the crossing.

### 4.6 One-shots

- **First play:** after `0.2 × min + random × min` seconds, so a short listen still hears it.
- **After that:** every `min + random × (max − min)` seconds.
- **Variation:** each play gets a random gain of 75–100 % and a pitch of 0.95–1.05.
- **Clean-up:** finished one-shots are deleted.

### 4.7 Start, stop and exclusivity

- **Start:** the master gain fades in over **1.5 s** to 0.8.
- **Stop:** fades out over **0.4 s**. Stopped by the action button (*Stop the sound simulation of…*),
  Back, or leaving the screen (`onPause`).
- **Exclusivity:** starting the simulation cancels aiming and the keyword filter, and vice versa;
  all three share the action button.
- **One at a time:** only one simulation plays. A new one waits for the previous one to release the
  native engine.

---

## 5. Sound bank

61 mono clips at 44.1 kHz, generated with **Stable Audio Open 1.0** on Replicate by
`tools/generate_soundscape_bank.py`:

- Every prompt asks for a steady field recording.
- The negative prompt excludes music and speech.
- Vehicles are generated as steady engine/rolling noise **without** a pass-by, because movement and
  Doppler come from the renderer.

| Group | Clips |
|---|---|
| Vehicles (loops) | car, car_gravel, truck, bus, motorcycle, bicycle, train, tram |
| Traffic events | car_horn, tram_bell, crossing_bell, bus_doors, bicycle_bell, siren |
| Beds | city_hum, traffic_far, countryside, wind_open |
| Nature | birds, leaves, forest, pigeons, crickets, frogs, cave_drips |
| Water | waterfall, river, stream, sea_waves, rocky_shore, harbor, seagulls, ship_horn, fountain |
| People and places | footsteps, market, supermarket, cafe, restaurant, pub, nightclub, playground, schoolyard, sports, station_hall, fuel_station, car_wash, garage, construction, shop_bell, ped_signal, campfire |
| Bells | church_bells, temple_bell |
| Animals | dog_bark, kennel_dogs, rooster, cows, sheep, goats, horses |

**Processing** (all in the generator script):

1. Trim trailing silence.
2. Remove DC offset.
3. Loops: 1.5 s equal-power crossfade at the loop point. One-shots: short fades.
4. Normalize to −20 dBFS RMS, peak ≤ 0.89.
5. Keep a 16-bit **WAV master** (`tools/soundscape_master/`).
6. Encode the shipped **Ogg Vorbis** file (`assets/soundscape/`).

Vorbis is used rather than AAC/m4a because AAC encoder padding breaks seamless loops.

`assets/soundscape/bank.json` records each clip's kind, length, prompt and seed. Reruns of the
generator only call Replicate for clips whose prompt changed; raw generations are cached in
`tools/soundscape_raw/`.

Licences:

- Stable Audio Open outputs fall under the Stability AI Community License.
- OpenAL Soft is LGPL-2.1, used unmodified as a separate shared library.
- stb_vorbis is public domain.

All three are listed on the Credits screen.

---

## 6. Known limits and future work

- **Geometry:** rail lines are straight local fits. Rivers, lakes, coastlines and parks are inferred
  from points and can be missed or misplaced. The real fix is to include railway, waterway,
  coastline and land-use ways in `tools/build_global_cluster_dbs.py` and rebuild the boxes.
- **No time of day, season or weather:** birds, crickets and schoolyards play at any hour.
- **Traffic volumes** are per-class defaults, not measured counts.
- **Uniform sounds per place type:** every cafe, garage or station sounds the same, apart from
  pitch and gain variation.
