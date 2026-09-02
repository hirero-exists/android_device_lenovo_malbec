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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemProperties;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.preference.PreferenceManager;

public final class GamingOverlayService extends Service {
    private static final String CHANNEL_ID = "gaming_overlay_channel";
    private static final int NOTIFICATION_ID = 2001;

    private static final String PROP_OVERLAY_ACTIVE = "sys.malbec.perf.overlay_active";
    private static final String PROP_CPU_USAGE = "sys.malbec.perf.cpu_usage";
    private static final String PROP_CPU_FREQ = "sys.malbec.perf.cpu_freq_mhz";
    private static final String PROP_CPU_TEMP = "sys.malbec.perf.cpu_temp_c";
    private static final String PROP_CPU_POWER = "sys.malbec.perf.cpu_power_mw";
    private static final String PROP_GPU_USAGE = "sys.malbec.perf.gpu_usage";
    private static final String PROP_GPU_FREQ = "sys.malbec.perf.gpu_freq_mhz";
    private static final String PROP_GPU_TEMP = "sys.malbec.perf.gpu_temp_c";
    private static final String PROP_GPU_POWER = "sys.malbec.perf.gpu_power_mw";
    private static final String PROP_SOC_POWER = "sys.malbec.perf.soc_power_mw";
    private static final String PROP_POWER_TOTAL = "sys.malbec.perf.power_mw";
    private static final String PROP_BATTERY_CAP = "sys.malbec.power.battery_capacity";
    private static final String PROP_BYPASS_ACTIVE = "sys.malbec.bypass.active";

    private static volatile boolean sIsActive = false;

    private WindowManager mWindowManager;
    private WindowManager.LayoutParams mParams;
    private LinearLayout mRootView;
    private TextView mCompactText;
    private LinearLayout mExpandedContainer;
    private TextView mExpandedStatsText;
    private Button mBypassToggleBtn;
    private boolean mExpanded = false;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private SharedPreferences mPrefs;

