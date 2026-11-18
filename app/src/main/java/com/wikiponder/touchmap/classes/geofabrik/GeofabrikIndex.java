package com.wikiponder.touchmap.classes.geofabrik;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.wikiponder.touchmap.MyApplication;
import com.wikiponder.touchmap.classes.Utils;
import com.wikiponder.touchmap.classes.db.OSMDatabaseHelper;

import org.json.JSONArray;
import org.json.JSONObject;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessagePacker;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Downloads, caches and parses the Geofabrik region index so the user can pick a country and,
 * if available, a province / sub-region (and sub-sub-regions) to download.
 *
 * <p>Uses the geometry-bearing {@code index-v1.json} (~4 MB) so the download screen can default to
 * the smallest region that actually contains the user's current location (point-in-polygon).
 */
public class GeofabrikIndex {
	private static final String TAG = "GeofabrikIndex";
	private static final String INDEX_URL = "https://download.geofabrik.de/index-v1.json";
	private static final String CACHE_FILE = "geofabrik_index_geo.json";
	private static final long CACHE_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000; // 30 days
	/** Shared version-check file for both this class's bundled db and WorldPlacesRepository's db. */
	public static final String UPDATES_URL = "https://wikiponder.com/navia/updates.json";

	private final Map<String, GeofabrikRegion> regionsById = new HashMap<>();
	private final Map<String, List<GeofabrikRegion>> childrenByParent = new HashMap<>();
	// Outer rings of each region's polygon(s); ring points are [lon, lat]. Used for containment.
	private final Map<String, List<float[][]>> geometryById = new HashMap<>();

	/** A region paired with its depth below the country (0 = the country itself). */
	public static final class FlatRegion {
		public final GeofabrikRegion region;
		public final int depth;
		public final boolean hasChildren;

		FlatRegion(GeofabrikRegion region, int depth, boolean hasChildren) {
			this.region = region;
			this.depth = depth;
			this.hasChildren = hasChildren;
		}
	}

	private GeofabrikIndex() {
	}

	/**
	 * Downloads and parses the raw {@code index-v1.json} directly from Geofabrik, bypassing the
	 * on-disk JSON cache entirely. Used by {@link GeofabrikIndexRepository} when {@code updates.json}
	 * signals a newer "regions" version, to rebuild the local {@code geofabrik_index.db} straight
	 * from the source of truth — Geofabrik's own server — rather than requiring a copy of that db to
	 * also be hosted and re-uploaded elsewhere. Blocking — call off the main thread.
	 */
	public static GeofabrikIndex downloadFresh() throws Exception {
		GeofabrikIndex index = new GeofabrikIndex();
		index.parse(download(INDEX_URL));
		return index;
	}

	/**
	 * Loads the index. Blocking — call off the main thread. Prefers the bundled/updated
	 * {@code geofabrik_index.db} (see {@link GeofabrikIndexRepository}) — fast and needs no network
	 * once the app is installed. Only falls back to downloading and parsing the raw
	 * {@code index-v1.json} if that db is genuinely unavailable (e.g. the bundled asset failed to
	 * copy on first run), so the feature still degrades gracefully instead of breaking outright.
	 */
	public static GeofabrikIndex load(Context context) throws Exception {
		GeofabrikIndex index = new GeofabrikIndex();
		File dbFile = MyApplication.getGeofabrikIndexRepository(context).ensureReady();
		if (dbFile != null) {
			index.parseFromDb(dbFile);
		} else {
			String json = readCachedOrDownload(context);
			index.parse(json);
		}
		try {
			index.persistBoundaries(context);
		} catch (Exception e) {
			// Persisting is best-effort: the in-memory index (already parsed above) is still fully
			// usable for this session even if the DB write fails.
			Log.w(TAG, "Failed to persist boundaries", e);
		}
		return index;
	}

