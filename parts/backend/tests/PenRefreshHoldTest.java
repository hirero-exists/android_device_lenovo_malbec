/*
 * Copyright (C) 2026 hirero-exists <hirerokazuoa@gmail.com>
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 * Compatible with GNU General Public License, Version 2.0 (GPLv2) or later
 * pursuant to Section 3.3 of the Mozilla Public License, v. 2.0.
 */
package com.lenovo.parts;

public final class PenRefreshHoldTest {
    private static void check(boolean result) {
        if (!result) throw new AssertionError();
    }
    public static void main(String[] args) {
        PenRefreshHold hold = new PenRefreshHold();
        check(!hold.active(1));
        hold.proximity(true, 100);
        check(hold.active(100) && hold.active(100000));
        hold.proximity(false, 100100);
        check(hold.releaseDelay(100200) == 1900 && hold.active(102099));
        hold.proximity(false, 101000);
        check(!hold.active(102100));
        hold.proximity(true, 102200);
        hold.proximity(false, 102300);
        hold.proximity(true, 102400);
        check(hold.releaseDelay(102400) == 0 && hold.active(999999));
        hold.clear();
        check(!hold.active(999999));
        hold.proximity(false, 1000000);
        check(!hold.active(1000000));
        hold.proximity(true, 1000100);
        check(hold.active(1000100));
    }
}
