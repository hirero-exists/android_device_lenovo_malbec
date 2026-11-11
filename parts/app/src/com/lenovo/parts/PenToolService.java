/*
 * Copyright (C) 2026 hirero-exists <hirerokazuoa@gmail.com>
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 * Compatible with GNU General Public License, Version 2.0 (GPLv2) or later
 * pursuant to Section 3.3 of the Mozilla Public License, v. 2.0.
 */
package com.lenovo.parts;

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
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Display;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PenToolService extends Service implements PenActionRouter.Tool {
    static final String MODE = "mode";
    static final int POINTER = 1, MAGNIFIER = 2, CROP = 3;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mIo = Executors.newSingleThreadExecutor();
    private WindowManager mWindows;
    private ToolView mView;
    private LinearLayout mBar;
    private Bitmap mBackground;
    private int mMode;
    private int mSession;
    private boolean mClosed, mCapturePending;
    private boolean mExporting;
    private float mX = -1000, mY = -1000;
    private long mCaptureAt;
    private final RectF mSelection = new RectF();
    private float mStartX, mStartY;
    private final BroadcastReceiver mScreen = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { close(); }
    };

    static void launch(Context context, int mode) {
        context.startForegroundService(new Intent(context, PenToolService.class).putExtra(MODE, mode));
    }

    @Override public void onCreate() {
        super.onCreate();
        mWindows = getSystemService(WindowManager.class);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("pen_tools", getString(R.string.pen_tools_title),
                NotificationManager.IMPORTANCE_LOW));
        startForeground(1010, new Notification.Builder(this, "pen_tools")
                .setSmallIcon(R.drawable.ic_lenovo_parts).setContentTitle(getString(R.string.pen_tools_title))
                .setOngoing(true).build());
        registerReceiver(mScreen, new IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED);
    }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent == null) return START_NOT_STICKY;
        mSession++;
        clearWindows();
        mClosed = false;
        mCapturePending = mExporting = false;
        mSelection.setEmpty();
        mX = mY = -1000;
        mCaptureAt = 0;
        mMode = intent.getIntExtra(MODE, 0);
        if (mMode != POINTER && mMode != MAGNIFIER && mMode != CROP) { close(); return START_NOT_STICKY; }
        PenActionRouter.get(this).setTool(this);
        final int session = mSession;
        Choreographer frames = Choreographer.getInstance();
        frames.postFrameCallback(first -> frames.postFrameCallback(second -> {
            if (!mClosed && session == mSession) showTool();
        }));
        return START_NOT_STICKY;
    }

    private void showTool() {
        if (mMode == CROP) {
            mBackground = PenCapture.screen(Display.DEFAULT_DISPLAY);
            if (mBackground == null) { fail(); return; }
        }
        mView = new ToolView();
        WindowManager.LayoutParams scene = new WindowManager.LayoutParams(-1, -1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | (mMode == CROP ? 0 : WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE), PixelFormat.TRANSLUCENT);
        scene.gravity = Gravity.TOP | Gravity.LEFT;
        scene.setFitInsetsTypes(0);
        mWindows.addView(mView, PartsOverlayWindows.prepare(scene));
        mBar = new LinearLayout(this);
        mBar.setBackgroundColor(0xee202024);
        if (mMode == CROP) {
            button(R.string.pen_save_image, () -> export(false));
            button(R.string.pen_share_image, () -> export(true));
        }
        button(R.string.pen_close_tool, this::close);
        WindowManager.LayoutParams bar = new WindowManager.LayoutParams(-2, -2,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        bar.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        bar.y = dp(40);
        mWindows.addView(mBar, PartsOverlayWindows.prepare(bar));
    }

    private void button(int title, Runnable action) {
        Button button = new Button(this);
        button.setText(title);
        button.setOnClickListener(v -> action.run());
        mBar.addView(button);
    }

    @Override public boolean onStylus(PenStylusState state) {
        if (mClosed || mView == null || state.displayId != Display.DEFAULT_DISPLAY || mMode == CROP) return false;
        int[] location = new int[2];
        mBar.getLocationOnScreen(location);
        if (state.x >= location[0] && state.x <= location[0] + mBar.getWidth()
                && state.y >= location[1] && state.y <= location[1] + mBar.getHeight()) return false;
        mX = state.x;
        mY = state.y;
        if (state.action == MotionEvent.ACTION_HOVER_EXIT || state.action == MotionEvent.ACTION_CANCEL) mX = -1000;
        if (mMode == MAGNIFIER && !mCapturePending && state.eventTime - mCaptureAt >= 100) {
            mCaptureAt = state.eventTime;
            mCapturePending = true;
            final int session = mSession;
            final ToolView view = mView;
            view.setVisibility(View.INVISIBLE);
            view.postOnAnimation(() -> view.postOnAnimation(() -> {
                if (mClosed || session != mSession) return;
                Bitmap bitmap = PenCapture.screen(Display.DEFAULT_DISPLAY);
                if (bitmap == null) { fail(); return; }
                if (mBackground != null) mBackground.recycle();
                mBackground = bitmap;
                mCapturePending = false;
                mView.setVisibility(View.VISIBLE);
                mView.invalidate();
            }));
        }
        mView.invalidate();
        return true;
    }

    private void export(boolean share) {
        if (mExporting || mClosed) return;
        if (mBackground == null || mSelection.width() < 2 || mSelection.height() < 2) {
            Toast.makeText(this, R.string.pen_select_area, Toast.LENGTH_SHORT).show();
            return;
        }
        int left = Math.max(0, Math.min((int) mSelection.left, mBackground.getWidth() - 1));
        int top = Math.max(0, Math.min((int) mSelection.top, mBackground.getHeight() - 1));
        int width = Math.max(1, Math.min((int) mSelection.width(), mBackground.getWidth() - left));
        int height = Math.max(1, Math.min((int) mSelection.height(), mBackground.getHeight() - top));
        Bitmap selection = Bitmap.createBitmap(mBackground, left, top, width, height);
        Bitmap cropped = selection.copy(Bitmap.Config.ARGB_8888, false);
        if (selection != mBackground) selection.recycle();
        if (cropped == null) { fail(); return; }
        mExporting = true;
        final int session = mSession;
        mIo.execute(() -> {
            try {
                Uri uri = PenCapture.save(this, cropped, false);
                mHandler.post(() -> {
                    if (mClosed || session != mSession) return;
                    if (share) PenCapture.share(this, uri, false);
                    else Toast.makeText(this, R.string.quick_note_saved, Toast.LENGTH_SHORT).show();
                    close();
                });
            } catch (Exception e) { mHandler.post(() -> { if (!mClosed && session == mSession) fail(); }); }
            finally { cropped.recycle(); }
        });
    }

    private void fail() {
        Toast.makeText(this, R.string.pen_capture_failed, Toast.LENGTH_LONG).show();
        close();
    }

    @Override public void close() {
        mClosed = true;
        mSession++;
        clearWindows();
        PenActionRouter.get(this).releaseTool(this);
        stopSelf();
    }
    private void clearWindows() {
        if (mView != null && mView.isAttachedToWindow()) mWindows.removeViewImmediate(mView);
        if (mBar != null && mBar.isAttachedToWindow()) mWindows.removeViewImmediate(mBar);
        mView = null;
        mBar = null;
        if (mBackground != null) mBackground.recycle();
        mBackground = null;
    }
    @Override public void onConfigurationChanged(Configuration config) { super.onConfigurationChanged(config); close(); }
    @Override public void onDestroy() {
        mClosed = true;
        PenActionRouter.get(this).releaseTool(this);
        unregisterReceiver(mScreen);
        mHandler.removeCallbacksAndMessages(null);
        clearWindows();
        mIo.shutdown();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    private final class ToolView extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ToolView() { super(PenToolService.this); }
        @Override protected void onDraw(Canvas canvas) {
            if (mMode == CROP && mBackground != null) {
                canvas.drawBitmap(mBackground, 0, 0, null);
                mPaint.setColor(0x66000000);
                canvas.drawRect(0, 0, getWidth(), getHeight(), mPaint);
                mPaint.setColor(0xff00bfff);
                mPaint.setStyle(Paint.Style.STROKE);
                mPaint.setStrokeWidth(dp(2));
                canvas.drawRect(mSelection, mPaint);
                mPaint.setStyle(Paint.Style.FILL);
            } else if (mMode == POINTER) {
                mPaint.setColor(0xcfff3040);
                canvas.drawCircle(mX, mY, dp(9), mPaint);
            } else if (mMode == MAGNIFIER && mBackground != null && mX >= 0) {
                int radius = dp(38), size = dp(140);
                int cx = Math.max(radius, Math.min((int) mX, mBackground.getWidth() - radius));
                int cy = Math.max(radius, Math.min((int) mY, mBackground.getHeight() - radius));
                Rect source = new Rect(cx - radius, cy - radius, cx + radius, cy + radius);
                float x = Math.max(0, Math.min(mX + dp(28), getWidth() - size));
                float y = Math.max(0, Math.min(mY - size - dp(24), getHeight() - size));
                canvas.drawBitmap(mBackground, source, new RectF(x, y, x + size, y + size), mPaint);
            }
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (mMode != CROP) return false;
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                mStartX = event.getX(); mStartY = event.getY();
                mSelection.set(mStartX, mStartY, mStartX, mStartY);
            } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE
                    || event.getActionMasked() == MotionEvent.ACTION_UP) {
                mSelection.set(Math.min(mStartX, event.getX()), Math.min(mStartY, event.getY()),
                        Math.max(mStartX, event.getX()), Math.max(mStartY, event.getY()));
            }
            invalidate();
            return true;
        }
    }
}
