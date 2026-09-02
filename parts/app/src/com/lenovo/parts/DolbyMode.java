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

import android.content.ContentResolver;
import android.media.audiofx.AudioEffect;
import android.provider.Settings;
import android.util.Log;

import java.util.UUID;

final class DolbyMode {
    private static final String TAG = "DolbyMode";
    private static final String ENABLED_SETTING = "dlb_dap_state";
    private static final String PROFILE_SETTING = "dolby_dap_profile";
    private static final String SHOW_ICON_SETTING = "persist.sys.dolby.show_icon";

    private static final UUID DAP_TYPE_UUID =
            UUID.fromString("fa81dbde-588b-11ed-9b6a-0242ac120002");
    private static final UUID DAP_EFFECT_UUID =
            UUID.fromString("9d4921da-8225-4f29-aefa-39537a04bcaa");

    private DolbyMode() {
    }

    static boolean isEnabled(ContentResolver resolver) {
        return Settings.Global.getInt(resolver, ENABLED_SETTING, 1) != 0;
    }

    static boolean setEnabled(ContentResolver resolver, boolean enabled) {
        return Settings.Global.putInt(resolver, ENABLED_SETTING, enabled ? 1 : 0);
    }

    static int getProfile(ContentResolver resolver) {
        return Settings.Global.getInt(resolver, PROFILE_SETTING, 0);
    }

    static boolean setProfile(ContentResolver resolver, int profile) {
        return Settings.Global.putInt(resolver, PROFILE_SETTING, profile);
    }

    static boolean isIconEnabled(ContentResolver resolver) {
        return Settings.Global.getInt(resolver, SHOW_ICON_SETTING, 1) != 0;
    }

    static boolean setIconEnabled(ContentResolver resolver, boolean enabled) {
        return Settings.Global.putInt(resolver, SHOW_ICON_SETTING, enabled ? 1 : 0);
    }

    static AudioEffect createDapEffect() {
        try {
            return new AudioEffect(DAP_TYPE_UUID, DAP_EFFECT_UUID, 0, 0);
        } catch (Exception e) {
            Log.w(TAG, "Unable to create DAP AudioEffect instance: " + e.getMessage());
            return null;
        }
    }
}
