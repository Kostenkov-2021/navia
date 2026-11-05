package com.wikiponder.touchmap.classes.soundscape;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The bundled airport table (tools/build_soundscape_airports.py). The box dbs carry no aeroway data,
 * so this is where airliners come from; the nearest airport's country also stands in for the country
 * of the point itself, which is all the horn rule needs (a border town may get its neighbour's horns).
 */
final class Airports {
	private static final String TAG = "Airports";
	private static final String ASSET = "soundscape/airports.tsv";
	private static volatile List<Airport> cached;

	static final class Airport {
		final double lat, lon;
		final String country;

		Airport(double lat, double lon, String country) {
			this.lat = lat;
			this.lon = lon;
			this.country = country;
		}
	}

	private Airports() {
	}

	/** Null if the table can't be read. Blocking on the first call. */
	static Airport nearest(Context context, double lat, double lon) {
		double cosLat = Math.cos(Math.toRadians(lat));
		Airport best = null;
		double bestD = Double.MAX_VALUE;
		for (Airport a : load(context)) {
			double dLon = Math.abs(a.lon - lon);
			if (dLon > 180) dLon = 360 - dLon;
			double dx = dLon * cosLat, dy = a.lat - lat;
			double d = dx * dx + dy * dy;
			if (d < bestD) {
				bestD = d;
				best = a;
			}
		}
		return best;
	}

	private static List<Airport> load(Context context) {
		List<Airport> list = cached;
		if (list != null) return list;
		list = new ArrayList<>();
		try (BufferedReader in = new BufferedReader(new InputStreamReader(
				context.getAssets().open(ASSET), StandardCharsets.US_ASCII))) {
			String line;
			while ((line = in.readLine()) != null) {
				String[] f = line.split("\t");
				if (f.length == 3) list.add(new Airport(Double.parseDouble(f[0]), Double.parseDouble(f[1]), f[2]));
			}
		} catch (IOException | NumberFormatException e) {
			Log.w(TAG, "could not read " + ASSET, e);
			list = Collections.emptyList();
		}
		cached = list;
		return list;
	}
}
