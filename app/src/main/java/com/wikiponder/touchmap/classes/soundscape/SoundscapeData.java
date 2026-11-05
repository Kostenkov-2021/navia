package com.wikiponder.touchmap.classes.soundscape;

import android.content.Context;
import android.location.Location;

import com.wikiponder.touchmap.MyApplication;
import com.wikiponder.touchmap.classes.Contract;
import com.wikiponder.touchmap.classes.models.MapElement;
import com.wikiponder.touchmap.classes.models.WayRecord;

import java.util.ArrayList;
import java.util.List;

/** Reads the map around a point and hands it to {@link SceneBuilder} in listener-relative metres. */
public final class SoundscapeData {
	/** Trees and shop points dominate dense cities; this is plenty for every rule in SceneBuilder. */
	private static final int MAX_NODES = 1500;

	private SoundscapeData() {
	}

	/** Blocking db reads — call off the main thread. */
	public static Scene buildScene(Context context, double lat, double lon) {
		Location center = new Location("");
		center.setLatitude(lat);
		center.setLongitude(lon);
		double cosLat = Math.cos(Math.toRadians(lat));

		List<SceneBuilder.Node> nodes = new ArrayList<>();
		java.util.Set<Long> seen = new java.util.HashSet<>();
		List<MapElement> found = new ArrayList<>(MyApplication.getMapTileManager(context)
				.queryFeatureNodesAround(center, SceneBuilder.RADIUS_M, MAX_NODES));
		// Waterfalls, surf, harbours, church bells... carry further than shops do.
		found.addAll(MyApplication.getMapTileManager(context)
				.queryNodesOfTypesAround(center, SceneBuilder.FAR_RADIUS_M, SceneBuilder.FAR_TYPES, 300));
		// ...and church bells and gulls further still.
		found.addAll(MyApplication.getMapTileManager(context)
				.queryNodesOfTypesAround(center, SceneBuilder.VERY_FAR_RADIUS_M, SceneBuilder.VERY_FAR_TYPES, 300));
		for (MapElement e : found) {
			if (!seen.add(e.id)) continue;
			nodes.add(new SceneBuilder.Node(e.id, e.type, e.tags, east(e.lon - lon, cosLat), north(e.lat - lat)));
		}
		List<SceneBuilder.Way> ways = new ArrayList<>();
		// Wider than the node radius: a road just outside it still carries vehicles into earshot.
		for (WayRecord w : MyApplication.getMapTileManager(context).queryWaysAround(center, SceneBuilder.RADIUS_M + 60)) {
			float[] xs = new float[w.lats.length];
			float[] ys = new float[w.lats.length];
			for (int i = 0; i < xs.length; i++) {
				xs[i] = east(w.lons[i] - lon, cosLat);
				ys[i] = north(w.lats[i] - lat);
			}
			// MapElement's constructor is what unpacks the msgpack tag blob.
			MapElement tags = new MapElement(false, w.id, 0, 0, w.catId, w.type, w.name, w.tags);
			ways.add(new SceneBuilder.Way(w.type, tags.tags, xs, ys));
		}
		return SceneBuilder.build(nodes, ways, surroundings(context, lat, lon, cosLat));
	}

	private static SceneBuilder.Surroundings surroundings(Context context, double lat, double lon, double cosLat) {
		Airports.Airport airport = Airports.nearest(context, lat, lon);
		if (airport == null) return SceneBuilder.Surroundings.UNKNOWN;
		float e = east(airport.lon - lon, cosLat), n = north(airport.lat - lat);
		return new SceneBuilder.Surroundings(HornCulture.factor(lat, lon, airport.country),
				Math.hypot(e, n) <= SceneBuilder.AIRPORT_RADIUS_M ? new float[]{e, n} : null);
	}

	private static float east(double dLon, double cosLat) {
		return (float) (Math.toRadians(dLon) * cosLat * Contract.EARTH_RADIUS);
	}

	private static float north(double dLat) {
		return (float) (Math.toRadians(dLat) * Contract.EARTH_RADIUS);
	}
}
