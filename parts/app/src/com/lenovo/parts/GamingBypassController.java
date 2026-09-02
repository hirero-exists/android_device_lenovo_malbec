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
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Locale;

final class GamingBypassController {
    static final String ACTION_TURN_OFF = "com.lenovo.parts.action.TURN_OFF_BYPASS";

    private static final String TAG = "GamingBypass";
    private static final String CHANNEL_ID = "gaming_bypass_channel";
    private static final String WARNING_CHANNEL_ID = "gaming_bypass_warning_channel";
    private static final int NOTIFICATION_ID = 1002;
    private static final int WARNING_NOTIFICATION_ID = 1005;
    private static final int RELEASE_RETRY_MS = 250;
    private static final int RELEASE_RETRY_LIMIT = 12;

    private static final String REQUEST_PROPERTY = "sys.malbec.bypass.requested";
    private static final String HEARTBEAT_PROPERTY = "sys.malbec.bypass.heartbeat";
    private static final String ACTIVE_PROPERTY = "sys.malbec.bypass.active";
    private static final String STATE_PROPERTY = "sys.malbec.bypass.state";
    private static final String USB_ONLINE_PROPERTY = "sys.malbec.power.usb_online";
    private static final String USB_TYPE_PROPERTY = "sys.malbec.power.usb_type";
    private static final String USB_VOLTAGE_PROPERTY = "sys.malbec.power.usb_voltage_uv";
    private static final String USB_CURRENT_PROPERTY = "sys.malbec.power.usb_current_ua";
    private static final String USB_VOLTAGE_MAX_PROPERTY =
            "sys.malbec.power.usb_voltage_max_uv";
    private static final String USB_CURRENT_MAX_PROPERTY =
            "sys.malbec.power.usb_current_max_ua";
    private static final String BATTERY_CAPACITY_PROPERTY =
            "sys.malbec.power.battery_capacity";
    private static final String BATTERY_VOLTAGE_PROPERTY =
            "sys.malbec.power.battery_voltage_uv";
    private static final String BATTERY_CURRENT_PROPERTY =
            "sys.malbec.power.battery_current_ua";
    private static final String BATTERY_TEMP_PROPERTY =
            "sys.malbec.power.battery_temp_tenth_c";
    private static final String CHARGING_ENABLED_PROPERTY =
            "sys.malbec.power.charging_enabled";
    private static final String INPUT_SUSPEND_PROPERTY =
            "sys.malbec.power.battery_input_suspend";

    private static final String SESSION_SETTING = "malbec_bypass_session_active";
    private static final String SESSION_BOOT_SETTING = "malbec_bypass_session_boot";
    private static final String LINEAGE_PRESENT_SETTING = "malbec_bypass_lineage_present";
    private static final String LINEAGE_ENABLED_SETTING = "malbec_bypass_lineage_enabled";

    private static final int STATE_OFF = 0;
    private static final int STATE_ACTIVE = 1;
    private static final int STATE_NO_POWER = 2;
    private static final int STATE_WRITE_FAILED = 3;
    private static final int STATE_LEASE_EXPIRED = 4;
    private static final int STATE_OVERRIDDEN = 5;

    private static GamingBypassController sInstance;

    private final Context mContext;
    private final NotificationManager mNotificationManager;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mRequested;
    private int mReleaseRetries;
    private int mVoltageFaultSamples;

