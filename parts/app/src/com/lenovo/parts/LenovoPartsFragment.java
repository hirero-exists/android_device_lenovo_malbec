/*
 * Copyright (C) 2026 hirero-exists <hirerokazuoa@gmail.com>
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

package com.lenovo.parts;

import android.os.Bundle;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.TwoStatePreference;

import com.android.settingslib.widget.MainSwitchPreference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;

public final class LenovoPartsFragment extends SettingsBasePreferenceFragment
        implements Preference.OnPreferenceChangeListener {
    private static final String KEY_FOLIO_COVER = "folio_cover";
    private static final String KEY_PEN_ENABLED = "pen_enabled";
    private static final String KEY_PEN_WAKEUP = "pen_wakeup";
    private static final String KEY_PEN_SINGLE_ACTION = "pen_single_action";
    private static final String KEY_PEN_DOUBLE_ACTION = "pen_double_action";
    private static final String KEY_PEN_TRIPLE_ACTION = "pen_triple_action";
    private static final String KEY_PEN_LONG_ACTION = "pen_long_action";
    private static final String KEY_PEN_LONG_CLICK_ACTION = "pen_long_click_action";
    private static final String KEY_DOLBY_ENABLED = "dolby_enabled";
    private static final String KEY_DOLBY_PROFILE = "dolby_profile";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.lenovo_parts, rootKey);

        setupPreference(KEY_FOLIO_COVER);
        setupPreference(KEY_PEN_ENABLED);
        setupPreference(KEY_PEN_WAKEUP);
        setupPreference(KEY_PEN_SINGLE_ACTION);
        setupPreference(KEY_PEN_DOUBLE_ACTION);
        setupPreference(KEY_PEN_TRIPLE_ACTION);
        setupPreference(KEY_PEN_LONG_ACTION);
        setupPreference(KEY_PEN_LONG_CLICK_ACTION);
        setupPreference(KEY_DOLBY_ENABLED);
        setupPreference(KEY_DOLBY_PROFILE);

        refreshPreferences();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshPreferences();
    }

    private void setupPreference(String key) {
        Preference preference = findPreference(key);
        if (preference != null) {
            preference.setPersistent(false);
            preference.setOnPreferenceChangeListener(this);
        }
    }

    private void refreshPreferences() {
        MainSwitchPreference folio = findPreference(KEY_FOLIO_COVER);
        if (folio != null) {
            folio.setChecked(FolioMode.isEnabled());
        }

        boolean penEnabled = PenMode.isEnabled();
        MainSwitchPreference pen = findPreference(KEY_PEN_ENABLED);
        if (pen != null) {
            pen.setChecked(penEnabled);
        }

        TwoStatePreference penWakeup = findPreference(KEY_PEN_WAKEUP);
        if (penWakeup != null) {
            penWakeup.setEnabled(penEnabled);
            penWakeup.setChecked(PenMode.isWakeupEnabled());
        }

        refreshPenAction(KEY_PEN_SINGLE_ACTION, PenShortcuts.SINGLE_SETTING, 5);
        refreshPenAction(KEY_PEN_DOUBLE_ACTION, PenShortcuts.DOUBLE_SETTING, 10);
        refreshPenAction(KEY_PEN_TRIPLE_ACTION, PenShortcuts.TRIPLE_SETTING, 2);
        refreshPenAction(KEY_PEN_LONG_ACTION, PenShortcuts.LONG_SETTING, 0);
        refreshPenAction(KEY_PEN_LONG_CLICK_ACTION, PenShortcuts.LONG_CLICK_SETTING, 0);

        MainSwitchPreference dolbyEnabled = findPreference(KEY_DOLBY_ENABLED);
        if (dolbyEnabled != null) {
            dolbyEnabled.setChecked(DolbyMode.isEnabled(requireContext().getContentResolver()));
        }

        ListPreference dolbyProfile = findPreference(KEY_DOLBY_PROFILE);
        if (dolbyProfile != null) {
            dolbyProfile.setValue(Integer.toString(
                    DolbyMode.getProfile(requireContext().getContentResolver())));
        }
    }

    private void refreshPenAction(String preferenceKey, String setting, int defaultAction) {
        ListPreference preference = findPreference(preferenceKey);
        if (preference != null) {
            preference.setValue(Integer.toString(
                    PenShortcuts.getAction(requireContext(), setting, defaultAction)));
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (KEY_FOLIO_COVER.equals(preference.getKey())) {
            return FolioMode.setEnabled((Boolean) newValue);
        } else if (KEY_PEN_ENABLED.equals(preference.getKey())) {
            boolean enabled = (Boolean) newValue;
            if (PenMode.setEnabled(enabled)) {
                Preference penWakeup = findPreference(KEY_PEN_WAKEUP);
                if (penWakeup != null) {
                    penWakeup.setEnabled(enabled);
                }
                return true;
            }
            return false;
        } else if (KEY_PEN_WAKEUP.equals(preference.getKey())) {
            return PenMode.setWakeupEnabled((Boolean) newValue);
        } else if (KEY_PEN_SINGLE_ACTION.equals(preference.getKey())) {
            return setPenAction(PenShortcuts.SINGLE_SETTING, newValue);
        } else if (KEY_PEN_DOUBLE_ACTION.equals(preference.getKey())) {
            return setPenAction(PenShortcuts.DOUBLE_SETTING, newValue);
        } else if (KEY_PEN_TRIPLE_ACTION.equals(preference.getKey())) {
            return setPenAction(PenShortcuts.TRIPLE_SETTING, newValue);
        } else if (KEY_PEN_LONG_ACTION.equals(preference.getKey())) {
            return setPenAction(PenShortcuts.LONG_SETTING, newValue);
        } else if (KEY_PEN_LONG_CLICK_ACTION.equals(preference.getKey())) {
            return setPenAction(PenShortcuts.LONG_CLICK_SETTING, newValue);
        } else if (KEY_DOLBY_ENABLED.equals(preference.getKey())) {
            return DolbyMode.setEnabled(requireContext().getContentResolver(), (Boolean) newValue);
        } else if (KEY_DOLBY_PROFILE.equals(preference.getKey())) {
            return DolbyMode.setProfile(requireContext().getContentResolver(),
                    Integer.parseInt((String) newValue));
        }
        return true;
    }

    private boolean setPenAction(String setting, Object value) {
        return PenShortcuts.setAction(requireContext(), setting, Integer.parseInt((String) value));
    }
}
