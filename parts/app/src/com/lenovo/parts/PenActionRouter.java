/*
 * Copyright (C) 2026 hirero-exists <hirerokazuoa@gmail.com>
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 * Compatible with GNU General Public License, Version 2.0 (GPLv2) or later
 * pursuant to Section 3.3 of the Mozilla Public License, v. 2.0.
 */
package com.lenovo.parts;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.media.AudioManager;
import android.media.session.MediaSessionManager;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import vendor.lenovo.hardware.pen.PenEvent;

final class PenActionRouter implements PenHalClient.Listener {
    interface Tool {
        boolean onStylus(PenStylusState state);
        void close();
    }
    private static PenActionRouter sInstance;
    private final Context mContext;
    private final PenHalClient mHal;
    private final PenInputMonitor mMonitor;
    private final ArrayList<Consumer<Boolean>> mListeners = new ArrayList<>();
    private boolean mStarted;
    private boolean mAvailable;
    private boolean mConnected;
    private boolean mPressed;
    private long mGeneration;
    private int mPressedUsage;
    private PenStylusState mStylus;
    private String mForegroundPackage;
    private Tool mTool;
    private long mLastRemoteFeedback = -1;

    static PenActionRouter get(Context context) {
        if (sInstance == null) sInstance = new PenActionRouter(context.getApplicationContext());
        return sInstance;
    }

    private PenActionRouter(Context context) {
        mContext = context;
        mHal = new PenHalClient(this);
        mMonitor = new PenInputMonitor(context, this);
    }

