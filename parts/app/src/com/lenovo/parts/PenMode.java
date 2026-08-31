package com.lenovo.parts;

import android.os.SystemProperties;

final class PenMode {
    private static final String ENABLED_PROPERTY = "persist.sys.pen.enabled";
    private static final String WAKEUP_PROPERTY = "persist.sys.pen.wakeup";

    private PenMode() {
    }

    static boolean isEnabled() {
        return SystemProperties.getBoolean(ENABLED_PROPERTY, true);
    }

    static void setEnabled(boolean enabled) {
        SystemProperties.set(ENABLED_PROPERTY, enabled ? "1" : "0");
    }

    static boolean isWakeupEnabled() {
        return SystemProperties.getBoolean(WAKEUP_PROPERTY, false);
    }

    static void setWakeupEnabled(boolean enabled) {
        SystemProperties.set(WAKEUP_PROPERTY, enabled ? "1" : "0");
    }
}
