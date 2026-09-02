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

import android.app.ActionBar;
import android.os.Bundle;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;

public final class LenovoPartsActivity extends CollapsingToolbarBaseActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.app_name);

        getSupportFragmentManager().addOnBackStackChangedListener(this::updateBackNavigation);
        updateBackNavigation();

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction().replace(
                    com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                    new LenovoPartsFragment()).commit();
        }
    }

    private void updateBackNavigation() {
        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            boolean hasBackStack = getSupportFragmentManager().getBackStackEntryCount() > 0;
            actionBar.setDisplayHomeAsUpEnabled(hasBackStack);
            actionBar.setHomeButtonEnabled(hasBackStack);
        }
    }
}
