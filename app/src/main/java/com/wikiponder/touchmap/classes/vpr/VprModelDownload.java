package com.wikiponder.touchmap.classes.vpr;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.util.Log;

import com.wikiponder.touchmap.classes.geofabrik.GeofabrikIndex;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.security.MessageDigest;

/**
 * Downloads the visual place recognition model (VprModel.MODEL_URL) into files/vpr/. One download
 * per process: it outlives the dialog that started it, so a recreated activity reattaches with
 * setListener and sees the current state. The file is written as .part and only renamed to its
 * real name once size and SHA-256 match, so VprModel.isAvailable never sees a truncated model.
 * Listener calls arrive on the main thread.
 */
public final class VprModelDownload {
	private static final String TAG = "VprModelDownload";
	/** Room left over on top of the model so the download doesn't fill the phone. */
	private static final long FREE_SPACE_MARGIN = 50L * 1024 * 1024;

	public enum Result {SUCCESS, NO_SPACE, FAILED, CANCELLED}

	public interface Listener {
		/** Whole percent, 0..100; called once per percent step. */
		void onProgress(int percent);

		void onFinished(Result result);
	}

	private static VprModelDownload running;

	private final Handler main = new Handler(Looper.getMainLooper());
	private volatile boolean cancelled;
	private volatile HttpURLConnection connection;
	private Listener listener;
	private int percent;

	private VprModelDownload() {
	}

	/** The download in progress, or null. Main thread only. */
	public static VprModelDownload current() {
		return running;
	}

	/** Starts a download unless one is already running; either way returns the running one. */
	public static VprModelDownload start(Context context, Listener listener) {
		if (running == null) {
			running = new VprModelDownload();
			running.listener = listener;
			Context app = context.getApplicationContext();
			VprModelDownload d = running;
			new Thread(() -> d.run(app), "VprModelDownload").start();
		} else {
			running.setListener(listener);
		}
		return running;
	}

	/** Replaces the listener (null to detach) and reports the current progress to the new one. */
	public void setListener(Listener listener) {
		this.listener = listener;
		if (listener != null) listener.onProgress(percent);
	}

	public void cancel() {
		cancelled = true;
		HttpURLConnection c = connection;
		if (c != null) c.disconnect();
	}

	private void run(Context context) {
		Result result = download(context);
		main.post(() -> {
			running = null;
			if (listener != null) listener.onFinished(result);
		});
	}

	private Result download(Context context) {
		File dest = VprModel.internalModelFile(context);
		File dir = dest.getParentFile();
		//noinspection ResultOfMethodCallIgnored
		dir.mkdirs();
		File tmp = new File(dest.getPath() + ".part");
		//noinspection ResultOfMethodCallIgnored
		tmp.delete();
		if (new StatFs(dir.getPath()).getAvailableBytes() < VprModel.MODEL_BYTES + FREE_SPACE_MARGIN) {
			return Result.NO_SPACE;
		}
		try {
			HttpURLConnection conn = GeofabrikIndex.openWithRedirects(VprModel.MODEL_URL);
			connection = conn;
			try {
				int code = conn.getResponseCode();
				if (code != HttpURLConnection.HTTP_OK) {
					Log.w(TAG, "model download HTTP " + code);
					return cancelled ? Result.CANCELLED : Result.FAILED;
				}
				MessageDigest sha = MessageDigest.getInstance("SHA-256");
				long total = 0;
				// Progress is measured against the known size, not Content-Length, which is -1 when
				// the server compresses the response.
				try (InputStream in = conn.getInputStream();
					 OutputStream out = new FileOutputStream(tmp)) {
					byte[] buf = new byte[65536];
					int n;
					while ((n = in.read(buf)) != -1) {
						if (cancelled) break;
						out.write(buf, 0, n);
						sha.update(buf, 0, n);
						total += n;
						int p = (int) Math.min(100, total * 100 / VprModel.MODEL_BYTES);
						if (p != percent) {
							percent = p;
							main.post(() -> {
								if (listener != null) listener.onProgress(p);
							});
						}
					}
				}
				if (cancelled) {
					//noinspection ResultOfMethodCallIgnored
					tmp.delete();
					return Result.CANCELLED;
				}
				String hash = toHex(sha.digest());
				if (total != VprModel.MODEL_BYTES || !hash.equals(VprModel.MODEL_SHA256)) {
					Log.w(TAG, "model download mismatch: " + total + " bytes, sha256 " + hash);
					//noinspection ResultOfMethodCallIgnored
					tmp.delete();
					return Result.FAILED;
				}
			} finally {
				conn.disconnect();
				connection = null;
			}
			return tmp.renameTo(dest) ? Result.SUCCESS : Result.FAILED;
		} catch (Exception e) {
			Log.w(TAG, "model download failed", e);
			//noinspection ResultOfMethodCallIgnored
			tmp.delete();
			return cancelled ? Result.CANCELLED : Result.FAILED;
		}
	}

	private static String toHex(byte[] bytes) {
		StringBuilder sb = new StringBuilder(bytes.length * 2);
		for (byte b : bytes) sb.append(String.format("%02x", b));
		return sb.toString();
	}
}
