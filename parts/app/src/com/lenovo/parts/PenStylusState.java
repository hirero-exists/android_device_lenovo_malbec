/*
 * Copyright (C) 2026 hirero-exists <hirerokazuoa@gmail.com>
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 * Compatible with GNU General Public License, Version 2.0 (GPLv2) or later
 * pursuant to Section 3.3 of the Mozilla Public License, v. 2.0.
 */
package com.lenovo.parts;

import android.view.MotionEvent;

final class PenStylusState {
    final int displayId, deviceId, action, tool, buttons;
    final long eventTime;
    final float x, y, pressure, tilt, orientation;

    PenStylusState(MotionEvent event, int pointer) {
        displayId = event.getDisplayId();
        deviceId = event.getDeviceId();
        action = event.getActionMasked();
        tool = event.getToolType(pointer);
        buttons = action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_HOVER_EXIT
                ? 0 : event.getButtonState();
        eventTime = event.getEventTime();
        x = event.getRawX(pointer);
        y = event.getRawY(pointer);
        pressure = event.getPressure(pointer);
        tilt = event.getAxisValue(MotionEvent.AXIS_TILT, pointer);
        orientation = event.getAxisValue(MotionEvent.AXIS_ORIENTATION, pointer);
    }
}
