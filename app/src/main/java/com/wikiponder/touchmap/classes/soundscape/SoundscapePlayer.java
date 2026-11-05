package com.wikiponder.touchmap.classes.soundscape;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

/**
 * Plays the simulated soundscape of one map point: builds the {@link Scene}, decodes the clips it
 * needs, and runs a render thread that animates the vehicles, fires the occasional one-shots, keeps
 * the listener turned to the phone's live compass heading, and pulls HRTF-rendered stereo out of
 * {@link SpatialRenderer} into an AudioTrack — on the Accessibility stream by default (the same
 * stream as every other sound in Navia — see AudioClient).
 *
 * <p>One instance plays at most once, but its point can be moved while it plays ({@link #moveTo}):
 * the new scene is built and its missing clips decoded in the background while the old one keeps
 * playing, then the mix dips briefly and continues from the new point. Every OpenAL call happens
 * on the render thread.
 */
public final class SoundscapePlayer {
	private static final String TAG = "SoundscapePlayer";

	public interface Listener {
		/** Audio is now playing. */
		void onSoundscapeStarted();

		/** Nothing mapped around the point (the first one or a {@link #moveTo} target) to build a
		 * scene from. The player keeps running silently, so a later move can still be heard. */
		void onSoundscapeEmpty();

		void onSoundscapeFailed();
	}

	private static final int SAMPLE_RATE = 48000;
	private static final int BLOCK_FRAMES = 960; // 20 ms
	private static final int MAX_MOVERS = 12;
	private static final float FADE_IN_S = 1.5f;
	private static final float FADE_OUT_S = 0.4f;
	private static final float MOVE_FADE_OUT_S = 0.35f;
	private static final float MOVE_FADE_IN_S = 0.6f;
	/** Time constant of the listener-heading smoothing: compass updates arrive in steps, and turning
	 * the HRTF listener by a whole step within one 20 ms block is audible as a jump. */
	private static final float HEADING_SMOOTHING_S = 0.1f;
	private static final float MASTER_GAIN = 0.8f;
	/** Past this OpenAL stops attenuating; beyond any spot, so a far church bell keeps fading with distance. */
	private static final float MAX_SPOT_DISTANCE_M = 8000f;
	/** The speed (m/s, about 50 km/h) at which a road vehicle plays its clip unchanged. */
	private static final float REFERENCE_SPEED = 14f;
	/** The native renderer is a single static device: a player that starts while the previous one
	 * is still fading out waits here for it to release, instead of sharing (and freeing) its device. */
	private static final Object ENGINE_LOCK = new Object();

	private final Context context;
	private final double lat, lon;
	private final int usage;
	private final Listener listener;
	private final Handler main = new Handler(Looper.getMainLooper());
	private final Random random = new Random();
	private volatile float heading;
	private volatile boolean stopRequested;
	/** {lat, lon} of the latest {@link #moveTo}, until the render thread picks it up. */
	private volatile double[] moveRequest;
	private Thread thread;
	// render thread only
	private float listenerHeading = Float.NaN;
	private boolean announced;
	/** Set only by the device test, which plays a synthetic scene instead of reading the db. */
	private Scene presetScene;
	/** For the device test: clips decoded, and the loudest rendered sample so far. */
	volatile int decodedClips;
	volatile float peak;

	/** Plays on the Accessibility stream, like every other sound in Navia (see AudioClient). */
	public SoundscapePlayer(Context context, double lat, double lon, float initialHeading, Listener listener) {
		this(context, lat, lon, initialHeading, AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY, listener);
	}

	/** @param usage an AudioAttributes.USAGE_* — USAGE_MEDIA where no screen reader is assumed: the
	 *               Accessibility stream's volume can't be set with the volume keys without one. */
	public SoundscapePlayer(Context context, double lat, double lon, float initialHeading, int usage, Listener listener) {
		this.context = context.getApplicationContext();
		this.lat = lat;
		this.lon = lon;
		this.heading = initialHeading;
		this.usage = usage;
		this.listener = listener;
	}

	SoundscapePlayer(Context context, Scene scene, Listener listener) {
		this(context, 0, 0, 0, listener);
		presetScene = scene;
	}

