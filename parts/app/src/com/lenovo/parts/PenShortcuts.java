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

import android.content.Context;
import android.hardware.input.InputGestureData;
import android.hardware.input.InputManager;
import android.hardware.input.KeyGestureEvent;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

final class PenShortcuts {
    static final String SINGLE_SETTING = "malbec_pen_single_action";
    static final String DOUBLE_SETTING = "malbec_pen_double_action";
    static final String LONG_SETTING = "malbec_pen_long_action";

    static final int ACTION_NONE = 0;
    static final int ACTION_TOOLBAR = 1;
    static final int ACTION_SCREENSHOT = 2;
    static final int ACTION_RECENTS = 3;
    static final int ACTION_HOME = 4;
    static final int ACTION_PLAY_PAUSE = 5;

    private static final String TAG = "PenShortcuts";

    private PenShortcuts() {
    }

    static int getAction(Context context, String setting, int defaultAction) {
        return Settings.Secure.getInt(context.getContentResolver(), setting, defaultAction);
    }

    static boolean setAction(Context context, String setting, int action) {
        boolean success = Settings.Secure.putInt(context.getContentResolver(), setting, action);
        if (success) {
            apply(context);
        }
        return success;
    }

    static void apply(Context context) {
        InputManager inputManager = context.getSystemService(InputManager.class);
        if (inputManager == null) {
            return;
        }

        applyKeyTrigger(inputManager, KeyEvent.KEYCODE_BUTTON_1,
                getAction(context, SINGLE_SETTING, ACTION_HOME));
        applyKeyTrigger(inputManager, KeyEvent.KEYCODE_BUTTON_2,
                getAction(context, DOUBLE_SETTING, ACTION_SCREENSHOT));
        applyKeyTrigger(inputManager, KeyEvent.KEYCODE_BUTTON_3,
                getAction(context, LONG_SETTING, ACTION_NONE));
        applyKeyTrigger(inputManager, KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY,
                getAction(context, SINGLE_SETTING, ACTION_HOME));
        applyKeyTrigger(inputManager, KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY,
                getAction(context, DOUBLE_SETTING, ACTION_SCREENSHOT));
        applyKeyTrigger(inputManager, KeyEvent.KEYCODE_STYLUS_BUTTON_TAIL,
                getAction(context, LONG_SETTING, ACTION_NONE));
    }

    private static void applyKeyTrigger(InputManager inputManager, int keyCode, int action) {
        InputGestureData.Trigger trigger = InputGestureData.createKeyTrigger(keyCode, 0);
        InputGestureData existing = inputManager.getInputGesture(trigger);
        if (existing != null) {
            inputManager.removeCustomInputGesture(existing);
        }

        int frameworkAction = mapToFrameworkGestureType(action);
        if (frameworkAction == KeyGestureEvent.KEY_GESTURE_TYPE_UNSPECIFIED) {
            return;
        }

        InputGestureData gesture = new InputGestureData.Builder()
                .setTrigger(trigger)
                .setKeyGestureType(frameworkAction)
                .setAllowCaptureByFocusedWindow(false)
                .build();
        int result = inputManager.addCustomInputGesture(gesture);
        if (result != InputManager.CUSTOM_INPUT_GESTURE_RESULT_SUCCESS) {
            Log.e(TAG, "Unable to map stylus key " + keyCode + ", result " + result);
        }
    }

    private static int mapToFrameworkGestureType(int action) {
        switch (action) {
            case ACTION_TOOLBAR:
                return KeyGestureEvent.KEY_GESTURE_TYPE_OPEN_NOTES;
            case ACTION_SCREENSHOT:
                return KeyGestureEvent.KEY_GESTURE_TYPE_TAKE_SCREENSHOT;
            case ACTION_RECENTS:
                return KeyGestureEvent.KEY_GESTURE_TYPE_RECENT_APPS;
            case ACTION_HOME:
                return KeyGestureEvent.KEY_GESTURE_TYPE_HOME;
            case ACTION_PLAY_PAUSE:
                return KeyGestureEvent.KEY_GESTURE_TYPE_MEDIA_KEY;
            default:
                return KeyGestureEvent.KEY_GESTURE_TYPE_UNSPECIFIED;
        }
    }


    static void executeGesture(Context context, int gestureMask) {
        if ((gestureMask & 0x01) != 0) {
            executeAction(context, getAction(context, SINGLE_SETTING, ACTION_HOME));
        } else if ((gestureMask & 0x02) != 0) {
            executeAction(context, getAction(context, DOUBLE_SETTING, ACTION_SCREENSHOT));
        } else if ((gestureMask & 0x08) != 0) {
            executeAction(context, getAction(context, LONG_SETTING, ACTION_NONE));
        }
    }

    static void executeAction(Context context, int action) {
        switch (action) {
            case ACTION_TOOLBAR:
                FloatingToolbarService.showToolbar(context);
                break;
            case ACTION_SCREENSHOT:
                injectKey(context, KeyEvent.KEYCODE_SYSRQ);
                break;
            case ACTION_RECENTS:
                injectKey(context, KeyEvent.KEYCODE_APP_SWITCH);
                break;
            case ACTION_HOME:
                injectKey(context, KeyEvent.KEYCODE_HOME);
                break;
            case ACTION_PLAY_PAUSE:
                injectKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                break;
            default:
                break;
        }
    }

    private static void injectKey(Context context, int keyCode) {
        InputManager inputManager = context.getSystemService(InputManager.class);
        if (inputManager == null) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        int flags = KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY;
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags, InputDevice.SOURCE_KEYBOARD);
        KeyEvent up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP);
        boolean downInjected = inputManager.injectInputEvent(
                down, InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT);
        boolean upInjected = inputManager.injectInputEvent(
                up, InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT);
        if (!downInjected || !upInjected) {
            Log.e(TAG, "Unable to inject key " + keyCode + ", down="
                    + downInjected + ", up=" + upInjected);
        }
    }
}
