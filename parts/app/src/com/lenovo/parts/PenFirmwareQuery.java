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

import android.net.Uri;
import android.os.Build;
import android.os.SystemProperties;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;

final class PenFirmwareQuery {
    enum Status { LATEST, AVAILABLE, ERROR }
    static final class Result {
        final Status status;
        final String version;
        Result(Status status, String version) {
            this.status = status;
            this.version = version;
        }
    }
    private static final int MAX_RESPONSE = 256 * 1024;
    private static final Pattern VERSION = Pattern.compile("(?:.*\\s)?V(\\d{1,4})\\.(\\d{2})");
    private HttpsURLConnection mConnection;
    private boolean mCanceled;

    static boolean supported(PenIdentity id) {
        return id != null && id.connection == PenIdentity.Connection.VERIFIED
                && id.pnpSource == 2 && id.pnpVendor == 0x17ef && id.pnpProduct == 0x617f
                && "PICASSO-CD1".equalsIgnoreCase(id.model)
                && valid(id.deviceName) && valid(id.serial) && valid(id.firmware)
                && valid(id.model);
    }

    private static boolean valid(String value) {
        if (value == null || value.trim().isEmpty() || value.length() > 128) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c > 0x7e) return false;
        }
        return true;
    }

    private static String region() {
        for (String key : new String[] { "ro.config.lgsi.region", "ro.lenovo.region",
                "ro.odm.lenovo.region" }) {
            String value = SystemProperties.get(key, "").trim();
            if (valid(value) && !value.equalsIgnoreCase("unknown")) return value;
        }
        return "unknown";
    }

    synchronized void cancel() {
        mCanceled = true;
        if (mConnection != null) mConnection.disconnect();
    }

    Result execute(PenIdentity id) {
        if (!supported(id)) return error();
        String region = region();
        if (!valid(Build.MODEL)) return error();
        String name = id.deviceName.replace(" ", "");
        String model = name + "_" + id.model + "_"
                + String.format(Locale.ROOT, "0x%04X_0x%04X", id.pnpProduct, id.pnpVendor)
                + "_" + Build.MODEL.replace(" ", "") + "_" + region;
        if (!valid(name) || !valid(model) || currentVersion(id.firmware) < 0) return error();
        Uri uri = Uri.parse("https://ota.lenovo.com/engine/config").buildUpon()
                .appendQueryParameter("ChecksumType", "sha256")
                .appendQueryParameter("locale", "en")
                .appendQueryParameter("curfirmwarever", "TB-styluspen_USR_S000001_20211018_V1")
                .appendQueryParameter("deviceid", id.serial)
                .appendQueryParameter("devicemodel", model)
                .appendQueryParameter("action", "querynewfirmwar").build();
        HttpsURLConnection connection = null;
        try {
            connection = (HttpsURLConnection) new URL(uri.toString()).openConnection();
            synchronized (this) {
                if (mCanceled) return error();
                mConnection = connection;
            }
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(10000);
            connection.setRequestMethod("GET");
            if (connection.getResponseCode() != 200) return error();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (out.size() + count > MAX_RESPONSE) return error();
                    out.write(buffer, 0, count);
                }
            }
            String json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(out.toByteArray())).toString();
            return parse(json, name, id.firmware);
        } catch (Exception e) {
            return error();
        } finally {
            synchronized (this) {
                mConnection = null;
            }
            if (connection != null) connection.disconnect();
        }
    }

    static Result parse(String json, String name, String current) {
        try {
            if (!valid(name) || json == null
                    || json.getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE) return error();
            long installed = currentVersion(current);
            if (installed < 0) return error();
            JSONObject object = new JSONObject(json);
            Object raw = object.opt("update_packages_data");
            if (!(raw instanceof JSONArray)) return error();
            JSONArray entries = (JSONArray) raw;
            if (entries.length() > 256) return error();
            if (entries.length() == 0) return new Result(Status.LATEST, null);
            Object entry = entries.get(entries.length() - 1);
            if (!(entry instanceof JSONObject)) return error();
            JSONObject last = (JSONObject) entry;
            Object packageName = last.opt("data_package_name");
            Object code = last.opt("version_code");
            if (!(packageName instanceof String) || !valid((String) packageName)
                    || !((String) packageName).contains(name) || !(code instanceof Number)) {
                return error();
            }
            String codeText = code.toString();
            if (!codeText.matches("[1-9][0-9]{2,5}")) return error();
            long candidate = Long.parseLong(codeText);
            String version = String.format(Locale.ROOT, "V%d.%02d",
                    candidate / 100, candidate % 100);
            return candidate > installed ? new Result(Status.AVAILABLE, version)
                    : new Result(Status.LATEST, null);
        } catch (Exception e) {
            return error();
        }
    }

    private static long currentVersion(String firmware) {
        if (!valid(firmware)) return -1;
        Matcher match = VERSION.matcher(firmware);
        if (!match.matches()) return -1;
        long major = Long.parseLong(match.group(1));
        return major * 100 + Long.parseLong(match.group(2));
    }

    private static Result error() {
        return new Result(Status.ERROR, null);
    }
}
