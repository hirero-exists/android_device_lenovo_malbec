package com.lenovo.parts;

import android.os.Bundle;

import androidx.preference.Preference;

import com.android.settingslib.widget.MainSwitchPreference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;

public final class LenovoPartsFragment extends SettingsBasePreferenceFragment
        implements Preference.OnPreferenceChangeListener {
    private static final String KEY_FOLIO_COVER = "folio_cover";
    private static final String KEY_PEN_ENABLED = "pen_enabled";
    private static final String KEY_PEN_WAKEUP = "pen_wakeup";
    private static final String KEY_PEN_PRIMARY_ACTION = "pen_primary_action";
    private static final String KEY_PEN_SECONDARY_ACTION = "pen_secondary_action";
    private static final String KEY_PEN_TERTIARY_ACTION = "pen_tertiary_action";
    private static final String KEY_PEN_TAIL_ACTION = "pen_tail_action";
    private static final String KEY_DOLBY_ENABLED = "dolby_enabled";
    private static final String KEY_DOLBY_PROFILE = "dolby_profile";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.lenovo_parts, rootKey);
        MainSwitchPreference folio = findPreference(KEY_FOLIO_COVER);
        folio.setChecked(FolioMode.isEnabled());
        folio.setOnPreferenceChangeListener(this);

        MainSwitchPreference penEnabled = findPreference(KEY_PEN_ENABLED);
        penEnabled.setChecked(PenMode.isEnabled());
        penEnabled.setOnPreferenceChangeListener(this);

        Preference penWakeup = findPreference(KEY_PEN_WAKEUP);
        penWakeup.setEnabled(PenMode.isEnabled());
        ((androidx.preference.TwoStatePreference) penWakeup)
                .setChecked(PenMode.isWakeupEnabled());
        penWakeup.setOnPreferenceChangeListener(this);

        configurePenAction(KEY_PEN_PRIMARY_ACTION, PenShortcuts.PRIMARY_SETTING, 5);
        configurePenAction(KEY_PEN_SECONDARY_ACTION, PenShortcuts.SECONDARY_SETTING, 10);
        configurePenAction(KEY_PEN_TERTIARY_ACTION, PenShortcuts.TERTIARY_SETTING, 2);
        configurePenAction(KEY_PEN_TAIL_ACTION, PenShortcuts.TAIL_SETTING, 0);

        MainSwitchPreference dolbyEnabled = findPreference(KEY_DOLBY_ENABLED);
        dolbyEnabled.setChecked(DolbyMode.isEnabled(requireContext().getContentResolver()));
        dolbyEnabled.setOnPreferenceChangeListener(this);

        androidx.preference.ListPreference dolbyProfile = findPreference(KEY_DOLBY_PROFILE);
        dolbyProfile.setValue(Integer.toString(
                DolbyMode.getProfile(requireContext().getContentResolver())));
        dolbyProfile.setOnPreferenceChangeListener(this);
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (KEY_FOLIO_COVER.equals(preference.getKey())) {
            FolioMode.setEnabled((Boolean) newValue);
        } else if (KEY_PEN_ENABLED.equals(preference.getKey())) {
            PenMode.setEnabled((Boolean) newValue);
            Preference penWakeup = findPreference(KEY_PEN_WAKEUP);
            penWakeup.setEnabled((Boolean) newValue);
        } else if (KEY_PEN_WAKEUP.equals(preference.getKey())) {
            PenMode.setWakeupEnabled((Boolean) newValue);
        } else if (KEY_PEN_PRIMARY_ACTION.equals(preference.getKey())) {
            setPenAction(PenShortcuts.PRIMARY_SETTING, newValue);
        } else if (KEY_PEN_SECONDARY_ACTION.equals(preference.getKey())) {
            setPenAction(PenShortcuts.SECONDARY_SETTING, newValue);
        } else if (KEY_PEN_TERTIARY_ACTION.equals(preference.getKey())) {
            setPenAction(PenShortcuts.TERTIARY_SETTING, newValue);
        } else if (KEY_PEN_TAIL_ACTION.equals(preference.getKey())) {
            setPenAction(PenShortcuts.TAIL_SETTING, newValue);
        } else if (KEY_DOLBY_ENABLED.equals(preference.getKey())) {
            DolbyMode.setEnabled(requireContext().getContentResolver(), (Boolean) newValue);
        } else if (KEY_DOLBY_PROFILE.equals(preference.getKey())) {
            DolbyMode.setProfile(requireContext().getContentResolver(),
                    Integer.parseInt((String) newValue));
        }
        return true;
    }

    private void configurePenAction(String preferenceKey, String setting, int defaultAction) {
        androidx.preference.ListPreference preference = findPreference(preferenceKey);
        preference.setValue(Integer.toString(
                PenShortcuts.getAction(requireContext(), setting, defaultAction)));
        preference.setOnPreferenceChangeListener(this);
    }

    private void setPenAction(String setting, Object value) {
        PenShortcuts.setAction(requireContext(), setting, Integer.parseInt((String) value));
    }
}
