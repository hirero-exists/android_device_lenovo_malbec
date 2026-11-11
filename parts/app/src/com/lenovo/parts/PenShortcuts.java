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
    static final int ACTION_PLAY_PAUSE = 5;
    static final int ACTION_SCREENSHOT = 2;
    static final int ACTION_RECENTS = 3;
    static final int ACTION_HOME = 4;
    static final int ACTION_TOOLBAR = 6;
    static final int ACTION_BRUSH = 7;
    static final int ACTION_CIRCLE_SEARCH = 8;

    private static final String TAG = "PenShortcuts";
    private static final String MIGRATION_SETTING = "malbec_pen_actions_version";
    private static final String CLEANUP_SETTING = "malbec_pen_gesture_cleanup_version";

    private PenShortcuts() {
    }

    static void migrateActions(Context context) {
        if (Settings.Secure.getInt(context.getContentResolver(), MIGRATION_SETTING, 0) >= 1) {
            return;
        }
        if (migrateAction(context, SINGLE_SETTING, ACTION_TOOLBAR)
                && migrateAction(context, DOUBLE_SETTING, ACTION_NONE)
                && migrateAction(context, LONG_SETTING, ACTION_NONE)) {
            Settings.Secure.putInt(context.getContentResolver(), MIGRATION_SETTING, 1);
        }
    }

    private static boolean migrateAction(Context context, String setting, int fallback) {
        String value = Settings.Secure.getString(context.getContentResolver(), setting);
        if (value != null) {
            try {
                int action = Integer.parseInt(value);
                if (action == ACTION_NONE || action == ACTION_SCREENSHOT
                        || action == ACTION_RECENTS || action == ACTION_HOME
                        || action == ACTION_PLAY_PAUSE || action == ACTION_TOOLBAR
                        || action == ACTION_BRUSH || action == ACTION_CIRCLE_SEARCH) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return Settings.Secure.putInt(context.getContentResolver(), setting, fallback);
    }

    static int getAction(Context context, String setting, int defaultAction) {
        return Settings.Secure.getInt(context.getContentResolver(), setting, defaultAction);
    }

    static boolean setAction(Context context, String setting, int action) {
        if (action != ACTION_NONE && action != ACTION_PLAY_PAUSE && action != ACTION_SCREENSHOT
                && action != ACTION_RECENTS && action != ACTION_HOME && action != ACTION_TOOLBAR
                && action != ACTION_BRUSH && action != ACTION_CIRCLE_SEARCH) {
            return false;
        }
        return Settings.Secure.putInt(context.getContentResolver(), setting, action);
    }

    static void executeAction(Context context, int action) {
        switch (action) {
            case ACTION_BRUSH:
                PenEditorService.cycleBrush();
                break;
            case ACTION_CIRCLE_SEARCH:
                PenPlatformActions.circleSearch(context);
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
            case ACTION_TOOLBAR:
                FloatingToolbarService.toggleMenu(context);
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
                return;
            } catch (Exception ignored) {
            }
        }

        AudioManager audioManager = context.getSystemService(AudioManager.class);
        if (audioManager != null) {
            try {
                audioManager.dispatchMediaKeyEvent(down);
                audioManager.dispatchMediaKeyEvent(up);
                return;
            } catch (Exception ignored) {
            }
        }

        injectKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
    }

    static void injectKey(Context context, int keyCode) {
        injectKeyWithMeta(context, keyCode, 0);
    }

    static void injectKeyWithMeta(Context context, int keyCode, int metaState) {
        InputManager inputManager = context.getSystemService(InputManager.class);
        if (inputManager == null) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        int flags = KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY;
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags,
                InputDevice.SOURCE_KEYBOARD);
        KeyEvent up = new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags,
                InputDevice.SOURCE_KEYBOARD);
        inputManager.injectInputEvent(down,
                InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
        inputManager.injectInputEvent(up,
                InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
    }

    static void removeLegacyGestures(Context context) {
        if (Settings.Secure.getInt(context.getContentResolver(), CLEANUP_SETTING, 0) >= 1) return;
        InputManager inputManager = context.getSystemService(InputManager.class);
        if (inputManager == null) {
            return;
        }

        try {
            boolean success = removeLegacyTrigger(context, inputManager, KeyEvent.KEYCODE_BUTTON_1,
                    getAction(context, SINGLE_SETTING, ACTION_PLAY_PAUSE));
            success &= removeLegacyTrigger(context, inputManager, KeyEvent.KEYCODE_BUTTON_2,
                    getAction(context, DOUBLE_SETTING, ACTION_SCREENSHOT));
            success &= removeLegacyTrigger(context, inputManager, KeyEvent.KEYCODE_BUTTON_3,
                    getAction(context, LONG_SETTING, ACTION_NONE));
            success &= removeLegacyTrigger(context, inputManager, KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY,
                    getAction(context, SINGLE_SETTING, ACTION_PLAY_PAUSE));
            success &= removeLegacyTrigger(context, inputManager, KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY,
                    getAction(context, DOUBLE_SETTING, ACTION_SCREENSHOT));
            success &= removeLegacyTrigger(context, inputManager, KeyEvent.KEYCODE_STYLUS_BUTTON_TAIL,
                    getAction(context, LONG_SETTING, ACTION_NONE));
            if (success) Settings.Secure.putInt(context.getContentResolver(), CLEANUP_SETTING, 1);
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to remove old pen mappings", e);
        }
    }

    private static boolean removeLegacyTrigger(Context context, InputManager inputManager,
            int keyCode, int action) {
        InputGestureData.Trigger trigger = InputGestureData.createKeyTrigger(keyCode, 0);
        InputGestureData existing = inputManager.getInputGesture(trigger);
        if (existing == null) return true;

        InputGestureData.Builder builder = new InputGestureData.Builder()
                .setTrigger(trigger)
                .setAllowCaptureByFocusedWindow(false);
        switch (action) {
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
            case ACTION_TOOLBAR:
                builder.setKeyGestureType(KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_APPLICATION)
                        .setAppLaunchData(AppLaunchData.createLaunchDataForComponent(
                                context.getPackageName(),
                                ToolbarTrampolineActivity.class.getName()));
                break;
            default:
                return true;
        }

        InputGestureData gesture = builder.build();
        if (existing.getAction().equals(gesture.getAction())) {
            int result = inputManager.removeCustomInputGesture(existing);
            if (result != InputManager.CUSTOM_INPUT_GESTURE_RESULT_SUCCESS) {
                Log.e(TAG, "Unable to remove old pen mapping " + keyCode + ", result " + result);
                return false;
            }
        }
        return true;
    }
}
