/*
 * Copyright (C) 2026 MiraCLO
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.launcher3.settings;

import android.app.AlertDialog;
import android.content.Context;
import android.util.AttributeSet;

import androidx.preference.ListPreference;

import com.android.launcher3.R;
import com.android.launcher3.icons.MiraCLOIconPacks;

import java.util.LinkedHashMap;
import java.util.Map;

/** Picks one of the installed icon packs, or the system icons. */
public class MiraCLOIconPackPreference extends ListPreference {

    public MiraCLOIconPackPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setDefaultValue("");
        setSummaryProvider(p -> {
            CharSequence entry = ((ListPreference) p).getEntry();
            return entry != null ? entry : context.getString(R.string.miraclo_icon_pack_system);
        });
        reload();
    }

    /** Refreshes the list of installed packs. */
    public void reload() {
        LinkedHashMap<String, CharSequence> packs = MiraCLOIconPacks.installed(getContext());
        CharSequence[] entries = new CharSequence[packs.size() + 1];
        CharSequence[] values = new CharSequence[packs.size() + 1];
        entries[0] = getContext().getString(R.string.miraclo_icon_pack_system);
        values[0] = "";
        int i = 1;
        for (Map.Entry<String, CharSequence> e : packs.entrySet()) {
            entries[i] = e.getValue();
            values[i] = e.getKey();
            i++;
        }
        setEntries(entries);
        setEntryValues(values);
        // A chosen pack that was uninstalled falls back to the system icons.
        if (getValue() != null && findIndexOfValue(getValue()) < 0) setValue("");
    }

    /**
     * Shows the packs in a platform single-choice dialog. The AndroidX list dialog needs an
     * AppCompat dialog theme, which the Launcher settings activity does not use.
     */
    @Override
    protected void onClick() {
        reload();
        final CharSequence[] entries = getEntries();
        final CharSequence[] values = getEntryValues();
        final int checked = Math.max(0, findIndexOfValue(getValue()));
        new AlertDialog.Builder(getContext())
                .setTitle(getDialogTitle())
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
