package com.wikiponder.touchmap.classes.vpr;

import java.util.Arrays;

/**
 * How far to turn so the live view lines up with a saved one, from the patch tokens of both
 * (VprModel.Embedding). Patches that are each other's best match are taken to show the same thing;
 * the median horizontal angle between where it sits in the live view and where it sat in the saved
 * one is the turn. Checked offline on two recordings of a room (2026-11-01): within a recording it
 * agreed with the compass to ~3°, across recordings it stayed right where the indoor compass
 * drifted by up to 20°.
 */
public final class VprAlign {
	/** Patch pairs below this cosine similarity are not trusted to show the same thing. */
	private static final float MIN_PATCH_SIM = 0.5f;
	/** Fewer mutual matches than this and the turn is not reported (offline: 85–174 for real
	 * matches of a 16x16 grid). */
	public static final int MIN_MATCHES = 30;

	private VprAlign() {
	}

	/** Degrees to turn, positive = clockwise (right), or NaN when too few patches match.
	 * @param fovDeg field of view across the centre square (see VprCameraInfo) */
	public static float turnDegrees(VprModel.Embedding live, float[] savedPatches, float fovDeg) {
		int grid = live.grid, dim = live.dim, n = grid * grid;
		if (savedPatches == null || savedPatches.length != live.patches.length) return Float.NaN;
		int[] bestForLive = new int[n];
		float[] bestLiveSim = new float[n];
		int[] bestForSaved = new int[n];
		float[] bestSavedSim = new float[n];
		Arrays.fill(bestLiveSim, -2);
		Arrays.fill(bestSavedSim, -2);
		float[] a = live.patches;
		for (int i = 0; i < n; i++) {
			int ai = i * dim;
			for (int j = 0; j < n; j++) {
				int bj = j * dim;
				float s = 0;
				for (int k = 0; k < dim; k++) s += a[ai + k] * savedPatches[bj + k];
				if (s > bestLiveSim[i]) {
					bestLiveSim[i] = s;
					bestForLive[i] = j;
				}
				if (s > bestSavedSim[j]) {
					bestSavedSim[j] = s;
					bestForSaved[j] = i;
				}
			}
		}
		double tanHalf = Math.tan(Math.toRadians(fovDeg / 2));
		float[] turns = new float[n];
		int m = 0;
		for (int i = 0; i < n; i++) {
			int j = bestForLive[i];
			if (bestForSaved[j] != i || bestLiveSim[i] < MIN_PATCH_SIM) continue;
			turns[m++] = (float) (angle(i % grid, grid, tanHalf) - angle(j % grid, grid, tanHalf));
		}
		if (m < MIN_MATCHES) return Float.NaN;
		Arrays.sort(turns, 0, m);
		return turns[m / 2];
	}

	/** Horizontal angle (degrees, right positive) of the centre of patch column col. */
	private static double angle(int col, int grid, double tanHalf) {
		double u = (col + 0.5) / grid * 2 - 1;
		return Math.toDegrees(Math.atan(u * tanHalf));
	}
}
