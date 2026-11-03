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
		for (MapElement e : MyApplication.getMapTileManager(context)
				.queryFeatureNodesAround(center, SceneBuilder.RADIUS_M, MAX_NODES)) {
			nodes.add(new SceneBuilder.Node(e.type, e.tags, east(e.lon - lon, cosLat), north(e.lat - lat)));
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
		return SceneBuilder.build(nodes, ways);
	}

	private static float east(double dLon, double cosLat) {
		return (float) (Math.toRadians(dLon) * cosLat * Contract.EARTH_RADIUS);
	}

	private static float north(double dLat) {
		return (float) (Math.toRadians(dLat) * Contract.EARTH_RADIUS);
	}
}
