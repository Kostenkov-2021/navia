package com.wikiponder.touchmap.classes.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.location.Location;
import android.util.Log;

import com.wikiponder.touchmap.classes.Contract;
import com.wikiponder.touchmap.classes.Utils;

import java.util.ArrayList;
import java.util.List;

public class OSMDatabaseHelper extends SQLiteOpenHelper {
	public static final int CATEGORY_INTERSECTION = 62;
	private static final String DB_NAME = "osm.db";
	private static final int DB_VERSION = 49;

	/** The seeded regions.id for the world-places gazetteer (see onCreate) — never a real import. */
	public static final int REGION_WORLD = 0;
	
	private static final String TAG = "OSMDatabaseHelper";
	
	public static final int CATEGORY_NONE = -60;
	public static final int CATEGORY_MANUAL = -20;
	public static final int CATEGORY_ADDRESS = 0;
	public static final int CATEGORY_HEALTH = 5;
	public static final int CATEGORY_TRAFFIC = 10;
	public static final int CATEGORY_BARRIER = 15;
	public static final int CATEGORY_SHOP = 20;
	public static final int CATEGORY_AMENITY = 25;
	public static final int CATEGORY_LEISURE = 30;
	public static final int CATEGORY_TOURISM = 35;
	public static final int CATEGORY_NATURAL = 50;
	public static final int CATEGORY_WAY = 60;

	// World-places categories (rows from the downloaded world_places.db gazetteer — NOT real OSM
	// data; used only as a stand-in in areas that haven't been imported yet). Consecutive with
	// WorldPlace.kind (0..5), so categoryId() there is simply CATEGORY_WORLD_CITY + kind.
	public static final int CATEGORY_WORLD_CITY = 100;
	public static final int CATEGORY_WORLD_SECTION = 101;
	public static final int CATEGORY_WORLD_LANDMARK = 102;
	public static final int CATEGORY_WORLD_AIRPORT = 103;
	public static final int CATEGORY_WORLD_STATION = 104;
	public static final int CATEGORY_WORLD_HOSPITAL = 105;

	public static boolean isWorldPlaceCategory(int catId) {
		return catId >= CATEGORY_WORLD_CITY && catId <= CATEGORY_WORLD_HOSPITAL;
	}
	
	// Singleton instance
	private static volatile OSMDatabaseHelper instance;
	// Kept only so the static insertNode/setFavorite methods below — which take no Context of their
	// own — can still reach UserEditsBackupHelper. Always an application Context, never an Activity.
	private final Context appContext;

	/**
	 * Private constructor to prevent direct instantiation.
	 * Use getInstance() to retrieve the singleton.
	 */
	private OSMDatabaseHelper(Context context) {
		super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
		this.appContext = context.getApplicationContext();
	}

	@Override
	public void onConfigure(SQLiteDatabase db) {
		super.onConfigure(db);
		// One connection, no WAL. This connection gets other databases ATTACHed (world_places.db as
		// "wp", each downloaded box as "box"), and an attachment lives on one connection only. Android
		// runs ATTACH as a read-only statement, so with WAL's connection pool it can land on a reader
		// connection while the following INSERT/SELECT runs on another: imports failed with "no such
		// table: box.nodes" and map queries with "no such table: wp.nodes". Android does switch WAL
		// off by itself on the first ATTACH, but that switch throws (and stays undone for the whole
		// process) when another thread holds a connection at that moment. WAL was enabled here to
		// keep readers from blocking behind a long import (ANRs); in practice it was already off
		// after the first ATTACH, so this only makes that state certain from the start.
		db.disableWriteAheadLogging();
	}
	
	/**
	 * Returns the singleton instance of this helper.
	 * Lazily initializes it on first access.
	 */
	public static OSMDatabaseHelper getInstance(Context context) {
		if (instance == null) {
			synchronized (OSMDatabaseHelper.class) {
				if (instance == null) {
					instance = new OSMDatabaseHelper(context);
				}
			}
		}
		return instance;
	}
	
