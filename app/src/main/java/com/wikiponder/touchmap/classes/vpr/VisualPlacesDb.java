package com.wikiponder.touchmap.classes.vpr;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Bitmap;
import android.location.Location;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Places the user saved for the visual check. A place is a name plus one or more recordings (one
 * per 5-second film, each from where the user stood then, e.g. one per direction of approach); a
 * recording holds a few camera frames with their place descriptors (VprModel). Kept in its own
 * visual_places.db, apart from osm.db (map data, rebuilt by downloads) and user_edits.db (small, in
 * Auto Backup). The frames themselves are JPEGs under files/visual_places/&lt;place id&gt;/, so a
 * future model can recompute the descriptors without asking the user to film the place again.
 */
public class VisualPlacesDb extends SQLiteOpenHelper {
	private static final String DB_NAME = "visual_places.db";
	private static final int DB_VERSION = 3;
	private static VisualPlacesDb instance;
	private final File framesDir;

	public static class Place {
		public long id;
		public String name;
		/** Where the first recording was made; placesNear sets the nearest recording instead. */
		public double lat, lon;
		public float accuracy;
		public long createdAt;
		public int frameCount;
		/** This place's frames for the current model; filled by placesNear only. */
		public List<SavedFrame> frames = new ArrayList<>();
	}

	public static class Recording {
		public long id;
		public long createdAt;
		public float accuracy;
		public int frameCount;
	}

	/** A stored frame as the visual check needs it. */
	public static class SavedFrame {
		public long id;
		public long recordingId;
		/** Where the user stood for this frame's recording. */
		public double lat, lon;
		/** Compass direction the lens looked (CameraOrientation), NaN if unknown. */
		public float heading;
		public String imagePath;
		public float[] descriptor;
	}

	public static class Frame {
		public final Bitmap bitmap;
		public final float[] descriptor;
		public final float heading;
		public final boolean frontCamera;

		public Frame(Bitmap bitmap, float[] descriptor, float heading, boolean frontCamera) {
			this.bitmap = bitmap;
			this.descriptor = descriptor;
			this.heading = heading;
			this.frontCamera = frontCamera;
		}
	}

	private VisualPlacesDb(Context context) {
		super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
		framesDir = new File(context.getApplicationContext().getFilesDir(), "visual_places");
	}

	public static synchronized VisualPlacesDb get(Context context) {
		if (instance == null) instance = new VisualPlacesDb(context);
		return instance;
	}

	@Override
	public void onCreate(SQLiteDatabase db) {
		db.execSQL("CREATE TABLE places(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, " +
				"lat REAL NOT NULL, lon REAL NOT NULL, accuracy REAL, created_at INTEGER NOT NULL)");
		// heading: compass azimuth (degrees) the phone faced for this frame, for later turn guidance.
		db.execSQL("CREATE TABLE frames(id INTEGER PRIMARY KEY AUTOINCREMENT, place_id INTEGER NOT NULL, " +
				"model TEXT NOT NULL, heading REAL, image_path TEXT, descriptor BLOB NOT NULL)");
		db.execSQL("CREATE INDEX frames_place ON frames(place_id)");
		createRecordings(db);
		addCameraColumn(db);
	}

	/** frames.camera: "front" or "back", the camera that took the frame. */
	private static void addCameraColumn(SQLiteDatabase db) {
		db.execSQL("ALTER TABLE frames ADD COLUMN camera TEXT NOT NULL DEFAULT 'back'");
	}

	private static void createRecordings(SQLiteDatabase db) {
		db.execSQL("CREATE TABLE recordings(id INTEGER PRIMARY KEY AUTOINCREMENT, place_id INTEGER NOT NULL, " +
				"lat REAL NOT NULL, lon REAL NOT NULL, accuracy REAL, created_at INTEGER NOT NULL)");
		db.execSQL("CREATE INDEX recordings_place ON recordings(place_id)");
		db.execSQL("ALTER TABLE frames ADD COLUMN recording_id INTEGER");
		db.execSQL("CREATE INDEX frames_recording ON frames(recording_id)");
	}

