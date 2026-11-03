package com.wikiponder.touchmap.classes.soundscape;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Plays the simulated soundscape of one map point: builds the {@link Scene}, decodes the clips it
 * needs, and runs a render thread that animates the vehicles, fires the occasional one-shots, keeps
 * the listener turned to the phone's live compass heading, and pulls HRTF-rendered stereo out of
 * {@link SpatialRenderer} into an Accessibility-stream AudioTrack (the same stream as every other
 * sound in the app — see AudioClient).
 *
 * <p>One instance plays at most once; make a new one for the next point. Every OpenAL call happens
 * on the render thread.
 */
public final class SoundscapePlayer {
	private static final String TAG = "SoundscapePlayer";

	public interface Listener {
		/** Audio is now playing. */
		void onSoundscapeStarted();

		/** Nothing mapped around the point to build a scene from; nothing was played. */
		void onSoundscapeEmpty();

		void onSoundscapeFailed();
	}

	private static final int SAMPLE_RATE = 48000;
	private static final int BLOCK_FRAMES = 960; // 20 ms
	private static final int MAX_MOVERS = 12;
	private static final float FADE_IN_S = 1.5f;
	private static final float FADE_OUT_S = 0.4f;
	private static final float MASTER_GAIN = 0.8f;
	/** The native renderer is a single static device: a player that starts while the previous one
	 * is still fading out waits here for it to release, instead of sharing (and freeing) its device. */
	private static final Object ENGINE_LOCK = new Object();

	private final Context context;
	private final double lat, lon;
	private final Listener listener;
	private final Handler main = new Handler(Looper.getMainLooper());
	private final Random random = new Random();
	private volatile float heading;
	private volatile boolean stopRequested;
	private Thread thread;
	/** Set only by the device test, which plays a synthetic scene instead of reading the db. */
	private Scene presetScene;
	/** For the device test: clips decoded, and the loudest rendered sample so far. */
	volatile int decodedClips;
	volatile float peak;