	/**
	 * Deletes every downloaded/imported map feature — every node except manually-added ones (favorites
	 * on a manually-added point survive; favorites on a downloaded point don't, since the point itself
	 * is gone), all ways and their spatial index, and the box-download tracking table so
	 * {@code MapTileManager.isTileImported} correctly reports every area as not-downloaded again. Used
	 * by the Settings "delete all downloaded maps" action. Does not touch {@code regions}/
	 * {@code boundaries} (admin-name lookup data, unrelated to downloaded map content).
	 */
	public static void deleteAllDownloadedMaps(Context context) {
		SQLiteDatabase db = getInstance(context).getWritableDatabase();
		db.beginTransaction();
		try {
			db.delete("nodes", "cat_id != ?", new String[]{String.valueOf(CATEGORY_MANUAL)});
			db.delete("ways", null, null);
			db.delete("way_cells", null, null);
			db.delete("way_regions", null, null);
			db.delete("env_features", null, null);
			db.delete("env_cells", null, null);
			db.delete("imported_boxes", null, null);
			db.setTransactionSuccessful();
		} finally {
			db.endTransaction();
		}
	}

	public static void insertNode(Long id, Location location, String name) {
		double lat = location.getLatitude();
		double lon = location.getLongitude();
		// coarse cell this node lives in (single cell — nodes never span cells)
		int[] cell = Utils.latLonToIndex(lat, lon, Contract.CELL_SIZE_METERS);

		if (id == null) {
			// get the next available manually-assigned id (below the real-OSM ID_OFFSET range)
			id = 1L;
			try (Cursor cursor = instance.getReadableDatabase().rawQuery("SELECT MAX(id) FROM nodes WHERE id < ?", new String[]{"1000000000"})) {
				if (cursor.moveToFirst()) {
					if (!cursor.isNull(0)) {
						id = cursor.getLong(0) + 1;
					}
				}
			}
		}

		SQLiteDatabase db = instance.getWritableDatabase();
		// A manual node isn't tied to any import, but still belongs to whatever real-world region
		// contains it, so it's included when that region's data is later deleted.
		int regionId = RegionResolver.resolveByLocation(db, lat, lon);

		// insert into db (absolute lat/lon — no tile-relative offsets)
		ContentValues values = new ContentValues();
		values.put("id", id);
		values.put("lat", lat);
		values.put("lon", lon);
		values.put("cell_x", cell[0]);
		values.put("cell_y", cell[1]);

		values.put("cat_id", CATEGORY_MANUAL);
		values.put("name", name);
		values.put("type", "");
		values.put("tags", new byte[0]);
		values.put("region", regionId);

		db.insertWithOnConflict("nodes", null, values, SQLiteDatabase.CONFLICT_IGNORE);

		// Every row this method writes is CATEGORY_MANUAL (set above, unconditionally) — never
		// reconstructable by re-downloading a box — so it always needs a full backup copy. See
		// UserEditsBackupHelper's class doc.
		UserEditsBackupHelper.recordManualNode(instance.appContext, id, lat, lon, CATEGORY_MANUAL, regionId, name);
	}
	
