package com.lenovo.parts;

import android.os.SystemProperties;

final class FolioMode {
    private static final String ENABLED_PROPERTY = "persist.sys.folio.enabled";
    private FolioMode() {
    }

    static boolean isEnabled() {
        return SystemProperties.getBoolean(ENABLED_PROPERTY, true);
    }

    static void setEnabled(boolean enabled) {
        SystemProperties.set(ENABLED_PROPERTY, enabled ? "1" : "0");
    }
}
