package com.wikiponder.touchmap.classes.soundscape;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Plays a synthetic scene that uses every clip in the bank end to end: decode, moving sources, AudioTrack. */
@RunWith(AndroidJUnit4.class)
public class SoundscapePlayerDeviceTest {

	private static Map<String, String> tags(String... kv) {
		Map<String, String> m = new HashMap<>();
		for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
		return m;
	}

	@Test
	public void busyStreetCornerPlays() throws Exception {
		List<SceneBuilder.Node> nodes = new ArrayList<>();
		for (int i = -3; i <= 3; i++) nodes.add(new SceneBuilder.Node("signal", tags("railway", "signal"), 40, i * 30));
		nodes.add(new SceneBuilder.Node("level_crossing", tags("railway", "level_crossing"), 40, 5));
		for (int i = 0; i < 30; i++) nodes.add(new SceneBuilder.Node("tree", tags("natural", "tree"), -20 - i, 15));
		nodes.add(new SceneBuilder.Node("cafe", tags("amenity", "cafe"), 10, 5));
		nodes.add(new SceneBuilder.Node("school", tags("amenity", "school"), -50, 40));
		nodes.add(new SceneBuilder.Node("bus_stop", tags("highway", "bus_stop"), 5, 18));
		List<SceneBuilder.Way> ways = new ArrayList<>();
		ways.add(new SceneBuilder.Way("primary", tags("lanes", "4"), new float[]{-300, 300}, new float[]{20, 20}));
		ways.add(new SceneBuilder.Way("residential", tags(), new float[]{0, 0}, new float[]{-300, 300}));
		ways.add(new SceneBuilder.Way("residential", tags(), new float[]{-60, -60}, new float[]{-300, 300}));
		Scene scene = SceneBuilder.build(nodes, ways);

		Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
		CountDownLatch started = new CountDownLatch(1);
		AtomicBoolean failed = new AtomicBoolean();
		SoundscapePlayer p = new SoundscapePlayer(ctx, scene, new SoundscapePlayer.Listener() {
			@Override public void onSoundscapeStarted() { started.countDown(); }
			@Override public void onSoundscapeEmpty() { failed.set(true); started.countDown(); }
			@Override public void onSoundscapeFailed() { failed.set(true); started.countDown(); }
		});
		p.start();
		assertTrue("started", started.await(20, TimeUnit.SECONDS));
		assertFalse(failed.get());
		assertEquals("every clip the scene needs decoded", scene.clips().size(), p.decodedClips);
		Thread.sleep(6000);
		p.setHeading(180);
		Thread.sleep(2000);
		p.stop();
		assertTrue("audible output, peak " + p.peak, p.peak > 0.01f);
		assertTrue("not clipping, peak " + p.peak, p.peak <= 1f);
	}

	@Test
	public void quietStreetAndAirlinerPlay() throws Exception {
		List<SceneBuilder.Way> ways = new ArrayList<>();
		ways.add(new SceneBuilder.Way("residential", tags("maxspeed", "30", "oneway", "-1"), new float[]{-300, 300}, new float[]{10, 10}));
		ways.add(new SceneBuilder.Way("cycleway", tags(), new float[]{-300, 300}, new float[]{4, 4}));
		Scene scene = SceneBuilder.build(new ArrayList<>(), ways, new SceneBuilder.Surroundings(1f, new float[]{800, 400}));
		// Not minutes of waiting in a test: the airliner comes at once.
		for (Scene.Lane l : scene.lanes) {
			if (l.label.equals("aircraft")) {
				l.gapMin = 1;
				l.gapMax = 2;
			}
		}
		Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
		CountDownLatch started = new CountDownLatch(1);
		AtomicBoolean failed = new AtomicBoolean();
		SoundscapePlayer p = new SoundscapePlayer(ctx, scene, new SoundscapePlayer.Listener() {
			@Override public void onSoundscapeStarted() { started.countDown(); }
			@Override public void onSoundscapeEmpty() { failed.set(true); started.countDown(); }
			@Override public void onSoundscapeFailed() { failed.set(true); started.countDown(); }
		});
		p.start();
		assertTrue("started", started.await(20, TimeUnit.SECONDS));
		assertFalse(failed.get());
		Thread.sleep(8000);
		p.stop();
		assertFalse(failed.get());
		assertEquals("every clip the scene needs decoded", scene.clips().size(), p.decodedClips);
		assertTrue("audible output, peak " + p.peak, p.peak > 0.01f);
		assertTrue("not clipping, peak " + p.peak, p.peak <= 1f);
	}
}
