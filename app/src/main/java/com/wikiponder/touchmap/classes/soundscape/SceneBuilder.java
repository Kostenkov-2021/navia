package com.wikiponder.touchmap.classes.soundscape;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides what a place should sound like from the map data around it.
 *
 * <p>Pure Java over listener-relative metres (see {@link Node}/{@link Way}), so it is unit-testable
 * without a device; {@link SoundscapeData} does the db reads and the lat/lon conversion.
 *
 * <p>What the box dbs actually contain shapes the rules here (see tools/build_global_cluster_dbs.py):
 * ways are only highways, barriers, platforms and addressed buildings — <b>no rail lines and no
 * park/landuse polygons</b>. So:
 * <ul>
 *   <li>a rail line is reconstructed from the railway <i>nodes</i> that sit on the track (switches,
 *       signals, crossings, stops) plus platform ways, by fitting a straight line through the
 *       cluster nearest the listener — right locally, but straight, not the real curve;</li>
 *   <li>greenery comes from the density of {@code natural=tree} nodes and leisure points, not from
 *       a park's outline;</li>
 *   <li>a wide river is inferred from a long bridge, and airports aren't in the data at all
 *       ({@link Airports}).</li>
 * </ul>
 */
public final class SceneBuilder {

	public static final String CLIP_CROSSING_BELL = "crossing_bell";

	/** Everything further than this is not heard (and not queried — see SoundscapeData). */
	public static final float RADIUS_M = 180f;
	/** Loud natural features (waterfalls, surf) and landmarks carry further; SoundscapeData queries
	 * just these node types out to this distance. */
	public static final float FAR_RADIUS_M = 450f;
	public static final Set<String> FAR_TYPES = new HashSet<>(Arrays.asList(
			"waterfall", "rapids", "weir", "dam", "lock_gate", "river", "stream", "canal", "spring", "hot_spring",
			"water", "wetland", "beach", "bay", "cape", "cliff", "reef", "islet", "ferry_terminal", "marina",
			"slipway", "lighthouse", "pier", "peak", "saddle", "viewpoint", "volcano", "cave_entrance", "wood",
			"camp_site", "station", "bus_station",
			"cattle_grid", "farm", "dairy", "animal_breeding", "horse_riding", "animal_shelter", "dog_park"));
	/** Bells and gulls carry further still, and are rare enough not to crowd anything out. */
	public static final float VERY_FAR_RADIUS_M = 2000f;
	public static final Set<String> VERY_FAR_TYPES = new HashSet<>(Arrays.asList(
			"place_of_worship", "beach", "bay", "reef", "islet", "marina", "slipway", "ferry_terminal", "lighthouse", "pier"));
	private static final float BELL_RADIUS_M = 1500f;
	/** Airliners are heard this far from an airport. */
	public static final float AIRPORT_RADIUS_M = 4000f;

	/** What the map around the point can't say. */
	public static final class Surroundings {
		static final Surroundings UNKNOWN = new Surroundings(1f, null);

		/** See {@link HornCulture}: 1 is average, more honks more. */
		final float hornFactor;
		/** {east, north} of an airport within {@link #AIRPORT_RADIUS_M}, or null. */
		final float[] airport;

		Surroundings(float hornFactor, float[] airport) {
			this.hornFactor = hornFactor;
			this.airport = airport;
		}
	}

	/** A point feature in listener-relative metres. */
	public static final class Node {
		/** Stable between visits, for choices that shouldn't change every time; 0 if unknown. */
		public final long id;
		public final String type;
		public final Map<String, String> tags;
		public final float east, north;

		public Node(String type, Map<String, String> tags, float east, float north) {
			this(0, type, tags, east, north);
		}

		public Node(long id, String type, Map<String, String> tags, float east, float north) {
			this.id = id;
			this.type = type == null ? "" : type;
			this.tags = tags == null ? Collections.emptyMap() : tags;
			this.east = east;
			this.north = north;
		}

		float dist() {
			return (float) Math.hypot(east, north);
		}

		String tag(String key) {
			String v = tags.get(key);
			return v == null ? "" : v;
		}
	}

	/** A line feature in listener-relative metres. */
	public static final class Way {
		public final String type;
		public final Map<String, String> tags;
		public final float[] east, north;

		public Way(String type, Map<String, String> tags, float[] east, float[] north) {
			this.type = type == null ? "" : type;
			this.tags = tags == null ? Collections.emptyMap() : tags;
			this.east = east;
			this.north = north;
		}

		String tag(String key) {
			String v = tags.get(key);
			return v == null ? "" : v;
		}
	}

	/** Per-class defaults: vehicles per minute (both directions), speed (m/s), truck/bus shares. */
	private static final class RoadClass {
		final float perMinute, speed, trucks, buses, motorcycles;

		RoadClass(float perMinute, float speed, float trucks, float buses, float motorcycles) {
			this.perMinute = perMinute;
			this.speed = speed;
			this.trucks = trucks;
			this.buses = buses;
			this.motorcycles = motorcycles;
		}
	}

	private static final Map<String, RoadClass> ROADS = new HashMap<>();

	static {
		ROADS.put("motorway", new RoadClass(40, 30, 0.18f, 0.02f, 0.03f));
		ROADS.put("trunk", new RoadClass(30, 22, 0.14f, 0.03f, 0.04f));
		ROADS.put("primary", new RoadClass(18, 14, 0.08f, 0.06f, 0.06f));
		ROADS.put("secondary", new RoadClass(12, 13, 0.06f, 0.05f, 0.06f));
		ROADS.put("tertiary", new RoadClass(7, 11, 0.04f, 0.04f, 0.05f));
		ROADS.put("unclassified", new RoadClass(3, 10, 0.04f, 0.01f, 0.05f));
		ROADS.put("road", new RoadClass(2, 9, 0.03f, 0.01f, 0.05f));
		ROADS.put("residential", new RoadClass(2, 8, 0.02f, 0f, 0.05f));
		ROADS.put("service", new RoadClass(0.6f, 5, 0.05f, 0f, 0.03f));
		ROADS.put("living_street", new RoadClass(0.4f, 4, 0f, 0f, 0.03f));
		ROADS.put("busway", new RoadClass(1.5f, 10, 0f, 1f, 0f));
	}

	/** Railway node values that sit on (or right next to) the track itself. */
	private static final Set<String> RAIL_TRACK_NODES = new HashSet<>(Arrays.asList(
			"switch", "signal", "level_crossing", "crossing", "railway_crossing", "tram_level_crossing",
			"tram_crossing", "tram_stop", "station", "halt", "stop", "buffer_stop", "milestone", "derail",
			"railway_crossing", "owner_change", "border", "radio"));

	/** Motor-traffic lanes kept (the loudest/closest); a cycleway gets its own extra slot, so a busy
	 * junction can't crowd out the bicycles that define a place like Amsterdam. */
	private static final int MAX_LANES = 5;
	private static final int MAX_POINT_SPOTS_PER_CLIP = 2;
	private static final int MAX_PLACE_SPOTS = 10;
	/** Road classes a 30 km/h limit makes a quiet street (not a service road or a cycleway). */
	private static final Set<String> CALM_CLASSES = new HashSet<>(Arrays.asList(
			"residential", "living_street", "unclassified", "tertiary", "road"));
	private static final Set<String> ROADS_AND_PATHS = new HashSet<>(Arrays.asList(
			"motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "road", "residential",
			"living_street", "service", "cycleway", "footway", "path", "pedestrian",
			"motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link"));
	private static final float CALM_LIMIT_KMH = 32f; // includes 20 mph
	private static final float CALM_GAP_MIN_S = 10f, CALM_GAP_MAX_S = 20f;
	private static final float FAR_CALM_GAP_MIN_S = 40f, FAR_CALM_GAP_MAX_S = 80f;
	private static final float QUIET_STREET_REACH_M = 45f;
	private static final float WIDE_ROAD_M = 8f;
	private static final float SLOW_CAR_MS = 9f, FAST_CAR_MS = 20f;
	private static final float BICYCLE_BELL_CHANCE = 0.3f;
	private static final int SIGNALS_PER_BEEPING = 5;
	private static final float BELL_REF_M = 120f, BELL_MIN_S = 120f, BELL_MAX_S = 180f;
	private static final float GULL_REF_M = 150f;
	private static final float LONG_BRIDGE_M = 60f, LONG_BRIDGE_REACH_M = 150f;
	private static final float UNPAVED_PATH_REACH_M = 40f;
	private static final float AIRCRAFT_PATH_M = 5000f, AIRCRAFT_GAP_MIN_S = 120f, AIRCRAFT_GAP_MAX_S = 180f;
	private static final float MIN_LOAD_FOR_HORNS = 4f;
	private static final float HORN_INTERVAL_S = 45f, MIN_HORN_INTERVAL_S = 8f, MIN_HORN_FACTOR = 0.2f;

