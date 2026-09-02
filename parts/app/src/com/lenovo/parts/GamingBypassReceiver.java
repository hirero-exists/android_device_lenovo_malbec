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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class GamingBypassReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (GamingBypassController.ACTION_TURN_OFF.equals(action)) {
            GamingBypassController.getInstance(context).setBypassEnabled(false);
        } else if (Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
            GamingBypassController.getInstance(context).setBypassEnabled(false);
        }
    }
}