	/** Records that {@code boxIndex} (see BoxImporter/BoxManifest) has been merged into this device's
	 * db, so {@link com.wikiponder.touchmap.classes.MapTileManager#isTileImported} recognizes the area
	 * it covers as already downloaded. Re-importing the same box (e.g. after the server rebuilds it)
	 * just refreshes imported_at, the map version and the bbox. */
	public static void markBoxImported(Context context, int boxIndex, int mapVersion, double minLat, double maxLat,
										double minLon, double maxLon) {
		ContentValues cv = new ContentValues();
		cv.put("box_index", boxIndex);
		cv.put("map_version", mapVersion);
		cv.put("imported_at", System.currentTimeMillis());
		cv.put("min_lat", minLat);
		cv.put("max_lat", maxLat);
		cv.put("min_lon", minLon);
		cv.put("max_lon", maxLon);
		getInstance(context).getWritableDatabase()
				.insertWithOnConflict("imported_boxes", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
	}

	public static void deleteNode(Context context, long id) {
		SQLiteDatabase db = getInstance(context).getWritableDatabase();
		db.delete(
				"nodes",
				"id = ?",
				new String[]{"" + id}
		);
		UserEditsBackupHelper.recordDeleted(context, id);
	}

	/** Updates just the display name of an existing node — the "Edit Node" dialog's Save action.
	 * Works for any node, real or manually-added; unlike {@link #deleteNode}, renaming a real
	 * downloaded OSM point (a local-only correction/override) doesn't require removing it. */
	public static void updateNodeName(Context context, long id, String name) {
		ContentValues values = new ContentValues();
		values.put("name", name);
		getInstance(context).getWritableDatabase().update("nodes", values, "id = ?", new String[]{"" + id});
		UserEditsBackupHelper.recordRename(context, id, name);
	}
	
	@Override
	public void onCreate(SQLiteDatabase db) {
		// Normalized OSM storage: one row per real feature, absolute lat/lon, no tile clipping.
		// nodes: point features (real OSM nodes, manual points, favorites overlay). A node belongs
		// to exactly one coarse cell (Contract.CELL_SIZE_METERS), so cell_x/cell_y are direct columns.
		db.execSQL("CREATE TABLE nodes(" +
				"  id   INTEGER PRIMARY KEY, " +
				"  lat  REAL, " +
				"  lon  REAL, " +
				"  cell_x  INTEGER, " +
				"  cell_y  INTEGER, " +
				"  cat_id INTEGER, " +
				"  favorite INTEGER NOT NULL DEFAULT 0, " +
				"  type TEXT, " +
				"  name TEXT," +
				"  tags BLOB, " +
				"  region INTEGER NOT NULL DEFAULT 0" +
				");");
		db.execSQL("CREATE INDEX IF NOT EXISTS nodes_cell ON nodes (cell_x, cell_y);");
		db.execSQL("CREATE INDEX IF NOT EXISTS nodes_favorite ON nodes (favorite) WHERE favorite = 1;");

		// ways: line/area features, full absolute-coordinate point list stored once (no per-tile
		// fragments), plus a bounding box for pre-filtering.
		db.execSQL("CREATE TABLE ways(" +
				"  id   INTEGER PRIMARY KEY, " +
				"  cat_id INTEGER, " +
				"  type TEXT, " +
				"  name TEXT, " +
				"  tags BLOB, " +
				"  min_lat REAL, max_lat REAL, " +
				"  min_lon REAL, max_lon REAL, " +
				"  points BLOB" +      // MessagePack array of [lat_e7, lon_e7] int pairs
				");");
		db.execSQL("CREATE INDEX IF NOT EXISTS ways_bbox ON ways(min_lat, max_lat, min_lon, max_lon);");

		// way_cells: manual spatial index (Android SQLite has no R-tree). One row per (cell a way
		// touches). Cell-first PK so "ways touching this cell" is a leading-column range scan.
		db.execSQL("CREATE TABLE way_cells(" +
				"  way_id INTEGER NOT NULL, " +
				"  cell_x INTEGER NOT NULL, " +
				"  cell_y INTEGER NOT NULL, " +
				"  PRIMARY KEY(cell_x, cell_y, way_id)" +
				");");
		db.execSQL("CREATE INDEX IF NOT EXISTS way_cells_way ON way_cells(way_id);");

		// boundaries: country/province admin polygons (from GeofabrikIndex's index-v1.json geometry).
		// Small worldwide, so a bbox pre-filter is enough — no cell-join needed. imported_at is the
		// "has this area been downloaded" replacement for the old per-tile existence check.
		db.execSQL("CREATE TABLE boundaries(" +
				"  region_id TEXT PRIMARY KEY, " +
				"  parent_id TEXT, " +
				"  name TEXT NOT NULL, " +
				"  depth INTEGER NOT NULL, " +
				"  iso2 TEXT, " +
				"  pbf_url TEXT, " +
				"  min_lat REAL, max_lat REAL, " +
				"  min_lon REAL, max_lon REAL, " +
				"  rings BLOB NOT NULL, " +
				"  imported_at INTEGER" +
				");");
		db.execSQL("CREATE INDEX IF NOT EXISTS boundaries_bbox ON boundaries(min_lat, max_lat, min_lon, max_lon);");
		db.execSQL("CREATE INDEX IF NOT EXISTS boundaries_parent ON boundaries(parent_id);");

		// regions: small lookup table mapping a (country, admin1, admin2) triple to a compact id,
		// stamped onto every node/way-point written by an import (see RegionResolver) so all of a
		// region's data can be found and deleted later without re-deriving country/admin names.
		db.execSQL("CREATE TABLE regions(" +
				"  id      INTEGER PRIMARY KEY AUTOINCREMENT, " +
				"  country TEXT NOT NULL DEFAULT '', " +
				"  admin1  TEXT NOT NULL DEFAULT '', " +
				"  admin2  TEXT NOT NULL DEFAULT '', " +
				"  UNIQUE(country, admin1, admin2)" +
				");");
		// Seeded row for the world-places gazetteer (see REGION_WORLD) — not a real import, but a
		// stable id so its rows are shaped identically to real regions.
		ContentValues worldRegion = new ContentValues();
		worldRegion.put("id", REGION_WORLD);
		worldRegion.put("country", "world");
		worldRegion.put("admin1", "");
		worldRegion.put("admin2", "");
		db.insertWithOnConflict("regions", null, worldRegion, SQLiteDatabase.CONFLICT_IGNORE);

		// way_regions: bridge table recording which regions a way's points touch (a way can straddle
		// a border). Region-first PK so "find candidate ways for region R" (deleteRegion) is a
		// leading-column range scan, mirroring way_cells's cell-first design.
		db.execSQL("CREATE TABLE way_regions(" +
				"  region_id INTEGER NOT NULL, " +
				"  way_id    INTEGER NOT NULL, " +
				"  PRIMARY KEY(region_id, way_id)" +
				");");
		db.execSQL("CREATE INDEX IF NOT EXISTS way_regions_way ON way_regions(way_id);");

		createImportedBoxesTable(db);
		createEnvTables(db);
	}

	/**
	 * Rivers, rail, airports, sea and lakes from the box files, kept apart from {@code ways} so they
	 * are never drawn, announced or turned into street junctions. Same schema as
	 * tools/build_planet_boxes_inmemory.py, which documents the columns. No screen reads them yet.
	 */
	private static void createEnvTables(SQLiteDatabase db) {
		db.execSQL("CREATE TABLE IF NOT EXISTS env_features(" +
				"  id INTEGER PRIMARY KEY, " +
				"  kind TEXT NOT NULL, " +
				"  type TEXT NOT NULL, " +
				"  name TEXT, " +
				"  role TEXT, " +
				"  relation_id INTEGER, " +
				"  tags BLOB, " +
				"  min_lat REAL, max_lat REAL, " +
				"  min_lon REAL, max_lon REAL, " +
				"  points BLOB" +
				");");
		db.execSQL("CREATE TABLE IF NOT EXISTS env_cells(" +
				"  feature_id INTEGER NOT NULL, " +
				"  cell_x INTEGER NOT NULL, " +
				"  cell_y INTEGER NOT NULL, " +
				"  PRIMARY KEY(cell_x, cell_y, feature_id)" +
				");");
		db.execSQL("CREATE INDEX IF NOT EXISTS env_cells_feature ON env_cells(feature_id);");
	}

	/** True if this box was downloaded before, whatever its version. */
	public static boolean isBoxImported(Context context, int boxIndex) {
		try (Cursor c = getInstance(context).getReadableDatabase().query("imported_boxes", new String[]{"box_index"},
				"box_index=?", new String[]{String.valueOf(boxIndex)}, null, null, null)) {
			return c.moveToFirst();
		}
	}

	/**
	 * imported_boxes: which of the server's planet-tiling boxes (see tools/build_global_cluster_dbs.py
	 * and BoxImporter) have already been merged into this device's db, plus the box's own bounding
	 * box — a box is a rectangle, so "is this location covered" is a plain bbox test, no polygon
	 * geometry needed (unlike `boundaries`, which this replaces for that purpose — see
	 * MapTileManager.isTileImported). box_index is the server's stable index into boxes_manifest.json.
	 * A standalone method (not inlined into onCreate) so {@link #onUpgrade} can add it in place — see
	 * there for why that matters.
	 */
	private static void createImportedBoxesTable(SQLiteDatabase db) {
		db.execSQL("CREATE TABLE IF NOT EXISTS imported_boxes(" +
				"  box_index INTEGER PRIMARY KEY, " +
				"  map_version INTEGER NOT NULL DEFAULT 1, " +
				"  imported_at INTEGER NOT NULL, " +
				"  min_lat REAL, max_lat REAL, " +
				"  min_lon REAL, max_lon REAL" +
				");");
		db.execSQL("CREATE INDEX IF NOT EXISTS imported_boxes_bbox ON imported_boxes(min_lat, max_lat, min_lon, max_lon);");
	}

	@Override
	public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
		// 47 -> 48 is purely additive (just the new imported_boxes table above) — no need to touch any
		// existing data, so it must NOT go through the destructive drop-everything path below. That
		// path was measured to freeze the main thread for 10+ seconds and trigger a real "Input
		// dispatching timed out" ANR on a device with real imported data: getWritableDatabase()/
		// getReadableDatabase() are internally synchronized on `this` (see SQLiteOpenHelper), so
		// DROPping large existing `nodes`/`ways` tables here — while this migration runs on whichever
		// thread happened to touch the db first — blocks every *other* thread's db call for the whole
		// migration too, including the UI thread's own touch/hover handling
		// (MapTileManager.isTileImported), regardless of which thread triggered the upgrade.
		if (oldV >= 47) {
			createImportedBoxesTable(db);
			// 48 -> 49 is additive too. Boxes already on the phone count as map version 1, so they
			// show up as outdated (MapTileManager.isTileOutdated); nothing is downloaded or removed here.
			if (oldV == 48) {
				db.execSQL("ALTER TABLE imported_boxes ADD COLUMN map_version INTEGER NOT NULL DEFAULT 1;");
			}
			createEnvTables(db);
			return;
		}
		// Older schema versions (pre-normalized/tile-blob format, etc.): no reasonable in-place path,
		// so fall back to the original drop-and-rebuild. Downloaded boxes must be re-downloaded via the
		// map download flow (see BoxImporter); boundaries repopulate from the cached index-v1.json.
		db.execSQL("DROP TABLE IF EXISTS nodes;");
		db.execSQL("DROP TABLE IF EXISTS ways;");
		db.execSQL("DROP TABLE IF EXISTS way_cells;");
		db.execSQL("DROP TABLE IF EXISTS boundaries;");
		db.execSQL("DROP TABLE IF EXISTS regions;");
		db.execSQL("DROP TABLE IF EXISTS way_regions;");
		db.execSQL("DROP TABLE IF EXISTS tiles_downloaded");
		db.execSQL("DROP TABLE IF EXISTS imported_boxes;");
		db.execSQL("DROP TABLE IF EXISTS env_features;");
		db.execSQL("DROP TABLE IF EXISTS env_cells;");
		onCreate(db);
	}
	