	private SceneBuilder() {
	}

	public static Scene build(List<Node> nodes, List<Way> ways) {
		return build(nodes, ways, Surroundings.UNKNOWN);
	}

	public static Scene build(List<Node> nodes, List<Way> ways, Surroundings around) {
		Scene scene = new Scene();
		// No map data at all: say so rather than invent a countryside (isRural is true of nothing).
		if (nodes.isEmpty() && ways.isEmpty()) return scene;
		List<Node> sorted = new ArrayList<>(nodes);
		sorted.sort(Comparator.comparingDouble(Node::dist));

		float trafficLoad = addRoads(scene, ways, sorted);
		addPedestrianLife(scene, ways, sorted);
		addRail(scene, sorted, ways);
		addGreenery(scene, sorted, ways);
		addPointsOfInterest(scene, sorted);
		addBells(scene, sorted);
		addWater(scene, sorted, ways);
		addLandscape(scene, sorted, trafficLoad);
		addAnimals(scene, sorted);
		addCasualEvents(scene, ways, trafficLoad, around.hornFactor);
		addQuietStreetLife(scene, ways);
		addUnpavedSteps(scene, ways);
		addAircraft(scene, around.airport);

		// Always some air: never digitally silent, or the gaps between cars sound like the audio
		// dropped out. Rural places (little traffic, few shops, unpaved roads) get countryside air
		// and farm sounds instead of city murmur.
		if (isRural(sorted, ways, trafficLoad)) {
			scene.beds.add(new Scene.Bed("countryside", 0.3f));
			scene.spots.add(new Scene.Spot("crickets", 25, -20, 0.35f, 20f, 0, 0));
			scene.spots.add(new Scene.Spot("rooster", -60, 40, 0.45f, 15f, 60, 160));
		} else {
			scene.beds.add(new Scene.Bed("city_hum", 0.12f + Math.min(0.25f, trafficLoad * 0.01f)));
		}
		if (trafficLoad > 4) {
			scene.beds.add(new Scene.Bed("traffic_far", Math.min(0.45f, 0.08f + trafficLoad * 0.006f)));
		}
		scene.beds.add(new Scene.Bed("air_noise", 0.1f));
		return scene;
	}

	// ------------------------------------------------------------------ roads

	/** Returns a rough "how much traffic is around" figure, used for the beds and the horns. */
	private static float addRoads(Scene scene, List<Way> ways, List<Node> nodes) {
		List<Scene.Lane> candidates = new ArrayList<>();
		List<Float> dists = new ArrayList<>();
		float load = 0;
		boolean busStopNear = false;
		for (Node n : nodes) {
			if (n.dist() < 60 && "bus_stop".equals(n.type)) {
				busStopNear = true;
				break;
			}
		}
		for (Way w : ways) {
			String hw = w.type;
			boolean link = hw.endsWith("_link");
			RoadClass rc = ROADS.get(link ? hw.substring(0, hw.length() - 5) : hw);
			if (rc == null) continue;
			float d = minDistance(w.east, w.north);
			if (d > RADIUS_M) continue;

			float perMinute = rc.perMinute * (link ? 0.3f : 1f) * laneFactor(w);
			float speed = speedOf(w, rc.speed);
			int direction = drivingDirection(w);
			boolean heavyAllowed = isWide(w) && !"no".equals(w.tag("hgv"));
			float trucks = heavyAllowed ? rc.trucks : 0f;
			boolean busRoute = "designated".equals(w.tag("bus")) || "yes".equals(w.tag("psv"));
			boolean stopHere = busStopNear && d < 60;
			// A bus only turns into a narrow street that has a stop or a bus route of its own.
			float buses = heavyAllowed || stopHere || busRoute || "busway".equals(hw) ? rc.buses : 0f;
			if (stopHere && buses < 0.08f) buses = 0.08f;
			if (busRoute) buses = Math.max(buses, 0.3f);

			List<Scene.Vehicle> vehicles = new ArrayList<>();
			float cars = 1f - trucks - buses - rc.motorcycles;
			if (isUnpaved(w)) {
				// Tyres on gravel/dirt are the dominant sound there, whatever the vehicle.
				vehicles.add(new Scene.Vehicle("car_gravel", cars + trucks + buses, 0.9f));
				vehicles.add(new Scene.Vehicle("motorcycle", Math.max(0.05f, rc.motorcycles), 0.8f, 1.1f));
				speed = Math.min(speed, 12f);
			} else {
				if (cars > 0) addCars(vehicles, cars, speed);
				if (trucks > 0) vehicles.add(new Scene.Vehicle("truck", trucks, 1.0f, 0.9f));
				if (buses > 0) vehicles.add(new Scene.Vehicle("bus", buses, 1.0f, 0.85f));
				if (rc.motorcycles > 0) vehicles.add(new Scene.Vehicle("motorcycle", rc.motorcycles, 0.8f, 1.1f));
			}

			float[][] clipped = clipToRadius(w.east, w.north, RADIUS_M + 60);
			if (clipped == null) continue;
			if (direction < 0) {
				reverse(clipped[0]);
				reverse(clipped[1]);
			}
			Scene.Lane lane = new Scene.Lane(hw, clipped[0], clipped[1], speed, perMinute, direction != 0, vehicles, null, null);
			lane.engine = true;
			lane.calm = isCalm(w);
			candidates.add(lane);
			dists.add(d);
			load += perMinute / (1f + d / 40f);
		}
		// The loudest/closest few only: each lane can hold several moving voices at once.
		Integer[] order = new Integer[candidates.size()];
		for (int i = 0; i < order.length; i++) order[i] = i;
		Arrays.sort(order, Comparator.comparingDouble(i -> -candidates.get(i).perMinute / (1f + dists.get(i) / 25f)));
		Scene.Lane nearestCalm = null;
		float nearestCalmDist = Float.MAX_VALUE;
		for (int i = 0; i < Math.min(MAX_LANES, order.length); i++) {
			Scene.Lane lane = candidates.get(order[i]);
			scene.lanes.add(lane);
			if (lane.calm) {
				// The other quiet streets around are heard only now and then.
				lane.gapMin = FAR_CALM_GAP_MIN_S;
				lane.gapMax = FAR_CALM_GAP_MAX_S;
				if (dists.get(order[i]) < nearestCalmDist) {
					nearestCalmDist = dists.get(order[i]);
					nearestCalm = lane;
				}
			}
		}
		if (nearestCalm != null) {
			nearestCalm.gapMin = CALM_GAP_MIN_S;
			nearestCalm.gapMax = CALM_GAP_MAX_S;
		}

		Way nearestCycleway = null;
		float cyclewayDist = 60;
		for (Way w : ways) {
			if (!isCyclePath(w)) continue;
			float d = minDistance(w.east, w.north);
			if (d < cyclewayDist) {
				cyclewayDist = d;
				nearestCycleway = w;
			}
		}
		if (nearestCycleway != null) {
			float[][] clipped = clipToRadius(nearestCycleway.east, nearestCycleway.north, 120);
			if (clipped != null) {
				int direction = drivingDirection(nearestCycleway);
				if (direction < 0) {
					reverse(clipped[0]);
					reverse(clipped[1]);
				}
				// Bicycles only, each one on its own speed, and now and then one rings as it passes.
				Scene.Lane lane = new Scene.Lane("cycleway", clipped[0], clipped[1], 5f, 4f, direction != 0,
						Arrays.asList(new Scene.Vehicle("bicycle", 0.5f, 0.7f, 0.85f), new Scene.Vehicle("bicycle_b", 0.5f, 0.7f, 1.2f)),
						"bicycle_bell", null);
				lane.passChance = BICYCLE_BELL_CHANCE;
				scene.lanes.add(lane);
			}
		}
		return load;
	}

