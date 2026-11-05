package com.wikiponder.touchmap.classes;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Point;
import android.location.Location;
import android.util.Log;

import com.wikiponder.touchmap.MyApplication;
import com.wikiponder.touchmap.R;
import com.wikiponder.touchmap.classes.db.OSMDatabaseHelper;
import com.wikiponder.touchmap.classes.db.RegionResolver;
import com.wikiponder.touchmap.classes.models.MapElement;
import com.wikiponder.touchmap.classes.models.NearestElement;
import com.wikiponder.touchmap.classes.models.NodeRecord;
import com.wikiponder.touchmap.classes.models.WayRecord;
import com.wikiponder.touchmap.classes.worldplaces.WorldPlacesRepository;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns the in-memory view of imported map data. The normalized {@code nodes}/{@code ways} tables
 * are queried by a rolling bounding box (the coarse cell index accelerates the query — see
 * {@link Utils#cellsForBbox}); features are held as flat lists in absolute lat/lon, so nearest-element
 * search and rendering are plain great-circle/equirectangular math with no tile-local coordinate
 * frames.
 *
 * <p><b>Two independent caches, not one.</b> {@code exploreCache} rolls with the map viewport
 * ({@link #requestLoadingMap}) and backs touch/hover exploration and rendering. {@code announceCache}
 * rolls with the device's live GPS position ({@link #requestLoadingAnnounceArea}) and backs
 * {@code BackgroundService}'s nearest-element announcements. They must stay separate: the user can pan
 * the map to browse a place far from where they physically are, and the background announcements must
 * keep describing their real surroundings regardless of what the map view is currently showing (and
 * vice versa — exploring a panned-away map must not be constrained by what's near the user physically).
 * Anything that changes the underlying data (manual add/delete/favorite, a finished download) reloads
 * both via {@link #reloadCurrentArea}.
 *
 * <p>Named MapTileManager for continuity with its many call sites; it no longer deals in tiles.
 */
public class MapTileManager {

	private static final String TAG = "MapTileManager";
	// How far beyond the visible viewport to load, so small pans stay within the cached area.
	// ~3x the screen width at minimum zoom (200m / MIN_ZOOM(1/8) = 1600m screen width -> ~4800m).
	// Now larger than MAX_LOAD_RADIUS_METERS below (that cap was tightened for memory, this wasn't
	// shrunk to match), so in practice the cap wins at every zoom level — kept as-is since it still
	// documents the margin that would apply if the cap were ever raised again.
	private static final double LOAD_PADDING_METERS = 4800.0;
	// Cap on the loaded radius, to bound memory/query cost. Reduced from 12000m to 3000m (following
	// the android:largeHeap removal) to further cut memory pressure and the size of every
	// touch/GPS/compass-tick candidatesNear() scan. Always kept above the max possible visible radius
	// at MIN_ZOOM (~1131m for a square screen — see LOAD_PADDING_METERS's comment for the 1600m
	// screen-width figure this is derived from), so loadArea's `Math.max(loadRadius, visibleRadius)`
	// never needs to override it: the historical "cap wins and every viewport gets zero padding" bug
	// only happens if the cap drops *below* the visible radius itself, which this still comfortably
	// avoids even though it's now below LOAD_PADDING_METERS.
	private static final double MAX_LOAD_RADIUS_METERS = 3000.0;
	// Fine-grained in-memory index cell size, separate from Contract.CELL_SIZE_METERS (the DB query
	// grid). Nearest-element search only scans a small neighborhood of these cells around the query
	// point instead of the entire loaded cache — necessary once real, dense urban data is loaded: a
	// single 24km x 24km area of the Duesseldorf import held ~130k nodes and ~120k ways, and a full
	// linear scan of that on every touch/GPS-tick/compass-tick is what actually caused the reported
	// slowdown and overheating (independent of how the cache itself is sized).
	private static final double QUERY_INDEX_CELL_METERS = 300.0;
	// Ring radii (in QUERY_INDEX_CELL_METERS cells) tried in order until candidates are found.
	private static final int[] SEARCH_RING_RADII = {1, 3};
	// Nominal "point, not viewport" radius the announce cache is loaded around — there's no screen
	// size to derive a visible area from, just the device's GPS position. Only used as the cache-hit
	// trigger box (see loadArea): the actual loaded radius is ANNOUNCE_LOAD_RADIUS_METERS below, so
	// this only controls how far the user can move before a reload is needed, not how far
	// announcements can reach.
	private static final double ANNOUNCE_VISIBLE_RADIUS_METERS = 200.0;
	// Hard cap on the announce cache's loaded radius — deliberately much smaller than the explore
	// cache's LOAD_PADDING_METERS/MAX_LOAD_RADIUS_METERS (which are tuned for on-screen viewport
	// coverage): a smaller candidate set directly speeds up every GPS/compass-triggered
	// candidatesNear() scan, at the cost of never announcing anything farther than this from the user
	// — an explicit, deliberate trade-off, not an oversight.
	private static final double ANNOUNCE_LOAD_RADIUS_METERS = 1500.0;
	// Cap on how many rows queryNearbyNodes returns — see its doc for why the ordering there is a
	// cheap lat/lon-delta sum rather than a real distance, and why a query-time LIMIT (not a Java-side
	// truncation after the fact) is what actually bounds the work done for a very dense/large radius.
	private static final int NEARBY_NODES_MAX = 10000;

	private final Context context;
	private final Set<String> displayedWayTypes;
	// Two independent serial loaders: viewport-load and GPS-driven announce-load requests each queue
	// and run in submit order off the main thread, but on separate queues/sequence counters — sharing
	// one would let a map pan cancel a still-queued GPS reload (or vice versa) even though they target
	// different caches (see the class doc for why the caches themselves must stay separate).
	private final ExecutorService loader = Executors.newSingleThreadExecutor();
	private final ExecutorService announceLoader = Executors.newSingleThreadExecutor();
	// Monotonic request ids; a queued load that is no longer the latest (for its own queue) bails immediately.
	private volatile long loadSeq = 0;
	private volatile long announceLoadSeq = 0;
	private int waitingStreamId = -1;

	/** One rolling in-memory snapshot of loaded features plus its spatial index. Snapshots are swapped
	 * atomically field-by-field; readers grab each reference once and iterate a stable list. See the
	 * class doc for why {@link #exploreCache} and {@link #announceCache} below are kept as two separate
	 * instances rather than one shared cache. */
	private static final class Cache {
		volatile List<NodeRecord> nodes = new ArrayList<>();
		volatile List<WayRecord> ways = new ArrayList<>();
		volatile double[] bbox = null; // {minLat, maxLat, minLon, maxLon}
		// Fine-grained spatial index over the same loaded features, rebuilt alongside them, keyed by a
		// packed QUERY_INDEX_CELL_METERS (cell_x,cell_y). Lets nearest-element search touch only a small
		// local neighborhood instead of the entire loaded cache — see QUERY_INDEX_CELL_METERS above.
		volatile Map<Long, List<NodeRecord>> nodesByCell = new HashMap<>();
		volatile Map<Long, List<WayRecord>> waysByCell = new HashMap<>();
	}

	// Rolls with the map viewport (requestLoadingMap) — touch/hover exploration and rendering.
	private final Cache exploreCache = new Cache();
	// Rolls with the device's live GPS position (requestLoadingAnnounceArea) — background announcements.
	private final Cache announceCache = new Cache();

	// aimed point
	private MapElement aimedElement;
	// last location + dimensions sent to requestLoadingMap (so a mutation can force a reload)
	private Location lastChangedMapViewCenter;
	private double lastWidth, lastHeight;
	// last location sent to requestLoadingAnnounceArea (so a mutation can force a reload)
	private Location lastAnnounceCenter;
	// Set by a screen other than MapActivity (e.g. NodeListActivity's "Zoom to Point on Map") that
	// wants the map recentered on a specific location the next time MapActivity is shown — since
	// MapTileManager is a shared per-app singleton (see MyApplication.getMapTileManager), this is a
	// simple, reliable hand-off with no Activity-result plumbing needed. MapActivity.onStart()
	// consumes it.
	private volatile Location pendingZoomTarget;
	// Same hand-off pattern, for NodeListActivity's "Aim at Point": aimedElement above is the actual
	// functional state (read by BackgroundService regardless of which screen is showing), this is
	// purely so MapActivity's onStart() knows to show its aim-cancel action button to match, without
	// doing so on every onStart() (which could otherwise clobber an unrelated "download this area" use
	// of that same shared button — see MapActivity.showAimCancelButton).
	private volatile MapElement pendingAimUiRefresh;

	private final AudioClient audioClient;
	private final WorldPlacesRepository worldPlacesRepository;
	private Set<Integer> catIdFilters;
	private boolean favoriteFilter;
	private volatile int touchFilterVersion = 0;
	private String keyword;

	// Types skipped in touch nearest-element search (too minor / noisy under the finger).
	private static final Set<String> TOUCH_EXCLUDED_TYPES = new HashSet<>(Arrays.asList("path", "cycleway"));
	// Way types excluded from junction detection (they don't form meaningful street intersections) —
	// mirrors what the old import-time detector excluded.
	private static final Set<String> INTERSECTION_EXCLUDED_TYPES =
			new HashSet<>(Arrays.asList("address", "cycleway", "footway", "pedestrian"));

	public MapTileManager(Context context) {
		this.context = context.getApplicationContext();
		aimedElement = null;
		audioClient = MyApplication.getAudioClient(context);
		worldPlacesRepository = MyApplication.getWorldPlacesRepository(context);

		// Initialize the set of way types to display
		displayedWayTypes = new HashSet<>(Arrays.asList(
				"motorway", "trunk", "primary", "secondary", "tertiary",
				"unclassified", "residential", "living_street", "service",
				"motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link"
		));

		selectAllCategories();
		this.keyword = null;
	}

	public void selectAllCategories() {
		catIdFilters = new HashSet<>();
		catIdFilters.add(-20); // CATEGORY_MANUAL
		catIdFilters.add(0);   // CATEGORY_ADDRESS
		catIdFilters.add(5);   // CATEGORY_HEALTH
		catIdFilters.add(10);  // CATEGORY_TRAFFIC
		catIdFilters.add(15);  // CATEGORY_BARRIER
		catIdFilters.add(20);  // CATEGORY_SHOP
		catIdFilters.add(25);  // CATEGORY_AMENITY
		catIdFilters.add(30);  // CATEGORY_LEISURE
		catIdFilters.add(35);  // CATEGORY_TOURISM
		catIdFilters.add(50);  // CATEGORY_NATURAL
		catIdFilters.add(60);  // CATEGORY_WAY
	}

	// ------------------------------------------------------------------ loading

	/**
	 * Called when the map view changes. Queues an off-main-thread load of the imported features
	 * around the new viewport into {@link #exploreCache}; the load itself no-ops if the area is
	 * already cached.
	 */
	/** Runs (on the loader thread) after each explore-cache load. Navia's map is redrawn twice a
	 * second by the compass anyway; Ambimap's never rotates, so it redraws on this instead. */
	private volatile Runnable exploreLoadedListener;

	public void setExploreLoadedListener(Runnable listener) {
		exploreLoadedListener = listener;
	}

	public void requestLoadingMap(Location center, double width, double height) {
		lastChangedMapViewCenter = center;
		lastWidth = width;
		lastHeight = height;
		final long seq = ++loadSeq;
		loader.submit(() -> {
			if (seq != loadSeq) return; // a newer request superseded this queued one
			double visibleRadius = Math.sqrt(width * width + height * height) / 2.0;
			loadArea(exploreCache, center, visibleRadius);
		});
	}

	/**
	 * Called on every GPS fix (see {@code BackgroundService.onLocationUpdated}). Queues an
	 * off-main-thread load of the imported features around the device's live position into
	 * {@link #announceCache} — kept on its own queue/cache so it never picks up wherever the map
	 * view happens to be panned to; the load itself no-ops if the area is already cached.
	 */
	public void requestLoadingAnnounceArea(Location center) {
		lastAnnounceCenter = center;
		final long seq = ++announceLoadSeq;
		announceLoader.submit(() -> {
			if (seq != announceLoadSeq) return; // a newer request superseded this queued one
			loadArea(announceCache, center, ANNOUNCE_VISIBLE_RADIUS_METERS);
		});
	}

	/** Shared load path for both {@link #exploreCache} and {@link #announceCache} — see
	 * {@link #requestLoadingMap} and {@link #requestLoadingAnnounceArea}. Audible load feedback and
	 * the "map failed to load" spoken message are explore-only: the announce cache reloads silently
	 * off GPS movement, and surfacing every one of those reloads to the user would be constant noise. */
	private void loadArea(Cache cache, Location center, double visibleRadius) {
		boolean isExplore = (cache == exploreCache);
		double[] visibleBox = bboxAround(center, visibleRadius);

		// Cache hit: the current cache already covers the visible area.
		double[] cached = cache.bbox;
		if (cached != null && contains(cached, visibleBox)) {
			return;
		}

		double loadRadius;
		if (isExplore) {
			loadRadius = Math.min(visibleRadius + LOAD_PADDING_METERS, MAX_LOAD_RADIUS_METERS);
			// Never load less than the visible area.
			loadRadius = Math.max(loadRadius, visibleRadius);
		} else {
			// Announce cache: a fixed cap, not the viewport-padding formula above — see
			// ANNOUNCE_LOAD_RADIUS_METERS's doc for why this is deliberately small.
			loadRadius = ANNOUNCE_LOAD_RADIUS_METERS;
		}
		double[] box = bboxAround(center, loadRadius);

		// The waiting/done sounds tell a blind user the map is loading; Ambimap's map is just drawn.
		final boolean loadSounds = isExplore && !MyApplication.isAmbimap();
		if (loadSounds) {
			audioClient.stopSound(waitingStreamId);
			waitingStreamId = audioClient.playSound(AudioClient.SOUND_WAIT, 0.1, -1, 1);
		}
		try {
			List<NodeRecord> nodes = queryNodes(box);
			List<WayRecord> ways = queryWays(box);
			// No imported data at all in this area: fall back to the world-places gazetteer, merged
			// into the same nodes list so nearest-element search and rendering need no special
			// casing — see passesFilter's world-place bypass for why filters don't hide them.
			if (nodes.isEmpty() && ways.isEmpty()
					&& worldPlacesRepository != null && worldPlacesRepository.isReady()) {
				nodes = queryWorldPlaceNodes(box);
			} else if (!ways.isEmpty()) {
				// Synthesize junction nodes from the ways just loaded for this area — see
				// findIntersections for why this replaced a much slower whole-region pass at import time.
				List<NodeRecord> intersections = findIntersections(ways);
				if (!intersections.isEmpty()) {
					// Intersections already persisted from an earlier load of an overlapping area are
					// already present in `nodes` (queryNodes returns them like any other node once
					// they're real rows) — skip those so a re-found junction isn't listed twice in this
					// snapshot, and only ever try to persist the ones that are genuinely new.
					Set<Long> seenIds = new HashSet<>();
					for (NodeRecord nd : nodes) seenIds.add(nd.id);
					List<NodeRecord> newOnes = new ArrayList<>();
					for (NodeRecord in : intersections) {
						if (seenIds.add(in.id)) newOnes.add(in);
					}
					if (!newOnes.isEmpty()) {
						try {
							// Only persisted once — whichever cache finds them first; CONFLICT_IGNORE in
							// persistIntersections makes the other cache's later attempt a cheap no-op.
							persistIntersections(newOnes);
						} catch (Exception e) {
							// Best-effort: the freshly computed intersections are still fully usable for
							// this load even if the write fails — they'll just be recomputed (and their
							// persistence retried) the next time this area is loaded, instead of being
							// remembered permanently.
							Log.w(TAG, "Failed to persist intersections", e);
						}
						nodes.addAll(newOnes);
					}
				}
			}
			cache.nodes = nodes;
			cache.ways = ways;
			cache.nodesByCell = indexNodesByCell(nodes);
			cache.waysByCell = indexWaysByCell(ways);
			cache.bbox = box;
			Runnable loaded = exploreLoadedListener;
			if (isExplore && loaded != null) loaded.run();
			if (loadSounds) {
				audioClient.stopSound(waitingStreamId);
				audioClient.playSound(AudioClient.SOUND_BEEP, 0.2, 0, 1.5);
			}
		} catch (Throwable t) {
			// Catches OutOfMemoryError too, not just Exception: a dense loaded area (bounded by
			// MAX_LOAD_RADIUS_METERS, but still potentially tens of thousands of nodes/ways in a big
			// city — see the CPU/heat fix earlier this project) is real memory pressure, and silently
			// leaving the user without feedback here reads as the map just being broken.
			Log.e(TAG, "loadArea failed (" + (isExplore ? "explore" : "announce") + ")", t);
			// The user only hears the generic map_load_failed_message, so without this the real cause
			// (e.g. the 1.1.4-1.1.6 msgpack R8 bug) is invisible remotely.
			CrashReporting.reportNonFatal("map load", t);
			if (isExplore) {
				audioClient.stopSound(waitingStreamId);
				MyApplication.getAnnouncementManager(context).onMapMessage(
						context.getString(R.string.map_load_failed_message));
			}
		}
	}

	private static long packCell(int cx, int cy) {
		return (((long) cx) << 32) | (cy & 0xffffffffL);
	}

	private static Map<Long, List<NodeRecord>> indexNodesByCell(List<NodeRecord> nodes) {
		Map<Long, List<NodeRecord>> index = new HashMap<>();
		for (NodeRecord nd : nodes) {
			int[] cell = Utils.latLonToIndex(nd.lat, nd.lon, QUERY_INDEX_CELL_METERS);
			index.computeIfAbsent(packCell(cell[0], cell[1]), k -> new ArrayList<>()).add(nd);
		}
		return index;
	}

	private static Map<Long, List<WayRecord>> indexWaysByCell(List<WayRecord> ways) {
		Map<Long, List<WayRecord>> index = new HashMap<>();
		for (WayRecord w : ways) {
			// Registered under every fine cell its bbox touches — most ways are short and land in
			// just one or two; only genuinely long ways (rare in this fine a grid) touch more.
			for (int[] cell : Utils.cellsForBbox(w.minLat, w.maxLat, w.minLon, w.maxLon, QUERY_INDEX_CELL_METERS)) {
				index.computeIfAbsent(packCell(cell[0], cell[1]), k -> new ArrayList<>()).add(w);
			}
		}
		return index;
	}

	/**
	 * Gathers candidate nodes/ways near (lat, lon) by expanding ring search over the fine cell index
	 * (SEARCH_RING_RADII) of the given cache, stopping at the first ring that yields any candidates —
	 * so a nearest-element query touches a small local neighborhood, not the entire loaded cache.
	 */
	private NodeWayCandidates candidatesNear(double lat, double lon, Cache cache) {
		Map<Long, List<NodeRecord>> nIdx = cache.nodesByCell;
		Map<Long, List<WayRecord>> wIdx = cache.waysByCell;
		int[] center = Utils.latLonToIndex(lat, lon, QUERY_INDEX_CELL_METERS);
		List<NodeRecord> nodes = new ArrayList<>();
		List<WayRecord> ways = new ArrayList<>();
		for (int radius : SEARCH_RING_RADII) {
			nodes.clear();
			ways.clear();
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dy = -radius; dy <= radius; dy++) {
					long key = packCell(center[0] + dx, center[1] + dy);
					List<NodeRecord> nb = nIdx.get(key);
					if (nb != null) nodes.addAll(nb);
					List<WayRecord> wb = wIdx.get(key);
					if (wb != null) ways.addAll(wb);
				}
			}
			if (!nodes.isEmpty() || !ways.isEmpty()) break;
		}
		return new NodeWayCandidates(nodes, ways);
	}

	private static final class NodeWayCandidates {
		final List<NodeRecord> nodes;
		final List<WayRecord> ways;
		NodeWayCandidates(List<NodeRecord> nodes, List<WayRecord> ways) {
			this.nodes = nodes;
			this.ways = ways;
		}
	}

	/**
	 * Clears both caches and reloads them at their last-known centers — used after anything that
	 * changes the underlying data (manual add/delete/favorite-toggle, a map download finishing,
	 * "delete all downloaded maps"). Both the touch-exploration cache and the announcement cache are
	 * reloaded, not just whichever one the triggering screen happens to relate to: e.g. downloading
	 * the map for wherever the user physically is doesn't move the map view at all, but must still
	 * refresh what {@code BackgroundService} announces, and vice versa for a change made while
	 * panned away from the user's real location.
	 */
	public void reloadCurrentArea() {
		exploreCache.bbox = null;
		if (lastChangedMapViewCenter != null) {
			requestLoadingMap(lastChangedMapViewCenter, lastWidth, lastHeight);
		}
		announceCache.bbox = null;
		if (lastAnnounceCenter != null) {
			requestLoadingAnnounceArea(lastAnnounceCenter);
		}
	}

	private List<NodeRecord> queryNodes(double[] box) {
		List<NodeRecord> out = new ArrayList<>();
		List<int[]> cells = Utils.cellsForBbox(box[0], box[1], box[2], box[3]);
		if (cells.isEmpty()) return out;
		StringBuilder where = new StringBuilder("(");
		List<String> args = new ArrayList<>();
		for (int i = 0; i < cells.size(); i++) {
			if (i > 0) where.append(" OR ");
			where.append("(cell_x=? AND cell_y=?)");
			args.add(String.valueOf(cells.get(i)[0]));
			args.add(String.valueOf(cells.get(i)[1]));
		}
		where.append(") AND lat>=? AND lat<=? AND lon>=? AND lon<=?");
		args.add(String.valueOf(box[0]));
		args.add(String.valueOf(box[1]));
		args.add(String.valueOf(box[2]));
		args.add(String.valueOf(box[3]));

		SQLiteDatabase db = OSMDatabaseHelper.getInstance(context).getReadableDatabase();
		try (Cursor c = db.query("nodes",
				new String[]{"id", "lat", "lon", "cat_id", "favorite", "type", "name", "tags"},
				where.toString(), args.toArray(new String[0]), null, null, null)) {
			while (c.moveToNext()) {
				out.add(new NodeRecord(
						c.getLong(0), c.getDouble(1), c.getDouble(2), c.getInt(3),
						c.getString(5), c.getString(6), c.getBlob(7), c.getInt(4) > 0));
			}
		}
		return out;
	}

	/** Raw row pulled out of the `ways` cursor before it's closed — see {@link #queryWays}. */
	private static final class RawWayRow {
		final long id;
		final int catId;
		final String type, name;
		final byte[] tags, pointsBlob;
		final double minLat, maxLat, minLon, maxLon;

		RawWayRow(long id, int catId, String type, String name, byte[] tags, byte[] pointsBlob,
				  double minLat, double maxLat, double minLon, double maxLon) {
			this.id = id;
			this.catId = catId;
			this.type = type;
			this.name = name;
			this.tags = tags;
			this.pointsBlob = pointsBlob;
			this.minLat = minLat;
			this.maxLat = maxLat;
			this.minLon = minLon;
			this.maxLon = maxLon;
		}
	}

	private List<WayRecord> queryWays(double[] box) {
		List<int[]> cells = Utils.cellsForBbox(box[0], box[1], box[2], box[3]);
		if (cells.isEmpty()) return new ArrayList<>();
		StringBuilder sql = new StringBuilder(
				"SELECT w.id, w.cat_id, w.type, w.name, w.tags, w.min_lat, w.max_lat, w.min_lon, w.max_lon, w.points "
						+ "FROM ways w JOIN way_cells wc ON w.id = wc.way_id WHERE (");
		List<String> args = new ArrayList<>();
		for (int i = 0; i < cells.size(); i++) {
			if (i > 0) sql.append(" OR ");
			sql.append("(wc.cell_x=? AND wc.cell_y=?)");
			args.add(String.valueOf(cells.get(i)[0]));
			args.add(String.valueOf(cells.get(i)[1]));
		}
		// Bounding-box overlap so cells that only clip a corner of the box don't pull in far-off ways.
		sql.append(") AND w.max_lat>=? AND w.min_lat<=? AND w.max_lon>=? AND w.min_lon<=? GROUP BY w.id");
		args.add(String.valueOf(box[0]));
		args.add(String.valueOf(box[1]));
		args.add(String.valueOf(box[2]));
		args.add(String.valueOf(box[3]));

		// Two passes, deliberately: pull every raw row out of the cursor first and close it, THEN
		// unpack each way's msgpack points blob. Unpacking is pure CPU work — it doesn't need the db
		// connection at all — but with it inlined into the `while (c.moveToNext())` loop, the cursor
		// (and the SQLite connection backing it) stayed checked out of the pool for as long as
		// unpacking every way in the viewport took. On a dense/large loaded area (real case: a
		// just-downloaded Iran box) that was long enough to starve the pool's small number of read
		// connections, blocking the *main* thread's own concurrent read (MapTileManager.isTileImported,
		// called from touch/hover handling) for 10+ seconds — a real "Input dispatching timed out" ANR,
		// same underlying SQLiteConnectionPool-exhaustion class of bug as the DB-migration ANR fixed
		// earlier, different trigger. Closing the cursor as fast as possible fixes it regardless of how
		// much unpacking work follows.
		List<RawWayRow> rows = new ArrayList<>();
		SQLiteDatabase db = OSMDatabaseHelper.getInstance(context).getReadableDatabase();
		try (Cursor c = db.rawQuery(sql.toString(), args.toArray(new String[0]))) {
			while (c.moveToNext()) {
				rows.add(new RawWayRow(
						c.getLong(0), c.getInt(1), c.getString(2), c.getString(3), c.getBlob(4), c.getBlob(9),
						c.getDouble(5), c.getDouble(6), c.getDouble(7), c.getDouble(8)));
			}
		}

		List<WayRecord> out = new ArrayList<>(rows.size());
		for (RawWayRow r : rows) {
			Utils.WayPoints pts = Utils.unpackWayPoints(r.pointsBlob);
			out.add(new WayRecord(r.id, r.catId, r.type, r.name, r.tags, pts.lats, pts.lons,
					r.minLat, r.maxLat, r.minLon, r.maxLon));
		}
		return out;
	}

	/**
	 * Queries the attached world-places gazetteer's {@code nodes} table using the same cell/bbox
	 * shape as {@link #queryNodes}, so results merge into loadedNodes/nodesByCell indistinguishably
	 * from real data (only their catId, in the CATEGORY_WORLD_* range, marks them as such).
	 */
	private List<NodeRecord> queryWorldPlaceNodes(double[] box) {
		List<NodeRecord> out = new ArrayList<>();
		List<int[]> cells = Utils.cellsForBbox(box[0], box[1], box[2], box[3]);
		if (cells.isEmpty()) return out;
		StringBuilder where = new StringBuilder("(");
		List<String> args = new ArrayList<>();
		for (int i = 0; i < cells.size(); i++) {
			if (i > 0) where.append(" OR ");
			where.append("(cell_x=? AND cell_y=?)");
			args.add(String.valueOf(cells.get(i)[0]));
			args.add(String.valueOf(cells.get(i)[1]));
		}
		where.append(") AND lat>=? AND lat<=? AND lon>=? AND lon<=?");
		args.add(String.valueOf(box[0]));
		args.add(String.valueOf(box[1]));
		args.add(String.valueOf(box[2]));
		args.add(String.valueOf(box[3]));

		SQLiteDatabase db = OSMDatabaseHelper.getInstance(context).getReadableDatabase();
		worldPlacesRepository.attachTo(db);
		try (Cursor c = db.query(WorldPlacesRepository.ATTACHED_ALIAS + ".nodes",
				new String[]{"id", "lat", "lon", "cat_id", "favorite", "type", "name", "tags"},
				where.toString(), args.toArray(new String[0]), null, null, null)) {
			while (c.moveToNext()) {
				out.add(new NodeRecord(
						c.getLong(0), c.getDouble(1), c.getDouble(2), c.getInt(3),
						c.getString(5), c.getString(6), c.getBlob(7), c.getInt(4) > 0));
			}
		} catch (Exception e) {
			Log.w(TAG, "queryWorldPlaceNodes failed", e);
		}
		return out;
	}

	/**
	 * Synthesizes intersection nodes among the just-loaded ways: a point shared by two or more ways
	 * with distinct non-empty names is a junction. Computed here — over only the currently loaded
	 * viewport's ways — instead of once at import time over the *whole* imported region; that
	 * whole-region SQL pass (matching by shared original OSM node id) was the slowest part of
	 * importing a large area. This matches by exact coordinate instead, since a persisted way only
	 * carries absolute lat/lon (no original OSM node ids survive past import) — the same
	 * "same coordinate == same point" assumption {@code BoxImporter}'s bulk merge already
	 * relies on, since OSM coordinates are stable across however many ways reference them.
	 */
	private static List<NodeRecord> findIntersections(List<WayRecord> ways) {
		Map<Long, TreeSet<String>> namesByCoord = new HashMap<>();
		Map<Long, double[]> latLonByCoord = new HashMap<>();
		for (WayRecord w : ways) {
			if (w.name == null || w.name.isEmpty()) continue; // can never supply a 2nd distinct name
			if (INTERSECTION_EXCLUDED_TYPES.contains(w.type)) continue;
			for (int i = 0; i < w.lats.length; i++) {
				long key = coordKey(w.lats[i], w.lons[i]);
				namesByCoord.computeIfAbsent(key, k -> new TreeSet<>()).add(w.name);
				latLonByCoord.putIfAbsent(key, new double[]{w.lats[i], w.lons[i]});
			}
		}
		List<NodeRecord> out = new ArrayList<>();
		for (Map.Entry<Long, TreeSet<String>> e : namesByCoord.entrySet()) {
			TreeSet<String> names = e.getValue();
			if (names.size() < 2) continue;
			double[] latLon = latLonByCoord.get(e.getKey());
			// Negative, so it can never collide with a real persisted node id (always positive —
			// same ID_OFFSET convention the box-building tool uses, see BoxImporter) even though these
			// are never written to `nodes`;
			// they only need to be unique (and stable across reloads) within one loaded snapshot.
			long id = -Math.abs(e.getKey()) - 1;
			out.add(new NodeRecord(id, latLon[0], latLon[1], OSMDatabaseHelper.CATEGORY_INTERSECTION,
					"intersection", String.join("|", names), null, false));
		}
		return out;
	}

	/** Packs a lat/lon into a single long as two E7 integers — same convention used to match
	 * coordinates elsewhere (e.g. packed way-point storage, see {@code Utils.packWayPoints}). */
	private static long coordKey(double lat, double lon) {
		int latE7 = (int) Math.round(lat * 1e7);
		int lonE7 = (int) Math.round(lon * 1e7);
		return (((long) latE7) << 32) | (lonE7 & 0xffffffffL);
	}

	/**
	 * Writes newly-found intersections into {@code nodes} so they persist like any other node
	 * (become favoritable, show up via a plain {@link #queryNodes} on a later load without needing
	 * to be recomputed). Each intersection's id is deterministic from its coordinate (see
	 * {@link #findIntersections}), so re-finding the same junction from a different/overlapping
	 * viewport load always maps to the exact same row — {@code CONFLICT_IGNORE} makes the insert a
	 * cheap no-op on that primary-key collision instead of creating a duplicate. That id space
	 * (always negative) never overlaps a real imported node's id (always positive — the ID_OFFSET
	 * convention shared by the box-building tool, see {@code BoxImporter}) or a manually-added node's
	 * id (small positive integers — see {@code OSMDatabaseHelper.insertNode}), so there's no risk of
	 * colliding with or clobbering either.
	 */
	private void persistIntersections(List<NodeRecord> newOnes) {
		SQLiteDatabase db = OSMDatabaseHelper.getInstance(context).getWritableDatabase();
		db.beginTransaction();
		try {
			for (NodeRecord nd : newOnes) {
				int regionId = RegionResolver.resolveByLocation(db, nd.lat, nd.lon);
				int[] cell = Utils.latLonToIndex(nd.lat, nd.lon);
				ContentValues cv = new ContentValues();
				cv.put("id", nd.id);
				cv.put("lat", nd.lat);
				cv.put("lon", nd.lon);
				cv.put("cell_x", cell[0]);
				cv.put("cell_y", cell[1]);
				cv.put("cat_id", OSMDatabaseHelper.CATEGORY_INTERSECTION);
				cv.put("favorite", 0);
				cv.put("type", "intersection");
				cv.put("name", nd.name);
				cv.putNull("tags");
				cv.put("region", regionId);
				db.insertWithOnConflict("nodes", null, cv, SQLiteDatabase.CONFLICT_IGNORE);
			}
			db.setTransactionSuccessful();
		} finally {
			db.endTransaction();
		}
	}

	public void release() {
		if (waitingStreamId >= 0)
			audioClient.stopSound(waitingStreamId);
		loader.shutdownNow();
		announceLoader.shutdownNow();
	}

	// ------------------------------------------------------------------ geometry helpers

	private static double[] bboxAround(Location center, double radiusMeters) {
		double lat = center.getLatitude();
		double lon = center.getLongitude();
		double dLat = Math.toDegrees(radiusMeters / Contract.EARTH_RADIUS);
		double dLon = Math.toDegrees(radiusMeters / (Contract.EARTH_RADIUS * Math.cos(Math.toRadians(lat))));
		return new double[]{lat - dLat, lat + dLat, lon - dLon, lon + dLon};
	}

	private static boolean contains(double[] outer, double[] inner) {
		return outer[0] <= inner[0] && outer[1] >= inner[1]
				&& outer[2] <= inner[2] && outer[3] >= inner[3];
	}

	/** Meters east of a reference longitude at a given latitude. */
	private static double metersEast(double dLonDeg, double cosLat) {
		return Math.toRadians(dLonDeg) * cosLat * Contract.EARTH_RADIUS;
	}

	/** Meters north of a reference latitude. */
	private static double metersNorth(double dLatDeg) {
		return Math.toRadians(dLatDeg) * Contract.EARTH_RADIUS;
	}

	private static double latFromMeters(double y, double refLat) {
		return refLat + Math.toDegrees(y / Contract.EARTH_RADIUS);
	}

	private static double lonFromMeters(double x, double refLon, double cosLat) {
		return refLon + Math.toDegrees(x / (Contract.EARTH_RADIUS * cosLat));
	}

	// ------------------------------------------------------------------ filters

	/**
	 * Category/zoom/favorite gating shared by nodes and ways (keyword handled by matchesKeyword).
	 * World-place gazetteer entries always pass: they only ever appear in loadedNodes as a fallback
	 * when an area has no imported data at all (see queryWorldPlaceNodes), so there is never a real
	 * element for the user's filters to prefer them over — showing them unconditionally beats
	 * silently hiding the only content available because it doesn't match a category/favorite filter
	 * meant for real OSM data.
	 */
	private boolean passesFilter(int catId, String type, boolean favorite, float zoomLevel, boolean includeRoads) {
		if (OSMDatabaseHelper.isWorldPlaceCategory(catId)) return true;
		if (calculateDetailsLevel(catId, type) > zoomLevel) return false;
		if (TOUCH_EXCLUDED_TYPES.contains(type)) return false;
		if (!includeRoads && catId == OSMDatabaseHelper.CATEGORY_WAY) return false;
		if (!catIdFilters.contains(catId)) return false;
		return !favoriteFilter || favorite;
	}

	// keyword needs the element's name; kept as a separate check to avoid building strings when unset.
	private boolean matchesKeyword(String type, String name) {
		if (keyword == null) return true;
		return (type + " " + name).contains(keyword);
	}

	/** Port of the per-element detail level used to hide fine detail when zoomed out. */
	private static float calculateDetailsLevel(int catId, String type) {
		switch (catId) {
			case OSMDatabaseHelper.CATEGORY_ADDRESS: return 0.5f;
			case OSMDatabaseHelper.CATEGORY_AMENITY: return 0.5f;
			case OSMDatabaseHelper.CATEGORY_BARRIER: return 1;
			case OSMDatabaseHelper.CATEGORY_HEALTH: return 0;
			case OSMDatabaseHelper.CATEGORY_LEISURE: return 0.5f;
			case OSMDatabaseHelper.CATEGORY_MANUAL: return 0;
			case OSMDatabaseHelper.CATEGORY_NATURAL: return 2;
			case OSMDatabaseHelper.CATEGORY_NONE: return 4;
			case OSMDatabaseHelper.CATEGORY_SHOP: return 0.25f;
			case OSMDatabaseHelper.CATEGORY_TOURISM: return 0.25f;
			case OSMDatabaseHelper.CATEGORY_TRAFFIC: return 1;
			case OSMDatabaseHelper.CATEGORY_WAY:
				if (type.equals("pedestrian") || type.equals("footway") || type.equals("path")
						|| type.equals("cycleway") || type.equals("steps") || type.equals("corridor")
						|| type.equals("via_ferrata") || type.equals("service"))
					return 2;
				return 0;
			default:
				return 0;
		}
	}

	// ------------------------------------------------------------------ nearest-element queries

	public NearestElement getNearestElement(Location location, boolean includeRoads) {
		return getNearestElement(location, 4, includeRoads);
	}

	public NearestElement getNearestElement(Location location, float zoomLevel, boolean includeRoads) {
		return getNearestElement(location, zoomLevel, includeRoads, 0);
	}

	/**
	 * What is under a finger at {@code location}. Points of interest win over roads and other ways
	 * whenever the point is within {@code nodePreferenceMeters} of being as close as the nearest way:
	 * many points (crossings, traffic lights, bus stops) sit exactly on a road's centre line, so a
	 * plain "nearest wins" rule made them unreachable — the road was always at distance zero. The
	 * caller passes a fixed on-screen distance converted to metres at the current zoom, so the
	 * behaviour is the same whether the map is zoomed in or out.
	 */
	public NearestElement getNearestElement(Location location, float zoomLevel, boolean includeRoads,
											double nodePreferenceMeters) {
		// Touch/hover exploration — always the map-viewport cache, even if it's panned away from
		// the user's physical location, since this answers "what's under my finger on screen".
		NodeWayCandidates candidates = candidatesNear(location.getLatitude(), location.getLongitude(), exploreCache);
		List<NodeRecord> nodes = candidates.nodes;
		List<WayRecord> ways = candidates.ways;

		double touchLat = location.getLatitude();
		double touchLon = location.getLongitude();
		double cosLat = Math.cos(Math.toRadians(touchLat));

		double nodeMinDistSq = Double.MAX_VALUE;
		NodeRecord bestNode = null;
		double wayMinDistSq = Double.MAX_VALUE;
		WayRecord bestWay = null;
		double wayLat = 0, wayLon = 0;

		for (NodeRecord nd : nodes) {
			boolean isWorldPlace = OSMDatabaseHelper.isWorldPlaceCategory(nd.catId);
			if (!isWorldPlace) {
				if (!passesFilter(nd.catId, nd.type, nd.favorite, zoomLevel, includeRoads)) continue;
				if (!matchesKeyword(nd.type, nd.name)) continue;
			}
			double dx = metersEast(nd.lon - touchLon, cosLat);
			double dy = metersNorth(nd.lat - touchLat);
			double d2 = dx * dx + dy * dy;
			if (d2 < nodeMinDistSq) {
				nodeMinDistSq = d2;
				bestNode = nd;
			}
		}

		for (WayRecord w : ways) {
			if (!passesFilter(w.catId, w.type, false, zoomLevel, includeRoads)) continue;
			if (!matchesKeyword(w.type, w.name)) continue;
			int n = w.lats.length;
			for (int j = 0; j < n - 1; j++) {
				double x1 = metersEast(w.lons[j] - touchLon, cosLat);
				double y1 = metersNorth(w.lats[j] - touchLat);
				double x2 = metersEast(w.lons[j + 1] - touchLon, cosLat);
				double y2 = metersNorth(w.lats[j + 1] - touchLat);
				Point nearest = new Point();
				double d2 = Utils.getDistanceSquareToLineSegment(0, 0, (int) x1, (int) y1, (int) x2, (int) y2, nearest);
				if (d2 < wayMinDistSq) {
					wayMinDistSq = d2;
					bestWay = w;
					wayLat = latFromMeters(nearest.y, touchLat);
					wayLon = lonFromMeters(nearest.x, touchLon, cosLat);
				}
			}
		}

		boolean useNode = bestNode != null
				&& (bestWay == null || Math.sqrt(nodeMinDistSq) - nodePreferenceMeters <= Math.sqrt(wayMinDistSq));
		if (useNode) {
			MapElement el = new MapElement(bestNode.favorite, bestNode.id, bestNode.lat, bestNode.lon,
					bestNode.catId, bestNode.type, bestNode.name, bestNode.tags);
			return new NearestElement(Math.sqrt(nodeMinDistSq), el);
		}
		if (bestWay != null) {
			MapElement el = new MapElement(false, bestWay.id, wayLat, wayLon,
					bestWay.catId, bestWay.type, bestWay.name, bestWay.tags);
			return new NearestElement(Math.sqrt(wayMinDistSq), el);
		}

		return new NearestElement(Double.MAX_VALUE, null);
	}

	public NearestElement getNearestElementAhead(Location userLocation, double bearing, double speed, double minCosine, boolean includeRoads) {
		// Background announcement (BackgroundService) — always the GPS-driven announce cache, never
		// whatever the map view happens to be panned to.
		NodeWayCandidates candidates = candidatesNear(userLocation.getLatitude(), userLocation.getLongitude(), announceCache);
		List<NodeRecord> nodes = candidates.nodes;
		List<WayRecord> ways = candidates.ways;

		double userLat = userLocation.getLatitude();
		double userLon = userLocation.getLongitude();
		double cosLat = Math.cos(Math.toRadians(userLat));
		double bearingRad = Math.toRadians(bearing);

		double minDistance = Double.MAX_VALUE;
		double minWeighted = Double.MAX_VALUE;
		NodeRecord bestNode = null;
		WayRecord bestWay = null;
		double bestLat = 0, bestLon = 0;

		for (NodeRecord nd : nodes) {
			if (TOUCH_EXCLUDED_TYPES.contains(nd.type)) continue;
			double x = metersEast(nd.lon - userLon, cosLat);
			double y = metersNorth(nd.lat - userLat);
			double distance = Math.hypot(x, y);
			double cosine = Utils.cosineBetween(bearingRad, 0, 0, x, y);
			if (cosine >= minCosine) {
				double weightFactor = 1 + (1 + cosine) * speed / 4.0;
				double weightedDistance = distance / weightFactor;
				if (weightedDistance < minWeighted) {
					minDistance = distance;
					minWeighted = weightedDistance;
					bestNode = nd;
					bestWay = null;
					bestLat = nd.lat;
					bestLon = nd.lon;
				}
			}
		}

		for (WayRecord w : ways) {
			if (TOUCH_EXCLUDED_TYPES.contains(w.type)) continue;
			if (!includeRoads && w.catId == OSMDatabaseHelper.CATEGORY_WAY) continue;
			int n = w.lats.length;
			for (int j = 0; j < n - 1; j++) {
				double x1 = metersEast(w.lons[j] - userLon, cosLat);
				double y1 = metersNorth(w.lats[j] - userLat);
				double x2 = metersEast(w.lons[j + 1] - userLon, cosLat);
				double y2 = metersNorth(w.lats[j + 1] - userLat);
				Point intersection = new Point();
				double distance = Utils.getIntersectionDistance(0, 0, bearing, (int) x1, (int) y1, (int) x2, (int) y2, intersection);
				if (distance >= 0 && distance < minDistance) {
					minDistance = distance;
					minWeighted = distance;
					bestWay = w;
					bestNode = null;
					bestLat = latFromMeters(intersection.y, userLat);
					bestLon = lonFromMeters(intersection.x, userLon, cosLat);
				}
			}
		}

		if (bestNode != null || bestWay != null) {
			long id = bestNode != null ? bestNode.id : bestWay.id;
			int catId = bestNode != null ? bestNode.catId : bestWay.catId;
			String type = bestNode != null ? bestNode.type : bestWay.type;
			String nm = bestNode != null ? bestNode.name : bestWay.name;
			byte[] tags = bestNode != null ? bestNode.tags : bestWay.tags;
			boolean fav = bestNode != null && bestNode.favorite;
			MapElement el = new MapElement(fav, id, bestLat, bestLon, catId, type, nm, tags);
			double pointBearing = (userLocation.bearingTo(el.getLocation()) - Math.toDegrees(bearingRad) + 360) % 360;
			if (pointBearing > 180) pointBearing -= 360;
			return new NearestElement(minDistance, minWeighted, pointBearing, speed, el);
		}

		return new NearestElement(Double.MAX_VALUE, null);
	}

	/**
	 * True if this location falls inside a server "box" (see {@code BoxManifest}/{@code BoxImporter})
	 * that has already been downloaded. Boxes are plain lat/lon rectangles — a bbox test is exact, no
	 * polygon geometry needed (unlike the old Geofabrik-admin-boundary check this replaced, back when
	 * imports were per-country rather than per-box; see {@code OSMDatabaseHelper.markBoxImported}).
	 */
	public boolean isTileImported(Location location) {
		double lat = location.getLatitude();
		double lon = location.getLongitude();
		SQLiteDatabase db = OSMDatabaseHelper.getInstance(context).getReadableDatabase();
		try (Cursor c = db.query("imported_boxes", new String[]{"box_index"},
				"min_lat<=? AND max_lat>=? AND min_lon<=? AND max_lon>=?",
				new String[]{String.valueOf(lat), String.valueOf(lat), String.valueOf(lon), String.valueOf(lon)},
				null, null, null, "1")) {
			return c.moveToFirst();
		}
	}

	public List<NearestElement> getNearestElements(Location location, int n) {
		List<NearestElement> nearestElements = new ArrayList<>();
		// Background announcement (BackgroundService) — always the GPS-driven announce cache, never
		// whatever the map view happens to be panned to.
		List<NodeRecord> nodes = candidatesNear(location.getLatitude(), location.getLongitude(), announceCache).nodes;

		double touchLat = location.getLatitude();
		double touchLon = location.getLongitude();
		double cosLat = Math.cos(Math.toRadians(touchLat));

		for (NodeRecord nd : nodes) {
			double dx = metersEast(nd.lon - touchLon, cosLat);
			double dy = metersNorth(nd.lat - touchLat);
			double distance = Math.sqrt(dx * dx + dy * dy);
			double weight = (nd.catId == OSMDatabaseHelper.CATEGORY_MANUAL
					|| nd.catId == OSMDatabaseHelper.CATEGORY_INTERSECTION) ? 0.7 : 1;
			MapElement el = new MapElement(nd.favorite, nd.id, nd.lat, nd.lon, nd.catId, nd.type, nd.name, nd.tags);
			nearestElements.add(new NearestElement(distance, distance * weight, 0, 0, el));
		}

		nearestElements.sort((o1, o2) -> Double.compare(o1.weightedDistance, o2.weightedDistance));
		if (nearestElements.size() > n) {
			return nearestElements.subList(0, n);
		}
		return nearestElements;
	}

	// ------------------------------------------------------------------ render + list accessors

	/** Snapshot of the currently-loaded point features (for rendering) — the map-viewport cache. */
	public List<NodeRecord> getLoadedNodes() {
		return exploreCache.nodes;
	}

	/** Snapshot of the currently-loaded line/area features (for rendering) — the map-viewport cache. */
	public List<WayRecord> getLoadedWays() {
		return exploreCache.ways;
	}

	/** The center of the last requested viewport (see {@link #requestLoadingMap}), or null if none has
	 * been requested yet this session. Used by the "nearby nodes" list, which centers its own db query
	 * on wherever the map is currently showing rather than the device's live GPS position (the user may
	 * be panned away to browse a different place — same reasoning as {@code DownloadMapActivity}'s
	 * "map center, not GPS position" default). */
	public Location getLastCenter() {
		return lastChangedMapViewCenter;
	}

	/** Asks MapActivity to recenter the map on {@code location} the next time it's shown — see
	 * {@link #pendingZoomTarget}'s doc. */
	public void requestZoomTo(Location location) {
		pendingZoomTarget = location;
	}

	/** Returns and clears the pending zoom target, or null if none is set. Consume-once so the same
	 * request doesn't keep re-centering the map on every later {@code onStart()}. */
	public Location consumePendingZoomTarget() {
		Location target = pendingZoomTarget;
		pendingZoomTarget = null;
		return target;
	}

	/** Asks MapActivity to show its aim-cancel action button for {@code element} the next time it's
	 * shown — see {@link #pendingAimUiRefresh}'s doc. Does not itself start aiming; call
	 * {@link #setAimedElement} for that (typically together, from the same caller). */
	public void requestAimUiRefresh(MapElement element) {
		pendingAimUiRefresh = element;
	}

	/** Returns and clears the pending aim-UI-refresh target, or null if none is set. */
	public MapElement consumePendingAimUiRefresh() {
		MapElement element = pendingAimUiRefresh;
		pendingAimUiRefresh = null;
		return element;
	}

	/**
	 * Nodes only, in a plain lat/lon bounding box around {@code center} sized to approximate
	 * {@code radiusMeters} on every side — queried directly against the db (not the loaded-viewport
	 * cache), since the "nearby nodes" list is meant to cover more area than whatever the map happens
	 * to currently have loaded. Deliberately not a true geodesic/Haversine distance filter: converting
	 * the radius to a lat/lon delta once (see {@link #bboxAround}) and letting the box itself do the
	 * filtering is a plain indexed range query, whereas computing the real distance to every candidate
	 * row would be per-row work over what can be a large result set. Results are ordered by that same
	 * cheap metric — {@code ABS(lat - centerLat) + ABS(lon - centerLon)}, ascending — and capped at
	 * {@link #NEARBY_NODES_MAX}, both done in the query itself (ORDER BY + LIMIT) so a very dense area
	 * never pulls more than {@code NEARBY_NODES_MAX} rows out of the db in the first place; the caller
	 * (NodeListActivity) re-sorts by real distance to the user afterward for display, this ordering
	 * only decides which rows survive the cap. Blocking — call off the main thread.
	 */
	public List<MapElement> queryNearbyNodes(Location center, double radiusMeters) {
		return queryNearbyNodes(center, radiusMeters, null);
	}

	/** Same as {@link #queryNearbyNodes(Location, double)}, plus an optional keyword filter — the
	 * "nearby nodes" list's "Show as list" search result mode, matching against type/name the same
	 * way {@link #matchesKeyword} does for the map-touch-exploration "Show on map" mode, just as a SQL
	 * {@code LIKE} instead of an in-memory check since this queries the db directly rather than an
	 * already-loaded cache. {@code keyword} null or blank means no filter (plain nearby list). */
	public List<MapElement> queryNearbyNodes(Location center, double radiusMeters, String keyword) {
		double[] box = bboxAround(center, radiusMeters);
		List<NodeRecord> nodes = queryNodesNearest(box, center.getLatitude(), center.getLongitude(), NEARBY_NODES_MAX, keyword, false, null);
		List<MapElement> out = new ArrayList<>(nodes.size());
		for (NodeRecord nd : nodes) {
			out.add(new MapElement(nd.favorite, nd.id, nd.lat, nd.lon, nd.catId, nd.type, nd.name, nd.tags));
		}
		return out;
	}

	/** Same row shape/filtering as {@link #queryNodes}, plus an optional keyword filter and an
	 * ORDER BY + LIMIT — kept as a separate method rather than added as optional params there, since
	 * every other caller of queryNodes wants every matching row (rendering, nearest-element search)
	 * and none of them want this ordering/keyword filter. */
	/**
	 * Every non-address, non-intersection node within roughly {@code radiusMeters} (bbox, same cheap
	 * metric as {@link #queryNearbyNodes}), nearest first, capped at {@code limit}. For the soundscape
	 * simulation (see soundscape.SceneBuilder): it needs trees, shops, rail nodes etc., and in a dense
	 * city the address points alone (most of all nodes) would otherwise fill the whole cap. Reads the
	 * db directly, so it does not depend on what the map viewport has loaded. Blocking — call off the
	 * main thread.
	 */
	public List<MapElement> queryFeatureNodesAround(Location center, double radiusMeters, int limit) {
		double[] box = bboxAround(center, radiusMeters);
		return toElements(queryNodesNearest(box, center.getLatitude(), center.getLongitude(), limit, null, true, null));
	}

	/** Like {@link #queryFeatureNodesAround}, restricted to nodes whose {@code type} is one of
	 * {@code types} — for the soundscape's far-carrying features (waterfalls, beaches, ...), which a
	 * plain nearest-first query over a wide radius would lose to the city's trees and shops. */
	public List<MapElement> queryNodesOfTypesAround(Location center, double radiusMeters, java.util.Collection<String> types, int limit) {
		double[] box = bboxAround(center, radiusMeters);
		return toElements(queryNodesNearest(box, center.getLatitude(), center.getLongitude(), limit, null, true, types));
	}

	private static List<MapElement> toElements(List<NodeRecord> nodes) {
		List<MapElement> out = new ArrayList<>(nodes.size());
		for (NodeRecord nd : nodes) {
			out.add(new MapElement(nd.favorite, nd.id, nd.lat, nd.lon, nd.catId, nd.type, nd.name, nd.tags));
		}
		return out;
	}

	/** Every way whose bounding box overlaps a box of roughly {@code radiusMeters} around
	 * {@code center}, straight from the db (see {@link #queryFeatureNodesAround}). Blocking. */
	public List<WayRecord> queryWaysAround(Location center, double radiusMeters) {
		return queryWays(bboxAround(center, radiusMeters));
	}

	private List<NodeRecord> queryNodesNearest(double[] box, double centerLat, double centerLon, int limit, String keyword,
											   boolean featuresOnly, java.util.Collection<String> types) {
		List<NodeRecord> out = new ArrayList<>();
		List<int[]> cells = Utils.cellsForBbox(box[0], box[1], box[2], box[3]);
		if (cells.isEmpty()) return out;
		StringBuilder sql = new StringBuilder(
				"SELECT id, lat, lon, cat_id, favorite, type, name, tags FROM nodes WHERE (");
		List<String> args = new ArrayList<>();
		for (int i = 0; i < cells.size(); i++) {
			if (i > 0) sql.append(" OR ");
			sql.append("(cell_x=? AND cell_y=?)");
			args.add(String.valueOf(cells.get(i)[0]));
			args.add(String.valueOf(cells.get(i)[1]));
		}
		sql.append(") AND lat>=? AND lat<=? AND lon>=? AND lon<=?");
		args.add(String.valueOf(box[0]));
		args.add(String.valueOf(box[1]));
		args.add(String.valueOf(box[2]));
		args.add(String.valueOf(box[3]));
		if (featuresOnly) {
			sql.append(" AND cat_id NOT IN (" + OSMDatabaseHelper.CATEGORY_ADDRESS + "," + OSMDatabaseHelper.CATEGORY_INTERSECTION + ")");
		}
		if (types != null && !types.isEmpty()) {
			sql.append(" AND type IN (");
			int i = 0;
			for (String t : types) {
				sql.append(i++ == 0 ? "?" : ",?");
				args.add(t);
			}
			sql.append(")");
		}
		if (keyword != null && !keyword.isEmpty()) {
			sql.append(" AND (type LIKE ? OR name LIKE ?)");
			String pattern = "%" + keyword + "%";
			args.add(pattern);
			args.add(pattern);
		}
		sql.append(" ORDER BY ABS(lat - ?) + ABS(lon - ?) LIMIT ?");
		args.add(String.valueOf(centerLat));
		args.add(String.valueOf(centerLon));
		args.add(String.valueOf(limit));

		SQLiteDatabase db = OSMDatabaseHelper.getInstance(context).getReadableDatabase();
		try (Cursor c = db.rawQuery(sql.toString(), args.toArray(new String[0]))) {
			while (c.moveToNext()) {
				out.add(new NodeRecord(
						c.getLong(0), c.getDouble(1), c.getDouble(2), c.getInt(3),
						c.getString(5), c.getString(6), c.getBlob(7), c.getInt(4) > 0));
			}
		}
		return out;
	}

	// ------------------------------------------------------------------ misc accessors

	public MapElement getAimedElement() {
		return aimedElement;
	}

	public void setAimedElement(MapElement aimedElement) {
		this.aimedElement = aimedElement;
	}

	public Set<Integer> getCatIdFilters() {
		return this.catIdFilters;
	}

	public void setFavoriteFilter(boolean favoriteFilter) {
		this.favoriteFilter = favoriteFilter;
		touchFilterVersion++;
	}

	public void setCatIdFilters(Set<Integer> selectedCategories) {
		this.catIdFilters = selectedCategories;
		touchFilterVersion++;
	}

	public boolean getFavoriteFilter() {
		return favoriteFilter;
	}

	public void setKeywordSearch(String keyword) {
		this.keyword = keyword;
		touchFilterVersion++;
	}

	/** Bumped whenever a filter that decides what touch exploration can find changes. */
	public int getTouchFilterVersion() {
		return touchFilterVersion;
	}

	/** Whether touch exploration at this zoom can find an element — the same rule getNearestElement
	 * applies (detail level for the zoom, category/favorite filters, keyword search). The drawn map
	 * only shows such elements, so everything you can see can also be heard by touching it. */
	public boolean isTouchable(int catId, String type, boolean favorite, float zoomLevel, String name) {
		if (OSMDatabaseHelper.isWorldPlaceCategory(catId)) return true;
		return passesFilter(catId, type, favorite, zoomLevel, true) && matchesKeyword(type, name);
	}

	public String getKeyword() {
		return keyword;
	}

	public Set<String> getDisplayedWayTypes() {
		return displayedWayTypes;
	}
}
