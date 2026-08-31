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
