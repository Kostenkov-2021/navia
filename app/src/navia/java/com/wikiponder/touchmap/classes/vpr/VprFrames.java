package com.wikiponder.touchmap.classes.vpr;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;

import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageProxy;
import androidx.preference.PreferenceManager;

/** Camera frame helpers shared by recording a place and the live visual check. */
public final class VprFrames {
	/** Whether the last screen that used the camera for places had the front camera on. Shared by
	 * recording and the visual check, so a user who holds the phone facing the other way only
	 * switches once. */
	private static final String PREF_FRONT_CAMERA = "vpr_front_camera";

	private VprFrames() {
	}

	public static boolean useFrontCamera(Context context) {
		return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_FRONT_CAMERA, false);
	}

	public static void setUseFrontCamera(Context context, boolean front) {
		PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(PREF_FRONT_CAMERA, front).apply();
	}

	public static CameraSelector selector(boolean front) {
		return front ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA;
	}

	/** The frame upright, as the user held the phone. The ImageAnalysis use case must output
	 * RGBA_8888 for ImageProxy.toBitmap(). Front camera frames come unmirrored (only the preview is
	 * mirrored), so they show the scene the same way round as the back camera. */
	public static Bitmap upright(ImageProxy image) {
		Bitmap bitmap = image.toBitmap();
		int rotation = image.getImageInfo().getRotationDegrees();
		if (rotation == 0) return bitmap;
		Matrix m = new Matrix();
		m.postRotate(rotation);
		Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), m, true);
		bitmap.recycle();
		return rotated;
	}

	/** Sharpness = variance of the Laplacian of a 160 px wide grey copy. Motion blur and missed
	 * focus both lower it, so the sharpest frame of each second is the one worth keeping. */
	public static double sharpness(Bitmap frame) {
		int w = 160;
		int h = Math.max(1, frame.getHeight() * w / frame.getWidth());
		Bitmap small = Bitmap.createScaledBitmap(frame, w, h, true);
		int[] px = new int[w * h];
		small.getPixels(px, 0, w, 0, 0, w, h);
		if (small != frame) small.recycle();
		float[] g = new float[w * h];
		for (int i = 0; i < px.length; i++) {
			int p = px[i];
			g[i] = 0.299f * ((p >> 16) & 0xff) + 0.587f * ((p >> 8) & 0xff) + 0.114f * (p & 0xff);
		}
		double sum = 0, sumSq = 0;
		int n = 0;
		for (int y = 1; y < h - 1; y++) {
			for (int x = 1; x < w - 1; x++) {
				int i = y * w + x;
				double lap = g[i - 1] + g[i + 1] + g[i - w] + g[i + w] - 4 * g[i];
				sum += lap;
				sumSq += lap * lap;
				n++;
			}
		}
		if (n == 0) return 0;
		double mean = sum / n;
		return sumSq / n - mean * mean;
	}
}
