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
