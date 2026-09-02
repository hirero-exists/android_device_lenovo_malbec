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
    private static final int OPERATION_TIMEOUT_MS = 5000;

    private static PenGattService sInstance;

    private static final UUID HID_SERVICE_UUID =
            UUID.fromString("00001812-0000-1000-8000-00805f9b34fb");
    private static final UUID REPORT_CHAR_UUID =
            UUID.fromString("00002a4d-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final UUID REPORT_REF_UUID =
            UUID.fromString("00002908-0000-1000-8000-00805f9b34fb");

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final NotificationManager mNotificationManager;
    private final Queue<BluetoothGattDescriptor> mDescriptorReadQueue = new LinkedList<>();

    private BluetoothGatt mGatt;
    private BluetoothDevice mTargetDevice;
    private boolean mReadingDescriptor = false;
    private long mConnectedTimestamp = 0;

    private final Runnable mOperationTimeout = () -> {
        synchronized (PenGattService.this) {
            if (!mReadingDescriptor || mGatt == null) {
                return;
            }
            Log.e(TAG, "GATT descriptor operation timed out");
            BluetoothGatt gatt = mGatt;
            mReadingDescriptor = false;
            mDescriptorReadQueue.clear();
            gatt.disconnect();
        }
    };

    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "GATT connected to pen, discovering services");
                mConnectedTimestamp = SystemClock.uptimeMillis();
                dismissOutOfRangeNotification();
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "GATT disconnected from pen");
                boolean established = mConnectedTimestamp > 0
                        && SystemClock.uptimeMillis() - mConnectedTimestamp > 15000;
                boolean unexpected = status == GATT_CONNECTION_TIMEOUT;
                if (established && unexpected && PenMode.isEnabled()
                        && isBluetoothEnabled()) {
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
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: " + status);
                return;
            }
            BluetoothGattService hidService = gatt.getService(HID_SERVICE_UUID);
            if (hidService == null) {
                Log.w(TAG, "HID service not found on pen");
                return;
            }

            mDescriptorReadQueue.clear();
            mReadingDescriptor = false;

            for (BluetoothGattCharacteristic characteristic : hidService.getCharacteristics()) {
                if (REPORT_CHAR_UUID.equals(characteristic.getUuid())) {
                    BluetoothGattDescriptor reportRef = characteristic.getDescriptor(REPORT_REF_UUID);
                    if (reportRef != null) {
                        mDescriptorReadQueue.add(reportRef);
                    } else if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                        subscribeToCharacteristic(gatt, characteristic);
                    }
                }
            }
            processNextDescriptorRead(gatt);
        }

        @Override
        public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor,
                int status, byte[] value) {
            if (gatt != mGatt) {
                return;
            }
            mHandler.removeCallbacks(mOperationTimeout);
            mReadingDescriptor = false;
            if (status == BluetoothGatt.GATT_SUCCESS && value != null && value.length >= 2) {
                if (value[0] == 0x02 && value[1] == 0x01) {
                    if (subscribeToCharacteristic(gatt, descriptor.getCharacteristic())) {
                        return;
                    }
                }
            }
            processNextDescriptorRead(gatt);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor,
                int status) {
            if (gatt != mGatt) {
                return;
            }
            mHandler.removeCallbacks(mOperationTimeout);
            mReadingDescriptor = false;
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Subscribed to Report ID 2 notifications");
            } else {
                Log.e(TAG, "Report ID 2 CCCD write failed: " + status);
            }
            processNextDescriptorRead(gatt);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                BluetoothGattCharacteristic characteristic, byte[] value) {
            handleReportData(value);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                BluetoothGattCharacteristic characteristic) {
            handleReportData(characteristic.getValue());
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
            if (name != null && (name.toLowerCase().contains("pen") || name.toLowerCase().contains("stylus"))) {
                penDevice = device;
                break;
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
        mGatt = penDevice.connectGatt(mContext, true, mGattCallback, BluetoothDevice.TRANSPORT_LE);
    }

    private synchronized void processNextDescriptorRead(BluetoothGatt gatt) {
        if (mReadingDescriptor || mDescriptorReadQueue.isEmpty() || gatt == null) {
            return;
        }
        BluetoothGattDescriptor descriptor = mDescriptorReadQueue.poll();
        if (descriptor != null) {
            mReadingDescriptor = true;
            if (gatt.readDescriptor(descriptor)) {
                mHandler.postDelayed(mOperationTimeout, OPERATION_TIMEOUT_MS);
            } else {
                mReadingDescriptor = false;
                processNextDescriptorRead(gatt);
            }
        }
    }

    private synchronized boolean subscribeToCharacteristic(BluetoothGatt gatt,
            BluetoothGattCharacteristic characteristic) {
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            Log.e(TAG, "Unable to enable local Report ID 2 notification routing");
            return false;
        }
        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD_UUID);
        if (descriptor == null) {
            Log.e(TAG, "Report ID 2 CCCD is missing");
            return false;
        }
        int result = gatt.writeDescriptor(
                descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        if (result != BluetoothStatusCodes.SUCCESS) {
            Log.e(TAG, "Unable to start Report ID 2 CCCD write: " + result);
            return false;
        }
        mReadingDescriptor = true;
        mHandler.postDelayed(mOperationTimeout, OPERATION_TIMEOUT_MS);
        return true;
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
        }
        final int gestureMask = mask;
        if (gestureMask != 0) {
            Log.i(TAG, "Pen gesture mask: 0x" + Integer.toHexString(gestureMask));
            mHandler.post(() -> PenShortcuts.executeGesture(mContext, gestureMask));
        }
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
        mHandler.removeCallbacks(mOperationTimeout);
        if (mGatt != null) {
            try {
                mGatt.close();
            } catch (Exception ignored) {
            }
            mGatt = null;
        }
        mTargetDevice = null;
        mDescriptorReadQueue.clear();
        mReadingDescriptor = false;
    }
}