	public static boolean isSupported() {
		return SpatialRenderer.isAvailable();
	}

	public void start() {
		thread = new Thread(this::run, "soundscape");
		thread.start();
	}

	/** Fades out and releases everything; safe to call more than once and from any thread. */
	public void stop() {
		stopRequested = true;
	}

	/** Compass heading the user faces, degrees clockwise from north. */
	public void setHeading(float headingDeg) {
		heading = headingDeg;
	}

	/** Continues the soundscape from another point. Any thread; only the latest request counts. */
	public void moveTo(double lat, double lon) {
		moveRequest = new double[]{lat, lon};
	}

	// ------------------------------------------------------------------ render thread

	private static final class Mover {
		final LanePath path;
		final int dir;
		final int source;
		final float gain, speed, pitch;
		float s;
		float closest = Float.MAX_VALUE;
		boolean passFired;

		Mover(LanePath path, int dir, int source, float gain, float speed, float pitch, float s) {
			this.path = path;
			this.dir = dir;
			this.source = source;
			this.gain = gain;
			this.speed = speed;
			this.pitch = pitch;
			this.s = s;
		}
	}

	/** A lane with its polyline's cumulative lengths, for "where is a vehicle s metres along". */
	private static final class LanePath {
		final Scene.Lane lane;
		final float[] cum;
		final float length;
		double nextSpawn;
		int warningSource;
		float warningGain;

		LanePath(Scene.Lane lane) {
			this.lane = lane;
			cum = new float[lane.east.length];
			for (int i = 1; i < cum.length; i++) {
				cum[i] = cum[i - 1] + (float) Math.hypot(lane.east[i] - lane.east[i - 1], lane.north[i] - lane.north[i - 1]);
			}
			length = cum[cum.length - 1];
		}

		/** out = {east, north, dirEast, dirNorth} at arc length s. */
		void at(float s, float[] out) {
			int i = 1;
			while (i < cum.length - 1 && cum[i] < s) i++;
			float seg = cum[i] - cum[i - 1];
			float t = seg == 0 ? 0 : (s - cum[i - 1]) / seg;
			float dx = lane.east[i] - lane.east[i - 1], dy = lane.north[i] - lane.north[i - 1];
			out[0] = lane.east[i - 1] + t * dx;
			out[1] = lane.north[i - 1] + t * dy;
			float len = (float) Math.hypot(dx, dy);
			out[2] = len == 0 ? 0 : dx / len;
			out[3] = len == 0 ? 0 : dy / len;
		}
	}

	private static final class Shot {
		final Scene.Spot spot;
		double next;

		Shot(Scene.Spot spot, double next) {
			this.spot = spot;
			this.next = next;
		}
	}

	/** A scene built for a {@link #moveTo} target, with the clips it needs that weren't loaded yet. */
	private static final class Relocation {
		final Scene scene;
		final Map<String, SoundBank.Clip> clips;

		Relocation(Scene scene, Map<String, SoundBank.Clip> clips) {
			this.scene = scene;
			this.clips = clips;
		}
	}

	private void run() {
		Scene scene;
		try {
			scene = presetScene != null ? presetScene : SoundscapeData.buildScene(context, lat, lon);
		} catch (RuntimeException e) {
			Log.e(TAG, "scene build failed", e);
			post(listener::onSoundscapeFailed);
			return;
		}
		Log.i(TAG, scene.toString());
		if (scene.isEmpty()) post(listener::onSoundscapeEmpty);
		synchronized (ENGINE_LOCK) {
			if (!stopRequested) render(scene);
		}
	}