    private final Runnable mUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            updateMetrics();
            mHandler.postDelayed(this, 500);
        }
    };

    public static boolean isOverlayActive() {
        return sIsActive;
    }

    public static void startOverlay(Context context) {
        Intent intent = new Intent(context, GamingOverlayService.class);
        context.startForegroundService(intent);
    }

    public static void stopOverlay(Context context) {
        Intent intent = new Intent(context, GamingOverlayService.class);
        context.stopService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sIsActive = true;
        SystemProperties.set(PROP_OVERLAY_ACTIVE, "1");
        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        createNotificationChannel();
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.gaming_overlay_title))
                .setSmallIcon(R.drawable.ic_lenovo_parts)
                .setOngoing(true)
                .build();
        startForeground(NOTIFICATION_ID, notification);

        buildOverlayView();
        mHandler.post(mUpdateRunnable);
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.gaming_overlay_title),
                NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(channel);
        }
    }

    private void buildOverlayView() {
        mParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);

        mParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        mParams.x = 0;
        mParams.y = dpToPx(8);

        mRootView = new LinearLayout(this);
        mRootView.setOrientation(LinearLayout.VERTICAL);
        mRootView.setGravity(Gravity.CENTER);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6141416);
        bg.setCornerRadius(dpToPx(20));
        bg.setStroke(dpToPx(1), 0x33FFFFFF);
        mRootView.setBackground(bg);
        mRootView.setPadding(dpToPx(16), dpToPx(6), dpToPx(16), dpToPx(6));

        mCompactText = new TextView(this);
        mCompactText.setTextColor(Color.WHITE);
        mCompactText.setTextSize(12f);
        mCompactText.setGravity(Gravity.CENTER);
        mRootView.addView(mCompactText);

        mExpandedContainer = new LinearLayout(this);
        mExpandedContainer.setOrientation(LinearLayout.VERTICAL);
        mExpandedContainer.setVisibility(View.GONE);
        mExpandedContainer.setPadding(0, dpToPx(8), 0, dpToPx(4));

        mExpandedStatsText = new TextView(this);
        mExpandedStatsText.setTextColor(0xFFCCCCCC);
        mExpandedStatsText.setTextSize(11f);
        mExpandedContainer.addView(mExpandedStatsText);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);
        btnRow.setPadding(0, dpToPx(8), 0, 0);

        mBypassToggleBtn = new Button(this);
        mBypassToggleBtn.setTextSize(11f);
        mBypassToggleBtn.setTextColor(Color.WHITE);
        mBypassToggleBtn.setBackgroundColor(0xFF2E7D32);
        mBypassToggleBtn.setOnClickListener(v -> {
            boolean active = SystemProperties.getBoolean(PROP_BYPASS_ACTIVE, false);
            GamingBypassController.getInstance(this).setBypassEnabled(!active);
        });

        btnRow.addView(mBypassToggleBtn);

        Button settingsBtn = new Button(this);
        settingsBtn.setText(R.string.gaming_overlay_settings_title);
        settingsBtn.setTextSize(11f);
        settingsBtn.setTextColor(Color.WHITE);
        settingsBtn.setBackgroundColor(0xFF37474F);
        settingsBtn.setOnClickListener(v -> {
            Intent intent = new Intent(this, GamingOverlaySettingsActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        });
        btnRow.addView(settingsBtn);

        mExpandedContainer.addView(btnRow);
        mRootView.addView(mExpandedContainer);

        mRootView.setOnClickListener(v -> {
            mExpanded = !mExpanded;
            mExpandedContainer.setVisibility(mExpanded ? View.VISIBLE : View.GONE);
            mWindowManager.updateViewLayout(mRootView, mParams);
        });

        mRootView.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;
            private boolean isMoving = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = mParams.x;
                        initialY = mParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        isMoving = false;
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (event.getRawX() - initialTouchX);
                        int dy = (int) (event.getRawY() - initialTouchY);
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isMoving = true;
                            mParams.x = initialX + dx;
                            mParams.y = initialY + dy;
                            mWindowManager.updateViewLayout(mRootView, mParams);
                            return true;
                        }
                        return false;
                    case MotionEvent.ACTION_UP:
                        return isMoving;
                }
                return false;
            }
        });

        mWindowManager.addView(mRootView, mParams);
    }

    private void updateMetrics() {
        int cpuUsage = SystemProperties.getInt(PROP_CPU_USAGE, 0);
        int cpuFreq = SystemProperties.getInt(PROP_CPU_FREQ, 0);
        int cpuTemp = SystemProperties.getInt(PROP_CPU_TEMP, 0);
        int cpuPower = SystemProperties.getInt(PROP_CPU_POWER, 0);

        int gpuUsage = SystemProperties.getInt(PROP_GPU_USAGE, 0);
        int gpuFreq = SystemProperties.getInt(PROP_GPU_FREQ, 0);
        int gpuTemp = SystemProperties.getInt(PROP_GPU_TEMP, 0);
        int gpuPower = SystemProperties.getInt(PROP_GPU_POWER, 0);

        int socPower = SystemProperties.getInt(PROP_SOC_POWER, 0);
        int totalPower = SystemProperties.getInt(PROP_POWER_TOTAL, 0);
        int battery = SystemProperties.getInt(PROP_BATTERY_CAP, 0);
        boolean bypass = SystemProperties.getBoolean(PROP_BYPASS_ACTIVE, false);

        boolean showCpuUsage = mPrefs.getBoolean("gaming_overlay_show_cpu_usage", true);
        boolean showCpuFreq = mPrefs.getBoolean("gaming_overlay_show_cpu_freq", false);
        boolean showCpuTemp = mPrefs.getBoolean("gaming_overlay_show_cpu_temp", true);
        boolean showGpuUsage = mPrefs.getBoolean("gaming_overlay_show_gpu_usage", true);
        boolean showGpuFreq = mPrefs.getBoolean("gaming_overlay_show_gpu_freq", false);
        boolean showGpuTemp = mPrefs.getBoolean("gaming_overlay_show_gpu_temp", true);
        boolean showTotalPower = mPrefs.getBoolean("gaming_overlay_show_total_power", true);
        boolean showBattery = mPrefs.getBoolean("gaming_overlay_show_battery", true);
        String socModeStr = mPrefs.getString("gaming_overlay_soc_power_mode", "3");
        int socMode = 3;
        try {
            socMode = Integer.parseInt(socModeStr);
        } catch (Exception ignored) {}

        StringBuilder compact = new StringBuilder();

        if (showCpuUsage) compact.append("CPU ").append(cpuUsage).append("% ");
        if (showCpuFreq) compact.append(cpuFreq).append("MHz ");
        if (showCpuTemp) compact.append(cpuTemp).append("°C ");
        if (compact.length() > 0) compact.append("| ");

        if (showGpuUsage) compact.append("GPU ").append(gpuUsage).append("% ");
        if (showGpuFreq) compact.append(gpuFreq).append("MHz ");
        if (showGpuTemp) compact.append(gpuTemp).append("°C ");
        if (compact.length() > 0 && compact.charAt(compact.length() - 2) != '|') compact.append("| ");

        if (socMode == 1) {
            compact.append(String.format("CPU: %.1fW | ", cpuPower / 1000.0));
        } else if (socMode == 2) {
            compact.append(String.format("GPU: %.1fW | ", gpuPower / 1000.0));
        } else if (socMode == 3) {
            compact.append(String.format("CPU: %.1fW GPU: %.1fW | ", cpuPower / 1000.0, gpuPower / 1000.0));
        } else if (socMode == 4) {
            compact.append(String.format("SoC: %.1fW | ", socPower / 1000.0));
        }

        if (showTotalPower) {
            String label = bypass ? "Bypass" : "Pwr";
            compact.append(String.format("%s: %.1fW ", label, totalPower / 1000.0));
        }

        if (showBattery) {
            compact.append("• ").append(battery).append("%");
        }

        mCompactText.setText(compact.toString().trim());

        if (mExpanded) {
            StringBuilder exp = new StringBuilder();
            exp.append("CPU: ").append(cpuUsage).append("% @ ").append(cpuFreq).append("MHz (").append(cpuTemp).append("°C) ~ ").append(String.format("%.2fW\n", cpuPower / 1000.0));
            exp.append("GPU: ").append(gpuUsage).append("% @ ").append(gpuFreq).append("MHz (").append(gpuTemp).append("°C) ~ ").append(String.format("%.2fW\n", gpuPower / 1000.0));
            exp.append("SoC Combined: ").append(String.format("%.2fW\n", socPower / 1000.0));
            exp.append("Total System Draw: ").append(String.format("%.2fW (%s)\n", totalPower / 1000.0, bypass ? "Bypass active" : "Battery"));
            exp.append("Battery: ").append(battery).append("%");
            mExpandedStatsText.setText(exp.toString());
            mBypassToggleBtn.setText(bypass ? "Disable Bypass" : "Enable Bypass");
            mBypassToggleBtn.setBackgroundColor(bypass ? 0xFFC62828 : 0xFF2E7D32);
        }
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        sIsActive = false;
        SystemProperties.set(PROP_OVERLAY_ACTIVE, "0");
        mHandler.removeCallbacks(mUpdateRunnable);
        if (mRootView != null && mRootView.isAttachedToWindow()) {
            mWindowManager.removeView(mRootView);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
