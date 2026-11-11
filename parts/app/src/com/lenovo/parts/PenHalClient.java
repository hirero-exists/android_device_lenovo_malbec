/*
 * Copyright (C) 2026 hirero-exists <hirerokazuoa@gmail.com>
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 * Compatible with GNU General Public License, Version 2.0 (GPLv2) or later
 * pursuant to Section 3.3 of the Mozilla Public License, v. 2.0.
 */
package com.lenovo.parts;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicLong;
import vendor.lenovo.hardware.pen.IPen;
import vendor.lenovo.hardware.pen.IPenCallback;
import vendor.lenovo.hardware.pen.PenEvent;

final class PenHalClient {
    interface Listener {
        void onTransport(boolean available);
        void onPenEvent(PenEvent event);
    }

    private static final String SERVICE = "vendor.lenovo.hardware.pen.IPen/default";
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Listener mListener;
    private final AtomicLong mEpoch = new AtomicLong();
    private final PenEventOrder mOrder = new PenEventOrder();
    private final HandlerThread mThread = new HandlerThread("PartsPenHal");
    private Handler mWorker;
    private IPen mService;
    private IBinder mBinder;
    private IPenCallback mCallback;
    private IBinder.DeathRecipient mDeath;
    private boolean mEnabled;
    private final ArrayDeque<Delivery> mDeliveries = new ArrayDeque<>();
    private boolean mDrainPending;
    private volatile long mRejectedEpoch = -1;

    private static final class Delivery {
        final long epoch;
        final PenEvent event;
        Delivery(long epoch, PenEvent event) { this.epoch = epoch; this.event = event; }
    }

    PenHalClient(Listener listener) {
        mListener = listener;
        mThread.start();
        mWorker = new Handler(mThread.getLooper());
    }

    void setEnabled(boolean enabled) {
        if (mEnabled == enabled) return;
        mEnabled = enabled;
        long epoch = mEpoch.incrementAndGet();
        mOrder.reset();
        mListener.onTransport(false);
        mWorker.post(() -> {
            disconnect();
            if (enabled) connect(epoch);
        });
    }

    private void disconnect() {
        if (mService != null && mCallback != null) {
            try { mService.unregisterCallback(mCallback); }
            catch (RemoteException | RuntimeException ignored) { }
        }
        if (mBinder != null && mDeath != null) mBinder.unlinkToDeath(mDeath, 0);
        mService = null;
        mBinder = null;
        mCallback = null;
        mDeath = null;
    }

    private void connect(long epoch) {
        if (mEpoch.get() != epoch) return;
        IBinder binder = ServiceManager.checkService(SERVICE);
        if (binder == null) {
            mWorker.postDelayed(() -> connect(epoch), 3000);
            return;
        }
        IPen service = IPen.Stub.asInterface(binder);
        IPenCallback callback = new IPenCallback.Stub() {
            @Override public void onEvent(PenEvent event) {
                deliver(epoch, event);
            }
            @Override public int getInterfaceVersion() { return VERSION; }
            @Override public String getInterfaceHash() { return HASH; }
        };
        IBinder.DeathRecipient death = () -> mWorker.post(() -> retry(epoch));
        try {
            binder.linkToDeath(death, 0);
            mBinder = binder;
            mDeath = death;
            mService = service;
            mCallback = callback;
            service.registerCallback(callback);
            mMain.post(() -> {
                if (mEpoch.get() == epoch) mListener.onTransport(true);
            });
            deliver(epoch, service.getState());
        } catch (RemoteException | RuntimeException e) {
            Log.w("PartsPenHal", "Pen event transport unavailable", e);
            retry(epoch);
        }
    }

    private void retry(long epoch) {
        if (mEpoch.get() != epoch) return;
        long next = mEpoch.incrementAndGet();
        disconnect();
        mMain.post(() -> {
            if (mEpoch.get() != next) return;
            mOrder.reset();
            mListener.onTransport(false);
        });
        mWorker.postDelayed(() -> connect(next), 3000);
    }

    private void deliver(long epoch, PenEvent event) {
        if (event == null || mEpoch.get() != epoch || mRejectedEpoch == epoch) return;
        synchronized (mDeliveries) {
            if (mDeliveries.size() >= 64) {
                mRejectedEpoch = epoch;
                mDeliveries.clear();
                mWorker.post(() -> retry(epoch));
                return;
            }
            mDeliveries.addLast(new Delivery(epoch, event));
            if (mDrainPending) return;
            mDrainPending = true;
        }
        mMain.post(this::drain);
    }

    private void drain() {
        for (;;) {
            Delivery delivery;
            synchronized (mDeliveries) {
                delivery = mDeliveries.pollFirst();
                if (delivery == null) {
                    mDrainPending = false;
                    return;
                }
            }
            PenEvent event = delivery.event;
            if (mEpoch.get() == delivery.epoch && mRejectedEpoch != delivery.epoch && mEnabled
                    && mOrder.accept(event.generation, event.sequence, event.monotonicNanos,
                            event.kind, event.usage)) mListener.onPenEvent(event);
        }
    }
}
