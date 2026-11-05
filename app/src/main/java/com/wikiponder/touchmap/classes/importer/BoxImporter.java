package com.wikiponder.touchmap.classes.importer;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.wikiponder.touchmap.R;
import com.wikiponder.touchmap.classes.CrashReporting;
import com.wikiponder.touchmap.classes.Utils;
import com.wikiponder.touchmap.classes.boxes.BoxManifest;
import com.wikiponder.touchmap.classes.boxes.BoxManifestRepository;
import com.wikiponder.touchmap.classes.db.OSMDatabaseHelper;
import com.wikiponder.touchmap.classes.db.UserEditsBackupHelper;
import com.wikiponder.touchmap.classes.geofabrik.GeofabrikIndex;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Downloads the pre-built map db for whichever planet-tiling "box" contains a given location (see
 * {@link BoxManifest}, built by {@code tools/build_global_cluster_dbs.py} and hosted at
 * {@code https://wikiponder.com/navia/global_boxes/}) and merges it into the app's own db — optionally
 * together with every box bordering it too (not capped at 4 — see {@link BoxManifest#findNeighbors};
 * the download screen's "larger map" option), so a user near the edge of one box isn't left with a
 * hard border a short walk away.
 *
 * <p>This is now the *only* way TouchMap downloads map data — there is no more per-country Geofabrik
 * PBF picker and no Overpass radius fallback. A box db is schema-identical to the app's own
 * {@code osm.db} (same {@code nodes}/{@code ways}/{@code way_cells}/{@code way_regions}/{@code regions}
 * tables — see {@code OSMDatabaseHelper.onCreate} and the box tool's own docstring), and every
 * feature in it is already tagged {@code region = OSMDatabaseHelper.REGION_WORLD} (the box tool does
 * no per-feature admin-region resolution), so merging is a straight bulk copy: {@code ATTACH} the
 * downloaded db and run {@code INSERT OR IGNORE INTO main.X SELECT * FROM box.X} for
 * {@code nodes}/{@code ways}/{@code way_cells}/{@code way_regions} — no per-row parsing, no
 * region-id remapping, and (per the box tool's docs) a way that touches more than one box always
 * carries its full, identical geometry in each one, so a way shared by two already-downloaded boxes
 * is a harmless duplicate `INSERT OR IGNORE`, never a partial/clipped merge. {@code regions} is
 * deliberately excluded from this copy — see {@link #mergeIntoAppDb}.
 *
 * <p>Pipeline (repeated per box when downloading neighbors too, always primary box first — see
 * {@link #importForLocation}): resolve the box(es) from the manifest -> download each
 * {@code .db.gz} -> gunzip -> attach + bulk-copy into the app db -> record it as imported (see
 * {@link OSMDatabaseHelper#markBoxImported}, which replaced the old Geofabrik-boundary-based
 * "imported_at" tracking — a box is a plain rectangle, not an admin polygon). A box's data is
 * merged and marked as soon as ITS OWN pipeline finishes, independent of every other box in the
 * run, so cancelling partway through a multi-box download (see {@link #cancel}) still leaves
 * whichever boxes already finished fully usable — nothing is rolled back.
 *
 * <p><b>Progress percent</b> is one continuous 0-100 across the WHOLE run (every box, every phase),
 * not a per-phase-per-box cycle: each box is weighted by its known download size ({@code gzSizeMb},
 * already in the manifest before any downloading starts — see {@link #importForLocation}), and
 * within a box the connect/download/extract/save phases are weighted {@link #DOWNLOAD_FRACTION}/
 * {@link #EXTRACT_FRACTION}/{@link #SAVE_FRACTION} of that box's share. See {@link #reportOverall}.
 */
public class BoxImporter implements Cancellable {
	private static final String TAG = "BoxImporter";

	private static final String PHASE_CONNECTING = "Connecting";
	private static final String PHASE_DOWNLOADING = "Downloading map";
	private static final String PHASE_EXTRACTING = "Extracting map";
	private static final String PHASE_SAVING = "Saving map";

	// How much of one box's weight each phase accounts for, as a running total (connecting itself
	// carries no measurable share — its own onProgress call always fires at the box's starting
	// point). Only the download phase has true byte-level granularity; extract and save (which
	// don't run long enough to be worth instrumenting the same way) advance in visible jumps
	// instead — extract in one jump when it finishes, save across its four existing checkpoints
	// (see reportSaveProgress).
	private static final double DOWNLOAD_FRACTION = 0.75;
	private static final double EXTRACT_FRACTION = 0.10;
	private static final double SAVE_FRACTION = 0.15; // 0.75 + 0.10 + 0.15 == 1.0

	private final Context context;
	private final ProgressListener listener;
	private volatile boolean cancelled = false;
	private volatile HttpURLConnection activeConnection;

	public BoxImporter(Context context, ProgressListener listener) {
		this.context = context.getApplicationContext();
		this.listener = listener;
	}

	@Override
	public void cancel() {
		cancelled = true;
		HttpURLConnection conn = activeConnection;
		if (conn != null) conn.disconnect();
	}

	/**
	 * Runs the full download+merge for whichever box contains (lat, lon), and — when
	 * {@code includeNeighbors} is true — also every box bordering it (see
	 * {@link BoxManifest#findNeighbors} — not capped at 4, a box's edge can border several smaller
	 * ones), skipping any that are empty. The primary box is always downloaded first, neighbors
	 * after. Blocking — call off the main thread. Fails outright only if the *primary* box (the one
	 * actually containing the location) is missing or empty; an empty/unreachable neighbor is just
	 * skipped, not a failure of the whole run.
	 */
	public void importForLocation(double lat, double lon, boolean includeNeighbors) {
		File gzFile = new File(context.getCacheDir(), "import_box.db.gz");
		File dbFile = new File(context.getCacheDir(), "import_box.db");
		boolean completed = false;
		int features = 0;
		try {
			if (listener != null) {
				listener.onProgress(PHASE_CONNECTING, context.getString(R.string.download_looking_up), 0);
			}
			BoxManifest manifest;
			try {
				// Bundled-asset only, no network — see BoxManifestRepository's class doc. A failure
				// here means the bundled boxes_manifest.json itself is missing/corrupt, not a
				// connectivity problem, so it's not folded into the download's own network error path.
				manifest = BoxManifestRepository.getOrFetch(context);
			} catch (Exception e) {
				fail("Could not read the map index: " + e.getMessage());
				return;
			}
			if (cancelled) return;

			BoxManifest.Entry primary = manifest.findContaining(lat, lon);
			if (primary == null) {
				fail("No map data is available for this area.");
				return;
			}
			if (primary.isEmpty()) {
				fail("There is no detailed map data to download for this area.");
				return;
			}

			// Primary box always first, neighbors after — see this method's own doc.
			List<BoxManifest.Entry> boxes = new ArrayList<>();
			boxes.add(primary);
			if (includeNeighbors) {
				for (BoxManifest.Entry n : manifest.findNeighbors(primary)) {
					if (!n.isEmpty()) boxes.add(n);
				}
			}

			double totalSizeMb = 0;
			for (BoxManifest.Entry box : boxes) totalSizeMb += box.gzSizeMb;
			// Guards a manifest with zero-reported sizes (shouldn't happen, but a weight of 0 for
			// every box would make the whole run report 100% from the very first byte) by falling
			// back to equal weight per box.
			boolean useEqualWeights = totalSizeMb <= 0;

			double completedWeight = 0;
			for (int i = 0; i < boxes.size(); i++) {
				if (cancelled) return;
				BoxManifest.Entry box = boxes.get(i);
				double boxWeight = useEqualWeights ? (1.0 / boxes.size()) : (box.gzSizeMb / totalSizeMb);
				downloadAndMergeOneBox(box, manifest.mapVersion, gzFile, dbFile, i + 1, boxes.size(), completedWeight, boxWeight);
				completedWeight += boxWeight;
				features += box.nodes + box.ways;
			}
			completed = true;
			try {
				// A rename/favorite recorded (via UserEditsBackupHelper, after Auto Backup restored
				// user_edits.db on a reinstall) against a real node id that wasn't imported yet at
				// startup only takes effect once that id actually appears in `nodes` — which is exactly
				// what this import may have just done. See UserEditsBackupHelper's class doc for why
				// this is always safe to call, restore or not.
				UserEditsBackupHelper.replayInto(context);
			} catch (Exception e) {
				// Never turn a successful import into a failure over this reconciliation step.
				Log.w(TAG, "replayInto after import failed", e);
			}
		} catch (Throwable t) {
			Log.e(TAG, "Box import failed", t);
			// The raw message below is all the user sees and logcat rolls over quickly, so record it.
			CrashReporting.reportNonFatal("box import", t);
			String msg = (t instanceof OutOfMemoryError)
					? "Ran out of memory importing this area."
					: (t.getMessage() != null ? t.getMessage() : t.toString());
			fail(msg);
		} finally {
			deleteQuietly(gzFile);
			deleteDbQuietly(dbFile);
		}
		if (completed && !cancelled && listener != null) {
			listener.onComplete(features);
		}
	}

	/** Downloads, extracts, merges and marks-imported a single box. {@code index}/{@code total} are
	 * for progress text ("area 2 of 5"); {@code completedWeight}/{@code boxWeight} are this box's
	 * share of the WHOLE run's progress bar — see {@link #reportOverall}. */
	private void downloadAndMergeOneBox(BoxManifest.Entry box, int mapVersion, File gzFile, File dbFile, int index, int total,
										 double completedWeight, double boxWeight) throws Exception {
		downloadBox(box, gzFile, index, total, completedWeight, boxWeight);
		if (cancelled) return;

		extract(gzFile, dbFile, index, total, completedWeight, boxWeight);
		if (cancelled) return;

		probeOpens(dbFile);
		if (cancelled) return;

		boolean merged = mergeIntoAppDb(dbFile, box, index, total, completedWeight, boxWeight);
		// False means cancel() landed between two table copies: the transaction rolled back, so the
		// old data and its old version stay. True means it committed, so mark it even if a cancel has
		// landed since, or the new data would still be labelled with the old version.
		if (!merged) return;
		OSMDatabaseHelper.markBoxImported(context, box.index, mapVersion, box.minLat, box.maxLat, box.minLon, box.maxLon);
	}

	/** Computes and reports one 0-100 percent for the WHOLE run from this box's already-completed
	 * share ({@code completedWeight}), this box's own share ({@code boxWeight}), and how far into
	 * that share the current phase has gotten ({@code withinBoxFraction}, 0..1). */
	private void reportOverall(String phase, String stageText, double completedWeight, double boxWeight,
								double withinBoxFraction) {
		if (listener == null) return;
		double overall = completedWeight + boxWeight * withinBoxFraction;
		int percent = (int) Math.round(Math.max(0.0, Math.min(1.0, overall)) * 100);
		listener.onProgress(phase, stageText, percent);
	}

	// ------------------------------------------------------------------ download

	private void downloadBox(BoxManifest.Entry box, File dest, int index, int total,
							  double completedWeight, double boxWeight) throws Exception {
		String url = BoxManifestRepository.boxDbUrl(box.index);
		reportOverall(PHASE_CONNECTING, context.getString(R.string.download_progress_connecting, index, total),
				completedWeight, boxWeight, 0.0);
		HttpURLConnection conn = GeofabrikIndex.openWithRedirects(url);
		activeConnection = conn;
		try {
			int code = conn.getResponseCode();
			if (cancelled) return;
			if (code != HttpURLConnection.HTTP_OK) {
				throw new Exception("Download HTTP " + code);
			}
			long totalBytes = conn.getContentLengthLong();
			long downloaded = 0;
			byte[] buf = new byte[65536];
			try (InputStream in = conn.getInputStream();
				 OutputStream out = new FileOutputStream(dest)) {
				int n;
				long lastReport = 0;
				while ((n = in.read(buf)) != -1) {
					if (cancelled) return;
					out.write(buf, 0, n);
					downloaded += n;
					if (downloaded - lastReport > 262144) {
						lastReport = downloaded;
						double bytePct = totalBytes > 0 ? (double) downloaded / totalBytes : 0.0;
						String stage = context.getString(R.string.download_progress_downloading,
								index, total, mb(downloaded), totalBytes > 0 ? mb(totalBytes) : "?");
						reportOverall(PHASE_DOWNLOADING, stage, completedWeight, boxWeight,
								DOWNLOAD_FRACTION * bytePct);
					}
				}
			}
		} catch (Exception e) {
			if (cancelled) return; // disconnect() from cancel() surfaces as an exception here — expected
			throw e;
		} finally {
			activeConnection = null;
			conn.disconnect();
		}
	}

	// ------------------------------------------------------------------ extract

	private void extract(File gzFile, File dest, int index, int total,
						  double completedWeight, double boxWeight) throws Exception {
		// Not instrumented byte-by-byte (unlike download) — this step is fast enough that a single
		// jump straight to "download's share of this box done" at the start, held until the save
		// phase's own first checkpoint moves things further, reads fine in practice.
		reportOverall(PHASE_EXTRACTING, context.getString(R.string.download_progress_extracting, index, total),
				completedWeight, boxWeight, DOWNLOAD_FRACTION);
		try (InputStream in = new GZIPInputStream(new java.io.FileInputStream(gzFile));
			 OutputStream out = new FileOutputStream(dest)) {
			byte[] buf = new byte[65536];
			int n;
			while ((n = in.read(buf)) != -1) {
				if (cancelled) return;
				out.write(buf, 0, n);
			}
		}
	}

	/** Sanity-checks the downloaded db actually opens before attaching it to the shared app connection
	 * — a corrupt/truncated download should fail cleanly here rather than surface as a confusing
	 * ATTACH or query error later. */
	private void probeOpens(File dbFile) throws Exception {
		// Not opened through SQLiteDatabase; the 16-byte SQLite header is enough to catch a
		// truncated/garbage download. (An earlier "no such table: box.nodes" on Android 15 was blamed
		// on such an open/close probe, but the real cause was ATTACH landing on another pooled
		// connection than the INSERTs — see OSMDatabaseHelper.onConfigure.)
		byte[] header = new byte[16];
		try (java.io.FileInputStream in = new java.io.FileInputStream(dbFile)) {
			int read = 0;
			while (read < header.length) {
				int n = in.read(header, read, header.length - read);
				if (n < 0) break;
				read += n;
			}
			if (read < header.length
					|| !"SQLite format 3\0".equals(new String(header, java.nio.charset.StandardCharsets.ISO_8859_1))) {
				throw new Exception("Downloaded map file is corrupt");
			}
		}
	}

	// ------------------------------------------------------------------ merge

	private static final String ALIAS = "box";

	private int mergeIndex, mergeTotal;
	private double mergeCompletedWeight, mergeBoxWeight;

	/** Bulk-copies every table from the downloaded box db into the app db, all in one transaction, so
	 * a failure or cancel leaves the old map as it was. Every row is already in the app's own schema
	 * (id conventions, region tagging, packed geometry — see class doc). The downloaded rows win over
	 * rows already on the phone, since a box can be newer than the copy of a shared way from a
	 * neighbour. When this box was downloaded before, rows it no longer lists are removed too.
	 * Manual points are never touched, favorite flags survive, and renames come back through
	 * UserEditsBackupHelper.replayInto after the import. Returns false if cancelled (nothing merged). */
	private boolean mergeIntoAppDb(File dbFile, BoxManifest.Entry box, int index, int total,
								 double completedWeight, double boxWeight) throws Exception {
		boolean replacing = OSMDatabaseHelper.isBoxImported(context, box.index);
		mergeIndex = index;
		mergeTotal = total;
		mergeCompletedWeight = completedWeight;
		mergeBoxWeight = boxWeight;
		reportOverall(PHASE_SAVING, context.getString(R.string.download_progress_saving, index, total, "data"),
				completedWeight, boxWeight, DOWNLOAD_FRACTION + EXTRACT_FRACTION);
		SQLiteDatabase appDb = OSMDatabaseHelper.getInstance(context).getWritableDatabase();
		appDb.execSQL("ATTACH DATABASE ? AS " + ALIAS, new Object[]{dbFile.getAbsolutePath()});
		try {
			appDb.beginTransaction();
			try {
				// Deliberately NOT copying box.regions: it only ever holds the single REGION_WORLD seed
				// row (see tools/build_global_cluster_dbs.py's init_box_db), which OSMDatabaseHelper.
				// onCreate already inserts into the app db unconditionally — so this copy was always a
				// no-op on a well-formed box. A handful of boxes built by an older tool version are
				// missing the `regions` table entirely though (found live: a real device hit "no such
				// table: box.regions" here and lost an otherwise perfectly good download over it), so
				// skip it rather than let a table with nothing worth copying anyway hard-fail the import.
				List<Long> favorites = favoriteNodeIds(appDb);
				if (replacing) deleteStaleNodes(appDb, box);
				appDb.execSQL("INSERT OR REPLACE INTO main.nodes SELECT * FROM " + ALIAS + ".nodes");
				restoreFavorites(appDb, favorites);
				reportSaveProgress(0.40, "nodes");
				// Checked between statements, not within one: a single execSQL call is a single
				// blocking native call and can't be interrupted mid-statement by this flag — see
				// downloadAndMergeOneBox's comment on markBoxImported. This only shortens how long a
				// cancel has to wait when it lands in the gap between two (usually fast) table copies;
				// it is not a guarantee cancel() takes effect quickly if one particular statement itself
				// is what's blocked (e.g. on a write-lock held by another connection) — see
				// ImportProgressDialog/MapActivity's own timeout for what actually unblocks the user in
				// that case.
				if (cancelled) return false;

				if (replacing) deleteStaleWays(appDb, box);
				appDb.execSQL("INSERT OR REPLACE INTO main.ways SELECT * FROM " + ALIAS + ".ways");
				reportSaveProgress(0.60, "ways");
				if (cancelled) return false;

				// A way's geometry may have changed, so its old cells must go with it.
				appDb.execSQL("DELETE FROM main.way_cells WHERE way_id IN (SELECT id FROM " + ALIAS + ".ways)");
				appDb.execSQL("INSERT OR IGNORE INTO main.way_cells SELECT * FROM " + ALIAS + ".way_cells");
				reportSaveProgress(0.80, "map index");
				if (cancelled) return false;

				appDb.execSQL("INSERT OR IGNORE INTO main.way_regions SELECT * FROM " + ALIAS + ".way_regions");
				reportSaveProgress(0.85, "map index");
				if (cancelled) return false;

				mergeEnvTables(appDb, box, replacing);
				reportSaveProgress(1.0, "map index");
				if (cancelled) return false;

				appDb.setTransactionSuccessful();
				return true;
			} finally {
				appDb.endTransaction();
			}
		} finally {
			// Always detach, even on failure — this app db connection is a long-lived singleton, and a
			// stuck attachment would break the *next* import (SQLite errors attaching the same alias
			// twice) as well as anything else sharing this connection.
			try {
				appDb.execSQL("DETACH DATABASE " + ALIAS);
			} catch (Exception e) {
				Log.w(TAG, "DETACH failed: " + e.getMessage());
			}
		}
	}

	private static List<Long> favoriteNodeIds(SQLiteDatabase db) {
		List<Long> ids = new ArrayList<>();
		try (Cursor c = db.rawQuery("SELECT id FROM main.nodes WHERE favorite=1", null)) {
			while (c.moveToNext()) ids.add(c.getLong(0));
		}
		return ids;
	}

	private static void restoreFavorites(SQLiteDatabase db, List<Long> ids) {
		for (long id : ids) {
			db.execSQL("UPDATE main.nodes SET favorite=1 WHERE id=?", new Object[]{id});
		}
	}

	/** Nodes belong to the one box whose rectangle holds them, so anything inside this rectangle that
	 * the new file lacks was deleted from the map. A favorite stays even then. The upper edges are
	 * open because a point exactly on a shared edge may belong to the neighbour. */
	private static void deleteStaleNodes(SQLiteDatabase db, BoxManifest.Entry box) {
		db.execSQL("DELETE FROM main.nodes WHERE cat_id!=? AND favorite=0"
						+ " AND lat>=? AND lat<? AND lon>=? AND lon<?"
						+ " AND id NOT IN (SELECT id FROM " + ALIAS + ".nodes)",
				new Object[]{OSMDatabaseHelper.CATEGORY_MANUAL, box.minLat, box.maxLat, box.minLon, box.maxLon});
	}

	/** A way is written to every box one of its points falls in, so a way on the phone with a point in
	 * this rectangle that the new file lacks is gone or has moved away. A way merely passing nearby
	 * belongs to a neighbour and must stay, which takes reading its points. */
	private static void deleteStaleWays(SQLiteDatabase db, BoxManifest.Entry box) {
		List<Long> candidates = new ArrayList<>();
		try (Cursor c = db.rawQuery("SELECT id FROM main.ways"
						+ " WHERE min_lat<=? AND max_lat>=? AND min_lon<=? AND max_lon>=?"
						+ " AND id NOT IN (SELECT id FROM " + ALIAS + ".ways)",
				new String[]{String.valueOf(box.maxLat), String.valueOf(box.minLat),
						String.valueOf(box.maxLon), String.valueOf(box.minLon)})) {
			while (c.moveToNext()) candidates.add(c.getLong(0));
		}
		for (long id : candidates) {
			if (!wayHasPointInBox(db, id, box)) continue;
			Object[] arg = {id};
			db.execSQL("DELETE FROM main.ways WHERE id=?", arg);
			db.execSQL("DELETE FROM main.way_cells WHERE way_id=?", arg);
			db.execSQL("DELETE FROM main.way_regions WHERE way_id=?", arg);
		}
	}

	private static boolean wayHasPointInBox(SQLiteDatabase db, long wayId, BoxManifest.Entry box) {
		byte[] points;
		try (Cursor c = db.rawQuery("SELECT points FROM main.ways WHERE id=?", new String[]{String.valueOf(wayId)})) {
			if (!c.moveToFirst()) return false;
			points = c.getBlob(0);
		}
		Utils.WayPoints way = Utils.unpackWayPoints(points);
		for (int i = 0; i < way.lats.length; i++) {
			if (box.contains(way.lats[i], way.lons[i])) return true;
		}
		return false;
	}

	/** Rivers, rail, airports, sea and lakes. Files from before these layers simply have no such tables. */
	private static void mergeEnvTables(SQLiteDatabase db, BoxManifest.Entry box, boolean replacing) {
		try (Cursor c = db.rawQuery("SELECT 1 FROM " + ALIAS + ".sqlite_master WHERE name='env_features'", null)) {
			if (!c.moveToFirst()) return;
		}
		if (replacing) {
			// A feature entirely inside this rectangle can only come from this box. One reaching
			// outside may still be listed by a neighbour, so it stays until that box is updated too.
			String staleIds = "SELECT id FROM main.env_features"
					+ " WHERE min_lat>=? AND max_lat<=? AND min_lon>=? AND max_lon<=?"
					+ " AND id NOT IN (SELECT id FROM " + ALIAS + ".env_features)";
			Object[] rect = {box.minLat, box.maxLat, box.minLon, box.maxLon};
			db.execSQL("DELETE FROM main.env_cells WHERE feature_id IN (" + staleIds + ")", rect);
			db.execSQL("DELETE FROM main.env_features WHERE id IN (" + staleIds + ")", rect);
		}
		String columns = "id,kind,type,name,role,relation_id,tags,min_lat,max_lat,min_lon,max_lon,points";
		db.execSQL("INSERT OR REPLACE INTO main.env_features (" + columns + ") SELECT " + columns
				+ " FROM " + ALIAS + ".env_features");
		db.execSQL("DELETE FROM main.env_cells WHERE feature_id IN (SELECT id FROM " + ALIAS + ".env_features)");
		db.execSQL("INSERT OR IGNORE INTO main.env_cells (feature_id,cell_x,cell_y)"
				+ " SELECT feature_id,cell_x,cell_y FROM " + ALIAS + ".env_cells");
	}

	/** {@code savePct} is 0..1 within the save phase itself; mapped into this box's own
	 * DOWNLOAD_FRACTION..1.0 remainder ({@link #EXTRACT_FRACTION} + {@link #SAVE_FRACTION}). */
	private void reportSaveProgress(double savePct, String what) {
		String stage = context.getString(R.string.download_progress_saving, mergeIndex, mergeTotal, what);
		reportOverall(PHASE_SAVING, stage, mergeCompletedWeight, mergeBoxWeight,
				DOWNLOAD_FRACTION + EXTRACT_FRACTION + SAVE_FRACTION * savePct);
	}

	// ------------------------------------------------------------------ helpers

	private static String mb(long bytes) {
		return String.format(java.util.Locale.US, "%.1f MB", bytes / 1_000_000.0);
	}

	private void fail(String message) {
		if (listener != null) listener.onError(message);
	}

	private static void deleteQuietly(File f) {
		//noinspection ResultOfMethodCallIgnored
		if (f != null) f.delete();
	}

	private static void deleteDbQuietly(File dbFile) {
		if (dbFile == null) return;
		deleteQuietly(dbFile);
		deleteQuietly(new File(dbFile.getPath() + "-journal"));
		deleteQuietly(new File(dbFile.getPath() + "-wal"));
		deleteQuietly(new File(dbFile.getPath() + "-shm"));
	}
}
