package com.wikiponder.touchmap.classes;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.accessibility.AccessibilityManager;

import androidx.preference.PreferenceManager;
import com.wikiponder.touchmap.MyApplication;
import com.wikiponder.touchmap.R;
import com.wikiponder.touchmap.classes.db.OSMDatabaseHelper;
import com.wikiponder.touchmap.classes.models.MapElement;
import com.wikiponder.touchmap.classes.models.NearestElement;
import com.wikiponder.touchmap.classes.ui.MapView;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AnnouncementManager implements MapView.UpdateListener {
	private static final String TAG = "AnnouncementManager";
	private final AudioClient audioClient;
	private final Context context;
	
	// --- Timers and Thresholds for intelligent announcements ---
	private static final long MIN_ANNOUNCEMENT_INTERVAL = 15000; // 15 seconds before re-announcing the same element
	private static final double MIN_DISTANCE_CHANGE = 5; // 10 meters closer to re-announce sooner
	private static final long GLOBAL_ANNOUNCEMENT_COOLDOWN = 8000; // 8 seconds between any two announcements
	private static final int RECENTLY_ANNOUNCED_CACHE_SIZE = 1; // Remember the last 3 announced elements
	
	private long lastId;
	private long lastTouchTime = 0;
	private long lastAnnounceTime = 0;
	private boolean isTouching = false;
	private MapElement lastElement = null;
	private String lastMapMessage = "";
	private long lastMapMessageTime = 0;
	private boolean isPaused;
	/** Set while the visual place check (VisualCheckController) or a simulated soundscape runs: the
	 * automatic, location- and aiming-driven announcements stay quiet so they don't talk over it.
	 * Held per owner so one of them ending doesn't lift the other's suppression. Separate from
	 * isPaused, which window focus and activity starts keep resetting. Touching the map still speaks. */
	private final Set<Object> suppressors = new HashSet<>();
	private volatile boolean automaticSuppressed;
	private int lastAnnouncedAimingClock;
	private float screenWidthMeters;
	
	// --- New variables for improved logic ---
	private long lastGlobalAnnouncementTime = 0;
	private final Map<Long, AnnouncementInfo> announcementInfoMap = new HashMap<>();
	private final Deque<Long> recentlyAnnouncedIds = new ArrayDeque<>(RECENTLY_ANNOUNCED_CACHE_SIZE + 1);

	/** Notified (in addition to the spoken announcement) when the user touches an unimported area,
	 * so the UI (MapActivity) can offer a "download this area" action. */
	public interface DownloadOfferListener {
		void onOfferAreaDownload(Location location);
	}
	private DownloadOfferListener downloadOfferListener;

	public void setDownloadOfferListener(DownloadOfferListener listener) {
		this.downloadOfferListener = listener;
	}
	
	// Helper class to store info about past announcements
	private static class AnnouncementInfo {
		long timestamp;
		double distance;
		
		AnnouncementInfo(long timestamp, double distance) {
			this.timestamp = timestamp;
			this.distance = distance;
		}
	}
	
	
	public AnnouncementManager(Context context) {
		this.context = context;
		
		this.isPaused = false;
		this.audioClient = MyApplication.getAudioClient(context);
	}
	
	@Override
	public void onElementSelected(MapElement element, Location userLocation, double mapRotation, double  distance) {
		if (isPaused)
			return;
		if (element == null)
			return;
		if (element.catId == OSMDatabaseHelper.CATEGORY_WAY)
			announceWay(element, userLocation, mapRotation, distance);
		else
			announceNode(element, userLocation, mapRotation);
	}
	
	public void announceNearestElement(Location userLocation, List<NearestElement> nearestElements, Double bearing) {
		Log.d(TAG, userLocation.toString());
		if (isPaused || automaticSuppressed || nearestElements == null || nearestElements.isEmpty()) {
			return;
		}
		
		long currentTime = System.currentTimeMillis();
		
		if (currentTime - lastGlobalAnnouncementTime < GLOBAL_ANNOUNCEMENT_COOLDOWN) {
			return;
		}
		
		List<NearestElement> elementsToConsider = nearestElements;
		
		if (bearing != null) {
			List<NearestElement> forwardElements = new ArrayList<>();
			for (NearestElement candidate : nearestElements) {
				double elementBearing = userLocation.bearingTo(candidate.element.getLocation());
				double relativeBearing = (elementBearing - bearing + 360) % 360;
				if (relativeBearing > 180) relativeBearing -= 360;
				
				if (Math.abs(relativeBearing) <= 90) {
					candidate.bearing = relativeBearing;
					forwardElements.add(candidate);
				}
			}
			elementsToConsider = forwardElements;
		}
		
		if (elementsToConsider.isEmpty()) {
			return;
		}
		
		NearestElement elementToAnnounce = null;
		NearestElement closestElement = elementsToConsider.get(0);
		long closestElementId = closestElement.element.id;
		AnnouncementInfo lastInfoOnClosest = announcementInfoMap.get(closestElementId);
		
		if (lastInfoOnClosest != null &&
				currentTime - lastInfoOnClosest.timestamp < MIN_ANNOUNCEMENT_INTERVAL &&
				lastInfoOnClosest.distance - closestElement.distance > MIN_DISTANCE_CHANGE) {
			elementToAnnounce = closestElement;
		} else {
			for (NearestElement candidate : elementsToConsider) {
				if (!recentlyAnnouncedIds.contains(candidate.element.id)) {
					elementToAnnounce = candidate;
					break;
				}
			}
			
			if (elementToAnnounce == null && lastInfoOnClosest != null) {
				if (currentTime - lastInfoOnClosest.timestamp > MIN_ANNOUNCEMENT_INTERVAL) {
					elementToAnnounce = closestElement;
				}
			}
			else if (elementToAnnounce == null && lastInfoOnClosest == null) {
				elementToAnnounce = closestElement;
			}
		}
		
		if (elementToAnnounce == null) {
			return;
		}
		
		boolean reportBearing = (bearing != null);
		announceNearestPoint(elementToAnnounce, reportBearing, true);
		
		long elementId = elementToAnnounce.element.id;
		announcementInfoMap.put(elementId, new AnnouncementInfo(currentTime, elementToAnnounce.distance));
		lastGlobalAnnouncementTime = currentTime;
		
		if (recentlyAnnouncedIds.contains(elementId)) {
			recentlyAnnouncedIds.remove(elementId);
		}
		recentlyAnnouncedIds.addFirst(elementId);
		if (recentlyAnnouncedIds.size() > RECENTLY_ANNOUNCED_CACHE_SIZE) {
			recentlyAnnouncedIds.removeLast();
		}
	}
	
	private void announceNode(MapElement element, Location userLocation, double mapRotation) {
		if (element.id == lastId)
			return;
		lastId = element.id;
		lastElement = element;

		float distance = userLocation.distanceTo(element.getLocation());

		String description;
		if (distance < 1000) {
			description = context.getString(R.string.announce_location_meters, element.getFormattedName(context, true), distance);
		}
		else {
			description = context.getString(R.string.announce_location_kilometers, element.getFormattedName(context, true), distance / 1000.0);
		}
		description = withTouchHint(description);
		audioClient.stopSpeaking();
		double bearing = userLocation.bearingTo(element.getLocation()) - mapRotation;
		double balance = Math.sin(Math.toRadians(bearing)) *50 + 50;
		double pitch = Math.min(distance/2, 99);

		// crisp tactile confirmation that a point of interest was found under the finger
		vibrate(new long[] {0, 25}, new int[] {0, 255});

		// Binaural earcon (18 pre-rendered clips, one per 20 degrees — see
		// AudioClient.playPointDirectional / tools/generate_sounds.py's build_point_dir_set):
		// placed at the point's bearing, loudness encodes distance from 100% (nearest) down to
		// 20% (most distant point on screen).
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		double prefVol = Double.parseDouble(prefs.getString("pref_sound_effects_volume", "0.5"));
		double halfWidth = (screenWidthMeters > 0) ? screenWidthMeters / 2.0 : 100.0;
		double norm = Math.min(1.0, distance / halfWidth);
		double volume = (1.0 - 0.8 * norm) * prefVol; // 0.2..1.0 by distance, scaled by user setting
		audioClient.playPointDirectional(bearing, volume);

		audioClient.speakWithLocation(description, balance, pitch, 50);

	}
	
	private void announceWay(MapElement element, Location userLocation, double mapRotation, double  distance) {
		if (System.currentTimeMillis() < lastTouchTime + 100)
			return;
		lastTouchTime = System.currentTimeMillis();
		
		if (element.id!= lastId || System.currentTimeMillis() - lastAnnounceTime > 4000) {
			if (element.id!= lastId) {
				audioClient.playSound(AudioClient.SOUND_DONE, -1, 0, 1);
			}
			lastId = element.id;
			lastElement = element;
			lastAnnounceTime = System.currentTimeMillis();
			
			String description = withTouchHint(element.getFormattedName(context, true));
			audioClient.stopSpeaking();
			audioClient.speak(description);
		}
		double volume = Math.max(1-distance/screenWidthMeters * 10, 0.5);
		int v = (int)(96*volume);
		vibrate(new long[] {0, 10, 10, 10, 10, 10, 10, 10, 10, 10, 10}, new int[] {0, v, 0, v, 0, v, 0, v, 0, v, 0});
		audioClient.playSound(AudioClient.SOUND_WAY_TOUCH, -volume, 0, 1);
	}
	
	
	/** Counts a point read by touch and, until the user has opened the point menu a few times, adds
	 * how to open it. Only with explore-by-touch on: without it a plain tap opens the menu. */
	private String withTouchHint(String description) {
		TouchHints.onPointRead(context);
		AccessibilityManager am = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
		if (am == null || !am.isTouchExplorationEnabled() || !TouchHints.needsDoubleTapHint(context))
			return description;
		// end the announcement as a sentence first, or TTS runs straight into the hint without a pause
		boolean ended = !description.isEmpty() && ".。۔!?！？".indexOf(description.charAt(description.length() - 1)) >= 0;
		return description + (ended ? " " : ". ") + context.getString(R.string.hint_double_tap_options);
	}

	/** {@code count} distinct buzzes, scaled by the vibration-intensity setting. */
	public void vibratePulses(int count) {
		long[] timings = new long[count * 2];
		int[] amplitudes = new int[count * 2];
		for (int i = 0; i < count; i++) {
			timings[2 * i] = i == 0 ? 0 : 150;
			timings[2 * i + 1] = 120;
			amplitudes[2 * i + 1] = 255;
		}
		vibrate(timings, amplitudes);
	}

	private void vibrate(int ms) {
		long[] timings = new long[] {0, ms};
		int[] amplitudes = new int[] {0, 255};
		vibrate(timings, amplitudes);
	}
	
	private void vibrate(long[] timings, int[] amplitudes) {
		Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
		if (vibrator == null || !vibrator.hasVibrator())
			return;
		
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		double  intencityFactor= Double.parseDouble(prefs.getString("pref_vibration_intensity", "0.3"));
		for (int i=0; i<amplitudes.length; i++)
			amplitudes[i] = (int) (amplitudes[i] * intencityFactor);
		
		VibrationEffect effect = VibrationEffect.createWaveform(timings, amplitudes, -1);
		vibrator.vibrate(effect);
	}
	
	@Override public void onTouchStop() {
		isTouching = false;
		lastId = -1;
	}
	
	@Override
	public void onMapMessage(String message) {
		if (isPaused)
			return;
		if (System.currentTimeMillis() < lastMapMessageTime + 5000 && lastMapMessage.equals(message))
			return;
		lastMapMessageTime = System.currentTimeMillis();
		lastMapMessage = message;
		
		audioClient.stopSpeaking();
		audioClient.speak(message);
		vibrate(50);
	}
	
	@Override
	public void onTouchStart(float screenWidthMeters) {
		isTouching = true;
		this.screenWidthMeters = screenWidthMeters;
	}

	@Override
	public void onUndownloadedAreaTouched(Location location) {
		onMapMessage(context.getString(R.string.no_map_here_message));
		if (!isPaused && downloadOfferListener != null) {
			downloadOfferListener.onOfferAreaDownload(location);
		}
	}
	
	public MapElement getSelectedElement() {
		return lastElement;
	}
	
	public void announceNearestPoint(NearestElement ne, boolean reportBearing, boolean attention) {
		if (isPaused || automaticSuppressed || isTouching)
			return;
		
		audioClient.stopSpeaking();
		
		if (attention) {
			long[] timings = {0, 40, 20, 40};
			int[] amplitudes = {0, 255, 0, 255};
			vibrate(timings, amplitudes);
		}
		
		if (reportBearing) {
			String s = context.getString(R.string.announce_point_with_bearing,
					ne.element.getFormattedName(context, true),
					(int) ne.distance,
					Utils.bearingToClockDirection(ne.bearing));
			
			double balance = Math.sin(Math.toRadians(ne.bearing)) *50 + 50;
			double pitch = Math.min(ne.distance/2, 99);
			audioClient.speakWithLocation(s, balance, pitch, 50);
		}
		else {
			String s = context.getString(R.string.announce_point_without_bearing,
					ne.element.getFormattedName(context, true),
					(int) ne.distance);
			
			audioClient.playSound(AudioClient.SOUND_DING, -0.2, 0, 1);
			audioClient.playSilence(200);
			audioClient.speak(s);
		}
	}
	
	public void pause() {
		isPaused = true;
	}
	public void resume() {
		isPaused = false;
	}

	public synchronized void setAutomaticSuppressed(Object owner, boolean suppressed) {
		if (suppressed) suppressors.add(owner);
		else suppressors.remove(owner);
		automaticSuppressed = !suppressors.isEmpty();
	}
	
	public void announceAiming(MapElement element, double distance, double bearing) {
		if (isPaused || automaticSuppressed || isTouching || element == null)
			return;
		
		int aimingClock = Utils.bearingToClockDirection(bearing);
		if (aimingClock == lastAnnouncedAimingClock)
			return;
		lastAnnouncedAimingClock = aimingClock;
		
		double balance = Math.sin(Math.toRadians(bearing)) *50 + 50;

		String s;
		if (aimingClock == 12) {
			s = context.getString(R.string.announce_aiming_distance_and_clock, (int) distance, aimingClock);
			long[] timings = {0, 300, 100, 200};
			int[] amplitudes = {0, 255, 0, 255};
			vibrate(timings, amplitudes);
			audioClient.playSound(AudioClient.SOUND_AIM_LOCK, -0.5, 0, 1, balance);
		}
		else {
			s = context.getString(R.string.announce_aiming_clock_only, aimingClock);
			vibrate(50);
			audioClient.playSound(AudioClient.SOUND_AIM_TICK, -0.4, 0, 1, balance);
		}
		audioClient.stopSpeaking();
		double pitch = Math.min(distance/2, 99);
		audioClient.speakWithLocation(s, balance, pitch, 50);
		audioClient.playSilence(2000);
	}
	
	public void announceFlatDevice(boolean isFlat) {
		if (isPaused)
			return;
		if (isFlat) {
			long[] timings = {0, 100, 50, 100, 50, 100, 50, 100};
			int[] amplitudes = {0, 255, 0, 255, 0, 255, 0, 255};
			vibrate(timings, amplitudes);
		}
		else {
			long[] timings = {0, 300, 50, 100};
			int[] amplitudes = {0, 255, 0, 255};
			vibrate(timings, amplitudes);
		}
	}
}