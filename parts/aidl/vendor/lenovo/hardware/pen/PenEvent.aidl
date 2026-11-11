/*
 * Copyright (C) 2026 hirero-exists <hirerokazuoa@gmail.com>
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 * Compatible with GNU General Public License, Version 2.0 (GPLv2) or later
 * pursuant to Section 3.3 of the Mozilla Public License, v. 2.0.
 */
package vendor.lenovo.hardware.pen;

@VintfStability
parcelable PenEvent {
    const int STATE_DISCONNECTED = 0;
    const int STATE_CONNECTED = 1;
    const int GESTURE = 2;
    const int RESET = 3;
    const int CORNER = 4;
    const int DIGITIZER_TAP = 5;
    const int PROXIMITY = 6;
    long generation;
    long sequence;
    long monotonicNanos;
    int kind;
    int usage;
    boolean pressed;
    boolean inRange;
}
