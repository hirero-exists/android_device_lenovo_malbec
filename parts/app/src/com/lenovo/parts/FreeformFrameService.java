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

import android.app.ActivityOptions;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.IBinder;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class FreeformFrameService extends Service {
    public static final String EXTRA_BOUNDS = "extra_bounds";
    public static final String EXTRA_PACKAGE = "extra_package";

    private WindowManager mWindowManager;
    private View mBorderView;
    private LinearLayout mControlsView;
    private Rect mBounds;
    private String mTargetPackage;

    private int mColorAccent = 0xFF7C4DFF;
    private int mColorSurface = 0xFF1C1B1F;

    public static void showFrame(Context context, Rect bounds, String packageName) {
        Intent intent = new Intent(context, FreeformFrameService.class);
        intent.putExtra(EXTRA_BOUNDS, bounds);
        intent.putExtra(EXTRA_PACKAGE, packageName);
        context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        resolveThemeColors();
    }

    private void resolveThemeColors() {
        try {
            mColorAccent = getColor(android.R.color.system_accent1_500);
        } catch (Exception e) {
            mColorAccent = 0xFF7C4DFF;
        }
        try {
            mColorSurface = getColor(android.R.color.system_neutral1_900);
        } catch (Exception e) {
            mColorSurface = 0xFF1C1B1F;
        }
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private int applyAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            mBounds = intent.getParcelableExtra(EXTRA_BOUNDS, Rect.class);
            mTargetPackage = intent.getStringExtra(EXTRA_PACKAGE);
        }
        if (mBounds == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        removeViews();
        buildBorderView();
        buildControlsView();

        return START_NOT_STICKY;
    }

    private void buildBorderView() {
        mBorderView = new View(this);
        GradientDrawable borderDrawable = new GradientDrawable();
        borderDrawable.setCornerRadius(dpToPx(20));
        borderDrawable.setStroke(dpToPx(3), mColorAccent);
        borderDrawable.setColor(Color.TRANSPARENT);
        mBorderView.setBackground(borderDrawable);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                mBounds.width(),
                mBounds.height(),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = mBounds.left;
        params.y = mBounds.top;

        mWindowManager.addView(mBorderView, params);
    }

    private void buildControlsView() {
        mControlsView = new LinearLayout(this);
        mControlsView.setOrientation(LinearLayout.HORIZONTAL);
        mControlsView.setGravity(Gravity.CENTER_VERTICAL);
        mControlsView.setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6));

        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(applyAlpha(mColorSurface, 235));
        pillBg.setCornerRadius(dpToPx(22));
        pillBg.setStroke(dpToPx(1), applyAlpha(mColorAccent, 100));
        mControlsView.setBackground(pillBg);
        mControlsView.setElevation(dpToPx(8));

        TextView fullBtn = createDockButton("⤢ " + getString(R.string.window_control_fullscreen),
                mColorAccent, () -> {
                    launchTarget(1);
                    stopSelf();
                });
        mControlsView.addView(fullBtn);

        TextView splitBtn = createDockButton("◫ " + getString(R.string.window_control_split),
                mColorAccent, () -> {
                    launchTarget(3);
                    stopSelf();
                });
        mControlsView.addView(splitBtn);

        TextView closeBtn = createDockButton("✕ " + getString(R.string.window_control_close),
                0xFFB3261E, this::stopSelf);
        mControlsView.addView(closeBtn);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = mBounds.left + Math.max(0, (mBounds.width() - dpToPx(300)) / 2);
        params.y = mBounds.bottom + dpToPx(10);

        mWindowManager.addView(mControlsView, params);
    }

    private TextView createDockButton(String label, int color, Runnable action) {
        TextView btn = new TextView(this);
        btn.setText(label);
        btn.setTextSize(11.5f);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btn.setTextColor(0xFFFFFFFF);
        btn.setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(applyAlpha(color, 210));
        bg.setCornerRadius(dpToPx(16));
        btn.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dpToPx(4), 0, dpToPx(4), 0);
        btn.setLayoutParams(lp);
        btn.setOnClickListener(v -> action.run());
        return btn;
    }

    private void launchTarget(int windowingMode) {
        if (mTargetPackage == null) return;
        Intent intent = getPackageManager().getLaunchIntentForPackage(mTargetPackage);
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ActivityOptions opts = ActivityOptions.makeBasic();
            opts.setLaunchWindowingMode(windowingMode);
            startActivity(intent, opts.toBundle());
        }
    }

    private void removeViews() {
        if (mBorderView != null && mBorderView.isAttachedToWindow()) {
            mWindowManager.removeView(mBorderView);
            mBorderView = null;
        }
        if (mControlsView != null && mControlsView.isAttachedToWindow()) {
            mWindowManager.removeView(mControlsView);
            mControlsView = null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        removeViews();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