	@Override
	public void onOpen(SQLiteDatabase db) {
		super.onOpen(db);
	}
	
	public static void setFavorite(long id, boolean isFavorite, Location location) {
		// check if this id exists in db
		boolean nodeExists = false;
		SQLiteDatabase db = instance.getWritableDatabase();
		try (Cursor cursor = db.query("nodes",
				null,
				"id = ?",
				new String[]{"" + id},
				null, null, null)) {
			nodeExists = cursor.moveToFirst();
		}
		
		if (!nodeExists)
			insertNode(id, location, "");
		// set favorite flag
		ContentValues values = new ContentValues();
		values.put("favorite", isFavorite ? 1:0);
		db.update("nodes", values, "id = ?", new String[] {"" + id});
		UserEditsBackupHelper.recordFavorite(instance.appContext, id, isFavorite);
	}

	/**
	 * Deletes every node and way exclusively owned by this region, and trims ways shared with other
	 * regions down to just the surviving (non-deleted) portion. A way that straddles this region and
	 * another keeps one boundary-adjacent point on the deleted side per point dropped, so the
	 * surviving geometry isn't left disconnected one point short of the border. Per-point region
	 * tags only ever came from the old per-country/radius importers (removed — see BoxImporter),
	 * which tagged different points of a border-straddling way differently; box-downloaded data is
	 * tagged {@code REGION_WORLD} uniformly, so this trimming path is now effectively dormant for new
	 * data, kept only for whatever legacy per-region tagging might still exist on a device.
	 * Also clears {@code imported_at} on any boundaries row(s) that resolve to this region — this
	 * does NOT touch {@code imported_boxes}, so it does not affect
	 * {@code MapTileManager.isTileImported}'s "is this area downloaded" check.
	 */
	public static void deleteRegion(Context context, int regionId) {
		// A region like a whole German Regierungsbezirk can touch well over a million way_regions
		// rows; catching Throwable (not just Exception) here means a genuine OutOfMemoryError on the
		// candidate-id list, or any other failure mid-way, rolls the transaction back (nothing half
		// deleted) and gets logged instead of crashing the caller. No UI is wired to this yet — once
		// one is, surface a failure message to the user from there using the return value.
		try {
			SQLiteDatabase db = getInstance(context).getWritableDatabase();
			String regionIdStr = String.valueOf(regionId);
			db.beginTransaction();
			try {
				db.delete("nodes", "region=?", new String[]{regionIdStr});

				List<Long> wayIds = new ArrayList<>();
				try (Cursor c = db.query("way_regions", new String[]{"way_id"}, "region_id=?",
						new String[]{regionIdStr}, null, null, null)) {
					while (c.moveToNext()) wayIds.add(c.getLong(0));
				}
				for (long wayId : wayIds) {
					trimOrDeleteWay(db, wayId, regionId);
				}

				clearImportedFlagForRegion(db, regionId);
				db.setTransactionSuccessful();
			} finally {
				db.endTransaction();
			}
		} catch (Throwable t) {
			Log.e(TAG, "deleteRegion(" + regionId + ") failed; region left unchanged", t);
		}
	}

