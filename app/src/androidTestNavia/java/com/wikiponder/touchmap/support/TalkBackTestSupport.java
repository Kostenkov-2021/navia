package com.wikiponder.touchmap.support;

import android.app.UiAutomation;
import android.content.SharedPreferences;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;

import androidx.preference.PreferenceManager;
import androidx.test.espresso.Espresso;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.IOException;
import java.io.InputStream;

/**
 * Shared shell-command helpers for androidTest smoke tests that need real system TalkBack on, not
 * just something faked from inside the app under test — {@code MapView.isTalkBackEnabled()} reads
 * the actual {@code AccessibilityManager} enabled-services list, and that flag decides whether a
 * single-finger swipe is treated as touch-exploration or a pan gesture in
 * {@code MapView.onTouchEvent}. Used by every smoke test in this package so TalkBack setup/teardown
 * and permission granting stay in one place instead of duplicated per test class.
 */
public final class TalkBackTestSupport {
	private static final String TAG = "TalkBackTestSupport";

	private TalkBackTestSupport() {
	}

	/** Grants runtime permissions before the app under test ever launches, so no system permission
	 * dialog can block a script. Test Lab normally auto-grants manifest-declared runtime permissions
	 * already; this is a defensive pre-grant in case that default ever changes. */
	public static void grantCorePermissions() {
		UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
		String pkg = InstrumentationRegistry.getInstrumentation().getTargetContext().getPackageName();
		for (String perm : new String[]{
				"android.permission.ACCESS_FINE_LOCATION",
				"android.permission.ACCESS_COARSE_LOCATION",
				"android.permission.ACCESS_BACKGROUND_LOCATION",
				"android.permission.POST_NOTIFICATIONS"}) {
			shell(automation, "pm grant " + pkg + " " + perm);
		}
	}

	public static void enableTalkBack() {
		UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
		shell(automation, "settings put secure enabled_accessibility_services "
				+ "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService");
		shell(automation, "settings put secure accessibility_enabled 1");
	}

	public static void disableTalkBack() {
		UiAutomation automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
		shell(automation, "settings put secure accessibility_enabled 0");
	}

	/** Pre-dismisses MapActivity's first-run "TalkBack required" notice before it ever launches, by
	 * writing the same preference flag it checks directly — instrumentation tests share the app's own
	 * process/UID, so this needs no shell command or UI interaction, just the same SharedPreferences
	 * write MapActivity itself does. Without this, a fresh install's first ActivityScenario.launch(...)
	 * in a test would show that dialog over whatever the test tries to interact with first — a real
	 * dialog these tests should never need to know about, since it isn't what they're testing. */
	public static void suppressFirstRunNotices() {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(
				InstrumentationRegistry.getInstrumentation().getTargetContext());
		prefs.edit().putBoolean("talkback_required_notice_shown", true).apply();
	}

	/** Runs one labeled test step, logging and swallowing any failure so the caller's overall test
	 * method keeps going — shared by every smoke test in this package rather than each having its own
	 * copy. A best-effort {@link Espresso#pressBack()} after a failure tries to recover to whatever
	 * screen the next step expects, e.g. when a swipe under real TalkBack gets reinterpreted by the
	 * system's touch-exploration layer and lands on a map element (opening its actions dialog) instead
	 * of just panning — real, expected behavior, not a bug, but it must not block every step after it.
	 * Splitting a sequence into several step() calls (one per swipe direction, say) rather than one
	 * call with several actions is what actually isolates each one from the others' failures. */
	public static void step(String tag, String name, Runnable action) {
		Log.i(tag, "Step: " + name);
		try {
			action.run();
		} catch (Throwable t) {
			Log.w(tag, "Step failed (continuing): " + name, t);
			try {
				Espresso.pressBack();
			} catch (Throwable ignored) {
				// nothing to pop, or already back where expected — fine either way
			}
		}
		SystemClock.sleep(1000);
	}

	public static void shell(UiAutomation automation, String cmd) {
		try (ParcelFileDescriptor pfd = automation.executeShellCommand(cmd);
			 InputStream is = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
			byte[] buf = new byte[1024];
			while (is.read(buf) != -1) {
				// drain output
			}
		} catch (IOException e) {
			Log.w(TAG, "shell command failed: " + cmd, e);
		}
	}
}