	/** Populates the same in-memory maps {@link #parse} would from JSON, but reading the compact
	 * {@code regions} table {@code tools/build_geofabrik_index.py} produces instead. */
	private void parseFromDb(File dbFile) {
		SQLiteDatabase db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
		try (Cursor c = db.query("regions",
				new String[]{"id", "parent", "name", "iso2", "pbf_url", "rings"},
				null, null, null, null, null)) {
			while (c.moveToNext()) {
				String id = c.getString(0);
				String parent = c.getString(1);
				String name = c.getString(2);
				String iso2 = c.getString(3);
				String pbfUrl = c.getString(4);
				byte[] ringsBlob = c.isNull(5) ? null : c.getBlob(5);

				regionsById.put(id, new GeofabrikRegion(id, name, parent, pbfUrl, iso2));
				if (ringsBlob != null) {
					List<float[][]> rings = Utils.unpackRings(ringsBlob);
					if (!rings.isEmpty()) geometryById.put(id, rings);
				}
			}
		} finally {
			db.close();
		}

		for (GeofabrikRegion region : regionsById.values()) {
			if (region.parent != null) {
				childrenByParent.computeIfAbsent(region.parent, k -> new ArrayList<>()).add(region);
			}
		}
		Comparator<GeofabrikRegion> byName = Comparator.comparing(r -> r.name.toLowerCase());
		for (List<GeofabrikRegion> list : childrenByParent.values()) {
			Collections.sort(list, byName);
		}
	}

