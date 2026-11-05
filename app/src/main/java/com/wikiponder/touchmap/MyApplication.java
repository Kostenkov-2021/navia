package com.wikiponder.touchmap;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Build;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ProcessLifecycleOwner;
import androidx.preference.PreferenceManager;

import com.wikiponder.touchmap.classes.AngleManager;
import com.wikiponder.touchmap.classes.AnnouncementManager;
import com.wikiponder.touchmap.classes.AudioClient;
import com.wikiponder.touchmap.classes.CrashReporting;
import com.wikiponder.touchmap.classes.MapTileManager;
import com.wikiponder.touchmap.classes.UserLocationManager;
import com.wikiponder.touchmap.classes.db.OSMDatabaseHelper;
import com.wikiponder.touchmap.classes.db.UserEditsBackupHelper;
import com.wikiponder.touchmap.classes.geofabrik.GeofabrikIndexRepository;
import com.wikiponder.touchmap.classes.worldplaces.WorldPlacesRepository;

import java.util.Locale;

public class MyApplication extends Application {
	private Locale preferredLocale;
	private boolean followsSystemLanguage = true;
	private AudioClient audioClient;
	private WorldPlacesRepository worldPlacesRepository;
	private GeofabrikIndexRepository geofabrikIndexRepository;
	private MapTileManager mapTileManager;
	private AngleManager angleManager;
	private UserLocationManager userLocationManager;
	private AnnouncementManager announcementManager;
	
	@Override
	public void onCreate() {
		super.onCreate();
		initialize();
	}
	public void initialize() {
		refreshPreferredLocale();
		// As early as possible so a crash during the rest of startup itself is still captured —
		// see CrashReporting's doc for why no further wiring is needed for the capture itself.
		CrashReporting.init(this);

		final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
		
		// Opens (and, on a version bump, upgrades) osm.db off the main thread as early as possible.
		// SQLiteOpenHelper's getWritableDatabase()/getReadableDatabase() are internally synchronized,
		// so whichever thread opens the db first also blocks every other thread's db call for as long
		// as that open takes — including the UI thread's own touch/hover handling
		// (MapTileManager.isTileImported). Without this warm-up, that first open could otherwise be
		// triggered later by the map-loading background thread at roughly the same time the user's
		// first touch reaches the screen, which is what an ANR trace on this device once showed.
		new Thread(() -> {
			OSMDatabaseHelper.getInstance(this).getWritableDatabase();
			// Re-applies manually-added points and any local renames/favorites that Auto Backup
			// restored into user_edits.db but osm.db itself never carries (osm.db is excluded from
			// backup — see backup_rules.xml). Unconditional and idempotent — see
			// UserEditsBackupHelper's class doc — so it's safe to just always run this after every
			// cold start, not only right after a restore.
			UserEditsBackupHelper.replayInto(this);
		}, "osm-db-warmup").start();

		audioClient = new AudioClient(this);
		audioClient.setSpeechLocale(speechLocaleFor(PreferenceManager.getDefaultSharedPreferences(this).getString("language_preference", "default")));
		// Plays the welcome chime on every ON_START of the WHOLE PROCESS's lifecycle — true cold
		// start, and every later reopen from Home/Recents/a notification, which is what a user
		// actually means by "opening the app" (confirmed live: MyApplication.onCreate() itself only
		// runs once per process, so a plain call here only ever played the chime the very first
		// time — reopening the app after backgrounding it did nothing, since the process usually
		// stays alive with MyApplication never re-constructed). ProcessLifecycleOwner deliberately
		// does NOT fire again during a same-process Activity recreate() — MapActivity.onCreate() is
		// re-run by AppCompatDelegate.setApplicationLocales() on every language change (see
		// applyUiLocale below) and by the permission-retry recreate() — because the whole-process
		// started-activity count never actually reaches zero during those, only during a real
		// backgrounding. See app/build.gradle.kts's lifecycle-process comment for why this needed a
		// new dependency rather than a hand-rolled Application.ActivityLifecycleCallbacks counter.
		// playWelcome() queues itself inside AudioClient if the sample hasn't finished its async
		// SoundPool load yet (only possible on the very first ON_START).
		ProcessLifecycleOwner.get().getLifecycle().addObserver(new DefaultLifecycleObserver() {
			@Override
			public void onStart(LifecycleOwner owner) {
				// Navia's audio identity; Ambimap is a silent visual map until its sound is switched on.
				if (!isAmbimap()) audioClient.playWelcome();
			}
		});
		// Constructed before MapTileManager (which looks this up in its own constructor) and kicked
		// off immediately: downloads world_places.db on first run so it's ready by the time the
		// user explores an area with no imported map.
		worldPlacesRepository = new WorldPlacesRepository(this);
		worldPlacesRepository.initializeAsync();
		// Bundled in the APK (assets/geofabrik_index.db), so this is a cheap local copy on first
		// run rather than a download — kicked off early so it's ready by the time the download
		// screen opens, but GeofabrikIndex.load() also awaits it directly (ensureReady()) if not.
		geofabrikIndexRepository = new GeofabrikIndexRepository(this);
		geofabrikIndexRepository.initializeAsync();
		mapTileManager = new MapTileManager(this);
		announcementManager = new AnnouncementManager(this);
		angleManager = new AngleManager(this, 500);
		// 2s nominal cadence, with fixes allowed as often as every 1s (most GPS hardware can't
		// go faster) so a walking user's position stays current without hammering the battery.
		userLocationManager = new UserLocationManager(this, 2000, 1000);
		
	}
	
