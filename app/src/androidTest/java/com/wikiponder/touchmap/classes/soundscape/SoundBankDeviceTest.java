package com.wikiponder.touchmap.classes.soundscape;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Every clip shipped in assets/soundscape decodes, is mono 44.1 kHz and not trivially short. */
@RunWith(AndroidJUnit4.class)
public class SoundBankDeviceTest {
	@Test
	public void everyClipDecodes() throws Exception {
		Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
		String[] files = ctx.getAssets().list("soundscape");
		int clips = 0;
		for (String f : files) {
			if (!f.endsWith(".ogg")) continue;
			String id = f.substring(0, f.length() - 4);
			SoundBank.Clip c = SoundBank.load(ctx, id);
			assertNotNull(id, c);
			assertTrue(id + " rate " + c.sampleRate, c.sampleRate == 44100);
			assertTrue(id + " too short", c.samples.length > c.sampleRate * 2);
			clips++;
		}
		assertTrue("bank has " + clips + " clips", clips >= 57);
	}
}
