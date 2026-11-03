package com.wikiponder.touchmap.classes.soundscape;

import android.util.Log;

/**
 * Java face of the native OpenAL Soft loopback renderer (src/main/cpp/soundscape_jni.cpp).
 *
 * <p>OpenAL Soft does the actual 3D work: HRTF convolution (so each ear gets its own delay and
 * spectrum — the interaural time/phase difference, not just a pan), distance attenuation, and
 * Doppler for moving sources. It renders into a buffer we pull with {@link #render}, never to an
 * output device of its own, so {@link SoundscapePlayer} can route the result to the Accessibility
 * stream like the rest of the app's audio.
 *
 * <p>World coordinates are metres relative to the listener, east = +x, north = +y (converted to
 * OpenAL's x/-z inside {@link #updateSource}). All calls must come from one thread.
 */
public final class SpatialRenderer {
	private static final String TAG = "SpatialRenderer";
	private static final boolean LOADED;

	static {
		boolean ok;
		try {
			System.loadLibrary("openal");
			System.loadLibrary("soundscape");
			ok = true;
		} catch (UnsatisfiedLinkError e) {
			Log.w(TAG, "native soundscape renderer unavailable", e);
			ok = false;
		}
		LOADED = ok;
	}

	private SpatialRenderer() {
	}

	/** False on an ABI the native library wasn't built for — the menu item is hidden then. */
	public static boolean isAvailable() {
		return LOADED;
	}

	static boolean init(int sampleRate) {
		return LOADED && nativeInit(sampleRate);
	}

	static boolean hrtfEnabled() {
		return nativeHrtfEnabled();
	}

	/** Mono 16-bit PCM, or null if {@code ogg} isn't a decodable Ogg Vorbis file. Thread-safe (no
	 * OpenAL state involved), unlike every other method here. outRate[0] gets the sample rate. */
	static short[] decodeOgg(byte[] ogg, int[] outRate) {
		return nativeDecodeOgg(ogg, outRate);
	}

	static int createBuffer(short[] mono, int sampleRate) {
		return nativeCreateBuffer(mono, sampleRate);
	}

	static void deleteBuffer(int buffer) {
		nativeDeleteBuffer(buffer);
	}

	/**
	 * @param refDistance  distance (m) at which the source plays at its own gain; it gets quieter
	 *                     beyond it (inverse-distance law, scaled by rolloff) and never louder
	 *                     closer in.
	 * @param headRelative true for a non-directional bed that sits on the listener's head.
	 */
	static int createSource(int buffer, boolean loop, float refDistance, float maxDistance, float rolloff,
							boolean headRelative) {
		return nativeCreateSource(buffer, loop, refDistance, maxDistance, rolloff, headRelative);
	}

	/** east/north/up in metres from the listener, velocity in m/s on the same axes. */
	static void updateSource(int source, float east, float north, float up,
							 float vEast, float vNorth, float gain, float pitch) {
		nativeUpdateSource(source, east, up, -north, vEast, 0f, -vNorth, gain, pitch);
	}

	static void seek(int source, float seconds) {
		nativeSeekSource(source, seconds);
	}

	static void play(int source) {
		nativePlay(source);
	}

	static void stop(int source) {
		nativeStop(source);
	}

	static boolean isPlaying(int source) {
		return nativeIsPlaying(source);
	}

	static void deleteSource(int source) {
		nativeDeleteSource(source);
	}

	/** Compass heading the listener faces, degrees clockwise from north. */
	static void setListenerHeading(float headingDeg) {
		nativeSetListenerHeading(headingDeg);
	}

	static void setMasterGain(float gain) {
		nativeSetMasterGain(gain);
	}

	/** Fills {@code interleavedStereo} (length >= 2 * frames) with the next rendered frames. */
	static void render(float[] interleavedStereo, int frames) {
		nativeRender(interleavedStereo, frames);
	}

	static void release() {
		if (LOADED) nativeRelease();
	}

	private static native boolean nativeInit(int sampleRate);
	private static native boolean nativeHrtfEnabled();
	private static native short[] nativeDecodeOgg(byte[] ogg, int[] outRate);
	private static native int nativeCreateBuffer(short[] mono, int sampleRate);
	private static native void nativeDeleteBuffer(int buffer);
	private static native int nativeCreateSource(int buffer, boolean loop, float refDistance, float maxDistance,
												 float rolloff, boolean headRelative);
	private static native void nativeUpdateSource(int source, float x, float y, float z,
												  float vx, float vy, float vz, float gain, float pitch);
	private static native void nativeSeekSource(int source, float seconds);
	private static native void nativePlay(int source);
	private static native void nativeStop(int source);
	private static native boolean nativeIsPlaying(int source);
	private static native void nativeDeleteSource(int source);
	private static native void nativeSetListenerHeading(float headingDeg);
	private static native void nativeSetMasterGain(float gain);
	private static native void nativeRender(float[] out, int frames);
	private static native void nativeRelease();
}