	/** Two recordings of a car, chosen so a slow street and a motorway don't sound alike. */
	private static void addCars(List<Scene.Vehicle> vehicles, float share, float speed) {
		String main = speed <= SLOW_CAR_MS ? "car_slow" : (speed >= FAST_CAR_MS ? "car_fast" : "car");
		vehicles.add(new Scene.Vehicle(main, share * 0.65f, 0.9f));
		vehicles.add(new Scene.Vehicle("car_b", share * 0.35f, 0.85f));
	}

	private static boolean isCyclePath(Way w) {
		return "cycleway".equals(w.type)
				|| (("path".equals(w.type) || "footway".equals(w.type)) && "designated".equals(w.tag("bicycle")));
	}

	/** 1 along the way's own direction, -1 against it, 0 both ways. */
	static int drivingDirection(Way w) {
		String oneway = w.tag("oneway");
		if (oneway.equals("yes") || oneway.equals("1") || oneway.equals("true")) return 1;
		if (oneway.equals("-1") || oneway.equals("reverse")) return -1;
		if (oneway.equals("no") || oneway.equals("false")) return 0;
		String junction = w.tag("junction");
		return w.type.startsWith("motorway") || junction.equals("roundabout") || junction.equals("circular") ? 1 : 0;
	}

	private static void reverse(float[] a) {
		for (int i = 0, j = a.length - 1; i < j; i++, j--) {
			float t = a[i];
			a[i] = a[j];
			a[j] = t;
		}
	}

	/** Wide enough for lorries: tagged so, or a main road if nothing says otherwise. */
	static boolean isWide(Way w) {
		float width = parseFloat(w.tag("width"), 0);
		if (width > 0) return width >= WIDE_ROAD_M;
		float lanes = parseFloat(w.tag("lanes"), 0);
		if (lanes > 0) return lanes >= 3 || (lanes >= 2 && drivingDirection(w) != 0);
		return w.type.startsWith("motorway") || w.type.startsWith("trunk") || w.type.startsWith("primary")
				|| w.type.startsWith("secondary");
	}

	/** A 30 km/h (or slower) street or a living street; the same streets where children play. */
	static boolean isCalm(Way w) {
		if ("living_street".equals(w.type)) return true;
		if (!CALM_CLASSES.contains(w.type)) return false;
		float kmh = speedLimitKmh(w);
		return kmh > 0 && kmh <= CALM_LIMIT_KMH;
	}

	/** The way's speed limit in km/h, 0 if it says nothing usable. */
	static float speedLimitKmh(Way w) {
		float kmh = parseLimit(w.tag("maxspeed"));
		if (kmh <= 0) kmh = parseLimit(w.tag("zone:maxspeed"));
		if (kmh <= 0) kmh = parseLimit(w.tag("maxspeed:type"));
		if (kmh <= 0) kmh = parseLimit(w.tag("source:maxspeed"));
		return kmh;
	}

	/** "30", "20 mph", "30;50", "walk", "none", and country-coded "DE:urban", "DE:zone30", "FR:zone:30". */
	private static float parseLimit(String value) {
		String s = value.trim().toLowerCase(java.util.Locale.ROOT);
		if (s.isEmpty()) return 0;
		if (s.equals("walk")) return 7;
		if (s.equals("none")) return 130;
		int colon = s.indexOf(':');
		if (colon > 0 && colon <= 3) {
			String kind = s.substring(colon + 1);
			if (kind.contains("urban")) return 50;
			if (kind.contains("rural")) return 90;
			if (kind.contains("motorway")) return 120;
			if (kind.contains("living_street")) return 10;
			if (kind.contains("zone") || kind.contains("30")) {
				String digits = kind.replaceAll("[^0-9]", "");
				return parseFloat(digits, 0);
			}
			return 0;
		}
		float n = parseFloat(s, 0);
		return s.contains("mph") ? n * 1.609f : n;
	}

	private static final Set<String> UNPAVED = new HashSet<>(Arrays.asList(
			"unpaved", "gravel", "fine_gravel", "dirt", "ground", "earth", "compacted", "sand", "grass", "pebblestone", "mud"));

	static boolean isUnpaved(Way w) {
		return UNPAVED.contains(w.tag("surface"));
	}

	/** Little traffic, hardly any shops or amenities nearby, and mostly unpaved roads (or none). */
	private static boolean isRural(List<Node> nodes, List<Way> ways, float trafficLoad) {
		if (trafficLoad > 5) return false;
		int places = 0;
		for (Node n : nodes) {
			if (n.dist() > RADIUS_M) break;
			if (n.tags.containsKey("shop") || (n.tags.containsKey("amenity")
					&& !"bench".equals(n.type) && !"waste_basket".equals(n.type))) places++;
		}
		int unpaved = 0, paved = 0;
		for (Way w : ways) {
			if (!ROADS.containsKey(w.type) || minDistance(w.east, w.north) > RADIUS_M) continue;
			if (isUnpaved(w)) unpaved++;
			else paved++;
		}
		return places <= 3 && unpaved >= paved;
	}

	private static float laneFactor(Way w) {
		float lanes = parseFloat(w.tag("lanes"), 0);
		if (lanes > 0) return clamp(lanes / 2f, 0.5f, 3f);
		float width = parseFloat(w.tag("width"), 0);
		if (width > 0) return clamp(width / 7f, 0.5f, 3f);
		return 1f;
	}

	/** By the speed limit; failing that, wider streets of a class are driven faster. */
	static float speedOf(Way w, float classSpeed) {
		float kmh = speedLimitKmh(w);
		// Traffic in a city mostly drives below the limit.
		if (kmh > 0) return clamp(kmh / 3.6f * 0.85f, 3f, 36f);
		float width = parseFloat(w.tag("width"), 0);
		if (width <= 0) width = parseFloat(w.tag("lanes"), 0) * 3.2f;
		if (width <= 0) return classSpeed;
		return clamp(classSpeed * clamp(width / 7f, 0.7f, 1.3f), 3f, 36f);
	}

	// ------------------------------------------------------------------ people

	private static void addPedestrianLife(Scene scene, List<Way> ways, List<Node> nodes) {
		float bestDist = Float.MAX_VALUE;
		float[] best = null;
		int count = 0;
		for (Way w : ways) {
			boolean pedestrian = "pedestrian".equals(w.type);
			if (!pedestrian && !"footway".equals(w.type) && !"steps".equals(w.type) && !"living_street".equals(w.type)) continue;
			float[] p = nearestPoint(w.east, w.north);
			float d = (float) Math.hypot(p[0], p[1]);
			if (d > 60) continue;
			count += pedestrian ? 3 : 1;
			if (d < bestDist) {
				bestDist = d;
				best = p;
			}
		}
		int shops = 0;
		float sx = 0, sy = 0;
		for (Node n : nodes) {
			if (n.dist() > 70) break;
			if (n.tags.containsKey("shop")) {
				shops++;
				sx += n.east;
				sy += n.north;
			}
		}
		if (best != null && count >= 2) {
			scene.spots.add(new Scene.Spot("footsteps", best[0], best[1], clamp(count / 10f, 0.25f, 0.8f), 6f, 0, 0));
		}
		if (shops >= 4) {
			scene.spots.add(new Scene.Spot("market", sx / shops, sy / shops, clamp(shops / 15f, 0.25f, 0.7f), 10f, 0, 0));
		}
	}

