package com.lenovo.parts;

import android.content.Context;
import android.os.SystemProperties;
import android.provider.Settings;

final class FolioMode {
    private static final String ENABLED_PROPERTY = "persist.sys.folio.enabled";
    private static final String STOCK_SETTING = "zui_lid_enable";

    private FolioMode() {
    }

    static boolean isEnabled() {
        return SystemProperties.getBoolean(ENABLED_PROPERTY, true);
    }

    static void apply(Context context, boolean enabled) {
        int value = enabled ? 1 : 0;
        SystemProperties.set(ENABLED_PROPERTY, Integer.toString(value));
        Settings.Global.putInt(context.getContentResolver(), Settings.Global.LID_BEHAVIOR, value);
        Settings.System.putInt(context.getContentResolver(), STOCK_SETTING, value);
    }
}
