package com.wikiponder.touchmap.classes.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;

import com.wikiponder.touchmap.R;

/**
 * One-time notice shown the first time the app's displayed UI language becomes non-English,
 * warning that the translation was AI-generated and may not be fully accurate.
 *
 * <p>Two-step show: {@link #markPendingIfNeeded} is called right after the language preference
 * changes, but AppCompatDelegate.setApplicationLocales() (see MyApplication.applyUiLocale)
 * recreates the hosting Activity almost immediately afterward, which would tear down a dialog
 * shown synchronously right then (WindowLeaked). So this only sets a one-shot flag; the actual
 * show happens from {@link #consumePendingIfNeeded}, called from SettingsActivity's onResume,
 * which by then is running on the freshly-recreated Activity.
 */
public final class TranslationDisclaimerDialog {
	private TranslationDisclaimerDialog() {
	}

	private static final String PREF_KEY_SUPPRESSED = "translation_disclaimer_suppressed";
	private static boolean pending = false;

	/** Call after the preferred-language setting changes. Arms the one-shot flag if the resolved
	 * language is non-English and the user hasn't previously checked "don't show this again". */
	public static void markPendingIfNeeded(Context context, String resolvedLanguage) {
		if ("en".equals(resolvedLanguage)) return;
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
		if (prefs.getBoolean(PREF_KEY_SUPPRESSED, false)) return;
		pending = true;
	}

	/** Call from the Settings activity's onResume. Shows the dialog if armed, then disarms it —
	 * safe to call on every resume, since it is a no-op once the one-shot flag is consumed. */
	public static void consumePendingIfNeeded(Context context) {
		if (!pending) return;
		pending = false;

		View view = LayoutInflater.from(context).inflate(R.layout.dialog_translation_disclaimer, null);
		CheckBox dontShowAgain = view.findViewById(R.id.dont_show_again);

		new AlertDialog.Builder(context)
				.setView(view)
				.setPositiveButton(R.string.translation_disclaimer_ok, (dialog, which) -> {
					if (dontShowAgain.isChecked()) {
						PreferenceManager.getDefaultSharedPreferences(context)
								.edit()
								.putBoolean(PREF_KEY_SUPPRESSED, true)
								.apply();
					}
				})
				.show();
	}
}