	private void render(Scene scene) {
		if (!SpatialRenderer.init(SAMPLE_RATE)) {
			post(listener::onSoundscapeFailed);
			return;
		}
		Log.i(TAG, "HRTF enabled: " + SpatialRenderer.hrtfEnabled());

		Map<String, Integer> buffers = new HashMap<>();
		AudioTrack track = null;
		try {
			Map<String, SoundBank.Clip> decoded = decode(scene.clips(), buffers.keySet());
			if (stopRequested) return;
			track = buildTrack();
			track.play();
			float fadeIn = FADE_IN_S;
			while (true) {
				// Buffers are created here on the render thread, which owns every OpenAL call; those the
				// previous scene used but this one doesn't are freed (no source plays them any more).
				for (Map.Entry<String, SoundBank.Clip> e : decoded.entrySet()) {
					int b = SpatialRenderer.createBuffer(e.getValue().samples, e.getValue().sampleRate);
					if (b != 0) buffers.put(e.getKey(), b);
				}
				for (Iterator<Map.Entry<String, Integer>> it = buffers.entrySet().iterator(); it.hasNext(); ) {
					Map.Entry<String, Integer> e = it.next();
					if (!scene.clips().contains(e.getKey())) {
						SpatialRenderer.deleteBuffer(e.getValue());
						it.remove();
					}
				}
				decodedClips = buffers.size();
				if (stopRequested) break;
				Relocation next = loop(scene, buffers, track, fadeIn);
				if (next == null) break;
				scene = next.scene;
				decoded = next.clips;
				fadeIn = MOVE_FADE_IN_S;
			}
		} catch (RuntimeException e) {
			Log.e(TAG, "soundscape failed", e);
			post(listener::onSoundscapeFailed);
		} finally {
			for (int b : buffers.values()) SpatialRenderer.deleteBuffer(b);
			SpatialRenderer.release();
			if (track != null) {
				track.stop();
				track.release();
			}
		}
	}

