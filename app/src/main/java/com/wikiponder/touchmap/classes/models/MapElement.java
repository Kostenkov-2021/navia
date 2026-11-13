package com.wikiponder.touchmap.classes.models;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.util.Log;

import androidx.preference.PreferenceManager;

import com.wikiponder.touchmap.classes.TypeLabels;
import com.wikiponder.touchmap.classes.Utils;
import com.wikiponder.touchmap.classes.db.OSMDatabaseHelper;

import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.value.Value;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class MapElement {
	private static final String TAG = "MapElement";

	/**
	 * Tag keys used purely to classify a feature's category/type — see
	 * {@code tools/build_global_cluster_dbs.py}'s {@code IMPORTANT_NODE_TAGS}/{@code get_way_type},
	 * which this list mirrors (the source of truth for which tag ends up driving {@link #type}/
	 * {@link #catId} server-side). Redundant to repeat in a tag details list since the classification
	 * they carry is already conveyed by the element's name/icon — see {@link #getDisplayableTags()}.
	 */
	private static final Set<String> CATEGORY_TAG_KEYS = new HashSet<>(Arrays.asList(
			"emergency", "healthcare", "highway", "railway", "public_transport", "barrier",
			"shop", "amenity", "leisure", "tourism", "historic", "natural", "waterway", "building"
	));

	// Whether this element is a favorite. Set directly from the query result — no MapTile needed.
	private final boolean favorite;
	public int catId;
	public String type;
	public String name;
	public Map<String, String> tags;
	public double lat;
	public double lon;
	public long id;

	public MapElement(boolean favorite, long id, double lat, double lon, int catId, String type, String name, byte[] packedTags) {
		this.id = id;
		this.lat = lat;
		this.lon = lon;
		this.catId = catId;
		this.type = type;
		this.name = name;
		this.favorite = favorite;

		// unpack tags
		this.tags = new LinkedHashMap<>();
		if (packedTags!= null && packedTags.length >0) {
			try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(packedTags)) {
				for (Map.Entry<Value, Value> entry : unpacker.unpackValue().asMapValue().entrySet()) {
					this.tags.put(entry.getKey().asStringValue().toString(), entry.getValue().asStringValue().toString());
				}
			} catch (Exception e) {
				Log.e(TAG, e.getMessage(), e);
			}
		}
	}
	public MapElement() {
		this(false, -1, 0, 0, OSMDatabaseHelper.CATEGORY_NONE, "", "", null);
	}
	public Location getLocation() {
		Location location = new Location("");
		location.setLatitude(lat);
		location.setLongitude(lon);
		return location;
	}
	
	public String getFormattedName(Context context, boolean shortName) {
		if (type.equals("address") && !name.isBlank()) {
			final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
			return Utils.formatAddress(name, sharedPreferences.getString("address_format", "auto"));
		}
		else if(catId == OSMDatabaseHelper.CATEGORY_INTERSECTION) {
			return getTypeLabel(context) + ": " + name;
		}
		else if (OSMDatabaseHelper.isWorldPlaceCategory(catId) && !name.isBlank()) {
			// A world-places gazetteer stand-in (no imported map here yet): say the kind too, since
			// the name alone ("Paris") is ambiguous as a substitute for real, detailed map data.
			return name + ", " + getTypeLabel(context);
		}
		else if (!name.isBlank())
			return name;
		else
			return getTypeLabel(context);
	}

	public String getTypeLabel(Context context) {
		return TypeLabels.label(context, catId, type);
	}
	
	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (!(o instanceof MapElement)) return false;
		MapElement other = (MapElement) o;
		return this.id == other.id;
	}
	
	@Override
	public int hashCode() {
		return Long.hashCode(id);
	}
	
	public boolean isFavorite() {
		return favorite;
	}

	/**
	 * {@link #tags} with the pure-classification keys (see {@link #CATEGORY_TAG_KEYS}) removed — what
	 * should actually be shown to the user in a tag/details list, since those keys just repeat the
	 * category already conveyed by the element's name/icon (e.g. a bench's {@code amenity=bench} adds
	 * nothing once "Bench" is already the displayed name/type).
	 */
	public Map<String, String> getDisplayableTags() {
		if (tags.isEmpty()) return tags;
		LinkedHashMap<String, String> out = new LinkedHashMap<>();
		for (Map.Entry<String, String> e : tags.entrySet()) {
			if (!CATEGORY_TAG_KEYS.contains(e.getKey())) out.put(e.getKey(), e.getValue());
		}
		return out;
	}

	/** True if {@link #getDisplayableTags()} would show anything — used to disable a "details"/
	 * "show tags" menu item when there's nothing to show. */
	public boolean hasDisplayableTags() {
		return !getDisplayableTags().isEmpty();
	}
}
