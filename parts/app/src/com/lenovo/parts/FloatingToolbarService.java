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
import android.content.ContentValues;
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
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
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

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class FloatingToolbarService extends Service {
    private static final String CHANNEL_ID = "pen_toolbar_channel";
    private static final int NOTIFICATION_ID = 1003;
    private static final int BUBBLE_SIZE_DP = 48;
    private static final int IDLE_TIMEOUT_MS = 3000;
    private static final String ACTION_SHOW_TOOLBAR =
            "com.lenovo.parts.action.SHOW_TOOLBAR";
    private static final String POSITION_ANCHOR_SETTING = "malbec_toolbar_anchor";
    private static final String POSITION_X_SETTING = "malbec_toolbar_x";
    private static final String POSITION_Y_SETTING = "malbec_toolbar_y";
    private static final int ANCHOR_LEFT = -1;
    private static final int ANCHOR_FLOAT = 0;
    private static final int ANCHOR_RIGHT = 1;

    private static FloatingToolbarService sInstance;

    private WindowManager mWindowManager;
    private FrameLayout mBubbleView;
    private LinearLayout mMenuView;
    private FrameLayout mQuickNoteOverlay;
    private WindowManager.LayoutParams mBubbleParams;
    private WindowManager.LayoutParams mMenuParams;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mMenuOpen = false;
    private int mAnchor = ANCHOR_LEFT;
    private float mNormalizedX = 0.0f;
    private float mNormalizedY = 0.35f;

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
            sInstance.showViews();
            sInstance.toggleMenu();
        } else {
            Intent intent = new Intent(context, FloatingToolbarService.class);
            intent.setAction(ACTION_SHOW_TOOLBAR);
            context.startForegroundService(intent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        loadPosition();

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
        mBubbleView.setScaleX(0.72f);
        mBubbleView.setScaleY(0.72f);
        mBubbleView.setAlpha(1.0f);
        mBubbleView.animate().scaleX(1.0f).scaleY(1.0f)
                .setDuration(220).setInterpolator(new DecelerateInterpolator()).start();
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
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        mBubbleParams.gravity = Gravity.TOP | Gravity.START;
        restoreBubblePosition();

        mBubbleView = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.argb(112, 20, 20, 20));
        bg.setStroke(dpToPx(1), Color.argb(64, 255, 255, 255));
        mBubbleView.setBackground(bg);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_lenovo_parts_bubble);
        icon.setAlpha(0.72f);
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
        if (currentX + size / 2 < screenWidth * 0.25f) {
            mAnchor = ANCHOR_LEFT;
            targetX = 0;
        } else if (currentX + size / 2 > screenWidth * 0.75f) {
            mAnchor = ANCHOR_RIGHT;
            targetX = screenWidth - size;
        } else {
            mAnchor = ANCHOR_FLOAT;
        }

        mNormalizedX = screenWidth > size
                ? (float) targetX / (float) (screenWidth - size) : 0.0f;
        mNormalizedY = screenHeight > size
                ? (float) mBubbleParams.y / (float) (screenHeight - size) : 0.0f;
        savePosition();

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
                restoreBubblePosition();
                mWindowManager.updateViewLayout(mBubbleView, mBubbleParams);
            }
            if (mMenuOpen) {
                closeMenu();
            }
        }, 150);
    }

    private Rect getDisplayBounds() {
        return mWindowManager.getCurrentWindowMetrics().getBounds();
    }

    private void loadPosition() {
        mAnchor = Settings.Secure.getInt(getContentResolver(),
                POSITION_ANCHOR_SETTING, ANCHOR_LEFT);
        mNormalizedX = Settings.Secure.getFloat(getContentResolver(),
                POSITION_X_SETTING, 0.0f);
        mNormalizedY = Settings.Secure.getFloat(getContentResolver(),
                POSITION_Y_SETTING, 0.35f);
    }

    private void savePosition() {
        Settings.Secure.putInt(getContentResolver(), POSITION_ANCHOR_SETTING, mAnchor);
        Settings.Secure.putFloat(getContentResolver(), POSITION_X_SETTING, mNormalizedX);
        Settings.Secure.putFloat(getContentResolver(), POSITION_Y_SETTING, mNormalizedY);
    }

    private void restoreBubblePosition() {
        Rect bounds = getDisplayBounds();
        int availableX = Math.max(0, bounds.width() - mBubbleParams.width);
        int availableY = Math.max(0, bounds.height() - mBubbleParams.height);
        if (mAnchor == ANCHOR_LEFT) {
            mBubbleParams.x = 0;
        } else if (mAnchor == ANCHOR_RIGHT) {
            mBubbleParams.x = availableX;
        } else {
            mBubbleParams.x = Math.round(Math.max(0.0f, Math.min(1.0f, mNormalizedX))
                    * availableX);
        }
        mBubbleParams.y = Math.round(Math.max(0.0f, Math.min(1.0f, mNormalizedY))
                * availableY);
    }

    private void createMenuView() {
        mMenuParams = new WindowManager.LayoutParams(
                dpToPx(220),
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
        addMenuItem(getString(R.string.toolbar_screenshot), () -> {
            PenShortcuts.executeAction(this, PenShortcuts.ACTION_SCREENSHOT);
            closeMenu();
        });
        addMenuItem(getString(R.string.toolbar_open_app), () -> {
            Intent intent = new Intent(this, AppPickerActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            closeMenu();
        });
        addMenuItem(getString(R.string.toolbar_play_pause), () -> {
            PenShortcuts.executeAction(this, PenShortcuts.ACTION_PLAY_PAUSE);
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
        Rect bounds = getDisplayBounds();
        int screenWidth = bounds.width();
        int screenHeight = bounds.height();
        int bubbleRight = mBubbleParams.x + mBubbleParams.width;

        if (mBubbleParams.x < screenWidth / 2) {
            mMenuParams.x = bubbleRight + dpToPx(8);
        } else {
            mMenuParams.x = Math.max(0, mBubbleParams.x - mMenuParams.width - dpToPx(8));
        }
        mMenuParams.x = Math.max(0, Math.min(mMenuParams.x,
                screenWidth - mMenuParams.width));
        mMenuParams.y = Math.max(dpToPx(16), Math.min(mBubbleParams.y,
                screenHeight - dpToPx(330)));

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
            if (saveCanvasBitmap(canvasView.getBitmap())) {
                closeQuickNoteCanvas();
            }
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

    private boolean saveCanvasBitmap(Bitmap bitmap) {
        if (bitmap == null) {
            return false;
        }
        android.net.Uri uri = null;
        try {
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME,
                    "QuickNote_" + timeStamp + ".png");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/QuickNotes");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            uri = getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IllegalStateException("MediaStore insert failed");
            }
            Bitmap outputBitmap = Bitmap.createBitmap(
                    bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas outputCanvas = new Canvas(outputBitmap);
            outputCanvas.drawColor(Color.rgb(18, 18, 18));
            outputCanvas.drawBitmap(bitmap, 0, 0, null);
            try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                if (output == null
                        || !outputBitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw new IllegalStateException("PNG write failed");
                }
            }
            outputBitmap.recycle();
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            getContentResolver().update(uri, values, null, null);
            Toast.makeText(this, R.string.quick_note_saved, Toast.LENGTH_SHORT).show();
            return true;
        } catch (Exception e) {
            if (uri != null) {
                getContentResolver().delete(uri, null, null);
            }
            Toast.makeText(this, R.string.quick_note_save_error, Toast.LENGTH_SHORT).show();
            return false;
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
                Bitmap previous = mBitmap;
                mBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                mCanvas = new Canvas(mBitmap);
                if (previous != null) {
                    mCanvas.drawBitmap(previous, 0, 0, null);
                    previous.recycle();
                }
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
