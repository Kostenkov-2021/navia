package com.wikiponder.touchmap.classes.ui;

import android.app.Activity;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.wikiponder.touchmap.R;
import com.wikiponder.touchmap.classes.vpr.VprModel;
import com.wikiponder.touchmap.classes.vpr.VprModelDownload;

/**
 * Shown instead of AI place recognition while the image recognition model is not on the phone: explains
 * the feature and the download size, downloads the model in place with its progress, and ends with
 * a Start button that runs onStart. One dialog walks through all the states (intro, downloading,
 * done, failed) by swapping its text and buttons, so TalkBack users stay in one place. Leaving the
 * activity does not stop the download (see VprModelDownload); opening the dialog again shows it.
 */
public final class VprModelDownloadDialog implements VprModelDownload.Listener {

	private final Activity activity;
	private final Runnable onStart;
	private final AlertDialog dialog;
	private final TextView message;
	private final ProgressBar progress;
	private final String sizeText;
	private VprModelDownload.Result finished;

	public static void show(Activity activity, Runnable onStart) {
		new VprModelDownloadDialog(activity, onStart);
	}

	private VprModelDownloadDialog(Activity activity, Runnable onStart) {
		this.activity = activity;
		this.onStart = onStart;
		sizeText = Formatter.formatShortFileSize(activity, VprModel.MODEL_BYTES);
		View view = LayoutInflater.from(activity).inflate(R.layout.dialog_vpr_model_download, null);
		message = view.findViewById(R.id.vprModelMessage);
		progress = view.findViewById(R.id.vprModelProgress);

		// Button texts are placeholders; every state sets its own in update(). Clicks are wired in
		// the show listener so they don't auto-dismiss the dialog.
		dialog = new AlertDialog.Builder(activity)
				.setTitle(R.string.visual_places_title)
				.setView(view)
				.setCancelable(false)
				.setPositiveButton(R.string.action_download_visual_model, null)
				.setNegativeButton(R.string.action_close, null)
				.create();
		dialog.setOnShowListener(d -> {
			dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> onPositive());
			dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> onNegative());
			update();
		});
		dialog.setOnDismissListener(d -> {
			VprModelDownload running = VprModelDownload.current();
			if (running != null) running.setListener(null);
		});
		dialog.show();
		VprModelDownload running = VprModelDownload.current();
		if (running != null) running.setListener(this);
	}

	private void onPositive() {
		if (finished == VprModelDownload.Result.SUCCESS) {
			dialog.dismiss();
			onStart.run();
		} else {
			finished = null;
			VprModelDownload.start(activity, this);
			update();
			announce(message.getText());
		}
	}

	private void onNegative() {
		VprModelDownload running = VprModelDownload.current();
		if (running != null) {
			running.cancel();
		} else {
			dialog.dismiss();
		}
	}

	@Override
	public void onProgress(int percent) {
		if (!dialog.isShowing()) return;
		progress.setProgress(percent);
		message.setText(activity.getString(R.string.visual_model_downloading, percent));
		// The text changes every percent; TalkBack hears it every 10.
		if (percent > 0 && percent % 10 == 0) announce(activity.getString(R.string.visual_model_percent, percent));
	}

	@Override
	public void onFinished(VprModelDownload.Result result) {
		if (!dialog.isShowing()) return;
		finished = result == VprModelDownload.Result.CANCELLED ? null : result;
		update();
		if (finished != null) announce(message.getText());
		if (finished == VprModelDownload.Result.SUCCESS) {
			Button start = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
			start.requestFocus();
			start.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED);
		}
	}

	/** Sets text and buttons for the current state. */
	private void update() {
		Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
		Button negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
		boolean downloading = VprModelDownload.current() != null;
		progress.setVisibility(downloading ? View.VISIBLE : View.GONE);
		positive.setVisibility(downloading ? View.GONE : View.VISIBLE);
		negative.setVisibility(finished == VprModelDownload.Result.SUCCESS ? View.GONE : View.VISIBLE);
		if (downloading) {
			dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
			message.setText(activity.getString(R.string.visual_model_downloading, progress.getProgress()));
			negative.setText(R.string.action_cancel_visual_model_download);
			return;
		}
		dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		negative.setText(R.string.action_close);
		if (finished == null) {
			message.setText(activity.getString(R.string.visual_model_intro, sizeText));
			positive.setText(R.string.action_download_visual_model);
		} else if (finished == VprModelDownload.Result.SUCCESS) {
			message.setText(R.string.visual_model_download_done);
			positive.setText(R.string.action_start_visual_places);
		} else {
			message.setText(finished == VprModelDownload.Result.NO_SPACE
					? activity.getString(R.string.visual_model_no_space, sizeText)
					: activity.getString(R.string.visual_model_download_failed));
			positive.setText(R.string.action_retry_visual_model_download);
		}
	}

	private void announce(CharSequence text) {
		message.announceForAccessibility(text);
	}
}