	private static void trimOrDeleteWay(SQLiteDatabase db, long wayId, int regionId) {
		String wayIdStr = String.valueOf(wayId);
		Utils.WayPoints wp = null;
		try (Cursor c = db.query("ways", new String[]{"points"}, "id=?",
				new String[]{wayIdStr}, null, null, null)) {
			if (c.moveToFirst()) wp = Utils.unpackWayPoints(c.getBlob(0));
		}
		if (wp == null) {
			// Way row already gone; just clean up the dangling bridge row.
			db.delete("way_regions", "region_id=? AND way_id=?", new String[]{String.valueOf(regionId), wayIdStr});
			return;
		}

		int n = wp.lats.length;
		boolean allInRegion = true;
		for (int r : wp.regions) {
			if (r != regionId) {
				allInRegion = false;
				break;
			}
		}
		if (allInRegion) {
			deleteWayEntirely(db, wayId);
			return;
		}

		// Keep a point if it's not in the deleted region, or if it's the one boundary-adjacent point
		// of a deleted run that touches a surviving neighbor (so the kept geometry isn't left one
		// point short of the border on that side).
		boolean[] keep = new boolean[n];
		for (int i = 0; i < n; i++) {
			if (wp.regions[i] != regionId) {
				keep[i] = true;
				continue;
			}
			boolean leftKept = i > 0 && wp.regions[i - 1] != regionId;
			boolean rightKept = i < n - 1 && wp.regions[i + 1] != regionId;
			keep[i] = leftKept || rightKept;
		}

		List<Double> lats = new ArrayList<>(), lons = new ArrayList<>();
		List<Integer> regions = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			if (keep[i]) {
				lats.add(wp.lats[i]);
				lons.add(wp.lons[i]);
				regions.add(wp.regions[i]);
			}
		}
		if (lats.size() < 2) {
			deleteWayEntirely(db, wayId);
			return;
		}

