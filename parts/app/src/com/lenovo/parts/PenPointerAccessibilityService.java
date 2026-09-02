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

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.hardware.input.InputManager;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

public final class PenPointerAccessibilityService extends AccessibilityService {
    private static final String TAG = "PenPointer";
    private static final int DOT_SIZE_DP = 6;

    private WindowManager mWindowManager;
    private View mDot;
    private boolean mDotShown;

    @Override
    protected void onServiceConnected() {
        AccessibilityServiceInfo info = getServiceInfo();
        info.setMotionEventSources(InputDevice.SOURCE_STYLUS);
        setServiceInfo(info);
        mWindowManager = getSystemService(WindowManager.class);
    }

    @Override
    public void onMotionEvent(MotionEvent event) {
        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_HOVER_ENTER:
            case MotionEvent.ACTION_HOVER_MOVE:
                moveDot(event.getRawX(), event.getRawY());
                break;
            case MotionEvent.ACTION_HOVER_EXIT:
                hideDot();
                break;
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                hideDot();
                MotionEvent clone = MotionEvent.obtain(event);
                clone.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                InputManager inputManager = getSystemService(InputManager.class);
                if (inputManager != null) {
                    inputManager.injectInputEvent(clone, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
                }
                clone.recycle();
                break;
            default:
                break;
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        hideDot();
        return super.onUnbind(intent);
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private void moveDot(float x, float y) {
        if (mWindowManager == null) {
            return;
        }
        try {
            int px = Math.round(x);
            int py = Math.round(y);
            if (!mDotShown) {
                if (mDot == null) {
                    mDot = createDot();
                }
                WindowManager.LayoutParams params = buildParams(px, py);
                mWindowManager.addView(mDot, params);
                mDotShown = true;
            } else {
                mWindowManager.updateViewLayout(mDot, buildParams(px, py));
            }
        } catch (Exception e) {
            Log.e(TAG, "Unable to draw pen pointer", e);
            mDotShown = false;
        }
    }

    private void hideDot() {
        if (!mDotShown || mDot == null || mWindowManager == null) {
            return;
        }
        try {
            mWindowManager.removeView(mDot);
        } catch (Exception ignored) {
        }
        mDotShown = false;
    }

    private View createDot() {
        int size = dpToPx(DOT_SIZE_DP);
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(Color.WHITE);
        drawable.setStroke(dpToPx(1), 0x40000000);
        View view = new View(this);
        view.setBackground(drawable);
        view.setMinimumWidth(size);
        view.setMinimumHeight(size);
        return view;
    }

    private WindowManager.LayoutParams buildParams(int x, int y) {
        int size = dpToPx(DOT_SIZE_DP);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = x - size / 2;
        params.y = y - size / 2;
        params.setTitle("PenPointer");
        return params;
    }

    static boolean isEnabled(Context context) {
        return PenMode.isPointerEnabled();
    }

    static void apply(Context context, boolean enabled) {
        Settings.Secure.putInt(context.getContentResolver(),
                "stylus_pointer_icon_enabled", enabled ? 1 : 0);
        try {
            SystemProperties.set(
                    "persist.debug.input.force_enable_stylus_pointer_icon", enabled ? "1" : "0");
        } catch (Exception ignored) {
        }

        String component = context.getPackageName() + "/"
                + PenPointerAccessibilityService.class.getName();
        String existing = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean present = existing != null
                && java.util.Arrays.asList(existing.split(":")).contains(component);
        if (enabled == present) {
            return;
        }
        String updated;
        if (enabled) {
            updated = existing == null || existing.isEmpty()
                    ? component : existing + ":" + component;
        } else {
            StringBuilder builder = new StringBuilder();
            for (String service : existing.split(":")) {
                if (!service.equals(component) && !service.isEmpty()) {
                    if (builder.length() > 0) {
                        builder.append(':');
                    }
                    builder.append(service);
                }
            }
            updated = builder.toString();
        }
        Settings.Secure.putString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated);
        Settings.Secure.putInt(context.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_ENABLED, 1);
    }
}
