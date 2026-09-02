/*
 * Copyright (C) 2026 hirero-exists <hirerokazuoa@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Compatible with GNU General Public License, Version 2.0 (GPLv2) or later
 * pursuant to Section 3.3 of the Mozilla Public License, v. 2.0.
 */

package com.lenovo.parts;

import android.app.ActionBar;
import android.os.Bundle;
import android.os.SystemProperties;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;

public final class GamingOverlaySettingsActivity extends CollapsingToolbarBaseActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.gaming_overlay_settings_title);

        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setHomeButtonEnabled(true);
        }

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction().replace(
                    com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                    new SettingsFragment()).commit();
        }
    }

    @Override
    public boolean onNavigateUp() {
        finish();
        return true;
    }

    public static final class SettingsFragment extends SettingsBasePreferenceFragment
            implements Preference.OnPreferenceChangeListener {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.gaming_overlay_settings, rootKey);

            setupPref("gaming_overlay_show_cpu_usage");
            setupPref("gaming_overlay_show_cpu_freq");
            setupPref("gaming_overlay_show_cpu_temp");
            setupPref("gaming_overlay_show_gpu_usage");
            setupPref("gaming_overlay_show_gpu_freq");
            setupPref("gaming_overlay_show_gpu_temp");
            setupPref("gaming_overlay_show_fps");
            setupPref("gaming_overlay_show_total_power");
            setupPref("gaming_overlay_soc_power_mode");
            setupPref("gaming_overlay_show_battery");
            setupPref("gaming_overlay_compact_default");
        }

        private void setupPref(String key) {
            Preference pref = findPreference(key);
            if (pref != null) {
                pref.setOnPreferenceChangeListener(this);
            }
        }

        @Override
        public boolean onPreferenceChange(Preference preference, Object newValue) {
            if ("gaming_overlay_soc_power_mode".equals(preference.getKey())) {
                try {
                    int mode = Integer.parseInt(String.valueOf(newValue));
                    SystemProperties.set("persist.sys.gaming.soc_power_mode", String.valueOf(mode));
                } catch (Exception ignored) {
                }
            }
            return true;
        }
    }
}
