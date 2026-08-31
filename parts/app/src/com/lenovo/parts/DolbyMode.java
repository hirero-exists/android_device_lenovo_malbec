package com.lenovo.parts;

import android.content.ContentResolver;
import android.provider.Settings;

final class DolbyMode {
    private static final String ENABLED_SETTING = "dlb_dap_state";
    private static final String PROFILE_SETTING = "dolby_dap_profile";

    private DolbyMode() {
    }

    static boolean isEnabled(ContentResolver resolver) {
        return Settings.Global.getInt(resolver, ENABLED_SETTING, 1) != 0;
    }

    static void setEnabled(ContentResolver resolver, boolean enabled) {
        Settings.Global.putInt(resolver, ENABLED_SETTING, enabled ? 1 : 0);
    }

    static int getProfile(ContentResolver resolver) {
        return Settings.Global.getInt(resolver, PROFILE_SETTING, 0);
    }

    static void setProfile(ContentResolver resolver, int profile) {
        Settings.Global.putInt(resolver, PROFILE_SETTING, profile);
    }
}
