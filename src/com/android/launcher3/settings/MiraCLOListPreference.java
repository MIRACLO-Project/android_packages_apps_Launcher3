/*
 * SPDX-FileCopyrightText: MiraCLO
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.launcher3.settings;

import android.app.AlertDialog;
import android.content.Context;
import android.util.AttributeSet;

import androidx.preference.ListPreference;

/**
 * A list preference that picks its value in a platform single-choice dialog. The AndroidX list
 * dialog needs an AppCompat dialog theme, which the Launcher settings activity does not use.
 */
public class MiraCLOListPreference extends ListPreference {

    public MiraCLOListPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onClick() {
        final CharSequence[] entries = getEntries();
        final CharSequence[] values = getEntryValues();
        final int checked = Math.max(0, findIndexOfValue(getValue()));
        new AlertDialog.Builder(getContext())
                .setTitle(getDialogTitle() != null ? getDialogTitle() : getTitle())
                .setSingleChoiceItems(entries, checked, (dialog, which) -> {
                    final String value = values[which].toString();
                    if (callChangeListener(value)) {
                        setValue(value);
                    }
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