	// ------------------------------------------------------------------ rail

	private static boolean isRailNode(Node n) {
		String r = n.tag("railway");
		return !r.isEmpty() && RAIL_TRACK_NODES.contains(r) && !"subway".equals(n.tag("station"));
	}

	/** +1 for tram evidence, -1 for mainline-train evidence, 0 for nodes both kinds of track have
	 * (switches and track crossings are everywhere on Amsterdam's tram network, for example). */
	private static int tramEvidence(Node n) {
		String r = n.tag("railway");
		if (r.startsWith("tram") || "yes".equals(n.tag("tram"))) return 1;
		if ("yes".equals(n.tag("train")) || r.equals("level_crossing") || r.equals("railway_crossing")
				|| r.equals("station") || r.equals("halt") || r.equals("signal") || r.equals("buffer_stop")
				|| r.equals("milestone")) return -1;
		return 0;
	}

	/**
	 * Fits the nearest track as a straight line through the railway nodes clustered around the one
	 * closest to the listener (principal axis of their positions), then runs trains or trams along it.
	 */
	private static void addRail(Scene scene, List<Node> nodes, List<Way> ways) {
		Node seed = null;
		for (Node n : nodes) {
			if (n.dist() > RADIUS_M) break;
			if (isRailNode(n)) {
				seed = n;
				break;
			}
		}
		List<float[]> pts = new ArrayList<>();
		int tram = 0, train = 0;
		float[] crossing = null;
		if (seed != null) {
			for (Node n : nodes) {
				if (!isRailNode(n)) continue;
				if (Math.hypot(n.east - seed.east, n.north - seed.north) > 140) continue;
				pts.add(new float[]{n.east, n.north});
				int e = tramEvidence(n);
				if (e > 0) tram++;
				else if (e < 0) train++;
				String r = n.tag("railway");
				if (crossing == null && (r.equals("level_crossing") || r.equals("crossing") || r.equals("railway_crossing")) && n.dist() < 150) {
					crossing = new float[]{n.east, n.north};
				}
			}
		}
		// Platforms run alongside the track — their long axis is the best direction evidence we have.
		for (Way w : ways) {
			if (!"platform".equals(w.type)) continue;
			boolean rail = !w.tag("railway").isEmpty() || "yes".equals(w.tag("train")) || "yes".equals(w.tag("tram"));
			if (!rail || minDistance(w.east, w.north) > RADIUS_M) continue;
			if (seed != null && minDistanceTo(w.east, w.north, seed.east, seed.north) > 140) continue;
			for (int i = 0; i < w.east.length; i++) pts.add(new float[]{w.east[i], w.north[i]});
			if ("yes".equals(w.tag("tram"))) tram += 2;
			else if ("yes".equals(w.tag("train"))) train += 2;
		}
		if (pts.size() < 2) return;

		float cx = 0, cy = 0;
		for (float[] p : pts) {
			cx += p[0];
			cy += p[1];
		}
		cx /= pts.size();
		cy /= pts.size();
		float sxx = 0, syy = 0, sxy = 0;
		for (float[] p : pts) {
			float dx = p[0] - cx, dy = p[1] - cy;
			sxx += dx * dx;
			syy += dy * dy;
			sxy += dx * dy;
		}
		if (sxx + syy < 25f) return; // all points on top of each other: no direction to speak of
		double angle = 0.5 * Math.atan2(2 * sxy, sxx - syy);
		float ux = (float) Math.cos(angle), uy = (float) Math.sin(angle);
		// Closest point of the fitted line to the listener, then 450 m either way.
		float t = -(cx * ux + cy * uy);
		float px = cx + t * ux, py = cy + t * uy;
		float half = 450f;
		float[] east = {px - ux * half, px + ux * half};
		float[] north = {py - uy * half, py + uy * half};

		// No evidence either way (only switches/crossings): a train is the safer guess outside a
		// city centre, but any tram evidence at all wins over none.
		boolean isTram = tram > train;
		List<Scene.Vehicle> v = Collections.singletonList(isTram
				? new Scene.Vehicle("tram", 1f, 1.1f)
				: new Scene.Vehicle("train", 1f, 1.3f));
		scene.lanes.add(0, new Scene.Lane(isTram ? "tram" : "rail", east, north, isTram ? 8f : 16f,
				isTram ? 1.2f : 0.6f, false, v, isTram ? "tram_bell" : null, isTram ? null : crossing));
	}

	// ------------------------------------------------------------------ nature

	private static void addGreenery(Scene scene, List<Node> nodes, List<Way> ways) {
		int trees = 0;
		float tx = 0, ty = 0;
		int parks = 0;
		for (Node n : nodes) {
			float d = n.dist();
			if (d > 90) break;
			if ("tree".equals(n.type) || "shrub".equals(n.type) || "tree_row".equals(n.type) || "wood".equals(n.type)) {
				trees++;
				tx += n.east;
				ty += n.north;
			} else if (n.tags.containsKey("leisure") && (n.type.equals("park") || n.type.equals("garden")
					|| n.type.equals("nature_reserve") || n.type.equals("picnic_table"))) {
				parks++;
			}
		}
		// Parks are polygons the box dbs don't carry (see the class doc), and big ones often have few
		// mapped trees — but they are thick with benches and footpaths and have no motor roads.
		int benches = 0;
		for (Node n : nodes) {
			if (n.dist() > 100) break;
			if ("bench".equals(n.type) || "picnic_table".equals(n.type)) benches++;
		}
		int paths = 0, motorRoads = 0;
		for (Way w : ways) {
			if (minDistance(w.east, w.north) > 80) continue;
			if ("footway".equals(w.type) || "path".equals(w.type) || "pedestrian".equals(w.type)) paths++;
			else if (ROADS.containsKey(w.type) && !"service".equals(w.type)) motorRoads++;
		}
		if (benches >= 6 && paths >= 8 && motorRoads <= 1) parks += 2 + benches / 6;
		float green = trees + parks * 6;
		if (green < 3) return;
		float cx = trees > 0 ? tx / trees : 0, cy = trees > 0 ? ty / trees : 0;
		scene.spots.add(new Scene.Spot("leaves", cx, cy, clamp(green / 30f, 0.15f, 0.55f), 15f, 0, 0));
		// Birds a little off the leaves, so the two aren't one blob in the same spot.
		scene.spots.add(new Scene.Spot("birds", cx * 0.6f + 12, cy * 0.6f + 8, clamp(green / 20f, 0.15f, 0.7f), 15f, 0, 0));
		if (green > 25) {
			scene.spots.add(new Scene.Spot("pigeons", cx - 10, cy + 5, 0.5f, 6f, 25, 60));
		}
	}

	// ------------------------------------------------------------------ points of interest

