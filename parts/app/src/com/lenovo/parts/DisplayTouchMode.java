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

import android.content.ContentResolver;
import android.os.SystemProperties;
import android.provider.Settings;

final class DisplayTouchMode {
    private static final String HIGH_REPORT_PROPERTY = "persist.sys.touch.high_report_rate";
    private static final String GAME_EDGE_PROPERTY = "persist.sys.touch.game_edge";
    private static final String HIGH_REPORT_APPLIED_PROPERTY =
            "sys.malbec.touch.high_report_rate_applied";
    private static final String GAME_EDGE_APPLIED_PROPERTY =
            "sys.malbec.touch.game_edge_applied";

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
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
