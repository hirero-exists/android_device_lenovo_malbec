package com.lenovo.parts;

import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public final class DesktopModeTile extends TileService {
    private static final String SETTING = "override_desktop_mode_features";

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        boolean enabled = Settings.Global.getInt(getContentResolver(), SETTING, 0) == 1;
        Settings.Global.putInt(getContentResolver(), SETTING, enabled ? 0 : 1);
        updateTile();
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        boolean enabled = Settings.Global.getInt(getContentResolver(), SETTING, 0) == 1;
        tile.setState(enabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setSubtitle(enabled ? getString(R.string.desktop_mode_on) : getString(R.string.desktop_mode_off));
        tile.updateTile();
    }
}