    void start() {
        if (mStarted) return;
        mStarted = true;
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) { onEnabledChanged(); }
        }, new IntentFilter(Intent.ACTION_USER_SWITCHED), Context.RECEIVER_EXPORTED);
        onEnabledChanged();
    }

    void onEnabledChanged() {
        boolean enabled = PenMode.isEnabled() && ActivityManager.getCurrentUser() == mContext.getUserId();
        PenPalmController.get(mContext).onEnabledChanged();
        PenRefreshController.get(mContext).setEnabled(enabled
                && PenPreferences.get(mContext, PenPreferences.REFRESH_CAP, 1) != 0);
        clearInput();
        mHal.setEnabled(enabled);
        mMonitor.setEnabled(enabled);
        if (!enabled) setTool(null);
        if (!enabled) mContext.stopService(new Intent(mContext, QuickNoteService.class));
    }

    void observe(Consumer<Boolean> listener) {
        mListeners.add(listener);
        listener.accept(mAvailable);
    }

    void unobserve(Consumer<Boolean> listener) { mListeners.remove(listener); }

    private void clearInput() {
        mPressed = false;
        mPressedUsage = 0;
        mStylus = null;
    }

    @Override public void onTransport(boolean available) {
        if (!available) PenRefreshController.get(mContext).onTransportLost();
        if (!available) PenPalmController.get(mContext).onTransportLost();
        if (!available) setTool(null);
        mAvailable = available;
        mConnected = false;
        mGeneration = 0;
        clearInput();
        for (Consumer<Boolean> listener : new ArrayList<>(mListeners)) listener.accept(available);
    }

    @Override public void onPenEvent(PenEvent event) {
        if (ActivityManager.getCurrentUser() != mContext.getUserId()) return;
        PenRefreshController.get(mContext).onProximity(event.inRange);
        PenPalmController.get(mContext).onProximity(event.inRange);
        if (event.generation != mGeneration) {
            clearInput();
            mGeneration = event.generation;
        }
        if (event.kind == PenEvent.STATE_CONNECTED) {
            mConnected = true;
            return;
        }
        if (event.kind == PenEvent.STATE_DISCONNECTED) {
            mConnected = false;
            clearInput();
            return;
        }
        if (event.kind == PenEvent.RESET) {
            clearInput();
            return;
        }
        if (event.kind == 6) return;
        if (event.kind == 4 || event.kind == 5) {
            if (!event.pressed || !PenMode.isEnabled()) return;
            PowerManager power = mContext.getSystemService(PowerManager.class);
            KeyguardManager keyguard = mContext.getSystemService(KeyguardManager.class);
            if (power == null) return;
            if (!power.isInteractive() || (keyguard != null && keyguard.isKeyguardLocked())) {
                if (event.kind == 5 && event.usage == 1
                        && PenPreferences.get(mContext, PenPreferences.SCREEN_OFF, 0) != 0) {
                    mContext.startActivity(new Intent(mContext, PenLockedNoteActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                }
                return;
            }
            if (event.kind == 4) {
                int action = PenPreferences.get(mContext,
                        event.usage == 1 ? PenPreferences.LEFT : PenPreferences.RIGHT, 0);
                if (action == 1) PenEditorService.launch(mContext, PenEditorService.EASY_JOT);
                else if (action == 2) PenShortcuts.injectKey(mContext, KeyEvent.KEYCODE_SYSRQ);
                else if (action == 3) mContext.startForegroundService(new Intent(mContext, QuickNoteService.class));
            } else if (event.usage == 1 && PenPreferences.get(mContext, PenPreferences.HELD_NOTE, 1) != 0) {
                mContext.startForegroundService(new Intent(mContext, QuickNoteService.class));
            }
            return;
        }
        mConnected = true;
        if (!PenMode.isEnabled()) return;
        if (!event.pressed) {
            if (event.usage == mPressedUsage) {
                mPressed = false;
                mPressedUsage = 0;
            }
            return;
        }
        if (mPressed && mPressedUsage == event.usage) return;
        mPressed = true;
        mPressedUsage = event.usage;
        refreshContext();
        PowerManager power = mContext.getSystemService(PowerManager.class);
        KeyguardManager keyguard = mContext.getSystemService(KeyguardManager.class);
        if (power == null || !power.isInteractive() || (keyguard != null
                && keyguard.isKeyguardLocked())) return;
        if (remote(event.usage)) return;
        String setting;
        int fallback;
        switch (event.usage) {
            case 0x0c0600:
                setting = PenShortcuts.SINGLE_SETTING;
                fallback = PenShortcuts.ACTION_TOOLBAR;
                break;
            case 0x0c0601:
                setting = PenShortcuts.DOUBLE_SETTING;
                fallback = PenShortcuts.ACTION_NONE;
                break;
            case 0x0c0603:
                setting = PenShortcuts.LONG_SETTING;
                fallback = PenShortcuts.ACTION_NONE;
                break;
            default:
                return;
        }
        PenShortcuts.executeAction(mContext, PenShortcuts.getAction(mContext, setting, fallback));
    }

    boolean onStylus(PenStylusState state) {
        if (ActivityManager.getCurrentUser() != mContext.getUserId()) return false;
        PenPalmController.get(mContext).onStylus(state);
        mStylus = state;
        if (state.action == MotionEvent.ACTION_DOWN
                && PenPreferences.get(mContext, PenPreferences.SOUND, 0) != 0) {
            AudioManager audio = mContext.getSystemService(AudioManager.class);
            if (audio != null) audio.playSoundEffect(AudioManager.FX_KEY_CLICK, 0.25f);
        }
        return mTool != null && mTool.onStylus(state);
    }

    private boolean remote(int usage) {
        if (usage != 0x0c0600 && usage != 0x0c0601) return false;
        int mode = PenPreferences.get(mContext, PenPreferences.REMOTE, 0);
        if (mode < 1 || mode > 3) return false;
        boolean next = usage == 0x0c0600;
        try {
            if (mode == 1) {
                MediaSessionManager sessions = mContext.getSystemService(MediaSessionManager.class);
                if (sessions == null) {
                    remoteUnavailable();
                    return true;
                }
                int code = next ? KeyEvent.KEYCODE_MEDIA_NEXT : KeyEvent.KEYCODE_MEDIA_PREVIOUS;
                long now = SystemClock.uptimeMillis();
                sessions.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0), false);
                sessions.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0), false);
                return true;
            }
            if (mode == 2 && java.util.Set.of("com.microsoft.office.officehub", "com.microsoft.office.officehubrow",
                    "com.microsoft.office.powerpoint", "com.google.android.apps.docs.editors.slides", "cn.wps.moffice_eng",
                    "com.zuxos.wpslauncher", "com.mobisystems.office", "com.infraware.office.link", "com.dragon.read",
                    "com.kmxs.reader", "com.esbook.reader", "com.qq.reader", "com.shuqi.controller", "com.tencent.weread",
                    "com.chaozh.iReaderFree", "com.qidian.QDReader", "com.jd.app.reader", "com.jjwxc.reader").contains(
                            mForegroundPackage == null ? "" : mForegroundPackage)) {
                PenShortcuts.injectKey(mContext, next ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT);
                return true;
            }
            if (mode == 3 && mForegroundPackage != null) {
                for (android.content.pm.ResolveInfo info : mContext.getPackageManager().queryIntentActivities(
                        new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE), 0)) {
                    if (mForegroundPackage.equals(info.activityInfo.packageName)) {
                        PenShortcuts.injectKey(mContext, KeyEvent.KEYCODE_CAMERA);
                        return true;
                    }
                }
            }
        } catch (RuntimeException | LinkageError e) {
            remoteUnavailable();
            return true;
        }
        remoteUnavailable();
        return true;
    }

    private void remoteUnavailable() {
        long now = SystemClock.uptimeMillis();
        if (mLastRemoteFeedback < 0 || now - mLastRemoteFeedback >= 2000) {
            mLastRemoteFeedback = now;
            Toast.makeText(mContext, R.string.pen_remote_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    void setTool(Tool tool) {
        Tool old = mTool;
        mTool = tool;
        if (old != null && old != tool) old.close();
        if (tool == null) FloatingToolbarService.restoreAfterTool();
        else FloatingToolbarService.hideForTool();
    }

    void releaseTool(Tool tool) {
        if (mTool == tool) mTool = null;
        if (mTool == null) FloatingToolbarService.restoreAfterTool();
    }

    PenStylusState getStylusState() { return mStylus; }
    String getForegroundPackage() { return mForegroundPackage; }

    void onDisplayRemoved(int displayId) {
        if (mStylus != null && mStylus.displayId == displayId) mStylus = null;
        if (displayId == android.view.Display.DEFAULT_DISPLAY) setTool(null);
    }

    private void refreshContext() {
        mForegroundPackage = PenForeground.get(mContext).current().packageName;
    }
}
