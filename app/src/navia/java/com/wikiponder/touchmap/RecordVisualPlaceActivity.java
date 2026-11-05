package com.wikiponder.touchmap;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.location.Location;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.wikiponder.touchmap.classes.AudioClient;
import com.wikiponder.touchmap.classes.interfaces.UserLocationListener;
import com.wikiponder.touchmap.classes.ui.EdgeToEdgeActionBar;
import com.wikiponder.touchmap.classes.ui.VoiceInputEditText;
import com.wikiponder.touchmap.classes.vpr.CameraOrientation;
import com.wikiponder.touchmap.classes.vpr.VisualPlacesDb;
import com.wikiponder.touchmap.classes.vpr.VprFrames;
import com.wikiponder.touchmap.classes.vpr.VprModel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Saves a place for the visual check: the user names it (typed or spoken), points the phone at it
 * and presses Record. For 5 seconds the phone beeps once a second while the sharpest frame of each
 * second is kept; those 5 frames get place descriptors (VprModel) and are stored with the user's
 * position (VisualPlacesDb). With {@link #EXTRA_PLACE_ID} it films another recording of an existing
 * place instead (EditVisualPlaceActivity), so no name is asked for.
 */
public class RecordVisualPlaceActivity extends AppCompatActivity implements UserLocationListener {
	private static final String TAG = "RecordVisualPlace";
	public static final String EXTRA_PLACE_ID = "place_id";
	private static final int SECONDS = 5;
	/** A position older than this is not trusted as "where the user stands now". */
	private static final long LOCATION_MAX_AGE_MS = 30_000;

	/** The place a new recording is added to, or -1 for a new place. */
	private long placeId;
	private String placeName;
	private VoiceInputEditText nameInput;
	private TextView statusText;
	private Button recordButton;
	private ProcessCameraProvider cameraProvider;
	private PreviewView preview;

	private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
	private final ExecutorService modelExecutor = Executors.newSingleThreadExecutor();
	private final Handler handler = new Handler(Looper.getMainLooper());

	private Location bestLocation;
	private long bestLocationTime;
	private CameraOrientation orientation;

	// Recording state, written by the analysis thread while recording.
	private volatile boolean recording = false;
	private long recordStart;
	private final Bitmap[] bestFrames = new Bitmap[SECONDS];
	private final double[] bestSharpness = new double[SECONDS];
	private final float[] bestHeading = new float[SECONDS];

	private final ActivityResultLauncher<String> cameraPermission =
			registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
				if (granted) startCamera();
				else {
					Toast.makeText(this, R.string.camera_permission_required, Toast.LENGTH_LONG).show();
					finish();
				}
			});

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_record_visual_place);
		EdgeToEdgeActionBar.apply(this);
		EdgeToEdgeActionBar.padBottomForNavigationBar(((android.view.ViewGroup) findViewById(android.R.id.content)).getChildAt(0));
		placeId = getIntent().getLongExtra(EXTRA_PLACE_ID, -1);
		orientation = new CameraOrientation(this);
		setTitle(R.string.record_visual_place_title);
		setVolumeControlStream(AudioManager.STREAM_ACCESSIBILITY);
		ActionBar actionBar = getSupportActionBar();
		if (actionBar != null) actionBar.setDisplayHomeAsUpEnabled(true);

		nameInput = findViewById(R.id.name_input);
		nameInput.setupSpeechRecognizer(MyApplication.getMyApplication(this).getPreferredLocale());
		nameInput.getEditText().setHint(R.string.hint_visual_place_name);
		statusText = findViewById(R.id.status_text);
		recordButton = findViewById(R.id.record_button);
		preview = findViewById(R.id.preview);
		recordButton.setOnClickListener(v -> startRecording());
		if (placeId >= 0) {
			nameInput.setVisibility(View.GONE);
			statusText.setText(R.string.record_visual_recording_instructions);
			new Thread(() -> {
				VisualPlacesDb.Place place = VisualPlacesDb.get(this).getPlace(placeId);
				runOnUiThread(() -> {
					if (place == null) {
						finish();
						return;
					}
					placeName = place.name;
					setTitle(getString(R.string.record_visual_recording_title, place.name));
				});
			}).start();
		}

		if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
			startCamera();
		} else {
			cameraPermission.launch(Manifest.permission.CAMERA);
		}
	}

	@Override
	protected void onResume() {
		super.onResume();
		MyApplication.getUserLocationManager(this).addListener(this);
		orientation.start();
		// Background announcements would talk over the recording beeps.
		MyApplication.getAnnouncementManager(this).pause();
	}

	@Override
	protected void onPause() {
		super.onPause();
		MyApplication.getUserLocationManager(this).removeListener(this);
		orientation.stop();
		MyApplication.getAnnouncementManager(this).resume();
		if (recording) cancelRecording();
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		analysisExecutor.shutdown();
		modelExecutor.shutdown();
		handler.removeCallbacksAndMessages(null);
	}

	@Override
	public void onLocationUpdated(Location location) {
		if (location == null) return;
		long now = SystemClock.elapsedRealtime();
		if (bestLocation == null || now - bestLocationTime > LOCATION_MAX_AGE_MS
				|| location.getAccuracy() <= bestLocation.getAccuracy()) {
			bestLocation = location;
			bestLocationTime = now;
		}
	}

	private void startCamera() {
		ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
		future.addListener(() -> {
			try {
				cameraProvider = future.get();
				bindCamera();
			} catch (Exception e) {
				Log.e(TAG, "camera", e);
				Toast.makeText(this, R.string.camera_unavailable, Toast.LENGTH_LONG).show();
			}
		}, ContextCompat.getMainExecutor(this));
	}

	private void bindCamera() {
		if (cameraProvider == null) return;
		ProcessCameraProvider provider = cameraProvider;
		try {
			Preview previewUseCase = new Preview.Builder().build();
			previewUseCase.setSurfaceProvider(preview.getSurfaceProvider());
			ImageAnalysis analysis = new ImageAnalysis.Builder()
					.setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
					.setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
					.build();
			orientation.attach(analysis);
			analysis.setAnalyzer(analysisExecutor, image -> {
				try {
					if (recording) onRecordingFrame(VprFrames.upright(image));
				} catch (Exception e) {
					Log.w(TAG, "frame", e);
				} finally {
					image.close();
				}
			});
			provider.unbindAll();
			provider.bindToLifecycle(this, VprFrames.selector(false), previewUseCase, analysis);
		} catch (Exception e) {
			Log.e(TAG, "camera", e);
			Toast.makeText(this, R.string.camera_unavailable, Toast.LENGTH_LONG).show();
		}
	}

	private void startRecording() {
		String name = placeId >= 0 ? placeName : nameInput.getText().trim();
		if (name == null) return; // the place is still loading
		if (name.isEmpty()) {
			Toast.makeText(this, R.string.toast_enter_name_required, Toast.LENGTH_SHORT).show();
			return;
		}
		if (bestLocation == null || SystemClock.elapsedRealtime() - bestLocationTime > LOCATION_MAX_AGE_MS) {
			Toast.makeText(this, R.string.toast_waiting_for_location, Toast.LENGTH_SHORT).show();
			return;
		}
		synchronized (bestFrames) {
			for (int i = 0; i < SECONDS; i++) {
				if (bestFrames[i] != null) bestFrames[i].recycle();
				bestFrames[i] = null;
				bestSharpness[i] = -1;
			}
		}
		recordButton.setEnabled(false);
		nameInput.setEnabled(false);
		statusText.setText(R.string.record_visual_place_recording);
		recordStart = SystemClock.elapsedRealtime();
		recording = true;
		for (int s = 0; s < SECONDS; s++) {
			handler.postDelayed(this::beep, s * 1000L);
		}
		handler.postDelayed(() -> finishRecording(name), SECONDS * 1000L);
	}

	private void beep() {
		MyApplication.getAudioClient(this).playSound(AudioClient.SOUND_BEEP, 1.0, 0, 1.0);
	}

	private void onRecordingFrame(Bitmap frame) {
		int second = (int) ((SystemClock.elapsedRealtime() - recordStart) / 1000);
		if (second < 0 || second >= SECONDS) {
			frame.recycle();
			return;
		}
		double sharp = VprFrames.sharpness(frame);
		synchronized (bestFrames) {
			if (sharp > bestSharpness[second]) {
				if (bestFrames[second] != null) bestFrames[second].recycle();
				bestFrames[second] = frame;
				bestSharpness[second] = sharp;
				bestHeading[second] = orientation.heading();
				return;
			}
		}
		frame.recycle();
	}

	private void cancelRecording() {
		recording = false;
		handler.removeCallbacksAndMessages(null);
		recordButton.setEnabled(true);
		nameInput.setEnabled(true);
		statusText.setText(placeId >= 0 ? R.string.record_visual_recording_instructions
				: R.string.record_visual_place_instructions);
	}

	private void finishRecording(String name) {
		recording = false;
		MyApplication.getAudioClient(this).playSound(AudioClient.SOUND_DONE, 1.0, 0, 1.0);
		statusText.setText(R.string.record_visual_place_processing);
		Location location = bestLocation;
		List<Bitmap> frames = new ArrayList<>();
		List<Float> headings = new ArrayList<>();
		synchronized (bestFrames) {
			for (int i = 0; i < SECONDS; i++) {
				if (bestFrames[i] != null) {
					frames.add(bestFrames[i]);
					headings.add(bestHeading[i]);
					bestFrames[i] = null;
				}
			}
		}
		modelExecutor.execute(() -> {
			String error = null;
			try {
				if (frames.isEmpty()) throw new IllegalStateException("no frames");
				VprModel model = VprModel.get(this);
				if (model == null) throw new IllegalStateException("model missing");
				List<VisualPlacesDb.Frame> stored = new ArrayList<>();
				for (int i = 0; i < frames.size(); i++) {
					stored.add(new VisualPlacesDb.Frame(frames.get(i), model.embed(frames.get(i)), headings.get(i), false));
				}
				if (placeId >= 0) VisualPlacesDb.get(this).addRecording(placeId, location, stored);
				else VisualPlacesDb.get(this).insertPlace(name, location, stored);
			} catch (Exception e) {
				Log.e(TAG, "saving place", e);
				error = e.getMessage();
			} finally {
				for (Bitmap b : frames) b.recycle();
			}
			boolean ok = error == null;
			int count = frames.size();
			runOnUiThread(() -> {
				if (isFinishing() || isDestroyed()) return;
				if (ok) {
					String msg = getString(R.string.visual_place_saved, name, count);
					statusText.setText(msg);
					MyApplication.getAudioClient(this).speak(msg);
					handler.postDelayed(this::finish, 1500);
				} else {
					statusText.setText(R.string.visual_place_save_failed);
					recordButton.setEnabled(true);
					nameInput.setEnabled(true);
				}
			});
		});
	}

	@Override
	public boolean onSupportNavigateUp() {
		finish();
		return true;
	}
}
