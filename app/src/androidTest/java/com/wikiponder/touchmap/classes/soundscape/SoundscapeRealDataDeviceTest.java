package com.wikiponder.touchmap.classes.soundscape;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Builds scenes from the real map db at Amsterdam spots. Only meaningful on a device whose osm.db
 * holds the Amsterdam box (box_001929); skipped elsewhere.
 */
@RunWith(AndroidJUnit4.class)
public class SoundscapeRealDataDeviceTest {
	private static final String TAG = "SoundscapeRealData";

	private static Scene scene(double lat, double lon) {
		Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
		long t0 = System.nanoTime();
		Scene s = SoundscapeData.buildScene(ctx, lat, lon);
		Log.i(TAG, String.format("(%f,%f) built in %d ms: %s", lat, lon, (System.nanoTime() - t0) / 1_000_000, s));
		return s;
	}

	private static boolean hasLane(Scene s, String label) {
		for (Scene.Lane l : s.lanes) if (l.label.equals(label)) return true;
		return false;
	}

	@Test
	public void damrakHasTramsAndTraffic() {
		Scene s = scene(52.3745, 4.8936);
		Assume.assumeFalse("no Amsterdam data on this device", s.isEmpty());
		assertTrue("tram on Damrak", hasLane(s, "tram"));
		assertFalse(s.lanes.isEmpty());
	}

	@Test
	public void vondelparkIsGreen() {
		Scene s = scene(52.3580, 4.8686);
		Assume.assumeFalse("no Amsterdam data on this device", s.isEmpty());
		boolean birds = false;
		for (Scene.Spot sp : s.spots) birds |= sp.clip.equals("birds");
		assertTrue("birds in the park", birds);
	}

	@Test
	public void centraalStationHasTrains() {
		scene(52.3791, 4.9003);
	}

	@Test
	public void ferryBehindCentraalHasHarbour() {
		Scene s = scene(52.3790, 4.9050);
		Assume.assumeFalse("no Amsterdam data on this device", s.isEmpty());
		boolean harbour = false;
		for (Scene.Spot sp : s.spots) harbour |= sp.clip.equals("harbor") || sp.clip.equals("ship_horn");
		assertTrue("harbour sounds at the IJ ferries", harbour);
	}

	@Test
	public void airportTableKnowsThePlaceAndItsCountry() {
		Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
		Airports.Airport schiphol = Airports.nearest(ctx, 52.3350, 4.7880); // Badhoevedorp
		assertEquals("NL", schiphol.country);
		assertEquals(52.31, schiphol.lat, 0.02);
		assertEquals(4.76, schiphol.lon, 0.03);
		assertEquals("IR", Airports.nearest(ctx, 35.70, 51.40).country);
	}

	@Test
	public void canalBridgeAndSuburbsAreLogged() {
		scene(52.3700, 4.8840); // Prinsengracht
		scene(52.3420, 4.9580); // Amsterdam-Oost residential
		scene(52.2950, 4.9600); // outskirts
	}
}
