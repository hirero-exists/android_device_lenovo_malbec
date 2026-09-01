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

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class PenGattService {
    private static final String TAG = "LenovoPartsPenGatt";
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
    private BluetoothGatt mGatt;
    private BluetoothDevice mTargetDevice;

    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "GATT connected to pen, discovering services");
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "GATT disconnected from pen");
                closeGatt();
                mHandler.postDelayed(PenGattService.this::checkAndConnect, 3000);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed with status " + status);
                return;
            }
            BluetoothGattService hidService = gatt.getService(HID_SERVICE_UUID);
            if (hidService == null) {
                Log.w(TAG, "HID service not found on pen");
                return;
            }
            for (BluetoothGattCharacteristic characteristic : hidService.getCharacteristics()) {
                if (REPORT_CHAR_UUID.equals(characteristic.getUuid())) {
                    boolean isInputReport = false;
                    BluetoothGattDescriptor reportRef = characteristic.getDescriptor(REPORT_REF_UUID);
                    if (reportRef != null && reportRef.getValue() != null && reportRef.getValue().length >= 2) {
                        if (reportRef.getValue()[0] == 0x02 && reportRef.getValue()[1] == 0x01) {
                            isInputReport = true;
                        }
                    } else {
                        isInputReport = (characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
                    }
                    if (isInputReport) {
                        subscribeToReport(gatt, characteristic);
                    }
                }
            }
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
                    closeGatt();
                }
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)
                    || BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                checkAndConnect();
            } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null && Objects.equals(device, mTargetDevice)) {
                    closeGatt();
                }
            }
        }
    };

    PenGattService(Context context) {
        mContext = context;
    }

    void start() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        mContext.registerReceiver(mReceiver, filter, Context.RECEIVER_EXPORTED);
        checkAndConnect();
    }

    private synchronized void checkAndConnect() {
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

    private void subscribeToReport(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        gatt.setCharacteristicNotification(characteristic, true);
        BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD_UUID);
        if (descriptor != null) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            Log.i(TAG, "Subscribed to pen report characteristic notifications");
        }
    }

    private void handleReportData(byte[] data) {
        if (data == null || data.length == 0) {
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
            Log.i(TAG, "Received pen gesture mask: 0x" + Integer.toHexString(gestureMask));
            mHandler.post(() -> PenShortcuts.executeGesture(mContext, gestureMask));
        }
    }

    private synchronized void closeGatt() {
        if (mGatt != null) {
            try {
                mGatt.close();
            } catch (Exception ignored) {
            }
            mGatt = null;
        }
        mTargetDevice = null;
    }
}
