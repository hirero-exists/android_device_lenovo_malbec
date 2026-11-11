/*
 * Copyright (C) 2026 hirero-exists <hirerokazuoa@gmail.com>
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 * Compatible with GNU General Public License, Version 2.0 (GPLv2) or later
 * pursuant to Section 3.3 of the Mozilla Public License, v. 2.0.
 */
package com.lenovo.parts;

import android.app.ActionBar;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.provider.Settings;
import androidx.preference.MultiSelectListPreference;
import androidx.preference.Preference;
import androidx.preference.TwoStatePreference;
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

public final class PenPalmActivity extends CollapsingToolbarBaseActivity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(R.string.pen_palm_title);
        ActionBar bar = getActionBar();
        if (bar != null) bar.setDisplayHomeAsUpEnabled(true);
        if (state == null) getSupportFragmentManager().beginTransaction().replace(
                com.android.settingslib.collapsingtoolbar.R.id.content_frame, new PalmFragment()).commit();
    }
    @Override public boolean onNavigateUp() { finish(); return true; }

    public static final class PalmFragment extends SettingsBasePreferenceFragment {
        private final Consumer<String> mStatus = status -> {
            Preference preference = findPreference("palm_status");
            if (preference != null) preference.setSummary(status);
        };
        @Override public void onCreatePreferences(Bundle state, String root) {
            setPreferencesFromResource(R.xml.pen_palm_settings, root);
            for (String key : new String[] { "palm_enabled", "palm_dynamic", "palm_apps" }) {
                findPreference(key).setPersistent(false);
                findPreference(key).setOnPreferenceChangeListener((preference, value) -> {
                    switch (key) {
                        case "palm_enabled": return PenPreferences.put(requireContext(), PenPreferences.PALM, (Boolean) value ? 1 : 0);
                        case "palm_dynamic": return PenPreferences.put(requireContext(), PenPreferences.PALM_DYNAMIC, (Boolean) value ? 1 : 0);
                        default: return Settings.Secure.putString(requireContext().getContentResolver(),
                                PenPreferences.PALM_APPS, String.join(",", (Set<String>) value));
                    }
                });
            }
            TreeMap<String, String> apps = new TreeMap<>();
            PackageManager packages = requireContext().getPackageManager();
            for (ResolveInfo info : packages.queryIntentActivities(new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
                apps.put(info.activityInfo.packageName, info.loadLabel(packages).toString());
            }
            // Preserve selected defaults even if an app is currently not installed.
            for (String name : PenPalmController.selectedApps(requireContext())) apps.putIfAbsent(name, name);
            MultiSelectListPreference selected = findPreference("palm_apps");
            selected.setEntryValues(apps.keySet().toArray(new String[0]));
            selected.setEntries(apps.values().toArray(new String[0]));
        }
        @Override public void onResume() {
            super.onResume();
            ((TwoStatePreference) findPreference("palm_enabled")).setChecked(PenPreferences.get(requireContext(), PenPreferences.PALM, 0) != 0);
            ((TwoStatePreference) findPreference("palm_dynamic")).setChecked(PenPreferences.get(requireContext(), PenPreferences.PALM_DYNAMIC, 1) != 0);
            ((MultiSelectListPreference) findPreference("palm_apps")).setValues(PenPalmController.selectedApps(requireContext()));
            PenPalmController.get(requireContext()).observe(mStatus);
        }
        @Override public void onPause() {
            PenPalmController.get(requireContext()).unobserve(mStatus);
            super.onPause();
        }
    }
}
