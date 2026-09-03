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
import android.hardware.input.AppLaunchData;
import android.hardware.input.InputGestureData;
import android.hardware.input.InputManager;
import android.hardware.input.KeyGestureEvent;
import android.media.AudioManager;
import android.media.session.MediaSessionManager;
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

    static void executeAction(Context context, int action) {
        switch (action) {
            case ACTION_TOOLBAR:
                FloatingToolbarService.showToolbar(context);
                break;
            case ACTION_PLAY_PAUSE:
                dispatchMediaPlayPause(context);
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
            default:
                break;
        }
    }

    static void dispatchMediaPlayPause(Context context) {
        long now = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0);
        KeyEvent up = new KeyEvent(now, now, KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0);

        MediaSessionManager mediaSessionManager = context.getSystemService(MediaSessionManager.class);
        if (mediaSessionManager != null) {
            try {
                mediaSessionManager.dispatchMediaKeyEvent(down, false);
                mediaSessionManager.dispatchMediaKeyEvent(up, false);
            } catch (Exception ignored) {
            }
        }

        AudioManager audioManager = context.getSystemService(AudioManager.class);
        if (audioManager != null) {
            try {
                audioManager.dispatchMediaKeyEvent(down);
                audioManager.dispatchMediaKeyEvent(up);
            } catch (Exception ignored) {
            }
        }

        injectKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
    }

    static void injectKey(Context context, int keyCode) {
        InputManager inputManager = context.getSystemService(InputManager.class);
        if (inputManager == null) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        int flags = KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY;
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags,
                InputDevice.SOURCE_KEYBOARD);
        KeyEvent up = KeyEvent.changeAction(down, KeyEvent.ACTION_UP);
        inputManager.injectInputEvent(down,
                InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
        inputManager.injectInputEvent(up,
                InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
    }

    static void apply(Context context) {
        InputManager inputManager = context.getSystemService(InputManager.class);
        if (inputManager == null) {
            return;
        }

        applyKeyTrigger(context, inputManager, KeyEvent.KEYCODE_BUTTON_1,
                getAction(context, SINGLE_SETTING, ACTION_HOME));
        applyKeyTrigger(context, inputManager, KeyEvent.KEYCODE_BUTTON_2,
                getAction(context, DOUBLE_SETTING, ACTION_SCREENSHOT));
        applyKeyTrigger(context, inputManager, KeyEvent.KEYCODE_BUTTON_3,
                getAction(context, LONG_SETTING, ACTION_NONE));
        applyKeyTrigger(context, inputManager, KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY,
                getAction(context, SINGLE_SETTING, ACTION_HOME));
        applyKeyTrigger(context, inputManager, KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY,
                getAction(context, DOUBLE_SETTING, ACTION_SCREENSHOT));
        applyKeyTrigger(context, inputManager, KeyEvent.KEYCODE_STYLUS_BUTTON_TAIL,
                getAction(context, LONG_SETTING, ACTION_NONE));
    }

    private static void applyKeyTrigger(Context context, InputManager inputManager,
            int keyCode, int action) {
        InputGestureData.Trigger trigger = InputGestureData.createKeyTrigger(keyCode, 0);
        InputGestureData existing = inputManager.getInputGesture(trigger);
        if (existing != null) {
            inputManager.removeCustomInputGesture(existing);
        }

        InputGestureData.Builder builder = new InputGestureData.Builder()
                .setTrigger(trigger)
                .setAllowCaptureByFocusedWindow(false);
        switch (action) {
            case ACTION_TOOLBAR:
                builder.setKeyGestureType(KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_APPLICATION)
                        .setAppLaunchData(AppLaunchData.createLaunchDataForComponent(
                                context.getPackageName(),
                                ToolbarTrampolineActivity.class.getName()));
                break;
            case ACTION_PLAY_PAUSE:
                builder.setKeyGestureType(KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_APPLICATION)
                        .setAppLaunchData(AppLaunchData.createLaunchDataForComponent(
                                context.getPackageName(),
                                MediaTrampolineActivity.class.getName()));
                break;
            case ACTION_SCREENSHOT:
                builder.setKeyGestureType(KeyGestureEvent.KEY_GESTURE_TYPE_TAKE_SCREENSHOT);
                break;
            case ACTION_RECENTS:
                builder.setKeyGestureType(KeyGestureEvent.KEY_GESTURE_TYPE_RECENT_APPS);
                break;
            case ACTION_HOME:
                builder.setKeyGestureType(KeyGestureEvent.KEY_GESTURE_TYPE_HOME);
                break;
            default:
                return;
        }

        InputGestureData gesture = builder.build();
        int result = inputManager.addCustomInputGesture(gesture);
        if (result != InputManager.CUSTOM_INPUT_GESTURE_RESULT_SUCCESS) {
            Log.e(TAG, "Unable to map stylus key " + keyCode + ", result " + result);
        }
    }
}
