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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.Locale;

final class GamingBypassController {
    static final String ACTION_TURN_OFF = "com.lenovo.parts.action.TURN_OFF_BYPASS";
    private static final String TAG = "GamingBypass";
    private static final String CHANNEL_ID = "gaming_bypass_channel";
    private static final int NOTIFICATION_ID = 1002;

    private static final String CHARGING_ENABLED_PATH =
            "/sys/class/power_supply/battery/charging_enabled";
    private static final String USB_ONLINE_PATH =
            "/sys/class/power_supply/usb/online";
    private static final String USB_VOLTAGE_PATH =
            "/sys/class/power_supply/usb/voltage_now";
    private static final String USB_CURRENT_PATH =
            "/sys/class/power_supply/usb/current_now";
    private static final String BATTERY_CAPACITY_PATH =
            "/sys/class/power_supply/battery/capacity";
    private static final String BATTERY_TEMP_PATH =
            "/sys/class/power_supply/battery/temp";
    private static final String BATTERY_VOLTAGE_PATH =
            "/sys/class/power_supply/battery/voltage_now";
    private static final String BATTERY_CURRENT_PATH =
            "/sys/class/power_supply/battery/current_now";

    private static GamingBypassController sInstance;

    private final Context mContext;
    private final NotificationManager mNotificationManager;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mBypassActive = false;

    private final Runnable mTelemetryRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mBypassActive) {
                return;
            }
            if (!isPowerConnected(mContext)) {
                setBypassEnabled(false);
                return;
            }
            updateHudNotification();
            mHandler.postDelayed(this, 5000);
        }
    };

    private GamingBypassController(Context context) {
        mContext = context.getApplicationContext();
        mNotificationManager = mContext.getSystemService(NotificationManager.class);

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                mContext.getString(R.string.gaming_bypass_title),
                NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        channel.enableLights(false);
        channel.enableVibration(false);
        channel.setSound(null, null);
        mNotificationManager.createNotificationChannel(channel);
    }

    static synchronized GamingBypassController getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new GamingBypassController(context);
        }
        return sInstance;
    }

    private static String readSysfs(String path) {
        File file = new File(path);
        if (!file.exists()) {
            return null;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            return reader.readLine();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean writeSysfs(String path, String value) {
        try (FileWriter writer = new FileWriter(path)) {
            writer.write(value);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Unable to write " + path + ": " + e.getMessage());
            return false;
        }
    }

    static boolean isPowerConnected(Context context) {
        BatteryManager bm = context.getSystemService(BatteryManager.class);
        if (bm != null && bm.isCharging()) {
            return true;
        }
        Intent batteryIntent = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (batteryIntent != null) {
            int plugged = batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            if (plugged != 0) {
                return true;
            }
        }
        String online = readSysfs(USB_ONLINE_PATH);
        if ("1".equals(online)) {
            return true;
        }
        long voltageUv = parseLongSafe(readSysfs(USB_VOLTAGE_PATH), 0);
        return voltageUv > 3000000;
    }

    boolean isBypassEnabled() {
        return mBypassActive;
    }

    synchronized boolean setBypassEnabled(boolean enabled) {
        if (enabled) {
            if (!isPowerConnected(mContext)) {
                return false;
            }
            if (!writeSysfs(CHARGING_ENABLED_PATH, "0")) {
                return false;
            }
            mBypassActive = true;
            mHandler.removeCallbacks(mTelemetryRunnable);
            mHandler.post(mTelemetryRunnable);
            return true;
        } else {
            writeSysfs(CHARGING_ENABLED_PATH, "1");
            mBypassActive = false;
            mHandler.removeCallbacks(mTelemetryRunnable);
            mNotificationManager.cancel(NOTIFICATION_ID);
            return true;
        }
    }

    private void updateHudNotification() {
        long usbVoltageUv = parseLongSafe(readSysfs(USB_VOLTAGE_PATH), 0);
        long usbCurrentUa = parseLongSafe(readSysfs(USB_CURRENT_PATH), 0);
        int batteryCapacity = (int) parseLongSafe(readSysfs(BATTERY_CAPACITY_PATH), 0);
        long batteryTempTenthC = parseLongSafe(readSysfs(BATTERY_TEMP_PATH), 0);

        double usbV = usbVoltageUv / 1000000.0;
        double usbA = Math.abs(usbCurrentUa) / 1000000.0;
        double usbW = usbV * usbA;
        double tempC = batteryTempTenthC / 10.0;

        String hudSummary = String.format(
                Locale.US,
                "%s: %.2fV, %.2fA (%.1fW) | %s: %d%%, %.1f\u00b0C",
                mContext.getString(R.string.hud_charger_input),
                usbV,
                usbA,
                usbW,
                mContext.getString(R.string.hud_battery_status),
                batteryCapacity,
                tempC);

        Intent turnOffIntent = new Intent(mContext, GamingBypassReceiver.class);
        turnOffIntent.setAction(ACTION_TURN_OFF);
        PendingIntent turnOffPendingIntent = PendingIntent.getBroadcast(
                mContext, 0, turnOffIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent openPartsIntent = new Intent(mContext, LenovoPartsActivity.class);
        PendingIntent openPartsPendingIntent = PendingIntent.getActivity(
                mContext, 0, openPartsIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Action turnOffAction = new Notification.Action.Builder(
                null,
                mContext.getString(R.string.gaming_bypass_turn_off),
                turnOffPendingIntent)
                .build();

        Notification notification = new Notification.Builder(mContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_lenovo_parts)
                .setContentTitle(mContext.getString(R.string.gaming_bypass_title))
                .setContentText(hudSummary)
                .setContentIntent(openPartsPendingIntent)
                .addAction(turnOffAction)
                .setOngoing(true)
                .setAutoCancel(false)
                .build();

        mNotificationManager.notify(NOTIFICATION_ID, notification);
    }

    private static long parseLongSafe(String str, long fallback) {
        if (str == null || str.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Long.parseLong(str.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