	/** True in the Ambimap flavor (sighted users, soundscape only — see app/build.gradle.kts). */
	public static boolean isAmbimap() {
		return "ambimap".equals(BuildConfig.FLAVOR);
	}

	public static AudioClient getAudioClient(Context context) {
		return getMyApplication(context).audioClient;
	}

	public static WorldPlacesRepository getWorldPlacesRepository(Context context) {
		return getMyApplication(context).worldPlacesRepository;
	}

	public static GeofabrikIndexRepository getGeofabrikIndexRepository(Context context) {
		return getMyApplication(context).geofabrikIndexRepository;
	}
	
	/** The language to use for voice input and speech. With the in-app language on "default" this
	 * is computed on every call, because the user can change the app's language at any time in
	 * Android's own per-app language setting, without this process being restarted. */
	public Locale getPreferredLocale() {
		if (followsSystemLanguage) return getDefaultDeviceLocale();
		return preferredLocale;
	}
	
	/** Applies the "theme_preference" choice (system / light / dark) to the whole app. */
	public static void applyTheme(String value) {
		int mode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
		if ("light".equals(value)) mode = AppCompatDelegate.MODE_NIGHT_NO;
		else if ("dark".equals(value)) mode = AppCompatDelegate.MODE_NIGHT_YES;
		AppCompatDelegate.setDefaultNightMode(mode);
	}

	public void refreshPreferredLocale() {
		final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
		String languageTag = sharedPreferences.getString("language_preference", null);

		// first run of the app?
		if (languageTag == null) {
			languageTag = "default";
			sharedPreferences.edit()
					.putString("language_preference",  languageTag)
					.commit();
		}
		if (languageTag.equals("default"))
			preferredLocale = getDefaultDeviceLocale();
		else
			preferredLocale = Locale.forLanguageTag(languageTag);

		this.preferredLocale = preferredLocale;
		followsSystemLanguage = languageTag.equals("default");
		applyUiLocale(languageTag);
		if (audioClient != null) audioClient.setSpeechLocale(speechLocaleFor(languageTag));
		applyTheme(sharedPreferences.getString("theme_preference", "system"));
	}

