package com.wikiponder.touchmap.classes.soundscape;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SceneBuilderTest {

	private static Map<String, String> tags(String... kv) {
		Map<String, String> m = new HashMap<>();
		for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
		return m;
	}

	private static SceneBuilder.Way road(String type, float north, Map<String, String> tags) {
		return new SceneBuilder.Way(type, tags, new float[]{-300, 0, 300}, new float[]{north, north, north});
	}

	private static Scene.Lane lane(Scene s, String label) {
		for (Scene.Lane l : s.lanes) if (l.label.equals(label)) return l;
		return null;
	}

	private static boolean hasSpot(Scene s, String clip) {
		for (Scene.Spot sp : s.spots) if (sp.clip.equals(clip)) return true;
		return false;
	}

	@Test
	public void primaryRoadIsBusierThanResidentialAndLanesScaleIt() {
		List<SceneBuilder.Way> ways = new ArrayList<>();
		ways.add(road("primary", 20, tags("lanes", "4", "maxspeed", "50")));
		ways.add(road("residential", -40, tags()));
		Scene s = SceneBuilder.build(Collections.emptyList(), ways);
		Scene.Lane primary = lane(s, "primary");
		Scene.Lane residential = lane(s, "residential");
		assertNotNull(primary);
		assertNotNull(residential);
		assertEquals(18f * 2f, primary.perMinute, 0.01f); // 4 lanes = 2x the default two
		assertEquals(50 / 3.6f * 0.85f, primary.speed, 0.01f);
		assertTrue(primary.perMinute > residential.perMinute * 5);
		// Busiest lane first after the rail (none here).
		assertEquals("primary", s.lanes.get(0).label);
	}

	@Test
	public void farRoadsAreIgnoredAndClippedToEarshot() {
		List<SceneBuilder.Way> ways = new ArrayList<>();
		ways.add(road("primary", 500, tags()));
		ways.add(new SceneBuilder.Way("secondary", tags(),
				new float[]{-2000, -1000, 0, 1000, 2000}, new float[]{30, 30, 30, 30, 30}));
		Scene s = SceneBuilder.build(Collections.emptyList(), ways);
		assertNull(lane(s, "primary"));
		Scene.Lane sec = lane(s, "secondary");
		assertNotNull(sec);
		// Only the vertices around the listener survive (plus the segment ends leaving earshot).
		assertEquals(3, sec.east.length);
	}

	@Test
	public void railLineIsFittedThroughTrackNodes() {
		List<SceneBuilder.Node> nodes = new ArrayList<>();
		// A north-south track 40 m east of the listener, known only from its signals and switches.
		for (int i = -3; i <= 3; i++) {
			nodes.add(new SceneBuilder.Node(i % 2 == 0 ? "signal" : "switch",
					tags("railway", i % 2 == 0 ? "signal" : "switch"), 40, i * 30));
		}
		nodes.add(new SceneBuilder.Node("level_crossing", tags("railway", "level_crossing"), 40, 5));
		Scene s = SceneBuilder.build(nodes, Collections.emptyList());
		Scene.Lane rail = lane(s, "rail");
		assertNotNull(rail);
		assertEquals("train", rail.vehicles.get(0).clip);
		// Line runs north-south through x = 40.
		assertEquals(40, rail.east[0], 0.5f);
		assertEquals(40, rail.east[1], 0.5f);
		assertTrue(Math.abs(rail.north[1] - rail.north[0]) > 800);
		assertNotNull(rail.warningAt);
		assertTrue(s.clips().contains(SceneBuilder.CLIP_CROSSING_BELL));
	}

	@Test
	public void tramStopsMakeATramWithBell() {
		List<SceneBuilder.Node> nodes = new ArrayList<>();
		nodes.add(new SceneBuilder.Node("tram_stop", tags("railway", "tram_stop"), -30, 10));
		nodes.add(new SceneBuilder.Node("tram_level_crossing", tags("railway", "tram_level_crossing"), 30, 10));
		nodes.add(new SceneBuilder.Node("tram_crossing", tags("railway", "tram_crossing"), 90, 12));
		Scene s = SceneBuilder.build(nodes, Collections.emptyList());
		Scene.Lane tram = lane(s, "tram");
		assertNotNull(tram);
		assertEquals("tram_bell", tram.passEvent);
		// East-west line about 10 m north of the listener.
		assertEquals(10, (tram.north[0] + tram.north[1]) / 2, 2f);
	}

	@Test
	public void treesSchoolAndCafeBecomeSpots() {
		List<SceneBuilder.Node> nodes = new ArrayList<>();
		for (int i = 0; i < 12; i++) nodes.add(new SceneBuilder.Node("tree", tags("natural", "tree"), 20 + i, -10));
		nodes.add(new SceneBuilder.Node("school", tags("amenity", "school"), -50, 40));
		nodes.add(new SceneBuilder.Node("cafe", tags("amenity", "cafe"), 10, 5));
		nodes.add(new SceneBuilder.Node("bench", tags("amenity", "bench"), 3, 3));
		Scene s = SceneBuilder.build(nodes, Collections.emptyList());
		assertTrue(hasSpot(s, "leaves"));
		assertTrue(hasSpot(s, "birds"));
		assertTrue(hasSpot(s, "schoolyard"));
		assertTrue(hasSpot(s, "cafe"));
		assertFalse(s.isEmpty());
	}

	@Test
	public void nothingMappedMeansEmptyScene() {
		Scene s = SceneBuilder.build(Collections.emptyList(), Collections.emptyList());
		assertTrue(s.isEmpty());
	}

	@Test
	public void clipToRadiusKeepsSegmentPassingThroughWithoutVerticesInside() {
		// A straight road whose only two vertices are both far away but which passes right by.
		float[][] c = SceneBuilder.clipToRadius(new float[]{-1000, 1000}, new float[]{5, 5}, 100);
		assertNotNull(c);
		assertEquals(2, c[0].length);
	}
}
