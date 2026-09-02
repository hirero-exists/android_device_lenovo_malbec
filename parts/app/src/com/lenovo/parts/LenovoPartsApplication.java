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

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.SystemProperties;
import android.util.Log;

public final class LenovoPartsApplication extends Application implements SensorEventListener {
    private static final String TAG = "LenovoParts";
    private static final int SENSOR_TYPE_HALL_EFFECT = 33171002;
    private static final String CLOSED_PROPERTY = "sys.malbec.folio.closed";

    private final GamingBypassReceiver mBypassReceiver = new GamingBypassReceiver();

    @Override
    public void onCreate() {
        super.onCreate();
        PenShortcuts.apply(this);
        new PenGattService(this).start();
        DolbyStatusNotification.init(this);
        GamingBypassController.getInstance(this);

        IntentFilter bypassFilter = new IntentFilter();
        bypassFilter.addAction(GamingBypassController.ACTION_TURN_OFF);
        bypassFilter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        registerReceiver(mBypassReceiver, bypassFilter, Context.RECEIVER_NOT_EXPORTED);

        if (PenMode.isEnabled() && PenMode.isToolbarEnabled()) {
            Intent toolbarIntent = new Intent(this, FloatingToolbarService.class);
            startForegroundService(toolbarIntent);
        }

        SensorManager sensorManager = getSystemService(SensorManager.class);
        if (sensorManager == null) {
            return;
        }
        Sensor hallSensor = null;
        for (Sensor sensor : sensorManager.getSensorList(Sensor.TYPE_ALL)) {
            if (sensor.getType() == SENSOR_TYPE_HALL_EFFECT
                    && (hallSensor == null || sensor.isWakeUpSensor())) {
                hallSensor = sensor;
            }
        }

        if (hallSensor == null) {
            Log.e(TAG, "Hall effect sensor is unavailable");
            return;
        }

        if (!sensorManager.registerListener(this, hallSensor, SensorManager.SENSOR_DELAY_NORMAL)) {
            Log.e(TAG, "Unable to register hall effect sensor listener");
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.values.length == 0) {
            return;
        }
        boolean closed = event.values[0] != 1.0f;
        SystemProperties.set(CLOSED_PROPERTY, closed ? "1" : "0");
        Log.i(TAG, "Folio sensor state " + (closed ? "closed" : "open"));
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
