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

import android.animation.ValueAnimator;
import android.app.ActivityOptions;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.input.InputManager;
import android.media.AudioManager;
import android.media.MediaScannerConnection;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class FloatingToolbarService extends Service {
    private static final String TAG = "FloatingToolbarService";
    private static final String CHANNEL_ID = "pen_toolbar_channel";
    private static final int NOTIFICATION_ID = 1003;
    private static final int BUBBLE_SIZE_DP = 48;
    private static final int IDLE_TIMEOUT_MS = 3000;
    private static final String ACTION_SHOW_TOOLBAR =
            "com.lenovo.parts.action.SHOW_TOOLBAR";
    private static final String POSITION_ANCHOR_SETTING = "malbec_toolbar_anchor";
    private static final String POSITION_X_SETTING = "malbec_toolbar_x";
    private static final String POSITION_Y_SETTING = "malbec_toolbar_y";

    private static final int ANCHOR_FREE = 0;
    private static final int ANCHOR_LEFT = 1;
    private static final int ANCHOR_RIGHT = 2;

    private static FloatingToolbarService sInstance;

    private WindowManager mWindowManager;
    private WindowManager.LayoutParams mBubbleParams;
    private WindowManager.LayoutParams mMenuParams;
    private FrameLayout mBubbleView;
    private LinearLayout mMenuView;
    private FrameLayout mQuickNoteOverlay;
    private boolean mMenuOpen = false;

    private TextView mPenBtn;
    private TextView mHighlighterBtn;
    private TextView mEraserBtn;

    private int mColorSurface = 0xFF1C1B1F;
    private int mColorAccent = 0xFF7C4DFF;
    private int mColorText = 0xFFFFFFFF;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mFadeRunnable = new Runnable() {
        @Override
        public void run() {
            if (mBubbleView != null && !mMenuOpen) {
                mBubbleView.animate()
                        .alpha(0.35f)
                        .setDuration(300)
                        .start();
            }
        }
    };

    private final BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent != null ? intent.getAction() : null;
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                closeMenu();
                closeQuickNoteCanvas();
            } else if (Intent.ACTION_SCREEN_ON.equals(action)
                    || Intent.ACTION_USER_PRESENT.equals(action)) {
                showViews();
            }
        }
    };

    public static void showToolbar(Context context) {
        Intent intent = new Intent(context, FloatingToolbarService.class);
        intent.setAction(ACTION_SHOW_TOOLBAR);
        context.startForegroundService(intent);
    }

    public static void toggleMenu(Context context) {
        if (sInstance != null) {
            sInstance.mHandler.post(() -> sInstance.toggleMenu());
        } else {
            showToolbar(context);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        resolveThemeColors();

        createNotificationChannel();
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.pen_toolbar_title))
                .setSmallIcon(R.drawable.ic_lenovo_parts)
                .setOngoing(true)
                .build();
        startForeground(NOTIFICATION_ID, notification);

        createBubbleView();
        createMenuView();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(mScreenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);

        resetIdleTimer();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.pen_toolbar_title),
                NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(channel);
        }
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
        mColorText = 0xFFFFFFFF;
    }

    private int applyAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void createBubbleView() {
        int size = dpToPx(BUBBLE_SIZE_DP);
        mBubbleParams = new WindowManager.LayoutParams(
                size,
                size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        mBubbleParams.gravity = Gravity.TOP | Gravity.START;
        restoreBubblePosition();

        mBubbleView = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(applyAlpha(mColorSurface, 210));
        bg.setStroke(dpToPx(2), applyAlpha(mColorAccent, 240));
        mBubbleView.setBackground(bg);
        mBubbleView.setElevation(dpToPx(8));

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_lenovo_parts_bubble);
        icon.setColorFilter(mColorAccent);
        int padding = dpToPx(11);
        icon.setPadding(padding, padding, padding, padding);
        mBubbleView.addView(icon, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        mBubbleView.setScaleX(0.2f);
        mBubbleView.setScaleY(0.2f);
        mBubbleView.setAlpha(0f);
        mBubbleView.animate()
                .scaleX(1.0f)
                .scaleY(1.0f)
                .alpha(1.0f)
                .setDuration(260)
                .setInterpolator(new OvershootInterpolator(1.2f))
                .start();

        mBubbleView.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;
            private boolean isClick = false;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                resetIdleTimer();
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = mBubbleParams.x;
                        initialY = mBubbleParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        isClick = true;
                        mBubbleView.animate().scaleX(1.08f).scaleY(1.08f).setDuration(100).start();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (event.getRawX() - initialTouchX);
                        int dy = (int) (event.getRawY() - initialTouchY);
                        if (Math.abs(dx) > 12 || Math.abs(dy) > 12) {
                            isClick = false;
                        }
                        updateBubblePosition(initialX + dx, initialY + dy);
                        return true;
                    case MotionEvent.ACTION_UP:
                        mBubbleView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start();
                        if (isClick) {
                            toggleMenu();
                        } else {
                            snapOrFloat();
                        }
                        return true;
                }
                return false;
            }
        });

        mWindowManager.addView(mBubbleView, mBubbleParams);
    }

    private void updateBubblePosition(int x, int y) {
        Rect bounds = getDisplayBounds();
        int screenWidth = bounds.width();
        int screenHeight = bounds.height();
        int size = mBubbleParams.width;

        mBubbleParams.x = Math.max(0, Math.min(x, screenWidth - size));
        mBubbleParams.y = Math.max(0, Math.min(y, screenHeight - size));

        if (mBubbleView.isAttachedToWindow()) {
            mWindowManager.updateViewLayout(mBubbleView, mBubbleParams);
        }
    }

    private void snapOrFloat() {
        Rect bounds = getDisplayBounds();
        int screenWidth = bounds.width();
        int screenHeight = bounds.height();
        int size = mBubbleParams.width;
        int currentX = mBubbleParams.x;

        int targetX = currentX;
        int anchor = ANCHOR_FREE;
        if (currentX + size / 2 < screenWidth * 0.25f) {
            targetX = 0;
            anchor = ANCHOR_LEFT;
        } else if (currentX + size / 2 > screenWidth * 0.75f) {
            targetX = screenWidth - size;
            anchor = ANCHOR_RIGHT;
        }

        saveBubblePosition(anchor, targetX, mBubbleParams.y);
        animateBubbleTo(targetX, mBubbleParams.y);
    }

    private void animateBubbleTo(int targetX, int targetY) {
        int startX = mBubbleParams.x;
        int startY = mBubbleParams.y;
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(220);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(animation -> {
            float f = (float) animation.getAnimatedValue();
            mBubbleParams.x = Math.round(startX + (targetX - startX) * f);
            mBubbleParams.y = Math.round(startY + (targetY - startY) * f);
            if (mBubbleView.isAttachedToWindow()) {
                mWindowManager.updateViewLayout(mBubbleView, mBubbleParams);
            }
        });
        anim.start();
    }

    private void createMenuView() {
        mMenuParams = new WindowManager.LayoutParams(
                dpToPx(230),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        mMenuParams.gravity = Gravity.TOP | Gravity.START;

        mMenuView = new LinearLayout(this);
        mMenuView.setOrientation(LinearLayout.VERTICAL);
        int pad = dpToPx(10);
        mMenuView.setPadding(pad, pad, pad, pad);

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dpToPx(24));
        bg.setColor(applyAlpha(mColorSurface, 235));
        bg.setStroke(dpToPx(1), applyAlpha(mColorAccent, 75));
        mMenuView.setBackground(bg);
        mMenuView.setElevation(dpToPx(10));

        addMenuItem(getString(R.string.quick_note_title), () -> {
            closeMenu();
            openQuickNoteCanvas();
        });
        addMenuItem(getString(R.string.toolbar_screenshot), () -> {
            takeScreenshot();
            closeMenu();
        });
        addMenuItem(getString(R.string.toolbar_open_app), () -> {
            Intent intent = new Intent(this, AppPickerActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchWindowingMode(5);
            options.setLaunchBounds(appPickerBounds());
            startActivity(intent, options.toBundle());
            closeMenu();
        });
        addMenuItem(getString(R.string.toolbar_play_pause), () -> {
            dispatchPlayPause();
            closeMenu();
        });
        addMenuItem(getString(R.string.app_name), () -> {
            Intent intent = new Intent(this, LenovoPartsActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            closeMenu();
        });
        addMenuItem(getString(R.string.toolbar_close), () -> {
            PenMode.setToolbarEnabled(false);
            stopSelf();
        });

        mMenuView.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_OUTSIDE) {
                closeMenu();
                return true;
            }
            return false;
        });
    }

    private void addMenuItem(String title, Runnable action) {
        TextView item = new TextView(this);
        item.setText(title);
        item.setTextColor(mColorText);
        item.setTextSize(13f);
        item.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        item.setPadding(dpToPx(16), dpToPx(11), dpToPx(16), dpToPx(11));
        android.util.TypedValue value = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, value, true);
        if (value.resourceId != 0) {
            item.setBackgroundResource(value.resourceId);
        }
        item.setOnClickListener(v -> action.run());
        mMenuView.addView(item);
    }

    private void takeScreenshot() {
        injectKey(KeyEvent.KEYCODE_SYSRQ);
    }

    private Rect appPickerBounds() {
        Rect bounds = getDisplayBounds();
        int w = bounds.width();
        int h = bounds.height();
        int winW = Math.round(w * 0.55f);
        int winH = Math.round(h * 0.6f);
        int left = (w - winW) / 2;
        int top = (h - winH) / 3;
        return new Rect(left, top, left + winW, top + winH);
    }

    private void dispatchPlayPause() {
        AudioManager am = getSystemService(AudioManager.class);
        if (am != null) {
            long now = SystemClock.uptimeMillis();
            KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0);
            am.dispatchMediaKeyEvent(down);
            KeyEvent up = new KeyEvent(now, now, KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0);
            am.dispatchMediaKeyEvent(up);
        } else {
            injectKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        }
    }

    private void injectKey(int keyCode) {
        InputManager im = getSystemService(InputManager.class);
        if (im == null) return;
        long now = SystemClock.uptimeMillis();
        int flags = KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY;
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags, InputDevice.SOURCE_KEYBOARD);
        KeyEvent up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP);
        im.injectInputEvent(down, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
        im.injectInputEvent(up, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
    }

    private void toggleMenu() {
        showViews();
        if (mMenuOpen) {
            closeMenu();
        } else {
            openMenu();
        }
    }

    private void openMenu() {
        if (mMenuView == null) return;
        positionMenu();
        if (!mMenuView.isAttachedToWindow()) {
            mMenuView.setScaleX(0.85f);
            mMenuView.setScaleY(0.85f);
            mMenuView.setAlpha(0f);
            mWindowManager.addView(mMenuView, mMenuParams);
            mMenuView.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .alpha(1.0f)
                    .setDuration(180)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
        mMenuOpen = true;
        resetIdleTimer();
    }

    private void closeMenu() {
        if (mMenuView != null && mMenuView.isAttachedToWindow()) {
            mMenuView.animate()
                    .scaleX(0.85f)
                    .scaleY(0.85f)
                    .alpha(0f)
                    .setDuration(140)
                    .withEndAction(() -> {
                        if (mMenuView.isAttachedToWindow()) {
                            mWindowManager.removeView(mMenuView);
                        }
                    })
                    .start();
        }
        mMenuOpen = false;
        resetIdleTimer();
    }

    private void positionMenu() {
        Rect bounds = getDisplayBounds();
        int screenWidth = bounds.width();
        int screenHeight = bounds.height();
        if (mMenuView != null && (mMenuView.getWidth() == 0 || mMenuView.getHeight() == 0)) {
            mMenuView.measure(
                    View.MeasureSpec.makeMeasureSpec(screenWidth, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(screenHeight, View.MeasureSpec.AT_MOST));
        }
        int menuWidth = mMenuView != null && mMenuView.getMeasuredWidth() > 0
                ? mMenuView.getMeasuredWidth() : dpToPx(280);
        int bubbleSize = mBubbleParams.width > 0 ? mBubbleParams.width : dpToPx(BUBBLE_SIZE_DP);

        int menuX;
        if (mBubbleParams.x + bubbleSize + menuWidth + dpToPx(8) <= screenWidth) {
            menuX = mBubbleParams.x + bubbleSize + dpToPx(8);
        } else {
            menuX = Math.max(0, mBubbleParams.x - menuWidth - dpToPx(8));
        }

        int menuY = Math.max(0, Math.min(mBubbleParams.y, screenHeight - dpToPx(280)));
        mMenuParams.x = menuX;
        mMenuParams.y = menuY;
    }

    private void resetIdleTimer() {
        mHandler.removeCallbacks(mFadeRunnable);
        if (mBubbleView != null) {
            mBubbleView.animate().alpha(1f).setDuration(120).start();
        }
        if (!mMenuOpen) {
            mHandler.postDelayed(mFadeRunnable, IDLE_TIMEOUT_MS);
        }
    }

    private void openQuickNoteCanvas() {
        if (mQuickNoteOverlay != null && mQuickNoteOverlay.isAttachedToWindow()) {
            return;
        }

        WindowManager.LayoutParams overlayParams = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);

        mQuickNoteOverlay = new FrameLayout(this);
        mQuickNoteOverlay.setBackgroundColor(Color.TRANSPARENT);

        DrawingCanvasView canvasView = new DrawingCanvasView(this,
                0xFFFFEB3B, applyAlpha(mColorAccent, 150));
        mQuickNoteOverlay.addView(canvasView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(0xEE1A1B22);
        barBg.setCornerRadius(dpToPx(28));
        barBg.setStroke(dpToPx(1), applyAlpha(mColorAccent, 100));
        topBar.setBackground(barBg);
        topBar.setElevation(dpToPx(10));
        int padH = dpToPx(16);
        int padV = dpToPx(8);
        topBar.setPadding(padH, padV, padH, padV);

        TextView title = new TextView(this);
        title.setText(R.string.quick_note_title);
        title.setTextColor(mColorAccent);
        title.setTextSize(13f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        topBar.addView(title);

        View spacer = new View(this);
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(dpToPx(12), 1);
        topBar.addView(spacer, spLp);

        mPenBtn = createToolButton(mColorAccent, onColor(mColorAccent));
        mPenBtn.setText(R.string.quick_note_pen);
        mPenBtn.setOnClickListener(v -> {
            canvasView.setTool(DrawingCanvasView.TOOL_PEN);
            updateActiveTool(DrawingCanvasView.TOOL_PEN);
        });
        topBar.addView(mPenBtn);

        mHighlighterBtn = createToolButton(applyAlpha(mColorText, 25), mColorText);
        mHighlighterBtn.setText(R.string.quick_note_highlighter);
        mHighlighterBtn.setOnClickListener(v -> {
            canvasView.setTool(DrawingCanvasView.TOOL_HIGHLIGHTER);
            updateActiveTool(DrawingCanvasView.TOOL_HIGHLIGHTER);
        });
        topBar.addView(mHighlighterBtn);

        mEraserBtn = createToolButton(applyAlpha(mColorText, 25), mColorText);
        mEraserBtn.setText(R.string.quick_note_eraser);
        mEraserBtn.setOnClickListener(v -> {
            canvasView.setTool(DrawingCanvasView.TOOL_ERASER);
            updateActiveTool(DrawingCanvasView.TOOL_ERASER);
        });
        topBar.addView(mEraserBtn);

        TextView clearBtn = createToolButton(applyAlpha(mColorText, 25), mColorText);
        clearBtn.setText(R.string.quick_note_clear);
        clearBtn.setOnClickListener(v -> canvasView.clear());
        topBar.addView(clearBtn);

        TextView saveBtn = createToolButton(0xFF4CAF50, 0xFFFFFFFF);
        saveBtn.setText(R.string.quick_note_save);
        saveBtn.setOnClickListener(v -> {
            if (saveCanvasBitmap(canvasView.getBitmap())) {
                closeQuickNoteCanvas();
            }
        });
        topBar.addView(saveBtn);

        TextView closeBtn = createToolButton(applyAlpha(mColorText, 35), mColorText);
        closeBtn.setText("✕");
        closeBtn.setOnClickListener(v -> closeQuickNoteCanvas());
        topBar.addView(closeBtn);

        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        barLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        barLp.topMargin = dpToPx(16);
        mQuickNoteOverlay.addView(topBar, barLp);

        mWindowManager.addView(mQuickNoteOverlay, overlayParams);
    }

    private void updateActiveTool(int tool) {
        setToolButtonActive(mPenBtn, tool == DrawingCanvasView.TOOL_PEN);
        setToolButtonActive(mHighlighterBtn, tool == DrawingCanvasView.TOOL_HIGHLIGHTER);
        setToolButtonActive(mEraserBtn, tool == DrawingCanvasView.TOOL_ERASER);
    }

    private void setToolButtonActive(TextView btn, boolean active) {
        if (btn == null) return;
        GradientDrawable bg = (GradientDrawable) btn.getBackground();
        if (active) {
            bg.setColor(mColorAccent);
            btn.setTextColor(onColor(mColorAccent));
        } else {
            bg.setColor(applyAlpha(mColorText, 25));
            btn.setTextColor(mColorText);
        }
    }

    private TextView createToolButton(int color, int textColor) {
        TextView pill = new TextView(this);
        pill.setTextSize(11f);
        pill.setTextColor(textColor);
        pill.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        pill.setPadding(dpToPx(12), dpToPx(7), dpToPx(12), dpToPx(7));
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setCornerRadius(dpToPx(16));
        pillBg.setColor(color);
        pill.setBackground(pillBg);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(dpToPx(4), 0, 0, 0);
        pill.setLayoutParams(params);
        return pill;
    }

    private int onColor(int background) {
        double luminance = (0.299 * Color.red(background)
                + 0.587 * Color.green(background)
                + 0.114 * Color.blue(background)) / 255.0;
        return luminance > 0.5 ? 0xFF1C1B1F : 0xFFFFFFFF;
    }

    private void closeQuickNoteCanvas() {
        if (mQuickNoteOverlay != null && mQuickNoteOverlay.isAttachedToWindow()) {
            mWindowManager.removeView(mQuickNoteOverlay);
            mQuickNoteOverlay = null;
        }
    }

    private boolean saveCanvasBitmap(Bitmap bitmap) {
        if (bitmap == null) {
            return false;
        }
        try {
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File dir = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "QuickNotes");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File file = new File(dir, "QuickNote_" + timeStamp + ".png");
            try (FileOutputStream fos = new FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
            MediaScannerConnection.scanFile(this,
                    new String[]{file.getAbsolutePath()},
                    new String[]{"image/png"},
                    null);
            Toast.makeText(this, R.string.quick_note_saved, Toast.LENGTH_SHORT).show();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Save note failed", e);
            Toast.makeText(this, R.string.quick_note_save_error, Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private static class DrawingCanvasView extends View {
        static final int TOOL_PEN = 0;
        static final int TOOL_HIGHLIGHTER = 1;
        static final int TOOL_ERASER = 2;

        private final Paint mPaint = new Paint();
        private final Path mCurrentPath = new Path();
        private final int mPenColor;
        private final int mHighlighterColor;
        private int mCurrentTool = TOOL_PEN;
        private Bitmap mBitmap;
        private Canvas mCanvas;
        private float mPrevX, mPrevY;

        DrawingCanvasView(Context context, int penColor, int highlighterColor) {
            super(context);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            mPenColor = penColor;
            mHighlighterColor = highlighterColor;
            mPaint.setAntiAlias(true);
            mPaint.setStyle(Paint.Style.STROKE);
            applyTool(TOOL_PEN);
        }

        void setTool(int tool) {
            mCurrentTool = tool;
            applyTool(tool);
        }

        private void applyTool(int tool) {
            if (tool == TOOL_PEN) {
                mPaint.setColor(mPenColor);
                mPaint.setStrokeWidth(6f);
                mPaint.setStrokeCap(Paint.Cap.ROUND);
                mPaint.setStrokeJoin(Paint.Join.ROUND);
                mPaint.setXfermode(null);
            } else if (tool == TOOL_HIGHLIGHTER) {
                mPaint.setColor(0x55FFE066);
                mPaint.setStrokeWidth(36f);
                mPaint.setStrokeCap(Paint.Cap.SQUARE);
                mPaint.setStrokeJoin(Paint.Join.BEVEL);
                mPaint.setXfermode(null);
            } else if (tool == TOOL_ERASER) {
                mPaint.setColor(Color.TRANSPARENT);
                mPaint.setStrokeWidth(48f);
                mPaint.setStrokeCap(Paint.Cap.ROUND);
                mPaint.setStrokeJoin(Paint.Join.ROUND);
                mPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
            }
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (w > 0 && h > 0) {
                Bitmap prev = mBitmap;
                mBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                mCanvas = new Canvas(mBitmap);
                if (prev != null) {
                    mCanvas.drawBitmap(prev, 0, 0, null);
                    prev.recycle();
                }
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (mBitmap != null) {
                canvas.drawBitmap(mBitmap, 0, 0, null);
            }
            if (mCurrentTool != TOOL_ERASER && !mCurrentPath.isEmpty()) {
                canvas.drawPath(mCurrentPath, mPaint);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            float x = event.getX();
            float y = event.getY();

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    mPrevX = x;
                    mPrevY = y;
                    mCurrentPath.reset();
                    mCurrentPath.moveTo(x, y);
                    if (mCurrentTool == TOOL_ERASER && mCanvas != null) {
                        mCanvas.drawPoint(x, y, mPaint);
                    }
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = Math.abs(x - mPrevX);
                    float dy = Math.abs(y - mPrevY);
                    if (dx >= 2 || dy >= 2) {
                        mCurrentPath.quadTo(mPrevX, mPrevY, (x + mPrevX) / 2, (y + mPrevY) / 2);
                        if (mCurrentTool == TOOL_ERASER && mCanvas != null) {
                            mCanvas.drawLine(mPrevX, mPrevY, x, y, mPaint);
                        }
                        mPrevX = x;
                        mPrevY = y;
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (mCurrentTool != TOOL_ERASER && mCanvas != null) {
                        mCurrentPath.lineTo(x, y);
                        mCanvas.drawPath(mCurrentPath, mPaint);
                    }
                    mCurrentPath.reset();
                    invalidate();
                    return true;
            }
            return false;
        }

        void clear() {
            if (mBitmap != null) {
                mBitmap.eraseColor(Color.TRANSPARENT);
                mCurrentPath.reset();
                invalidate();
            }
        }

        Bitmap getBitmap() {
            return mBitmap;
        }
    }

    private void restoreBubblePosition() {
        Rect bounds = getDisplayBounds();
        int screenWidth = bounds.width();
        int screenHeight = bounds.height();
        int size = dpToPx(BUBBLE_SIZE_DP);

        int anchor = Settings.Secure.getInt(getContentResolver(),
                POSITION_ANCHOR_SETTING, ANCHOR_RIGHT);
        int savedX = Settings.Secure.getInt(getContentResolver(),
                POSITION_X_SETTING, screenWidth - size);
        int savedY = Settings.Secure.getInt(getContentResolver(),
                POSITION_Y_SETTING, screenHeight / 3);

        if (anchor == ANCHOR_LEFT) {
            mBubbleParams.x = 0;
        } else if (anchor == ANCHOR_RIGHT) {
            mBubbleParams.x = screenWidth - size;
        } else {
            mBubbleParams.x = Math.max(0, Math.min(savedX, screenWidth - size));
        }
        mBubbleParams.y = Math.max(0, Math.min(savedY, screenHeight - size));
    }

    private void saveBubblePosition(int anchor, int x, int y) {
        Settings.Secure.putInt(getContentResolver(), POSITION_ANCHOR_SETTING, anchor);
        Settings.Secure.putInt(getContentResolver(), POSITION_X_SETTING, x);
        Settings.Secure.putInt(getContentResolver(), POSITION_Y_SETTING, y);
    }

    private Rect getDisplayBounds() {
        return mWindowManager.getCurrentWindowMetrics().getBounds();
    }

    private void hideViews() {
        if (mBubbleView != null && mBubbleView.isAttachedToWindow()) {
            mBubbleView.setVisibility(View.GONE);
        }
        closeMenu();
        closeQuickNoteCanvas();
    }

    private void showViews() {
        if (mBubbleView != null && mBubbleView.isAttachedToWindow()) {
            mBubbleView.setVisibility(View.VISIBLE);
            resetIdleTimer();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        resolveThemeColors();
        restoreBubblePosition();
        if (mBubbleView != null && mBubbleView.isAttachedToWindow()) {
            mWindowManager.updateViewLayout(mBubbleView, mBubbleParams);
        }
        closeMenu();
        if (mQuickNoteOverlay != null && mQuickNoteOverlay.isAttachedToWindow()) {
            WindowManager.LayoutParams qnParams = (WindowManager.LayoutParams) mQuickNoteOverlay.getLayoutParams();
            if (qnParams != null) {
                Rect bounds = getDisplayBounds();
                qnParams.width = bounds.width();
                qnParams.height = bounds.height();
                mWindowManager.updateViewLayout(mQuickNoteOverlay, qnParams);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!PenMode.isEnabled()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_SHOW_TOOLBAR.equals(intent.getAction())) {
            showViews();
            if (!mMenuOpen) {
                openMenu();
            }
            return START_NOT_STICKY;
        }
        if (!PenMode.isToolbarEnabled()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        sInstance = null;
        unregisterReceiver(mScreenReceiver);
        closeQuickNoteCanvas();
        closeMenu();
        if (mBubbleView != null && mBubbleView.isAttachedToWindow()) {
            mWindowManager.removeView(mBubbleView);
        }
        mHandler.removeCallbacks(mFadeRunnable);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
