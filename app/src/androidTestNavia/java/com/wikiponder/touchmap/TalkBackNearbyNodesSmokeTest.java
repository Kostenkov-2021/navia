package com.wikiponder.touchmap;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.swipeLeft;
import static androidx.test.espresso.action.ViewActions.swipeRight;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import android.os.SystemClock;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.wikiponder.touchmap.support.TalkBackTestSupport;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Firebase Test Lab smoke test requested to exercise: real system TalkBack on, a map download,
 * panning the map left and right, then opening the "nearby nodes" list.
 *
 * <p>TalkBack is enabled for real (see {@link TalkBackTestSupport}), not faked from inside the app
 * — {@code MapView.isTalkBackEnabled()} reads the actual {@code AccessibilityManager} enabled-
 * services list, and that flag is what decides whether a single-finger swipe is treated as touch-
 * exploration (TalkBack off) or a pan gesture (TalkBack on) in {@code MapView.onTouchEvent}. This
 * also means the swipe steps below exercise the exact gesture class that used to leave
 * {@code AnnouncementManager.isTouching} stuck true after any pan (fixed by making
 * {@code ACTION_UP}/{@code ACTION_CANCEL} call {@code onTouchStop()}).
 *
 * <p>This is a robustness/crash smoke test, not a correctness test — every step runs through
 * {@link TalkBackTestSupport#step} so a slow/failed network download, or a swipe under real TalkBack
 * landing on a map element instead of panning (see that method's doc), doesn't abort the rest of the
 * sequence. The real signal is whether Firebase Test Lab's own crash/ANR monitor stays clean for the
 * whole run. See also {@link FullAppFeaturesSmokeTest} for a broader pass over the rest of
 * MapActivity's UI.
 */
@RunWith(AndroidJUnit4.class)
public class TalkBackNearbyNodesSmokeTest {

	private static final String TAG = "TalkBackSmokeTest";

	@Test
	public void enableTalkBackDownloadScrollAndListNearbyNodes() {
		TalkBackTestSupport.grantCorePermissions();
		TalkBackTestSupport.suppressFirstRunNotices();
		TalkBackTestSupport.enableTalkBack();
		SystemClock.sleep(3000); // let the service actually spin up

		ActivityScenario<MapActivity> scenario = ActivityScenario.launch(MapActivity.class);
		try {
			SystemClock.sleep(3000); // let the map view lay out and attempt a first location fix

			TalkBackTestSupport.step(TAG, "more menu -> Download map -> Download -> back", () -> {
				onView(withId(R.id.buttonMore)).perform(click());
				onView(withText("Download map")).perform(click());
				SystemClock.sleep(2000);
				onView(withId(R.id.downloadButton)).perform(click());
				// Best-effort wait: a real network box download can take a while and needs a resolved
				// GPS location, which isn't guaranteed on every Test Lab device — this test cares about
				// exercising the flow and catching a crash/ANR along the way, not asserting completion.
				SystemClock.sleep(20000);
			});

			// Each direction is its own step (not one step with both swipes) so a swipe reinterpreted
			// as a tap on a map element — opening its actions dialog instead of panning — doesn't leave
			// a stray dialog blocking the other direction too; see TalkBackTestSupport.step's doc.
			TalkBackTestSupport.step(TAG, "pan map left", () ->
					onView(withId(R.id.map_view)).perform(swipeLeft()));
			TalkBackTestSupport.step(TAG, "pan map right", () ->
					onView(withId(R.id.map_view)).perform(swipeRight()));

			TalkBackTestSupport.step(TAG, "nearby nodes list", () -> {
				onView(withId(R.id.list_nodes_button)).perform(click());
				SystemClock.sleep(5000);
			});
		} finally {
			TalkBackTestSupport.disableTalkBack();
			scenario.close();
		}
	}
}
