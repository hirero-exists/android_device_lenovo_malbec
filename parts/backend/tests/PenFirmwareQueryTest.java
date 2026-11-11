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

public final class PenFirmwareQueryTest {
    private static void check(String json, String current, PenFirmwareQuery.Status expected,
            String version) {
        PenFirmwareQuery.Result result = PenFirmwareQuery.parse(json, "motopenpro", current);
        if (result.status != expected || !java.util.Objects.equals(result.version, version)) {
            throw new AssertionError("unexpected result: " + result.status + " " + result.version);
        }
    }

    private static String entry(String name, String code) {
        return "{\"update_packages_data\":[{\"data_package_name\":\"" + name
                + "\",\"version_code\":" + code + "}]}";
    }

    public static void main(String[] args) {
        String current = "PICASSO-CD1 V3.06";
        check("{\"update_packages_data\":[]}", current, PenFirmwareQuery.Status.LATEST, null);
        check(entry("motopenpro_package", "306"), current, PenFirmwareQuery.Status.LATEST, null);
        check(entry("motopenpro_package", "307"), current,
                PenFirmwareQuery.Status.AVAILABLE, "V3.07");
        check(entry("motopenpro_package", "1000"), "PICASSO-CD1 V9.99",
                PenFirmwareQuery.Status.AVAILABLE, "V10.00");
        check("{}", current, PenFirmwareQuery.Status.ERROR, null);
        check(entry("other_package", "307"), current, PenFirmwareQuery.Status.ERROR, null);
        check(entry("motopenpro_package", "\"307\""), current,
                PenFirmwareQuery.Status.ERROR, null);
        check(entry("motopenpro_package", "307"), "unknown", PenFirmwareQuery.Status.ERROR, null);
        check("{\"update_packages_data\":[]" + " ".repeat(256 * 1024) + "}",
                current, PenFirmwareQuery.Status.ERROR, null);
        check("{\"update_packages_data\":[" + "{},".repeat(256) + "{}]}",
                current, PenFirmwareQuery.Status.ERROR, null);
        check("{", current, PenFirmwareQuery.Status.ERROR, null);
    }
}
