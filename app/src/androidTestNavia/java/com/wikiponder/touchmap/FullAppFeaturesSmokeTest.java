package com.wikiponder.touchmap;

import static androidx.test.espresso.Espresso.onData;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.typeText;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.anything;
import static org.hamcrest.Matchers.instanceOf;

import android.os.SystemClock;
import android.widget.ListView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.Espresso;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.wikiponder.touchmap.support.TalkBackTestSupport;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Broad Firebase Test Lab smoke test covering most of MapActivity's top-level UI with real system
 * TalkBack on (see {@link TalkBackTestSupport}): zoom in/out/recenter/map-lock buttons, the info
 * button, the filters dialog (including its keyword filter), actually saving a manually-added
 * point, moving the map to Tokyo, Japan via the toolbar's address search, and the nearby-nodes list.
 * Deliberately does not pan/scroll the map — see {@link TalkBackNearbyNodesSmokeTest} for that,
 * kept separate since a swipe under real TalkBack can get reinterpreted by the system's touch-
 * exploration layer into landing on a map element instead of panning, which is real/expected
 * behavior but adds noise to a script this one doesn't need for its own coverage goals.
 *
 * <p>Like {@link TalkBackNearbyNodesSmokeTest}, this is a robustness/crash smoke test, not a
 * correctness test: nearly every step runs through {@link TalkBackTestSupport#step} so one screen
 * behaving unexpectedly (a dialog not appearing, a network-dependent flow not finishing) on a given
 * device doesn't abort the rest of the sequence — the signal that matters is whether Firebase Test
 * Lab's own crash/ANR monitor stays clean for the whole run. Not exhaustive by design: it
 * deliberately doesn't click into every individual Settings preference — see the "Robo test"
 * alternative for open-ended crawling of screens this script doesn't visit.
 */
@RunWith(AndroidJUnit4.class)
public class FullAppFeaturesSmokeTest {

	private static final String TAG = "FullAppSmokeTest";

	@Test
	public void exerciseAllMainScreensWithTalkBackOn() {
		TalkBackTestSupport.grantCorePermissions();
		TalkBackTestSupport.suppressFirstRunNotices();
		TalkBackTestSupport.enableTalkBack();
		SystemClock.sleep(3000); // let the service actually spin up

		ActivityScenario<MapActivity> scenario = ActivityScenario.launch(MapActivity.class);
		try {
			SystemClock.sleep(3000); // let the map view lay out and attempt a first location fix

			testZoomRecenterAndLockButtons();
			testInfoButton();
			testFiltersDialog();
			testAddPointDialog();
			testMoreMenu();
			testNearbyNodesList();
		} finally {
			TalkBackTestSupport.disableTalkBack();
			scenario.close();
		}
	}

	private void testZoomRecenterAndLockButtons() {
		TalkBackTestSupport.step(TAG, "zoom out (MapView.MIN_ZOOM = 1/8, halved per tap from 1.0 -> 3 taps to reach it)", () -> {
			for (int i = 0; i < 3; i++) {
				onView(withId(R.id.zoom_out_button)).perform(click());
				SystemClock.sleep(500);
			}
		});
		TalkBackTestSupport.step(TAG, "zoom in", () -> onView(withId(R.id.zoom_in_button)).perform(click()));
		TalkBackTestSupport.step(TAG, "recenter", () -> onView(withId(R.id.recenter_button)).perform(click()));
		TalkBackTestSupport.step(TAG, "toggle map lock button (compass/north lock)", () -> {
			onView(withId(R.id.map_lock_button)).perform(click());
			SystemClock.sleep(500);
			onView(withId(R.id.map_lock_button)).perform(click()); // toggle back
		});
	}

	private void testInfoButton() {
		TalkBackTestSupport.step(TAG, "info button (accuracy/speed announcement)", () ->
				onView(withId(R.id.info_button)).perform(click()));
	}

	private void testFiltersDialog() {
		TalkBackTestSupport.step(TAG, "filters dialog -> keyword \"bus stop\" -> Show Selected -> cancel via Back", () -> {
			onView(withId(R.id.filters_button)).perform(click());
			SystemClock.sleep(500);
			// Keyword filtering lives in this dialog now (it replaced the old standalone search
			// dialog); the field is the inner EditText of a VoiceInputEditText, same as elsewhere.
			onView(withId(R.id.editText)).perform(typeText("bus stop"), closeSoftKeyboard());
			onView(withText("Show Selected")).perform(click());
			SystemClock.sleep(1000);
			// With a keyword filter active, Back should cancel it (MapActivity's modeBackCallback)
			// rather than falling through to BaseActivity's double-back-to-exit.
			Espresso.pressBack();
		});
		TalkBackTestSupport.step(TAG, "filters dialog -> Show All", () -> {
			onView(withId(R.id.filters_button)).perform(click());
			SystemClock.sleep(500);
			onView(withText("Show All")).perform(click());
		});
	}

	private void testAddPointDialog() {
		TalkBackTestSupport.step(TAG, "add point dialog -> enter name -> Save node", () -> {
			onView(withId(R.id.add_button)).perform(click());
			SystemClock.sleep(1000);
			// The inner EditText inside VoiceInputEditText — see dialog_node_adding.xml / view_voice_input_edit_text.xml.
			onView(withId(R.id.editText)).perform(typeText("Test Point"), closeSoftKeyboard());
			// Give the dialog's own location listener a chance to receive a GPS fix before tapping
			// Save — NodeAddingDialogBox shows a "still waiting for a location" toast instead of
			// crashing if none has arrived yet (see the guard added alongside this test), but a real
			// fix lets this step actually exercise the full insert + cache-reload path.
			SystemClock.sleep(3000);
			onView(withText("Save node")).perform(click());
			SystemClock.sleep(2000);
		});
	}

	private void testMoreMenu() {
		TalkBackTestSupport.step(TAG, "search button -> \"Tokyo, Japan\" -> Search -> pick first result", () -> {
			// Address search moved from More > "Move to address" onto the toolbar's search button.
			onView(withId(R.id.search_button)).perform(click());
			SystemClock.sleep(500);
			onView(withId(R.id.editText)).perform(typeText("Tokyo, Japan"), closeSoftKeyboard());
			// By button id, not withText("Search"): the dialog's title is now "Search" too, so
			// matching on the text alone is ambiguous and throws AmbiguousViewMatcherException.
			onView(withId(android.R.id.button1)).perform(click());
			// Best-effort wait for the network Geocoder lookup, then pick the first matching address
			// from the results list (AddressSearch.showResultsDialog) so the map actually moves to
			// Tokyo — without this, that list dialog is left open, blocking every step after it. Not
			// guaranteed to resolve on every Test Lab device/network, so the whole step stays
			// best-effort regardless.
			SystemClock.sleep(6000);
			onData(anything()).inAdapterView(instanceOf(ListView.class)).atPosition(0).perform(click());
			SystemClock.sleep(2000);
		});
		TalkBackTestSupport.step(TAG, "more menu -> Settings -> back", () -> {
			onView(withId(R.id.buttonMore)).perform(click());
			SystemClock.sleep(500);
			onView(withText("Settings")).perform(click());
			SystemClock.sleep(2000);
			Espresso.pressBack();
		});
		TalkBackTestSupport.step(TAG, "more menu -> Download map -> back (best-effort, network/GPS dependent)", () -> {
			onView(withId(R.id.buttonMore)).perform(click());
			SystemClock.sleep(500);
			onView(withText("Download map")).perform(click());
			SystemClock.sleep(3000);
			Espresso.pressBack();
		});
	}

	private void testNearbyNodesList() {
		TalkBackTestSupport.step(TAG, "nearby nodes list -> back", () -> {
			onView(withId(R.id.list_nodes_button)).perform(click());
			SystemClock.sleep(5000);
			Espresso.pressBack();
		});
	}
}
