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
    static final String TRIPLE_SETTING = "malbec_pen_triple_action";
    static final String LONG_SETTING = "malbec_pen_long_action";
    static final String LONG_CLICK_SETTING = "malbec_pen_long_click_action";

    private static final String TAG = "LenovoParts";

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
        apply(inputManager, KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY,
                getAction(context, SINGLE_SETTING,
                        KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_ASSISTANT));
        apply(inputManager, KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY,
                getAction(context, DOUBLE_SETTING,
                        KeyGestureEvent.KEY_GESTURE_TYPE_TAKE_SCREENSHOT));
        apply(inputManager, KeyEvent.KEYCODE_STYLUS_BUTTON_TERTIARY,
                getAction(context, TRIPLE_SETTING,
                        KeyGestureEvent.KEY_GESTURE_TYPE_RECENT_APPS));
        apply(inputManager, KeyEvent.KEYCODE_STYLUS_BUTTON_TAIL,
                getAction(context, LONG_SETTING,
                        KeyGestureEvent.KEY_GESTURE_TYPE_UNSPECIFIED));
        apply(inputManager, KeyEvent.KEYCODE_F13,
                getAction(context, LONG_CLICK_SETTING,
                        KeyGestureEvent.KEY_GESTURE_TYPE_UNSPECIFIED));
    }

    private static void apply(InputManager inputManager, int keyCode, int action) {
        InputGestureData.Trigger trigger = InputGestureData.createKeyTrigger(keyCode, 0);
        InputGestureData existing = inputManager.getInputGesture(trigger);
        if (existing != null) {
            inputManager.removeCustomInputGesture(existing);
        }
        if (action == KeyGestureEvent.KEY_GESTURE_TYPE_UNSPECIFIED) {
            return;
        }
        InputGestureData gesture = new InputGestureData.Builder()
                .setTrigger(trigger)
                .setKeyGestureType(action)
                .setAllowCaptureByFocusedWindow(false)
                .build();
        int result = inputManager.addCustomInputGesture(gesture);
        if (result != InputManager.CUSTOM_INPUT_GESTURE_RESULT_SUCCESS) {
            Log.e(TAG, "Unable to map stylus key " + keyCode + ", result " + result);
        }
    }

    static void executeGesture(Context context, int gestureMask) {
        if ((gestureMask & 0x01) != 0) {
            executeAction(context, getAction(context, SINGLE_SETTING,
                    KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_ASSISTANT));
        } else if ((gestureMask & 0x02) != 0) {
            executeAction(context, getAction(context, DOUBLE_SETTING,
                    KeyGestureEvent.KEY_GESTURE_TYPE_TAKE_SCREENSHOT));
        } else if ((gestureMask & 0x04) != 0) {
            executeAction(context, getAction(context, TRIPLE_SETTING,
                    KeyGestureEvent.KEY_GESTURE_TYPE_RECENT_APPS));
        } else if ((gestureMask & 0x08) != 0) {
            executeAction(context, getAction(context, LONG_SETTING,
                    KeyGestureEvent.KEY_GESTURE_TYPE_UNSPECIFIED));
        } else if ((gestureMask & 0x10) != 0) {
            executeAction(context, getAction(context, LONG_CLICK_SETTING,
                    KeyGestureEvent.KEY_GESTURE_TYPE_UNSPECIFIED));
        }
    }

    static void executeAction(Context context, int action) {
        InputManager inputManager = context.getSystemService(InputManager.class);
        if (inputManager == null) {
            return;
        }
        int keyCode;
        switch (action) {
            case KeyGestureEvent.KEY_GESTURE_TYPE_TAKE_SCREENSHOT:
                keyCode = KeyEvent.KEYCODE_SYSRQ;
                break;
            case KeyGestureEvent.KEY_GESTURE_TYPE_RECENT_APPS:
                keyCode = KeyEvent.KEYCODE_APP_SWITCH;
                break;
            case KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_ASSISTANT:
            case KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_VOICE_ASSISTANT:
                keyCode = KeyEvent.KEYCODE_ASSIST;
                break;
            case KeyGestureEvent.KEY_GESTURE_TYPE_HOME:
                keyCode = KeyEvent.KEYCODE_HOME;
                break;
            default:
                return;
        }
        long now = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0);
        KeyEvent up = new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0);
        inputManager.injectInputEvent(down, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
        inputManager.injectInputEvent(up, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
    }
}
