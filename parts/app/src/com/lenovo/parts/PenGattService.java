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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.LinkedList;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

final class PenGattService {
    private static final String TAG = "LenovoPartsPenGatt";
    private static final String CHANNEL_ID = "pen_alert_channel";
    private static final int NOTIFICATION_ID = 1004;
    private static final int GATT_CONNECTION_TIMEOUT = 8;

    private static PenGattService sInstance;

    private static final UUID HID_SERVICE_UUID =
            UUID.fromString("00001812-0000-1000-8000-00805f9b34fb");
    private static final UUID REPORT_CHAR_UUID =
            UUID.fromString("00002a4d-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final NotificationManager mNotificationManager;
    private final Queue<BluetoothGattDescriptor> mDescriptorWriteQueue = new LinkedList<>();

    private BluetoothGatt mGatt;
    private BluetoothDevice mTargetDevice;
    private boolean mWritingDescriptor = false;
    private long mConnectedTimestamp = 0;

    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "GATT connected to pen, discovering services");
                mConnectedTimestamp = SystemClock.uptimeMillis();
                dismissOutOfRangeNotification();
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "GATT disconnected from pen status=" + status);
                boolean established = mConnectedTimestamp > 0
                        && SystemClock.uptimeMillis() - mConnectedTimestamp > 15000;
                boolean unexpected = status == GATT_CONNECTION_TIMEOUT;
                if (established && unexpected && PenMode.isEnabled() && isBluetoothEnabled()) {
                    showOutOfRangeNotification();
                }
                mConnectedTimestamp = 0;
                closeGatt();
                if (PenMode.isEnabled()) {
                    mHandler.postDelayed(PenGattService.this::checkAndConnect, 3000);
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            Log.i(TAG, "onServicesDiscovered status=" + status);
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: " + status);
                return;
            }
            BluetoothGattService hidService = gatt.getService(HID_SERVICE_UUID);
            if (hidService == null) {
                Log.w(TAG, "HID service not found on pen");
                return;
            }

            synchronized (PenGattService.this) {
                mDescriptorWriteQueue.clear();
                mWritingDescriptor = false;

                for (BluetoothGattCharacteristic characteristic : hidService.getCharacteristics()) {
                    if (REPORT_CHAR_UUID.equals(characteristic.getUuid())
                            || (characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                        gatt.setCharacteristicNotification(characteristic, true);
                        BluetoothGattDescriptor cccd = characteristic.getDescriptor(CCCD_UUID);
                        if (cccd != null) {
                            mDescriptorWriteQueue.add(cccd);
                        }
                    }
                }
                processNextDescriptorWrite(gatt);
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor,
                int status) {
            synchronized (PenGattService.this) {
                mWritingDescriptor = false;
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "Subscribed to pen notification: "
                            + (descriptor != null && descriptor.getCharacteristic() != null
                                    ? descriptor.getCharacteristic().getUuid() : "unknown"));
                } else {
                    Log.e(TAG, "Pen CCCD write failed: " + status);
                }
                processNextDescriptorWrite(gatt);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                BluetoothGattCharacteristic characteristic) {
            handleReportData(characteristic != null ? characteristic.getValue() : null);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                BluetoothGattCharacteristic characteristic, byte[] value) {
            handleReportData(value);
        }
    };

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_ON) {
                    checkAndConnect();
                } else if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF) {
                    dismissOutOfRangeNotification();
                    closeGatt();
                }
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)
                    || BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                checkAndConnect();
            }
        }
    };

    PenGattService(Context context) {
        sInstance = this;
        mContext = context.getApplicationContext();
        mNotificationManager = mContext.getSystemService(NotificationManager.class);

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                mContext.getString(R.string.pen_enabled_title),
                NotificationManager.IMPORTANCE_DEFAULT);
        mNotificationManager.createNotificationChannel(channel);
    }

    void start() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        mContext.registerReceiver(mReceiver, filter, Context.RECEIVER_EXPORTED);
        checkAndConnect();
    }

    static synchronized void onPenEnabledChanged() {
        if (sInstance == null) {
            return;
        }
        if (PenMode.isEnabled()) {
            sInstance.checkAndConnect();
        } else {
            sInstance.dismissOutOfRangeNotification();
            sInstance.closeGatt();
        }
    }

    private boolean isBluetoothEnabled() {
        BluetoothManager manager = mContext.getSystemService(BluetoothManager.class);
        if (manager == null) return false;
        BluetoothAdapter adapter = manager.getAdapter();
        return adapter != null && adapter.isEnabled();
    }

    synchronized void checkAndConnect() {
        if (!PenMode.isEnabled()) {
            dismissOutOfRangeNotification();
            closeGatt();
            return;
        }

        BluetoothManager manager = mContext.getSystemService(BluetoothManager.class);
        if (manager == null) {
            return;
        }
        BluetoothAdapter adapter = manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            return;
        }
        Set<BluetoothDevice> bondedDevices = adapter.getBondedDevices();
        if (bondedDevices == null) {
            return;
        }
        BluetoothDevice penDevice = null;
        for (BluetoothDevice device : bondedDevices) {
            String name = device.getName();
            if (name != null) {
                String lower = name.toLowerCase();
                if (lower.contains("pen") || lower.contains("stylus") || lower.contains("ap50")) {
                    penDevice = device;
                    break;
                }
            }
        }
        if (penDevice == null) {
            return;
        }
        if (mGatt != null && Objects.equals(mTargetDevice, penDevice)) {
            return;
        }
        closeGatt();
        mTargetDevice = penDevice;
        Log.i(TAG, "Connecting GATT to pen " + penDevice.getAddress());
        mGatt = penDevice.connectGatt(mContext, false, mGattCallback, BluetoothDevice.TRANSPORT_LE);
    }

    private synchronized void processNextDescriptorWrite(BluetoothGatt gatt) {
        if (mWritingDescriptor || mDescriptorWriteQueue.isEmpty() || gatt == null) {
            return;
        }
        BluetoothGattDescriptor descriptor = mDescriptorWriteQueue.poll();
        if (descriptor != null) {
            mWritingDescriptor = true;
            int result = gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            if (result != BluetoothStatusCodes.SUCCESS) {
                Log.e(TAG, "CCCD write initiation failed: " + result);
                mWritingDescriptor = false;
                processNextDescriptorWrite(gatt);
            }
        }
    }

    private void handleReportData(byte[] data) {
        if (data == null || data.length == 0 || !PenMode.isEnabled()) {
            return;
        }
        int mask = 0;
        if (data.length >= 2 && data[0] == 0x02) {
            mask = data[1] & 0xff;
        } else if (data.length == 1) {
            mask = data[0] & 0xff;
        } else {
            mask = (data[0] & 0xff) | ((data[1] & 0xff) << 8);
        }
        Log.i(TAG, "Received pen report raw data: " + bytesToHex(data) + " mask=" + mask);
        if (mask != 0) {
            int action = PenShortcuts.ACTION_NONE;
            if ((mask & 0x01) != 0) {
                action = PenShortcuts.getAction(mContext, PenShortcuts.SINGLE_SETTING,
                        PenShortcuts.ACTION_PLAY_PAUSE);
            } else if ((mask & 0x02) != 0 || (mask & 0x04) != 0) {
                action = PenShortcuts.getAction(mContext, PenShortcuts.DOUBLE_SETTING,
                        PenShortcuts.ACTION_SCREENSHOT);
            } else if ((mask & 0x08) != 0 || (mask & 0x10) != 0) {
                action = PenShortcuts.getAction(mContext, PenShortcuts.LONG_SETTING,
                        PenShortcuts.ACTION_NONE);
            }
            if (action != PenShortcuts.ACTION_NONE) {
                int targetAction = action;
                mHandler.post(() -> PenShortcuts.executeAction(mContext, targetAction));
            }
        }
    }

    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x ", b));
        }
        return sb.toString().trim();
    }

    private void showOutOfRangeNotification() {
        Notification notification = new Notification.Builder(mContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_lenovo_parts)
                .setContentTitle(mContext.getString(R.string.pen_out_of_range_title))
                .setContentText(mContext.getString(R.string.pen_out_of_range_message))
                .setAutoCancel(true)
                .build();
        mNotificationManager.notify(NOTIFICATION_ID, notification);
    }

    private void dismissOutOfRangeNotification() {
        mNotificationManager.cancel(NOTIFICATION_ID);
    }

    synchronized void closeGatt() {
        if (mGatt != null) {
            try {
                mGatt.close();
            } catch (Exception ignored) {
            }
            mGatt = null;
        }
        mTargetDevice = null;
        mDescriptorWriteQueue.clear();
        mWritingDescriptor = false;
    }
}
