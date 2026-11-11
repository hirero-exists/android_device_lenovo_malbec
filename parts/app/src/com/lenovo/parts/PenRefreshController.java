/*
 * Copyright (C) 2026 hirero-exists <hirerokazuoa@gmail.com>
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 * Compatible with GNU General Public License, Version 2.0 (GPLv2) or later
 * pursuant to Section 3.3 of the Mozilla Public License, v. 2.0.
 */
package com.lenovo.parts;

import android.content.BroadcastReceiver;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.DisplayManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import java.util.ArrayList;
import java.util.function.Consumer;

final class PenRefreshController implements DisplayManager.DisplayListener {
    enum State { DISABLED, IDLE, WITHIN_LIMIT, PENDING, ACTIVE, UNAVAILABLE }
    private static final long CONFIRM_MS = 2000;
    private static final long POLL_MS = 200;
    private static PenRefreshController sInstance;
    private final DisplayManager mDisplays;
    private final PowerManager mPower;
    private final Context mContext;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final PenRefreshHold mHold = new PenRefreshHold();
    private final ArrayList<Consumer<State>> mListeners = new ArrayList<>();
    private final Runnable mRelease = this::update;
    private final Runnable mConfirm = this::update;
    private State mState = State.DISABLED;
    private boolean mEnabled;
    private WindowManager mWindows;
    private View mPixel;
    private WindowManager.LayoutParams mParams;
    private long mDeadline;
    private int mWidth;
    private int mHeight;
    private int mModeId;

    static PenRefreshController get(Context context) {
        if (sInstance == null) sInstance = new PenRefreshController(context.getApplicationContext());
        return sInstance;
    }