	/** Decodes the clips in {@code ids} not in {@code have} yet, in parallel (decoding is independent
	 * per clip and touches no OpenAL state). Missing or broken clips are left out. Any thread. */
	private Map<String, SoundBank.Clip> decode(Collection<String> ids, Set<String> have) {
		long t0 = android.os.SystemClock.elapsedRealtime();
		List<String> todo = new ArrayList<>();
		for (String id : ids) if (!have.contains(id)) todo.add(id);
		Map<String, SoundBank.Clip> out = new HashMap<>();
		if (todo.isEmpty()) return out;
		ExecutorService pool = Executors.newFixedThreadPool(
				Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() - 1)));
		List<Future<SoundBank.Clip>> futures = new ArrayList<>();
		for (String id : todo) futures.add(pool.submit(() -> SoundBank.load(context, id)));
		pool.shutdown();
		for (int i = 0; i < todo.size(); i++) {
			try {
				SoundBank.Clip clip = futures.get(i).get();
				if (clip != null) out.put(todo.get(i), clip);
			} catch (InterruptedException | ExecutionException ignored) {
			}
		}
		Log.i(TAG, "decoded " + out.size() + " clips in " + (android.os.SystemClock.elapsedRealtime() - t0) + " ms");
		return out;
	}

	/** Builds the scene of a {@link #moveTo} target off the render thread. Yields null if that
	 * failed — the current scene then simply keeps playing. */
	private FutureTask<Relocation> startRelocation(double[] to, Set<String> loaded) {
		FutureTask<Relocation> task = new FutureTask<>(() -> {
			try {
				Scene scene = SoundscapeData.buildScene(context, to[0], to[1]);
				Log.i(TAG, "moved: " + scene);
				return new Relocation(scene, decode(scene.clips(), loaded));
			} catch (RuntimeException e) {
				Log.e(TAG, "scene build failed after move", e);
				return null;
			}
		});
		new Thread(task, "soundscape-move").start();
		return task;
	}

	private AudioTrack buildTrack() {
		AudioFormat format = new AudioFormat.Builder()
				.setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
				.setSampleRate(SAMPLE_RATE)
				.setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
				.build();
		int min = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT);
		return new AudioTrack.Builder()
				.setAudioAttributes(new AudioAttributes.Builder()
						.setUsage(usage)
						.setContentType(usage == AudioAttributes.USAGE_MEDIA
								? AudioAttributes.CONTENT_TYPE_MUSIC : AudioAttributes.CONTENT_TYPE_SONIFICATION)
						.build())
				.setAudioFormat(format)
				.setBufferSizeInBytes(Math.max(min, BLOCK_FRAMES * 2 * 4 * 4))
				.setTransferMode(AudioTrack.MODE_STREAM)
				.build();
	}

	/** Plays one scene until a stop (returns null) or until a {@link #moveTo} scene is ready and the
	 * mix has faded out for it (returns that scene). Deletes every source it made before returning. */
	private Relocation loop(Scene scene, Map<String, Integer> buffers, AudioTrack track, float fadeInS) {
		final float dt = BLOCK_FRAMES / (float) SAMPLE_RATE;
		final float headingAlpha = 1f - (float) Math.exp(-dt / HEADING_SMOOTHING_S);
		double time = 0;
		List<Integer> sources = new ArrayList<>();
		FutureTask<Relocation> relocating = null;
		Relocation pending = null;

		for (Scene.Bed bed : scene.beds) {
			Integer b = buffers.get(bed.clip);
			if (b == null) continue;
			int s = SpatialRenderer.createSource(b, true, 1f, 1000f, 0f, true);
			SpatialRenderer.updateSource(s, 0, 0, 0, 0, 0, bed.gain, 1f);
			startLooped(s);
			sources.add(s);
		}
		List<Shot> shots = new ArrayList<>();
		for (Scene.Spot spot : scene.spots) {
			Integer b = buffers.get(spot.clip);
			if (b == null) continue;
			if (spot.isLoop()) {
				int s = SpatialRenderer.createSource(b, true, spot.refDistance, MAX_SPOT_DISTANCE_M, 1f, false);
				SpatialRenderer.updateSource(s, spot.east, spot.north, 1.5f, 0, 0, spot.gain, 1f);
				startLooped(s);
				sources.add(s);
			} else {
				// First occurrence soon-ish, so a short listen still hears it.
				shots.add(new Shot(spot, spot.minInterval * 0.2 + random.nextDouble() * spot.minInterval));
			}
		}
		List<LanePath> lanes = new ArrayList<>();
		for (Scene.Lane lane : scene.lanes) {
			if (lane.east.length < 2) continue;
			LanePath p = new LanePath(lane);
			if (p.length < 20) continue;
			lanes.add(p);
		}
		List<Mover> movers = new ArrayList<>();
		List<Integer> oneShots = new ArrayList<>();
		// Seed traffic already under way, as if the user had been standing there a while.
		for (LanePath p : lanes) {
			if (p.lane.gapMax > 0) {
				// Timed arrivals start empty; the first one comes soon-ish, so a short listen still hears it.
				p.nextSpawn = p.lane.gapMin * (0.1 + 0.4 * random.nextDouble());
				continue;
			}
			float expected = p.lane.perMinute / 60f * p.length / p.lane.speed;
			int n = Math.min(3, poisson(expected));
			for (int i = 0; i < n && movers.size() < MAX_MOVERS; i++) {
				spawn(p, buffers, movers, random.nextFloat() * p.length);
			}
			p.nextSpawn = expNext(p.lane.perMinute);
		}

		float[] out = new float[BLOCK_FRAMES * 2];
		float[] at = new float[4];
		float master = 0;
		float fadeOutS = FADE_OUT_S;
		float fadeOutLeft = -1;
		while (true) {
			// --- moving to another point: built in the background while this scene plays on
			if (relocating == null && pending == null) {
				double[] to = moveRequest;
				if (to != null) {
					moveRequest = null;
					relocating = startRelocation(to, new HashSet<>(buffers.keySet()));
				}
			} else if (relocating != null && relocating.isDone()) {
				Relocation r;
				try {
					r = relocating.get();
				} catch (InterruptedException | ExecutionException e) {
					r = null;
				}
				relocating = null;
				// a newer move made while this one was building wins; this result is dropped
				if (r != null && moveRequest == null) {
					pending = r;
					if (r.scene.isEmpty()) post(listener::onSoundscapeEmpty);
				}
			}
			if (fadeOutLeft < 0 && (stopRequested || pending != null)) {
				fadeOutS = stopRequested ? FADE_OUT_S : MOVE_FADE_OUT_S;
				fadeOutLeft = fadeOutS;
			}
			if (fadeOutLeft >= 0) {
				fadeOutLeft -= dt;
				if (fadeOutLeft <= 0) break;
				master = Math.min(master, MASTER_GAIN * fadeOutLeft / fadeOutS);
			} else {
				master = (float) Math.min(MASTER_GAIN, MASTER_GAIN * time / fadeInS);
			}
			SpatialRenderer.setMasterGain(master);
			float target = heading;
			if (Float.isNaN(listenerHeading)) listenerHeading = target;
			float turn = ((target - listenerHeading) % 360f + 540f) % 360f - 180f; // shortest way round
			listenerHeading = ((listenerHeading + turn * headingAlpha) % 360f + 360f) % 360f;
			SpatialRenderer.setListenerHeading(listenerHeading);

			// --- vehicles
			for (LanePath p : lanes) {
				if (time >= p.nextSpawn) {
					if (movers.size() < MAX_MOVERS) spawn(p, buffers, movers, -1);
					p.nextSpawn = time + (p.lane.gapMax > 0
							? p.lane.gapMin + random.nextDouble() * (p.lane.gapMax - p.lane.gapMin)
							: expNext(p.lane.perMinute));
				}
			}
			boolean[] warn = new boolean[lanes.size()];
			for (Iterator<Mover> it = movers.iterator(); it.hasNext(); ) {
				Mover m = it.next();
				m.s += m.dir * m.speed * dt;
				if (m.s < 0 || m.s > m.path.length) {
					SpatialRenderer.deleteSource(m.source);
					it.remove();
					continue;
				}
				m.path.at(m.s, at);
				// Fade in/out at both ends, in case a lane starts or ends within earshot.
				float edge = Math.min(m.s, m.path.length - m.s);
				float env = Math.min(1f, edge / m.path.lane.edgeFade);
				float vE = at[2] * m.dir * m.speed, vN = at[3] * m.dir * m.speed;
				SpatialRenderer.updateSource(m.source, at[0], at[1], m.path.lane.height, vE, vN, m.gain * env, m.pitch);

				float d = (float) Math.hypot(at[0], at[1]);
				if (d < m.closest) {
					m.closest = d;
				} else if (!m.passFired && m.path.lane.passEvent != null && m.closest < 70) {
					m.passFired = true;
					Integer b = random.nextFloat() < m.path.lane.passChance ? buffers.get(m.path.lane.passEvent) : null;
					if (b != null) {
						int s = SpatialRenderer.createSource(b, false, 8f, MAX_SPOT_DISTANCE_M, 1f, false);
						SpatialRenderer.updateSource(s, at[0], at[1], 2f, 0, 0, 0.8f, 1f);
						SpatialRenderer.play(s);
						oneShots.add(s);
					}
				}
				float[] w = m.path.lane.warningAt;
				if (w != null && Math.hypot(at[0] - w[0], at[1] - w[1]) < 220) warn[lanes.indexOf(m.path)] = true;
			}
			for (int i = 0; i < lanes.size(); i++) updateWarning(lanes.get(i), warn[i], buffers, sources, dt);

			// --- occasional one-shots
			for (Shot shot : shots) {
				if (time < shot.next) continue;
				shot.next = time + shot.spot.minInterval + random.nextDouble() * (shot.spot.maxInterval - shot.spot.minInterval);
				Integer b = buffers.get(shot.spot.clip);
				if (b == null) continue;
				int s = SpatialRenderer.createSource(b, false, shot.spot.refDistance, MAX_SPOT_DISTANCE_M, 1f, false);
				SpatialRenderer.updateSource(s, shot.spot.east, shot.spot.north, 2f, 0, 0,
						shot.spot.gain * (0.75f + 0.25f * random.nextFloat()), 0.95f + 0.1f * random.nextFloat());
				SpatialRenderer.play(s);
				oneShots.add(s);
			}
			for (Iterator<Integer> it = oneShots.iterator(); it.hasNext(); ) {
				int s = it.next();
				if (!SpatialRenderer.isPlaying(s)) {
					SpatialRenderer.deleteSource(s);
					it.remove();
				}
			}

			SpatialRenderer.render(out, BLOCK_FRAMES);
			softLimit(out);
			for (float v : out) if (Math.abs(v) > peak) peak = Math.abs(v);
			track.write(out, 0, out.length, AudioTrack.WRITE_BLOCKING);
			if (!announced) {
				announced = true;
				post(listener::onSoundscapeStarted);
			}
			time += dt;
		}
		for (Mover m : movers) SpatialRenderer.deleteSource(m.source);
		for (int s : oneShots) SpatialRenderer.deleteSource(s);
		for (int s : sources) SpatialRenderer.deleteSource(s);
		if (relocating != null) relocating.cancel(false); // its thread just finishes on its own
		return stopRequested ? null : pending;
	}

	private void updateWarning(LanePath p, boolean on, Map<String, Integer> buffers, List<Integer> sources, float dt) {
		if (p.lane.warningAt == null) return;
		if (p.warningSource == 0) {
			Integer b = buffers.get(SceneBuilder.CLIP_CROSSING_BELL);
			if (b == null) return;
			p.warningSource = SpatialRenderer.createSource(b, true, 6f, MAX_SPOT_DISTANCE_M, 1f, false);
			sources.add(p.warningSource);
			SpatialRenderer.play(p.warningSource);
		}
		p.warningGain = Math.max(0f, Math.min(0.7f, p.warningGain + (on ? dt * 2f : -dt * 2f)));
		SpatialRenderer.updateSource(p.warningSource, p.lane.warningAt[0], p.lane.warningAt[1], 2f, 0, 0, p.warningGain, 1f);
	}

	/** s < 0: enter at whichever end the vehicle's direction starts from. */
	private void spawn(LanePath p, Map<String, Integer> buffers, List<Mover> movers, float s) {
		Scene.Vehicle v = pick(p.lane.vehicles);
		Integer b = v == null ? null : buffers.get(v.clip);
		if (b == null) return;
		int dir = p.lane.oneway || random.nextBoolean() ? 1 : -1;
		if (s < 0) s = dir > 0 ? 0 : p.length;
		Scene.Lane lane = p.lane;
		int src = SpatialRenderer.createSource(b, true, lane.refDistance, lane.maxDistance, 1f, false);
		float speed = lane.speed * v.speedFactor * (lane.engine ? 0.8f + 0.4f * random.nextFloat() : 1f);
		float pitch = 0.96f + 0.08f * random.nextFloat();
		float gain = v.gain;
		if (lane.engine) {
			float relative = speed / REFERENCE_SPEED;
			pitch *= SceneBuilder.clamp((float) Math.pow(relative, 0.2), 0.88f, 1.12f);
			gain *= SceneBuilder.clamp((float) Math.pow(relative, 0.4), 0.7f, 1.25f);
		}
		SpatialRenderer.updateSource(src, 0, 0, 0, 0, 0, 0f, pitch);
		startLooped(src);
		movers.add(new Mover(p, dir, src, gain, speed, pitch, s));
	}

	private Scene.Vehicle pick(List<Scene.Vehicle> vehicles) {
		float total = 0;
		for (Scene.Vehicle v : vehicles) total += v.share;
		float r = random.nextFloat() * total;
		for (Scene.Vehicle v : vehicles) {
			r -= v.share;
			if (r <= 0) return v;
		}
		return vehicles.isEmpty() ? null : vehicles.get(vehicles.size() - 1);
	}

	/** Starts a loop at a random offset so two sources sharing a clip never play in lockstep. */
	private void startLooped(int source) {
		SpatialRenderer.play(source);
		SpatialRenderer.seek(source, random.nextFloat() * 5f);
	}

	private double expNext(float perMinute) {
		if (perMinute <= 0) return Double.MAX_VALUE;
		return -Math.log(1 - random.nextDouble()) * 60.0 / perMinute;
	}

	private int poisson(float mean) {
		double l = Math.exp(-mean), p = 1;
		int k = 0;
		do {
			k++;
			p *= random.nextDouble();
		} while (p > l);
		return k - 1;
	}

	/** Gentle tanh limiter: a dense moment (bus + tram + horn) saturates softly instead of clipping. */
	private static void softLimit(float[] buf) {
		for (int i = 0; i < buf.length; i++) {
			float x = buf[i];
			if (x > 0.7f || x < -0.7f) {
				float sign = Math.signum(x);
				float over = (Math.abs(x) - 0.7f) / 0.3f;
				buf[i] = sign * (0.7f + 0.3f * (float) Math.tanh(over));
			}
		}
	}

	private void post(Runnable r) {
		if (!stopRequested) main.post(r);
	}
}
