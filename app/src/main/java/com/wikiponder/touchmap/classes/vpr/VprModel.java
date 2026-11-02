package com.wikiponder.touchmap.classes.vpr;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import java.io.File;
import java.nio.FloatBuffer;
import java.util.Collections;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

/**
 * The visual place recognition model: MegaLoc (MIT; DINOv2 ViT-B/14 + SALAD + its final linear
 * layer), exported to ONNX by tools/vpr/export_megaloc.py and quantized by tools/vpr/quantize.py.
 * One camera frame in, one L2-normalized place descriptor out; two descriptors are compared by
 * their dot product (cosine similarity, 1 = identical view).
 *
 * The model file is not bundled (~240 MB): VprModelDownload fetches it into files/vpr/ the first
 * time the user opens Place photos. For development it may also be pushed with adb into the app's
 * external files/vpr/. Not thread-safe; callers run it on one background thread.
 */
public class VprModel {
	private static final String TAG = "VprModel";
	/** Changing the model makes every stored descriptor incomparable, which is why each stored
	 * frame records the model it was computed with (VisualPlacesDb.frames.model). */
	public static final String MODEL_NAME = "megaloc_full_224_hq";
	static final String MODEL_FILE = MODEL_NAME + ".onnx";
	/** Where VprModelDownload gets the model; hosted next to the other navia data (an .htaccess
	 * rewrite can move it to B2 like the boxes without changing this URL). Size and hash are of
	 * tools/out/vpr/megaloc_full_224_hq.onnx and must change together with MODEL_NAME. */
	static final String MODEL_URL = "https://wikiponder.com/navia/vpr/" + MODEL_FILE;
	public static final long MODEL_BYTES = 240712048L;
	static final String MODEL_SHA256 = "d4c0a8d7bb850853d8ed9c2ca9a49d36f0f26f07ef72da695da9b86c6425cb27";
	/** Stored with each descriptor (frames.model): the model plus how frames are fed to it. Saved
	 * frames whose key differs are ignored by the check (their JPEGs are kept, so they could be
	 * recomputed). "sq" = centre square crop, see embed. */
	public static final String DESCRIPTOR_KEY = MODEL_NAME + "/sq";

	private static VprModel instance;

	private final OrtEnvironment env;
	private final OrtSession session;
	private final int inputSize;

	private VprModel(File file) throws Exception {
		env = OrtEnvironment.getEnvironment();
		OrtSession.SessionOptions options = new OrtSession.SessionOptions();
		options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
		// Measured on a Galaxy S21: 4 threads ~2x faster than 2; the XNNPACK provider was slower.
		options.setIntraOpNumThreads(4);
		session = env.createSession(file.getAbsolutePath(), options);
		long[] shape = ((TensorInfo) session.getInputInfo().get("image").getInfo()).getShape();
		inputSize = (int) shape[2];
	}

	/** Where the downloaded model lives. */
	static File internalModelFile(Context context) {
		return new File(new File(context.getFilesDir(), "vpr"), MODEL_FILE);
	}

	public static File findModelFile(Context context) {
		File internal = internalModelFile(context);
		if (internal.exists()) return internal;
		File external = context.getExternalFilesDir(null);
		if (external != null) {
			File dev = new File(new File(external, "vpr"), MODEL_FILE);
			if (dev.exists()) return dev;
		}
		return null;
	}

	public static boolean isAvailable(Context context) {
		return findModelFile(context) != null;
	}

	/** Loads the model on first use (~0.5 s). Returns null if the model file is missing or broken. */
	public static synchronized VprModel get(Context context) {
		if (instance == null) {
			File file = findModelFile(context);
			if (file == null) return null;
			try {
				instance = new VprModel(file);
			} catch (Exception e) {
				Log.e(TAG, "could not load " + file, e);
				return null;
			}
		}
		return instance;
	}

	/** Frees the ~500 MB the loaded model holds; the next get() loads it again. */
	public static synchronized void release() {
		if (instance != null) {
			try {
				instance.session.close();
			} catch (Exception e) {
				Log.w(TAG, "close", e);
			}
			instance = null;
		}
	}

	/** The place descriptor of one (upright) frame. Only its centre square goes to the model: that
	 * square is the same part of the scene whether the phone was held upright, sideways or upside
	 * down, so a place saved in portrait still matches a landscape frame. Scaling the whole frame
	 * instead would stretch portrait and landscape frames differently. */
	public float[] embed(Bitmap frame) throws Exception {
		return embedFull(frame).global;
	}

	/** One frame's place descriptor plus its patch tokens: an L2-normalized 768-d vector for each
	 * cell of a grid x grid raster over the same centre square (row-major, row 0 at the top), used
	 * by VprAlign to find how far the view is turned from a saved one. */
	public static class Embedding {
		public final float[] global;
		public final float[] patches;
		public final int grid, dim;

		Embedding(float[] global, float[] patches, int grid, int dim) {
			this.global = global;
			this.patches = patches;
			this.grid = grid;
			this.dim = dim;
		}
	}

	public Embedding embedFull(Bitmap frame) throws Exception {
		int side = Math.min(frame.getWidth(), frame.getHeight());
		Bitmap square = Bitmap.createBitmap(frame, (frame.getWidth() - side) / 2, (frame.getHeight() - side) / 2, side, side);
		Bitmap scaled = Bitmap.createScaledBitmap(square, inputSize, inputSize, true);
		if (square != frame && square != scaled) square.recycle();
		int n = inputSize * inputSize;
		int[] pixels = new int[n];
		scaled.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize);
		if (scaled != frame) scaled.recycle();
		float[] chw = new float[3 * n];
		for (int i = 0; i < n; i++) {
			int p = pixels[i];
			chw[i] = ((p >> 16) & 0xff) / 255f;
			chw[n + i] = ((p >> 8) & 0xff) / 255f;
			chw[2 * n + i] = (p & 0xff) / 255f;
		}
		try (OnnxTensor input = OnnxTensor.createTensor(env, FloatBuffer.wrap(chw), new long[]{1, 3, inputSize, inputSize});
		     OrtSession.Result result = session.run(Collections.singletonMap("image", input))) {
			float[] global = ((float[][]) result.get(0).getValue())[0];
			float[][][] p = ((float[][][][]) result.get(1).getValue())[0];
			int grid = p.length, dim = p[0][0].length;
			float[] patches = new float[grid * grid * dim];
			for (int y = 0; y < grid; y++) {
				for (int x = 0; x < grid; x++) {
					System.arraycopy(p[y][x], 0, patches, (y * grid + x) * dim, dim);
				}
			}
			return new Embedding(global, patches, grid, dim);
		}
	}

	public static float similarity(float[] a, float[] b) {
		if (a.length != b.length) return -1;
		double s = 0;
		for (int i = 0; i < a.length; i++) s += a[i] * b[i];
		return (float) s;
	}
}
