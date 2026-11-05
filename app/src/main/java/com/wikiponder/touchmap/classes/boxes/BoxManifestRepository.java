package com.wikiponder.touchmap.classes.boxes;

import android.content.Context;
import android.util.Log;

import com.wikiponder.touchmap.classes.geofabrik.GeofabrikIndex;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/**
 * Loads {@code boxes_manifest.json}, the index of every planet-tiling box db hosted at
 * {@code https://wikiponder.com/navia/global_boxes/} (see {@link BoxManifest}). This is the only
 * source of downloadable map data; {@link com.wikiponder.touchmap.classes.importer.BoxImporter} always
 * resolves "download the map here" to exactly one box via this manifest.
 *
 * <p>The copy bundled in the APK always works, offline included. Once per launch
 * {@link #refreshAsync} compares the {@code "boxes"} number in the shared {@code updates.json} with
 * the map version in use and, when the server's is newer, replaces a private copy from
 * {@code boxes_manifest.json.gz}. Whichever of the two copies has the higher map version is used.
 * Box boundaries never change between versions, so a downloaded copy with different rectangles is
 * rejected: released apps keep using their own bundled rectangles, and a mismatch would hand them the
 * wrong file for a location.
 *
 * <p>Parsed once and cached in memory; a successful refresh replaces that cache.
 */
public class BoxManifestRepository {
	private static final String TAG = "BoxManifestRepository";
	public static final String BASE_URL = "https://wikiponder.com/navia/global_boxes/";
	private static final String MANIFEST_GZ_URL = BASE_URL + "boxes_manifest.json.gz";
	private static final String ASSET_NAME = "boxes_manifest.json";
	private static final String LOCAL_FILE_NAME = "boxes_manifest_server.json";
	/** About 100 times the real size; only stops a bad response from filling memory. */
	private static final int MAX_MANIFEST_BYTES = 100 * 1024 * 1024;

	private static volatile BoxManifest cached;

	private BoxManifestRepository() {
	}

	/** Url of one box's gzipped db, e.g. box_000123.db.gz. */
	public static String boxDbUrl(int index) {
		return String.format(java.util.Locale.US, "%sbox_%06d.db.gz", BASE_URL, index);
	}

	/** Returns the cached manifest if already parsed this process, else parses the newest local copy.
	 * Blocking — call off the main thread. Throws only if the bundled asset is somehow missing or
	 * corrupt, which should never happen since it ships with the APK. */
	public static BoxManifest getOrFetch(Context context) throws Exception {
		BoxManifest m = cached;
		if (m != null) return m;
		synchronized (BoxManifestRepository.class) {
			if (cached != null) return cached;
			BoxManifest bundled = readBundled(context);
			BoxManifest local = readLocalCopy(context, bundled);
			cached = local != null && local.mapVersion > bundled.mapVersion ? local : bundled;
			return cached;
		}
	}

	/** Checks for a newer manifest on its own thread. Any failure leaves the current one in use. */
	public static void refreshAsync(Context context) {
		final Context app = context.getApplicationContext();
		new Thread(() -> {
			try {
				refresh(app);
			} catch (Exception e) {
				Log.d(TAG, "Manifest refresh skipped: " + e.getMessage());
			}
		}, "box-manifest-refresh").start();
	}

	private static void refresh(Context context) throws Exception {
		String updates = new String(download(GeofabrikIndex.UPDATES_URL), StandardCharsets.UTF_8);
		int serverVersion = new JSONObject(updates).optInt("boxes", -1);
		if (serverVersion <= getOrFetch(context).mapVersion) return;

		String json = gunzipIfNeeded(download(MANIFEST_GZ_URL));
		BoxManifest fresh = BoxManifest.parse(json);
		BoxManifest bundled = readBundled(context);
		if (fresh.mapVersion < serverVersion || !fresh.hasSameBoxesAs(bundled)) {
			Log.w(TAG, "Ignoring server manifest: version " + fresh.mapVersion + ", expected " + serverVersion);
			return;
		}
		File target = localFile(context);
		File tmp = new File(target.getPath() + ".tmp");
		try (OutputStream out = new FileOutputStream(tmp)) {
			out.write(json.getBytes(StandardCharsets.UTF_8));
		}
		if (!tmp.renameTo(target)) {
			//noinspection ResultOfMethodCallIgnored
			tmp.delete();
			throw new IOException("could not save the manifest");
		}
		synchronized (BoxManifestRepository.class) {
			cached = fresh;
		}
		Log.i(TAG, "Box manifest updated to map version " + fresh.mapVersion);
	}

	private static File localFile(Context context) {
		return new File(context.getApplicationContext().getFilesDir(), LOCAL_FILE_NAME);
	}

	private static BoxManifest readBundled(Context context) throws Exception {
		try (InputStream in = context.getApplicationContext().getAssets().open(ASSET_NAME)) {
			return BoxManifest.parse(readAll(in));
		}
	}

	/** A missing, corrupt or mismatched saved copy just means the bundled one is used. */
	private static BoxManifest readLocalCopy(Context context, BoxManifest bundled) {
		File file = localFile(context);
		if (!file.exists()) return null;
		try (InputStream in = new FileInputStream(file)) {
			BoxManifest local = BoxManifest.parse(readAll(in));
			return local.hasSameBoxesAs(bundled) ? local : null;
		} catch (Exception e) {
			Log.w(TAG, "Ignoring saved manifest: " + e.getMessage());
			return null;
		}
	}

	/** A web server may send a .gz with Content-Encoding: gzip, and then the connection has already
	 * unpacked it, so the gzip header is checked rather than assumed. */
	private static String gunzipIfNeeded(byte[] data) throws IOException {
		boolean gzip = data.length > 2 && (data[0] & 0xff) == 0x1f && (data[1] & 0xff) == 0x8b;
		if (!gzip) return new String(data, StandardCharsets.UTF_8);
		try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
			return readAll(in);
		}
	}

	private static byte[] download(String url) throws Exception {
		HttpURLConnection conn = GeofabrikIndex.openWithRedirects(url);
		try {
			int code = conn.getResponseCode();
			if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code);
			try (InputStream in = conn.getInputStream()) {
				return readBytes(in);
			}
		} finally {
			conn.disconnect();
		}
	}

	private static String readAll(InputStream in) throws IOException {
		return new String(readBytes(in), StandardCharsets.UTF_8);
	}

	private static byte[] readBytes(InputStream in) throws IOException {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		byte[] buf = new byte[65536];
		int n;
		while ((n = in.read(buf)) != -1) {
			if (bos.size() + n > MAX_MANIFEST_BYTES) throw new IOException("manifest too large");
			bos.write(buf, 0, n);
		}
		return bos.toByteArray();
	}
}
