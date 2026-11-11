/*
 * Copyright (C) 2026 hirero-exists <hirerokazuoa@gmail.com>
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 * Compatible with GNU General Public License, Version 2.0 (GPLv2) or later
 * pursuant to Section 3.3 of the Mozilla Public License, v. 2.0.
 */
package com.lenovo.parts;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

/** Connection and identity only. Hardware gestures are owned by the native input path. */
final class PenGattService {
    private static final long TIMEOUT_MS = 10000;
    private static final long RETRY_MS = 3000;
    private static final long BATTERY_POLL_MS = 60000;
    private static final String HINT = "validated_pen_address";
    private static PenGattService sInstance;
    private final Context mContext;
    private final SharedPreferences mPrefs;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<BluetoothGattCharacteristic> mQueue = new ArrayDeque<>();
    private final ArrayList<Consumer<PenIdentity>> mListeners = new ArrayList<>();
    private BluetoothGatt mGatt;
    private BluetoothDevice mDevice;
    private BluetoothGattCharacteristic mPending;
    private Runnable mTimeout;
    private Runnable mBatteryPoll;
    private long mGeneration;
    private PenIdentity.Connection mConnection = PenIdentity.Connection.DISCONNECTED;
    private String mSerial;
    private String mFirmware;
    private String mModel;
    private int mBattery = -1;
    private boolean mPnpValid;
    private int mPnpSource = -1, mPnpVendor = -1, mPnpProduct = -1, mPnpVersion = -1;
    private long mConnectedAt;
    private final NotificationManager mNotifications;

    private static UUID uuid(int value) {
        return UUID.fromString(String.format(Locale.ROOT, "0000%04x-0000-1000-8000-00805f9b34fb", value));
    }

    PenGattService(Context context) {
        mContext = context.getApplicationContext();
        mPrefs = mContext.createDeviceProtectedStorageContext()
                .getSharedPreferences("pen_identity", Context.MODE_PRIVATE);
        mNotifications = mContext.getSystemService(NotificationManager.class);
        mNotifications.createNotificationChannel(new NotificationChannel("pen_alert_channel",
                mContext.getString(R.string.pen_enabled_title), NotificationManager.IMPORTANCE_DEFAULT));
        sInstance = this;
    }

