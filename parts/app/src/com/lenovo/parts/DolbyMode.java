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
import android.provider.Settings;

final class DolbyMode {
    private static final String ENABLED_SETTING = "dlb_dap_state";
    private static final String PROFILE_SETTING = "dolby_dap_profile";

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
}
