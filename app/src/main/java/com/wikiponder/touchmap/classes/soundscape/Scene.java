package com.wikiponder.touchmap.classes.soundscape;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What {@link SceneBuilder} decided should be heard around one point, in listener-relative metres
 * (east = +x, north = +y). Pure data — {@link SoundscapePlayer} turns it into OpenAL sources and
 * animates the moving parts.
 */
public final class Scene {

	/** A non-directional bed sitting on the listener's head (distant traffic, city murmur). */
	public static final class Bed {
		public final String clip;
		public final float gain;

		Bed(String clip, float gain) {
			this.clip = clip;
			this.gain = gain;
		}
	}

	/** A sound fixed at one place: a looping ambience, or a one-shot repeated at random intervals. */
	public static final class Spot {
		public final String clip;
		public final float east, north;
		public final float gain;
		/** Distance (m) within which the source is at full {@link #gain}; bigger = carries further. */
		public final float refDistance;
		/** 0 for a loop; otherwise the one-shot repeats every minInterval..maxInterval seconds. */
		public final float minInterval, maxInterval;

		Spot(String clip, float east, float north, float gain, float refDistance, float minInterval, float maxInterval) {
			this.clip = clip;
			this.east = east;
			this.north = north;
			this.gain = gain;
			this.refDistance = refDistance;
			this.minInterval = minInterval;
			this.maxInterval = maxInterval;
		}

		public boolean isLoop() {
			return maxInterval <= 0;
		}
	}

	/** One kind of vehicle a {@link Lane} can send along its path, with its share of the traffic. */
	public static final class Vehicle {
		public final String clip;
		public final float share;
		public final float gain;

		Vehicle(String clip, float share, float gain) {
			this.clip = clip;
			this.share = share;
			this.gain = gain;
		}
	}

	/**
	 * A street, cycleway or track that vehicles drive along. Each vehicle is a moving source that
	 * enters at one end of {@link #east}/{@link #north}, follows the polyline at {@link #speed}, and
	 * is removed at the other end — OpenAL derives the Doppler shift from its velocity.
	 */
	public static final class Lane {
		public final String label;
		public final float[] east, north;
		/** m/s */
		public final float speed;
		/** Average vehicles per minute, both directions together. */
		public final float perMinute;
		public final boolean oneway;
		public final List<Vehicle> vehicles;
		/** Optional one-shot fired once per vehicle as it passes nearest the listener (tram bell). */
		public final String passEvent;
		/** Optional spot whose one-shot/loop is switched on while a vehicle is within 150 m of it (level-crossing bell). */
		public final float[] warningAt;

		Lane(String label, float[] east, float[] north, float speed, float perMinute, boolean oneway,
			 List<Vehicle> vehicles, String passEvent, float[] warningAt) {
			this.label = label;
			this.east = east;
			this.north = north;
			this.speed = speed;
			this.perMinute = perMinute;
			this.oneway = oneway;
			this.vehicles = vehicles;
			this.passEvent = passEvent;
			this.warningAt = warningAt;
		}
	}

	public final List<Bed> beds = new ArrayList<>();
	public final List<Spot> spots = new ArrayList<>();
	public final List<Lane> lanes = new ArrayList<>();

	/** Every clip id the scene uses, so the player decodes only those. */
	public Set<String> clips() {
		Set<String> out = new LinkedHashSet<>();
		for (Bed b : beds) out.add(b.clip);
		for (Spot s : spots) out.add(s.clip);
		for (Lane l : lanes) {
			for (Vehicle v : l.vehicles) out.add(v.clip);
			if (l.passEvent != null) out.add(l.passEvent);
			if (l.warningAt != null) out.add(SceneBuilder.CLIP_CROSSING_BELL);
		}
		return out;
	}

	public boolean isEmpty() {
		return spots.isEmpty() && lanes.isEmpty();
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder("Scene{");
		for (Bed b : beds) sb.append(String.format(java.util.Locale.US, " bed %s %.2f;", b.clip, b.gain));
		for (Spot s : spots) sb.append(String.format(java.util.Locale.US, " %s %s@(%.0f,%.0f) g%.2f;",
				s.isLoop() ? "loop" : "shot", s.clip, s.east, s.north, s.gain));
		for (Lane l : lanes) sb.append(String.format(java.util.Locale.US, " lane %s %.1f/min %.0fm/s %d pts;",
				l.label, l.perMinute, l.speed, l.east.length));
		return sb.append('}').toString();
	}
}