	public SoundscapePlayer(Context context, double lat, double lon, float initialHeading, Listener listener) {
		this.context = context.getApplicationContext();
		this.lat = lat;
		this.lon = lon;
		this.heading = initialHeading;
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

	// ------------------------------------------------------------------ render thread

	private static final class Mover {
		final LanePath path;
		final int dir;
		final int source;
		final float gain;
		float s;
		float closest = Float.MAX_VALUE;
		boolean passFired;

		Mover(LanePath path, int dir, int source, float gain, float s) {
			this.path = path;
			this.dir = dir;
			this.source = source;
			this.gain = gain;
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
		if (scene.isEmpty()) {
			post(listener::onSoundscapeEmpty);
			return;
		}
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
		List<Integer> sources = new ArrayList<>();
		AudioTrack track = null;
		try {
			// Decoding is independent per clip, so it runs in parallel; the OpenAL buffers are still
			// created here on the render thread, which owns every OpenAL call.
			long t0 = android.os.SystemClock.elapsedRealtime();
			List<String> ids = new ArrayList<>(scene.clips());
			java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(
					Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() - 1)));
			List<java.util.concurrent.Future<SoundBank.Clip>> decoded = new ArrayList<>();
			for (String id : ids) decoded.add(pool.submit(() -> SoundBank.load(context, id)));
			pool.shutdown();
			for (int i = 0; i < ids.size(); i++) {
				SoundBank.Clip clip;
				try {
					clip = decoded.get(i).get();
				} catch (InterruptedException | java.util.concurrent.ExecutionException e) {
					clip = null;
				}
				if (clip == null) continue;
				int b = SpatialRenderer.createBuffer(clip.samples, clip.sampleRate);
				if (b != 0) buffers.put(ids.get(i), b);
				decodedClips = buffers.size();
			}
			Log.i(TAG, "decoded " + buffers.size() + " clips in " + (android.os.SystemClock.elapsedRealtime() - t0) + " ms");
			if (stopRequested) return;
			track = buildTrack();
			loop(scene, buffers, sources, track);
		} catch (RuntimeException e) {
			Log.e(TAG, "soundscape failed", e);
			post(listener::onSoundscapeFailed);
		} finally {
			for (int s : sources) SpatialRenderer.deleteSource(s);
			for (int b : buffers.values()) SpatialRenderer.deleteBuffer(b);
			SpatialRenderer.release();
			if (track != null) {
				track.stop();
				track.release();
			}
		}
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
						.setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
						.setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
						.build())
				.setAudioFormat(format)
				.setBufferSizeInBytes(Math.max(min, BLOCK_FRAMES * 2 * 4 * 4))
				.setTransferMode(AudioTrack.MODE_STREAM)
				.build();
	}

	private void loop(Scene scene, Map<String, Integer> buffers, List<Integer> sources, AudioTrack track) {
		final float dt = BLOCK_FRAMES / (float) SAMPLE_RATE;
		double time = 0;

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
				int s = SpatialRenderer.createSource(b, true, spot.refDistance, SceneBuilder.RADIUS_M * 2, 1f, false);
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
			float expected = p.lane.perMinute / 60f * p.length / p.lane.speed;
			int n = Math.min(3, poisson(expected));
			for (int i = 0; i < n && movers.size() < MAX_MOVERS; i++) {
				spawn(p, buffers, movers, random.nextFloat() * p.length);
			}
			p.nextSpawn = expNext(p.lane.perMinute);
		}

		float[] out = new float[BLOCK_FRAMES * 2];
		float[] at = new float[4];
		track.play();
		boolean announced = false;
		float master = 0;
		float fadeOutLeft = -1;
		while (true) {
			if (stopRequested && fadeOutLeft < 0) fadeOutLeft = FADE_OUT_S;
			if (fadeOutLeft >= 0) {
				fadeOutLeft -= dt;
				if (fadeOutLeft <= 0) break;
				master = MASTER_GAIN * fadeOutLeft / FADE_OUT_S;
			} else {
				master = (float) Math.min(MASTER_GAIN, MASTER_GAIN * time / FADE_IN_S);
			}
			SpatialRenderer.setMasterGain(master);
			SpatialRenderer.setListenerHeading(heading);

			// --- vehicles
			for (LanePath p : lanes) {
				if (time >= p.nextSpawn) {
					if (movers.size() < MAX_MOVERS) spawn(p, buffers, movers, -1);
					p.nextSpawn = time + expNext(p.lane.perMinute);
				}
			}
			boolean[] warn = new boolean[lanes.size()];
			for (Iterator<Mover> it = movers.iterator(); it.hasNext(); ) {
				Mover m = it.next();
				m.s += m.dir * m.path.lane.speed * dt;
				if (m.s < 0 || m.s > m.path.length) {
					SpatialRenderer.deleteSource(m.source);
					it.remove();
					continue;
				}
				m.path.at(m.s, at);
				// Fade in/out over the first/last 40 m, in case a lane starts or ends within earshot.
				float edge = Math.min(m.s, m.path.length - m.s);
				float env = Math.min(1f, edge / 40f);
				float vE = at[2] * m.dir * m.path.lane.speed, vN = at[3] * m.dir * m.path.lane.speed;
				SpatialRenderer.updateSource(m.source, at[0], at[1], 0.8f, vE, vN, m.gain * env, 1f);

				float d = (float) Math.hypot(at[0], at[1]);
				if (d < m.closest) {
					m.closest = d;
				} else if (!m.passFired && m.path.lane.passEvent != null && m.closest < 70) {
					m.passFired = true;
					Integer b = buffers.get(m.path.lane.passEvent);
					if (b != null) {
						int s = SpatialRenderer.createSource(b, false, 8f, SceneBuilder.RADIUS_M * 2, 1f, false);
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
				int s = SpatialRenderer.createSource(b, false, shot.spot.refDistance, SceneBuilder.RADIUS_M * 2, 1f, false);
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
	}

	private void updateWarning(LanePath p, boolean on, Map<String, Integer> buffers, List<Integer> sources, float dt) {
		if (p.lane.warningAt == null) return;
		if (p.warningSource == 0) {
			Integer b = buffers.get(SceneBuilder.CLIP_CROSSING_BELL);
			if (b == null) return;
			p.warningSource = SpatialRenderer.createSource(b, true, 6f, SceneBuilder.RADIUS_M * 2, 1f, false);
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
		int src = SpatialRenderer.createSource(b, true, 6f, SceneBuilder.RADIUS_M * 2.5f, 1f, false);
		SpatialRenderer.updateSource(src, 0, 0, 0, 0, 0, 0f, 0.92f + 0.16f * random.nextFloat());
		startLooped(src);
		movers.add(new Mover(p, dir, src, v.gain, s));
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