	private static void addPointsOfInterest(Scene scene, List<Node> nodes) {
		Map<String, Integer> used = new HashMap<>();
		int places = 0;
		for (Node n : nodes) {
			float d = n.dist();
			if (d > FAR_RADIUS_M) break;
			String amenity = n.tag("amenity");
			String leisure = n.tag("leisure");
			String shop = n.tag("shop");
			String clip = null;
			float gain = 0.6f, ref = 6f, maxD = 70f, minI = 0, maxI = 0;
			String craft = n.tag("craft"), tourism = n.tag("tourism");
			if (amenity.equals("cafe") || amenity.equals("ice_cream")) {
				clip = "cafe";
			} else if (amenity.equals("restaurant") || amenity.equals("fast_food") || amenity.equals("food_court")) {
				clip = "restaurant";
			} else if (amenity.equals("nightclub")) {
				clip = "nightclub";
				gain = 0.5f;
			} else if (amenity.equals("pub") || amenity.equals("bar") || amenity.equals("biergarten")) {
				clip = "pub";
			} else if (shop.equals("supermarket") || shop.equals("mall") || shop.equals("department_store")) {
				clip = "supermarket";
				gain = 0.55f;
				maxD = 60f;
			} else if (amenity.equals("marketplace")) {
				clip = "market";
				ref = 12f;
				maxD = 120f;
			} else if (amenity.equals("fuel")) {
				clip = "fuel_station";
				ref = 8f;
				maxD = 90f;
			} else if (amenity.equals("car_wash")) {
				clip = "car_wash";
				gain = 0.5f;
			} else if (shop.equals("car_repair") || shop.equals("tyres") || shop.equals("car_parts")
					|| shop.equals("motorcycle_repair")) {
				clip = "garage";
				gain = 0.5f;
			} else if (n.tag("railway").equals("station") || n.tag("public_transport").equals("station")
					|| amenity.equals("bus_station")) {
				clip = "station_hall";
				gain = 0.5f;
				ref = 15f;
				maxD = 130f;
			} else if (n.tag("tourism").equals("camp_site") || amenity.equals("bbq") || leisure.equals("firepit")) {
				clip = "campfire";
				gain = 0.5f;
				ref = 3f;
				maxD = 50f;
			} else if (shop.equals("hairdresser") || shop.equals("beauty")) {
				clip = "hairdresser";
				gain = 0.45f;
				ref = 4f;
				maxD = 40f;
			} else if (shop.equals("bakery") || shop.equals("pastry") || shop.equals("confectionery")) {
				clip = "bakery";
				gain = 0.45f;
				ref = 4f;
				maxD = 40f;
			} else if (shop.equals("butcher") || shop.equals("seafood")) {
				clip = "butcher";
				gain = 0.5f;
				ref = 4f;
				maxD = 45f;
				minI = 20;
				maxI = 50;
			} else if (shop.equals("greengrocer")) {
				clip = "greengrocer";
				gain = 0.5f;
				maxD = 50f;
			} else if (shop.equals("laundry") || shop.equals("dry_cleaning")) {
				clip = "laundry";
				gain = 0.4f;
				ref = 4f;
				maxD = 35f;
			} else if (shop.equals("tailor") || craft.equals("tailor") || craft.equals("dressmaker")) {
				clip = "sewing";
				gain = 0.4f;
				ref = 3f;
				maxD = 30f;
			} else if (!craft.isEmpty() || shop.equals("hardware") || shop.equals("doityourself")) {
				clip = "workshop";
				gain = 0.45f;
				maxD = 50f;
			} else if (shop.equals("bicycle") || amenity.equals("bicycle_repair_station")) {
				clip = "bicycle_repair";
				gain = 0.5f;
				ref = 4f;
				maxD = 40f;
				minI = 25;
				maxI = 60;
			} else if (shop.equals("clothes") || shop.equals("shoes") || shop.equals("bag") || shop.equals("fashion")) {
				clip = "clothes_shop";
				gain = 0.4f;
				ref = 3f;
				maxD = 30f;
				minI = 20;
				maxI = 50;
			} else if (shop.equals("electronics") || shop.equals("mobile_phone") || shop.equals("computer")
					|| shop.equals("appliance")) {
				clip = "electronics_shop";
				gain = 0.4f;
				ref = 3f;
				maxD = 30f;
				minI = 20;
				maxI = 50;
			} else if (shop.equals("kiosk") || shop.equals("tobacco") || shop.equals("newsagent")) {
				clip = "kiosk";
				gain = 0.45f;
				ref = 3f;
				maxD = 30f;
				minI = 25;
				maxI = 60;
			} else if (shop.equals("pet")) {
				clip = "pet_shop";
				gain = 0.45f;
				ref = 3f;
				maxD = 35f;
				minI = 25;
				maxI = 60;
			} else if (!shop.isEmpty() && d < 40) {
				// Small shops: a door bell now and then, from whichever shops are closest.
				clip = "shop_bell";
				gain = 0.45f;
				ref = 3f;
				maxD = 40f;
				minI = 18;
				maxI = 45;
			} else if (amenity.equals("school") || amenity.equals("kindergarten") || amenity.equals("childcare")) {
				clip = "schoolyard";
				gain = 0.6f;
				ref = 15f;
				maxD = 140f;
			} else if (leisure.equals("playground")) {
				clip = "playground";
				ref = 10f;
				maxD = 110f;
			} else if (leisure.equals("stadium")) {
				clip = "stadium";
				ref = 50f;
				maxD = RADIUS_M;
			} else if (amenity.equals("atm") || amenity.equals("bank")) {
				clip = "atm";
				gain = 0.45f;
				ref = 3f;
				maxD = 30f;
				minI = 30;
				maxI = 70;
			} else if (amenity.equals("recycling")) {
				clip = "recycling_glass";
				gain = 0.5f;
				ref = 8f;
				maxD = 60f;
				minI = 50;
				maxI = 130;
			} else if (amenity.equals("vending_machine")) {
				clip = "vending";
				gain = 0.45f;
				ref = 3f;
				maxD = 25f;
				minI = 45;
				maxI = 110;
			} else if (amenity.equals("parking") || amenity.equals("parking_entrance") || amenity.equals("taxi")
					|| amenity.equals("car_rental") || amenity.equals("car_sharing")) {
				clip = "car_door";
				gain = 0.5f;
				maxD = 60f;
				minI = 30;
				maxI = 80;
			} else if (leisure.equals("fitness_centre") || leisure.equals("sports_hall")) {
				clip = "gym";
				gain = 0.4f;
				ref = 5f;
				maxD = 40f;
			} else if ((leisure.equals("swimming_pool") && !n.tag("access").equals("private")) || leisure.equals("water_park")) {
				// Garden pools are private and silent.
				clip = "swimming";
				gain = 0.5f;
				ref = 10f;
				maxD = 90f;
			} else if (amenity.equals("cinema") || amenity.equals("theatre") || amenity.equals("arts_centre")
					|| amenity.equals("events_venue")) {
				clip = "cinema_lobby";
				gain = 0.4f;
				maxD = 50f;
			} else if (tourism.equals("hotel") || tourism.equals("hostel") || tourism.equals("guest_house")) {
				clip = "hotel";
				gain = 0.45f;
				ref = 4f;
				maxD = 40f;
				minI = 30;
				maxI = 75;
			} else if (amenity.equals("university") || amenity.equals("college")) {
				clip = "university";
				gain = 0.5f;
				ref = 15f;
				maxD = 120f;
			} else if (tourism.equals("theme_park")) {
				clip = "theme_park";
				ref = 40f;
				maxD = RADIUS_M;
			} else if (tourism.equals("zoo")) {
				clip = "zoo";
				ref = 30f;
				maxD = RADIUS_M;
				minI = 30;
				maxI = 70;
			} else if (leisure.equals("amusement_arcade") || leisure.equals("adult_gaming_centre")) {
				clip = "arcade";
				gain = 0.4f;
				ref = 4f;
				maxD = 35f;
			} else if (leisure.equals("pitch") || leisure.equals("sports_centre")) {
				clip = "sports";
				ref = 15f;
				maxD = 140f;
			} else if (amenity.equals("fountain")) {
				clip = "fountain";
				gain = 0.7f;
				ref = 4f;
				maxD = 60f;
			} else if (amenity.equals("hospital") || amenity.equals("fire_station") || n.tag("emergency").equals("ambulance_station")) {
				clip = "siren";
				gain = 0.45f;
				ref = 50f;
				maxD = RADIUS_M;
				minI = 40;
				maxI = 90;
			} else if ("bus_stop".equals(n.type) && d < 60) {
				clip = "bus_doors";
				gain = 0.6f;
				minI = 35;
				maxI = 70;
			} else if (beepsForPedestrians(n)) {
				// Some ticks, some beeps; the same light always sounds the same.
				clip = Math.floorMod(n.id, 2L) == 0 ? "ped_beep" : "ped_signal";
				gain = 0.4f;
				ref = 2f;
				maxD = 40f;
			} else if (n.tags.containsKey("construction") || "construction".equals(n.tag("landuse"))) {
				clip = "construction";
				ref = 15f;
				maxD = 140f;
			}
			if (clip == null || d > maxD) continue;
			int count = used.getOrDefault(clip, 0);
			if (count >= MAX_POINT_SPOTS_PER_CLIP) continue;
			used.put(clip, count + 1);
			scene.spots.add(new Scene.Spot(clip, n.east, n.north, gain, ref, minI, maxI));
			// A dense shopping street has dozens of kinds of place; the nearest few are plenty.
			if (++places >= MAX_PLACE_SPOTS) return;
		}
	}

