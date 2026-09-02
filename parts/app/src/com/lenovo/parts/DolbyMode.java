/*
 * Copyright (C) 2026 hirero-exists <hirerokazuoa@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