		double minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
		double[] la = new double[lats.size()];
		double[] lo = new double[lats.size()];
		int[] rg = new int[lats.size()];
		for (int i = 0; i < la.length; i++) {
			la[i] = lats.get(i);
			lo[i] = lons.get(i);
			rg[i] = regions.get(i);
			if (la[i] < minLat) minLat = la[i];
			if (la[i] > maxLat) maxLat = la[i];
			if (lo[i] < minLon) minLon = lo[i];
			if (lo[i] > maxLon) maxLon = lo[i];
		}

		try {
			ContentValues cv = new ContentValues();
			cv.put("min_lat", minLat);
			cv.put("max_lat", maxLat);
			cv.put("min_lon", minLon);
			cv.put("max_lon", maxLon);
			cv.put("points", Utils.packWayPoints(la, lo, rg));
			db.update("ways", cv, "id=?", new String[]{wayIdStr});
		} catch (Exception e) {
			return; // leave the way as-is rather than risk a half-applied trim
		}

		// The surviving bbox can only shrink, so way_cells is rebuilt from scratch for this way.
		db.delete("way_cells", "way_id=?", new String[]{wayIdStr});
		for (int[] cell : Utils.cellsForBbox(minLat, maxLat, minLon, maxLon)) {
			ContentValues wc = new ContentValues();
			wc.put("way_id", wayId);
			wc.put("cell_x", cell[0]);
			wc.put("cell_y", cell[1]);
			db.insertWithOnConflict("way_cells", null, wc, SQLiteDatabase.CONFLICT_IGNORE);
		}
		db.delete("way_regions", "region_id=? AND way_id=?", new String[]{String.valueOf(regionId), wayIdStr});
	}

	private static void deleteWayEntirely(SQLiteDatabase db, long wayId) {
		String wayIdStr = String.valueOf(wayId);
		db.delete("ways", "id=?", new String[]{wayIdStr});
		db.delete("way_cells", "way_id=?", new String[]{wayIdStr});
		db.delete("way_regions", "way_id=?", new String[]{wayIdStr});
	}

	/** Clears imported_at on every boundaries row whose resolved region matches regionId. */
	private static void clearImportedFlagForRegion(SQLiteDatabase db, int regionId) {
		String country = "", admin1 = "", admin2 = "";
		try (Cursor c = db.query("regions", new String[]{"country", "admin1", "admin2"},
				"id=?", new String[]{String.valueOf(regionId)}, null, null, null)) {
			if (!c.moveToFirst()) return; // unknown region id; nothing to clear
			country = c.getString(0);
			admin1 = c.getString(1);
			admin2 = c.getString(2);
		}

		List<String> matching = new ArrayList<>();
		try (Cursor c = db.query("boundaries", new String[]{"region_id"}, null, null, null, null, null)) {
			while (c.moveToNext()) {
				String bId = c.getString(0);
				String[] triple = RegionResolver.resolveTriple(db, bId);
				if (triple != null && triple[0].equals(country) && triple[1].equals(admin1) && triple[2].equals(admin2)) {
					matching.add(bId);
				}
			}
		}
		for (String bId : matching) {
			ContentValues cv = new ContentValues();
			cv.putNull("imported_at");
			db.update("boundaries", cv, "region_id=?", new String[]{bId});
		}
	}

}