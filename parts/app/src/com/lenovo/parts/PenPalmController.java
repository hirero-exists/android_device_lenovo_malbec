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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.view.MotionEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

final class PenPalmController {
    private static final String INTERFACE = "vendor.lenovo.hardware.touchscreen.ITouchscreen";
    private static PenPalmController sInstance;
    private final Context mContext;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Handler mWorker;
    private final PenPalmSession mSession;
    private final ArrayList<Consumer<String>> mListeners = new ArrayList<>();
    private final Object mLock = new Object();
    private final Set<String> mGames;
    private Integer mDesired;
    private long mRevision;
    private boolean mQueued;
    private volatile IBinder mBinder;
    private IBinder.DeathRecipient mDeath;
    private boolean mStylus;
    private boolean mNativeProximity;
    private boolean mNativeAvailable;
    private String mStatus;
    private final Runnable mDecide = this::decide;
    private final Runnable mRetry = this::queue;
    private final Runnable mStylusExpired = () -> { mStylus = false; schedule(); };

    static PenPalmController get(Context context) {
        if (sInstance == null) sInstance = new PenPalmController(context.getApplicationContext());
        return sInstance;
    }
    private PenPalmController(Context context) {
        mContext = context;
        SharedPreferences ownership = context.createDeviceProtectedStorageContext()
                .getSharedPreferences("pen_palm_ownership", Context.MODE_PRIVATE);
        int captured = ownership.getInt("baseline", -1);
        mSession = new PenPalmSession(PenPalmSession.valid(captured) ? captured : null, baseline -> {
            SharedPreferences.Editor editor = ownership.edit();
            if (baseline == null) editor.remove("baseline");
            else editor.putInt("baseline", baseline);
            return editor.commit();
        });
        mStatus = context.getString(R.string.pen_palm_checking);
        mGames = new HashSet<>(Arrays.asList(context.getResources().getStringArray(R.array.pen_palm_game_apps)));
        HandlerThread thread = new HandlerThread("PenPalm");
        thread.start();
        mWorker = new Handler(thread.getLooper());
        PenForeground.get(context).observe(state -> schedule());
        ContentObserver settings = new ContentObserver(mMain) {
            @Override public void onChange(boolean self) { schedule(); }
        };
        for (String key : new String[] { PenPreferences.PALM, PenPreferences.PALM_DYNAMIC,
                PenPreferences.PALM_APPS }) {
            context.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(key), false, settings);
        }
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        context.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                mStylus = false;
                mNativeAvailable = false;
                mNativeProximity = false;
                mMain.removeCallbacks(mStylusExpired);
                PenForeground.get(c).refresh();
                // Invalidate an in-flight decision before the debounce runs.
                request(null);
                schedule();
            }
        }, filter, Context.RECEIVER_EXPORTED);
        schedule();
    }
    void observe(Consumer<String> listener) { mListeners.add(listener); listener.accept(mStatus); }
    void unobserve(Consumer<String> listener) { mListeners.remove(listener); }
    void onEnabledChanged() {
        mStylus = false;
        mNativeAvailable = false;
        mNativeProximity = false;
        mMain.removeCallbacks(mStylusExpired);
        request(null);
        schedule();
    }
    void onProximity(boolean present) {
        boolean changed = !mNativeAvailable || mNativeProximity != present;
        mNativeAvailable = true;
        mNativeProximity = present;
        if (changed) schedule();
    }
    void onTransportLost() {
        mNativeAvailable = false;
        mNativeProximity = false;
        mStylus = false;
        mMain.removeCallbacks(mStylusExpired);
        schedule();
    }
    void onStylus(PenStylusState state) {
        boolean present = state.action != MotionEvent.ACTION_HOVER_EXIT
                && state.action != MotionEvent.ACTION_CANCEL;
        if (mStylus != present) { mStylus = present; schedule(); }
        mMain.removeCallbacks(mStylusExpired);
        if (present) mMain.postDelayed(mStylusExpired, 1500);
    }
    static Set<String> selectedApps(Context context) {
        String saved = Settings.Secure.getString(context.getContentResolver(), PenPreferences.PALM_APPS);
        return new HashSet<>(Arrays.asList(saved == null
                ? context.getResources().getStringArray(R.array.pen_palm_default_apps)
                : saved.isEmpty() ? new String[0] : saved.split(",")));
    }
    private void schedule() {
        mMain.removeCallbacks(mDecide);
        mMain.postDelayed(mDecide, 100);
    }
    private void decide() {
        PowerManager power = mContext.getSystemService(PowerManager.class);
        boolean active = PenMode.isEnabled() && ActivityManager.getCurrentUser() == mContext.getUserId()
                && power != null && power.isInteractive()
                && PenPreferences.get(mContext, PenPreferences.PALM, 0) != 0;
        Integer desired = null;
        if (active) {
            PenForeground.State foreground = PenForeground.get(mContext).current();
            boolean dynamic = PenPreferences.get(mContext, PenPreferences.PALM_DYNAMIC, 1) != 0;
            if (dynamic && foreground.fullscreen
                    && (foreground.game || mGames.contains(foreground.packageName))) desired = 2;
            else if ((dynamic && (mNativeAvailable ? mNativeProximity : mStylus))
                    || selectedApps(mContext).contains(foreground.packageName)) desired = 1;
            else if (foreground.packageName != null) desired = 0;
        }
        request(desired);
    }
    private void request(Integer desired) {
        boolean changed;
        synchronized (mLock) {
            changed = !java.util.Objects.equals(mDesired, desired);
            mDesired = desired;
            mRevision++;
        }
        if (changed) {
            mStatus = mContext.getString(R.string.pen_palm_checking);
            for (Consumer<String> listener : new ArrayList<>(mListeners)) listener.accept(mStatus);
        }
        queue();
    }
    private void queue() {
        synchronized (mLock) {
            if (mQueued) return;
            mQueued = true;
        }
        mWorker.post(this::drain);
    }
    private void drain() {
        final long revision;
        final Integer desired;
        synchronized (mLock) { revision = mRevision; desired = mDesired; }
        String status;
        boolean retry = false;
        try {
            IBinder binder = connect();
            if (binder == null) throw new RemoteException("Palm provider absent");
            int actual = mSession.apply(new PenPalmSession.Provider() {
                @Override public int get() throws RemoteException { return read(binder); }
                @Override public boolean set(int mode) throws RemoteException { return write(binder, mode); }
            }, desired);
            status = mContext.getString(desired == null ? R.string.pen_palm_inactive : R.string.pen_palm_applied,
                    mContext.getResources().getStringArray(R.array.pen_palm_modes)[actual]);
        } catch (Exception e) {
            status = mContext.getString(mSession.needsRestore()
                    ? R.string.pen_palm_restore_pending : R.string.pen_palm_unavailable);
            retry = true;
            disconnect();
        }
        final String result = status;
        mMain.post(() -> {
            synchronized (mLock) { if (revision != mRevision) return; }
            mStatus = result;
            for (Consumer<String> listener : new ArrayList<>(mListeners)) listener.accept(result);
        });
        boolean again;
        synchronized (mLock) { mQueued = false; again = revision != mRevision; }
        mWorker.removeCallbacks(mRetry);
        if (again) queue();
        else if (retry) mWorker.postDelayed(mRetry, 3000);
    }
    private IBinder connect() throws RemoteException {
        if (mBinder != null && mBinder.isBinderAlive()) return mBinder;
        disconnect();
        IBinder binder = ServiceManager.checkService(INTERFACE + "/default");
        if (binder == null) return null;
        IBinder.DeathRecipient death = () -> {
            final long revision;
            synchronized (mLock) { revision = ++mRevision; }
            mMain.post(() -> {
                synchronized (mLock) { if (revision != mRevision || binder != mBinder) return; }
                mStatus = mContext.getString(R.string.pen_palm_unavailable);
                for (Consumer<String> listener : new ArrayList<>(mListeners)) listener.accept(mStatus);
            });
            queue();
        };
        binder.linkToDeath(death, 0);
        mBinder = binder;
        mDeath = death;
        return binder;
    }
    private void disconnect() {
        if (mBinder != null && mDeath != null) {
            try { mBinder.unlinkToDeath(mDeath, 0); }
            catch (RuntimeException ignored) { }
        }
        mBinder = null;
        mDeath = null;
    }
    private static int read(IBinder binder) throws RemoteException {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(INTERFACE);
            if (!binder.transact(1, data, reply, 0)) throw new RemoteException("Palm getter unsupported");
            reply.readException();
            return reply.readInt();
        } finally { data.recycle(); reply.recycle(); }
    }
    private static boolean write(IBinder binder, int mode) throws RemoteException {
        if (!PenPalmSession.valid(mode)) throw new IllegalArgumentException("Invalid palm mode");
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(INTERFACE);
            data.writeInt(mode);
            if (!binder.transact(7, data, reply, 0)) throw new RemoteException("Palm setter unsupported");
            reply.readException();
            return reply.readBoolean();
        } finally { data.recycle(); reply.recycle(); }
    }
}
