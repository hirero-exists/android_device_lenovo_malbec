package com.lenovo.parts;

import android.app.Application;
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

    @Override
    public void onCreate() {
        super.onCreate();
        PenShortcuts.apply(this);

        SensorManager sensorManager = getSystemService(SensorManager.class);
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
