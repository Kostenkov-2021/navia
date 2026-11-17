package com.wikiponder.touchmap.classes.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;

import com.wikiponder.touchmap.MyApplication;
import com.wikiponder.touchmap.R;

import java.time.LocalDate;

/**
 * "Would you like to share the app?" prompt, shown when the user exits the app — at most once a
 * day, and never again once they tick "Don't ask again". "Share" opens the system share sheet with
 * the share link (navia/ landing page, or Play for Ambimap) and a short pitch about the touchable map.
 */
public final class SharePromptDialog {
	private static final String PREF_NEVER_ASK = "share_prompt_never_ask";
	private static final String PREF_LAST_PROMPT_DAY = "share_prompt_last_day";

	private SharePromptDialog() {
	}

	/**
	 * Shows the prompt if one is due. Returns false (and does nothing) when it isn't, so the caller
	 * exits right away; returns true when the dialog was shown, in which case {@code proceed} is
	 * run exactly once when the user has answered it (Share, Not now, or dismissing it).
	 */
	public static boolean showIfDue(Activity activity, Runnable proceed) {
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
		if (prefs.getBoolean(PREF_NEVER_ASK, false)) return false;
		long today = LocalDate.now().toEpochDay();
		if (prefs.getLong(PREF_LAST_PROMPT_DAY, -1) == today) return false;
		// Counted as asked the moment it appears, whatever the answer.
		prefs.edit().putLong(PREF_LAST_PROMPT_DAY, today).apply();

		View view = LayoutInflater.from(activity).inflate(R.layout.dialog_share_prompt, null);
		CheckBox neverAsk = view.findViewById(R.id.share_never_ask);
		Runnable finish = () -> {
			if (neverAsk.isChecked()) prefs.edit().putBoolean(PREF_NEVER_ASK, true).apply();
			proceed.run();
		};

		new AlertDialog.Builder(activity)
				.setTitle(R.string.share_prompt_title)
				.setView(view)
				.setPositiveButton(R.string.share_prompt_share, (dialog, which) -> {
					share(activity);
					finish.run();
				})
				.setNegativeButton(R.string.share_prompt_not_now, (dialog, which) -> finish.run())
				.setOnCancelListener(dialog -> finish.run())
				.show();
		return true;
	}

	/** The same dialog on request (More menu): no "don't ask again" box, no daily-limit bookkeeping. */
	public static void showNow(Activity activity) {
		View view = LayoutInflater.from(activity).inflate(R.layout.dialog_share_prompt, null);
		view.findViewById(R.id.share_never_ask).setVisibility(View.GONE);
		new AlertDialog.Builder(activity)
				.setTitle(R.string.share_prompt_title)
				.setView(view)
				.setPositiveButton(R.string.share_prompt_share, (dialog, which) -> share(activity))
				.setNegativeButton(R.string.share_prompt_not_now, null)
				.show();
	}

	/** Navia's one shared link: on Android it offers the Play app or the web version, elsewhere it
	 * opens the web version (see web/index.html), so the link also works for iPhone users. */
	private static final String NAVIA_SHARE_URL = "https://wikiponder.com/navia/";

	/** Opens the system share sheet with the share link and the pitch. Ambimap has no web version,
	 * so it keeps sharing its Play Store page. */
	public static void share(Activity activity) {
		boolean ambimap = MyApplication.isAmbimap();
		String url = ambimap ? "https://play.google.com/store/apps/details?id=" + activity.getPackageName() : NAVIA_SHARE_URL;
		Intent send = new Intent(Intent.ACTION_SEND);
		send.setType("text/plain");
		send.putExtra(Intent.EXTRA_SUBJECT, activity.getString(R.string.share_subject));
		send.putExtra(Intent.EXTRA_TEXT, activity.getString(ambimap ? R.string.share_text : R.string.share_text_web, url));
		activity.startActivity(Intent.createChooser(send, activity.getString(R.string.share_chooser_title)));
	}
}
