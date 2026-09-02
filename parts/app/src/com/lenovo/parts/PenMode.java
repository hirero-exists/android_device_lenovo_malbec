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

import android.os.SystemProperties;

final class PenMode {
    private static final String ENABLED_PROPERTY = "persist.sys.pen.enabled";
    private static final String WAKEUP_PROPERTY = "persist.sys.pen.wakeup";
    private static final String TOOLBAR_PROPERTY = "persist.sys.pen.toolbar";

    private PenMode() {
    }

    static boolean isEnabled() {
        return SystemProperties.getBoolean(ENABLED_PROPERTY, true);
    }

    static boolean setEnabled(boolean enabled) {
        try {
            SystemProperties.set(ENABLED_PROPERTY, enabled ? "1" : "0");
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    static boolean isWakeupEnabled() {
        return SystemProperties.getBoolean(WAKEUP_PROPERTY, false);
    }

    static boolean setWakeupEnabled(boolean enabled) {
        try {
            SystemProperties.set(WAKEUP_PROPERTY, enabled ? "1" : "0");
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    static boolean isToolbarEnabled() {
        return SystemProperties.getBoolean(TOOLBAR_PROPERTY, false);
    }

    static boolean setToolbarEnabled(boolean enabled) {
        try {
            SystemProperties.set(TOOLBAR_PROPERTY, enabled ? "1" : "0");
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
