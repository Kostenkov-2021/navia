package com.wikiponder.touchmap.classes.soundscape;

import static org.junit.Assert.assertTrue;

import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Checks the two properties the soundscape depends on, on a real device with the real native
 * library: (1) HRTF rendering produces an interaural TIME difference, not just a level difference
 * — a click to the left reaches the left ear first; and (2) the listener heading turns the scene —
 * the same source heard facing the other way reaches the right ear first.
 */
@RunWith(AndroidJUnit4.class)
public class SpatialRendererDeviceTest {
	private static final String TAG = "SpatialRendererTest";
	private static final int RATE = 48000;

	@Before
	public void setUp() {
		assertTrue("native library loads", SpatialRenderer.isAvailable());
		assertTrue("loopback device opens", SpatialRenderer.init(RATE));
		assertTrue("HRTF is active", SpatialRenderer.hrtfEnabled());
	}

	@After
	public void tearDown() {
		SpatialRenderer.release();
	}

	/** Positive = left ear leads, in milliseconds; also returns left/right energy ratio in [1]. */
	private static double[] renderClick(float east, float north, float heading) {
		short[] click = new short[RATE / 5];
		click[200] = 32767;
		int buf = SpatialRenderer.createBuffer(click, RATE);
		int src = SpatialRenderer.createSource(buf, false, 1f, 100f, 0f, false);
		SpatialRenderer.setListenerHeading(heading);
		SpatialRenderer.updateSource(src, east, north, 0, 0, 0, 1f, 1f);
		// Let parameter updates settle (OpenAL Soft fades gains over the first mix) before playing.
		float[] warm = new float[2 * 1024];
		SpatialRenderer.render(warm, 1024);
		SpatialRenderer.play(src);
		int frames = RATE / 5;
		float[] out = new float[frames * 2];
		SpatialRenderer.render(out, frames);
		SpatialRenderer.deleteSource(src);
		SpatialRenderer.deleteBuffer(buf);

		float[] l = new float[frames], r = new float[frames];
		double el = 0, er = 0;
		for (int i = 0; i < frames; i++) {
			l[i] = out[2 * i];
			r[i] = out[2 * i + 1];
			el += l[i] * l[i];
			er += r[i] * r[i];
		}
		// Cross-correlation: the lag at which right best matches left, within +-2 ms.
		int maxLag = RATE / 500;
		int bestLag = 0;
		double best = -Double.MAX_VALUE;
		for (int lag = -maxLag; lag <= maxLag; lag++) {
			double sum = 0;
			for (int i = Math.max(0, -lag); i < frames && i + lag < frames; i++) sum += l[i] * r[i + lag];
			if (sum > best) {
				best = sum;
				bestLag = lag;
			}
		}
		double itdMs = bestLag * 1000.0 / RATE; // right lags left by bestLag samples
		Log.i(TAG, String.format("src(%.0f,%.0f) heading %.0f: ITD %.3f ms, L/R energy %.2f", east, north, heading, itdMs, el / er));
		return new double[]{itdMs, el / er};
	}

	@Test
	public void sourceOnTheLeftReachesTheLeftEarFirst() {
		// Facing north, a source 5 m west is straight to the left.
		double[] r = renderClick(-5, 0, 0);
		assertTrue("left ear leads by 0.3-1.0 ms, was " + r[0], r[0] > 0.3 && r[0] < 1.0);
		assertTrue("left ear louder, ratio " + r[1], r[1] > 2);
	}

	@Test
	public void turningAroundSwapsTheEars() {
		// Same west source, now facing south: it is on the right.
		double[] r = renderClick(-5, 0, 180);
		assertTrue("right ear leads, was " + r[0], r[0] < -0.3 && r[0] > -1.0);
		assertTrue("right ear louder, ratio " + r[1], r[1] < 0.5);
	}

	@Test
	public void sourceStraightAheadHasNoTimeDifference() {
		double[] r = renderClick(0, 5, 0);
		assertTrue("ITD near zero, was " + r[0], Math.abs(r[0]) < 0.1);
	}
}
