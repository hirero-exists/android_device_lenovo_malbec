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

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class AppPickerActivity extends Activity {
    private final List<ResolveInfo> mAllApps = new ArrayList<>();
    private final List<ResolveInfo> mFilteredApps = new ArrayList<>();
    private AppGridAdapter mAdapter;
    private PackageManager mPackageManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mPackageManager = getPackageManager();
        Intent queryIntent = new Intent(Intent.ACTION_MAIN);
        queryIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = mPackageManager.queryIntentActivities(queryIntent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL));

        for (ResolveInfo info : apps) {
            if (info.activityInfo != null && !getPackageName().equals(info.activityInfo.packageName)) {
                mAllApps.add(info);
            }
        }
        mAllApps.sort(Comparator.comparing(
                info -> info.loadLabel(mPackageManager).toString(),
                String.CASE_INSENSITIVE_ORDER));
        mFilteredApps.addAll(mAllApps);

        if (mAllApps.isEmpty()) {
            Toast.makeText(this, R.string.app_picker_empty, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xF0121214);
        root.setPadding(dpToPx(24), dpToPx(32), dpToPx(24), dpToPx(16));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dpToPx(16));

        TextView title = new TextView(this);
        title.setText(R.string.app_picker_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(22f);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        header.addView(title);

        Button closeHeaderBtn = new Button(this);
        closeHeaderBtn.setText("✕");
        closeHeaderBtn.setTextSize(16f);
        closeHeaderBtn.setTextColor(Color.WHITE);
        closeHeaderBtn.setBackgroundColor(Color.TRANSPARENT);
        closeHeaderBtn.setOnClickListener(v -> finish());
        header.addView(closeHeaderBtn);
        root.addView(header);

        EditText searchBar = new EditText(this);
        searchBar.setHint(R.string.app_drawer_search_hint);
        searchBar.setHintTextColor(0xFF888888);
        searchBar.setTextColor(Color.WHITE);
        searchBar.setTextSize(14f);
        searchBar.setSingleLine(true);
        GradientDrawable searchBg = new GradientDrawable();
        searchBg.setColor(0xFF222226);
        searchBg.setCornerRadius(dpToPx(24));
        searchBg.setStroke(dpToPx(1), 0x33FFFFFF);
        searchBar.setBackground(searchBg);
        searchBar.setPadding(dpToPx(18), dpToPx(10), dpToPx(18), dpToPx(10));
        searchBar.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterApps(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
        root.addView(searchBar);

        GridView grid = new GridView(this);
        grid.setNumColumns(4);
        grid.setVerticalSpacing(dpToPx(16));
        grid.setHorizontalSpacing(dpToPx(16));
        grid.setPadding(0, dpToPx(20), 0, dpToPx(16));
        grid.setClipToPadding(false);
        mAdapter = new AppGridAdapter();
        grid.setAdapter(mAdapter);
        grid.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < mFilteredApps.size()) {
                launchInWindow(mFilteredApps.get(position));
            }
        });
        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        root.addView(grid, gridLp);

        LinearLayout controlsRow = new LinearLayout(this);
        controlsRow.setOrientation(LinearLayout.HORIZONTAL);
        controlsRow.setGravity(Gravity.CENTER);
        controlsRow.setPadding(0, dpToPx(12), 0, dpToPx(8));

        Button fullBtn = createControlButton(getString(R.string.window_control_fullscreen), 0xFF1976D2, () -> {
            if (!mFilteredApps.isEmpty()) {
                launchFullscreen(mFilteredApps.get(0));
            }
        });
        controlsRow.addView(fullBtn);

        Button splitBtn = createControlButton(getString(R.string.window_control_split), 0xFF7B1FA2, () -> {
            if (!mFilteredApps.isEmpty()) {
                launchSplit(mFilteredApps.get(0));
            }
        });
        controlsRow.addView(splitBtn);

        Button closeBtn = createControlButton(getString(R.string.window_control_close), 0xFFD32F2F, this::finish);
        controlsRow.addView(closeBtn);

        root.addView(controlsRow);

        setContentView(root);
    }

    private Button createControlButton(String label, int color, Runnable action) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setTextSize(12f);
        btn.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dpToPx(16));
        btn.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                dpToPx(120), dpToPx(40));
        lp.setMargins(dpToPx(8), 0, dpToPx(8), 0);
        btn.setLayoutParams(lp);
        btn.setOnClickListener(v -> action.run());
        return btn;
    }

    private void filterApps(String query) {
        mFilteredApps.clear();
        String q = query.trim().toLowerCase(Locale.getDefault());
        if (q.isEmpty()) {
            mFilteredApps.addAll(mAllApps);
        } else {
            for (ResolveInfo info : mAllApps) {
                String label = info.loadLabel(mPackageManager).toString().toLowerCase(Locale.getDefault());
                if (label.contains(q)) {
                    mFilteredApps.add(info);
                }
            }
        }
        mAdapter.notifyDataSetChanged();
    }

    private void launchInWindow(ResolveInfo info) {
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchWindowingMode(5);
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        int winW = Math.round(w * 0.7f);
        int winH = Math.round(h * 0.7f);
        int left = (w - winW) / 2;
        int top = (h - winH) / 2;
        options.setLaunchBounds(new Rect(left, top, left + winW, top + winH));

        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setClassName(info.activityInfo.packageName, info.activityInfo.name);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        startActivity(intent, options.toBundle());
        finish();
    }

    private void launchFullscreen(ResolveInfo info) {
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchWindowingMode(1);
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setClassName(info.activityInfo.packageName, info.activityInfo.name);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent, options.toBundle());
        finish();
    }

    private void launchSplit(ResolveInfo info) {
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchWindowingMode(3);
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setClassName(info.activityInfo.packageName, info.activityInfo.name);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent, options.toBundle());
        finish();
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private final class AppGridAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return mFilteredApps.size();
        }

        @Override
        public Object getItem(int position) {
            return mFilteredApps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout itemLayout;
            ImageView iconView;
            TextView labelView;

            if (convertView == null) {
                itemLayout = new LinearLayout(AppPickerActivity.this);
                itemLayout.setOrientation(LinearLayout.VERTICAL);
                itemLayout.setGravity(Gravity.CENTER_HORIZONTAL);
                itemLayout.setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8));

                iconView = new ImageView(AppPickerActivity.this);
                LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dpToPx(56), dpToPx(56));
                iconView.setLayoutParams(iconLp);
                itemLayout.addView(iconView);

                labelView = new TextView(AppPickerActivity.this);
                labelView.setTextColor(Color.WHITE);
                labelView.setTextSize(12f);
                labelView.setGravity(Gravity.CENTER_HORIZONTAL);
                labelView.setSingleLine(true);
                labelView.setPadding(0, dpToPx(6), 0, 0);
                itemLayout.addView(labelView);
            } else {
                itemLayout = (LinearLayout) convertView;
                iconView = (ImageView) itemLayout.getChildAt(0);
                labelView = (TextView) itemLayout.getChildAt(1);
            }

            ResolveInfo info = mFilteredApps.get(position);
            iconView.setImageDrawable(info.loadIcon(mPackageManager));
            labelView.setText(info.loadLabel(mPackageManager));

            return itemLayout;
        }
    }
}