	/** Tagged lights say so themselves; of the many without a tag, one in {@value #SIGNALS_PER_BEEPING}. */
	static boolean beepsForPedestrians(Node n) {
		if (!"traffic_signals".equals(n.type) && !"traffic_signals".equals(n.tag("crossing"))) return false;
		String sound = n.tag("traffic_signals:sound");
		if (sound.equals("no")) return false;
		if (!sound.isEmpty()) return true;
		return n.id != 0 && Math.floorMod(n.id * 31, (long) SIGNALS_PER_BEEPING) == 0;
	}

	/**
	 * One bell tower at most, however many are around, so bells stay rare. Places of other religions,
	 * and places that don't say, stay silent rather than get a sound that isn't theirs.
	 */
	private static void addBells(Scene scene, List<Node> nodes) {
		boolean church = false, temple = false;
		for (Node n : nodes) {
			if (n.dist() > BELL_RADIUS_M) break;
			if (!n.tag("amenity").equals("place_of_worship")) continue;
			String religion = n.tag("religion");
			if (!church && religion.equals("christian")) {
				church = true;
				scene.spots.add(new Scene.Spot("church_bells", n.east, n.north, 0.7f, BELL_REF_M, BELL_MIN_S, BELL_MAX_S));
			} else if (!temple && (religion.equals("buddhist") || religion.equals("shinto") || religion.equals("hindu"))) {
				temple = true;
				scene.spots.add(new Scene.Spot("temple_bell", n.east, n.north, 0.7f, BELL_REF_M, BELL_MIN_S, BELL_MAX_S));
			}
		}
	}

	// ------------------------------------------------------------------ water and landscape

	/**
	 * Water. Rivers, lakes and the sea are lines/areas the box dbs don't carry, so this goes by the
	 * points that mark them — waterfalls, rapids and weirs, springs, beaches, harbours — and, for a
	 * river or canal under a bridge, by the bridge itself.
	 */
	private static void addWater(Scene scene, List<Node> nodes, List<Way> ways) {
		Map<String, Integer> used = new HashMap<>();
		boolean coast = false;
		Node beach = null, rocks = null, harbour = null, ferry = null, seaSide = null;
		for (Node n : nodes) {
			if (n.dist() > VERY_FAR_RADIUS_M) break;
			if (isSeaSide(n)) {
				seaSide = n;
				break;
			}
		}
		for (Node n : nodes) {
			float d = n.dist();
			if (d > FAR_RADIUS_M) break;
			String natural = n.tag("natural"), waterway = n.tag("waterway"), amenity = n.tag("amenity");
			String manMade = n.tag("man_made"), leisure = n.tag("leisure");
			if (waterway.equals("waterfall") && d < 350) {
				addOnce(scene, used, new Scene.Spot("waterfall", n.east, n.north, 0.85f, 30f, 0, 0));
			} else if ((waterway.equals("rapids") || waterway.equals("weir") || waterway.equals("dam")) && d < 250) {
				addOnce(scene, used, new Scene.Spot("river", n.east, n.north, 0.7f, 20f, 0, 0));
			} else if (waterway.equals("river") && d < 200) {
				addOnce(scene, used, new Scene.Spot("river", n.east, n.north, 0.5f, 15f, 0, 0));
			} else if ((waterway.equals("canal") || waterway.equals("lock_gate")) && d < 150) {
				addOnce(scene, used, new Scene.Spot("harbor", n.east, n.north, 0.35f, 10f, 0, 0));
			} else if ((natural.equals("spring") || natural.equals("hot_spring") || waterway.equals("stream")) && d < 80) {
				addOnce(scene, used, new Scene.Spot("stream", n.east, n.north, 0.55f, 5f, 0, 0));
			} else if (natural.equals("wetland") && d < 220) {
				addOnce(scene, used, new Scene.Spot("frogs", n.east, n.north, 0.5f, 20f, 0, 0));
			} else if (natural.equals("water") && d < 150) {
				addOnce(scene, used, new Scene.Spot("harbor", n.east, n.north, 0.3f, 10f, 0, 0));
			} else if (natural.equals("cave_entrance") && d < 40) {
				addOnce(scene, used, new Scene.Spot("cave_drips", n.east, n.north, 0.6f, 4f, 0, 0));
			}
			if (natural.equals("beach") && beach == null) beach = n;
			if ((natural.equals("cliff") || natural.equals("cape") || natural.equals("reef")) && rocks == null) rocks = n;
			if ((leisure.equals("marina") || leisure.equals("slipway") || manMade.equals("lighthouse")
					|| manMade.equals("pier")) && harbour == null) harbour = n;
			if (amenity.equals("ferry_terminal") && ferry == null) ferry = n;
			if (natural.equals("beach") || natural.equals("bay") || natural.equals("reef") || natural.equals("islet")
					|| leisure.equals("marina") || leisure.equals("slipway") || amenity.equals("ferry_terminal")
					|| manMade.equals("lighthouse")) coast = true;
		}
		// Ferry piers are often mapped as platform ways rather than a terminal node (Amsterdam's IJ
		// ferries behind Centraal, for one).
		if (ferry == null) {
			float best = RADIUS_M;
			for (Way w : ways) {
				if (!"platform".equals(w.type) || !("yes".equals(w.tag("ferry")) || "ferry_terminal".equals(w.tag("amenity")))) continue;
				float[] p = nearestPoint(w.east, w.north);
				float d = (float) Math.hypot(p[0], p[1]);
				if (d < best) {
					best = d;
					ferry = new Node("ferry_terminal", w.tags, p[0], p[1]);
				}
			}
			if (ferry != null) coast = true;
		}
		// Surf only with real evidence of a coast: a cliff on its own is just as often inland.
		if (beach != null) {
			scene.spots.add(new Scene.Spot("sea_waves", beach.east, beach.north, 0.8f, 45f, 0, 0));
		}
		if (rocks != null && coast) {
			scene.spots.add(new Scene.Spot("rocky_shore", rocks.east, rocks.north, 0.7f, 40f, 0, 0));
		}
		Node port = harbour != null ? harbour : ferry;
		Node gulls = port != null ? port : (beach != null ? beach : (coast ? rocks : seaSide));
		if (port != null) {
			scene.spots.add(new Scene.Spot("harbor", port.east, port.north, 0.6f, 15f, 0, 0));
		}
		if (gulls != null) {
			// Gulls fly about far from the shore itself, so they carry a long way.
			scene.spots.add(new Scene.Spot("seagulls", gulls.east + 20, gulls.north + 10, 0.6f, GULL_REF_M, 20, 60));
		}
		if (ferry != null) {
			scene.spots.add(new Scene.Spot("ship_horn", ferry.east, ferry.north, 0.6f, 60f, 60, 160));
		}

		// Rivers aren't in the map data, but a long bridge is very likely over a wide one.
		Way longBridge = null;
		float longest = LONG_BRIDGE_M;
		for (Way w : ways) {
			if (!isBridge(w) || minDistance(w.east, w.north) > LONG_BRIDGE_REACH_M) continue;
			float len = pathLength(w);
			if (len >= longest) {
				longest = len;
				longBridge = w;
			}
		}
		if (longBridge != null) {
			float[] p = nearestPoint(longBridge.east, longBridge.north);
			scene.spots.add(new Scene.Spot("river_roar", p[0], p[1], clamp(longest / 150f, 0.5f, 0.9f), 25f, 0, 0));
		}

		// A small bridge (not a motorway viaduct) is usually over water: a stream in the country, a
		// canal's lapping in town. Only a guess, so kept quiet and used only without better evidence.
		if (longBridge == null && used.isEmpty() && port == null && beach == null) {
			Way bridge = null;
			float best = 70;
			for (Way w : ways) {
				if (!"yes".equals(w.tag("bridge")) || w.type.startsWith("motorway") || w.type.startsWith("trunk")) continue;
				float d = minDistance(w.east, w.north);
				if (d < best && pathLength(w) >= 8) {
					best = d;
					bridge = w;
				}
			}
			if (bridge != null) {
				float[] p = nearestPoint(bridge.east, bridge.north);
				boolean town = false;
				for (Node n : nodes) {
					if (n.dist() > 120) break;
					if (n.tags.containsKey("shop")) {
						town = true;
						break;
					}
				}
				scene.spots.add(new Scene.Spot(town ? "harbor" : "stream", p[0], p[1], 0.35f, 6f, 0, 0));
			}
		}
	}

