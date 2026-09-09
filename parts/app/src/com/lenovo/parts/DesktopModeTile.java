package com.lenovo.parts;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public final class DesktopModeTile extends TileService {

    static void migrateLegacyToggle(Context context) {
        var preferences = context.createDeviceProtectedStorageContext()
                .getSharedPreferences("desktop_mode", Context.MODE_PRIVATE);
        if (!preferences.getBoolean("legacy_toggle_removed", false)
                && Settings.Global.putInt(context.getContentResolver(),
                        "override_desktop_mode_features", -1)) {
            preferences.edit().putBoolean("legacy_toggle_removed", true).apply();
        }
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        openDesktop(this);
    }

    static void openDesktop(Context context) {
        Context app = context.getApplicationContext();
        app.sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
        new Handler(Looper.getMainLooper()).postDelayed(() ->
                PenShortcuts.injectKeyWithMeta(app, KeyEvent.KEYCODE_DPAD_DOWN,
                        KeyEvent.META_META_ON | KeyEvent.META_CTRL_ON), 300);
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        tile.setState(Tile.STATE_INACTIVE);
        tile.setSubtitle(getString(R.string.desktop_mode_open_app));
        tile.updateTile();
    }
}