    void start() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                mHandler.post(PenGattService.this::checkAndConnect);
            }
        }, filter, Context.RECEIVER_EXPORTED);
        checkAndConnect();
    }

    static void onPenEnabledChanged() {
        if (sInstance != null) sInstance.mHandler.post(sInstance::checkAndConnect);
    }

    static String getAlias() {
        return sInstance != null && sInstance.mDevice != null
                && sInstance.mConnection == PenIdentity.Connection.VERIFIED ? sInstance.mDevice.getAlias() : null;
    }

    static long lastDisconnected() {
        return sInstance == null ? 0 : sInstance.mPrefs.getLong("last_disconnected", 0);
    }

    static boolean rename(String name) {
        if (sInstance == null || sInstance.mDevice == null
                || sInstance.mConnection != PenIdentity.Connection.VERIFIED
                || name == null || name.trim().isEmpty() || name.length() > 64) return false;
        boolean success = sInstance.mDevice.setAlias(name.trim()) == android.bluetooth.BluetoothStatusCodes.SUCCESS;
        if (success) sInstance.publish();
        return success;
    }

    static void observe(Consumer<PenIdentity> listener) {
        if (sInstance == null) return;
        sInstance.mListeners.add(listener);
        listener.accept(sInstance.snapshot());
    }

    static void unobserve(Consumer<PenIdentity> listener) {
        if (sInstance != null) sInstance.mListeners.remove(listener);
    }

    private PenIdentity snapshot() {
        boolean verified = mConnection == PenIdentity.Connection.VERIFIED;
        return new PenIdentity(mGeneration, mConnection, verified ? mSerial : null,
                verified ? mFirmware : null, verified ? mModel : null, verified ? mBattery : -1,
                verified ? mPnpSource : -1, verified ? mPnpVendor : -1,
                verified ? mPnpProduct : -1, verified ? mPnpVersion : -1,
                verified && mDevice != null ? mDevice.getName() : null);
    }

    private void publish() {
        PenIdentity value = snapshot();
        for (Consumer<PenIdentity> listener : new ArrayList<>(mListeners)) listener.accept(value);
    }

    private void clear(PenIdentity.Connection next) {
        if (mTimeout != null) mHandler.removeCallbacks(mTimeout);
        if (mBatteryPoll != null) mHandler.removeCallbacks(mBatteryPoll);
        mBatteryPoll = null;
        mTimeout = null;
        BluetoothGatt old = mGatt;
        mGatt = null;
        if (old != null) old.close();
        mDevice = null;
        mPending = null;
        mQueue.clear();
        mSerial = null;
        mFirmware = null;
        mModel = null;
        mBattery = -1;
        mPnpValid = false;
        mPnpSource = mPnpVendor = mPnpProduct = mPnpVersion = -1;
        mConnectedAt = 0;
        mGeneration++;
        mConnection = next;
        publish();
    }

    private void checkAndConnect() {
        BluetoothManager manager = mContext.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (!PenMode.isEnabled()) {
            mNotifications.cancel(1004);
            if (mConnection != PenIdentity.Connection.DISABLED) clear(PenIdentity.Connection.DISABLED);
            return;
        }
        if (adapter == null || !adapter.isEnabled()) {
            mNotifications.cancel(1004);
            if (mConnection != PenIdentity.Connection.BLUETOOTH_OFF) clear(PenIdentity.Connection.BLUETOOTH_OFF);
            return;
        }
        String hint = mPrefs.getString(HINT, null);
        BluetoothDevice selected = null;
        boolean hintBonded = false;
        for (BluetoothDevice device : adapter.getBondedDevices()) {
            if (device.getAddress().equals(hint)) {
                selected = device;
                hintBonded = true;
                break;
            }
            String name = device.getName();
            if (name != null && name.equalsIgnoreCase("moto pen pro")) selected = device;
        }
        if (hint != null && !hintBonded) mPrefs.edit().remove(HINT).apply();
        if (selected == null) {
            if (mConnection != PenIdentity.Connection.DISCONNECTED) clear(PenIdentity.Connection.DISCONNECTED);
            return;
        }
        if (mGatt != null && selected.equals(mDevice)) return;
        clear(PenIdentity.Connection.CONNECTING);
        mDevice = selected;
        mGatt = selected.connectGatt(mContext, false, mCallback, BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK, mHandler);
        if (mGatt == null) {
            clear(PenIdentity.Connection.DISCONNECTED);
            mHandler.postDelayed(this::checkAndConnect, RETRY_MS);
        } else {
            armTimeout(mGatt, mGeneration);
        }
    }

    private void armTimeout(BluetoothGatt gatt, long generation) {
        if (mTimeout != null) mHandler.removeCallbacks(mTimeout);
        mTimeout = () -> {
            if (mGatt != gatt || mGeneration != generation) return;
            clear(PenIdentity.Connection.DISCONNECTED);
            mHandler.postDelayed(this::checkAndConnect, RETRY_MS);
        };
        mHandler.postDelayed(mTimeout, TIMEOUT_MS);
    }

    private void cancelTimeout() {
        if (mTimeout != null) mHandler.removeCallbacks(mTimeout);
        mTimeout = null;
    }

    private void enqueue(BluetoothGatt gatt, int serviceId, int characteristicId) {
        BluetoothGattService service = gatt.getService(uuid(serviceId));
        BluetoothGattCharacteristic characteristic = service == null ? null
                : service.getCharacteristic(uuid(characteristicId));
        if (characteristic != null) mQueue.add(characteristic);
    }

    private void next(BluetoothGatt gatt) {
        if (gatt != mGatt || mPending != null) return;
        while ((mPending = mQueue.poll()) != null) {
            if (gatt.readCharacteristic(mPending)) {
                armTimeout(gatt, mGeneration);
                return;
            }
            mPending = null;
        }
        if (mPnpValid && "PICASSO-CD1".equalsIgnoreCase(mModel)) {
            mConnection = PenIdentity.Connection.VERIFIED;
            mPrefs.edit().putString(HINT, mDevice.getAddress()).apply();
        } else if (mConnection != PenIdentity.Connection.VERIFIED) {
            mConnection = PenIdentity.Connection.UNSUPPORTED;
        }
        publish();
        if (mConnection == PenIdentity.Connection.VERIFIED) {
            long generation = mGeneration;
            mBatteryPoll = () -> {
                if (gatt != mGatt || generation != mGeneration) return;
                mBattery = -1;
                publish();
                enqueue(gatt, 0x180f, 0x2a19);
                next(gatt);
            };
            mHandler.postDelayed(mBatteryPoll, BATTERY_POLL_MS);
        }
    }

    private static String text(byte[] value) {
        if (value == null || value.length == 0 || value.length > 128) return null;
        String decoded = new String(value, StandardCharsets.UTF_8).trim();
        for (int i = 0; i < decoded.length(); i++) {
            if (Character.isISOControl(decoded.charAt(i)) || decoded.charAt(i) == '\ufffd') return null;
        }
        return decoded.isEmpty() ? null : decoded;
    }

    private void readResult(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic,
            byte[] value, int status) {
        if (gatt != mGatt || characteristic != mPending) return;
        cancelTimeout();
        mPending = null;
        if (status == BluetoothGatt.GATT_SUCCESS && value != null) {
            UUID id = characteristic.getUuid();
            if (id.equals(uuid(0x2a25))) mSerial = text(value);
            else if (id.equals(uuid(0x2a26))) mFirmware = text(value);
            else if (id.equals(uuid(0x2a27))) mModel = text(value);
            else if (id.equals(uuid(0x2a50)) && value.length == 7) {
                mPnpSource = value[0] & 255;
                mPnpVendor = (value[1] & 255) | ((value[2] & 255) << 8);
                mPnpProduct = (value[3] & 255) | ((value[4] & 255) << 8);
                mPnpVersion = (value[5] & 255) | ((value[6] & 255) << 8);
                // 2 identifies the USB-IF vendor namespace containing 0x17ef.
                mPnpValid = mPnpSource == 2 && mPnpVendor == 0x17ef && mPnpProduct == 0x617f;
            } else if (id.equals(uuid(0x2a19)) && value.length == 1 && (value[0] & 255) <= 100) {
                mBattery = value[0] & 255;
            }
        }
        next(gatt);
    }

    private final BluetoothGattCallback mCallback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt gatt, int status, int state) {
            if (gatt != mGatt) return;
            cancelTimeout();
            if (status == BluetoothGatt.GATT_SUCCESS && state == BluetoothProfile.STATE_CONNECTED) {
                mConnectedAt = SystemClock.uptimeMillis();
                mNotifications.cancel(1004);
                mConnection = PenIdentity.Connection.UNVERIFIED;
                publish();
                if (gatt.discoverServices()) armTimeout(gatt, mGeneration);
                else {
                    clear(PenIdentity.Connection.DISCONNECTED);
                    mHandler.postDelayed(PenGattService.this::checkAndConnect, RETRY_MS);
                }
            } else if (state == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                if (mConnection == PenIdentity.Connection.VERIFIED) {
                    mPrefs.edit().putLong("last_disconnected", System.currentTimeMillis()).apply();
                }
                BluetoothManager manager = mContext.getSystemService(BluetoothManager.class);
                BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
                if (status == 8 && PenPreferences.get(mContext, PenPreferences.LOST_ALERT, 1) != 0
                        && mConnectedAt > 0 && adapter != null && adapter.isEnabled()
                        && SystemClock.uptimeMillis() - mConnectedAt > 15000 && PenMode.isEnabled()) {
                    mNotifications.notify(1004, new Notification.Builder(mContext, "pen_alert_channel")
                            .setSmallIcon(R.drawable.ic_lenovo_parts)
                            .setContentTitle(mContext.getString(R.string.pen_out_of_range_title))
                            .setContentText(mContext.getString(R.string.pen_out_of_range_message))
                            .setAutoCancel(true).build());
                }
                clear(PenIdentity.Connection.DISCONNECTED);
                mHandler.postDelayed(PenGattService.this::checkAndConnect, RETRY_MS);
            }
        }

        @Override public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (gatt != mGatt) return;
            cancelTimeout();
            if (status != BluetoothGatt.GATT_SUCCESS) {
                clear(PenIdentity.Connection.DISCONNECTED);
                mHandler.postDelayed(PenGattService.this::checkAndConnect, RETRY_MS);
                return;
            }
            enqueue(gatt, 0x180a, 0x2a25);
            enqueue(gatt, 0x180a, 0x2a26);
            enqueue(gatt, 0x180a, 0x2a27);
            enqueue(gatt, 0x180a, 0x2a50);
            enqueue(gatt, 0x180f, 0x2a19);
            next(gatt);
        }

        @Override public void onCharacteristicRead(BluetoothGatt gatt,
                BluetoothGattCharacteristic characteristic, byte[] value, int status) {
            readResult(gatt, characteristic, value, status);
        }

        @Override public void onCharacteristicRead(BluetoothGatt gatt,
                BluetoothGattCharacteristic characteristic, int status) {
            readResult(gatt, characteristic, characteristic.getValue(), status);
        }
    };
}
