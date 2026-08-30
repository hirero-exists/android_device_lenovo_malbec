package com.lenovo.parts;

import android.os.Bundle;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;

public final class LenovoPartsActivity extends PreferenceActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.app_name);

        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(this);
        SwitchPreference folio = new SwitchPreference(this);
        folio.setTitle(R.string.folio_title);
        folio.setSummary(R.string.folio_summary);
        folio.setChecked(FolioMode.isEnabled());
        folio.setOnPreferenceChangeListener((preference, newValue) -> {
            FolioMode.apply(this, (Boolean) newValue);
            return true;
        });
        screen.addPreference(folio);
        setPreferenceScreen(screen);
    }
}
