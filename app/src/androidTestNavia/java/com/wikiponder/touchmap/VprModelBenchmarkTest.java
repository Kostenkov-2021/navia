package com.wikiponder.touchmap;

import android.content.Context;
import android.os.Debug;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileWriter;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * Times the visual place recognition models exported by tools/vpr/export_megaloc.py.
 *
 * Not a correctness test: push the .onnx files first, then read the timings from logcat
 * (tag VprBench) or from vpr/bench_results.txt next to the models.
 *
 *   adb push tools/out/vpr/megaloc_salad_322_int8.onnx /sdcard/Android/data/com.wikiponder.touchmap/files/vpr/
 *   ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.wikiponder.touchmap.VprModelBenchmarkTest
 */
@RunWith(AndroidJUnit4.class)
public class VprModelBenchmarkTest {
	private static final String TAG = "VprBench";
	private static final int WARMUP = 2;
	private static final int RUNS = 6;

	@Test
	public void benchmarkModels() throws Exception {
		Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
		File dir = new File(context.getExternalFilesDir(null), "vpr");
		File[] models = dir.listFiles((d, name) -> name.endsWith(".onnx"));
		Assume.assumeTrue("no models pushed to " + dir, models != null && models.length > 0);
		Arrays.sort(models);

		OrtEnvironment env = OrtEnvironment.getEnvironment();
		StringBuilder report = new StringBuilder(android.os.Build.MODEL + " API " + android.os.Build.VERSION.SDK_INT + "\n");
		for (File model : models) {
			for (int threads : new int[]{2, 4}) {
				report.append(run(env, model, threads, false)).append('\n');
			}
			if (!model.getName().contains("int8")) {
				report.append(run(env, model, 4, true)).append('\n');
			}
		}
		try (FileWriter out = new FileWriter(new File(dir, "bench_results.txt"))) {
			out.write(report.toString());
		}
	}

	private String run(OrtEnvironment env, File model, int threads, boolean xnnpack) throws Exception {
		OrtSession.SessionOptions options = new OrtSession.SessionOptions();
		options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
		options.setIntraOpNumThreads(xnnpack ? 1 : threads);
		if (xnnpack) {
			options.addXnnpack(Collections.singletonMap("intra_op_num_threads", String.valueOf(threads)));
		}

		long t0 = System.nanoTime();
		try (OrtSession session = env.createSession(model.getAbsolutePath(), options)) {
			long loadMs = (System.nanoTime() - t0) / 1_000_000;
			long[] shape = ((ai.onnxruntime.TensorInfo) session.getInputInfo().get("image").getInfo()).getShape();
			int size = (int) shape[2];
			float[] pixels = new float[3 * size * size];
			for (int i = 0; i < pixels.length; i++) pixels[i] = (i % 255) / 255f;

			long[] times = new long[RUNS];
			try (OnnxTensor input = OnnxTensor.createTensor(env, FloatBuffer.wrap(pixels), new long[]{1, 3, size, size})) {
				Map<String, OnnxTensor> feed = Collections.singletonMap("image", input);
				for (int i = 0; i < WARMUP + RUNS; i++) {
					long s = System.nanoTime();
					try (OrtSession.Result ignored = session.run(feed)) {
						if (i >= WARMUP) times[i - WARMUP] = (System.nanoTime() - s) / 1_000_000;
					}
				}
			}
			Arrays.sort(times);
			long nativeMb = Debug.getNativeHeapAllocatedSize() / (1024 * 1024);
			String line = String.format(java.util.Locale.US, "%s threads=%d%s load=%dms median=%dms min=%dms nativeHeap=%dMB",
					model.getName(), threads, xnnpack ? " xnnpack" : "", loadMs, times[RUNS / 2], times[0], nativeMb);
			Log.i(TAG, line);
			return line;
		}
	}
}