	/**
	 * Writes this index out as a standalone SQLite db with the exact same {@code regions} table
	 * shape {@code tools/build_geofabrik_index.py} produces (readable back via {@link #parseFromDb}) —
	 * used to refresh the local bundled copy on-device instead of downloading a pre-built db from a
	 * server. Overwrites {@code dest} if it already exists.
	 */
	public void writeToDb(File dest) {
		//noinspection ResultOfMethodCallIgnored
		if (dest.exists()) dest.delete();
		SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(dest, null);
		try {
			db.execSQL("CREATE TABLE regions (" +
					"  id TEXT PRIMARY KEY, parent TEXT, name TEXT NOT NULL, iso2 TEXT, pbf_url TEXT, " +
					"  min_lat REAL, max_lat REAL, min_lon REAL, max_lon REAL, rings BLOB)");
			db.beginTransaction();
			try {
				for (GeofabrikRegion region : regionsById.values()) {
					List<float[][]> rings = geometryById.get(region.id);
					ContentValues cv = new ContentValues();
					cv.put("id", region.id);
					cv.put("parent", region.parent);
					cv.put("name", region.name);
					cv.put("iso2", region.iso2);
					cv.put("pbf_url", region.pbfUrl);
					if (rings != null && !rings.isEmpty()) {
						float[] bbox = computeBbox(rings);
						cv.put("min_lat", (double) bbox[0]);
						cv.put("max_lat", (double) bbox[1]);
						cv.put("min_lon", (double) bbox[2]);
						cv.put("max_lon", (double) bbox[3]);
						try {
							cv.put("rings", packRings(rings));
						} catch (Exception e) {
							Log.w(TAG, "Failed to pack rings for " + region.id, e);
						}
					}
					db.insertWithOnConflict("regions", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
				}
				db.setTransactionSuccessful();
			} finally {
				db.endTransaction();
			}
		} finally {
			db.close();
		}
	}

	/** {minLat, maxLat, minLon, maxLon} across every point in every ring. */
	private static float[] computeBbox(List<float[][]> rings) {
		float minLat = Float.MAX_VALUE, maxLat = -Float.MAX_VALUE;
		float minLon = Float.MAX_VALUE, maxLon = -Float.MAX_VALUE;
		for (float[][] ring : rings) {
			for (float[] pt : ring) {
				if (pt[1] < minLat) minLat = pt[1];
				if (pt[1] > maxLat) maxLat = pt[1];
				if (pt[0] < minLon) minLon = pt[0];
				if (pt[0] > maxLon) maxLon = pt[0];
			}
		}
		return new float[]{minLat, maxLat, minLon, maxLon};
	}

	/**
	 * Upserts every region with geometry into the {@code boundaries} table, so country/province
	 * containment becomes a real indexed DB query (see MapTileManager.isTileImported in a later
	 * phase) instead of this class's in-memory linear scan. Runs on the same cadence as the
	 * underlying index-v1.json cache (see CACHE_MAX_AGE_MS).
	 *
	 * <p>Deliberately insert-if-absent then update everything except {@code imported_at}: that column
	 * is owned by the import pipeline (set when a region is actually downloaded) and must survive a
	 * routine boundary-geometry refresh, not get reset to NULL every 30 days.
	 */
	private void persistBoundaries(Context context) throws Exception {
		SQLiteDatabase db = OSMDatabaseHelper.getInstance(context).getWritableDatabase();
		db.beginTransaction();
		try {
			for (Map.Entry<String, List<float[][]>> e : geometryById.entrySet()) {
				String id = e.getKey();
				GeofabrikRegion region = regionsById.get(id);
				if (region == null) continue;
				List<float[][]> rings = e.getValue();
				if (rings.isEmpty()) continue;

				float[] bbox = computeBbox(rings);
				byte[] packedRings = packRings(rings);
				int depth = depthOf(id);

				ContentValues insertCv = new ContentValues();
				insertCv.put("region_id", id);
				insertCv.put("parent_id", region.parent);
				insertCv.put("name", region.name);
				insertCv.put("depth", depth);
				insertCv.put("iso2", region.iso2);
				insertCv.put("pbf_url", region.pbfUrl);
				insertCv.put("min_lat", (double) bbox[0]);
				insertCv.put("max_lat", (double) bbox[1]);
				insertCv.put("min_lon", (double) bbox[2]);
				insertCv.put("max_lon", (double) bbox[3]);
				insertCv.put("rings", packedRings);
				db.insertWithOnConflict("boundaries", null, insertCv, SQLiteDatabase.CONFLICT_IGNORE);

				ContentValues updateCv = new ContentValues();
				updateCv.put("parent_id", region.parent);
				updateCv.put("name", region.name);
				updateCv.put("depth", depth);
				updateCv.put("iso2", region.iso2);
				updateCv.put("pbf_url", region.pbfUrl);
				updateCv.put("min_lat", (double) bbox[0]);
				updateCv.put("max_lat", (double) bbox[1]);
				updateCv.put("min_lon", (double) bbox[2]);
				updateCv.put("max_lon", (double) bbox[3]);
				updateCv.put("rings", packedRings);
				db.update("boundaries", updateCv, "region_id=?", new String[]{id});
			}
			db.setTransactionSuccessful();
		} finally {
			db.endTransaction();
		}
	}

	/** Packs a region's rings (outer polygon rings, lon/lat float pairs) as nested MessagePack arrays. */
	private static byte[] packRings(List<float[][]> rings) throws Exception {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		try (MessagePacker packer = MessagePack.newDefaultPacker(bos)) {
			packer.packArrayHeader(rings.size());
			for (float[][] ring : rings) {
				packer.packArrayHeader(ring.length);
				for (float[] pt : ring) {
					packer.packArrayHeader(2);
					packer.packFloat(pt[0]); // lon
					packer.packFloat(pt[1]); // lat
				}
			}
		}
		return bos.toByteArray();
	}

	private static String readCachedOrDownload(Context context) throws Exception {
		File cache = new File(context.getFilesDir(), CACHE_FILE);
		boolean fresh = cache.exists() && (System.currentTimeMillis() - cache.lastModified()) < CACHE_MAX_AGE_MS;
		if (fresh) {
			try {
				return readFile(cache);
			} catch (Exception e) {
				Log.w(TAG, "Failed to read cached index, re-downloading", e);
			}
		}
		String json = download(INDEX_URL);
		try (FileOutputStream fos = new FileOutputStream(cache)) {
			fos.write(json.getBytes(StandardCharsets.UTF_8));
		} catch (Exception e) {
			Log.w(TAG, "Failed to cache index", e);
		}
		return json;
	}

	private void parse(String json) throws Exception {
		JSONObject root = new JSONObject(json);
		JSONArray features = root.getJSONArray("features");
		for (int i = 0; i < features.length(); i++) {
			JSONObject feature = features.getJSONObject(i);
			JSONObject props = feature.getJSONObject("properties");
			String id = props.optString("id", null);
			if (id == null) continue;
			String name = props.optString("name", id);
			String parent = props.has("parent") ? props.optString("parent", null) : null;

			String pbfUrl = null;
			JSONObject urls = props.optJSONObject("urls");
			if (urls != null) pbfUrl = urls.optString("pbf", null);

			String iso2 = null;
			JSONArray isoArr = props.optJSONArray("iso3166-1:alpha2");
			if (isoArr != null && isoArr.length() > 0) {
				iso2 = isoArr.optString(0, null);
				if (iso2 != null) iso2 = iso2.toUpperCase();
			}

			regionsById.put(id, new GeofabrikRegion(id, name, parent, pbfUrl, iso2));

			JSONObject geom = feature.optJSONObject("geometry");
			if (geom != null) {
				List<float[][]> rings = parseOuterRings(geom);
				if (!rings.isEmpty()) geometryById.put(id, rings);
			}
		}

		for (GeofabrikRegion region : regionsById.values()) {
			if (region.parent != null) {
				childrenByParent.computeIfAbsent(region.parent, k -> new ArrayList<>()).add(region);
			}
		}
		Comparator<GeofabrikRegion> byName = Comparator.comparing(r -> r.name.toLowerCase());
		for (List<GeofabrikRegion> list : childrenByParent.values()) {
			Collections.sort(list, byName);
		}
	}

	/** Extracts outer rings from a GeoJSON Polygon or MultiPolygon (holes are ignored). */
	private static List<float[][]> parseOuterRings(JSONObject geom) {
		List<float[][]> rings = new ArrayList<>();
		try {
			String type = geom.optString("type", "");
			JSONArray coords = geom.optJSONArray("coordinates");
			if (coords == null) return rings;
			if ("Polygon".equals(type)) {
				addRing(rings, coords.optJSONArray(0));
			} else if ("MultiPolygon".equals(type)) {
				for (int i = 0; i < coords.length(); i++) {
					JSONArray poly = coords.optJSONArray(i);
					if (poly != null) addRing(rings, poly.optJSONArray(0));
				}
			}
		} catch (Exception e) {
			Log.w(TAG, "geometry parse failed", e);
		}
		return rings;
	}

	private static void addRing(List<float[][]> rings, JSONArray ring) {
		if (ring == null) return;
		float[][] pts = new float[ring.length()][2];
		for (int i = 0; i < ring.length(); i++) {
			JSONArray p = ring.optJSONArray(i);
			if (p == null) continue;
			pts[i][0] = (float) p.optDouble(0); // lon
			pts[i][1] = (float) p.optDouble(1); // lat
		}
		rings.add(pts);
	}

	private boolean isContinent(GeofabrikRegion region) {
		return region.parent == null;
	}

	/** Countries: regions whose parent is a top-level continent. Sorted by display name. */
	public List<GeofabrikRegion> getCountries() {
		List<GeofabrikRegion> countries = new ArrayList<>();
		for (GeofabrikRegion region : regionsById.values()) {
			if (region.parent == null) continue;
			GeofabrikRegion parent = regionsById.get(region.parent);
			if (parent != null && isContinent(parent)) {
				countries.add(region);
			}
		}
		Collections.sort(countries, Comparator.comparing(r -> r.name.toLowerCase()));
		return countries;
	}

	public List<GeofabrikRegion> getChildren(String regionId) {
		List<GeofabrikRegion> children = childrenByParent.get(regionId);
		if (children == null) return new ArrayList<>();
		return new ArrayList<>(children);
	}

	public boolean hasChildren(String regionId) {
		List<GeofabrikRegion> c = childrenByParent.get(regionId);
		return c != null && !c.isEmpty();
	}

	public GeofabrikRegion getById(String id) {
		return regionsById.get(id);
	}

	/**
	 * Returns the country + all its descendant regions as a depth-first flattened list, so the UI
	 * can show provinces and their sub-regions (e.g. NRW and its Regierungsbezirke).
	 */
	public List<FlatRegion> getCountrySubtree(String countryId) {
		List<FlatRegion> out = new ArrayList<>();
		GeofabrikRegion country = regionsById.get(countryId);
		if (country == null) return out;
		out.add(new FlatRegion(country, 0, hasChildren(countryId)));
		appendChildren(countryId, 1, out);
		return out;
	}

	private void appendChildren(String parentId, int depth, List<FlatRegion> out) {
		for (GeofabrikRegion child : getChildren(parentId)) {
			boolean kids = hasChildren(child.id);
			out.add(new FlatRegion(child, depth, kids));
			if (kids) appendChildren(child.id, depth + 1, out);
		}
	}

	/** Finds a country by ISO 3166-1 alpha-2 code (e.g. "DE"). Returns null if not found. */
	public GeofabrikRegion findCountryByIso(String iso2) {
		if (iso2 == null) return null;
		iso2 = iso2.toUpperCase();
		for (GeofabrikRegion country : getCountries()) {
			if (iso2.equals(country.iso2)) return country;
		}
		return null;
	}

	/**
	 * Number of ancestors up to (but not including) the continent — i.e. the region's depth, where a
	 * country is depth 0. Continents themselves (Geofabrik gives them geometry too, since a whole
	 * continent is downloadable as one PBF) return -1, never 0 — otherwise a continent and the country
	 * it contains would tie at depth 0, and {@link #findDeepestRegionContaining} (which only overwrites
	 * its best match on a strictly greater depth) would keep whichever of the two it happened to reach
	 * first while iterating the backing {@code HashMap}, occasionally picking the continent — which has
	 * no country of its own, so {@link #countryOf} then returns null and nothing gets pre-selected.
	 */
	public int depthOf(String regionId) {
		GeofabrikRegion r = regionsById.get(regionId);
		if (r == null || r.parent == null) return -1; // unknown region, or a continent
		int depth = 0;
		while (r != null && r.parent != null) {
			GeofabrikRegion p = regionsById.get(r.parent);
			if (p == null || p.parent == null) break; // stop at country (parent is continent)
			depth++;
			r = p;
		}
		return depth;
	}

	/** Walks up to the country (whose parent is a continent) that contains this region. */
	public GeofabrikRegion countryOf(String regionId) {
		GeofabrikRegion r = regionsById.get(regionId);
		while (r != null && r.parent != null) {
			GeofabrikRegion p = regionsById.get(r.parent);
			if (p == null) return null;
			if (p.parent == null) return r; // r's parent is a continent -> r is the country
			r = p;
		}
		return null;
	}

	/**
	 * Returns the deepest (most specific) region whose polygon contains the given location, or null.
	 * Used to default the download screen to the user's actual sub-region.
	 */
	public GeofabrikRegion findDeepestRegionContaining(double lat, double lon) {
		GeofabrikRegion best = null;
		int bestDepth = -1;
		for (Map.Entry<String, List<float[][]>> e : geometryById.entrySet()) {
			if (Utils.containsPoint(e.getValue(), lat, lon)) {
				int d = depthOf(e.getKey());
				if (d > bestDepth) {
					bestDepth = d;
					best = regionsById.get(e.getKey());
				}
			}
		}
		return best;
	}

	// ---- IO helpers ----

	private static String download(String urlStr) throws Exception {
		HttpURLConnection conn = openWithRedirects(urlStr);
		try {
			int code = conn.getResponseCode();
			if (code != HttpURLConnection.HTTP_OK) throw new Exception("Geofabrik index HTTP " + code);
			try (InputStream in = conn.getInputStream()) {
				return new String(readAll(in), StandardCharsets.UTF_8);
			}
		} finally {
			conn.disconnect();
		}
	}

	/** Opens a connection following redirects, including http->https which HttpURLConnection won't do automatically. */
	public static HttpURLConnection openWithRedirects(String urlStr) throws Exception {
		return openWithRedirects(urlStr, 30000);
	}

	public static HttpURLConnection openWithRedirects(String urlStr, int connectTimeoutMs) throws Exception {
		String current = urlStr;
		for (int redirects = 0; redirects < 5; redirects++) {
			URL url = new URL(current);
			HttpURLConnection conn = (HttpURLConnection) url.openConnection();
			conn.setConnectTimeout(connectTimeoutMs);
			conn.setReadTimeout(60000);
			conn.setInstanceFollowRedirects(false);
			conn.setRequestProperty("User-Agent", "TouchMap-Android");
			int code = conn.getResponseCode();
			if (code == HttpURLConnection.HTTP_MOVED_PERM
					|| code == HttpURLConnection.HTTP_MOVED_TEMP
					|| code == HttpURLConnection.HTTP_SEE_OTHER
					|| code == 307 || code == 308) {
				String location = conn.getHeaderField("Location");
				conn.disconnect();
				if (location == null) throw new Exception("Redirect without Location header");
				current = location;
				continue;
			}
			return conn;
		}
		throw new Exception("Too many redirects for " + urlStr);
	}

	private static String readFile(File file) throws Exception {
		try (FileInputStream in = new FileInputStream(file)) {
			return new String(readAll(in), StandardCharsets.UTF_8);
		}
	}

	private static byte[] readAll(InputStream in) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buf = new byte[8192];
		int n;
		while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
		return out.toByteArray();
	}
}
