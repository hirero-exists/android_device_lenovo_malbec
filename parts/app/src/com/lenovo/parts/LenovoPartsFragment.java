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
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.TwoStatePreference;

import com.android.settingslib.widget.SettingsBasePreferenceFragment;

public final class LenovoPartsFragment extends SettingsBasePreferenceFragment
        implements Preference.OnPreferenceChangeListener {
    private static final String KEY_FOLIO_COVER = "folio_cover";
    private static final String KEY_PEN_ENABLED = "pen_enabled";
    private static final String KEY_PEN_TOOLBAR = "pen_toolbar";
    private static final String KEY_PEN_POINTER = "pen_pointer";
    private static final String KEY_PEN_SINGLE_ACTION = "pen_single_action";
    private static final String KEY_PEN_DOUBLE_ACTION = "pen_double_action";
    private static final String KEY_PEN_LONG_ACTION = "pen_long_action";
    private static final String KEY_REFRESH_RATE = "refresh_rate";
    private static final String KEY_HIGH_REPORT_RATE = "high_report_rate";
    private static final String KEY_GAME_EDGE = "game_edge";
    private static final String KEY_GAMING_BYPASS = "gaming_bypass";
    private static final String KEY_GAMING_OVERLAY = "gaming_overlay";
    private static final String KEY_GAMING_OVERLAY_SETTINGS = "gaming_overlay_settings";
    private static final String KEY_DOLBY_ENABLED = "dolby_enabled";
    private static final String KEY_DOLBY_PROFILE = "dolby_profile";
    private static final String KEY_DOLBY_SHOW_ICON = "dolby_show_icon";
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.lenovo_parts, rootKey);

        setupPreference(KEY_FOLIO_COVER);
        setupPreference(KEY_PEN_ENABLED);
        setupPreference(KEY_PEN_TOOLBAR);
        setupPreference(KEY_PEN_POINTER);
        setupPreference(KEY_PEN_SINGLE_ACTION);
        setupPreference(KEY_PEN_DOUBLE_ACTION);
        setupPreference(KEY_PEN_LONG_ACTION);
        setupPreference(KEY_REFRESH_RATE);
        setupPreference(KEY_HIGH_REPORT_RATE);
        setupPreference(KEY_GAME_EDGE);
        setupPreference(KEY_GAMING_BYPASS);
        setupPreference(KEY_GAMING_OVERLAY);
        setupPreference(KEY_GAMING_OVERLAY_SETTINGS);
        setupPreference(KEY_DOLBY_ENABLED);
        setupPreference(KEY_DOLBY_PROFILE);
        setupPreference(KEY_DOLBY_SHOW_ICON);

        refreshPreferences();
    }


    @Override
    public void onResume() {
        super.onResume();
        refreshPreferences();
    }

    private void setupPreference(String key) {
        Preference preference = findPreference(key);
        if (preference != null) {
            preference.setPersistent(false);
            preference.setOnPreferenceChangeListener(this);
        }
    }

    private void refreshPreferences() {
        Context context = getContext();
        if (context == null) {
            return;
        }

        TwoStatePreference folio = findPreference(KEY_FOLIO_COVER);
        if (folio != null) {
            folio.setChecked(FolioMode.isEnabled());
        }

        boolean penEnabled = PenMode.isEnabled();
        TwoStatePreference pen = findPreference(KEY_PEN_ENABLED);
        if (pen != null) {
            pen.setChecked(penEnabled);
        }

        TwoStatePreference penToolbar = findPreference(KEY_PEN_TOOLBAR);
        if (penToolbar != null) {
            penToolbar.setEnabled(penEnabled);
            penToolbar.setChecked(PenMode.isToolbarEnabled());
        }

        TwoStatePreference penPointer = findPreference(KEY_PEN_POINTER);
        if (penPointer != null) {
            penPointer.setEnabled(penEnabled);
            penPointer.setChecked(PenMode.isPointerEnabled());
        }

        refreshPenAction(KEY_PEN_SINGLE_ACTION, PenShortcuts.SINGLE_SETTING, PenShortcuts.ACTION_HOME);
        refreshPenAction(KEY_PEN_DOUBLE_ACTION, PenShortcuts.DOUBLE_SETTING, PenShortcuts.ACTION_SCREENSHOT);
        refreshPenAction(KEY_PEN_LONG_ACTION, PenShortcuts.LONG_SETTING, PenShortcuts.ACTION_NONE);

        ListPreference refreshRate = findPreference(KEY_REFRESH_RATE);
        if (refreshRate != null) {
            refreshRate.setValue(Integer.toString(
                    DisplayTouchMode.getRefreshRate(context.getContentResolver())));
        }

        TwoStatePreference highReport = findPreference(KEY_HIGH_REPORT_RATE);
        if (highReport != null) {
            highReport.setChecked(DisplayTouchMode.isHighReportRateEnabled());
        }

        TwoStatePreference gameEdge = findPreference(KEY_GAME_EDGE);
        if (gameEdge != null) {
            gameEdge.setChecked(DisplayTouchMode.isGameEdgeEnabled());
        }

        TwoStatePreference bypass = findPreference(KEY_GAMING_BYPASS);
        if (bypass != null) {
            bypass.setChecked(GamingBypassController.getInstance(context).isBypassEnabled());
        }

        TwoStatePreference overlay = findPreference(KEY_GAMING_OVERLAY);
        if (overlay != null) {
            overlay.setChecked(GamingOverlayService.isOverlayActive());
        }

        boolean dolbyEnabled = DolbyMode.isEnabled(context.getContentResolver());
        TwoStatePreference dolbySwitch = findPreference(KEY_DOLBY_ENABLED);
        if (dolbySwitch != null) {
            dolbySwitch.setChecked(dolbyEnabled);
        }

        ListPreference dolbyProfile = findPreference(KEY_DOLBY_PROFILE);
        if (dolbyProfile != null) {
            dolbyProfile.setEnabled(dolbyEnabled);
            dolbyProfile.setValue(Integer.toString(
                    DolbyMode.getProfile(context.getContentResolver())));
        }

        TwoStatePreference dolbyIcon = findPreference(KEY_DOLBY_SHOW_ICON);
        if (dolbyIcon != null) {
            dolbyIcon.setEnabled(dolbyEnabled);
            dolbyIcon.setChecked(DolbyMode.isIconEnabled(context.getContentResolver()));
        }

        TwoStatePreference overlayPref = findPreference(KEY_GAMING_OVERLAY);
        if (overlayPref != null) {
            overlayPref.setChecked(android.os.SystemProperties.getBoolean("persist.sys.gaming.overlay", false));
        }
    }

    @Override
    public boolean onPreferenceTreeClick(Preference preference) {
        if (KEY_GAMING_OVERLAY_SETTINGS.equals(preference.getKey())) {
            startActivity(new Intent(getContext(), GamingOverlaySettingsActivity.class));
            return true;
        }
        return super.onPreferenceTreeClick(preference);
    }

    private void refreshPenAction(String preferenceKey, String setting, int defaultAction) {
        Context context = getContext();
        if (context == null) {
            return;
        }
        ListPreference preference = findPreference(preferenceKey);
        if (preference != null) {
            preference.setValue(Integer.toString(
                    PenShortcuts.getAction(context, setting, defaultAction)));
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        Context context = getContext();
        if (context == null) {
            return false;
        }


        String key = preference.getKey();
        if (KEY_FOLIO_COVER.equals(key)) {
            return FolioMode.setEnabled((Boolean) newValue);
        } else if (KEY_PEN_ENABLED.equals(key)) {
            boolean enabled = (Boolean) newValue;
            if (PenMode.setEnabled(enabled)) {
                PenGattService.onPenEnabledChanged();
                Preference penToolbar = findPreference(KEY_PEN_TOOLBAR);
                if (penToolbar != null) {
                    penToolbar.setEnabled(enabled);
                }
                Preference single = findPreference(KEY_PEN_SINGLE_ACTION);
                if (single != null) {
                    single.setEnabled(enabled);
                }
                Preference dbl = findPreference(KEY_PEN_DOUBLE_ACTION);
                if (dbl != null) {
                    dbl.setEnabled(enabled);
                }
                Preference lng = findPreference(KEY_PEN_LONG_ACTION);
                if (lng != null) {
                    lng.setEnabled(enabled);
                }
                if (!enabled) {
                    context.stopService(new Intent(context, FloatingToolbarService.class));
                } else if (PenMode.isToolbarEnabled()) {
                    context.startForegroundService(new Intent(context, FloatingToolbarService.class));
                }
                return true;
            }
            return false;
        } else if (KEY_PEN_TOOLBAR.equals(key)) {
            boolean enabled = (Boolean) newValue;
            if (PenMode.setToolbarEnabled(enabled)) {
                if (enabled) {
                    context.startForegroundService(new Intent(context, FloatingToolbarService.class));
                } else {
                    context.stopService(new Intent(context, FloatingToolbarService.class));
                }
                return true;
            }
            return false;
        } else if (KEY_PEN_POINTER.equals(key)) {
            boolean enabled = (Boolean) newValue;
            if (PenMode.setPointerEnabled(enabled)) {
                PenPointerAccessibilityService.apply(context, enabled);
                return true;
            }
            return false;
        } else if (KEY_PEN_SINGLE_ACTION.equals(key)) {
            return PenShortcuts.setAction(context, PenShortcuts.SINGLE_SETTING,
                    Integer.parseInt((String) newValue));
        } else if (KEY_PEN_DOUBLE_ACTION.equals(key)) {
            return PenShortcuts.setAction(context, PenShortcuts.DOUBLE_SETTING,
                    Integer.parseInt((String) newValue));
        } else if (KEY_PEN_LONG_ACTION.equals(key)) {
            return PenShortcuts.setAction(context, PenShortcuts.LONG_SETTING,
                    Integer.parseInt((String) newValue));
        } else if (KEY_REFRESH_RATE.equals(key)) {
            int rate = Integer.parseInt((String) newValue);
            return DisplayTouchMode.setRefreshRate(context.getContentResolver(), rate);
        } else if (KEY_HIGH_REPORT_RATE.equals(key)) {
            boolean success = DisplayTouchMode.setHighReportRateEnabled((Boolean) newValue);
            if (success) {
                mHandler.postDelayed(this::refreshPreferences, 1200);
            }
            return success;
        } else if (KEY_GAME_EDGE.equals(key)) {
            boolean success = DisplayTouchMode.setGameEdgeEnabled((Boolean) newValue);
            if (success) {
                mHandler.postDelayed(this::refreshPreferences, 1200);
            }
            return success;
        } else if (KEY_GAMING_BYPASS.equals(key)) {
            boolean enable = (Boolean) newValue;
            if (enable && !GamingBypassController.isPowerConnected(context)) {
                Toast.makeText(context, R.string.gaming_bypass_connect_charger,
                        Toast.LENGTH_SHORT).show();
                return false;
            }
            boolean success = GamingBypassController.getInstance(context)
                    .setBypassEnabled(enable);
            if (!success) {
                Toast.makeText(context, R.string.gaming_bypass_backend_error,
                        Toast.LENGTH_SHORT).show();
            }
            mHandler.postDelayed(this::refreshPreferences, 1000);
            return success;
        } else if (KEY_GAMING_OVERLAY.equals(key)) {
            boolean enabled = (Boolean) newValue;
            android.os.SystemProperties.set("persist.sys.gaming.overlay", enabled ? "1" : "0");
            if (enabled) {
                GamingOverlayService.startOverlay(context);
            } else {
                GamingOverlayService.stopOverlay(context);
            }
            return true;
        } else if (KEY_DOLBY_ENABLED.equals(key)) {

            boolean enabled = (Boolean) newValue;
            boolean success = DolbyMode.setEnabled(context.getContentResolver(), enabled);
            if (success) {
                Preference profile = findPreference(KEY_DOLBY_PROFILE);
                if (profile != null) {
                    profile.setEnabled(enabled);
                }
                Preference icon = findPreference(KEY_DOLBY_SHOW_ICON);
                if (icon != null) {
                    icon.setEnabled(enabled);
                }
                DolbyStatusNotification.updateNotification(context);
            }
            return success;
        } else if (KEY_DOLBY_PROFILE.equals(key)) {
            return DolbyMode.setProfile(context.getContentResolver(),
                    Integer.parseInt((String) newValue));
        } else if (KEY_DOLBY_SHOW_ICON.equals(key)) {
            boolean enabled = (Boolean) newValue;
            boolean success = DolbyMode.setIconEnabled(context.getContentResolver(), enabled);
            if (success) {
                DolbyStatusNotification.updateNotification(context);
            }
            return success;
        }
        return true;
    }
}
