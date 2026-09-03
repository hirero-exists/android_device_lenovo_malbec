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

import android.content.ContentResolver;
import android.os.SystemProperties;
import android.provider.Settings;

final class DisplayTouchMode {
    private static final String HIGH_REPORT_PROPERTY = "persist.sys.touch.high_report_rate";
    private static final String GAME_EDGE_PROPERTY = "persist.sys.touch.game_edge";
    private static final String EDGE_GRID_ZONE_PROPERTY = "persist.sys.touch.edge_grid_zone";
    private static final String HIGH_REPORT_APPLIED_PROPERTY =
            "sys.malbec.touch.high_report_rate_applied";
    private static final String GAME_EDGE_APPLIED_PROPERTY =
            "sys.malbec.touch.game_edge_applied";
    private static final String DEFAULT_EDGE_GRID_ZONE = "16,16,500,500";

    private DisplayTouchMode() {
    }

    static int getRefreshRate(ContentResolver resolver) {
        float peak = Settings.System.getFloat(resolver, Settings.System.PEAK_REFRESH_RATE, 144.0f);
        float min = Settings.System.getFloat(resolver, Settings.System.MIN_REFRESH_RATE, 0.0f);
        if (min == 0.0f) {
            return 0;
        }
        return Math.round(peak);
    }

    static boolean setRefreshRate(ContentResolver resolver, int rate) {
        if (rate == 0) {
            boolean minRemoved = Settings.System.putString(
                    resolver, Settings.System.MIN_REFRESH_RATE, null);
            boolean peakRemoved = Settings.System.putString(
                    resolver, Settings.System.PEAK_REFRESH_RATE, null);
            return minRemoved && peakRemoved;
        }
        float target = (float) rate;
        boolean minSet = Settings.System.putFloat(
                resolver, Settings.System.MIN_REFRESH_RATE, target);
        boolean peakSet = Settings.System.putFloat(
                resolver, Settings.System.PEAK_REFRESH_RATE, target);
        return minSet && peakSet;
    }

    static boolean isHighReportRateEnabled() {
        String applied = SystemProperties.get(HIGH_REPORT_APPLIED_PROPERTY, "");
        return applied.isEmpty()
                ? SystemProperties.getBoolean(HIGH_REPORT_PROPERTY, false)
                : "1".equals(applied);
    }

    static boolean setHighReportRateEnabled(boolean enabled) {
        try {
            SystemProperties.set(HIGH_REPORT_PROPERTY, enabled ? "1" : "0");
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    static boolean isGameEdgeEnabled() {
        String applied = SystemProperties.get(GAME_EDGE_APPLIED_PROPERTY, "");
        return applied.isEmpty()
                ? SystemProperties.getBoolean(GAME_EDGE_PROPERTY, false)
                : "1".equals(applied);
    }

    static boolean setGameEdgeEnabled(boolean enabled) {
        try {
            SystemProperties.set(GAME_EDGE_PROPERTY, enabled ? "1" : "0");
            SystemProperties.set(EDGE_GRID_ZONE_PROPERTY,
                    enabled ? DEFAULT_EDGE_GRID_ZONE : "0,0,0,0");
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
