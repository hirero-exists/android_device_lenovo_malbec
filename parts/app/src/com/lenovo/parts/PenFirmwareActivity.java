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
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class PenFirmwareActivity extends CollapsingToolbarBaseActivity {
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Consumer<PenIdentity> mObserver = this::onIdentity;
    private PenIdentity mIdentity;
    private PenFirmwareQuery mQuery;
    private long mToken;
    private boolean mActive;
    private TextView mCurrent;
    private TextView mStatus;
    private Button mCheck;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.pen_firmware_page_title);
        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setHomeButtonEnabled(true);
        }
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        mCurrent = new TextView(this);
        mStatus = new TextView(this);
        mCheck = new Button(this);
        mCheck.setText(R.string.pen_firmware_check);
        mCheck.setOnClickListener(v -> check());
        content.addView(mCurrent);
        content.addView(mStatus);
        content.addView(mCheck);
        ViewGroup frame = requireViewById(
                com.android.settingslib.collapsingtoolbar.R.id.content_frame);
        frame.addView(content);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mActive = true;
        PenGattService.observe(mObserver);
    }

    @Override
    protected void onPause() {
        mActive = false;
        PenGattService.unobserve(mObserver);
        cancelQuery();
        mIdentity = null;
        render();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        cancelQuery();
        mExecutor.shutdownNow();
        super.onDestroy();
    }

    private void onIdentity(PenIdentity identity) {
        if (!sameIdentity(mIdentity, identity)) {
            cancelQuery();
            mIdentity = identity;
            mStatus.setText(PenFirmwareQuery.supported(identity)
                    ? "" : getString(R.string.pen_firmware_connect));
        } else {
            mIdentity = identity;
        }
        render();
    }

    private static boolean sameIdentity(PenIdentity a, PenIdentity b) {
        return a != null && b != null && a.generation == b.generation
                && a.connection == b.connection && a.pnpSource == b.pnpSource
                && a.pnpVendor == b.pnpVendor && a.pnpProduct == b.pnpProduct
                && Objects.equals(a.deviceName, b.deviceName)
                && Objects.equals(a.model, b.model) && Objects.equals(a.serial, b.serial)
                && Objects.equals(a.firmware, b.firmware);
    }

    private void render() {
        boolean supported = PenFirmwareQuery.supported(mIdentity);
        mCurrent.setText(supported
                ? getString(R.string.pen_firmware_current, mIdentity.firmware)
                : getString(R.string.pen_firmware_current_unknown));
        if (!supported) mStatus.setText(R.string.pen_firmware_connect);
        mCheck.setEnabled(mActive && supported && mQuery == null);
    }

    private void check() {
        if (!mActive || mQuery != null || !PenFirmwareQuery.supported(mIdentity)) return;
        PenIdentity identity = mIdentity;
        PenFirmwareQuery query = new PenFirmwareQuery();
        mQuery = query;
        long token = ++mToken;
        mStatus.setText(R.string.pen_firmware_checking);
        render();
        mExecutor.execute(() -> {
            PenFirmwareQuery.Result result = query.execute(identity);
            mHandler.post(() -> {
                if (!mActive || token != mToken || mQuery != query
                        || !sameIdentity(identity, mIdentity)) return;
                mQuery = null;
                if (result.status == PenFirmwareQuery.Status.LATEST) {
                    mStatus.setText(R.string.pen_firmware_latest);
                } else if (result.status == PenFirmwareQuery.Status.AVAILABLE) {
                    mStatus.setText(getString(R.string.pen_firmware_available, result.version));
                } else {
                    mStatus.setText(R.string.pen_firmware_error);
                }
                render();
            });
        });
    }

    private void cancelQuery() {
        mToken++;
        if (mQuery != null) {
            mQuery.cancel();
            mQuery = null;
        }
    }

    @Override
    public boolean onNavigateUp() {
        finish();
        return true;
    }
}