    private PenRefreshController(Context context) {
        mContext = context;
        mDisplays = context.getSystemService(DisplayManager.class);
        mPower = context.getSystemService(PowerManager.class);
        if (mDisplays != null) mDisplays.registerDisplayListener(this, mMain);
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())
                        || Intent.ACTION_USER_SWITCHED.equals(intent.getAction())) {
                    mHold.clear();
                    state(release() ? (mEnabled ? State.IDLE : State.DISABLED) : State.UNAVAILABLE);
                } else {
                    update();
                }
            }
        }, filter, Context.RECEIVER_NOT_EXPORTED);
    }

    void setEnabled(boolean enabled) {
        mEnabled = enabled;
        if (!enabled) {
            mHold.clear();
        }
        update();
    }

    void onProximity(boolean inRange) {
        boolean accepting = mEnabled && mPower != null && mPower.isInteractive()
                && ActivityManager.getCurrentUser() == mContext.getUserId();
        if (accepting) {
            mHold.proximity(inRange, SystemClock.uptimeMillis());
        } else {
            mHold.clear();
        }
        update();
    }

    void onTransportLost() {
        mHold.clear();
        update();
    }

    void observe(Consumer<State> listener) {
        mListeners.add(listener);
        listener.accept(mState);
    }

    void unobserve(Consumer<State> listener) { mListeners.remove(listener); }

    private void state(State state) {
        if (state == mState) return;
        mState = state;
        for (Consumer<State> listener : new ArrayList<>(mListeners)) listener.accept(state);
    }

    private void update() {
        mMain.removeCallbacks(mRelease);
        long now = SystemClock.uptimeMillis();
        if (!mEnabled || ActivityManager.getCurrentUser() != mContext.getUserId()
                || mPower == null || !mPower.isInteractive() || !mHold.active(now)) {
            state(release() ? (mEnabled ? State.IDLE : State.DISABLED) : State.UNAVAILABLE);
            return;
        }
        long delay = mHold.releaseDelay(now);
        if (delay > 0) mMain.postDelayed(mRelease, delay);
        try {
            Display display = mDisplays == null ? null : mDisplays.getDisplay(Display.DEFAULT_DISPLAY);
            if (display == null) {
                release();
                state(State.UNAVAILABLE);
                return;
            }
            Display.Mode current = display.getMode();
            int width = current.getPhysicalWidth();
            int height = current.getPhysicalHeight();
            if (mPixel == null && eligible(current)) {
                mMain.removeCallbacks(mConfirm);
                mDeadline = 0;
                state(State.WITHIN_LIMIT);
                return;
            }
            Display.Mode selected = null;
            for (Display.Mode mode : display.getSupportedModes()) {
                if (mode.getPhysicalWidth() == width && mode.getPhysicalHeight() == height
                        && exact120(mode)) {
                    selected = mode;
                    break;
                }
            }
            if (selected == null) {
                release();
                state(State.UNAVAILABLE);
                return;
            }
            if (mPixel == null) {
                Context windowContext = mContext.createWindowContext(display,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
                mWindows = windowContext.getSystemService(WindowManager.class);
                if (mWindows == null) throw new IllegalStateException("No window manager");
                mPixel = new View(windowContext);
                mPixel.setBackgroundColor(Color.BLACK);
                mParams = new WindowManager.LayoutParams(1, 1,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT);
                mParams.setTitle("Pen refresh limit");
                mParams.gravity = Gravity.TOP | Gravity.LEFT;
                mParams.alpha = 0.01f;
                mParams.windowAnimations = 0;
                mParams.inputFeatures |= WindowManager.LayoutParams.INPUT_FEATURE_NO_INPUT_CHANNEL;
                mParams.setSystemApplicationOverlay(true);
                mParams.preferredDisplayModeId = selected.getModeId();
                mParams.preferredMinDisplayRefreshRate = 120;
                mParams.preferredMaxDisplayRefreshRate = 120;
                mWindows.addView(mPixel, PartsOverlayWindows.prepare(mParams));
                mWidth = width;
                mHeight = height;
                mModeId = selected.getModeId();
                mDeadline = now + CONFIRM_MS;
                state(State.PENDING);
            } else if (mWidth != width || mHeight != height || mModeId != selected.getModeId()) {
                mParams.preferredDisplayModeId = selected.getModeId();
                mWindows.updateViewLayout(mPixel, PartsOverlayWindows.prepare(mParams));
                mWidth = width;
                mHeight = height;
                mModeId = selected.getModeId();
                mDeadline = now + CONFIRM_MS;
                state(State.PENDING);
            }
            boolean confirmed = mPixel.isAttachedToWindow()
                    && current.getPhysicalWidth() == mWidth
                    && current.getPhysicalHeight() == mHeight && eligible(current);
            mMain.removeCallbacks(mConfirm);
            if (confirmed) {
                mDeadline = 0;
                state(State.ACTIVE);
            } else {
                if (mState == State.ACTIVE) mDeadline = now + CONFIRM_MS;
                if (mDeadline > now) {
                    state(State.PENDING);
                    mMain.postDelayed(mConfirm, Math.min(POLL_MS, mDeadline - now));
                } else {
                    mDeadline = 0;
                    state(State.UNAVAILABLE);
                }
            }
        } catch (RuntimeException | LinkageError e) {
            Log.w("PartsPenRefresh", "Pen display limit unavailable", e);
            release();
            state(State.UNAVAILABLE);
        }
    }

    private static boolean eligible(Display.Mode mode) {
        float peak = mode.getRefreshRate();
        float vsync = mode.getVsyncRate();
        return !mode.isSynthetic() && Float.isFinite(peak) && peak > 0 && peak <= 120.1f
                && Float.isFinite(vsync) && vsync > 0 && vsync <= 120.1f;
    }

    private static boolean exact120(Display.Mode mode) {
        return eligible(mode) && Math.abs(mode.getRefreshRate() - 120f) <= 0.1f
                && Math.abs(mode.getVsyncRate() - 120f) <= 0.1f;
    }

    private boolean release() {
        mMain.removeCallbacks(mRelease);
        mMain.removeCallbacks(mConfirm);
        mDeadline = 0;
        if (mPixel == null) return true;
        try {
            mWindows.removeViewImmediate(mPixel);
            if (mPixel.isAttachedToWindow()) throw new IllegalStateException("Window still attached");
        }
        catch (RuntimeException | LinkageError e) {
            if (!mPixel.isAttachedToWindow()) {
                mPixel = null;
                mWindows = null;
                mParams = null;
                return true;
            }
            if (mState != State.UNAVAILABLE) {
                Log.w("PartsPenRefresh", "Unable to release pen display limit", e);
            }
            mMain.postDelayed(mRelease, 3000);
            return false;
        }
        mPixel = null;
        mWindows = null;
        mParams = null;
        return true;
    }

    @Override public void onDisplayAdded(int displayId) {
        if (displayId == Display.DEFAULT_DISPLAY) {
            update();
        }
    }
    @Override public void onDisplayChanged(int displayId) {
        if (displayId == Display.DEFAULT_DISPLAY) update();
    }
    @Override public void onDisplayRemoved(int displayId) {
        if (displayId == Display.DEFAULT_DISPLAY) {
            mHold.clear();
            release();
            state(mEnabled ? State.UNAVAILABLE : State.DISABLED);
        }
    }
}
