package com.wikiponder.touchmap.classes.boxes;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Parsed {@code boxes_manifest.json} — the planet-tiling partition built by
 * {@code tools/build_global_cluster_dbs.py} and hosted at
 * {@code https://wikiponder.com/navia/global_boxes/}. Every box is a lat/lon rectangle; together they
 * tile the whole planet with no gaps and no overlaps, so a single point falls in exactly one box.
 */
public class BoxManifest {
	public final List<Entry> boxes;
	/** Raised each time the box files are rebuilt, so a downloaded box older than this can be offered
	 * for update. Manifests from before this field count as version 1. */
	public final int mapVersion;

	public BoxManifest(List<Entry> boxes, int mapVersion) {
		this.boxes = boxes;
		this.mapVersion = mapVersion;
	}

	/** True if both list the same box indexes with exactly the same rectangles, in the same order. */
	public boolean hasSameBoxesAs(BoxManifest other) {
		if (boxes.size() != other.boxes.size()) return false;
		for (int i = 0; i < boxes.size(); i++) {
			Entry a = boxes.get(i), b = other.boxes.get(i);
			if (a.index != b.index || a.minLat != b.minLat || a.maxLat != b.maxLat
					|| a.minLon != b.minLon || a.maxLon != b.maxLon) {
				return false;
			}
		}
		return true;
	}

	/** One box db's manifest row: its bounding box, row counts and file sizes. */
	public static final class Entry {
		public final int index;
		public final int nodes;
		public final int ways;
		public final double dbSizeMb;
		public final double gzSizeMb;
		public final double minLat, maxLat, minLon, maxLon;

		Entry(int index, int nodes, int ways, double dbSizeMb, double gzSizeMb,
			  double minLat, double maxLat, double minLon, double maxLon) {
			this.index = index;
			this.nodes = nodes;
			this.ways = ways;
			this.dbSizeMb = dbSizeMb;
			this.gzSizeMb = gzSizeMb;
			this.minLat = minLat;
			this.maxLat = maxLat;
			this.minLon = minLon;
			this.maxLon = maxLon;
		}

		public boolean contains(double lat, double lon) {
			return lat >= minLat && lat <= maxLat && lon >= minLon && lon <= maxLon;
		}

		/** True if this box's db has nothing useful in it (e.g. open ocean) — not worth downloading. */
		public boolean isEmpty() {
			return nodes <= 0 && ways <= 0;
		}
	}

	public static BoxManifest parse(String json) throws Exception {
		JSONObject root = new JSONObject(json);
		JSONArray arr = root.getJSONArray("boxes");
		List<Entry> boxes = new ArrayList<>(arr.length());
		for (int i = 0; i < arr.length(); i++) {
			JSONObject b = arr.getJSONObject(i);
			boxes.add(new Entry(
					b.getInt("index"),
					b.optInt("nodes", 0),
					b.optInt("ways", 0),
					b.optDouble("db_size_mb", 0),
					b.optDouble("gz_size_mb", 0),
					b.getDouble("lat_min"),
					b.getDouble("lat_max"),
					b.getDouble("lon_min"),
					b.getDouble("lon_max")
			));
		}
		return new BoxManifest(boxes, root.optInt("map_version", 1));
	}

	/** The box containing (lat, lon), or null if none does (shouldn't happen — boxes tile the planet
	 * — but the manifest could theoretically be stale/incomplete). Prefers a non-empty box on the rare
	 * chance two entries both claim a point (e.g. a manifest rebuilt mid-download). */
	public Entry findContaining(double lat, double lon) {
		Entry fallback = null;
		for (Entry e : boxes) {
			if (!e.contains(lat, lon)) continue;
			if (!e.isEmpty()) return e;
			if (fallback == null) fallback = e;
		}
		return fallback;
	}

	/**
	 * Every box that shares a border *segment* with {@code box} — not capped at 4. The box tree is a
	 * recursive median-split partition, not a uniform grid, so one box's edge can border several
	 * smaller boxes stacked along it (e.g. a large rural box's east edge facing a run of small dense
	 * ones); treating this as "one neighbor per cardinal direction" would silently skip the extra
	 * ones. A box qualifies by touching on any one of the four sides:
	 * <pre>
	 *   east:  other.minLon == box.maxLon
	 *   west:  other.maxLon == box.minLon
	 *   north: other.minLat == box.maxLat
	 *   south: other.maxLat == box.minLat
	 * </pre>
	 * plus — for an east/west match — the two boxes' latitude ranges must actually overlap (or, for a
	 * north/south match, their longitude ranges must overlap): two boxes can otherwise share the same
	 * border *value* without being anywhere near each other along it (e.g. both happen to end at the
	 * same longitude on opposite sides of the planet). The overlap test is a strict {@code <}/{@code >}
	 * (not {@code <=}/{@code >=}) so two boxes that only touch at a single corner point — zero-length
	 * shared edge — are correctly excluded, matching "common edge" rather than "common corner".
	 * <p>Exact {@code ==} on the border coordinate is safe here, not an epsilon match: adjacent boxes
	 * are split from the same parent rectangle at one shared coordinate, so both sides carry the
	 * identical {@code double} value bit-for-bit, never merely a close one. Used by the download
	 * screen's "larger map" option.
	 */
	public List<Entry> findNeighbors(Entry box) {
		List<Entry> out = new ArrayList<>();
		for (Entry other : boxes) {
			if (other.index == box.index) continue;
			boolean eastOrWest = (other.minLon == box.maxLon || other.maxLon == box.minLon)
					&& rangesOverlap(other.minLat, other.maxLat, box.minLat, box.maxLat);
			boolean northOrSouth = (other.minLat == box.maxLat || other.maxLat == box.minLat)
					&& rangesOverlap(other.minLon, other.maxLon, box.minLon, box.maxLon);
			if (eastOrWest || northOrSouth) out.add(other);
		}
		return out;
	}

	private static boolean rangesOverlap(double aMin, double aMax, double bMin, double bMax) {
		return aMin < bMax && aMax > bMin;
	}
}
