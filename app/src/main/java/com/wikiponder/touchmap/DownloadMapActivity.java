package com.wikiponder.touchmap;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.wikiponder.touchmap.classes.boxes.BoxManifest;
import com.wikiponder.touchmap.classes.boxes.BoxManifestRepository;
import com.wikiponder.touchmap.classes.importer.ImportService;
import com.wikiponder.touchmap.classes.importer.ImportState;
import com.wikiponder.touchmap.classes.ui.EdgeToEdgeActionBar;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Downloads the map data for one location — either the spot passed in via {@link #EXTRA_TARGET_LAT}/
 * {@link #EXTRA_TARGET_LON} (e.g. an area the user touched that has no data yet) or, if neither extra
 * is given, the device's own GPS position. There is nothing to pick: the app looks up which server
 * box (see {@link BoxManifest}) covers that location and downloads only that one (plus its neighbors,
 * if "Larger map" is checked — always primary box first, see BoxImporter.importForLocation), via
 * {@link ImportService}.
 *
 * <p>This screen itself is idle-only: tapping Download starts the import and immediately finishes
 * this Activity — MapActivity owns the actual progress UI for the whole run (see
 * {@code ImportProgressDialog}), since an import is a foreground service that keeps running
 * regardless of which screen (if any) is in front. If a download is already running when this
 * screen opens, the Download button is simply disabled rather than offering a second entry point
 * into the same running import.
 */
public class DownloadMapActivity extends AppCompatActivity {
	private static final String TAG = "DownloadMapActivity";

	public static final String EXTRA_TARGET_LAT = "target_lat";
	public static final String EXTRA_TARGET_LON = "target_lon";

	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private final Handler mainHandler = new Handler(Looper.getMainLooper());

	private TextView infoText;
	private Button downloadButton, closeButton;
	private CheckBox largerMapCheckbox;

	private double[] targetLocation; // {lat, lon}, or null if unresolved
	private BoxManifest.Entry resolvedBox; // set once the manifest lookup succeeds
	// Every non-empty box bordering resolvedBox (not capped at 4 — see BoxManifest.findNeighbors),
	// computed alongside it (cheap, in-memory — no extra network call) so toggling "larger map" can
	// recompute the total size instantly.
	private List<BoxManifest.Entry> neighborBoxes = new ArrayList<>();
	// Whether the box covering targetLocation is already in the local imported_boxes registry (see
	// MapTileManager.isTileImported) — never inferred from whether any nodes/ways happen to be nearby,
	// since an already-downloaded area can be genuinely sparse/empty, and an undownloaded one can show
	// world-place gazetteer stand-ins that look like real data at a glance.
	private boolean alreadyDownloaded = false;
	// That downloaded box is older than the current map version, so the button offers an update.
	private boolean outdated = false;
	private long lookupToken = 0;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_download_map);
		// See EdgeToEdgeActionBar's doc for why this screen needs it: without it, on API 35+ this
		// screen's content renders underneath the ActionBar.
		EdgeToEdgeActionBar.apply(this);
		setTitle(R.string.download_map_title);
		// See BaseActivity's onCreate for why: routes the hardware volume rocker to the same
		// Accessibility stream every sound in this app plays on (AudioClient).
		setVolumeControlStream(AudioManager.STREAM_ACCESSIBILITY);

		infoText = findViewById(R.id.infoText);
		downloadButton = findViewById(R.id.downloadButton);
		closeButton = findViewById(R.id.closeButton);
		largerMapCheckbox = findViewById(R.id.largerMapCheckbox);

		downloadButton.setOnClickListener(v -> onDownloadClicked());
		closeButton.setOnClickListener(v -> finish());
		largerMapCheckbox.setOnCheckedChangeListener((btn, checked) -> showBoxInfo(resolvedBox));

		resolveLocation();
	}

	@Override
	protected void onStart() {
		super.onStart();
		updateButtonStates();
	}

	@Override
	protected void onResume() {
		super.onResume();
		// Paused/resumed on the resume/pause boundary (not just once in onCreate) so it's correct
		// across the whole show/hide cycle, not just the first time this screen appears: e.g. the
		// screen locking and unlocking again while this activity is still on top must leave
		// announcements paused, not accidentally resume them.
		MyApplication.getAnnouncementManager(this).pause();
	}

	@Override
	protected void onPause() {
		super.onPause();
		// Resume as soon as this screen is no longer the active one, for ANY reason — navigating back
		// to MapActivity (which also unconditionally resumes in its own onStart(), so this is
		// harmlessly redundant there) or the whole app going to the background (screen lock, Home,
		// another app coming forward). Announcements are driven by BackgroundService, a foreground
		// service independent of any activity's lifecycle, and must keep running while backgrounded —
		// they should only be suppressed while something belonging to this app itself is what's
		// actually on screen.
		MyApplication.getAnnouncementManager(this).resume();
	}

	// ---- location + box lookup ----

	private void resolveLocation() {
		Intent intent = getIntent();
		if (intent != null && intent.hasExtra(EXTRA_TARGET_LAT) && intent.hasExtra(EXTRA_TARGET_LON)) {
			targetLocation = new double[]{
					intent.getDoubleExtra(EXTRA_TARGET_LAT, 0),
					intent.getDoubleExtra(EXTRA_TARGET_LON, 0)
			};
			infoText.setText(R.string.download_looking_up);
			lookUpBox();
			return;
		}
		infoText.setText(R.string.download_looking_up);
		executor.execute(() -> {
			double[] here = detectLocation();
			mainHandler.post(() -> {
				targetLocation = here;
				if (targetLocation == null) {
					infoText.setText(R.string.download_radius_location_unknown);
					updateButtonStates();
				} else {
					lookUpBox();
				}
			});
		});
	}

	/** Looks up (in the background) which box covers {@link #targetLocation} and whether it's already
	 * downloaded, purely to show a size/status preview — the download itself re-resolves independently
	 * inside {@link ImportService}, so a failure here never blocks the Download button, it just means
	 * no preview. */
	private void lookUpBox() {
		final long token = ++lookupToken;
		final double lat = targetLocation[0], lon = targetLocation[1];
		executor.execute(() -> {
			try {
				BoxManifest manifest = BoxManifestRepository.getOrFetch(this);
				BoxManifest.Entry box = manifest.findContaining(lat, lon);
				// Computed alongside the primary box, not lazily on checkbox-toggle: it's a cheap,
				// purely in-memory scan of the already-loaded manifest (see BoxManifest.findNeighbors),
				// so there's no reason to make toggling "larger map" wait on anything.
				List<BoxManifest.Entry> neighbors = (box != null && !box.isEmpty())
						? manifest.findNeighbors(box) : new ArrayList<>();
				// Local downloaded-boxes registry check (imported_boxes, via MapTileManager), not any
				// inference from the loaded-viewport cache — see the alreadyDownloaded field doc.
				Location loc = new Location("");
				loc.setLatitude(lat);
				loc.setLongitude(lon);
				boolean imported = MyApplication.getMapTileManager(this).isTileImported(loc);
				boolean old = MyApplication.getMapTileManager(this).isTileOutdated(loc, manifest.mapVersion);
				mainHandler.post(() -> {
					if (token != lookupToken) return;
					resolvedBox = box;
					neighborBoxes = neighbors;
					alreadyDownloaded = imported;
					outdated = old;
					downloadButton.setText(old ? R.string.update_map_action : R.string.download_button);
					showBoxInfo(box);
					updateButtonStates();
				});
			} catch (Exception e) {
				Log.w(TAG, "Box lookup failed", e);
				mainHandler.post(() -> {
					if (token != lookupToken) return;
					infoText.setText(R.string.download_size_unknown);
					updateButtonStates();
				});
			}
		});
	}

	private void showBoxInfo(BoxManifest.Entry box) {
		if (box == null || box.isEmpty()) {
			infoText.setText(R.string.download_no_data_here);
			return;
		}
		String sizeText;
		if (largerMapCheckbox.isChecked()) {
			double totalMb = box.gzSizeMb;
			int fileCount = 1;
			for (BoxManifest.Entry n : neighborBoxes) {
				totalMb += n.gzSizeMb;
				fileCount++;
			}
			sizeText = getString(R.string.download_size_label_multi, fileCount, formatMb(totalMb));
		} else {
			sizeText = getString(R.string.download_size_label, formatMb(box.gzSizeMb));
		}
		if (outdated) {
			infoText.setText(getString(R.string.download_update_available) + " " + sizeText);
		} else {
			infoText.setText(alreadyDownloaded
					? getString(R.string.download_already_downloaded_prefix) + " " + sizeText
					: sizeText);
		}
	}

	// ---- import control ----

	private void onDownloadClicked() {
		if (ImportState.get().isRunning()) {
			Toast.makeText(this, R.string.download_in_progress_title, Toast.LENGTH_SHORT).show();
			return;
		}
		if (targetLocation == null) return;
		Intent intent = new Intent(this, ImportService.class);
		intent.setAction(ImportService.ACTION_START);
		intent.putExtra(ImportService.EXTRA_TARGET_LAT, targetLocation[0]);
		intent.putExtra(ImportService.EXTRA_TARGET_LON, targetLocation[1]);
		intent.putExtra(ImportService.EXTRA_INCLUDE_NEIGHBORS, largerMapCheckbox.isChecked());
		intent.putExtra(ImportService.EXTRA_NAME, getString(R.string.download_area_default_name));
		startForegroundService(intent);
		// The import is a foreground service and keeps running independently of any screen — this
		// screen's only job was to start it. MapActivity (below it on the back stack) owns the
		// actual progress UI (ImportProgressDialog) for the rest of the run.
		finish();
	}

	/** Disables the Download button (and explains why) while a download is already running — this
	 * screen no longer offers a Cancel of its own; that lives on MapActivity's progress dialog. */
	private void updateButtonStates() {
		boolean running = ImportState.get().isRunning();
		downloadButton.setEnabled(!running && targetLocation != null
				&& (resolvedBox == null || !resolvedBox.isEmpty()));
		largerMapCheckbox.setEnabled(!running);
		if (running) {
			infoText.setText(R.string.download_in_progress_title);
		}
	}

	@SuppressLint("MissingPermission")
	private double[] detectLocation() {
		if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
				&& checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
			return null;
		}
		try {
			FusedLocationProviderClient client = LocationServices.getFusedLocationProviderClient(this);
			Location location = com.google.android.gms.tasks.Tasks.await(client.getLastLocation());
			if (location == null) return null;
			return new double[]{location.getLatitude(), location.getLongitude()};
		} catch (Exception e) {
			Log.w(TAG, "Location detection failed", e);
			return null;
		}
	}

	private String formatMb(double mb) {
		return String.format(Locale.US, "%.0f MB", mb);
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		executor.shutdownNow();
	}
}
