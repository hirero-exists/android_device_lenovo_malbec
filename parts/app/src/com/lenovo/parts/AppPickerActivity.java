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
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.input.InputManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
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

    private int mColorSurface = 0xFF14151A;
    private int mColorText = 0xFFFFFFFF;
    private int mColorAccent = 0xFF7C4DFF;

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

        resolveThemeColors();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable rootBg = new GradientDrawable();
        rootBg.setColor(applyAlpha(mColorSurface, 245));
        rootBg.setCornerRadius(dpToPx(24));
        rootBg.setStroke(dpToPx(1), applyAlpha(mColorAccent, 80));
        root.setBackground(rootBg);
        root.setPadding(dpToPx(20), dpToPx(18), dpToPx(20), dpToPx(14));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dpToPx(4), 0, dpToPx(4), dpToPx(12));

        TextView title = new TextView(this);
        title.setText(R.string.app_picker_title);
        title.setTextColor(mColorAccent);
        title.setTextSize(18f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        header.addView(title);

        TextView closeHeaderBtn = new TextView(this);
        closeHeaderBtn.setText("✕");
        closeHeaderBtn.setTextSize(15f);
        closeHeaderBtn.setTextColor(0xFFCCCCCC);
        closeHeaderBtn.setPadding(dpToPx(10), dpToPx(4), dpToPx(10), dpToPx(4));
        closeHeaderBtn.setOnClickListener(v -> finish());
        header.addView(closeHeaderBtn);
        root.addView(header);

        EditText searchBar = new EditText(this);
        searchBar.setHint(R.string.app_drawer_search_hint);
        searchBar.setHintTextColor(applyAlpha(mColorText, 100));
        searchBar.setTextColor(mColorText);
        searchBar.setTextSize(14f);
        searchBar.setSingleLine(true);
        GradientDrawable searchBg = new GradientDrawable();
        searchBg.setColor(applyAlpha(mColorText, 25));
        searchBg.setCornerRadius(dpToPx(20));
        searchBar.setBackground(searchBg);
        searchBar.setPadding(dpToPx(16), dpToPx(10), dpToPx(16), dpToPx(10));
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
        grid.setVerticalSpacing(dpToPx(14));
        grid.setHorizontalSpacing(dpToPx(14));
        grid.setPadding(0, dpToPx(16), 0, dpToPx(8));
        grid.setClipToPadding(false);
        mAdapter = new AppGridAdapter();
        grid.setAdapter(mAdapter);
        grid.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < mFilteredApps.size()) {
                ResolveInfo selected = mFilteredApps.get(position);
                launchInWindow(selected);
            }
        });
        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        root.addView(grid, gridLp);

        setContentView(root);
    }

    private void resolveThemeColors() {
        try {
            mColorAccent = getColor(android.R.color.system_accent1_500);
        } catch (Exception e) {
            mColorAccent = 0xFF7C4DFF;
        }
        try {
            mColorSurface = getColor(android.R.color.system_neutral1_900);
        } catch (Exception e) {
            mColorSurface = 0xFF14151A;
        }
        mColorText = 0xFFFFFFFF;
    }

    private int applyAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
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
        if (info.activityInfo == null) return;
        try {
            Settings.Global.putInt(getContentResolver(), "enable_freeform_support", 1);
            Settings.Global.putInt(getContentResolver(), "development_enable_freeform_windows_support", 1);
            Settings.Global.putInt(getContentResolver(), "force_resizable_activities", 1);
        } catch (Exception ignored) {
        }

        Rect bounds = defaultWindowBounds();
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchWindowingMode(5);
        options.setLaunchBounds(bounds);

        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setClassName(info.activityInfo.packageName, info.activityInfo.name);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        startActivity(intent, options.toBundle());
        finish();
    }

    private Rect defaultWindowBounds() {
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        int winW = Math.round(w * 0.72f);
        int winH = Math.round(h * 0.72f);
        int left = (w - winW) / 2;
        int top = (h - winH) / 3;
        return new Rect(left, top, left + winW, top + winH);
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
                itemLayout.setPadding(dpToPx(6), dpToPx(6), dpToPx(6), dpToPx(6));

                iconView = new ImageView(AppPickerActivity.this);
                LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                        dpToPx(52), dpToPx(52));
                iconView.setLayoutParams(iconLp);
                itemLayout.addView(iconView);

                labelView = new TextView(AppPickerActivity.this);
                labelView.setTextColor(applyAlpha(mColorText, 220));
                labelView.setTextSize(11.5f);
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