	@Override
	public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
		if (oldVersion < 2) {
			// Version 1 had one film per place: it becomes that place's only recording.
			createRecordings(db);
			db.execSQL("INSERT INTO recordings(id, place_id, lat, lon, accuracy, created_at) " +
					"SELECT id, id, lat, lon, accuracy, created_at FROM places");
			db.execSQL("UPDATE frames SET recording_id = place_id");
		}
		if (oldVersion < 3) addCameraColumn(db);
	}

	/** A new place with its first recording. Returns the place id. */
	public long insertPlace(String name, Location location, List<Frame> frames) throws Exception {
		SQLiteDatabase db = getWritableDatabase();
		db.beginTransaction();
		try {
			ContentValues place = new ContentValues();
			place.put("name", name);
			place.put("lat", location.getLatitude());
			place.put("lon", location.getLongitude());
			place.put("accuracy", location.getAccuracy());
			place.put("created_at", System.currentTimeMillis());
			long id = db.insertOrThrow("places", null, place);
			insertRecording(db, id, location, frames);
			db.setTransactionSuccessful();
			return id;
		} finally {
			db.endTransaction();
		}
	}

	/** Another film of an existing place, e.g. from a different direction. */
	public void addRecording(long placeId, Location location, List<Frame> frames) throws Exception {
		SQLiteDatabase db = getWritableDatabase();
		db.beginTransaction();
		try {
			insertRecording(db, placeId, location, frames);
			db.setTransactionSuccessful();
		} finally {
			db.endTransaction();
		}
	}

	private void insertRecording(SQLiteDatabase db, long placeId, Location location, List<Frame> frames) throws Exception {
		ContentValues rec = new ContentValues();
		rec.put("place_id", placeId);
		rec.put("lat", location.getLatitude());
		rec.put("lon", location.getLongitude());
		rec.put("accuracy", location.getAccuracy());
		rec.put("created_at", System.currentTimeMillis());
		long recordingId = db.insertOrThrow("recordings", null, rec);
		File dir = new File(framesDir, String.valueOf(placeId));
		if (!dir.mkdirs() && !dir.isDirectory()) throw new IllegalStateException("cannot create " + dir);
		for (int i = 0; i < frames.size(); i++) {
			Frame f = frames.get(i);
			File jpg = new File(dir, recordingId + "_" + i + ".jpg");
			try (FileOutputStream out = new FileOutputStream(jpg)) {
				f.bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out);
			}
			ContentValues frame = new ContentValues();
			frame.put("place_id", placeId);
			frame.put("recording_id", recordingId);
			frame.put("model", VprModel.DESCRIPTOR_KEY);
			frame.put("heading", f.heading);
			frame.put("camera", f.frontCamera ? "front" : "back");
			frame.put("image_path", jpg.getAbsolutePath());
			frame.put("descriptor", toBlob(f.descriptor));
			db.insertOrThrow("frames", null, frame);
		}
	}

	public void renamePlace(long id, String name) {
		ContentValues v = new ContentValues();
		v.put("name", name);
		getWritableDatabase().update("places", v, "id=?", new String[]{String.valueOf(id)});
	}

	/** The place's recordings, oldest first. */
	public List<Recording> listRecordings(long placeId) {
		List<Recording> list = new ArrayList<>();
		try (Cursor c = getReadableDatabase().rawQuery(
				"SELECT r.id, r.created_at, r.accuracy, " +
						"(SELECT COUNT(*) FROM frames f WHERE f.recording_id = r.id) " +
						"FROM recordings r WHERE r.place_id=? ORDER BY r.created_at",
				new String[]{String.valueOf(placeId)})) {
			while (c.moveToNext()) {
				Recording r = new Recording();
				r.id = c.getLong(0);
				r.createdAt = c.getLong(1);
				r.accuracy = c.getFloat(2);
				r.frameCount = c.getInt(3);
				list.add(r);
			}
		}
		return list;
	}

	/** Deletes one recording and its photos; the place stays, even with no recording left. */
	public void deleteRecording(long recordingId) {
		SQLiteDatabase db = getWritableDatabase();
		String[] arg = {String.valueOf(recordingId)};
		try (Cursor c = db.rawQuery("SELECT image_path FROM frames WHERE recording_id=?", arg)) {
			while (c.moveToNext()) {
				if (c.isNull(0)) continue;
				//noinspection ResultOfMethodCallIgnored
				new File(c.getString(0)).delete();
			}
		}
		db.delete("frames", "recording_id=?", arg);
		db.delete("recordings", "id=?", arg);
	}

	public void deletePlace(long id) {
		SQLiteDatabase db = getWritableDatabase();
		db.delete("frames", "place_id=?", new String[]{String.valueOf(id)});
		db.delete("recordings", "place_id=?", new String[]{String.valueOf(id)});
		db.delete("places", "id=?", new String[]{String.valueOf(id)});
		File dir = new File(framesDir, String.valueOf(id));
		File[] files = dir.listFiles();
		if (files != null) for (File f : files) //noinspection ResultOfMethodCallIgnored
			f.delete();
		//noinspection ResultOfMethodCallIgnored
		dir.delete();
	}

	public Place getPlace(long id) {
		try (Cursor c = getReadableDatabase().rawQuery(
				"SELECT p.id, p.name, p.lat, p.lon, p.accuracy, p.created_at, " +
						"(SELECT COUNT(*) FROM frames f WHERE f.place_id = p.id) " +
						"FROM places p WHERE p.id=?", new String[]{String.valueOf(id)})) {
			return c.moveToFirst() ? readPlace(c) : null;
		}
	}

	public List<Place> listPlaces() {
		List<Place> places = new ArrayList<>();
		try (Cursor c = getReadableDatabase().rawQuery(
				"SELECT p.id, p.name, p.lat, p.lon, p.accuracy, p.created_at, " +
						"(SELECT COUNT(*) FROM frames f WHERE f.place_id = p.id) " +
						"FROM places p ORDER BY p.name COLLATE NOCASE", null)) {
			while (c.moveToNext()) places.add(readPlace(c));
		}
		return places;
	}

	/** Places with a recording within radiusMeters of (lat, lon), nearest first, with their
	 * descriptors. Each recording counts from where it was made, so a place filmed from both ends
	 * of a street is near from either end. */
	public List<Place> placesNear(double lat, double lon, double radiusMeters) {
		// A bounding box narrows the scan before the exact distance check.
		double dLat = radiusMeters / 111_320.0;
		double dLon = radiusMeters / (111_320.0 * Math.max(0.01, Math.cos(Math.toRadians(lat))));
		Map<Long, double[]> nearest = new HashMap<>(); // place id -> {distance, lat, lon}
		float[] dist = new float[1];
		SQLiteDatabase db = getReadableDatabase();
		try (Cursor c = db.rawQuery(
				"SELECT place_id, lat, lon FROM recordings WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?",
				new String[]{String.valueOf(lat - dLat), String.valueOf(lat + dLat),
						String.valueOf(lon - dLon), String.valueOf(lon + dLon)})) {
			while (c.moveToNext()) {
				Location.distanceBetween(lat, lon, c.getDouble(1), c.getDouble(2), dist);
				if (dist[0] > radiusMeters) continue;
				double[] best = nearest.get(c.getLong(0));
				if (best == null || dist[0] < best[0]) {
					nearest.put(c.getLong(0), new double[]{dist[0], c.getDouble(1), c.getDouble(2)});
				}
			}
		}
		List<Place> near = new ArrayList<>();
		for (Map.Entry<Long, double[]> e : nearest.entrySet()) {
			Place p = getPlace(e.getKey());
			if (p == null) continue;
			p.lat = e.getValue()[1];
			p.lon = e.getValue()[2];
			near.add(p);
		}
		near.sort((a, b) -> Double.compare(nearest.get(a.id)[0], nearest.get(b.id)[0]));
		for (Place p : near) {
			try (Cursor c = db.rawQuery(
					"SELECT f.id, f.recording_id, r.lat, r.lon, f.heading, f.image_path, f.descriptor " +
							"FROM frames f JOIN recordings r ON r.id = f.recording_id " +
							"WHERE f.place_id=? AND f.model=?",
					new String[]{String.valueOf(p.id), VprModel.DESCRIPTOR_KEY})) {
				while (c.moveToNext()) {
					SavedFrame f = new SavedFrame();
					f.id = c.getLong(0);
					f.recordingId = c.getLong(1);
					f.lat = c.getDouble(2);
					f.lon = c.getDouble(3);
					f.heading = c.isNull(4) ? Float.NaN : c.getFloat(4);
					f.imagePath = c.getString(5);
					f.descriptor = fromBlob(c.getBlob(6));
					p.frames.add(f);
				}
			}
		}
		return near;
	}

	private static Place readPlace(Cursor c) {
		Place p = new Place();
		p.id = c.getLong(0);
		p.name = c.getString(1);
		p.lat = c.getDouble(2);
		p.lon = c.getDouble(3);
		p.accuracy = c.getFloat(4);
		p.createdAt = c.getLong(5);
		p.frameCount = c.getInt(6);
		return p;
	}

	private static byte[] toBlob(float[] v) {
		ByteBuffer b = ByteBuffer.allocate(v.length * 4).order(ByteOrder.LITTLE_ENDIAN);
		b.asFloatBuffer().put(v);
		return b.array();
	}

	private static float[] fromBlob(byte[] blob) {
		float[] v = new float[blob.length / 4];
		ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(v);
		return v;
	}
}
