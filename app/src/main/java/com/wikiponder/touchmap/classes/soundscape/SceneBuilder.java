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
 *       a park's outline.</li>
 * </ul>
 */
public final class SceneBuilder {

	public static final String CLIP_CROSSING_BELL = "crossing_bell";

	/** Everything further than this is not heard (and not queried — see SoundscapeData). */
	public static final float RADIUS_M = 180f;

	/** A point feature in listener-relative metres. */
	public static final class Node {
		public final String type;
		public final Map<String, String> tags;
		public final float east, north;

		public Node(String type, Map<String, String> tags, float east, float north) {
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

	private SceneBuilder() {
	}

	public static Scene build(List<Node> nodes, List<Way> ways) {
		Scene scene = new Scene();
		List<Node> sorted = new ArrayList<>(nodes);
		sorted.sort(Comparator.comparingDouble(Node::dist));

		float trafficLoad = addRoads(scene, ways, sorted);
		addPedestrianLife(scene, ways, sorted);
		addRail(scene, sorted, ways);
		addGreenery(scene, sorted, ways);
		addPointsOfInterest(scene, sorted);
		addCasualEvents(scene, ways, trafficLoad);

		// Always some air: a city is never digitally silent, and without it the gaps between cars
		// sound like the audio dropped out.
		scene.beds.add(new Scene.Bed("city_hum", 0.12f + Math.min(0.25f, trafficLoad * 0.01f)));
		if (trafficLoad > 4) {
			scene.beds.add(new Scene.Bed("traffic_far", Math.min(0.45f, 0.08f + trafficLoad * 0.006f)));
		}
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
			boolean oneway = "yes".equals(w.tag("oneway")) || "1".equals(w.tag("oneway"))
					|| hw.startsWith("motorway") || "roundabout".equals(w.tag("junction"));
			float buses = rc.buses;
			if (busStopNear && d < 60 && buses < 0.08f) buses = 0.08f;
			if ("designated".equals(w.tag("bus")) || "yes".equals(w.tag("psv"))) buses = Math.max(buses, 0.3f);

			List<Scene.Vehicle> vehicles = new ArrayList<>();
			float cars = 1f - rc.trucks - buses - rc.motorcycles;
			if (cars > 0) vehicles.add(new Scene.Vehicle("car", cars, 0.9f));
			if (rc.trucks > 0) vehicles.add(new Scene.Vehicle("truck", rc.trucks, 1.0f));
			if (buses > 0) vehicles.add(new Scene.Vehicle("bus", buses, 1.0f));
			if (rc.motorcycles > 0) vehicles.add(new Scene.Vehicle("motorcycle", rc.motorcycles, 0.8f));

			float[][] clipped = clipToRadius(w.east, w.north, RADIUS_M + 60);
			if (clipped == null) continue;
			candidates.add(new Scene.Lane(hw, clipped[0], clipped[1], speed, perMinute, oneway, vehicles, null, null));
			dists.add(d);
			load += perMinute / (1f + d / 40f);
		}
		// The loudest/closest few only: each lane can hold several moving voices at once.
		Integer[] order = new Integer[candidates.size()];
		for (int i = 0; i < order.length; i++) order[i] = i;
		Arrays.sort(order, Comparator.comparingDouble(i -> -candidates.get(i).perMinute / (1f + dists.get(i) / 25f)));
		for (int i = 0; i < Math.min(MAX_LANES, order.length); i++) scene.lanes.add(candidates.get(order[i]));

		Way nearestCycleway = null;
		float cyclewayDist = 60;
		for (Way w : ways) {
			if (!"cycleway".equals(w.type)) continue;
			float d = minDistance(w.east, w.north);
			if (d < cyclewayDist) {
				cyclewayDist = d;
				nearestCycleway = w;
			}
		}
		if (nearestCycleway != null) {
			float[][] clipped = clipToRadius(nearestCycleway.east, nearestCycleway.north, 120);
			if (clipped != null) {
				scene.lanes.add(new Scene.Lane("cycleway", clipped[0], clipped[1], 5f, 4f, false,
						Collections.singletonList(new Scene.Vehicle("bicycle", 1f, 0.7f)), null, null));
			}
		}
		return load;
	}

