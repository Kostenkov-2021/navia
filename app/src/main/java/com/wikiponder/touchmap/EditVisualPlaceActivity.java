package com.wikiponder.touchmap;

import android.content.Intent;
import android.media.AudioManager;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.wikiponder.touchmap.classes.ui.EdgeToEdgeActionBar;
import com.wikiponder.touchmap.classes.ui.VoiceInputEditText;
import com.wikiponder.touchmap.classes.ui.VprModelDownloadDialog;
import com.wikiponder.touchmap.classes.vpr.VisualPlacesDb;
import com.wikiponder.touchmap.classes.vpr.VprModel;

import java.util.ArrayList;
import java.util.List;

/**
 * One saved place (opened from ManageVisualPlacesActivity): rename it, and list, delete or add its
 * recordings. Each recording is named by its date and time so the user can tell them apart, e.g.
 * the one filmed from the north end of the street.
 */
public class EditVisualPlaceActivity extends AppCompatActivity {
	public static final String EXTRA_PLACE_ID = "place_id";

	private long placeId;
	private VisualPlacesDb.Place place;
	private final List<VisualPlacesDb.Recording> recordings = new ArrayList<>();
	private RecordingAdapter adapter;
	private VoiceInputEditText nameInput;
	private TextView emptyText;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_edit_visual_place);
		EdgeToEdgeActionBar.apply(this);
		EdgeToEdgeActionBar.padBottomForNavigationBar(((ViewGroup) findViewById(android.R.id.content)).getChildAt(0));
		setVolumeControlStream(AudioManager.STREAM_ACCESSIBILITY);
		ActionBar actionBar = getSupportActionBar();
		if (actionBar != null) actionBar.setDisplayHomeAsUpEnabled(true);

		placeId = getIntent().getLongExtra(EXTRA_PLACE_ID, -1);
		nameInput = findViewById(R.id.name_input);
		nameInput.setupSpeechRecognizer(MyApplication.getMyApplication(this).getPreferredLocale());
		nameInput.getEditText().setHint(R.string.hint_visual_place_name);
		emptyText = findViewById(R.id.empty_text);
		ListView list = findViewById(R.id.recordings_list);
		adapter = new RecordingAdapter();
		list.setAdapter(adapter);

		findViewById(R.id.save_name_button).setOnClickListener(v -> saveName());
		findViewById(R.id.add_recording_button).setOnClickListener(v -> {
			Intent record = new Intent(this, RecordVisualPlaceActivity.class)
					.putExtra(RecordVisualPlaceActivity.EXTRA_PLACE_ID, placeId);
			if (!VprModel.isAvailable(this)) {
				VprModelDownloadDialog.show(this, () -> startActivity(record));
				return;
			}
			startActivity(record);
		});
	}

	@Override
	protected void onResume() {
		super.onResume();
		reload();
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		nameInput.release();
	}

	private void reload() {
		new Thread(() -> {
			VisualPlacesDb db = VisualPlacesDb.get(this);
			VisualPlacesDb.Place loaded = db.getPlace(placeId);
			List<VisualPlacesDb.Recording> loadedRecordings = loaded == null ? List.of() : db.listRecordings(placeId);
			runOnUiThread(() -> {
				if (isFinishing() || isDestroyed()) return;
				if (loaded == null) {
					finish();
					return;
				}
				boolean first = place == null;
				place = loaded;
				setTitle(place.name);
				if (first) nameInput.setText(place.name);
				recordings.clear();
				recordings.addAll(loadedRecordings);
				adapter.notifyDataSetChanged();
				emptyText.setVisibility(recordings.isEmpty() ? View.VISIBLE : View.GONE);
			});
		}).start();
	}

	private void saveName() {
		String name = nameInput.getText().trim();
		if (name.isEmpty()) {
			Toast.makeText(this, R.string.toast_enter_name_required, Toast.LENGTH_SHORT).show();
			return;
		}
		new Thread(() -> {
			VisualPlacesDb.get(this).renamePlace(placeId, name);
			runOnUiThread(() -> {
				Toast.makeText(this, R.string.visual_place_renamed, Toast.LENGTH_SHORT).show();
				reload();
			});
		}).start();
	}

	private String recordingLabel(VisualPlacesDb.Recording r) {
		return DateUtils.formatDateTime(this, r.createdAt,
				DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_SHOW_YEAR);
	}

	private void confirmDelete(VisualPlacesDb.Recording r) {
		new AlertDialog.Builder(this)
				.setMessage(getString(R.string.visual_recording_delete_confirm, recordingLabel(r)))
				.setPositiveButton(R.string.action_delete, (d, w) -> new Thread(() -> {
					VisualPlacesDb.get(this).deleteRecording(r.id);
					runOnUiThread(this::reload);
				}).start())
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	@Override
	public boolean onSupportNavigateUp() {
		finish();
		return true;
	}

	private class RecordingAdapter extends ArrayAdapter<VisualPlacesDb.Recording> {
		RecordingAdapter() {
			super(EditVisualPlaceActivity.this, 0, recordings);
		}

		@NonNull
		@Override
		public View getView(int position, View view, @NonNull ViewGroup parent) {
			if (view == null) {
				view = LayoutInflater.from(getContext()).inflate(R.layout.list_item_visual_place, parent, false);
			}
			VisualPlacesDb.Recording r = recordings.get(position);
			String label = recordingLabel(r);
			((TextView) view.findViewById(R.id.place_text)).setText(
					getString(R.string.visual_recording_item, label, r.frameCount));
			ImageButton delete = view.findViewById(R.id.delete_button);
			delete.setContentDescription(getString(R.string.content_description_delete_visual_recording, label));
			delete.setOnClickListener(v -> confirmDelete(r));
			return view;
		}
	}
}
