/*
 * Copyright (C) 2026 hirero-exists <hirerokazuoa@gmail.com>
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 * Compatible with GNU General Public License, Version 2.0 (GPLv2) or later
 * pursuant to Section 3.3 of the Mozilla Public License, v. 2.0.
 */
package com.lenovo.parts;

public final class PenEventOrderTest {
    public static void main(String[] args) {
        PenEventOrder order = new PenEventOrder();
        assert order.accept(1, 0, 100, 1, 0);
        assert order.accept(1, 1, 101, 2, 0x0c0600);
        assert !order.accept(1, 1, 102, 2, 0x0c0600);
        assert !order.accept(1, 0, 103, 1, 0);
        assert !order.accept(1, 2, 99, 2, 0x0c0601);
        assert !order.accept(1, 2, 103, 2, 0x0c0606);
        assert !order.accept(1, 2, 103, 1, 0x0c0600);
        assert !order.accept(1, 2, 103, 4, 0);
        assert !order.accept(-1, 2, 103, 1, 0);
        assert !order.accept(2, -1, 103, 1, 0);
        assert order.accept(2, 0, 104, 3, 0);
        assert !order.accept(1, 9, 105, 2, 0x0c0601);
        assert order.accept(2, 1, 105, 2, 0x0c0601);
        order.reset();
        assert order.accept(1, 0, 1, 0, 0);
        assert order.accept(1, 1, 2, 5, 1);
        assert order.accept(1, 2, 3, 4, 2);
        assert !order.accept(1, 3, 4, 5, 3);
        assert !order.accept(1, 3, 4, 6, 1);
        assert order.accept(1, 3, 4, 6, 0);
        assert !order.accept(1, 4, 5, 7, 0);
    }
}
