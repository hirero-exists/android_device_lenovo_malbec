package com.lenovo.parts;

import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.provider.Settings;
import android.view.Display;
import android.view.InputDevice;

import com.android.internal.policy.GestureNavigationSettingsObserver;

final class PenGestureGuard implements DisplayManager.DisplayListener,
        InputManager.InputDeviceListener, ComponentCallbacks {
    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final DisplayManager mDisplays;
    private final InputManager mInputs;
    private final GestureNavigationSettingsObserver mNavigation;

    PenGestureGuard(Context context) {
        mContext = context;
        mDisplays = context.getSystemService(DisplayManager.class);
        mInputs = context.getSystemService(InputManager.class);
        mNavigation = new GestureNavigationSettingsObserver(mHandler, mHandler, context,
                this::publishConfig);
    }

    void start() {
        mNavigation.register();
        mContext.registerComponentCallbacks(this);
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor("navigation_mode"), false,
                new ContentObserver(mHandler) {
                    @Override public void onChange(boolean selfChange) { publishConfig(); }
                });
        if (mDisplays != null) mDisplays.registerDisplayListener(this, mHandler);
        if (mInputs != null) mInputs.registerInputDeviceListener(this, mHandler);
        publishConfig();
        publishReady();
    }

    private void publishConfig() {
        Display display = mDisplays == null ? null : mDisplays.getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null) {
            SystemProperties.set("sys.malbec.pen.guard_config", "");
            return;
        }
        Resources resources = mContext.createDisplayContext(display).getResources();
        Point size = new Point();
        display.getRealSize(size);
        boolean gestural = resources.getInteger(
                com.android.internal.R.integer.config_navBarInteractionMode) == 2;
        int left = gestural ? mNavigation.getLeftSensitivity(resources) : 0;
        int right = gestural ? mNavigation.getRightSensitivity(resources) : 0;
        int bottom = gestural ? resources.getDimensionPixelSize(
                com.android.internal.R.dimen.navigation_bar_gesture_height) : 0;
        SystemProperties.set("sys.malbec.pen.guard_config", display.getRotation() + ","
                + size.x + "," + size.y + "," + left + "," + right + "," + bottom);
    }

    private void publishReady() {
        String name = "";
        if (mInputs != null) {
            for (int id : mInputs.getInputDeviceIds()) {
                InputDevice device = mInputs.getInputDevice(id);
                if (device != null && device.getName().startsWith("Lenovo Pen Guard ")
                        && device.getVendorId() == 0x17ef && device.getProductId() == 0x36
                        && device.supportsSource(InputDevice.SOURCE_STYLUS)) {
                    name = device.getName();
                }
            }
        }
        SystemProperties.set("sys.malbec.pen.guard_ready", name);
    }

    @Override public void onDisplayAdded(int displayId) { publishConfig(); }
    @Override public void onDisplayRemoved(int displayId) { publishConfig(); }
    @Override public void onDisplayChanged(int displayId) {
        if (displayId == Display.DEFAULT_DISPLAY) publishConfig();
    }
    @Override public void onInputDeviceAdded(int deviceId) { publishReady(); }
    @Override public void onInputDeviceRemoved(int deviceId) { publishReady(); }
    @Override public void onInputDeviceChanged(int deviceId) { publishReady(); }
    @Override public void onConfigurationChanged(Configuration configuration) { publishConfig(); }
    @Override public void onLowMemory() { }
}
