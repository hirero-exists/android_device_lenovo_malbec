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

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.preference.PreferenceManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Locale;

public final class GamingOverlayService extends Service {
    private static final String CHANNEL_ID = "gaming_overlay_channel";
    private static final int NOTIFICATION_ID = 2001;
    private static final long GB = 1024L * 1024L * 1024L;

    private static final String PROP_OVERLAY_ACTIVE = "sys.malbec.perf.overlay_active";
    private static final String PROP_PERSIST_OVERLAY = "persist.sys.gaming.overlay";
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

    private static final String PREF_SHOW_FPS = "gaming_overlay_show_fps";
    private static final String PREF_SHOW_RAM = "gaming_overlay_show_ram";

    private static volatile boolean sIsActive = false;

    private WindowManager mWindowManager;
    private WindowManager.LayoutParams mParams;
    private LinearLayout mRootView;
    private TextView mCpuText;
    private TextView mGpuText;
    private TextView mFpsRamText;
    private TextView mPowerText;

    private int mColorSurface = 0xFF14151A;
    private int mColorAccent = 0xFF7C4DFF;
    private int mColorText = 0xFFFFFFFF;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private HandlerThread mFpsThread;
    private Handler mFpsHandler;
    private SharedPreferences mPrefs;

    private volatile int mFps = -1;
    private long mLastTotalFrames = -1;
    private long mLastFpsTimestamp = 0;