    private final Runnable mTelemetryRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mRequested) {
                return;
            }
            SystemProperties.set(HEARTBEAT_PROPERTY,
                    Integer.toString((int) (SystemClock.elapsedRealtime() / 1000L)));
            boolean requested = SystemProperties.getBoolean(REQUEST_PROPERTY, false);
            int state = SystemProperties.getInt(STATE_PROPERTY, STATE_OFF);
            if (!requested || state == STATE_NO_POWER || state == STATE_WRITE_FAILED
                    || state == STATE_LEASE_EXPIRED || state == STATE_OVERRIDDEN) {
                mRequested = false;
                mNotificationManager.cancel(NOTIFICATION_ID);
                postBackendWarning(state);
                beginControllerRestore();
                return;
            }
            updateHudNotification(state == STATE_ACTIVE);
            mHandler.postDelayed(this, 5000);
        }
    };

    private final Runnable mRestoreRunnable = new Runnable() {
        @Override
        public void run() {
            boolean active = SystemProperties.getBoolean(ACTIVE_PROPERTY, false);
            if (active && mReleaseRetries++ < RELEASE_RETRY_LIMIT) {
                mHandler.postDelayed(this, RELEASE_RETRY_MS);
                return;
            }
            restoreLineageController();
            clearSessionState();
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

        NotificationChannel warningChannel = new NotificationChannel(
                WARNING_CHANNEL_ID,
                mContext.getString(R.string.gaming_bypass_warning_title),
                NotificationManager.IMPORTANCE_HIGH);
        warningChannel.setShowBadge(false);
        mNotificationManager.createNotificationChannel(warningChannel);

        recoverSession();
    }

    static synchronized GamingBypassController getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new GamingBypassController(context);
        }
        return sInstance;
    }

    static boolean isPowerConnected(Context context) {
        if (SystemProperties.getBoolean(USB_ONLINE_PROPERTY, false)) {
            return true;
        }
        Intent batteryIntent = context.registerReceiver(
                null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return batteryIntent != null && batteryIntent.getIntExtra(
                BatteryManager.EXTRA_PLUGGED, 0) != 0;
    }

    boolean isBypassEnabled() {
        return mRequested && SystemProperties.getBoolean(REQUEST_PROPERTY, false);
    }

    synchronized boolean setBypassEnabled(boolean enabled) {
        if (!enabled) {
            stopBypass();
            return true;
        }
        if (mRequested) {
            return true;
        }
        if (!isPowerConnected(mContext) || !suspendLineageController()) {
            return false;
        }

        int bootCount = Settings.Global.getInt(
                mContext.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        Settings.Secure.putInt(mContext.getContentResolver(), SESSION_SETTING, 1);
        Settings.Secure.putInt(
                mContext.getContentResolver(), SESSION_BOOT_SETTING, bootCount);

        mRequested = true;
        mVoltageFaultSamples = 0;
        SystemProperties.set(HEARTBEAT_PROPERTY,
                Integer.toString((int) (SystemClock.elapsedRealtime() / 1000L)));
        SystemProperties.set(REQUEST_PROPERTY, "1");
        mHandler.removeCallbacks(mTelemetryRunnable);
        mHandler.postDelayed(mTelemetryRunnable, 1000);
        updateHudNotification(false);
        return true;
    }

    private synchronized void stopBypass() {
        mRequested = false;
        SystemProperties.set(REQUEST_PROPERTY, "0");
        SystemProperties.set(HEARTBEAT_PROPERTY, "0");
        mHandler.removeCallbacks(mTelemetryRunnable);
        mNotificationManager.cancel(NOTIFICATION_ID);
        mNotificationManager.cancel(WARNING_NOTIFICATION_ID);
        beginControllerRestore();
    }

    private void beginControllerRestore() {
        mReleaseRetries = 0;
        mHandler.removeCallbacks(mRestoreRunnable);
        mHandler.postDelayed(mRestoreRunnable, RELEASE_RETRY_MS);
    }

    private void recoverSession() {
        boolean session = Settings.Secure.getInt(
                mContext.getContentResolver(), SESSION_SETTING, 0) != 0;
        int storedBoot = Settings.Secure.getInt(
                mContext.getContentResolver(), SESSION_BOOT_SETTING, -2);
        int currentBoot = Settings.Global.getInt(
                mContext.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        boolean requested = SystemProperties.getBoolean(REQUEST_PROPERTY, false);
        if (session && storedBoot == currentBoot && requested) {
            mRequested = true;
            mHandler.post(mTelemetryRunnable);
            return;
        }
        if (requested) {
            SystemProperties.set(REQUEST_PROPERTY, "0");
            SystemProperties.set(HEARTBEAT_PROPERTY, "0");
        }
        if (session) {
            beginControllerRestore();
        }
    }

    private boolean suspendLineageController() {
        try {
            Class<?> healthClass = Class.forName("lineageos.health.HealthInterface");
            Method getInstance = healthClass.getMethod("getInstance", Context.class);
            Object health = getInstance.invoke(null, mContext);
            Method getEnabled = healthClass.getMethod("getChargingControlEnabled");
            Method setEnabled = healthClass.getMethod(
                    "setChargingControlEnabled", boolean.class);
            boolean wasEnabled = (Boolean) getEnabled.invoke(health);
            Settings.Secure.putInt(
                    mContext.getContentResolver(), LINEAGE_PRESENT_SETTING, 1);
            Settings.Secure.putInt(mContext.getContentResolver(),
                    LINEAGE_ENABLED_SETTING, wasEnabled ? 1 : 0);
            if (wasEnabled && !(Boolean) setEnabled.invoke(health, false)) {
                return false;
            }
            return true;
        } catch (ClassNotFoundException e) {
            Settings.Secure.putInt(
                    mContext.getContentResolver(), LINEAGE_PRESENT_SETTING, 0);
            Settings.Secure.putInt(
                    mContext.getContentResolver(), LINEAGE_ENABLED_SETTING, 0);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.e(TAG, "Unable to suspend ROM charging control", e);
            return false;
        }
    }

    private void restoreLineageController() {
        boolean present = Settings.Secure.getInt(
                mContext.getContentResolver(), LINEAGE_PRESENT_SETTING, 0) != 0;
        boolean wasEnabled = Settings.Secure.getInt(
                mContext.getContentResolver(), LINEAGE_ENABLED_SETTING, 0) != 0;
        if (!present || !wasEnabled) {
            return;
        }
        try {
            Class<?> healthClass = Class.forName("lineageos.health.HealthInterface");
            Method getInstance = healthClass.getMethod("getInstance", Context.class);
            Object health = getInstance.invoke(null, mContext);
            Method setEnabled = healthClass.getMethod(
                    "setChargingControlEnabled", boolean.class);
            if (!(Boolean) setEnabled.invoke(health, true)) {
                Log.e(TAG, "ROM charging control restore was rejected");
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.e(TAG, "Unable to restore ROM charging control", e);
        }
    }

    private void clearSessionState() {
        Settings.Secure.putInt(mContext.getContentResolver(), SESSION_SETTING, 0);
        Settings.Secure.putInt(
                mContext.getContentResolver(), LINEAGE_PRESENT_SETTING, 0);
        Settings.Secure.putInt(
                mContext.getContentResolver(), LINEAGE_ENABLED_SETTING, 0);
    }

    private void updateHudNotification(boolean active) {
        long usbVoltageUv = SystemProperties.getLong(USB_VOLTAGE_PROPERTY, 0);
        long usbCurrentUa = SystemProperties.getLong(USB_CURRENT_PROPERTY, 0);
        long usbVoltageMaxUv = SystemProperties.getLong(USB_VOLTAGE_MAX_PROPERTY, 0);
        long usbCurrentMaxUa = SystemProperties.getLong(USB_CURRENT_MAX_PROPERTY, 0);
        long batteryVoltageUv = SystemProperties.getLong(BATTERY_VOLTAGE_PROPERTY, 0);
        long batteryCurrentUa = SystemProperties.getLong(BATTERY_CURRENT_PROPERTY, 0);
        int batteryCapacity = SystemProperties.getInt(BATTERY_CAPACITY_PROPERTY, 0);
        int batteryTempTenthC = SystemProperties.getInt(BATTERY_TEMP_PROPERTY, 0);
        boolean chargingEnabled = SystemProperties.getBoolean(
                CHARGING_ENABLED_PROPERTY, true);
        boolean inputSuspend = SystemProperties.getBoolean(INPUT_SUSPEND_PROPERTY, false);
        String usbType = SystemProperties.get(USB_TYPE_PROPERTY, "Unknown");

        double usbV = usbVoltageUv / 1000000.0;
        double usbA = Math.abs(usbCurrentUa) / 1000000.0;
        double usbW = usbV * usbA;
        double batteryV = batteryVoltageUv / 1000000.0;
        double batteryA = batteryCurrentUa / 1000000.0;
        double tempC = batteryTempTenthC / 10.0;

        Intent batteryIntent = mContext.registerReceiver(
                null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int health = batteryIntent == null ? BatteryManager.BATTERY_HEALTH_UNKNOWN
                : batteryIntent.getIntExtra(BatteryManager.EXTRA_HEALTH,
                        BatteryManager.BATTERY_HEALTH_UNKNOWN);
        int status = batteryIntent == null ? BatteryManager.BATTERY_STATUS_UNKNOWN
                : batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS,
                        BatteryManager.BATTERY_STATUS_UNKNOWN);
        String healthText = batteryHealthText(health);
        String statusText = batteryStatusText(status);

        String collapsed = String.format(Locale.US,
                "%s • %.2f V • %.2f A • %.1f W • Battery %.1f°C",
                usbType, usbV, usbA, usbW, tempC);
        String expanded = String.format(Locale.US,
                "State: %s\nCharger: %s, %.3f V, %.3f A, %.2f W input\n"
                        + "Negotiated limit: %.3f V, %.3f A\n"
                        + "Battery: %d%%, %.3f V, %+.3f A, %.1f°C, %s, %s\n"
                        + "charging_enabled=%d, input_suspend=%d",
                active ? "Stable" : "Starting", usbType, usbV, usbA, usbW,
                usbVoltageMaxUv / 1000000.0, usbCurrentMaxUa / 1000000.0,
                batteryCapacity, batteryV, batteryA, tempC, healthText, statusText,
                chargingEnabled ? 1 : 0, inputSuspend ? 1 : 0);

        PendingIntent turnOffPendingIntent = PendingIntent.getBroadcast(
                mContext, 0,
                new Intent(mContext, GamingBypassReceiver.class).setAction(ACTION_TURN_OFF),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent openPartsPendingIntent = PendingIntent.getActivity(
                mContext, 0, new Intent(mContext, LenovoPartsActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Action turnOffAction = new Notification.Action.Builder(
                null, mContext.getString(R.string.gaming_bypass_turn_off),
                turnOffPendingIntent).build();

        Notification notification = new Notification.Builder(mContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_lenovo_parts)
                .setContentTitle(mContext.getString(R.string.gaming_bypass_title))
                .setContentText(collapsed)
                .setStyle(new Notification.BigTextStyle().bigText(expanded))
                .setContentIntent(openPartsPendingIntent)
                .addAction(turnOffAction)
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .build();
        mNotificationManager.notify(NOTIFICATION_ID, notification);

        boolean voltageFault = usbVoltageMaxUv > 0 && usbVoltageUv > 0
                && (usbVoltageUv < usbVoltageMaxUv * 0.90
                        || usbVoltageUv > usbVoltageMaxUv * 1.10);
        mVoltageFaultSamples = voltageFault ? mVoltageFaultSamples + 1 : 0;
        if (mVoltageFaultSamples >= 3 || inputSuspend
                || health == BatteryManager.BATTERY_HEALTH_OVERHEAT
                || health == BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE
                || health == BatteryManager.BATTERY_HEALTH_DEAD) {
            postSafetyWarning(voltageFault, inputSuspend, healthText);
        } else {
            mNotificationManager.cancel(WARNING_NOTIFICATION_ID);
        }
    }

    private void postBackendWarning(int state) {
        int text;
        if (state == STATE_NO_POWER) {
            text = R.string.gaming_bypass_power_lost;
        } else if (state == STATE_LEASE_EXPIRED) {
            text = R.string.gaming_bypass_lease_expired;
        } else if (state == STATE_OVERRIDDEN) {
            text = R.string.gaming_bypass_overridden;
        } else {
            text = R.string.gaming_bypass_backend_error;
        }
        postWarning(mContext.getString(text));
    }

    private void postSafetyWarning(boolean voltageFault, boolean inputSuspend,
            String healthText) {
        String text;
        if (voltageFault) {
            text = mContext.getString(R.string.gaming_bypass_voltage_warning);
        } else if (inputSuspend) {
            text = mContext.getString(R.string.gaming_bypass_input_suspended);
        } else {
            text = mContext.getString(
                    R.string.gaming_bypass_battery_health_warning, healthText);
        }
        postWarning(text);
    }

    private void postWarning(String text) {
        PendingIntent openPartsPendingIntent = PendingIntent.getActivity(
                mContext, 1, new Intent(mContext, LenovoPartsActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(mContext, WARNING_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_lenovo_parts)
                .setContentTitle(mContext.getString(R.string.gaming_bypass_warning_title))
                .setContentText(text)
                .setContentIntent(openPartsPendingIntent)
                .setAutoCancel(true)
                .build();
        mNotificationManager.notify(WARNING_NOTIFICATION_ID, notification);
    }

    private static String batteryHealthText(int health) {
        return switch (health) {
            case BatteryManager.BATTERY_HEALTH_GOOD -> "Good";
            case BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat";
            case BatteryManager.BATTERY_HEALTH_DEAD -> "Dead";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over-voltage";
            case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure";
            case BatteryManager.BATTERY_HEALTH_COLD -> "Cold";
            default -> "Unknown";
        };
    }

    private static String batteryStatusText(int status) {
        return switch (status) {
            case BatteryManager.BATTERY_STATUS_CHARGING -> "Charging";
            case BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging";
            case BatteryManager.BATTERY_STATUS_FULL -> "Full";
            default -> "Unknown";
        };
    }
}