	private static boolean isSeaSide(Node n) {
		String natural = n.tag("natural"), leisure = n.tag("leisure"), manMade = n.tag("man_made");
		return natural.equals("beach") || natural.equals("bay") || natural.equals("reef") || natural.equals("islet")
				|| leisure.equals("marina") || leisure.equals("slipway") || n.tag("amenity").equals("ferry_terminal")
				|| manMade.equals("lighthouse") || manMade.equals("pier");
	}

	/** Viaducts cross valleys and roads rather than rivers. */
	private static boolean isBridge(Way w) {
		String bridge = w.tag("bridge");
		return !bridge.isEmpty() && !bridge.equals("no") && !bridge.equals("viaduct") && ROADS_AND_PATHS.contains(w.type);
	}

	private static void addOnce(Scene scene, Map<String, Integer> used, Scene.Spot spot) {
		int count = used.getOrDefault(spot.clip, 0);
		if (count >= MAX_POINT_SPOTS_PER_CLIP) return;
		used.put(spot.clip, count + 1);
		scene.spots.add(spot);
	}

	/** Wind on exposed ground (peaks, ridges, viewpoints), and forest sound deep in woods. */
	private static void addLandscape(Scene scene, List<Node> nodes, float trafficLoad) {
		boolean exposed = false, wood = false;
		int trees = 0;
		for (Node n : nodes) {
			float d = n.dist();
			if (d > FAR_RADIUS_M) break;
			String natural = n.tag("natural");
			if ((natural.equals("peak") || natural.equals("saddle") || natural.equals("volcano")) && d < 400) exposed = true;
			if (n.tag("tourism").equals("viewpoint") && d < 150) exposed = true;
			if (natural.equals("wood") && d < 300) wood = true;
			if ("tree".equals(n.type) && d < 120) trees++;
		}
		if (exposed && trafficLoad < 6) scene.beds.add(new Scene.Bed("wind_open", 0.35f));
		if ((wood || trees >= 40) && trafficLoad < 4) {
			scene.spots.add(new Scene.Spot("forest", -20, 30, 0.6f, 25f, 0, 0));
		}
	}

	/**
	 * Farm animals and dogs. OSM maps no animals, only places that imply them: cattle grids
	 * (pasture), farm and dairy shops, animal breeding, riding stables, animal shelters, dog parks.
	 */
	private static void addAnimals(Scene scene, List<Node> nodes) {
		Map<String, Integer> used = new HashMap<>();
		for (Node n : nodes) {
			float d = n.dist();
			if (d > 300) break;
			String barrier = n.tag("barrier"), shop = n.tag("shop"), amenity = n.tag("amenity");
			String leisure = n.tag("leisure"), sport = n.tag("sport");
			// Placed a little off the point itself: the animals are in the field, not on the grid.
			float e = n.east + 25, no = n.north + 15;
			if (barrier.equals("cattle_grid")) {
				addOnce(scene, used, new Scene.Spot("cows", e, no, 0.5f, 20f, 40, 110));
				addOnce(scene, used, new Scene.Spot("sheep", e - 40, no + 20, 0.5f, 20f, 45, 120));
			} else if (shop.equals("farm") || shop.equals("dairy") || amenity.equals("animal_breeding")) {
				addOnce(scene, used, new Scene.Spot("cows", e, no, 0.5f, 20f, 40, 110));
				addOnce(scene, used, new Scene.Spot("goats", e - 30, no - 10, 0.5f, 15f, 45, 120));
				addOnce(scene, used, new Scene.Spot("rooster", e + 20, no + 25, 0.45f, 15f, 60, 160));
			} else if (leisure.equals("horse_riding") || sport.equals("equestrian") || sport.equals("horse_racing")) {
				addOnce(scene, used, new Scene.Spot("horses", e, no, 0.55f, 15f, 35, 90));
			} else if ((amenity.equals("animal_shelter") || amenity.equals("animal_boarding")) && d < 200) {
				addOnce(scene, used, new Scene.Spot("kennel_dogs", n.east, n.north, 0.5f, 15f, 30, 80));
			} else if (leisure.equals("dog_park") && d < 120) {
				addOnce(scene, used, new Scene.Spot("dog_bark", n.east, n.north, 0.5f, 10f, 25, 60));
			}
		}
	}

	private static float pathLength(Way w) {
		float len = 0;
		for (int i = 1; i < w.east.length; i++) {
			len += (float) Math.hypot(w.east[i] - w.east[i - 1], w.north[i] - w.north[i - 1]);
		}
		return len;
	}

	/** Occasional sounds that belong to a kind of street rather than to one mapped point. */
	private static void addCasualEvents(Scene scene, List<Way> ways, float trafficLoad, float hornFactor) {
		int residential = 0;
		for (Way w : ways) {
			if (("residential".equals(w.type) || "living_street".equals(w.type)) && minDistance(w.east, w.north) < 80) residential++;
		}
		if (residential >= 2) {
			scene.spots.add(new Scene.Spot("dog_bark", -35, 45, 0.45f, 8f, 45, 120));
		}
		float interval = hornInterval(hornFactor);
		if (trafficLoad > MIN_LOAD_FOR_HORNS && interval > 0) {
			// Horns come from the traffic, not the sky: placed on the busiest lanes where people do honk.
			// With two lanes each honks half as often, and the total stays the same.
			List<Scene.Lane> honking = new ArrayList<>();
			Scene.Lane heavy = null;
			for (Scene.Lane l : scene.lanes) {
				if (!l.engine || l.calm) continue;
				if (honking.size() < 2) honking.add(l);
				if (heavy == null) {
					for (Scene.Vehicle v : l.vehicles) if (v.clip.equals("truck")) heavy = l;
				}
			}
			for (int i = 0; i < honking.size(); i++) {
				float[] p = nearestPoint(honking.get(i).east, honking.get(i).north);
				float every = interval * honking.size();
				scene.spots.add(new Scene.Spot(i == 0 ? "car_horn" : "car_horn_double", p[0] + 15, p[1], 0.5f, 10f,
						every * 0.6f, every * 1.4f));
			}
			if (heavy != null) {
				float[] p = nearestPoint(heavy.east, heavy.north);
				scene.spots.add(new Scene.Spot("truck_horn", p[0] - 15, p[1], 0.5f, 15f, interval * 2f, interval * 4f));
			}
		}
	}