	/**
	 * Makes "language_preference" also drive which values-xx/strings.xml the app displays (it
	 * previously only fed {@link #getPreferredLocale()}, used solely as the SpeechRecognizer locale
	 * for voice input). AppCompatDelegate.setApplicationLocales() is the AndroidX per-app-language
	 * API: it persists the choice itself and recreates every running AppCompatActivity to apply it,
	 * so no manual Activity.recreate()/attachBaseContext override is needed anywhere else.
	 */
	private void applyUiLocale(String languageTag) {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
		String previouslyApplied = prefs.getString("ui_locale_applied", "default");
		prefs.edit().putString("ui_locale_applied", languageTag).apply();
		if (languageTag.equals("default")) {
			// Only reset when coming back from an explicit in-app choice. Otherwise leave it alone,
			// so a language picked in Android's per-app language settings survives app restarts.
			if (!previouslyApplied.equals("default")) {
				// Empty list = "follow the system language", not "force English".
				AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList());
			}
			return;
		}
		AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(toResourceLocaleTag(languageTag)));
	}

	/**
	 * language_codes (languages.xml) uses Google speech-recognition locale tags, which for Chinese
	 * are the ISO 639-3 macrolanguage codes "cmn"/"yue" that RecognizerIntent expects — not the "zh"
	 * ISO 639-1 code Android's own resource-qualifier matching looks for. Left unmapped, picking
	 * either Chinese entry would silently fail to select values-zh-rCN/values-zh-rHK and fall back to
	 * English. Every other tag in that array is already a resource-resolvable standard code as-is.
	 */
	/** Locale for text-to-speech, with the Chinese speech-recognition codes mapped to resource tags. */
	public Locale getSpeechLocale() {
		return speechLocaleFor(PreferenceManager.getDefaultSharedPreferences(this).getString("language_preference", "default"));
	}

	private Locale speechLocaleFor(String languageTag) {
		return languageTag.equals("default")
				? getDefaultDeviceLocale()
				: Locale.forLanguageTag(toResourceLocaleTag(languageTag));
	}

	private String toResourceLocaleTag(String speechLanguageTag) {
		if (speechLanguageTag.startsWith("cmn-Hans")) return "zh-Hans-CN";
		if (speechLanguageTag.startsWith("yue-Hant")) return "zh-Hant-HK";
		return speechLanguageTag;
	}
	
	public static MapTileManager getMapTileManager(Context context) {
		return getMyApplication(context).mapTileManager;
	}
	
	public static AnnouncementManager getAnnouncementManager(Context context) {
		return getMyApplication(context).announcementManager;
	}
	
	public static AngleManager getAngleManager(Context context) {
		return getMyApplication(context).angleManager;
	}
	
	public static UserLocationManager getUserLocationManager(Context context) {
		return getMyApplication(context).userLocationManager;
	}
	
	/**
	 * Gets the default locale of the device.
	 * <p>
	 * This method retrieves the primary locale configured on the device.
	 * For Android API level 24 (Nougat) and above, it uses the `getLocales()` method
	 * from `Configuration` to get the primary locale. For older versions, it falls
	 * back to the deprecated `locale` field of the `Configuration` object.
	 *
	 * @return The default {@link Locale} of the device.
	 */
	public Locale getDefaultDeviceLocale() {
		// A language chosen in Android's per-app language settings (or by this app's empty/explicit
		// locale list) takes precedence over the device-wide language.
		LocaleListCompat appLocales = AppCompatDelegate.getApplicationLocales();
		if (!appLocales.isEmpty() && appLocales.get(0) != null) return appLocales.get(0);
		Locale locale;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
			// For API 24 and above, get the primary locale from the system configuration.
			locale = Resources.getSystem().getConfiguration().getLocales().get(0);
		} else {
			// For older versions, get the locale from the configuration directly.
			//noinspection deprecation
			locale = Resources.getSystem().getConfiguration().locale;
		}
		return locale;
	}
	public static MyApplication getMyApplication(Context context) {
		return (MyApplication)(context.getApplicationContext());
	}
}