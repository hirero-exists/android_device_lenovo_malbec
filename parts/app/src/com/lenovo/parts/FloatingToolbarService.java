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

import android.animation.ValueAnimator;
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
import android.graphics.drawable.GradientDrawable;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
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
    private static final String CHANNEL_ID = "pen_toolbar_channel";
    private static final int NOTIFICATION_ID = 1003;
    private static final int BUBBLE_SIZE_DP = 48;
    private static final int IDLE_TIMEOUT_MS = 3000;

    private static FloatingToolbarService sInstance;

    private WindowManager mWindowManager;
    private FrameLayout mBubbleView;
    private LinearLayout mMenuView;
    private FrameLayout mQuickNoteOverlay;
    private WindowManager.LayoutParams mBubbleParams;
    private WindowManager.LayoutParams mMenuParams;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mMenuOpen = false;

    private final Runnable mFadeRunnable = new Runnable() {
        @Override
        public void run() {
            if (mBubbleView != null && !mMenuOpen) {
                mBubbleView.animate().alpha(0.35f).setDuration(300).start();
            }
        }
    };

    private final BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                hideViews();
            } else if (Intent.ACTION_USER_PRESENT.equals(action) || Intent.ACTION_SCREEN_ON.equals(action)) {
                if (PenMode.isEnabled() && PenMode.isToolbarEnabled()) {
                    showViews();
                }
            }
        }
    };

    static void showToolbar(Context context) {
        if (sInstance != null) {
            sInstance.toggleMenu();
        } else {
            Intent intent = new Intent(context, FloatingToolbarService.class);
            context.startForegroundService(intent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.pen_toolbar_title),
                NotificationManager.IMPORTANCE_MIN);
        channel.setShowBadge(false);
        channel.enableLights(false);
        channel.enableVibration(false);
        channel.setSound(null, null);

        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.createNotificationChannel(channel);
        }

        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_lenovo_parts)
                .setContentTitle(getString(R.string.pen_toolbar_title))
                .setOngoing(true)
                .build();

        startForeground(NOTIFICATION_ID, notification);

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(mScreenReceiver, filter, Context.RECEIVER_EXPORTED);

        createBubbleView();
        createMenuView();
        resetIdleTimer();
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
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        mBubbleParams.gravity = Gravity.TOP | Gravity.START;
        mBubbleParams.x = 0;
        mBubbleParams.y = dpToPx(240);

        mBubbleView = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.argb(160, 20, 20, 20));
        bg.setStroke(dpToPx(1), Color.argb(90, 255, 255, 255));
        mBubbleView.setBackground(bg);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_lenovo_parts);
        icon.setAlpha(0.85f);
        int padding = dpToPx(11);
        icon.setPadding(padding, padding, padding, padding);
        mBubbleView.addView(icon, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

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
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenWidth = dm.widthPixels;
        int screenHeight = dm.heightPixels;
        int size = mBubbleParams.width;

        mBubbleParams.x = Math.max(0, Math.min(x, screenWidth - size));
        mBubbleParams.y = Math.max(0, Math.min(y, screenHeight - size));

        if (mBubbleView.isAttachedToWindow()) {
            mWindowManager.updateViewLayout(mBubbleView, mBubbleParams);
        }
    }

    private void snapOrFloat() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenWidth = dm.widthPixels;
        int size = mBubbleParams.width;
        int currentX = mBubbleParams.x;

        int targetX = currentX;
        if (currentX < screenWidth * 0.30f) {
            targetX = 0;
        } else if (currentX > screenWidth * 0.70f) {
            targetX = screenWidth - size;
        }

        if (targetX != currentX) {
            ValueAnimator animator = ValueAnimator.ofInt(currentX, targetX);
            animator.setDuration(220);
            animator.setInterpolator(new DecelerateInterpolator());
            animator.addUpdateListener(animation -> {
                mBubbleParams.x = (int) animation.getAnimatedValue();
                if (mBubbleView.isAttachedToWindow()) {
                    mWindowManager.updateViewLayout(mBubbleView, mBubbleParams);
                }
            });
            animator.start();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mHandler.postDelayed(() -> {
            if (mBubbleView != null && mBubbleView.isAttachedToWindow()) {
                updateBubblePosition(mBubbleParams.x, mBubbleParams.y);
                snapOrFloat();
            }
            if (mMenuOpen) {
                closeMenu();
            }
        }, 150);
    }

    private void createMenuView() {
        mMenuParams = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
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
        bg.setCornerRadius(dpToPx(18));
        bg.setColor(Color.argb(235, 22, 22, 22));
        bg.setStroke(dpToPx(1), Color.argb(70, 255, 255, 255));
        mMenuView.setBackground(bg);

        addMenuItem(getString(R.string.quick_note_title), () -> {
            closeMenu();
            openQuickNoteCanvas();
        });
        addMenuItem(getString(R.string.pen_single_action_title) + " (" + getString(R.string.pen_enabled_title) + ")", () -> {
            PenShortcuts.executeAction(this, PenShortcuts.ACTION_SCREENSHOT);
            closeMenu();
        });
        addMenuItem("Take screenshot", () -> {
            PenShortcuts.executeAction(this, PenShortcuts.ACTION_SCREENSHOT);
            closeMenu();
        });
        addMenuItem("Play / Pause", () -> {
            PenShortcuts.executeAction(this, PenShortcuts.ACTION_PLAY_PAUSE);
            closeMenu();
        });
        addMenuItem(getString(R.string.app_name), () -> {
            Intent intent = new Intent(this, LenovoPartsActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            closeMenu();
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
        Button button = new Button(this);
        button.setText(title);
        button.setTextColor(Color.WHITE);
        button.setTextSize(13);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setPadding(dpToPx(14), dpToPx(6), dpToPx(14), dpToPx(6));
        button.setOnClickListener(v -> action.run());
        mMenuView.addView(button);
    }

    private void toggleMenu() {
        if (mMenuOpen) {
            closeMenu();
        } else {
            openMenu();
        }
    }

    private void openMenu() {
        if (mMenuOpen) {
            return;
        }
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenWidth = dm.widthPixels;
        int bubbleRight = mBubbleParams.x + mBubbleParams.width;

        if (mBubbleParams.x < screenWidth / 2) {
            mMenuParams.x = bubbleRight + dpToPx(8);
        } else {
            mMenuParams.x = Math.max(0, mBubbleParams.x - dpToPx(190));
        }
        mMenuParams.y = Math.max(dpToPx(40), mBubbleParams.y);

        if (!mMenuView.isAttachedToWindow()) {
            mMenuView.setAlpha(0f);
            mWindowManager.addView(mMenuView, mMenuParams);
            mMenuView.animate().alpha(1f).setDuration(180).start();
        }
        mMenuOpen = true;
        resetIdleTimer();
    }

    private void closeMenu() {
        if (!mMenuOpen) {
            return;
        }
        if (mMenuView.isAttachedToWindow()) {
            mWindowManager.removeView(mMenuView);
        }
        mMenuOpen = false;
        resetIdleTimer();
    }

    private void resetIdleTimer() {
        if (mBubbleView != null) {
            mBubbleView.setAlpha(1.0f);
        }
        mHandler.removeCallbacks(mFadeRunnable);
        mHandler.postDelayed(mFadeRunnable, IDLE_TIMEOUT_MS);
    }

    private void hideViews() {
        closeMenu();
        if (mBubbleView != null && mBubbleView.isAttachedToWindow()) {
            mBubbleView.setVisibility(View.GONE);
        }
    }

    private void showViews() {
        if (mBubbleView != null && mBubbleView.isAttachedToWindow()) {
            mBubbleView.setVisibility(View.VISIBLE);
            resetIdleTimer();
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
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);

        mQuickNoteOverlay = new FrameLayout(this);
        mQuickNoteOverlay.setBackgroundColor(Color.argb(170, 10, 10, 10));

        DrawingCanvasView canvasView = new DrawingCanvasView(this);
        mQuickNoteOverlay.addView(canvasView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(Color.argb(200, 30, 30, 30));
        int pad = dpToPx(10);
        topBar.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(R.string.quick_note_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        topBar.addView(title);

        Button clearBtn = new Button(this);
        clearBtn.setText(R.string.quick_note_clear);
        clearBtn.setTextColor(Color.WHITE);
        clearBtn.setOnClickListener(v -> canvasView.clear());
        topBar.addView(clearBtn);

        Button saveBtn = new Button(this);
        saveBtn.setText(R.string.quick_note_save);
        saveBtn.setTextColor(Color.WHITE);
        saveBtn.setOnClickListener(v -> {
            saveCanvasBitmap(canvasView.getBitmap());
            closeQuickNoteCanvas();
        });
        topBar.addView(saveBtn);

        Button closeBtn = new Button(this);
        closeBtn.setText(R.string.quick_note_close);
        closeBtn.setTextColor(Color.WHITE);
        closeBtn.setOnClickListener(v -> closeQuickNoteCanvas());
        topBar.addView(closeBtn);

        mQuickNoteOverlay.addView(topBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        mWindowManager.addView(mQuickNoteOverlay, overlayParams);
    }

    private void closeQuickNoteCanvas() {
        if (mQuickNoteOverlay != null && mQuickNoteOverlay.isAttachedToWindow()) {
            mWindowManager.removeView(mQuickNoteOverlay);
            mQuickNoteOverlay = null;
        }
    }

    private void saveCanvasBitmap(Bitmap bitmap) {
        if (bitmap == null) return;
        try {
            File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
            File notesDir = new File(picturesDir, "QuickNotes");
            if (!notesDir.exists()) {
                notesDir.mkdirs();
            }
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File file = new File(notesDir, "QuickNote_" + timeStamp + ".png");
            try (FileOutputStream out = new FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            Toast.makeText(this, R.string.quick_note_saved, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Save error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private static class DrawingCanvasView extends View {
        private final Paint mPaint = new Paint();
        private final Path mPath = new Path();
        private Bitmap mBitmap;
        private Canvas mCanvas;
        private float mPrevX, mPrevY;

        DrawingCanvasView(Context context) {
            super(context);
            mPaint.setAntiAlias(true);
            mPaint.setColor(Color.parseColor("#FFF59D"));
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeJoin(Paint.Join.ROUND);
            mPaint.setStrokeCap(Paint.Cap.ROUND);
            mPaint.setStrokeWidth(6f);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (w > 0 && h > 0) {
                mBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                mCanvas = new Canvas(mBitmap);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (mBitmap != null) {
                canvas.drawBitmap(mBitmap, 0, 0, null);
            }
            canvas.drawPath(mPath, mPaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            float x = event.getX();
            float y = event.getY();

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    mPath.reset();
                    mPath.moveTo(x, y);
                    mPrevX = x;
                    mPrevY = y;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    mPath.quadTo(mPrevX, mPrevY, (x + mPrevX) / 2, (y + mPrevY) / 2);
                    mPrevX = x;
                    mPrevY = y;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    mPath.lineTo(x, y);
                    if (mCanvas != null) {
                        mCanvas.drawPath(mPath, mPaint);
                    }
                    mPath.reset();
                    invalidate();
                    return true;
            }
            return false;
        }

        void clear() {
            if (mBitmap != null) {
                mBitmap.eraseColor(Color.TRANSPARENT);
                mPath.reset();
                invalidate();
            }
        }

        Bitmap getBitmap() {
            return mBitmap;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!PenMode.isEnabled() || !PenMode.isToolbarEnabled()) {
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
