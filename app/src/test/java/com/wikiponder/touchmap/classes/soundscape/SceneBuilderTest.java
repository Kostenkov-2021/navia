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
	public void waterfallCarriesFarAndInlandCliffHasNoSurf() {
		List<SceneBuilder.Node> nodes = new ArrayList<>();
		nodes.add(new SceneBuilder.Node("waterfall", tags("waterway", "waterfall"), 200, 150));
		nodes.add(new SceneBuilder.Node("cliff", tags("natural", "cliff"), -100, 50));
		Scene s = SceneBuilder.build(nodes, Collections.emptyList());
		assertTrue(hasSpot(s, "waterfall"));
		assertFalse("a cliff alone is no coast", hasSpot(s, "rocky_shore"));
		assertFalse(hasSpot(s, "seagulls"));
	}

	@Test
	public void beachAndFerryMakeACoast() {
		List<SceneBuilder.Node> nodes = new ArrayList<>();
		nodes.add(new SceneBuilder.Node("beach", tags("natural", "beach"), 0, -250));
		nodes.add(new SceneBuilder.Node("ferry_terminal", tags("amenity", "ferry_terminal"), 120, -200));
		nodes.add(new SceneBuilder.Node("cliff", tags("natural", "cliff"), -300, -200));
		Scene s = SceneBuilder.build(nodes, Collections.emptyList());
		assertTrue(hasSpot(s, "sea_waves"));
		assertTrue(hasSpot(s, "rocky_shore"));
		assertTrue(hasSpot(s, "harbor"));
		assertTrue(hasSpot(s, "seagulls"));
		assertTrue(hasSpot(s, "ship_horn"));
	}

	@Test
	public void unpavedRoadsGetGravelTyresAndCountryside() {
		List<SceneBuilder.Way> ways = new ArrayList<>();
		ways.add(road("unclassified", 15, tags("surface", "gravel")));
		ways.add(road("residential", -50, tags("surface", "dirt")));
		Scene s = SceneBuilder.build(Collections.emptyList(), ways);
		assertEquals("car_gravel", lane(s, "unclassified").vehicles.get(0).clip);
		boolean countryside = false;
		for (Scene.Bed b : s.beds) countryside |= b.clip.equals("countryside");
		assertTrue(countryside);
		assertTrue(hasSpot(s, "rooster"));
	}

	@Test
	public void placesOfWorshipFollowTheirReligion() {
		List<SceneBuilder.Node> nodes = new ArrayList<>();
		nodes.add(new SceneBuilder.Node("place_of_worship", tags("amenity", "place_of_worship", "religion", "buddhist"), 50, 0));
		nodes.add(new SceneBuilder.Node("place_of_worship", tags("amenity", "place_of_worship", "religion", "muslim"), -50, 0));
		Scene s = SceneBuilder.build(nodes, Collections.emptyList());
		assertTrue(hasSpot(s, "temple_bell"));
		assertFalse(hasSpot(s, "church_bells"));
	}

	@Test
	public void fuelStationGarageAndSmallShops() {
		List<SceneBuilder.Node> nodes = new ArrayList<>();
		nodes.add(new SceneBuilder.Node("fuel", tags("amenity", "fuel"), 30, 10));
		nodes.add(new SceneBuilder.Node("car_repair", tags("shop", "car_repair"), -30, 10));
		nodes.add(new SceneBuilder.Node("florist", tags("shop", "florist"), 10, -10));
		nodes.add(new SceneBuilder.Node("supermarket", tags("shop", "supermarket"), 20, 40));
		Scene s = SceneBuilder.build(nodes, Collections.emptyList());
		assertTrue(hasSpot(s, "fuel_station"));
		assertTrue(hasSpot(s, "garage"));
		assertTrue(hasSpot(s, "shop_bell"));
		assertTrue(hasSpot(s, "supermarket"));
	}

	@Test
	public void farmCluesBringAnimals() {
		List<SceneBuilder.Node> nodes = new ArrayList<>();
		nodes.add(new SceneBuilder.Node("cattle_grid", tags("barrier", "cattle_grid"), 80, 20));
		nodes.add(new SceneBuilder.Node("horse_riding", tags("leisure", "horse_riding"), -120, 40));
		Scene s = SceneBuilder.build(nodes, Collections.emptyList());
		assertTrue(hasSpot(s, "cows"));
		assertTrue(hasSpot(s, "sheep"));
		assertTrue(hasSpot(s, "horses"));
		assertFalse(hasSpot(s, "goats"));
	}

	private static Scene.Spot spot(Scene s, String clip) {
		for (Scene.Spot sp : s.spots) if (sp.clip.equals(clip)) return sp;
		return null;
	}

	private static boolean hasVehicle(Scene.Lane l, String clip) {
		for (Scene.Vehicle v : l.vehicles) if (v.clip.equals(clip)) return true;
		return false;
	}

	@Test
	public void speedComesFromTheLimitThenTheWidth() {
		assertEquals(30 / 3.6f * 0.85f, SceneBuilder.speedOf(road("residential", 0, tags("maxspeed", "30")), 8f), 0.01f);
		assertEquals(20 * 1.609f / 3.6f * 0.85f, SceneBuilder.speedOf(road("residential", 0, tags("maxspeed", "20 mph")), 8f), 0.01f);
		assertEquals(50 / 3.6f * 0.85f, SceneBuilder.speedOf(road("residential", 0, tags("maxspeed", "DE:urban")), 8f), 0.01f);
		assertEquals(30 / 3.6f * 0.85f, SceneBuilder.speedOf(road("residential", 0, tags("zone:maxspeed", "DE:30")), 8f), 0.01f);
		float narrow = SceneBuilder.speedOf(road("tertiary", 0, tags("width", "4")), 11f);
		float wide = SceneBuilder.speedOf(road("tertiary", 0, tags("lanes", "4")), 11f);
		assertTrue(narrow < 11f);
		assertTrue(wide > 11f);
		assertEquals(11f, SceneBuilder.speedOf(road("tertiary", 0, tags()), 11f), 0.01f);
	}

	@Test
	public void carSoundFollowsSpeed() {
		List<SceneBuilder.Way> ways = new ArrayList<>();
		ways.add(road("residential", 10, tags("maxspeed", "30")));
		ways.add(road("motorway", 60, tags("maxspeed", "120")));
		Scene s = SceneBuilder.build(Collections.emptyList(), ways);
		assertTrue(hasVehicle(lane(s, "residential"), "car_slow"));
		assertTrue(hasVehicle(lane(s, "motorway"), "car_fast"));
	}

	@Test
	public void heavyVehiclesOnlyOnWideRoads() {
		List<SceneBuilder.Way> ways = new ArrayList<>();
		ways.add(road("primary", 10, tags("lanes", "4")));
		ways.add(road("tertiary", 30, tags()));
		ways.add(road("secondary", 50, tags("width", "5")));
		Scene s = SceneBuilder.build(Collections.emptyList(), ways);
		assertTrue(hasVehicle(lane(s, "primary"), "truck"));
		assertTrue(hasVehicle(lane(s, "primary"), "bus"));
		assertFalse(hasVehicle(lane(s, "tertiary"), "truck"));
		assertFalse(hasVehicle(lane(s, "tertiary"), "bus"));
		assertFalse(hasVehicle(lane(s, "secondary"), "truck"));
	}

	@Test
	public void quietStreetHasNoHornsLouderBirdsSparseCarsAndChildren() {
		List<SceneBuilder.Way> ways = new ArrayList<>();
		for (int i = 0; i < 8; i++) ways.add(road("residential", 10 + i * 5, tags("maxspeed", "30")));
		Scene s = SceneBuilder.build(Collections.emptyList(), ways, new SceneBuilder.Surroundings(4f, null));
		assertFalse(hasSpot(s, "car_horn"));
		assertTrue(spot(s, "birds").gain >= 0.7f);
		assertTrue(hasSpot(s, "children"));
		int nearest = 0;
		for (Scene.Lane l : s.lanes) if (l.gapMin == 10f && l.gapMax == 20f) nearest++;
		assertEquals(1, nearest);
		assertEquals(5, s.lanes.size());
	}

	@Test
	public void hornsFollowTheHornFactor() {
		List<SceneBuilder.Way> ways = new ArrayList<>();
		ways.add(road("primary", 10, tags("lanes", "4", "maxspeed", "50")));
		ways.add(road("secondary", 40, tags("maxspeed", "50")));
		Scene loud = SceneBuilder.build(Collections.emptyList(), ways, new SceneBuilder.Surroundings(4f, null));
		Scene mild = SceneBuilder.build(Collections.emptyList(), ways, new SceneBuilder.Surroundings(0.3f, null));
		Scene none = SceneBuilder.build(Collections.emptyList(), ways, new SceneBuilder.Surroundings(0.15f, null));
		assertTrue(spot(loud, "car_horn").maxInterval < spot(mild, "car_horn").minInterval);
		assertTrue(hasSpot(loud, "car_horn_double"));
		assertTrue(hasSpot(loud, "truck_horn"));
		assertFalse(hasSpot(none, "car_horn"));
	}

	@Test
	public void hornCultureCombinesCountryAndCity() {
		float tehran = HornCulture.factor(35.70, 51.40, "IR");
		float amsterdam = HornCulture.factor(52.37, 4.89, "NL");
		float dutchVillage = HornCulture.factor(53.3, 6.5, "NL");
		assertTrue(tehran > amsterdam * 5);
		assertTrue(amsterdam > dutchVillage);
		assertEquals(0f, SceneBuilder.hornInterval(dutchVillage), 0f);
		assertTrue(SceneBuilder.hornInterval(tehran) < 15f);
	}

	@Test
	public void onewayDrivesOneWayIncludingReversed() {
		float[] e = {-300, 0, 300}, n = {10, 10, 10};
		assertEquals(1, SceneBuilder.drivingDirection(new SceneBuilder.Way("residential", tags("oneway", "yes"), e, n)));
		assertEquals(-1, SceneBuilder.drivingDirection(new SceneBuilder.Way("residential", tags("oneway", "-1"), e, n)));
		assertEquals(0, SceneBuilder.drivingDirection(new SceneBuilder.Way("residential", tags(), e, n)));
		assertEquals(0, SceneBuilder.drivingDirection(new SceneBuilder.Way("motorway", tags("oneway", "no"), e, n)));
		assertEquals(1, SceneBuilder.drivingDirection(new SceneBuilder.Way("motorway", tags(), e, n)));
		List<SceneBuilder.Way> ways = new ArrayList<>();
		ways.add(new SceneBuilder.Way("residential", tags("oneway", "-1"), new float[]{-100, 100}, n));
		Scene.Lane reversed = SceneBuilder.build(Collections.emptyList(), ways).lanes.get(0);
		assertTrue(reversed.oneway);
		assertTrue(reversed.east[0] > reversed.east[1]);
	}

	@Test
	public void cyclePathCarriesOnlyBicyclesWithAnOccasionalBell() {
		List<SceneBuilder.Way> ways = new ArrayList<>();
		ways.add(road("cycleway", 5, tags()));
		Scene.Lane l = lane(SceneBuilder.build(Collections.emptyList(), ways), "cycleway");
		for (Scene.Vehicle v : l.vehicles) assertTrue(v.clip.startsWith("bicycle"));
		assertEquals("bicycle_bell", l.passEvent);
		assertTrue(l.passChance < 1f);
	}

	@Test
	public void someTrafficLightsBeepAndTheSameOneAlwaysDoes() {
		assertTrue(SceneBuilder.beepsForPedestrians(new SceneBuilder.Node(1, "traffic_signals", tags("traffic_signals:sound", "yes"), 0, 0)));
		assertFalse(SceneBuilder.beepsForPedestrians(new SceneBuilder.Node(5, "traffic_signals", tags("traffic_signals:sound", "no"), 0, 0)));
		int beeping = 0;
		for (long id = 1; id <= 100; id++) {
			if (SceneBuilder.beepsForPedestrians(new SceneBuilder.Node(id, "traffic_signals", tags(), 0, 0))) beeping++;
		}
		assertEquals(20, beeping);
		assertFalse(SceneBuilder.beepsForPedestrians(new SceneBuilder.Node(5, "bench", tags(), 0, 0)));
	}

	@Test
	public void longBridgeRoarsAndShortOneDoesNot() {
		List<SceneBuilder.Way> ways = new ArrayList<>();
		ways.add(new SceneBuilder.Way("primary", tags("bridge", "yes"), new float[]{-100, 100}, new float[]{5, 5}));
		assertTrue(hasSpot(SceneBuilder.build(Collections.emptyList(), ways), "river_roar"));
		ways.clear();
		ways.add(new SceneBuilder.Way("primary", tags("bridge", "yes"), new float[]{-10, 10}, new float[]{5, 5}));
		assertFalse(hasSpot(SceneBuilder.build(Collections.emptyList(), ways), "river_roar"));
		ways.clear();
		ways.add(new SceneBuilder.Way("primary", tags("bridge", "viaduct"), new float[]{-100, 100}, new float[]{5, 5}));
		assertFalse(hasSpot(SceneBuilder.build(Collections.emptyList(), ways), "river_roar"));
	}

	@Test
	public void unpavedPathGetsSteps() {
		List<SceneBuilder.Way> ways = new ArrayList<>();
		ways.add(road("path", 10, tags("surface", "dirt")));
		assertTrue(hasSpot(SceneBuilder.build(Collections.emptyList(), ways), "footsteps_dirt"));
		ways.clear();
		ways.add(road("footway", 10, tags("surface", "asphalt")));
		assertFalse(hasSpot(SceneBuilder.build(Collections.emptyList(), ways), "footsteps_dirt"));
	}

	@Test
	public void churchBellsCarryFarButRarelyAndOnlyOnce() {
		List<SceneBuilder.Node> nodes = new ArrayList<>();
		nodes.add(new SceneBuilder.Node("place_of_worship", tags("amenity", "place_of_worship", "religion", "christian"), 900, 0));
		nodes.add(new SceneBuilder.Node("place_of_worship", tags("amenity", "place_of_worship", "religion", "christian"), 1000, 0));
		nodes.add(new SceneBuilder.Node("place_of_worship", tags("amenity", "place_of_worship"), 5, 0));
		Scene s = SceneBuilder.build(nodes, Collections.emptyList());
		int bells = 0;
		for (Scene.Spot sp : s.spots) if (sp.clip.equals("church_bells")) bells++;
		assertEquals(1, bells);
		assertEquals(120f, spot(s, "church_bells").minInterval, 0f);
		assertEquals(180f, spot(s, "church_bells").maxInterval, 0f);
	}

	@Test
	public void airportBringsAnAirlinerEveryFewMinutes() {
		List<SceneBuilder.Way> ways = new ArrayList<>();
		ways.add(road("residential", 10, tags()));
		Scene s = SceneBuilder.build(Collections.emptyList(), ways, new SceneBuilder.Surroundings(1f, new float[]{2000, 1000}));
		Scene.Lane plane = lane(s, "aircraft");
		assertEquals(120f, plane.gapMin, 0f);
		assertEquals(180f, plane.gapMax, 0f);
		assertTrue(plane.height > 100f);
		assertNull(lane(SceneBuilder.build(Collections.emptyList(), ways), "aircraft"));
	}

	@Test
	public void gullsAreHeardFarFromTheSeaSide() {
		List<SceneBuilder.Node> nodes = new ArrayList<>();
		nodes.add(new SceneBuilder.Node("beach", tags("natural", "beach"), 1500, 0));
		Scene s = SceneBuilder.build(nodes, Collections.emptyList());
		assertTrue(hasSpot(s, "seagulls"));
		assertFalse(hasSpot(s, "sea_waves"));
	}

	@Test
	public void moreKindsOfPlaces() {
		List<SceneBuilder.Node> nodes = new ArrayList<>();
		nodes.add(new SceneBuilder.Node("hairdresser", tags("shop", "hairdresser"), 10, 0));
		nodes.add(new SceneBuilder.Node("bakery", tags("shop", "bakery"), 12, 0));
		nodes.add(new SceneBuilder.Node("atm", tags("amenity", "atm"), 14, 0));
		nodes.add(new SceneBuilder.Node("fitness_centre", tags("leisure", "fitness_centre"), 16, 0));
		Scene s = SceneBuilder.build(nodes, Collections.emptyList());
		assertTrue(hasSpot(s, "hairdresser"));
		assertTrue(hasSpot(s, "bakery"));
		assertTrue(hasSpot(s, "atm"));
		assertTrue(hasSpot(s, "gym"));
	}

	@Test
	public void airNoiseIsAlwaysThere() {
		List<SceneBuilder.Way> ways = new ArrayList<>();
		ways.add(road("residential", 10, tags()));
		boolean air = false;
		for (Scene.Bed b : SceneBuilder.build(Collections.emptyList(), ways).beds) air |= b.clip.equals("air_noise");
		assertTrue(air);
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
