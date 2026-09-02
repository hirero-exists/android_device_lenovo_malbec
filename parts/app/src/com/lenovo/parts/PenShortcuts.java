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

import android.content.Context;
import android.hardware.input.InputGestureData;
import android.hardware.input.InputManager;
import android.hardware.input.KeyGestureEvent;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
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
            case ACTION_SCREENSHOT:
                return KeyGestureEvent.KEY_GESTURE_TYPE_TAKE_SCREENSHOT;
            case ACTION_RECENTS:
                return KeyGestureEvent.KEY_GESTURE_TYPE_RECENT_APPS;
            case ACTION_HOME:
                return KeyGestureEvent.KEY_GESTURE_TYPE_HOME;
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
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0);
        KeyEvent up = new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0);
        inputManager.injectInputEvent(down, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
        inputManager.injectInputEvent(up, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
    }
}
