package com.wikiponder.touchmap;

import android.content.Intent;
import android.media.AudioManager;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.wikiponder.touchmap.classes.ui.EdgeToEdgeActionBar;
import com.wikiponder.touchmap.classes.vpr.VisualPlacesDb;
import com.wikiponder.touchmap.classes.vpr.VprModel;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * The places saved for the visual check (More > Place photos): list, delete, open one in
 * EditVisualPlaceActivity, and the button that opens RecordVisualPlaceActivity to film a new one.
 */
public class ManageVisualPlacesActivity extends AppCompatActivity {

	private final List<VisualPlacesDb.Place> places = new ArrayList<>();
	private PlaceAdapter adapter;
	private TextView emptyText;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_manage_visual_places);
		EdgeToEdgeActionBar.apply(this);
		EdgeToEdgeActionBar.padBottomForNavigationBar(((android.view.ViewGroup) findViewById(android.R.id.content)).getChildAt(0));
		setTitle(R.string.visual_places_title);
		setVolumeControlStream(AudioManager.STREAM_ACCESSIBILITY);
		ActionBar actionBar = getSupportActionBar();
		if (actionBar != null) actionBar.setDisplayHomeAsUpEnabled(true);

		emptyText = findViewById(R.id.empty_text);
		ListView list = findViewById(R.id.places_list);
		adapter = new PlaceAdapter();
		list.setAdapter(adapter);
		findViewById(R.id.add_place_button).setOnClickListener(v -> {
			if (!VprModel.isAvailable(this)) {
				new AlertDialog.Builder(this)
						.setMessage(R.string.visual_model_missing)
						.setPositiveButton(android.R.string.ok, null)
						.show();
				return;
			}
			startActivity(new Intent(this, RecordVisualPlaceActivity.class));
		});
	}

	@Override
	protected void onResume() {
		super.onResume();
		reload();
	}

	private void reload() {
		new Thread(() -> {
			List<VisualPlacesDb.Place> loaded = VisualPlacesDb.get(this).listPlaces();
			runOnUiThread(() -> {
				places.clear();
				places.addAll(loaded);
				adapter.notifyDataSetChanged();
				emptyText.setVisibility(places.isEmpty() ? View.VISIBLE : View.GONE);
			});
		}).start();
	}

	private void confirmDelete(VisualPlacesDb.Place place) {
		new AlertDialog.Builder(this)
				.setMessage(getString(R.string.visual_place_delete_confirm, place.name))
				.setPositiveButton(R.string.action_delete, (d, w) -> new Thread(() -> {
					VisualPlacesDb.get(this).deletePlace(place.id);
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

	private class PlaceAdapter extends ArrayAdapter<VisualPlacesDb.Place> {
		PlaceAdapter() {
			super(ManageVisualPlacesActivity.this, 0, places);
		}

		@NonNull
		@Override
		public View getView(int position, View view, @NonNull ViewGroup parent) {
			if (view == null) {
				view = LayoutInflater.from(getContext()).inflate(R.layout.list_item_visual_place, parent, false);
			}
			VisualPlacesDb.Place place = places.get(position);
			String date = DateFormat.getMediumDateFormat(getContext()).format(new Date(place.createdAt));
			TextView text = view.findViewById(R.id.place_text);
			text.setText(getString(R.string.visual_place_item, place.name, date, place.frameCount));
			text.setOnClickListener(v -> startActivity(new Intent(ManageVisualPlacesActivity.this, EditVisualPlaceActivity.class)
					.putExtra(EditVisualPlaceActivity.EXTRA_PLACE_ID, place.id)));
			ImageButton delete = view.findViewById(R.id.delete_button);
			delete.setContentDescription(getString(R.string.content_description_delete_visual_place, place.name));
			delete.setOnClickListener(v -> confirmDelete(place));
			return view;
		}
	}
}
