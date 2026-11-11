/*
 * Copyright (C) 2026 hirero-exists <hirerokazuoa@gmail.com>
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 * Compatible with GNU General Public License, Version 2.0 (GPLv2) or later
 * pursuant to Section 3.3 of the Mozilla Public License, v. 2.0.
 */
package com.lenovo.parts;

/** Immutable state for a single GATT connection generation. */
final class PenIdentity {
    enum Connection { DISABLED, BLUETOOTH_OFF, DISCONNECTED, CONNECTING, UNVERIFIED, UNSUPPORTED, VERIFIED }
    final long generation;
    final Connection connection;
    final String serial;
    final String firmware;
    final String model;
    final String deviceName;
    final int battery;
    final int pnpSource, pnpVendor, pnpProduct, pnpVersion;

    PenIdentity(long generation, Connection connection, String serial, String firmware,
            String model, int battery, int pnpSource, int pnpVendor, int pnpProduct,
            int pnpVersion, String deviceName) {
        this.generation = generation;
        this.connection = connection;
        this.serial = serial;
        this.firmware = firmware;
        this.model = model;
        this.deviceName = deviceName;
        this.battery = battery;
        this.pnpSource = pnpSource;
        this.pnpVendor = pnpVendor;
        this.pnpProduct = pnpProduct;
        this.pnpVersion = pnpVersion;
    }
}