    private final Runnable mUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            updateMetrics();
            mHandler.postDelayed(this, 500);
        }
    };

    private final Runnable mFpsRunnable = new Runnable() {
        @Override
        public void run() {
            pollFps();
            mFpsHandler.postDelayed(this, 1000);
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

        resolveThemeColors();
        buildOverlayView();

        enableTimestats(true);
        mFpsThread = new HandlerThread("GamingOverlayFps");
        mFpsThread.start();
        mFpsHandler = new Handler(mFpsThread.getLooper());
        mFpsHandler.post(mFpsRunnable);
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

    private void resolveThemeColors() {
        mColorSurface = 0xEE121214;
        mColorAccent = 0xFF90CAF9;
        mColorText = 0xFFFFFFFF;
    }

    private void buildOverlayView() {
        mParams = new WindowManager.LayoutParams(
                dpToPx(280),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_BLUR_BEHIND,
                PixelFormat.TRANSLUCENT);
        mParams.setBlurBehindRadius(dpToPx(20));

        mParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        mParams.x = 0;
        mParams.y = dpToPx(12);

        mRootView = new LinearLayout(this);
        mRootView.setOrientation(LinearLayout.VERTICAL);
        mRootView.setPadding(dpToPx(14), dpToPx(10), dpToPx(14), dpToPx(10));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEE121214);
        bg.setCornerRadius(dpToPx(18));
        bg.setStroke(dpToPx(1), 0x33FFFFFF);
        mRootView.setBackground(bg);
        mRootView.setElevation(dpToPx(8));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dpToPx(6));

        View dragHandle = new View(this);
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setColor(0x4DFFFFFF);
        handleBg.setCornerRadius(dpToPx(3));
        dragHandle.setBackground(handleBg);
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(0, dpToPx(4), 1.0f);
        handleLp.setMargins(0, 0, dpToPx(8), 0);
        header.addView(dragHandle, handleLp);

        FrameLayout closeBtn = new FrameLayout(this);
        GradientDrawable closeBg = new GradientDrawable();
        closeBg.setShape(GradientDrawable.OVAL);
        closeBg.setColor(0x33FFFFFF);
        closeBtn.setBackground(closeBg);
        int btnSize = dpToPx(24);
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(btnSize, btnSize);
        closeBtn.setLayoutParams(closeLp);

        TextView closeIcon = new TextView(this);
        closeIcon.setText("✕");
        closeIcon.setTextColor(0xFFFFFFFF);
        closeIcon.setTextSize(11f);
        closeIcon.setGravity(Gravity.CENTER);
        closeBtn.addView(closeIcon, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        closeBtn.setOnClickListener(v -> {
            try {
                SystemProperties.set(PROP_PERSIST_OVERLAY, "0");
            } catch (Exception ignored) {
            }
            stopOverlay(GamingOverlayService.this);
        });
        header.addView(closeBtn);
        mRootView.addView(header);

        LinearLayout statsBox = new LinearLayout(this);
        statsBox.setOrientation(LinearLayout.VERTICAL);

        mCpuText = createMetricRow();
        statsBox.addView(mCpuText);

        mGpuText = createMetricRow();
        statsBox.addView(mGpuText);

        mFpsRamText = createMetricRow();
        statsBox.addView(mFpsRamText);

        mPowerText = createMetricRow();
        statsBox.addView(mPowerText);

        mRootView.addView(statsBox);

        final int touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        final Rect closeHitRect = new Rect();

        mRootView.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;
            private boolean isMoving = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                closeBtn.getGlobalVisibleRect(closeHitRect);
                if (closeHitRect.contains((int) event.getRawX(), (int) event.getRawY())) {
                    return false;
                }
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
                        if (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop) {
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

    private TextView createMetricRow() {
        TextView tv = new TextView(this);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(11.5f);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setPadding(0, dpToPx(2), 0, dpToPx(2));
        return tv;
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
        boolean showFps = mPrefs.getBoolean(PREF_SHOW_FPS, true);
        boolean showRam = mPrefs.getBoolean(PREF_SHOW_RAM, true);
        boolean showTotalPower = mPrefs.getBoolean("gaming_overlay_show_total_power", true);
        boolean showBattery = mPrefs.getBoolean("gaming_overlay_show_battery", true);

        float[] ram = showRam ? readRamGiB() : null;

        StringBuilder cpuSb = new StringBuilder("CPU ");
        if (showCpuUsage) cpuSb.append(cpuUsage).append("% ");
        if (showCpuFreq) cpuSb.append("@ ").append(cpuFreq).append("MHz ");
        if (showCpuTemp) cpuSb.append("• ").append(cpuTemp).append("°C ");
        cpuSb.append("• ").append(String.format(Locale.US, "%.1fW", cpuPower / 1000.0));
        mCpuText.setText(cpuSb.toString());

        StringBuilder gpuSb = new StringBuilder("GPU ");
        if (showGpuUsage) gpuSb.append(gpuUsage).append("% ");
        if (showGpuFreq) gpuSb.append("@ ").append(gpuFreq).append("MHz ");
        if (showGpuTemp) gpuSb.append("• ").append(gpuTemp).append("°C ");
        gpuSb.append("• ").append(String.format(Locale.US, "%.1fW", gpuPower / 1000.0));
        mGpuText.setText(gpuSb.toString());

        StringBuilder fpsRamSb = new StringBuilder();
        if (showFps) {
            fpsRamSb.append("FPS: ").append(mFps < 0 ? "--" : mFps);
        }
        if (showRam && ram != null) {
            if (fpsRamSb.length() > 0) fpsRamSb.append("  •  ");
            fpsRamSb.append(String.format(Locale.US, "RAM: %.1f/%.1fGB", ram[0], ram[1]));
        }
        if (fpsRamSb.length() > 0) {
            mFpsRamText.setVisibility(View.VISIBLE);
            mFpsRamText.setText(fpsRamSb.toString());
        } else {
            mFpsRamText.setVisibility(View.GONE);
        }

        StringBuilder pwrSb = new StringBuilder();
        pwrSb.append(String.format(Locale.US, "SoC: %.1fW", socPower / 1000.0));
        if (showTotalPower) {
            String label;
            boolean isUsb = SystemProperties.getInt("sys.malbec.power.usb_online", 0) == 1;
            if (bypass) {
                label = "Bypass";
            } else if (isUsb) {
                label = "Charge";
            } else {
                label = "Draw";
            }
            pwrSb.append(String.format(Locale.US, "  •  %s: %s%.1fW",
                    label, "Charge".equals(label) ? "+" : "", totalPower / 1000.0));
        }
        if (showBattery) {
            pwrSb.append("  •  Bat: ").append(battery).append("%");
        }
        mPowerText.setText(pwrSb.toString());
    }

    private float[] readRamGiB() {
        ActivityManager am = getSystemService(ActivityManager.class);
        if (am == null) {
            return null;
        }
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(info);
        if (info.totalMem <= 0) {
            return null;
        }
        return new float[]{
                (info.totalMem - info.availMem) / (float) GB,
                info.totalMem / (float) GB
        };
    }

    private void enableTimestats(boolean enable) {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{
                    "dumpsys", "SurfaceFlinger",
                    "--timestats", enable ? "-enable" : "-disable"});
            process.waitFor();
            process.destroy();
        } catch (Exception ignored) {
        }
    }

    private void pollFps() {
        try {
            if (!mPrefs.getBoolean(PREF_SHOW_FPS, true)) {
                mFps = -1;
                mLastTotalFrames = -1;
                return;
            }
            Process process = Runtime.getRuntime().exec(new String[]{
                    "dumpsys", "SurfaceFlinger", "--timestats", "-dump"});
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            long totalFrames = -1;
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("totalFrames = ")) {
                    totalFrames = Long.parseLong(line.substring("totalFrames = ".length()).trim());
                    break;
                }
            }
            reader.close();
            process.waitFor();
            process.destroy();
            if (totalFrames < 0) {
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (mLastTotalFrames >= 0) {
                double seconds = (now - mLastFpsTimestamp) / 1000.0;
                if (seconds >= 0.5) {
                    mFps = (int) Math.max(0,
                            Math.round((totalFrames - mLastTotalFrames) / seconds));
                }
            }
            mLastTotalFrames = totalFrames;
            mLastFpsTimestamp = now;
        } catch (Exception ignored) {
        }
    }

    private int applyAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        resolveThemeColors();
        if (mRootView != null && mRootView.isAttachedToWindow()) {
            Rect bounds = mWindowManager.getCurrentWindowMetrics().getBounds();
            int screenWidth = bounds.width();
            int screenHeight = bounds.height();
            mParams.x = 0;
            mParams.y = dpToPx(12);
            mWindowManager.updateViewLayout(mRootView, mParams);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        sIsActive = false;
        SystemProperties.set(PROP_OVERLAY_ACTIVE, "0");
        mHandler.removeCallbacks(mUpdateRunnable);
        if (mFpsHandler != null) {
            mFpsHandler.removeCallbacks(mFpsRunnable);
        }
        enableTimestats(false);
        if (mFpsThread != null) {
            mFpsThread.quitSafely();
            mFpsThread = null;
        }
        if (mRootView != null && mRootView.isAttachedToWindow()) {
            mWindowManager.removeView(mRootView);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
