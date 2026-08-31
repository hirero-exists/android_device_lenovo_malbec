package com.lenovo.parts;

import android.content.Context;
import android.hardware.input.InputGestureData;
import android.hardware.input.InputManager;
import android.hardware.input.KeyGestureEvent;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;

final class PenShortcuts {
    static final String PRIMARY_SETTING = "malbec_pen_primary_action";
    static final String SECONDARY_SETTING = "malbec_pen_secondary_action";
    static final String TERTIARY_SETTING = "malbec_pen_tertiary_action";
    static final String TAIL_SETTING = "malbec_pen_tail_action";

    private static final String TAG = "LenovoParts";

    private PenShortcuts() {
    }

    static int getAction(Context context, String setting, int defaultAction) {
        return Settings.Secure.getInt(context.getContentResolver(), setting, defaultAction);
    }

    static void setAction(Context context, String setting, int action) {
        Settings.Secure.putInt(context.getContentResolver(), setting, action);
        apply(context);
    }

    static void apply(Context context) {
        InputManager inputManager = context.getSystemService(InputManager.class);
        apply(inputManager, KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY,
                getAction(context, PRIMARY_SETTING,
                        KeyGestureEvent.KEY_GESTURE_TYPE_LAUNCH_ASSISTANT));
        apply(inputManager, KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY,
                getAction(context, SECONDARY_SETTING,
                        KeyGestureEvent.KEY_GESTURE_TYPE_TAKE_SCREENSHOT));
        apply(inputManager, KeyEvent.KEYCODE_STYLUS_BUTTON_TERTIARY,
                getAction(context, TERTIARY_SETTING, KeyGestureEvent.KEY_GESTURE_TYPE_RECENT_APPS));
        apply(inputManager, KeyEvent.KEYCODE_STYLUS_BUTTON_TAIL,
                getAction(context, TAIL_SETTING, KeyGestureEvent.KEY_GESTURE_TYPE_UNSPECIFIED));
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
}
