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
import android.provider.Settings;
import android.hardware.input.InputSettings;
import androidx.preference.EditTextPreference;
import androidx.preference.MultiSelectListPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.TwoStatePreference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;
import java.util.function.Consumer;

public final class PenSettingsFragment extends SettingsBasePreferenceFragment
        implements Preference.OnPreferenceChangeListener {
    private static final String[] KEYS = { "pen_enabled", "pen_toolbar", "pen_gestures",
            "pen_single_action", "pen_double_action", "pen_long_action", "pen_corner_left",
            "pen_corner_right", "pen_held_note", "pen_handwriting", "pen_hover", "pen_sound",
            "pen_lost_alert", "pen_name", "pen_remote", "pen_screen_off", "pen_guard_apps", "pen_refresh_cap" };
    private final Consumer<PenIdentity> mIdentityListener = this::showIdentity;
    private final Consumer<PenRefreshController.State> mRefreshListener = state -> {
        Preference preference = findPreference("pen_refresh_cap");
        if (preference != null) preference.setSummary(state == PenRefreshController.State.ACTIVE
                ? R.string.pen_refresh_active : state == PenRefreshController.State.WITHIN_LIMIT
                        ? R.string.pen_refresh_within_limit : state == PenRefreshController.State.PENDING
                        ? R.string.pen_refresh_pending : state == PenRefreshController.State.UNAVAILABLE
                        ? R.string.pen_refresh_unavailable : R.string.pen_refresh_cap_summary);
    };
    private final Consumer<Boolean> mTransportListener = available -> {
        Preference preference = findPreference("pen_transport");
        if (preference != null) preference.setSummary(available
                ? R.string.pen_transport_available : R.string.pen_transport_unavailable);
    };

    @Override
    public void onCreatePreferences(Bundle state, String rootKey) {
        setPreferencesFromResource(R.xml.pen_settings, rootKey);
        for (String key : KEYS) {
            Preference preference = findPreference(key);
            if (preference != null) {
                preference.setPersistent(false);
                preference.setOnPreferenceChangeListener(this);
            }
        }
        java.util.TreeMap<String, String> apps = new java.util.TreeMap<>();
        android.content.pm.PackageManager pm = requireContext().getPackageManager();
        for (android.content.pm.ResolveInfo info : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            apps.put(info.activityInfo.packageName, info.loadLabel(pm).toString());
        }
        MultiSelectListPreference guard = findPreference("pen_guard_apps");
        guard.setEntryValues(apps.keySet().toArray(new String[0]));
        guard.setEntries(apps.values().toArray(new String[0]));
    }

    @Override
    public void onResume() {
        super.onResume();
        PenGattService.observe(mIdentityListener);
        PenActionRouter.get(requireContext()).observe(mTransportListener);
        PenRefreshController.get(requireContext()).observe(mRefreshListener);
        Context context = getContext();
        if (context == null) return;
        boolean enabled = PenMode.isEnabled();
        ((TwoStatePreference) findPreference("pen_enabled")).setChecked(enabled);
        ((TwoStatePreference) findPreference("pen_toolbar")).setChecked(PenMode.isToolbarEnabled());
        ((TwoStatePreference) findPreference("pen_gestures")).setChecked(PenMode.isGesturesDisabled());
        refreshAction(context, "pen_single_action", PenShortcuts.SINGLE_SETTING,
                PenShortcuts.ACTION_TOOLBAR);
        refreshAction(context, "pen_double_action", PenShortcuts.DOUBLE_SETTING,
                PenShortcuts.ACTION_NONE);
        refreshAction(context, "pen_long_action", PenShortcuts.LONG_SETTING,
                PenShortcuts.ACTION_NONE);
        ((ListPreference) findPreference("pen_corner_left")).setValue(Integer.toString(PenPreferences.get(context, PenPreferences.LEFT, 0)));
        ((ListPreference) findPreference("pen_corner_right")).setValue(Integer.toString(PenPreferences.get(context, PenPreferences.RIGHT, 0)));
        ((ListPreference) findPreference("pen_remote")).setValue(Integer.toString(PenPreferences.get(context, PenPreferences.REMOTE, 0)));
        ((TwoStatePreference) findPreference("pen_held_note")).setChecked(PenPreferences.get(context, PenPreferences.HELD_NOTE, 1) != 0);
        ((TwoStatePreference) findPreference("pen_screen_off")).setChecked(PenPreferences.get(context, PenPreferences.SCREEN_OFF, 0) != 0);
        TwoStatePreference handwriting = findPreference("pen_handwriting");
        handwriting.setChecked(Settings.Secure.getInt(context.getContentResolver(), Settings.Secure.STYLUS_HANDWRITING_ENABLED, 1) != 0);
        handwriting.setEnabled(PenPlatformActions.handwritingSupported(context));
        TwoStatePreference hover = findPreference("pen_hover");
        hover.setChecked(InputSettings.isStylusPointerIconEnabled(context, true));
        hover.setEnabled(context.getResources().getBoolean(com.android.internal.R.bool.config_enableStylusPointerIcon)
                && !android.sysprop.InputProperties.force_enable_stylus_pointer_icon().orElse(false));
        ((TwoStatePreference) findPreference("pen_sound")).setChecked(PenPreferences.get(context, PenPreferences.SOUND, 0) != 0);
        ((TwoStatePreference) findPreference("pen_lost_alert")).setChecked(PenPreferences.get(context, PenPreferences.LOST_ALERT, 1) != 0);
        ((TwoStatePreference) findPreference("pen_refresh_cap")).setChecked(PenPreferences.get(context, PenPreferences.REFRESH_CAP, 1) != 0);
        String guardApps = Settings.Secure.getString(context.getContentResolver(), PenPreferences.GUARD_APPS);
        ((MultiSelectListPreference) findPreference("pen_guard_apps")).setValues(guardApps == null || guardApps.isEmpty()
                ? new java.util.HashSet<>() : new java.util.HashSet<>(java.util.Arrays.asList(guardApps.split(","))));
    }

    @Override
    public void onPause() {
        PenGattService.unobserve(mIdentityListener);
        PenActionRouter.get(requireContext()).unobserve(mTransportListener);
        PenRefreshController.get(requireContext()).unobserve(mRefreshListener);
        super.onPause();
    }

    private void showIdentity(PenIdentity identity) {
        Preference connection = findPreference("pen_connection");
        Preference details = findPreference("pen_identity");
        if (connection == null || details == null || getContext() == null) return;
        int text;
        switch (identity.connection) {
            case DISABLED: text = R.string.pen_bluetooth_disabled; break;
            case BLUETOOTH_OFF: text = R.string.pen_bluetooth_off; break;
            case CONNECTING: text = R.string.pen_connecting; break;
            case UNVERIFIED: text = R.string.pen_verifying; break;
            case UNSUPPORTED: text = R.string.pen_unsupported; break;
            case VERIFIED: text = R.string.pen_connected; break;
            default: text = R.string.pen_disconnected; break;
        }
        connection.setSummary(text);
        long disconnected = PenGattService.lastDisconnected();
        findPreference("pen_last_disconnected").setSummary(disconnected == 0
                ? getString(R.string.pen_identity_unavailable)
                : android.text.format.DateFormat.getDateFormat(requireContext()).format(new java.util.Date(disconnected))
                        + " " + android.text.format.DateFormat.getTimeFormat(requireContext()).format(new java.util.Date(disconnected)));
        EditTextPreference name = findPreference("pen_name");
        name.setEnabled(identity.connection == PenIdentity.Connection.VERIFIED);
        name.setText(PenGattService.getAlias());
        name.setSummary(PenGattService.getAlias());
        String unavailable = getString(R.string.pen_identity_unavailable);
        if (identity.connection != PenIdentity.Connection.VERIFIED) {
            details.setSummary(unavailable);
        } else {
            details.setSummary(getString(R.string.pen_identity_summary,
                    identity.model == null ? unavailable : identity.model,
                    identity.serial == null ? unavailable : identity.serial,
                    identity.firmware == null ? unavailable : identity.firmware,
                    identity.battery < 0 ? unavailable
                            : getString(R.string.pen_battery_percent, identity.battery)));
        }
    }

    private void refreshAction(Context context, String key, String setting, int fallback) {
        ((ListPreference) findPreference(key)).setValue(Integer.toString(
                PenShortcuts.getAction(context, setting, fallback)));
    }

    @Override
    public boolean onPreferenceTreeClick(Preference preference) {
        if ("pen_palm".equals(preference.getKey())) {
            startActivity(new Intent(requireContext(), PenPalmActivity.class));
            return true;
        }
        if ("pen_handwriting_settings".equals(preference.getKey())) {
            PenPlatformActions.handwritingSettings(requireContext());
            return true;
        }
        if ("pen_firmware".equals(preference.getKey())) {
            startActivity(new Intent(requireContext(), PenFirmwareActivity.class));
            return true;
        }
        return super.onPreferenceTreeClick(preference);
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object value) {
        Context context = getContext();
        if (context == null) return false;
        switch (preference.getKey()) {
            case "pen_refresh_cap":
                if (!PenPreferences.put(context, PenPreferences.REFRESH_CAP, (Boolean) value ? 1 : 0)) return false;
                PenRefreshController.get(context).setEnabled(PenMode.isEnabled()
                        && (Boolean) value && android.app.ActivityManager.getCurrentUser() == context.getUserId());
                return true;
            case "pen_guard_apps": return Settings.Secure.putString(context.getContentResolver(), PenPreferences.GUARD_APPS,
                    String.join(",", (java.util.Set<String>) value));
            case "pen_corner_left": return PenPreferences.put(context, PenPreferences.LEFT, Integer.parseInt((String) value));
            case "pen_corner_right": return PenPreferences.put(context, PenPreferences.RIGHT, Integer.parseInt((String) value));
            case "pen_held_note": return PenPreferences.put(context, PenPreferences.HELD_NOTE, (Boolean) value ? 1 : 0);
            case "pen_screen_off": return PenPreferences.put(context, PenPreferences.SCREEN_OFF, (Boolean) value ? 1 : 0);
            case "pen_remote": return PenPreferences.put(context, PenPreferences.REMOTE, Integer.parseInt((String) value));
            case "pen_sound": return PenPreferences.put(context, PenPreferences.SOUND, (Boolean) value ? 1 : 0);
            case "pen_lost_alert": return PenPreferences.put(context, PenPreferences.LOST_ALERT, (Boolean) value ? 1 : 0);
            case "pen_hover": return Settings.Secure.putInt(context.getContentResolver(), Settings.Secure.STYLUS_POINTER_ICON_ENABLED, (Boolean) value ? 1 : 0);
            case "pen_handwriting": return PenPlatformActions.handwritingSupported(context) && Settings.Secure.putInt(context.getContentResolver(), Settings.Secure.STYLUS_HANDWRITING_ENABLED, (Boolean) value ? 1 : 0);
            case "pen_name": return PenGattService.rename(String.valueOf(value));
            case "pen_enabled":
                boolean enabled = (Boolean) value;
                if (!PenMode.setEnabled(enabled)) return false;
                PenGattService.onPenEnabledChanged();
                PenActionRouter.get(context).onEnabledChanged();
                if (!enabled) {
                    context.stopService(new Intent(context, FloatingToolbarService.class));
                } else if (PenMode.isToolbarEnabled()) {
                    context.startForegroundService(new Intent(context, FloatingToolbarService.class));
                }
                return true;
            case "pen_toolbar":
                boolean toolbar = (Boolean) value;
                if (!PenMode.setToolbarEnabled(toolbar)) return false;
                Intent intent = new Intent(context, FloatingToolbarService.class);
                if (toolbar) context.startForegroundService(intent);
                else context.stopService(intent);
                return true;
            case "pen_gestures":
                return PenMode.setGesturesDisabled((Boolean) value);
            case "pen_single_action":
                return PenShortcuts.setAction(context, PenShortcuts.SINGLE_SETTING,
                        Integer.parseInt((String) value));
            case "pen_double_action":
                return PenShortcuts.setAction(context, PenShortcuts.DOUBLE_SETTING,
                        Integer.parseInt((String) value));
            case "pen_long_action":
                return PenShortcuts.setAction(context, PenShortcuts.LONG_SETTING,
                        Integer.parseInt((String) value));
            default:
                return false;
        }
    }
}
