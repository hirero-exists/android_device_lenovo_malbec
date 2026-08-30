package com.lenovo.parts;

import android.os.Bundle;

import androidx.preference.Preference;

import com.android.settingslib.widget.MainSwitchPreference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;

public final class LenovoPartsFragment extends SettingsBasePreferenceFragment
        implements Preference.OnPreferenceChangeListener {
    private static final String KEY_FOLIO_COVER = "folio_cover";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.lenovo_parts, rootKey);
        MainSwitchPreference folio = findPreference(KEY_FOLIO_COVER);
        folio.setChecked(FolioMode.isEnabled());
        folio.setOnPreferenceChangeListener(this);
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (KEY_FOLIO_COVER.equals(preference.getKey())) {
            FolioMode.setEnabled((Boolean) newValue);
        }
        return true;
    }
}