	/** Seconds between horns at a place, 0 for a place where nobody honks. */
	static float hornInterval(float hornFactor) {
		return hornFactor < MIN_HORN_FACTOR ? 0f : Math.max(MIN_HORN_INTERVAL_S, HORN_INTERVAL_S / hornFactor);
	}

	/** Louder birds in a quiet street, and in a narrow one children playing now and then. */
	private static void addQuietStreetLife(Scene scene, List<Way> ways) {
		Way nearest = null;
		float best = QUIET_STREET_REACH_M;
		for (Way w : ways) {
			if (!ROADS.containsKey(w.type) || "service".equals(w.type)) continue;
			float d = minDistance(w.east, w.north);
			if (d < best) {
				best = d;
				nearest = w;
			}
		}
		if (nearest == null || !isCalm(nearest)) return;
		float birdGain = 0.45f;
		float birdEast = 12, birdNorth = 8;
		for (java.util.Iterator<Scene.Spot> it = scene.spots.iterator(); it.hasNext(); ) {
			Scene.Spot s = it.next();
			if (s.clip.equals("birds")) {
				birdGain = s.gain;
				birdEast = s.east;
				birdNorth = s.north;
				it.remove();
			}
		}
		scene.spots.add(new Scene.Spot("birds", birdEast, birdNorth, Math.min(0.9f, birdGain + 0.3f), 15f, 0, 0));
		if (!isWide(nearest) && parseFloat(nearest.tag("lanes"), 0) <= 2) {
			float[] p = nearestPoint(nearest.east, nearest.north);
			scene.spots.add(new Scene.Spot("children", p[0] + 14, p[1] - 9, 0.5f, 8f, 40, 100));
		}
	}

	/** Someone walking by now and then on an unpaved path nearby. */
	private static void addUnpavedSteps(Scene scene, List<Way> ways) {
		float best = UNPAVED_PATH_REACH_M;
		float[] at = null;
		for (Way w : ways) {
			if (!"path".equals(w.type) && !"footway".equals(w.type)) continue;
			// A path with no surface tag is more often earth than not; a footway more often paved.
			if (!isUnpaved(w) && !("path".equals(w.type) && w.tag("surface").isEmpty())) continue;
			float[] p = nearestPoint(w.east, w.north);
			float d = (float) Math.hypot(p[0], p[1]);
			if (d < best) {
				best = d;
				at = p;
			}
		}
		if (at != null) scene.spots.add(new Scene.Spot("footsteps_dirt", at[0], at[1], 0.6f, 4f, 20, 55));
	}

	/** The runway's direction isn't known; the airliner just flies past on a line through the airport. */
	private static void addAircraft(Scene scene, float[] airport) {
		if (airport == null) return;
		// Roughly across the line of sight, so it passes rather than flies straight at the listener.
		double angle = Math.atan2(airport[1], airport[0]) + Math.toRadians(60);
		float ux = (float) Math.cos(angle), uy = (float) Math.sin(angle);
		float[] east = {airport[0] - ux * AIRCRAFT_PATH_M, airport[0] + ux * AIRCRAFT_PATH_M};
		float[] north = {airport[1] - uy * AIRCRAFT_PATH_M, airport[1] + uy * AIRCRAFT_PATH_M};
		Scene.Lane lane = new Scene.Lane("aircraft", east, north, 75f, 0.4f, false,
				Collections.singletonList(new Scene.Vehicle("airplane", 1f, 1f)), null, null);
		lane.gapMin = AIRCRAFT_GAP_MIN_S;
		lane.gapMax = AIRCRAFT_GAP_MAX_S;
		lane.height = 300f;
		lane.refDistance = 400f;
		lane.maxDistance = 30000f;
		lane.edgeFade = 1500f;
		scene.lanes.add(lane);
	}

	// ------------------------------------------------------------------ geometry

	static float minDistance(float[] xs, float[] ys) {
		return minDistanceTo(xs, ys, 0, 0);
	}

	static float minDistanceTo(float[] xs, float[] ys, float qx, float qy) {
		float[] p = nearestPointTo(xs, ys, qx, qy);
		return (float) Math.hypot(p[0] - qx, p[1] - qy);
	}

	static float[] nearestPoint(float[] xs, float[] ys) {
		return nearestPointTo(xs, ys, 0, 0);
	}

	static float[] nearestPointTo(float[] xs, float[] ys, float qx, float qy) {
		float bx = xs[0], by = ys[0];
		float best = Float.MAX_VALUE;
		for (int i = 0; i < xs.length; i++) {
			float x1 = xs[i], y1 = ys[i];
			float x2 = i + 1 < xs.length ? xs[i + 1] : x1, y2 = i + 1 < ys.length ? ys[i + 1] : y1;
			float dx = x2 - x1, dy = y2 - y1;
			float len2 = dx * dx + dy * dy;
			float t = len2 == 0 ? 0 : clamp(((qx - x1) * dx + (qy - y1) * dy) / len2, 0, 1);
			float px = x1 + t * dx, py = y1 + t * dy;
			float d = (px - qx) * (px - qx) + (py - qy) * (py - qy);
			if (d < best) {
				best = d;
				bx = px;
				by = py;
			}
		}
		return new float[]{bx, by};
	}

	/**
	 * The stretch of a polyline around its point nearest the listener, from where it first comes
	 * within {@code radius} to where it last leaves it — so a vehicle enters and leaves out of
	 * earshot, and a long road's far parts (which may loop back) aren't driven. Null if it never
	 * comes within the radius.
	 */
	static float[][] clipToRadius(float[] xs, float[] ys, float radius) {
		int first = -1, last = -1;
		for (int i = 0; i < xs.length; i++) {
			boolean in = Math.hypot(xs[i], ys[i]) <= radius;
			boolean segIn = i + 1 < xs.length && segmentDistance(xs[i], ys[i], xs[i + 1], ys[i + 1]) <= radius;
			if (in || segIn) {
				if (first < 0) first = i;
				last = segIn ? i + 1 : i;
			}
		}
		if (first < 0) return null;
		if (last == first) {
			if (last + 1 < xs.length) last++;
			else if (first > 0) first--;
			else return null;
		}
		return new float[][]{Arrays.copyOfRange(xs, first, last + 1), Arrays.copyOfRange(ys, first, last + 1)};
	}

	private static float segmentDistance(float x1, float y1, float x2, float y2) {
		float dx = x2 - x1, dy = y2 - y1;
		float len2 = dx * dx + dy * dy;
		float t = len2 == 0 ? 0 : clamp(-(x1 * dx + y1 * dy) / len2, 0, 1);
		return (float) Math.hypot(x1 + t * dx, y1 + t * dy);
	}

	static float clamp(float v, float lo, float hi) {
		return v < lo ? lo : (v > hi ? hi : v);
	}

	private static float parseFloat(String s, float fallback) {
		if (s == null || s.isEmpty()) return fallback;
		try {
			// "2;3" (per-direction lanes) and "7 m" both occur in OSM; take the leading number.
			int end = 0;
			while (end < s.length() && (Character.isDigit(s.charAt(end)) || s.charAt(end) == '.')) end++;
			return end == 0 ? fallback : Float.parseFloat(s.substring(0, end));
		} catch (NumberFormatException e) {
			return fallback;
		}
	}
}
