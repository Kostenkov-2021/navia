package com.wikiponder.touchmap.classes;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

/** Spoken coaching for touch exploration, each one dropped once the user shows they have learned it:
 * "hold and move your finger to read the map" on the map's TalkBack label until enough points were
 * read by touch, and "lift and double-tap for options" after each touched point until the point menu
 * was opened that way a few times. */
public final class TouchHints {
	private static final String KEY_POINTS_READ = "hint_points_read";
	private static final String KEY_MENU_OPENS = "hint_point_menu_opens";
	private static final int POINTS_TO_LEARN = 50;
	private static final int MENU_OPENS_TO_LEARN = 3;

	private TouchHints() {}

	public static boolean needsExploreHint(Context context) {
		return prefs(context).getInt(KEY_POINTS_READ, 0) < POINTS_TO_LEARN;
	}

	public static void onPointRead(Context context) {
		increment(context, KEY_POINTS_READ, POINTS_TO_LEARN);
	}

	public static boolean needsDoubleTapHint(Context context) {
		return prefs(context).getInt(KEY_MENU_OPENS, 0) < MENU_OPENS_TO_LEARN;
	}

	public static void onPointMenuOpened(Context context) {
		increment(context, KEY_MENU_OPENS, MENU_OPENS_TO_LEARN);
	}

	/** Stops writing once the limit is reached, so a learned hint costs nothing per touch. */
	private static void increment(Context context, String key, int limit) {
		SharedPreferences prefs = prefs(context);
		int count = prefs.getInt(key, 0);
		if (count < limit) prefs.edit().putInt(key, count + 1).apply();
	}

	private static SharedPreferences prefs(Context context) {
		return PreferenceManager.getDefaultSharedPreferences(context);
	}
}
