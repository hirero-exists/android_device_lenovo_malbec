/*
 * Copyright (C) 2026 hirero-exists <hirerokazuoa@gmail.com>
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 * Compatible with GNU General Public License, Version 2.0 (GPLv2) or later
 * pursuant to Section 3.3 of the Mozilla Public License, v. 2.0.
 */
package com.lenovo.parts;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.graphics.Point;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputMonitor;
import android.view.MotionEvent;
import java.util.HashSet;

final class PenInputMonitor implements DisplayManager.DisplayListener {
    private final DisplayManager mDisplays;
    private final InputManager mInputs;
    private final PenActionRouter mRouter;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final SparseArray<DisplayMonitor> mMonitors = new SparseArray<>();
    private boolean mEnabled;

    PenInputMonitor(Context context, PenActionRouter router) {
        mDisplays = context.getSystemService(DisplayManager.class);
        mInputs = context.getSystemService(InputManager.class);
        mRouter = router;
    }

    void setEnabled(boolean enabled) {
        if (mEnabled == enabled) return;
        mEnabled = enabled;
        if (mDisplays == null || mInputs == null) return;
        if (enabled) {
            mDisplays.registerDisplayListener(this, mHandler);
            for (Display display : mDisplays.getDisplays()) open(display.getDisplayId());
        } else {
            mDisplays.unregisterDisplayListener(this);
            while (mMonitors.size() > 0) close(mMonitors.keyAt(0));
        }
    }

    private void open(int displayId) {
        if (!mEnabled || mMonitors.get(displayId) != null) return;
        InputMonitor monitor = null;
        try {
            monitor = mInputs.monitorGestureInput("Lenovo Parts pen", displayId);
            mMonitors.put(displayId, new DisplayMonitor(monitor, displayId));
        } catch (RuntimeException e) {
            if (monitor != null) monitor.dispose();
            Log.w("PartsPenMonitor", "Unable to observe display " + displayId, e);
        }
    }

    private void close(int displayId) {
        DisplayMonitor monitor = mMonitors.get(displayId);
        if (monitor == null) return;
        mMonitors.remove(displayId);
        monitor.receiver.dispose();
        monitor.monitor.dispose();
        mRouter.onDisplayRemoved(displayId);
    }

    @Override public void onDisplayAdded(int displayId) { open(displayId); }
    @Override public void onDisplayRemoved(int displayId) { close(displayId); }
    @Override public void onDisplayChanged(int displayId) {
        DisplayMonitor existing = mMonitors.get(displayId);
        Display display = mDisplays == null ? null : mDisplays.getDisplay(displayId);
        if (existing != null && display != null && existing.sameGeometry(display)) return;
        close(displayId);
        open(displayId);
    }

    private final class DisplayMonitor {
        final InputMonitor monitor;
        final InputEventReceiver receiver;
        final HashSet<Integer> fingerDevices = new HashSet<>();
        final int rotation, width, height;

        DisplayMonitor(InputMonitor inputMonitor, int displayId) {
            monitor = inputMonitor;
            Display display = mDisplays.getDisplay(displayId);
            Point size = new Point();
            if (display != null) display.getRealSize(size);
            rotation = display == null ? -1 : display.getRotation();
            width = size.x;
            height = size.y;
            receiver = new InputEventReceiver(monitor.getInputChannel(), Looper.getMainLooper()) {
                @Override public void onInputEvent(InputEvent event) {
                    try {
                        if (!(event instanceof MotionEvent)) return;
                        MotionEvent motion = (MotionEvent) event;
                        for (int i = 0; i < motion.getPointerCount(); i++) {
                            if (motion.getToolType(i) == MotionEvent.TOOL_TYPE_FINGER) {
                                int action = motion.getActionMasked();
                                if (action == MotionEvent.ACTION_DOWN) fingerDevices.add(motion.getDeviceId());
                                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                                    fingerDevices.remove(motion.getDeviceId());
                                }
                            }
                        }
                        InputDevice device = motion.getDevice();
                        if (device == null) return;
                        String name = device.getName();
                        boolean physical = "NVTCapacitivePen".equals(name)
                                && device.getVendorId() == 0 && device.getProductId() == 0
                                && device.supportsSource(InputDevice.SOURCE_STYLUS);
                        boolean guarded = name.startsWith("Lenovo Pen Guard ")
                                && device.getVendorId() == 0x17ef && device.getProductId() == 0x36;
                        if (!physical && !guarded) return;
                        for (int i = 0; i < motion.getPointerCount(); i++) {
                            int tool = motion.getToolType(i);
                            if (tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER) {
                                if (!fingerDevices.isEmpty()) return;
                                boolean claim = mRouter.onStylus(new PenStylusState(motion, i));
                                if (claim && motion.getActionMasked() == MotionEvent.ACTION_DOWN) {
                                    monitor.pilferPointers();
                                }
                                break;
                            }
                        }
                    } finally {
                        finishInputEvent(event, false);
                    }
                }
            };
        }
        boolean sameGeometry(Display display) {
            Point size = new Point();
            display.getRealSize(size);
            return rotation == display.getRotation() && width == size.x && height == size.y;
        }
    }
}
