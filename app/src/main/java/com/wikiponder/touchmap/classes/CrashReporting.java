package com.wikiponder.touchmap.classes;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.preference.PreferenceManager;

import com.google.firebase.crashlytics.FirebaseCrashlytics;
import com.wikiponder.touchmap.BuildConfig;

/**
 * Thin wrapper around Firebase Crashlytics — if a real user's app crashes, the stack trace (plus
 * device model/OS version, which Crashlytics attaches automatically) uploads to the Firebase
 * console for the touchmap-testlab project on the crashing user's next app launch, with no action
 * required from them. No manual exception-handler wiring is needed for the crash-capture itself:
 * the Crashlytics SDK installs its own {@code Thread.UncaughtExceptionHandler} (chained with
 * whatever was already set) as soon as it's on the classpath. This class only adds the small
 * amount of manual value beyond that default: recording non-fatal (caught) errors that would
 * otherwise vanish into a log no one reads, and a couple of custom keys so a report in the console
 * is easier to place (which build, TalkBack on/off).
 */
public final class CrashReporting {

	private CrashReporting() {}

	private static final String DEBUG_FORCE_CRASH_ACTION = "com.wikiponder.touchmap.DEBUG_FORCE_CRASH";

	/** SharedPreferences key of the Settings "Send crash reports" switch (opt-in, default off). */
	public static final String PREF_KEY = "crash_reports";

	/** Null when no FirebaseApp exists — Ambimap has no google-services.json yet (see
	 * app/build.gradle.kts), and getInstance() would throw there. Every entry point goes through this. */
	private static FirebaseCrashlytics crashlytics() {
		return appContext == null || com.google.firebase.FirebaseApp.getApps(appContext).isEmpty() ? null : FirebaseCrashlytics.getInstance();
	}

	private static Context appContext;

	public static void init(Context context) {
		appContext = context.getApplicationContext();
		FirebaseCrashlytics crashlytics = crashlytics();
		if (crashlytics == null) return;
		// Opt-in (GDPR/ePrivacy): the manifest turns automatic collection off
		// (firebase_crashlytics_collection_enabled) and nothing is sent until the user switches it on.
		crashlytics.setCrashlyticsCollectionEnabled(PreferenceManager
				.getDefaultSharedPreferences(context).getBoolean(PREF_KEY, false));
		crashlytics.setCustomKey("version_name", versionName(context));
		if (BuildConfig.DEBUG) {
			registerDebugCrashTrigger(context);
		}
	}

	/** Applies the Settings switch immediately. Turning it off also discards any report already
	 * queued on the device, so nothing recorded before the opt-out is uploaded afterwards. */
	public static void setCollectionEnabled(boolean enabled) {
		FirebaseCrashlytics crashlytics = crashlytics();
		if (crashlytics == null) return;
		crashlytics.setCrashlyticsCollectionEnabled(enabled);
		if (!enabled) {
			crashlytics.deleteUnsentReports();
		}
	}

	/** Debug builds only: `adb shell am broadcast -a com.wikiponder.touchmap.DEBUG_FORCE_CRASH`
	 * throws immediately, so the whole capture -> next-launch-upload -> Firebase-console pipeline
	 * can be exercised on a real device without shipping a "Force crash" button to real users. */
	private static void registerDebugCrashTrigger(Context context) {
		Context appContext = context.getApplicationContext();
		BroadcastReceiver receiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context ctx, Intent intent) {
				throw new RuntimeException("Debug-triggered test crash (CrashReporting.DEBUG_FORCE_CRASH)");
			}
		};
		IntentFilter filter = new IntentFilter(DEBUG_FORCE_CRASH_ACTION);
		// The 3-arg overload (with an explicit exported/not-exported flag) only exists from API 33 —
		// minSdk here is 26, so this has to branch rather than always using it.
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
		} else {
			appContext.registerReceiver(receiver, filter);
		}
	}

	/** Records a caught (non-fatal) exception so it still shows up in the Crashlytics console —
	 * for spots that currently catch-and-swallow an error that's worth knowing about remotely,
	 * without crashing the app outright. {@code context} is a short human label (e.g. "box import"),
	 * not an Android Context. */
	public static void reportNonFatal(String context, Throwable t) {
		FirebaseCrashlytics crashlytics = crashlytics();
		if (crashlytics == null) return;
		crashlytics.log(context);
		crashlytics.recordException(t);
	}

	private static String versionName(Context context) {
		try {
			PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
			return info.versionName != null ? info.versionName : "unknown";
		} catch (PackageManager.NameNotFoundException e) {
			return "unknown";
		}
	}
}
