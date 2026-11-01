package com.wikiponder.touchmap.classes.ui;

import android.util.TypedValue;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Works around a real content/ActionBar overlap on Android 15+ (API 35+), where edge-to-edge is
 * enforced regardless of what the app requests. Affects every screen in this app that uses the
 * theme's platform window ActionBar (Theme.AICam, {@code windowActionBar=true}, no Toolbar widget
 * in the layout) without extending {@link BaseActivity} — i.e. every activity except MapActivity.
 * BaseActivity's own edge-to-edge handling is deliberate (the immersive fullscreen map) and unrelated
 * — don't call this there.
 *
 * <p>Root cause, confirmed on-device (Pixel 10 Pro emulator, API 36, vs. a Galaxy S8 on API 28 where
 * the bug does not reproduce): under forced edge-to-edge, {@code ActionBarOverlayLayout} still
 * correctly positions the ActionBar itself below the status bar, but stops offsetting its content
 * sibling by the status bar height + ActionBar height. The content frame ({@code android.R.id.content})
 * ends up laid out from y=0, underneath the ActionBar, while WindowInsets are dispatched to it
 * completely unconsumed (verified with a temporary
 * {@code ViewCompat.setOnApplyWindowInsetsListener} logging {@code systemBars().top}: it read a real,
 * nonzero value at that view). On API &lt; 35 the same probe reads 0 there, because the classic
 * (pre-edge-to-edge) layout already reserves the correct space by itself — hence the {@code top > 0}
 * guard below, which is what keeps this a no-op on older devices instead of double-padding content
 * that's already correctly positioned.
 */
public final class EdgeToEdgeActionBar {

	private EdgeToEdgeActionBar() {}

	/**
	 * Pads the activity's content frame down by (status bar inset + ActionBar height) whenever those
	 * insets arrive unconsumed. Call once from {@code onCreate}, after {@code setContentView}. Safe
	 * to call unconditionally — see the {@code top > 0} guard in the class doc.
	 */
	public static void apply(AppCompatActivity activity) {
		View content = activity.findViewById(android.R.id.content);
		if (content == null) return;

		ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
			int topInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
			int extraPadding = topInset > 0 ? topInset + actionBarHeightPx(activity) : 0;
			// setPadding (not add-to-existing) so repeat dispatches — rotation, multi-window resize,
			// gesture-nav bar toggling — stay idempotent instead of accumulating.
			v.setPadding(v.getPaddingLeft(), extraPadding, v.getPaddingRight(), v.getPaddingBottom());
			return insets;
		});
	}

	/**
	 * Keeps {@code view}'s bottom edge above the navigation bar under forced edge-to-edge, for
	 * screens with a control pinned to the bottom (RecordVisualPlaceActivity's Record button sat
	 * under the gesture bar on a Galaxy S21, API 35, and taps on it went to the system).
	 */
	public static void padBottomForNavigationBar(View view) {
		int basePadding = view.getPaddingBottom();
		ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
			int bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
			v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), basePadding + bottom);
			return insets;
		});
	}

	/** Reads the theme's actual resolved ActionBar height rather than assuming the 56dp default. */
	private static int actionBarHeightPx(AppCompatActivity activity) {
		TypedValue value = new TypedValue();
		if (activity.getTheme().resolveAttribute(android.R.attr.actionBarSize, value, true)) {
			return TypedValue.complexToDimensionPixelSize(value.data, activity.getResources().getDisplayMetrics());
		}
		return 0;
	}
}
