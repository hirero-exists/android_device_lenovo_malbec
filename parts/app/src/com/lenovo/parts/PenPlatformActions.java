/*
 * Copyright (C) 2026 hirero-exists <hirerokazuoa@gmail.com>
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 * Compatible with GNU General Public License, Version 2.0 (GPLv2) or later
 * pursuant to Section 3.3 of the Mozilla Public License, v. 2.0.
 */
package com.lenovo.parts;

import android.app.contextualsearch.ContextualSearchManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

final class PenPlatformActions {
    static void circleSearch(Context context) {
        try {
            ContextualSearchManager manager = context.getSystemService(ContextualSearchManager.class);
            if (manager != null && manager.isContextualSearchAvailable()) {
                manager.startContextualSearch(ContextualSearchManager.ENTRYPOINT_SYSTEM_ACTION);
                return;
            }
        } catch (RuntimeException ignored) { }
        Toast.makeText(context, R.string.pen_provider_unavailable, Toast.LENGTH_LONG).show();
    }

    static boolean handwritingSupported(Context context) {
        InputMethodManager manager = context.getSystemService(InputMethodManager.class);
        InputMethodInfo current = manager == null ? null : manager.getCurrentInputMethodInfo();
        return current != null && current.supportsStylusHandwriting();
    }

    static void handwritingSettings(Context context) {
        InputMethodInfo current = null;
        try {
            InputMethodManager manager = context.getSystemService(InputMethodManager.class);
            current = manager == null ? null : manager.getCurrentInputMethodInfo();
        } catch (RuntimeException | LinkageError ignored) { }
        if (current != null) {
            try {
                if (launchSettings(context, current.createStylusHandwritingSettingsActivityIntent())) {
                    return;
                }
            } catch (RuntimeException | LinkageError ignored) { }
            try {
                String activity = current.getSettingsActivity();
                if (activity != null && launchSettings(context, new Intent().setComponent(
                        new ComponentName(current.getPackageName(), activity)))) {
                    return;
                }
            } catch (RuntimeException | LinkageError ignored) { }
        }
        if (!launchSettings(context, new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))) {
            Toast.makeText(context, R.string.pen_provider_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    private static boolean launchSettings(Context context, Intent intent) {
        try {
            if (intent == null || intent.resolveActivity(context.getPackageManager()) == null) {
                return false;
            }
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }
}
