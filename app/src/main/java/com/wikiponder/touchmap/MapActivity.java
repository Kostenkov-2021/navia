package com.wikiponder.touchmap;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.view.accessibility.AccessibilityManager;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;

import com.wikiponder.touchmap.classes.AngleListener;
import com.wikiponder.touchmap.classes.db.OSMDatabaseHelper;
import com.wikiponder.touchmap.classes.importer.ImportService;
import com.wikiponder.touchmap.classes.importer.ImportState;
import com.wikiponder.touchmap.classes.interfaces.UserLocationListener;
import com.wikiponder.touchmap.classes.models.MapElement;
import com.wikiponder.touchmap.classes.ui.ActionsDialog;
import com.wikiponder.touchmap.classes.ui.AddressSearch;
import com.wikiponder.touchmap.classes.ui.BaseActivity;
import com.wikiponder.touchmap.classes.ui.ElementDetailsDialog;
import com.wikiponder.touchmap.classes.ui.FilterDialog;
import com.wikiponder.touchmap.classes.ui.ImportProgressDialog;
import com.wikiponder.touchmap.classes.ui.NodeAddingDialogBox;
import com.wikiponder.touchmap.classes.ui.NodeEditDialogBox;
import com.wikiponder.touchmap.classes.ui.SharePromptDialog;
import com.wikiponder.touchmap.classes.vpr.VisualCheckController;
import com.wikiponder.touchmap.classes.vpr.VisualPlacesDb;
import com.wikiponder.touchmap.classes.vpr.VprModel;
import com.wikiponder.touchmap.databinding.ActivityMapBinding;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MapActivity extends BaseActivity
		implements AngleListener, UserLocationListener, SharedPreferences.OnSharedPreferenceChangeListener,
		ImportState.Listener, VisualCheckController.Listener {
	
	private static final String TAG = "MapActivity";
	
	private ActivityMapBinding binding;
	private boolean compassLock = true;
	private SharedPreferences sharedPreferences;
	// The last completedImportVersion this activity has already reacted to — lets onStart() notice
	// a download that finished while DownloadMapActivity (not this activity) held the
	// ImportState listener slot, without re-triggering a reload for the same completion twice.
	private long lastSeenImportVersion = -1;
	// Set once the very first GPS fix after launch has been checked against the downloaded-boxes
	// registry (see maybeShowStartupDownloadPrompt) — an instance field, not persisted, so this is a
	// once-per-launch check, not a once-ever one: a user who dismisses it and never downloads
	// anything should see it again next time they open the app, unlike the one-time TalkBack notice.
	private boolean startupDownloadPromptChecked = false;
	// The download-progress dialog (see ImportProgressDialog), owned here rather than by
	// DownloadMapActivity — that screen now finishes as soon as it starts the import. Null
	// whenever no import is running/being shown.
	private ImportProgressDialog importProgressDialog;
	// How long to wait, after the user confirms Cancel, for the service to actually report back
	// (onImportFinished) before giving up on waiting and closing the dialog locally anyway — see
	// onImportCancelConfirmed. The worker thread can be stuck in a step that isn't truly
	// interruptible (a single blocking SQL statement — see BoxImporter's cancelled checks), so a
	// confirmed cancel isn't always followed by a prompt finish(); the user shouldn't be stuck
	// staring at a non-dismissible dialog because of that.
	private static final long CANCEL_FORCE_CLOSE_DELAY_MS = 8000;
	private final Handler cancelTimeoutHandler = new Handler(Looper.getMainLooper());
	// Non-null only while waiting out CANCEL_FORCE_CLOSE_DELAY_MS after a confirmed cancel; cleared
	// the moment onImportFinished actually arrives (see there) so a later, genuine finish() doesn't
	// also fire the force-close toast on top of its own normal one.
	private Runnable cancelTimeoutRunnable;
	// True strictly while the first-run TalkBack notice is on screen — see
	// showTalkBackRequiredNoticeIfFirstRun's dismiss listener for why the download-prompt check is
	// deferred while this is true (avoids stacking two AlertDialogs, one hidden behind the other).
	private boolean talkBackNoticeShowing = false;
	private Location pendingStartupDownloadCheckLocation;

	// Intercepts the system Back button to cancel whichever of {aiming, on-map search filter} is
	// currently active, instead of Back's normal action here (BaseActivity's double-back-to-exit) —
	// enabled only while one of those two modes is active (see showAimCancelButton/
	// showSearchCancelButton/cancelAiming/cancelSearchOnMap), so it's a no-op the rest of the time.
	// Registered *after* BaseActivity's own back callback (super.onCreate() runs first), so the
	// dispatcher tries this one first — falling through to the base double-back-to-exit behavior only
	// while this stays disabled.
	private final OnBackPressedCallback modeBackCallback = new OnBackPressedCallback(false) {
		@Override
		public void handleOnBackPressed() {
			if (MyApplication.getMapTileManager(MapActivity.this).getAimedElement() != null) {
				cancelAiming();
			} else if (MyApplication.getMapTileManager(MapActivity.this).getKeyword() != null) {
				cancelSearchOnMap();
			} else {
				setEnabled(false);
			}
		}
	};

	private final ActivityResultLauncher<String[]> requestPermissionsLauncher =
			registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
				boolean allGranted = true;
				for (Map.Entry<String, Boolean> entry : result.entrySet()) {
					if (!entry.getKey().equals(Manifest.permission.POST_NOTIFICATIONS) && !entry.getValue()) {
						allGranted = false;
						break;
					}
				}
				
				if (allGranted) {
					performActionsNeedPermissions();
				} else {
					new AlertDialog.Builder(this)
							.setMessage(R.string.permissions_required_message)
							.setNegativeButton(R.string.action_grant_permissions, (dialog, which) -> {
								MapActivity.this.recreate();
							})
							.setPositiveButton(R.string.action_close, (dialog, which) -> {
								MapActivity.this.finish();
							})
							.show();
				}
			});
	private boolean permissionsRequested;

	// Visual place check (experimental): the offer button appears when a place saved under
	// More > Place photos is within VisualCheckController.RADIUS_M; see updateVisualCheckOffer.
	private VisualCheckController visualCheck;
	private static final long VISUAL_OFFER_CHECK_MS = 10_000;
	private long lastVisualOfferCheck;
	private String visualOfferPlace;
	private final ActivityResultLauncher<String> cameraPermissionLauncher =
			registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
				if (granted) startVisualCheck();
				else Toast.makeText(this, R.string.camera_permission_required, Toast.LENGTH_LONG).show();
			});
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		binding = ActivityMapBinding.inflate(getLayoutInflater());
		setContentView(binding.getRoot());
		
		binding.mapView.setOnUpdateListener(MyApplication.getAnnouncementManager(this));
		MyApplication.getAnnouncementManager(this).setDownloadOfferListener(this::showDownloadAreaAction);
		getOnBackPressedDispatcher().addCallback(this, modeBackCallback);

		setupButtons();
		visualCheck = new VisualCheckController(this, this);
		binding.visualCheckButton.setOnClickListener(v -> toggleVisualCheck());
		binding.visualCheckCameraButton.setOnClickListener(v -> {
			visualCheck.setFrontCamera(!visualCheck.isFrontCamera());
			MyApplication.getAudioClient(this).speak(getString(
					visualCheck.isFrontCamera() ? R.string.front_camera_on : R.string.back_camera_on));
			updateVisualCheckButton();
		});
		this.permissionsRequested = false;
		// Baseline so the first onStart() doesn't mistake "no import has ever run" for "one just
		// finished" — only later increases past this starting point should trigger a reload.
		lastSeenImportVersion = ImportState.get().getCompletedImportVersion();

		sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
		sharedPreferences.registerOnSharedPreferenceChangeListener(this);

		updateKeepScreenOnFlag();
		updateRenderMapFlag();
		showSafetyWarningIfNotAccepted();
	}

	/** First launch only: a modal, non-cancelable warning that the app's information can be wrong and
	 * must never replace a cane or guide dog. The app stays unusable behind it until the user accepts;
	 * the TalkBack notice follows once it is dismissed. Reuses talkBackNoticeShowing so the startup
	 * download prompt is deferred (not stacked on top) while either dialog is up. */
	private void showSafetyWarningIfNotAccepted() {
		if (sharedPreferences.getBoolean("safety_warning_accepted", false)) {
			showTalkBackRequiredNoticeIfFirstRun();
			return;
		}
		talkBackNoticeShowing = true;
		new AlertDialog.Builder(this)
				.setTitle(R.string.safety_warning_title)
				.setMessage(R.string.safety_warning_message)
				.setCancelable(false)
				.setPositiveButton(R.string.safety_warning_accept, (dialog, which) ->
						sharedPreferences.edit().putBoolean("safety_warning_accepted", true).apply())
				.setOnDismissListener(dialog -> {
					talkBackNoticeShowing = false;
					showTalkBackRequiredNoticeIfFirstRun();
				})
				.show();
	}

	/** Shown once, the very first time the app is opened: explains that this app is built around
	 * TalkBack and needs it turned on to work correctly — shown unconditionally on first run
	 * regardless of TalkBack's current state, as a "how this app works" notice for a new user, not a
	 * live status check. The AlertDialog itself already pauses announcements while it's up, same as
	 * every other dialog on this screen (see BaseActivity's window-focus handling) — no extra wiring
	 * needed here. */
	private void showTalkBackRequiredNoticeIfFirstRun() {
		if (sharedPreferences.getBoolean("talkback_required_notice_shown", false)) return;
		sharedPreferences.edit().putBoolean("talkback_required_notice_shown", true).apply();

		talkBackNoticeShowing = true;
		new AlertDialog.Builder(this)
				.setTitle(R.string.talkback_required_title)
				.setMessage(R.string.talkback_required_message)
				.setCancelable(true)
				.setPositiveButton(android.R.string.ok, null)
				.setNegativeButton(R.string.talkback_required_open_settings, (dialog, which) ->
						startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
				// A GPS fix (triggering maybeShowStartupDownloadPrompt) can arrive before the user
				// dismisses this dialog, which would otherwise stack a second AlertDialog on top and
				// hide this one underneath it entirely (found live: dismissing the download prompt
				// revealed this notice still sitting there, unacknowledged). Deferring the download
				// check to this dismiss callback instead means at most one dialog is ever showing.
				.setOnDismissListener(dialog -> {
					talkBackNoticeShowing = false;
					if (pendingStartupDownloadCheckLocation != null) {
						Location location = pendingStartupDownloadCheckLocation;
						pendingStartupDownloadCheckLocation = null;
						checkAndShowStartupDownloadPrompt(location);
					}
				})
				.show();
	}

	@Override
	protected void onStart() {
		super.onStart();
		MyApplication.getAnnouncementManager(this).resume();
		// Picks up a download that finished while this activity wasn't the one showing progress —
		// see onImportFinished — and also becomes the progress dialog's owner for one already
		// running (started from DownloadMapActivity, which finishes as soon as it kicks the
		// import off — see ImportProgressDialog).
		ImportState.get().setListener(this);
		if (ImportState.get().isRunning()) {
			showImportProgressDialogIfNeeded();
			importProgressDialog.updateProgress(ImportState.get().getStage(), ImportState.get().getPercent());
		}
		// Covers the common case: the download screen (not this activity) held the listener slot
		// at the moment a normal, foreground-watched import completed, so onImportFinished below
		// never fired here — but the version bump it left behind is still visible now.
		long v = ImportState.get().getCompletedImportVersion();
		if (v != lastSeenImportVersion) {
			lastSeenImportVersion = v;
			MyApplication.getMapTileManager(this).reloadCurrentArea();
		}
		// Another screen (e.g. NodeListActivity's "Zoom to Point on Map") may have asked to recenter
		// the map here — regardless of wherever it was already showing. setCenterLocation's own
		// updateMapView() call already refreshes the explore cache for the new location, so nothing
		// extra is needed for that.
		Location zoomTarget = MyApplication.getMapTileManager(this).consumePendingZoomTarget();
		if (zoomTarget != null) {
			binding.mapView.setCenterLocation(zoomTarget, false);
			binding.mapView.zoomIn();
		}
		// Likewise for NodeListActivity's "Aim at Point": the actual aiming (MapTileManager's
		// aimedElement, which is what BackgroundService's compass-driven announcements read) already
		// took effect the moment it was requested, independent of which screen is showing — this just
		// catches this screen's own action-button UI up to reflect it, one-shot so it doesn't fire on
		// every later onStart() and clobber an unrelated "download this area" use of the same button.
		MapElement pendingAim = MyApplication.getMapTileManager(this).consumePendingAimUiRefresh();
		if (pendingAim != null) {
			showAimCancelButton(pendingAim);
		}
	}

	@Override
	protected void onStop() {
		super.onStop();
		// clearListener (not setListener(null)): during a brief transition where another activity
		// also registers itself, Android runs the new activity's onStart() before this onStop() —
		// an unconditional null here would wipe out its just-made registration.
		ImportState.get().clearListener(this);
	}

	@Override
	protected boolean interceptExit(Runnable exit) {
		return SharePromptDialog.showIfDue(this, exit);
	}

	@Override
	public void onStopByUser() {
		// The one deliberate-exit hook (BaseActivity's double-back-to-exit) — see BaseActivity's
		// class doc. No delay needed before the finish() that follows this: AudioClient/SoundPool
		// live at Application scope, not tied to this Activity's window, so the chime keeps
		// playing through the teardown exactly like BackgroundService's announcements do.
		MyApplication.getAudioClient(this).playGoodbye();
		stopService();
	}
	
	@Override
	protected void onDestroy() {
		super.onDestroy();
		visualCheck.shutdown();
		// The pending force-close Runnable (see onImportCancelConfirmed) captures this Activity —
		// drop it so a cancel confirmed just before leaving this screen can't fire its callback
		// (dialog dismiss + Toast) against an already-destroyed Activity later.
		if (cancelTimeoutRunnable != null) {
			cancelTimeoutHandler.removeCallbacks(cancelTimeoutRunnable);
			cancelTimeoutRunnable = null;
		}
		// Whenever this screen is actually torn down — explicit exit, or the system reclaiming
		// it — stop announcements/vibration immediately. BackgroundService.onTaskRemoved() covers
		// the app being swiped away from Recents; this covers every other teardown path. The
		// importer (ImportService) is a separate service and is unaffected either way.
		stopService();
		// Balances performActionsNeedPermissions()'s addListener(this). Without this the app-scoped
		// UserLocationManager singleton keeps a strong reference to this (destroyed) Activity — and
		// therefore its whole view tree, MapView render caches included — for the life of the process,
		// with two knock-on effects beyond the leak itself: onLocationUpdated keeps firing on a dead
		// Activity (touching binding.* and, via maybeShowStartupDownloadPrompt, potentially trying to
		// show a dialog on a destroyed window), and because UserLocationManager only calls stop() once
		// its listener list goes empty, the high-accuracy GPS request never stops after the user exits.
		MyApplication.getUserLocationManager(this).removeListener(this);
		if (sharedPreferences != null) {
			sharedPreferences.unregisterOnSharedPreferenceChangeListener(this);
		}
	}
	
	private void startService() {
		Intent serviceIntent = new Intent(this, BackgroundService.class);
		startForegroundService(serviceIntent);
	}
	
	private void stopService() {
		Intent intent = new Intent(this, BackgroundService.class);
		intent.setAction(BackgroundService.ACTION_STOP);
		startService(intent);
		
		intent = new Intent(this, BackgroundService.class);
		stopService(intent);
		Log.d(TAG, "service stopped.");
	}
	
	private void setupButtons() {
		binding.mapView.setOnClickListener((v) -> showNodeActionsDialog());
		binding.zoomInButton.setOnClickListener(v -> binding.mapView.zoomIn());
		binding.zoomOutButton.setOnClickListener(v -> binding.mapView.zoomOut());
		binding.recenterButton.setOnClickListener(v -> binding.mapView.recenter());
		binding.mapLockButton.setOnClickListener((view) -> {
			compassLock = !compassLock;
			binding.mapLockButton.setContentDescription(compassLock
					? getString(R.string.content_description_map_lock)
					: getString(R.string.content_description_switch_to_compass_lock));
			binding.mapView.setCompassLock(compassLock);
		});
		binding.buttonMore.setOnClickListener(this::showMenu);
		binding.addButton.setOnClickListener((v) -> {
			Locale preferredLocale = MyApplication.getMyApplication(this).getPreferredLocale();
			new NodeAddingDialogBox(MapActivity.this, preferredLocale).show();
		});
		
		binding.filtersButton.setOnClickListener((v) -> showFilterDialog());
		// The toolbar search button opens the address search (what used to sit behind More > "Move to
		// address"). Keyword filtering, which this button used to open, now lives in the filter dialog
		// as a text field alongside the category checkboxes — it is a filter in the same sense they are.
		binding.searchButton.setOnClickListener((v) -> searchAddress());
		binding.listNodesButton.setOnClickListener((v) -> {
			Intent intent = new Intent(MapActivity.this, NodeListActivity.class);
			startActivity(intent);
		});
		binding.actionButton.setVisibility(View.INVISIBLE);
	}
	
	
	private void showFilterDialog() {
		FilterDialog.showDialog(this,
				MyApplication.getMapTileManager(this).getCatIdFilters(),
				MyApplication.getMapTileManager(this).getKeyword(),
				new FilterDialog.FilterDialogListener() {
			@Override
			public void onShowSelected(Set<Integer> selectedCategories, String keyword) {
				MyApplication.getMapTileManager(MapActivity.this).setCatIdFilters(selectedCategories);
				applyKeywordFilter(keyword);
			}

			@Override
			public void onShowFavorites(String keyword) {
				MyApplication.getMapTileManager(MapActivity.this).selectAllCategories();
				MyApplication.getMapTileManager(MapActivity.this).setFavoriteFilter(true);
				applyKeywordFilter(keyword);
			}

			@Override
			public void onShowAll() {
				MyApplication.getMapTileManager(MapActivity.this).selectAllCategories();
				MyApplication.getMapTileManager(MapActivity.this).setFavoriteFilter(false);
				applyKeywordFilter(null); // "Show all" means no filters, keyword included
			}
		});
	}

	/**
	 * Applies (or clears) the keyword filter and keeps the shared action button in sync with it.
	 *
	 * <p>A keyword filter is invisible on a map explored by touch and sound — nothing about the
	 * silence where a filtered-out point used to be says "a filter is hiding this". So an active
	 * keyword always arms the same cancel button that aiming uses, giving the user both an
	 * announcement that the filter is on and a one-tap (or Back) way out of it.
	 *
	 * @param keyword the keyword to filter by, or null to clear the filter.
	 */
	private void applyKeywordFilter(String keyword) {
		if (keyword == null) {
			// Only tear down the button if a keyword filter is what's currently using it — an active
			// aim shares the same button, and must not be cancelled by an unrelated filter change.
			if (MyApplication.getMapTileManager(this).getKeyword() != null) {
				cancelSearchOnMap();
			}
			return;
		}
		// Aiming and a keyword filter are mutually exclusive — they share this screen's one action
		// button, so starting one cancels the other rather than leaving a stale aim active with no
		// visible way to cancel it.
		MyApplication.getMapTileManager(this).setAimedElement(null);
		MyApplication.getMapTileManager(this).setKeywordSearch(keyword);
		showSearchCancelButton(keyword);
	}
	
	private void searchAddress() {
		AddressSearch.OnAddressSelectedListener listener = new AddressSearch.OnAddressSelectedListener() {
			@Override
			public void onAddressSelected(Location location) {
				binding.mapView.setCenterLocation(location, false);
				if (!MyApplication.getMapTileManager(MapActivity.this).isTileImported(location)) {
					MyApplication.getAnnouncementManager(MapActivity.this).onUndownloadedAreaTouched(location);
				}
			}
			
			@Override
			public void onError(String message) {
				Toast.makeText(MapActivity.this, getString(R.string.address_search_error_toast, message), Toast.LENGTH_SHORT).show();
			}

			@Override
			public void onDialogDismissed() {
				Toast.makeText(MapActivity.this, R.string.address_search_cancelled_toast, Toast.LENGTH_SHORT).show();
			}
		};
		
		Locale preferredLocale = MyApplication.getMyApplication(this).getPreferredLocale();
		AddressSearch addressSearch = new AddressSearch(this, preferredLocale, listener);
		addressSearch.show();
	}
	
	@Override
	protected void onResume() {
		super.onResume();
		updateKeepScreenOnFlag();
		updateRenderMapFlag();
		// The app's language may have been changed in Android's per-app language settings.
		MyApplication.getAudioClient(this).setSpeechLocale(MyApplication.getMyApplication(this).getSpeechLocale());
		checkRequiredPermissions();
		MyApplication.getAngleManager(this).addListener(this);
		AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
		if (am != null) {
			am.addAccessibilityStateChangeListener(accessibilityStateListener);
			am.addTouchExplorationStateChangeListener(touchExplorationListener);
		}
	}
	
	@Override
	protected void onPause() {
		super.onPause();
		if (visualCheck.isRunning()) {
			visualCheck.stop();
			updateVisualCheckButton();
		}
		MyApplication.getAngleManager(this).removeListener(this);
		AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
		if (am != null) {
			am.removeAccessibilityStateChangeListener(accessibilityStateListener);
			am.removeTouchExplorationStateChangeListener(touchExplorationListener);
		}
	}
	
	private void checkRequiredPermissions() {
		List<String> permissions = new ArrayList<>();
		if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
			permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
		}
		// Deliberately NOT gated on "some other permission is already being requested": it used to
		// carry a `&& !permissions.isEmpty()` condition, which meant that on a device where location
		// was already granted but notifications were not, POST_NOTIFICATIONS was silently never asked
		// for — leaving BackgroundService running as a foreground service whose ongoing notification
		// the system then suppresses on Android 13+. That notification is the only affordance the user
		// has to see (or stop) the announcement service, so it must not depend on which other
		// permissions happen to be outstanding.
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
				checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
			permissions.add(Manifest.permission.POST_NOTIFICATIONS);
		}
		
		if (!permissions.isEmpty()) {
			if (!permissionsRequested) {
				permissionsRequested = true;
				requestPermissionsLauncher.launch(permissions.toArray(new String[0]));
			}
		} else {
			performActionsNeedPermissions();
		}
	}
	
	private void performActionsNeedPermissions() {
		MyApplication.getUserLocationManager(this).addListener(this);
		startService();
	}
	
	@Override
	public void onLocationUpdated(Location location) {
		if (location != null) {
			binding.mapView.setUserLocation(location);
			binding.infoButton.setContentDescription(getString(R.string.info_button_text, (int) location.getAccuracy(), location.getSpeed() * 3.6));
			maybeShowStartupDownloadPrompt(location);
			visualCheck.setLocation(location);
			updateVisualCheckOffer(location);
		}
	}

	/** Every VISUAL_OFFER_CHECK_MS: is a place saved for the visual check within its radius? If so
	 * the button at the top offers the check (announced once when it appears). */
	private void updateVisualCheckOffer(Location location) {
		long now = android.os.SystemClock.elapsedRealtime();
		if (now - lastVisualOfferCheck < VISUAL_OFFER_CHECK_MS) return;
		lastVisualOfferCheck = now;
		new Thread(() -> {
			List<VisualPlacesDb.Place> near = VisualPlacesDb.get(this)
					.placesNear(location.getLatitude(), location.getLongitude(), VisualCheckController.RADIUS_M);
			String name = near.isEmpty() || !VprModel.isAvailable(this) ? null : near.get(0).name;
			runOnUiThread(() -> {
				if (isFinishing() || isDestroyed()) return;
				boolean appeared = name != null && !name.equals(visualOfferPlace);
				visualOfferPlace = name;
				updateVisualCheckButton();
				if (appeared && !visualCheck.isRunning()) {
					binding.visualCheckButton.announceForAccessibility(binding.visualCheckButton.getText());
				}
			});
		}).start();
	}

	private void updateVisualCheckButton() {
		binding.visualCheckCameraButton.setVisibility(visualCheck.isRunning() ? View.VISIBLE : View.GONE);
		binding.visualCheckCameraButton.setText(visualCheck.isFrontCamera()
				? R.string.action_use_back_camera : R.string.action_use_front_camera);
		if (visualCheck.isRunning()) {
			binding.visualCheckButton.setText(R.string.action_stop_visual_check);
			binding.visualCheckButton.setVisibility(View.VISIBLE);
		} else if (visualOfferPlace != null) {
			binding.visualCheckButton.setText(getString(R.string.action_start_visual_check, visualOfferPlace));
			binding.visualCheckButton.setVisibility(View.VISIBLE);
		} else {
			binding.visualCheckButton.setVisibility(View.GONE);
		}
	}

	private void toggleVisualCheck() {
		if (visualCheck.isRunning()) {
			visualCheck.stop();
			MyApplication.getAudioClient(this).speak(getString(R.string.visual_check_stopped));
			updateVisualCheckButton();
		} else if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
			startVisualCheck();
		} else {
			cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
		}
	}

	private void startVisualCheck() {
		visualCheck.start();
		updateVisualCheckButton();
	}

	@Override
	public void onVisualCheckStopped(String reason) {
		MyApplication.getAudioClient(this).speak(reason);
		updateVisualCheckButton();
	}

	/** Checked once per app launch, off the very first GPS fix that arrives — not on every location
	 * update afterward, and deliberately separate from MapView.handleTab's own "undownloaded area
	 * touched" announcement (which reacts to touching/scrolling the map, not to where the user
	 * actually is). If the user's real starting location has no downloaded map at all, offers a
	 * shortcut straight to the download screen instead of leaving them to discover an empty map by
	 * exploring it. */
	private void maybeShowStartupDownloadPrompt(Location location) {
		if (startupDownloadPromptChecked) return;
		startupDownloadPromptChecked = true;
		if (talkBackNoticeShowing) {
			// Deferred to showTalkBackRequiredNoticeIfFirstRun's dismiss listener instead — see its
			// comment for why this avoids stacking two AlertDialogs.
			pendingStartupDownloadCheckLocation = location;
			return;
		}
		checkAndShowStartupDownloadPrompt(location);
	}

	private void checkAndShowStartupDownloadPrompt(Location location) {
		new Thread(() -> {
			boolean imported = MyApplication.getMapTileManager(this).isTileImported(location);
			if (imported) return;
			runOnUiThread(() -> {
			// The db check above runs off the main thread, so this Activity can be gone by the time
			// the result lands — show()ing on a destroyed window throws BadTokenException.
			if (isFinishing() || isDestroyed()) return;
			new AlertDialog.Builder(this)
					.setTitle(R.string.startup_download_prompt_title)
					.setMessage(R.string.startup_download_prompt_message)
					.setCancelable(true)
					.setPositiveButton(R.string.startup_download_prompt_download, (dialog, which) -> {
						Intent intent = new Intent(MapActivity.this, DownloadMapActivity.class);
						intent.putExtra(DownloadMapActivity.EXTRA_TARGET_LAT, location.getLatitude());
						intent.putExtra(DownloadMapActivity.EXTRA_TARGET_LON, location.getLongitude());
						startActivity(intent);
					})
					.setNegativeButton(android.R.string.cancel, null)
					.show();
			});
		}, "startup-download-check").start();
	}
	
	private void showNodeActionsDialog() {
		final MapElement element = MyApplication.getAnnouncementManager(this).getSelectedElement();
		if (element == null) return;

		Map<String, Runnable> actions = new LinkedHashMap<>();
		if (element.catId != OSMDatabaseHelper.CATEGORY_WAY) {
			actions.put(getString(R.string.action_aim_at_point), () -> aimAtPoint(element));
			actions.put(getString(R.string.action_navigate_google_maps), () -> navigateToLocation(element.lat, element.lon));
			actions.put(getString(R.string.action_zoom_to_point), () -> {
				binding.mapView.setCenterLocation(element.getLocation(), false);
				binding.mapView.zoomIn();
			});
		}

		actions.put(getString(element.isFavorite() ? R.string.action_remove_from_favorites : R.string.action_add_to_favorites), () -> toggleElementFavorite(element));

		// Any node (manually-added or a real downloaded OSM point) can be renamed — never ways, and
		// never a world-place gazetteer stand-in (OSMDatabaseHelper's node writes only ever touch the
		// local nodes table, so editing a world-place point — which lives in the attached read-only
		// world_places.db instead — would silently do nothing). Deleting from inside that dialog is
		// still limited to manually-added points — see NodeEditDialogBox.
		if (element.catId != OSMDatabaseHelper.CATEGORY_WAY && !OSMDatabaseHelper.isWorldPlaceCategory(element.catId)) {
			actions.put(getString(R.string.action_edit_node), () -> showEditNodeDialog(element));
		}

		final String detailsLabel = getString(R.string.action_details);
		actions.put(detailsLabel, () -> ElementDetailsDialog.show(this, element));

		ActionsDialog.show(this, element.getFormattedName(this, true), actions, detailsLabel, element.hasDisplayableTags());
	}


	private void toggleElementFavorite(MapElement element) {
		new Thread(() -> {
			boolean status = element.isFavorite();
			OSMDatabaseHelper.setFavorite(element.id, !status, element.getLocation());
			// refresh the loaded-data cache so the favorite state is reflected
			MyApplication.getMapTileManager(this).reloadCurrentArea();
		}).start();
	}
	
	/** Shows the shared action button offering to download the map for an unimported area. */
	private void showDownloadAreaAction(Location location) {
		binding.actionButton.setVisibility(View.VISIBLE);
		binding.actionButton.setContentDescription(getString(R.string.download_area_action));
		binding.actionButton.setOnClickListener((v) -> {
			Intent intent = new Intent(MapActivity.this, DownloadMapActivity.class);
			intent.putExtra(DownloadMapActivity.EXTRA_TARGET_LAT, location.getLatitude());
			intent.putExtra(DownloadMapActivity.EXTRA_TARGET_LON, location.getLongitude());
			startActivity(intent);
			v.setVisibility(View.INVISIBLE);
		});
		setFocus(binding.actionButton);
	}

	private void aimAtPoint(MapElement element) {
		// Aiming and an on-map search filter are mutually exclusive — see onShowOnMap's comment.
		MyApplication.getMapTileManager(this).setKeywordSearch(null);
		MyApplication.getMapTileManager(this).setAimedElement(element);
		showAimCancelButton(element);
		Toast.makeText(this, getString(R.string.aiming_started, element.getFormattedName(this, true)), Toast.LENGTH_SHORT).show();
		setFocus(binding.actionButton);
	}

	/** Wires the shared action button to show/cancel aiming at {@code element} — split out of
	 * {@link #aimAtPoint} so {@link #onStart()} can also call it when aiming was started from
	 * {@code NodeListActivity} instead (see {@code MapTileManager#consumePendingAimUiRefresh}),
	 * without re-showing the "aiming started" toast a second time. Also arms {@link #modeBackCallback}
	 * so the system Back button cancels aiming instead of its normal action, same as tapping the
	 * button itself. */
	private void showAimCancelButton(MapElement element) {
		binding.actionButton.setVisibility(View.VISIBLE);
		binding.actionButton.setContentDescription(getString(R.string.cancel_aiming, element.getFormattedName(this, true)));
		binding.actionButton.setOnClickListener((v) -> cancelAiming());
		modeBackCallback.setEnabled(true);
	}

	private void cancelAiming() {
		MyApplication.getMapTileManager(this).setAimedElement(null);
		Toast.makeText(this, getString(R.string.aiming_canceled), Toast.LENGTH_SHORT).show();
		binding.actionButton.setVisibility(View.INVISIBLE);
		modeBackCallback.setEnabled(false);
	}

	/** Wires the shared action button to cancel the on-map keyword search filter started by
	 * the filter dialog's keyword field — same pattern as {@link #showAimCancelButton}, including arming
	 * {@link #modeBackCallback} so Back cancels it too. */
	private void showSearchCancelButton(String query) {
		binding.actionButton.setVisibility(View.VISIBLE);
		binding.actionButton.setContentDescription(getString(R.string.cancel_search, query));
		binding.actionButton.setOnClickListener((v) -> cancelSearchOnMap());
		Toast.makeText(this, getString(R.string.search_on_map_started, query), Toast.LENGTH_SHORT).show();
		modeBackCallback.setEnabled(true);
		setFocus(binding.actionButton);
	}

	private void cancelSearchOnMap() {
		MyApplication.getMapTileManager(this).setKeywordSearch(null);
		Toast.makeText(this, R.string.search_canceled, Toast.LENGTH_SHORT).show();
		binding.actionButton.setVisibility(View.INVISIBLE);
		modeBackCallback.setEnabled(false);
	}
	
	private void showEditNodeDialog(MapElement element) {
		Locale preferredLocale = MyApplication.getMyApplication(this).getPreferredLocale();
		NodeEditDialogBox.show(this, preferredLocale, element, new NodeEditDialogBox.Listener() {
			@Override
			public void onSaved(MapElement element, String newName) {
				// refresh the loaded-data cache so the new name is reflected
				MyApplication.getMapTileManager(MapActivity.this).reloadCurrentArea();
			}

			@Override
			public void onDeleted(MapElement element) {
				// refresh the loaded-data cache so the removed point disappears
				MyApplication.getMapTileManager(MapActivity.this).reloadCurrentArea();
			}
		});
	}
	
	
	private void navigateToLocation(double latitude, double longitude) {
		String uri = String.format(
				Locale.US,
				"google.navigation:q=%f,%f&mode=w",
				latitude,
				longitude
		);
		Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
		intent.setPackage("com.google.android.apps.maps");
		if (intent.resolveActivity(getPackageManager()) != null) {
			startActivity(intent);
		} else {
			String browserUri = String.format(
					Locale.US,
					"https://www.google.com/maps/dir/?api=1&destination=%f,%f&travelmode=walking",
					latitude,
					longitude
			);
			startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(browserUri)));
		}
	}
	
	/**
	 * A standard anchored {@link PopupMenu} instead of the custom AlertDialog+GridLayout this used to
	 * be — the GridLayout's fixed 3-column sizing could size the dialog window narrower than its own
	 * content on some screen/font-scale configurations, clipping the last button (found by
	 * {@code FullAppFeaturesSmokeTest}: the "Settings" button rendered with less than 90% of its area
	 * inside the dialog's own window). PopupMenu lays out its items as a plain list, so there's no
	 * multi-column width to get wrong, and it's the platform-standard pattern for an overflow menu.
	 */
	private void showMenu(View view) {
		PopupMenu popupMenu = new PopupMenu(this, view);
		popupMenu.inflate(R.menu.menu_more_actions);
		popupMenu.setOnMenuItemClickListener(item -> {
			int id = item.getItemId();
			if (id == R.id.menu_download_map) {
				openDownloadMapActivity();
				return true;
			} else if (id == R.id.menu_visual_places) {
				startActivity(new Intent(MapActivity.this, ManageVisualPlacesActivity.class));
				return true;
			} else if (id == R.id.menu_settings) {
				openSettings();
				return true;
			} else if (id == R.id.menu_credits) {
				startActivity(new Intent(MapActivity.this, CreditsActivity.class));
				return true;
			} else if (id == R.id.menu_share) {
				SharePromptDialog.showNow(MapActivity.this);
				return true;
			} else if (id == R.id.menu_about) {
				startActivity(new Intent(MapActivity.this, AboutActivity.class));
				return true;
			}
			return false;
		});
		popupMenu.show();
	}

	private void openDownloadMapActivity() {
		Intent intent = new Intent(MapActivity.this, DownloadMapActivity.class);
		// The map center, not the device's GPS position — the user may be panned to look at a
		// completely different place than where they physically are (e.g. browsing a city on the
		// other side of the world), and both the region-picker default and the radius tab should
		// target whatever's currently on screen.
		Location center = binding.mapView.getCenterLocation();
		if (center != null) {
			intent.putExtra(DownloadMapActivity.EXTRA_TARGET_LAT, center.getLatitude());
			intent.putExtra(DownloadMapActivity.EXTRA_TARGET_LON, center.getLongitude());
		}
		startActivity(intent);
	}
	
	private void openSettings() {
		Intent intent = new Intent(this, SettingsActivity.class);
		startActivity(intent);
	}
	
	@Override
	public void onAverageAnglesComputed(float avgAzimuth, float avgPitch, float avgRoll) {
		binding.mapView.setCompassRotation(avgAzimuth, avgPitch);
	}

	// ---- ImportState.Listener: this activity owns the download progress dialog (see
	// ImportProgressDialog) for the whole run — DownloadMapActivity only ever kicks the import off
	// and finishes immediately, it never shows progress itself. Both callbacks below MAY ARRIVE ON
	// A BACKGROUND THREAD (ImportService's "box-import" worker calls straight through BoxImporter
	// -> ImportState -> here with no thread hop of its own — confirmed live: an earlier version of
	// this code built the AlertDialog directly in onImportProgress and crashed instantly with
	// "Can't create handler inside thread that has not called Looper.prepare()"), so every UI touch
	// here is wrapped in runOnUiThread. ----

	private void showImportProgressDialogIfNeeded() {
		if (importProgressDialog != null && importProgressDialog.isShowing()) return;
		importProgressDialog = ImportProgressDialog.show(this, this::onImportCancelConfirmed);
	}

	private void onImportCancelConfirmed() {
		Intent intent = new Intent(this, ImportService.class);
		intent.setAction(ImportService.ACTION_CANCEL);
		startService(intent);
		// Not dismissed here — onImportFinished (triggered by the service's own cancellation path,
		// via ImportState.finish()) is what actually closes the dialog, once the run has genuinely
		// stopped rather than the instant the user confirmed. But that can take an unbounded amount
		// of time (see BoxImporter's cancelled checks — a single SQL statement mid-merge can't
		// actually be interrupted, only skipped once it returns), so give it a bounded grace period:
		// if onImportFinished still hasn't arrived by then, stop waiting on the user's behalf and
		// close the dialog anyway. The service keeps running/cleaning up in the background regardless
		// (whatever it eventually does still lands via onImportFinished's version bump / reloadCurrentArea
		// next time this activity is started, same as any other background completion).
		if (cancelTimeoutRunnable != null) cancelTimeoutHandler.removeCallbacks(cancelTimeoutRunnable);
		cancelTimeoutRunnable = () -> {
			cancelTimeoutRunnable = null;
			if (importProgressDialog != null) {
				importProgressDialog.dismiss();
				importProgressDialog = null;
			}
			Toast.makeText(this, R.string.import_cancel_still_running, Toast.LENGTH_LONG).show();
		};
		cancelTimeoutHandler.postDelayed(cancelTimeoutRunnable, CANCEL_FORCE_CLOSE_DELAY_MS);
	}

	@Override
	public void onImportProgress(String phase, String stage, int percent) {
		runOnUiThread(() -> {
			showImportProgressDialogIfNeeded();
			importProgressDialog.updateProgress(stage, percent);
		});
	}

	@Override
	public void onImportFinished(boolean success, String message) {
		lastSeenImportVersion = ImportState.get().getCompletedImportVersion();
		runOnUiThread(() -> {
			// The run genuinely finished on its own — the force-close timeout from
			// onImportCancelConfirmed (if one was pending) no longer needs to fire.
			if (cancelTimeoutRunnable != null) {
				cancelTimeoutHandler.removeCallbacks(cancelTimeoutRunnable);
				cancelTimeoutRunnable = null;
			}
			if (importProgressDialog != null) {
				importProgressDialog.dismiss();
				importProgressDialog = null;
			}
			Toast.makeText(this, message, Toast.LENGTH_LONG).show();
			// Always reload, success, failure, OR cancel: BoxImporter merges and marks each box
			// imported as soon as ITS OWN download+merge finishes (see markBoxImported), not only
			// at the very end of the whole run — so a cancelled or partially-failed multi-box
			// download can still have left genuinely new data behind, and the map should show it
			// rather than keep displaying stale (empty/world-place) data for whatever finished.
			MyApplication.getMapTileManager(this).reloadCurrentArea();
		});
	}

	public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus);
		if (hasFocus) {
			MyApplication.getAnnouncementManager(this).resume();
		}
	}
	
	private void setFocus(View view) {
		AccessibilityManager accessibilityManager = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
		if (accessibilityManager.isEnabled()) {
			view.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED);
			view.requestFocus();
		}
	}
	
	@Override
	public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
		if ("keep_screen_on".equals(key)) {
			updateKeepScreenOnFlag();
		} else if ("render_map_mode".equals(key) || "render_labels".equals(key)) {
			updateRenderMapFlag();
		}
	}

	/** Re-evaluates the "Automatic" map-drawing mode when TalkBack (or any accessibility service) is
	 * turned on or off while the screen is showing. */
	private final AccessibilityManager.AccessibilityStateChangeListener accessibilityStateListener =
			enabled -> onAccessibilityChanged();
	private final AccessibilityManager.TouchExplorationStateChangeListener touchExplorationListener =
			enabled -> onAccessibilityChanged();

	/** The service list can lag the state callback when TalkBack is switched on, so look again shortly after. */
	private void onAccessibilityChanged() {
		updateRenderMapFlag();
		binding.mapView.postDelayed(this::updateRenderMapFlag, 1500);
	}

	private static boolean isTalkBackOn(Context context) {
		AccessibilityManager am = (AccessibilityManager) context.getSystemService(ACCESSIBILITY_SERVICE);
		if (am == null) return false;
		List<AccessibilityServiceInfo> services =
				am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN);
		return services != null && !services.isEmpty();
	}

	/** "auto" (default): draw the map unless TalkBack is on; "on"/"off" force it. The old boolean
	 * "render_map" switch is migrated once: users who had turned it on keep it on. */
	private void updateRenderMapFlag() {
		if (!sharedPreferences.contains("render_map_mode")) {
			Object old = sharedPreferences.getAll().get("render_map");
			if (Boolean.TRUE.equals(old)) {
				sharedPreferences.edit().putString("render_map_mode", "on").apply();
			}
		}
		String mode = sharedPreferences.getString("render_map_mode", "auto");
		boolean render = "on".equals(mode) || ("auto".equals(mode) && !isTalkBackOn(this));
		binding.mapView.setLabelsEnabled(sharedPreferences.getBoolean("render_labels", true));
		binding.mapView.setRenderEnabled(render);
	}
	
	private void updateKeepScreenOnFlag() {
		boolean keepScreenOn = sharedPreferences.getBoolean("keep_screen_on", true);
		runOnUiThread(() -> {
			if (keepScreenOn) {
				getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
			} else {
				getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
			}
		});
	}
}