	private static float laneFactor(Way w) {
		float lanes = parseFloat(w.tag("lanes"), 0);
		if (lanes > 0) return clamp(lanes / 2f, 0.5f, 3f);
		float width = parseFloat(w.tag("width"), 0);
		if (width > 0) return clamp(width / 7f, 0.5f, 3f);
		return 1f;
	}

	private static float speedOf(Way w, float fallback) {
		String max = w.tag("maxspeed");
		float kmh = parseFloat(max.replace("km/h", "").trim(), 0);
		if (max.endsWith("mph")) kmh = parseFloat(max.replace("mph", "").trim(), 0) * 1.609f;
		if (kmh <= 0) return fallback;
		// Traffic in a city mostly drives below the limit.
		return clamp(kmh / 3.6f * 0.85f, 3f, 36f);
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
		for (Node n : nodes) {
			float d = n.dist();
			if (d > RADIUS_M) break;
			String amenity = n.tag("amenity");
			String leisure = n.tag("leisure");
			String shop = n.tag("shop");
			String clip = null;
			float gain = 0.6f, ref = 6f, maxD = 70f, minI = 0, maxI = 0;
			if (amenity.equals("cafe") || amenity.equals("ice_cream")) {
				clip = "cafe";
			} else if (amenity.equals("restaurant") || amenity.equals("fast_food") || amenity.equals("food_court")) {
				clip = "restaurant";
			} else if (amenity.equals("pub") || amenity.equals("bar") || amenity.equals("biergarten") || amenity.equals("nightclub")) {
				clip = "pub";
			} else if (amenity.equals("marketplace") || shop.equals("supermarket") || shop.equals("mall") || shop.equals("department_store")) {
				clip = "market";
				gain = 0.55f;
			} else if (amenity.equals("school") || amenity.equals("kindergarten") || amenity.equals("childcare")) {
				clip = "schoolyard";
				gain = 0.6f;
				ref = 15f;
				maxD = 140f;
			} else if (leisure.equals("playground")) {
				clip = "playground";
				ref = 10f;
				maxD = 110f;
			} else if (leisure.equals("pitch") || leisure.equals("sports_centre") || leisure.equals("stadium")) {
				clip = "sports";
				ref = 15f;
				maxD = 140f;
			} else if (amenity.equals("fountain")) {
				clip = "fountain";
				gain = 0.7f;
				ref = 4f;
				maxD = 60f;
			} else if (amenity.equals("place_of_worship") && !"muslim".equals(n.tag("religion"))
					&& !"jewish".equals(n.tag("religion")) && !"buddhist".equals(n.tag("religion"))) {
				clip = "church_bells";
				gain = 0.6f;
				ref = 40f;
				maxD = RADIUS_M;
				minI = 50;
				maxI = 110;
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
			} else if (("traffic_signals".equals(n.type) || "crossing".equals(n.type))
					&& "yes".equals(n.tag("traffic_signals:sound"))) {
				clip = "ped_signal";
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
		}
	}

	/** Occasional sounds that belong to a kind of street rather than to one mapped point. */
	private static void addCasualEvents(Scene scene, List<Way> ways, float trafficLoad) {
		int residential = 0;
		for (Way w : ways) {
			if (("residential".equals(w.type) || "living_street".equals(w.type)) && minDistance(w.east, w.north) < 80) residential++;
		}
		if (residential >= 2) {
			scene.spots.add(new Scene.Spot("dog_bark", -35, 45, 0.45f, 8f, 45, 120));
		}
		if (trafficLoad > 6) {
			// Placed on the busiest lane's nearest point: horns come from the traffic, not the sky.
			for (Scene.Lane l : scene.lanes) {
				if (l.label.equals("rail") || l.label.equals("tram") || l.label.equals("cycleway")) continue;
				float[] p = nearestPoint(l.east, l.north);
				scene.spots.add(new Scene.Spot("car_horn", p[0] + 15, p[1], 0.5f, 10f, 20, 55));
				break;
			}
		}
		for (Scene.Lane l : scene.lanes) {
			if (l.label.equals("cycleway")) {
				float[] p = nearestPoint(l.east, l.north);
				scene.spots.add(new Scene.Spot("bicycle_bell", p[0], p[1], 0.5f, 4f, 30, 80));
				break;
			}
		}
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
