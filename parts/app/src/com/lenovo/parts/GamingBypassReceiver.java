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
