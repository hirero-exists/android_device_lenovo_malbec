/*
 * Copyright (C) 2026 hirero-exists <hirerokazuoa@gmail.com>
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 * Compatible with GNU General Public License, Version 2.0 (GPLv2) or later
 * pursuant to Section 3.3 of the Mozilla Public License, v. 2.0.
 */
package com.lenovo.parts;

import android.content.Context;
import android.os.SystemProperties;
import android.provider.Settings;
import android.view.WindowManager;

final class PenPreferences {
    static final String LEFT = "malbec_pen_corner_left";
    static final String RIGHT = "malbec_pen_corner_right";
    static final String HELD_NOTE = "malbec_pen_held_note";
    static final String SCREEN_OFF = "malbec_pen_screen_off_note";
    static final String REMOTE = "malbec_pen_remote";
    static final String SOUND = "malbec_pen_sound";
    static final String LOST_ALERT = "malbec_pen_lost_alert";
    static final String GUARD_APPS = "malbec_pen_guard_apps";
    static final String PALM = "malbec_pen_palm_enabled";
    static final String PALM_DYNAMIC = "malbec_pen_palm_dynamic";
    static final String PALM_APPS = "malbec_pen_palm_apps";
    static final String REFRESH_CAP = "malbec_pen_refresh_cap";

    static int get(Context c, String key, int fallback) {
        return Settings.Secure.getInt(c.getContentResolver(), key, fallback);
    }
    static boolean put(Context c, String key, int value) {
        boolean success = Settings.Secure.putInt(c.getContentResolver(), key, value);
        if (success && (LEFT.equals(key) || RIGHT.equals(key))) publishCorners(c);
        return success;
    }
    static void publishCorners(Context c) {
        WindowManager windows = c.getSystemService(WindowManager.class);
        if (windows == null) return;
        android.graphics.Rect bounds = windows.getCurrentWindowMetrics().getBounds();
        int threshold = Math.max(1, Math.min(Math.round(48 * c.getResources().getDisplayMetrics().density),
                Math.min(bounds.width(), bounds.height()) / 4 - 1));
        int left = get(c, LEFT, 0), right = get(c, RIGHT, 0);
        int mask = (left >= 1 && left <= 3 ? 1 : 0) | (right >= 1 && right <= 3 ? 2 : 0);
        SystemProperties.set("sys.malbec.pen.corner_config", mask + "," + threshold + "," + threshold);
    }
}
