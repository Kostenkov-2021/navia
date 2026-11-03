package com.wikiponder.touchmap.classes.ui;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.RectF;
import android.location.Location;
import android.util.AttributeSet;
import android.util.Log;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.wikiponder.touchmap.MyApplication;
import com.wikiponder.touchmap.R;
import com.wikiponder.touchmap.classes.Contract;
import com.wikiponder.touchmap.classes.MapTileManager;
import com.wikiponder.touchmap.classes.TimerLogger;
import com.wikiponder.touchmap.classes.TouchHints;
import com.wikiponder.touchmap.classes.Utils;
import com.wikiponder.touchmap.classes.db.OSMDatabaseHelper;
import com.wikiponder.touchmap.classes.models.MapElement;
import com.wikiponder.touchmap.classes.models.MyPoint;
import com.wikiponder.touchmap.classes.models.NearestElement;
import com.wikiponder.touchmap.classes.models.NodeRecord;
import com.wikiponder.touchmap.classes.models.WayRecord;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MapView extends View {

	private static final String TAG = "MapView";
	private static final float INITIAL_MAP_WIDTH_METERS = 200f;
	private static final float MAX_ZOOM = 4.0f;
	private static final float MIN_ZOOM = 1 / 8f;
	// Labels (street names, POI names) only draw once zoomed in this far — below it the map is too
	// zoomed out for text to be legible, and would just be visual noise over the lines/dots.
	private static final float LABEL_MIN_ZOOM = 1.0f;
	// At or below this zoom the map is a regional overview: houses, footpaths, service roads, fences
	// and address dots are sub-pixel noise there, so they are not even built (level of detail).
	private static final float DETAIL_MIN_ZOOM = 0.25f;
	private static final int ZOOM_LEVELS = 6; // MIN_ZOOM..MAX_ZOOM in powers of two: 1/8,1/4,1/2,1,2,4
	private static final int ZOOM_LEVEL_AT_ONE = 3;
	private static final int MAX_BITMAP_SIDE = 4096; // larger would exceed common GPU texture limits
	private static final int MAX_ROAD_LABELS = 80;
	private static final int MAX_POI_LABELS = 40;
	private static final int MAX_LABELS_PER_NAME = 2; // OSM splits one street into many ways

	// Way "type" strings (see WayRecord.type / tools/build_global_cluster_dbs.py's get_way_type,
	// which this grouping must stay in sync with) grouped into the road classes styled distinctly.
	private static final Set<String> MAJOR_HIGHWAY_TYPES = new HashSet<>(Arrays.asList(
			"motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link"));
	private static final Set<String> MID_HIGHWAY_TYPES = new HashSet<>(Arrays.asList(
			"secondary", "secondary_link", "tertiary", "tertiary_link", "unclassified",
			"residential", "living_street", "road"));
	private static final Set<String> PEDESTRIAN_WAY_TYPES = new HashSet<>(Arrays.asList(
			"pedestrian", "footway", "path", "cycleway", "steps", "corridor", "via_ferrata", "busway"));

	// Draw order, bottom to top: a motorway always paints over a footpath, a building under everything.
	private static final int LAYER_BUILDING = 0;
	private static final int LAYER_OTHER = 1; // fences/walls, platforms, unrecognized types
	private static final int LAYER_PATH = 2;
	private static final int LAYER_SERVICE = 3;
	private static final int LAYER_MID = 4;
	private static final int LAYER_MAJOR = 5;
	private static final int LAYER_COUNT = 6;

	private final Context context;
	private final float density;
	private final boolean dark; // night mode: black map with brighter colours

	// --- paints for showing map (all sizes are dp/sp so the map looks the same on every screen and
	// text follows the user's font scale — this feature exists for low-vision users) ---
	private final Paint wayLabelFillPaint;
	private final Paint wayLabelStrokePaint; // halo so text stays legible over any colour/background
	private final Paint nodeLabelFillPaint;
	private final Paint nodeLabelStrokePaint;
	private final float labelTextHeight;
	private final Paint nodeFillPaint; // fallback node colour (also CATEGORY_NONE)
	private final Paint nodeBorderPaint;
	private final Paint favoriteBorderPaint;
	private final SparseArray<Paint> nodeCategoryFillPaints;
	private final Paint userFillPaint;
	private final LineStyle fallbackStyle;
	private final LineStyle majorStyle;
	private final LineStyle midStyle;
	private final LineStyle minorStyle;
	private final LineStyle pathStyle;
	private final LineStyle barrierStyle;
	private final LineStyle platformStyle;
	private final Paint buildingFillPaint;
	private final Paint buildingBorderPaint;
	private final Paint backgroundPaint;
	private final Paint worldPlaceFillPaint;
	private final Paint worldPlaceBorderPaint;
	private final Path userMarkerPath = new Path();

	// Point markers differ in shape as well as colour, so categories stay tellable apart without
	// relying on colour alone (red/green/orange blur together for many colour-blind people).
	private static final int SHAPE_CIRCLE = 0;
	private static final int SHAPE_SQUARE = 1;
	private static final int SHAPE_DIAMOND = 2;
	private static final int SHAPE_TRIANGLE_UP = 3;
	private static final int SHAPE_TRIANGLE_DOWN = 4;
	private static final int SHAPE_PLUS = 5;
	private static final int SHAPE_CROSS = 6;
	private static final int SHAPE_HEXAGON = 7;
	private static final int SHAPE_STAR = 8;
	private static final int SHAPE_COUNT = 9;
	private final Path[] markerShapes = new Path[SHAPE_COUNT]; // index SHAPE_CIRCLE unused (drawCircle)

	// --- Map State ---
	private Location userLocation = new Location("");
	private Location centerLocation = new Location("");
	private Location centerLocationBuffer = new Location("");

	private float metersPerPixel = 1.0f;
	private float currentZoom = 1.0f;
	private float mapRotation = 0f; // Rotation in degrees
	private float mapRotationBuffer = 0f;

	// --- Touch Handling ---
	private MyPoint lastTouchPoint = new MyPoint();
	private boolean isTouching = false;
	// Last tile for which "no map here" was announced, so dragging within it doesn't repeat.
	private Point lastAnnouncedMissingTile = null;
	// Debounce for isTileImported() (a synchronous db query): handleTab runs on every touch-move/
	// hover-move frame, and without this it re-queries the db every single frame while exploring or
	// dragging across empty space (water, gaps between points) — competing with MapTileManager's own
	// cache-reload queries for the small SQLite connection pool and, since this runs on the main
	// thread, delaying whatever else is queued on it (including BackgroundService's GPS/compass
	// callbacks, which is why that could show up as announcements going briefly quiet right after
	// scrolling the map). Only re-queried when the touched tile actually changes.
	private Point lastCheckedImportedTile = null;
	private boolean lastCheckedImportedResult = false;

	private UpdateListener updateListener;
	private boolean isCenteredOnUser = true;
	private boolean compassLock = true;
	private float compassRotation = 0f;
	private float tilt = 0f;

	/** Stroke style of one way class: the fill line plus an optional wider light "casing" drawn
	 * underneath it, which separates roads from buildings and from each other at junctions. One Paint
	 * per zoom level (line widths grow a little when zoomed in), all created up front and never
	 * mutated, so the background thread that renders the bitmap can share them with the UI thread. */
	private static final class LineStyle {
		final Paint[] fills = new Paint[ZOOM_LEVELS];
		final Paint[] casings = new Paint[ZOOM_LEVELS]; // entries null = no casing
	}

	/** A projected way, cached at cache-build time in the map-space pixel frame (see ensureCache). */
	private static final class WayShape {
		final Path path;
		final LineStyle style; // null for buildings
		WayShape(Path path, LineStyle style) {
			this.path = path;
			this.style = style;
		}
	}

	/** A projected point feature (real POI, manually-added point, or world-place gazetteer
	 * stand-in), cached in the same map-space pixel frame. */
	private static final class NodePoint {
		final float x, y;
		final int catId;
		final boolean favorite;
		final String name;
		NodePoint(float x, float y, int catId, boolean favorite, String name) {
			this.x = x;
			this.y = y;
			this.catId = catId;
			this.favorite = favorite;
			this.name = name;
		}
	}

	/** A street name anchored on a roughly straight stretch of a way. The position and direction are
	 * in the map-space frame; the text itself is drawn in screen space so it is always upright. */
	private static final class RoadLabel implements Comparable<RoadLabel> {
		final String text;
		final float x, y, angleDeg, textWidth, segmentLength;
		final int layer;
		RoadLabel(String text, float x, float y, float angleDeg, float textWidth, float segmentLength, int layer) {
			this.text = text;
			this.x = x;
			this.y = y;
			this.angleDeg = angleDeg;
			this.textWidth = textWidth;
			this.segmentLength = segmentLength;
			this.layer = layer;
		}
		/** Most important first: bigger road class, then longer stretch. */
		@Override
		public int compareTo(RoadLabel o) {
			if (layer != o.layer) return Integer.compare(o.layer, layer);
			return Float.compare(o.segmentLength, segmentLength);
		}
	}

	// --- Drawing cache ---
	// Geometry is projected once into a pixel frame anchored at (cacheOriginLat, cacheOriginLon) with
	// the current scale, NOT rotated and NOT centred on the screen; the lists below are replaced (never
	// mutated) on each rebuild, so a background thread can safely read the ones it was handed. They are
	// rebuilt only for new data, a zoom/size change, a labels-setting change, or when the view centre
	// has drifted far enough from the origin to near the edge of what was built.
	private List<WayShape>[] cachedLayers = newLayerLists();
	private List<NodePoint> cachedNodePoints = new ArrayList<>();
	private List<RoadLabel> cachedRoadLabels = new ArrayList<>();
	private boolean cacheValid = false;
	private int cacheSerial = 0; // bumped on every rebuild
	private int cacheFilterVersion = -1;
	private int cacheZoomLevel = ZOOM_LEVEL_AT_ONE;
	private List<WayRecord> cacheWays = null;
	private List<NodeRecord> cacheNodes = null;
	private double cacheOriginLat, cacheOriginLon, cacheCosLat;
	private float cacheMetersPerPixel;
	private double cacheHalfDiagonalM; // half of the screen diagonal in metres at build time
	private float[] tmpX = new float[64];
	private float[] tmpY = new float[64];

	/** The static geometry (ways + dots, no names) pre-drawn into one bitmap, together with the
	 * frame it was drawn in and the names that go with it. Rotating or panning the map is then a
	 * single textured quad per frame instead of re-rasterizing thousands of strokes: with the live
	 * Path set that cost ~40 ms a frame on a Galaxy S21 as soon as the compass turned the map,
	 * because the GPU path cache is keyed on the transform. Built off the UI thread. */
	private static final class RenderedLayer {
		final Bitmap bitmap;
		final float centerX, centerY; // where the bitmap's centre sits in the map-space frame
		final int serial;
		final double originLat, originLon, cosLat;
		final float metersPerPixel;
		final List<RoadLabel> roadLabels;
		final List<NodePoint> nodePoints;
		RenderedLayer(Bitmap bitmap, float centerX, float centerY, int serial, double originLat,
					  double originLon, double cosLat, float metersPerPixel,
					  List<RoadLabel> roadLabels, List<NodePoint> nodePoints) {
			this.bitmap = bitmap;
			this.centerX = centerX;
			this.centerY = centerY;
			this.serial = serial;
			this.originLat = originLat;
			this.originLon = originLon;
			this.cosLat = cosLat;
			this.metersPerPixel = metersPerPixel;
			this.roadLabels = roadLabels;
			this.nodePoints = nodePoints;
		}
	}
	private volatile RenderedLayer renderedLayer = null;
	private volatile boolean renderPending = false;
	private boolean bitmapRenderingFailed = false; // e.g. out of memory: stay on live Paths
	private final ExecutorService renderExecutor = Executors.newSingleThreadExecutor();
	private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

	// --- per-frame scratch (no allocation inside onDraw) ---
	private final Matrix drawMatrix = new Matrix();
	private final float[] tmpPoint = new float[2];
	private final RectF[] placedRects = new RectF[MAX_ROAD_LABELS + MAX_POI_LABELS];
	private int placedCount;

	// --- Optional graphical rendering (off by default; for low-vision users) ---
	private boolean renderEnabled = false;
	private boolean labelsEnabled = true;


	public interface UpdateListener {
		void onElementSelected(MapElement element, Location userLocation, double mapRotation, double distance);
		void onMapMessage(String message);
		void onTouchStart(float meterPerPixel);
		void onTouchStop();
		/** The touched location's tile has no imported map data (and no world-place was near enough). */
		void onUndownloadedAreaTouched(Location location);
	}

	public MapView(Context context, @Nullable AttributeSet attrs) {
		super(context, attrs);
		this.context = context;
		this.density = context.getResources().getDisplayMetrics().density;
		this.dark = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
				== Configuration.UI_MODE_NIGHT_YES;
		setClickable(true);
		refreshExploreDescription();
		for (int i = 0; i < placedRects.length; i++) placedRects[i] = new RectF();

		// set paints
		this.wayLabelFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		this.wayLabelFillPaint.setStyle(Paint.Style.FILL);
		this.wayLabelFillPaint.setColor(c("#1A1A1A", "#F2F2F2"));
		this.wayLabelFillPaint.setTextSize(sp(12));
		this.wayLabelFillPaint.setTextAlign(Paint.Align.LEFT);
		Paint.FontMetrics fm = this.wayLabelFillPaint.getFontMetrics();
		this.labelTextHeight = fm.descent - fm.ascent;

		this.wayLabelStrokePaint = new Paint(wayLabelFillPaint);
		this.wayLabelStrokePaint.setStyle(Paint.Style.STROKE);
		this.wayLabelStrokePaint.setStrokeWidth(dp(2.5f));
		this.wayLabelStrokePaint.setColor(c("#E0F4F1EA", "#E6000000")); // AARRGGBB — background colour halo
		this.wayLabelStrokePaint.setStrokeJoin(Paint.Join.ROUND);

		this.nodeLabelFillPaint = new Paint(wayLabelFillPaint);
		this.nodeLabelStrokePaint = new Paint(wayLabelStrokePaint);
		this.nodeLabelStrokePaint.setColor(dark ? Color.BLACK : Color.WHITE);

		this.nodeFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		this.nodeFillPaint.setStyle(Paint.Style.FILL);
		this.nodeFillPaint.setColor(c("#D7263D", "#F87171"));

		this.nodeBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		this.nodeBorderPaint.setStyle(Paint.Style.STROKE);
		this.nodeBorderPaint.setColor(c("#000000", "#F0F0F0"));
		this.nodeBorderPaint.setStrokeWidth(dp(1.5f));

		// Favorited points keep their category colour but get a gold border instead of black, so
		// they stand out from the rest of their own category too, not just from other categories.
		this.favoriteBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		this.favoriteBorderPaint.setStyle(Paint.Style.STROKE);
		this.favoriteBorderPaint.setColor(Color.parseColor("#D69E2E"));
		this.favoriteBorderPaint.setStrokeWidth(dp(2f));

		this.nodeCategoryFillPaints = buildNodeCategoryPaints();

		buildMarkerShapes(dp(4.4f));

		this.userFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		this.userFillPaint.setStyle(Paint.Style.FILL);
		this.userFillPaint.setColor(c("#0B6E4F", "#34D399"));
		float ur = dp(9f); // user arrow, pointing "up" (the map rotates around the user)
		userMarkerPath.moveTo(0, -ur);
		userMarkerPath.lineTo(-ur * 0.7f, ur * 0.8f);
		userMarkerPath.lineTo(0, ur * 0.4f);
		userMarkerPath.lineTo(ur * 0.7f, ur * 0.8f);
		userMarkerPath.close();

		// High-contrast way rendering for low-vision users: distinct colour/width per
		// road class (see styleForType), roads with a light casing so they stay separable.
		this.fallbackStyle = lineStyle(c("#102A43", "#E2E8F0"), 3f, 0f, false);
		this.majorStyle = lineStyle(c("#C2410C", "#FB923C"), 5f, 1.5f, true); // burnt orange — motorway/trunk/primary
		this.midStyle = lineStyle(c("#334E68", "#CBD5E1"), 3.5f, 1.5f, true); // slate — secondary/tertiary/residential
		this.minorStyle = lineStyle(c("#829AB1", "#64748B"), 2.5f, 1f, true); // light grey — service roads
		this.pathStyle = lineStyle(c("#2F855A", "#4ADE80"), 2f, 0f, false); // green thin — footway/path/cycleway/steps
		this.barrierStyle = lineStyle(c("#9B2C2C", "#F87171"), 1.5f, 0f, false); // dark red thin — fences/walls
		this.platformStyle = lineStyle(c("#00838F", "#22D3EE"), 2.5f, 0f, false); // teal — transit platforms

		this.buildingFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		this.buildingFillPaint.setStyle(Paint.Style.FILL);
		this.buildingFillPaint.setColor(c("#D9CBB8", "#2B2B2B"));

		this.buildingBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		this.buildingBorderPaint.setStyle(Paint.Style.STROKE);
		this.buildingBorderPaint.setColor(c("#8C7B65", "#6B6B6B"));
		this.buildingBorderPaint.setStrokeWidth(dp(1f));

		this.backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		this.backgroundPaint.setStyle(Paint.Style.FILL);
		this.backgroundPaint.setColor(c("#F4F1EA", "#000000")); // soft map background (black in dark mode)

		// World-place dots (gazetteer stand-ins where no map is imported yet): distinct blue,
		// smaller and thinner-bordered than real POI dots so the two are never confused.
		this.worldPlaceFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		this.worldPlaceFillPaint.setStyle(Paint.Style.FILL);
		this.worldPlaceFillPaint.setColor(c("#4A90D9", "#60A5FA"));

		this.worldPlaceBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
		this.worldPlaceBorderPaint.setStyle(Paint.Style.STROKE);
		this.worldPlaceBorderPaint.setColor(c("#1B3A5C", "#BFDBFE"));
		this.worldPlaceBorderPaint.setStrokeWidth(dp(1.2f));
	}

	/** Closed polygons centred on (0,0), each fitting a circle of radius r. */
	private void buildMarkerShapes(float r) {
		markerShapes[SHAPE_SQUARE] = polygon(4, r * 0.88f, 45f);
		markerShapes[SHAPE_DIAMOND] = polygon(4, r * 1.15f, 0f);
		markerShapes[SHAPE_TRIANGLE_UP] = polygon(3, r * 1.2f, -90f);
		markerShapes[SHAPE_TRIANGLE_DOWN] = polygon(3, r * 1.2f, 90f);
		markerShapes[SHAPE_HEXAGON] = polygon(6, r, 0f);
		markerShapes[SHAPE_PLUS] = cross(r * 1.15f, 0f);
		markerShapes[SHAPE_CROSS] = cross(r * 1.15f, 45f);
		Path star = new Path();
		for (int i = 0; i < 10; i++) {
			double a = Math.toRadians(-90 + i * 36);
			float rad = (i % 2 == 0) ? r * 1.3f : r * 0.55f;
			float x = (float) (Math.cos(a) * rad), y = (float) (Math.sin(a) * rad);
			if (i == 0) star.moveTo(x, y);
			else star.lineTo(x, y);
		}
		star.close();
		markerShapes[SHAPE_STAR] = star;
	}

	private static Path polygon(int sides, float r, float startDeg) {
		Path path = new Path();
		for (int i = 0; i < sides; i++) {
			double a = Math.toRadians(startDeg + i * 360.0 / sides);
			float x = (float) (Math.cos(a) * r), y = (float) (Math.sin(a) * r);
			if (i == 0) path.moveTo(x, y);
			else path.lineTo(x, y);
		}
		path.close();
		return path;
	}

	/** A plus-shaped polygon with arm length r and arm width 0.7r, rotated by rotDeg. */
	private static Path cross(float r, float rotDeg) {
		float w = r * 0.35f;
		float[] pts = {-w, -r, w, -r, w, -w, r, -w, r, w, w, w, w, r, -w, r, -w, w, -r, w, -r, -w, -w, -w};
		double a = Math.toRadians(rotDeg);
		float c = (float) Math.cos(a), sn = (float) Math.sin(a);
		Path path = new Path();
		for (int i = 0; i < pts.length; i += 2) {
			float x = pts[i] * c - pts[i + 1] * sn, y = pts[i] * sn + pts[i + 1] * c;
			if (i == 0) path.moveTo(x, y);
			else path.lineTo(x, y);
		}
		path.close();
		return path;
	}

	private static int shapeForCategory(int catId) {
		switch (catId) {
			case OSMDatabaseHelper.CATEGORY_HEALTH: return SHAPE_PLUS;
			case OSMDatabaseHelper.CATEGORY_TRAFFIC: return SHAPE_TRIANGLE_UP;
			case OSMDatabaseHelper.CATEGORY_SHOP: return SHAPE_SQUARE;
			case OSMDatabaseHelper.CATEGORY_AMENITY: return SHAPE_DIAMOND;
			case OSMDatabaseHelper.CATEGORY_LEISURE: return SHAPE_HEXAGON;
			case OSMDatabaseHelper.CATEGORY_TOURISM: return SHAPE_STAR;
			case OSMDatabaseHelper.CATEGORY_NATURAL: return SHAPE_TRIANGLE_DOWN;
			case OSMDatabaseHelper.CATEGORY_BARRIER: return SHAPE_CROSS;
			default: return SHAPE_CIRCLE; // manual points, addresses, intersections, world places, other
		}
	}

	/** Picks the light- or dark-mode colour. */
	private int c(String lightHex, String darkHex) {
		return Color.parseColor(dark ? darkHex : lightHex);
	}

	private float dp(float v) {
		return v * density;
	}

	private float sp(float v) {
		return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics());
	}

	/** Builds a way style: one fill (and optional casing) Paint per zoom level. */
	private LineStyle lineStyle(int color, float widthDp, float casingExtraDp, boolean casing) {
		LineStyle style = new LineStyle();
		for (int z = 0; z < ZOOM_LEVELS; z++) {
			// widths grow a little when zoomed in and shrink when zoomed out, like a real map
			float scale = Math.max(0.6f, Math.min(1.5f, 1f + 0.2f * (z - ZOOM_LEVEL_AT_ONE)));
			Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
			fill.setStyle(Paint.Style.STROKE);
			fill.setColor(color);
			fill.setStrokeJoin(Paint.Join.ROUND);
			fill.setStrokeCap(Paint.Cap.ROUND);
			fill.setStrokeWidth(dp(widthDp) * scale);
			style.fills[z] = fill;
			if (casing) {
				Paint casingPaint = new Paint(fill);
				casingPaint.setColor(c("#F2FFFFFF", "#F2000000")); // light halo on light maps, dark on dark
				casingPaint.setStrokeWidth(dp(widthDp + casingExtraDp * 2) * scale);
				style.casings[z] = casingPaint;
			}
		}
		return style;
	}

	@SuppressWarnings("unchecked")
	private static List<WayShape>[] newLayerLists() {
		List<WayShape>[] lists = new List[LAYER_COUNT];
		for (int i = 0; i < LAYER_COUNT; i++) lists[i] = new ArrayList<>();
		return lists;
	}

	private int zoomLevel() {
		int z = Math.round((float) (Math.log(currentZoom) / Math.log(2))) + ZOOM_LEVEL_AT_ONE;
		return Math.max(0, Math.min(ZOOM_LEVELS - 1, z));
	}

	/** One fill-colour Paint per real-POI node category (OSMDatabaseHelper.CATEGORY_*), so a glance
	 * at the map tells a shop from a restaurant from a park without opening it. Anything not in this
	 * table (CATEGORY_NONE, or a future category) falls back to nodeFillPaint's plain red. */
	private SparseArray<Paint> buildNodeCategoryPaints() {
		SparseArray<Paint> paints = new SparseArray<>();
		paints.put(OSMDatabaseHelper.CATEGORY_MANUAL, categoryPaint("#D69E2E", "#FBBF24")); // gold — always stands out
		paints.put(OSMDatabaseHelper.CATEGORY_HEALTH, categoryPaint("#E53E3E", "#F87171")); // red
		paints.put(OSMDatabaseHelper.CATEGORY_TRAFFIC, categoryPaint("#3182CE", "#60A5FA")); // blue
		paints.put(OSMDatabaseHelper.CATEGORY_BARRIER, categoryPaint("#8B6F47", "#C9A66B")); // brown
		paints.put(OSMDatabaseHelper.CATEGORY_SHOP, categoryPaint("#805AD5", "#C084FC")); // purple
		paints.put(OSMDatabaseHelper.CATEGORY_AMENITY, categoryPaint("#DD6B20", "#FB923C")); // orange
		paints.put(OSMDatabaseHelper.CATEGORY_LEISURE, categoryPaint("#38A169", "#4ADE80")); // green
		paints.put(OSMDatabaseHelper.CATEGORY_TOURISM, categoryPaint("#D53F8C", "#F472B6")); // pink
		paints.put(OSMDatabaseHelper.CATEGORY_NATURAL, categoryPaint("#276749", "#34D399")); // dark green
		paints.put(OSMDatabaseHelper.CATEGORY_INTERSECTION, categoryPaint("#A0AEC0", "#A0AEC0")); // pale grey — synthesized, low visual weight
		// Plain addr:* points (house numbers) are usually the single most numerous node category in
		// any downloaded area — a muted colour (and smaller radius, see onDraw) keeps them from
		// drowning every real POI in a wall of dots.
		paints.put(OSMDatabaseHelper.CATEGORY_ADDRESS, categoryPaint("#B7A99A", "#8A8178"));
		return paints;
	}

	private Paint categoryPaint(String lightHex, String darkHex) {
		Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
		p.setStyle(Paint.Style.FILL);
		p.setColor(c(lightHex, darkHex));
		return p;
	}

	/** Enables or disables graphical rendering of the map (off = touch/sound only). */
	public void setRenderEnabled(boolean enabled) {
		this.renderEnabled = enabled;
		if (!enabled) { // drop the (large) bitmap and caches while the map isn't being drawn
			renderedLayer = null;
			cacheValid = false;
			cachedLayers = newLayerLists();
			cachedNodePoints = new ArrayList<>();
			cachedRoadLabels = new ArrayList<>();
		}
		invalidate();
	}

	/** Shows or hides street and point-of-interest names on the drawn map. */
	public void setLabelsEnabled(boolean enabled) {
		if (this.labelsEnabled == enabled) return;
		this.labelsEnabled = enabled;
		cacheValid = false; // road labels are built into the cache
		invalidate();
	}

	public void setUserLocation(Location location) {
		if (location == null) return;

		this.userLocation = location;

		if (isCenteredOnUser) {
			setCenterLocation(this.userLocation, true);
		}
	}


	public void setCenterLocation(Location centerLocation, boolean isCentered) {
		this.centerLocationBuffer = centerLocation;
		if (!isTouching) {
			copycenterLocationBuffer();
		}
		this.isCenteredOnUser = isCentered;
	}

	private void copycenterLocationBuffer() {
		centerLocation = centerLocationBuffer;
		updateMapView();
		invalidate();
	}

	private void copyMapRotationBuffer() {
		mapRotation = mapRotationBuffer;
	}

	private void updateMapView() {
		double width = metersPerPixel * getWidth();
		double height = metersPerPixel * getHeight();
		MyApplication.getMapTileManager(context).requestLoadingMap(centerLocation, width, height);
	}

	public void setCompassLock(boolean compassLock) {
		this.compassLock = compassLock;
		this.mapRotation = (compassLock) ? compassRotation : 0;
		this.mapRotationBuffer = (compassLock) ? compassRotation : 0;
		invalidate();
	}

	public Location getCenterLocation() {
		return centerLocation;
	}

	public void setOnUpdateListener(UpdateListener listener) {
		this.updateListener = listener;
	}

	public void setCompassRotation(float rotation, float tilt) {
		this.compassRotation = rotation;
		this.tilt = tilt;

		if (compassLock) {
			mapRotationBuffer = rotation;
			if (!isTouching) {
				mapRotation = mapRotationBuffer;
				invalidate();
			}
		}
	}

	public void zoomIn() {
		currentZoom = Math.min(currentZoom * 2, MAX_ZOOM);
		updateScale();
		invalidate();
		updateListener.onMapMessage(String.format(
				Locale.US,
				"Zoomed in to %d meters",
				(int) (metersPerPixel * getWidth())));
	}

	public void zoomOut() {
		currentZoom = Math.max(currentZoom / 2, MIN_ZOOM);
		updateScale();
		updateMapView();
		invalidate();
		updateListener.onMapMessage(String.format(
				Locale.US,
				"Zoomed out to %d meters",
				(int) (metersPerPixel * getWidth())));
	}

	public void recenter() {
		setCenterLocation(userLocation, true);
	}

	@Override
	protected void onSizeChanged(int w, int h, int oldW, int oldH) {
		super.onSizeChanged(w, h, oldW, oldH);
		updateScale();
		cacheValid = false;
		renderedLayer = null; // sized for the old view
	}

	private void updateScale() {
		if (getWidth() == 0)
			return;
		metersPerPixel = (INITIAL_MAP_WIDTH_METERS / currentZoom) / getWidth();
	}

	@Override
	protected void onDraw(@NonNull Canvas canvas) {
		super.onDraw(canvas);
		// When rendering is disabled the map is explored by touch and sound only.
		if (!renderEnabled || getWidth() == 0)
			return;

		ensureCache();
		maybeStartBitmapRender();

		canvas.drawColor(backgroundPaint.getColor());

		// Prefer the pre-drawn bitmap (possibly from a slightly older frame/scale while a fresh one is
		// being rendered); fall back to live Paths until the first bitmap is ready.
		RenderedLayer layer = renderedLayer;
		double originLat = layer != null ? layer.originLat : cacheOriginLat;
		double originLon = layer != null ? layer.originLon : cacheOriginLon;
		double cosLat = layer != null ? layer.cosLat : cacheCosLat;
		float mpp = layer != null ? layer.metersPerPixel : cacheMetersPerPixel;

		// Map-space frame -> screen: shift so the current centre is at the origin, scale (only non-1
		// while an out-of-date bitmap awaits its replacement after a zoom), rotate, then move the
		// centre to the middle of the view.
		float dx = (float) (Math.toRadians(centerLocation.getLongitude() - originLon) * cosLat
				* Contract.EARTH_RADIUS / mpp);
		float dy = (float) (-Math.toRadians(centerLocation.getLatitude() - originLat)
				* Contract.EARTH_RADIUS / mpp);
		float ratio = mpp / metersPerPixel;
		drawMatrix.setTranslate(-dx, -dy);
		drawMatrix.postScale(ratio, ratio);
		// mapRotation is the heading (clockwise from north): turn the map the opposite way so the
		// direction the user faces points up. handleTab() applies the inverse of exactly this.
		drawMatrix.postRotate(-mapRotation);
		drawMatrix.postTranslate(getWidth() / 2f, getHeight() / 2f);

		int saved = canvas.save();
		canvas.concat(drawMatrix);
		if (layer != null) {
			canvas.drawBitmap(layer.bitmap, layer.centerX - layer.bitmap.getWidth() / 2f,
					layer.centerY - layer.bitmap.getHeight() / 2f, bitmapPaint);
		} else {
			drawGeometry(canvas, cachedLayers, cachedNodePoints, cacheZoomLevel);
		}
		canvas.restoreToCount(saved);

		// Names are drawn in screen space (upright, collision-checked) over the finished geometry.
		placedCount = 0;
		if (labelsEnabled && currentZoom >= LABEL_MIN_ZOOM) {
			drawRoadLabels(canvas, layer != null ? layer.roadLabels : cachedRoadLabels);
			drawNodeLabels(canvas, layer != null ? layer.nodePoints : cachedNodePoints);
		}

		// User marker at the screen centre, pointing "up" (the map rotates around the user)
		saved = canvas.save();
		canvas.translate(getWidth() / 2f, getHeight() / 2f);
		canvas.drawPath(userMarkerPath, userFillPaint);
		canvas.drawPath(userMarkerPath, nodeBorderPaint);
		canvas.restoreToCount(saved);
	}

	/** (Re)renders the geometry bitmap when there is none for the current cache, or the view centre
	 * has moved far enough from the bitmap's centre to approach its edge. Runs on a worker thread;
	 * the old bitmap keeps being drawn until the new one is swapped in. */
	private void maybeStartBitmapRender() {
		if (bitmapRenderingFailed || renderPending || !cacheValid) return;
		float diagPx = (float) Math.hypot(getWidth(), getHeight());
		// the visible area is always inside a circle of radius diagPx/2, whatever the rotation
		final int side = (int) Math.ceil(diagPx * 1.15f);
		if (side > MAX_BITMAP_SIDE) {
			bitmapRenderingFailed = true;
			return;
		}
		final float cx = (float) (Math.toRadians(centerLocation.getLongitude() - cacheOriginLon) * cacheCosLat
				* Contract.EARTH_RADIUS / cacheMetersPerPixel);
		final float cy = (float) (-Math.toRadians(centerLocation.getLatitude() - cacheOriginLat)
				* Contract.EARTH_RADIUS / cacheMetersPerPixel);
		RenderedLayer current = renderedLayer;
		if (current != null && current.serial == cacheSerial
				&& Math.hypot(cx - current.centerX, cy - current.centerY) < diagPx * 0.06f) {
			return;
		}
		renderPending = true;
		final List<WayShape>[] layers = cachedLayers;
		final List<NodePoint> nodes = cachedNodePoints;
		final List<RoadLabel> labels = cachedRoadLabels;
		final int serial = cacheSerial, zoomLevel = cacheZoomLevel;
		final double originLat = cacheOriginLat, originLon = cacheOriginLon, cosLat = cacheCosLat;
		final float mpp = cacheMetersPerPixel;
		final int bg = backgroundPaint.getColor();
		renderExecutor.execute(() -> {
			try {
				Bitmap bmp = Bitmap.createBitmap(side, side, Bitmap.Config.RGB_565);
				Canvas c = new Canvas(bmp);
				c.drawColor(bg);
				c.translate(side / 2f - cx, side / 2f - cy);
				drawGeometry(c, layers, nodes, zoomLevel);
				renderedLayer = new RenderedLayer(bmp, cx, cy, serial, originLat, originLon, cosLat, mpp,
						labels, nodes);
			} catch (OutOfMemoryError | RuntimeException e) {
				bitmapRenderingFailed = true; // keep drawing live Paths
				Log.w(TAG, "Map bitmap render failed; using live paths", e);
			} finally {
				renderPending = false;
				postInvalidateOnAnimation();
			}
		});
	}

	/** Ways, bottom layer first, then the dots. Within a layer all casings go down before any fill so
	 * same-class roads merge cleanly at junctions while a higher class still cuts across a lower
	 * one. Pure function of its arguments (uses only immutable paints), so it can run on any thread. */
	private void drawGeometry(Canvas canvas, List<WayShape>[] layers, List<NodePoint> nodes, int zoomLevel) {
		for (int layer = 0; layer < LAYER_COUNT; layer++) {
			List<WayShape> shapes = layers[layer];
			if (shapes.isEmpty()) continue;
			if (layer == LAYER_BUILDING) {
				for (WayShape shape : shapes) {
					canvas.drawPath(shape.path, buildingFillPaint);
					canvas.drawPath(shape.path, buildingBorderPaint);
				}
				continue;
			}
			for (WayShape shape : shapes) {
				Paint casing = shape.style.casings[zoomLevel];
				if (casing != null) canvas.drawPath(shape.path, casing);
			}
			for (WayShape shape : shapes) {
				canvas.drawPath(shape.path, shape.style.fills[zoomLevel]);
			}
		}
		// Points of interest as bordered dots, coloured by category. World-place stand-ins, plain
		// addresses and synthesized intersections render smaller than real POIs: the first so they're
		// never mistaken for imported points, the others because they're by far the most numerous.
		// own copies of the marker paths: this can run on the render thread while the UI thread draws
		Path[] shapes = new Path[SHAPE_COUNT];
		for (int i = 0; i < SHAPE_COUNT; i++) shapes[i] = markerShapes[i] == null ? null : new Path(markerShapes[i]);
		for (NodePoint p : nodes) {
			float r = nodeRadius(p);
			boolean isWorldPlace = OSMDatabaseHelper.isWorldPlaceCategory(p.catId);
			Paint fill = isWorldPlace ? worldPlaceFillPaint : nodeCategoryFillPaints.get(p.catId, nodeFillPaint);
			Paint border = isWorldPlace ? worldPlaceBorderPaint : (p.favorite ? favoriteBorderPaint : nodeBorderPaint);
			int shape = isWorldPlace ? SHAPE_CIRCLE : shapeForCategory(p.catId);
			if (shape == SHAPE_CIRCLE) {
				canvas.drawCircle(p.x, p.y, r, fill);
				canvas.drawCircle(p.x, p.y, r, border);
			} else {
				int saved = canvas.save();
				canvas.translate(p.x, p.y);
				canvas.drawPath(shapes[shape], fill);
				canvas.drawPath(shapes[shape], border);
				canvas.restoreToCount(saved);
			}
		}
	}

	private float nodeRadius(NodePoint p) {
		if (OSMDatabaseHelper.isWorldPlaceCategory(p.catId)) return dp(2.5f);
		if (p.catId == OSMDatabaseHelper.CATEGORY_ADDRESS || p.catId == OSMDatabaseHelper.CATEGORY_INTERSECTION)
			return dp(2f);
		return dp(3.5f);
	}

	private void drawRoadLabels(Canvas canvas, List<RoadLabel> labels) {
		int drawn = 0;
		float margin = dp(40f);
		for (RoadLabel l : labels) {
			if (drawn >= MAX_ROAD_LABELS) break;
			tmpPoint[0] = l.x;
			tmpPoint[1] = l.y;
			drawMatrix.mapPoints(tmpPoint);
			float sx = tmpPoint[0], sy = tmpPoint[1];
			if (sx < -margin || sy < -margin || sx > getWidth() + margin || sy > getHeight() + margin) continue;
			// Text runs along the road but is never upside down: flip when it would point left.
			float angle = l.angleDeg - mapRotation;
			angle = ((angle + 180f) % 360f + 360f) % 360f - 180f; // (-180, 180]
			if (angle > 90f) angle -= 180f;
			else if (angle < -90f) angle += 180f;
			double rad = Math.toRadians(angle);
			float c = (float) Math.abs(Math.cos(rad)), s = (float) Math.abs(Math.sin(rad));
			float halfW = (l.textWidth + dp(4f)) / 2f, halfH = (labelTextHeight + dp(2f)) / 2f;
			float hx = c * halfW + s * halfH, hy = s * halfW + c * halfH;
			if (!reserve(sx - hx, sy - hy, sx + hx, sy + hy)) continue;
			int saved = canvas.save();
			canvas.translate(sx, sy);
			canvas.rotate(angle);
			// baseline sits just above the road line; vertically centred text would hide the road
			float baseline = -dp(3f);
			canvas.drawText(l.text, -l.textWidth / 2f, baseline, wayLabelStrokePaint);
			canvas.drawText(l.text, -l.textWidth / 2f, baseline, wayLabelFillPaint);
			canvas.restoreToCount(saved);
			drawn++;
		}
	}

	private void drawNodeLabels(Canvas canvas, List<NodePoint> points) {
		int drawn = 0;
		float margin = dp(40f);
		Paint.FontMetrics fm = nodeLabelFillPaint.getFontMetrics();
		for (NodePoint p : points) {
			if (drawn >= MAX_POI_LABELS) break;
			if (!isLabelableNode(p) || p.name == null || p.name.isEmpty()) continue;
			tmpPoint[0] = p.x;
			tmpPoint[1] = p.y;
			drawMatrix.mapPoints(tmpPoint);
			float sx = tmpPoint[0], sy = tmpPoint[1];
			if (sx < -margin || sy < -margin || sx > getWidth() + margin || sy > getHeight() + margin) continue;
			float x = sx + nodeRadius(p) + dp(2f);
			float w = nodeLabelFillPaint.measureText(p.name);
			float top = sy - labelTextHeight / 2f;
			if (!reserve(x - dp(1f), top, x + w + dp(1f), top + labelTextHeight)) continue;
			float baseline = sy - (fm.ascent + fm.descent) / 2f;
			canvas.drawText(p.name, x, baseline, nodeLabelStrokePaint);
			canvas.drawText(p.name, x, baseline, nodeLabelFillPaint);
			drawn++;
		}
	}

	/** Claims a screen rectangle for a label; false if it overlaps one already placed this frame. */
	private boolean reserve(float l, float t, float r, float b) {
		for (int i = 0; i < placedCount; i++) {
			RectF o = placedRects[i];
			if (l < o.right && r > o.left && t < o.bottom && b > o.top) return false;
		}
		if (placedCount < placedRects.length) placedRects[placedCount++].set(l, t, r, b);
		return true;
	}

	/** Real, named POIs are worth labeling; raw addresses and synthesized intersections are too
	 * dense/uninteresting to label, and world-place stand-ins get their own zoomed-out UI instead. */
	private static boolean isLabelableNode(NodePoint p) {
		return p.catId != OSMDatabaseHelper.CATEGORY_ADDRESS
				&& p.catId != OSMDatabaseHelper.CATEGORY_INTERSECTION
				&& !OSMDatabaseHelper.isWorldPlaceCategory(p.catId);
	}

	/** True for building=yes footprints (WayRecord.type == "address") — filled as polygons rather
	 * than drawn as an outline stroke, since OSM building ways are closed rings. */
	private static boolean isBuildingType(String type) {
		return "address".equals(type);
	}

	/** Draw layer of a way by its road/feature class (see WayRecord.type /
	 * tools/build_global_cluster_dbs.py's get_way_type). */
	private static int layerForType(String type) {
		if (type == null) return LAYER_OTHER;
		if (isBuildingType(type)) return LAYER_BUILDING;
		if (type.startsWith("barrier:") || "platform".equals(type) || "tram_stop".equals(type)) return LAYER_OTHER;
		if (PEDESTRIAN_WAY_TYPES.contains(type)) return LAYER_PATH;
		if ("service".equals(type)) return LAYER_SERVICE;
		if (MID_HIGHWAY_TYPES.contains(type)) return LAYER_MID;
		if (MAJOR_HIGHWAY_TYPES.contains(type)) return LAYER_MAJOR;
		return LAYER_OTHER;
	}

	private LineStyle styleForType(String type) {
		if (type == null) return fallbackStyle;
		if (type.startsWith("barrier:")) return barrierStyle;
		if ("platform".equals(type) || "tram_stop".equals(type)) return platformStyle;
		if (PEDESTRIAN_WAY_TYPES.contains(type)) return pathStyle;
		if (MAJOR_HIGHWAY_TYPES.contains(type)) return majorStyle;
		if (MID_HIGHWAY_TYPES.contains(type)) return midStyle;
		if ("service".equals(type)) return minorStyle;
		return fallbackStyle;
	}

	/** Real roads/paths are worth labeling with their street name; barriers, platforms and
	 * buildings are not. */
	private static boolean isLabelableLayer(int layer) {
		return layer == LAYER_PATH || layer == LAYER_SERVICE || layer == LAYER_MID || layer == LAYER_MAJOR;
	}

	/** Rebuilds the map-space cache if (and only if) something it depends on changed. */
	private void ensureCache() {
		MapTileManager tm = MyApplication.getMapTileManager(context);
		if (tm == null) return;
		List<WayRecord> ways = tm.getLoadedWays();
		List<NodeRecord> nodes = tm.getLoadedNodes();
		double centerLat = centerLocation.getLatitude();
		double centerLon = centerLocation.getLongitude();
		boolean rebuild = !cacheValid || ways != cacheWays || nodes != cacheNodes
				|| metersPerPixel != cacheMetersPerPixel || tm.getTouchFilterVersion() != cacheFilterVersion;
		if (!rebuild) {
			double east = Math.toRadians(centerLon - cacheOriginLon) * cacheCosLat * Contract.EARTH_RADIUS;
			double north = Math.toRadians(centerLat - cacheOriginLat) * Contract.EARTH_RADIUS;
			// the build covers 1.8 half-diagonals around the origin; rebuild well before the view
			// (1 half-diagonal around the centre) can reach the edge of it
			rebuild = Math.hypot(east, north) > cacheHalfDiagonalM * 0.6;
		}
		if (rebuild) buildCache(tm, ways, nodes, centerLat, centerLon);
	}

	private void buildCache(MapTileManager tm, List<WayRecord> ways, List<NodeRecord> nodes, double originLat, double originLon) {
		TimerLogger.startTimer("buildCache");
		// fresh lists each time: the previous ones may still be read by a bitmap render in flight
		List<WayShape>[] layerLists = newLayerLists();
		List<NodePoint> nodePoints = new ArrayList<>();
		List<RoadLabel> roadLabels = new ArrayList<>();

		cacheValid = true;
		cacheSerial++;
		cacheFilterVersion = tm.getTouchFilterVersion();
		cacheZoomLevel = zoomLevel();
		cacheWays = ways;
		cacheNodes = nodes;
		cacheOriginLat = originLat;
		cacheOriginLon = originLon;
		cacheCosLat = Math.cos(Math.toRadians(originLat));
		cacheMetersPerPixel = metersPerPixel;
		cacheHalfDiagonalM = Math.sqrt(getWidth() * (double) getWidth() + getHeight() * (double) getHeight())
				* metersPerPixel / 2.0;
		// Loaded-cache padding (see MapTileManager.LOAD_PADDING_METERS) is deliberately bigger than
		// the viewport, so skip anything clearly off-screen before projecting it. A circle is safe
		// for any map rotation angle.
		double radiusM = cacheHalfDiagonalM * 1.8;
		boolean detail = currentZoom > DETAIL_MIN_ZOOM;
		boolean buildLabels = labelsEnabled && currentZoom >= LABEL_MIN_ZOOM;
		double mx = Math.toRadians(1) * cacheCosLat * Contract.EARTH_RADIUS; // metres per degree lon
		double my = Math.toRadians(1) * Contract.EARTH_RADIUS; // metres per degree lat
		float minStep = dp(1.5f); // vertices closer than this to the previous kept one are dropped
		List<RoadLabel> candidates = new ArrayList<>();

		for (WayRecord w : ways) {
			double minEast = (w.minLon - originLon) * mx, maxEast = (w.maxLon - originLon) * mx;
			double minNorth = (w.minLat - originLat) * my, maxNorth = (w.maxLat - originLat) * my;
			if (maxEast < -radiusM || minEast > radiusM || maxNorth < -radiusM || minNorth > radiusM) continue;
			// only what touch exploration can find at this zoom is drawn (see isTouchable)
			if (!tm.isTouchable(w.catId, w.type, false, currentZoom, w.name)) continue;
			int layer = layerForType(w.type);
			if (!detail && (layer == LAYER_BUILDING || layer == LAYER_OTHER
					|| layer == LAYER_PATH || layer == LAYER_SERVICE)) continue;
			int n = w.lats.length;
			if (n < 2) continue;
			if (tmpX.length < n) {
				tmpX = new float[n];
				tmpY = new float[n];
			}
			int kept = 0;
			float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
			for (int i = 0; i < n; i++) {
				float x = (float) ((w.lons[i] - originLon) * mx / metersPerPixel);
				float y = (float) (-(w.lats[i] - originLat) * my / metersPerPixel);
				if (i > 0 && i < n - 1 && kept > 0) {
					float ddx = x - tmpX[kept - 1], ddy = y - tmpY[kept - 1];
					if (ddx * ddx + ddy * ddy < minStep * minStep) continue;
				}
				tmpX[kept] = x;
				tmpY[kept] = y;
				kept++;
				if (x < minX) minX = x;
				if (x > maxX) maxX = x;
				if (y < minY) minY = y;
				if (y > maxY) maxY = y;
			}
			if (kept < 2) continue;
			if (maxX - minX < dp(1f) && maxY - minY < dp(1f)) continue; // sub-pixel speck
			Path path = new Path();
			LineStyle style = layer == LAYER_BUILDING ? null : styleForType(w.type);
			path.moveTo(tmpX[0], tmpY[0]);
			for (int i = 1; i < kept; i++) path.lineTo(tmpX[i], tmpY[i]);
			if (layer == LAYER_BUILDING) path.close();
			layerLists[layer].add(new WayShape(path, style));
			if (buildLabels && isLabelableLayer(layer) && w.name != null && !w.name.isEmpty()) {
				addRoadLabels(candidates, w.name, tmpX, tmpY, kept, layer);
			}
		}

		// Keep the best few labels per street name (one street is often dozens of OSM ways).
		Collections.sort(candidates);
		Map<String, Integer> perName = new HashMap<>();
		for (RoadLabel l : candidates) {
			Integer used = perName.get(l.text);
			if (used != null && used >= MAX_LABELS_PER_NAME) continue;
			perName.put(l.text, used == null ? 1 : used + 1);
			roadLabels.add(l);
		}

		for (NodeRecord nd : nodes) {
			double east = (nd.lon - originLon) * mx, north = (nd.lat - originLat) * my;
			if (Math.abs(east) > radiusM || Math.abs(north) > radiusM) continue;
			if (!tm.isTouchable(nd.catId, nd.type, nd.favorite, currentZoom, nd.name)) continue;
			if (!detail && (nd.catId == OSMDatabaseHelper.CATEGORY_ADDRESS
					|| nd.catId == OSMDatabaseHelper.CATEGORY_INTERSECTION)) continue;
			nodePoints.add(new NodePoint((float) (east / metersPerPixel), (float) (-north / metersPerPixel),
					nd.catId, nd.favorite, nd.name));
		}
		cachedLayers = layerLists;
		cachedNodePoints = nodePoints;
		cachedRoadLabels = roadLabels;
		TimerLogger.logTime("buildCache", "Finished");
	}

	/** Adds label candidates along a polyline: one in the middle, or two when the way is long. A
	 * spot only qualifies if the text fits along it and that stretch is roughly straight. */
	private void addRoadLabels(List<RoadLabel> out, String name, float[] xs, float[] ys, int n, int layer) {
		float textW = wayLabelFillPaint.measureText(name);
		float total = 0;
		for (int i = 1; i < n; i++) total += (float) Math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]);
		if (total < textW * 1.2f) return;
		float[] fractions = total >= textW * 3.5f ? new float[]{0.25f, 0.75f} : new float[]{0.5f};
		float[] pa = new float[2], pb = new float[2], pc = new float[2];
		for (float f : fractions) {
			float s = f * total;
			float s0 = Math.max(0, s - textW / 2f), s1 = Math.min(total, s + textW / 2f);
			pointAt(xs, ys, n, s0, pa);
			pointAt(xs, ys, n, s1, pb);
			pointAt(xs, ys, n, s, pc);
			float chord = (float) Math.hypot(pb[0] - pa[0], pb[1] - pa[1]);
			if (chord < (s1 - s0) * 0.85f) continue; // too curved for straight text
			float angle = (float) Math.toDegrees(Math.atan2(pb[1] - pa[1], pb[0] - pa[0]));
			out.add(new RoadLabel(name, pc[0], pc[1], angle, textW, total, layer));
		}
	}

	/** The point at arc length s along a polyline. */
	private static void pointAt(float[] xs, float[] ys, int n, float s, float[] out) {
		float acc = 0;
		for (int i = 1; i < n; i++) {
			float seg = (float) Math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]);
			if (acc + seg >= s && seg > 0) {
				float t = (s - acc) / seg;
				out[0] = xs[i - 1] + (xs[i] - xs[i - 1]) * t;
				out[1] = ys[i - 1] + (ys[i] - ys[i - 1]) * t;
				return;
			}
			acc += seg;
		}
		out[0] = xs[n - 1];
		out[1] = ys[n - 1];
	}


	@Override
	public boolean dispatchHoverEvent(MotionEvent event) {
		final int action = event.getAction();
		switch (action) {
			case MotionEvent.ACTION_HOVER_ENTER:
			case MotionEvent.ACTION_HOVER_MOVE:
				isTouching = true;
				if (updateListener != null)
					updateListener.onTouchStart(getWidth() * metersPerPixel);
				handleTab(event.getX(), event.getY());
				break;
			case MotionEvent.ACTION_HOVER_EXIT:
				isTouching = false;
				lastAnnouncedMissingTile = null;
				// Bounded to one touch session: a stale "not imported" result must never survive past
				// it, since a download can finish (see MapTileManager.reloadCurrentArea) while the user
				// isn't actively touching, and the next touch session on that same tile must re-check.
				lastCheckedImportedTile = null;
				copyMapRotationBuffer();
				copycenterLocationBuffer();
				if (updateListener != null)
					updateListener.onTouchStop();
				refreshExploreDescription();
				break;
		}
		return super.dispatchHoverEvent(event);
	}

	/** TalkBack's label for the map: adds how to read it until enough points were read by touch. */
	private void refreshExploreDescription() {
		setContentDescription(context.getString(TouchHints.needsExploreHint(context)
				? R.string.content_description_explore_map_hint : R.string.content_description_explore_map));
	}
	
	@Override
	public boolean performClick() {
		return super.performClick();
	}
	
	@Override
	public boolean onTouchEvent(MotionEvent event) {
		int action = event.getActionMasked();
		int pointerCount = event.getPointerCount();
		switch (action) {
			case MotionEvent.ACTION_DOWN:
			case MotionEvent.ACTION_POINTER_DOWN:
				isTouching = true;
				if (updateListener != null)
					updateListener.onTouchStart(getWidth() * metersPerPixel);
				if (isTalkBackEnabled() && pointerCount == 1) {
					updateListener.onMapMessage("Scrolling");
					lastTouchPoint = new MyPoint(event.getX(), -event.getY());
				}
				break;
			case MotionEvent.ACTION_MOVE:
				if (!isTalkBackEnabled() && pointerCount == 1) {
					handleTab(event.getX(), event.getY());
					return true;
				}
				break;
			case MotionEvent.ACTION_UP:
			case MotionEvent.ACTION_CANCEL:
				isTouching = false;
				lastAnnouncedMissingTile = null;
				lastCheckedImportedTile = null; // see the ACTION_HOVER_EXIT case for why this resets here too
				// Missing here (unlike the ACTION_HOVER_EXIT case below) meant AnnouncementManager.isTouching
				// — set true by onTouchStart on ACTION_DOWN above — never got reset back to false for any
				// gesture that goes through onTouchEvent rather than dispatchHoverEvent (a two-finger pan
				// with TalkBack off, or — the reported case — a one-finger pan gesture with TalkBack on,
				// which Android delivers as raw touch rather than hover). announceNearestPoint/announceAiming
				// both bail out while isTouching is true, so a single pan gesture silently and permanently
				// suppressed announcements until the next hover-based touch (e.g. touching a point) happened
				// to reset it via onTouchStop there. ACTION_CANCEL resets the same way (a gesture the system
				// aborted mid-touch must not leave this stuck either) but must not fall into the pan-commit
				// logic below, which is why that block still checks for ACTION_UP specifically.
				if (updateListener != null)
					updateListener.onTouchStop();
				copyMapRotationBuffer();
				copycenterLocationBuffer();
				if (action == MotionEvent.ACTION_UP
						&& ((isTalkBackEnabled() && pointerCount == 1) || (!isTalkBackEnabled() && pointerCount == 2))) {
					MyPoint currentTouchPoint = new MyPoint(event.getX(), -event.getY());
					MyPoint transferInMeters = lastTouchPoint
							.subtract(currentTouchPoint)
							.rotate(-mapRotation)
							.multiply(metersPerPixel);
					double scaleX = Math.toDegrees(1/ Contract.EARTH_RADIUS/Math.cos(Math.toRadians(centerLocation.getLatitude())));
					double scaleY = Math.toDegrees(1/ Contract.EARTH_RADIUS);
					
					double deltaLat = transferInMeters.y * scaleY;
					double deltaLon = transferInMeters.x * scaleX;
					
					centerLocation.setLatitude(centerLocation.getLatitude() + deltaLat);
					centerLocation.setLongitude(centerLocation.getLongitude() + deltaLon);
					
					int d = (int)Math.sqrt(transferInMeters.x * transferInMeters.x + transferInMeters.y * transferInMeters.y);
					updateListener.onMapMessage("Moved " + d + "meters");
					isCenteredOnUser = false;
					updateMapView();
					invalidate();
					return true;
				}
				break;
		}
		return super.onTouchEvent(event);
	}
	
	/** Debug builds: logs what a touch hit and where the drawing puts that element on screen (same
	 * projection as the map), so touch results can be checked against the picture from adb. */
	private void logHit(float touchX, float touchY, NearestElement ne) {
		double cosLat = Math.cos(Math.toRadians(centerLocation.getLatitude()));
		float east = (float) (Math.toRadians(ne.element.lon - centerLocation.getLongitude()) * cosLat
				* Contract.EARTH_RADIUS / metersPerPixel);
		float north = (float) (Math.toRadians(ne.element.lat - centerLocation.getLatitude())
				* Contract.EARTH_RADIUS / metersPerPixel);
		MyPoint sp = new MyPoint(east, -north).rotate(-mapRotation).add(new MyPoint(getWidth() / 2f, getHeight() / 2f));
		Log.i("TouchMapHit", String.format(Locale.US,
				"touch=(%.0f,%.0f) rot=%.1f zoom=%.2f -> '%s' [%s cat=%d] drawnAt=(%.0f,%.0f) touchDist=%.1fm",
				touchX, touchY, mapRotation, currentZoom, ne.element.name, ne.element.type, ne.element.catId,
				sp.x, sp.y, ne.distance));
	}

	private void handleTab(float touchX, float touchY) {
		MyPoint deltaInMeters = new MyPoint(touchX, -touchY)
				.add(new MyPoint(-getWidth()/2, getHeight()/2))
				.rotate(-mapRotation)
				.multiply(metersPerPixel);
		double scaleX = Math.toDegrees(1/ Contract.EARTH_RADIUS/Math.cos(Math.toRadians(centerLocation.getLatitude())));
		double scaleY = Math.toDegrees(1/ Contract.EARTH_RADIUS);
		
		double deltaLat = deltaInMeters.y * scaleY;
		double deltaLon = deltaInMeters.x * scaleX;
		
		Location touchLocation = new Location("");
		touchLocation.setLatitude(centerLocation.getLatitude() + deltaLat);
		touchLocation.setLongitude(centerLocation.getLongitude() + deltaLon);
		
		NearestElement ne = MyApplication.getMapTileManager(context).getNearestElement(touchLocation, currentZoom, true,
				// a point within this many on-screen dp of being as close as a road wins over the road
				dp(10f) * metersPerPixel);
		
		if (com.wikiponder.touchmap.BuildConfig.DEBUG && ne.element != null) logHit(touchX, touchY, ne);

		double userDistance = (userLocation!= null) ? userLocation.distanceTo(touchLocation) : Double.MAX_VALUE;
		double  maxAllowedDistance = getWidth() * 0.15 * metersPerPixel;
		
		double minDistance = Math.min(userDistance, ne.distance);
		if (minDistance > maxAllowedDistance || updateListener == null) {
			// Nothing real (or world-place) found in range: if this specific tile has no imported
			// map data at all, let the user know they can download it — once per tile visited, so
			// dragging across a big empty area doesn't repeat the announcement. isTileImported() is a
			// db query, so it's only actually re-run when the touched tile changes (see
			// lastCheckedImportedTile) rather than on every single touch-move frame this branch is hit.
			if (updateListener != null) {
				int[] idx = Utils.latLonToIndex(touchLocation.getLatitude(), touchLocation.getLongitude());
				Point tile = new Point(idx[0], idx[1]);
				boolean imported;
				if (tile.equals(lastCheckedImportedTile)) {
					imported = lastCheckedImportedResult;
				} else {
					imported = MyApplication.getMapTileManager(context).isTileImported(touchLocation);
					lastCheckedImportedTile = tile;
					lastCheckedImportedResult = imported;
				}
				if (!imported && !tile.equals(lastAnnouncedMissingTile)) {
					lastAnnouncedMissingTile = tile;
					updateListener.onUndownloadedAreaTouched(touchLocation);
				}
			}
			return;
		}
		lastAnnouncedMissingTile = null; // touched something real; reset so re-entering empty space announces again

		if (minDistance == ne.distance) {
			updateListener.onElementSelected(ne.element, userLocation, mapRotation, minDistance);
			return;
		}
		if (minDistance == userDistance ) {
			updateListener.onMapMessage("You");
			updateListener.onTouchStop();
			return;
		}
	}
	
	private boolean isTalkBackEnabled() {
		AccessibilityManager accessibilityManager =
				(AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
		
		if (accessibilityManager == null) {
			return false;
		}
		
		List<AccessibilityServiceInfo> enabledServices =
				accessibilityManager.getEnabledAccessibilityServiceList(
						AccessibilityServiceInfo.FEEDBACK_SPOKEN);
		
		return enabledServices != null && !enabledServices.isEmpty();
	}
}