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
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemProperties;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

public final class EdgeCalibrationActivity extends Activity {
    private static final String PROP_EDGE_GRID = "persist.sys.touch.edge_grid_zone";
    private int mMargin = 48;
    private OverlayView mOverlayView;
    private TextView mValueText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String currentVal = SystemProperties.get(PROP_EDGE_GRID, "48,48,48,48");
        try {
            String[] parts = currentVal.split(",");
            if (parts.length > 0) {
                mMargin = Integer.parseInt(parts[0].trim());
            }
        } catch (Exception ignored) {
            mMargin = 48;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x88000000);

        mOverlayView = new OverlayView(this);
        root.addView(mOverlayView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xDD1E1E22);
        panel.setPadding(dpToPx(24), dpToPx(16), dpToPx(24), dpToPx(16));

        TextView title = new TextView(this);
        title.setText(R.string.edge_calibration_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(18f);
        panel.addView(title);

        mValueText = new TextView(this);
        mValueText.setText(getString(R.string.edge_calibration_slider_label) + ": " + mMargin + " px");
        mValueText.setTextColor(0xFF00E5FF);
        mValueText.setTextSize(14f);
        mValueText.setPadding(0, dpToPx(8), 0, dpToPx(8));
        panel.addView(mValueText);

        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(120);
        seekBar.setProgress(mMargin);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                mMargin = progress;
                mValueText.setText(getString(R.string.edge_calibration_slider_label) + ": " + mMargin + " px");
                mOverlayView.invalidate();
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {}

            @Override
            public void onStopTrackingTouch(SeekBar sb) {}
        });
        panel.addView(seekBar);

        Button applyButton = new Button(this);
        applyButton.setText(R.string.edge_calibration_apply);
        applyButton.setTextColor(Color.WHITE);
        applyButton.setBackgroundColor(0xFF00838F);
        applyButton.setOnClickListener(v -> {
            String formatted = mMargin + "," + mMargin + "," + mMargin + "," + mMargin;
            SystemProperties.set(PROP_EDGE_GRID, formatted);
            Toast.makeText(this, R.string.edge_calibration_applied, Toast.LENGTH_SHORT).show();
            finish();
        });
        panel.addView(applyButton);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                dpToPx(380), FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        root.addView(panel, lp);

        setContentView(root);
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    private final class OverlayView extends View {
        private final Paint mPaint = new Paint();

        OverlayView(Context context) {
            super(context);
            mPaint.setColor(0x6600E5FF);
            mPaint.setStyle(Paint.Style.FILL);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            int m = mMargin;

            canvas.drawRect(0, 0, m, h, mPaint);
            canvas.drawRect(w - m, 0, w, h, mPaint);
            canvas.drawRect(m, 0, w - m, m, mPaint);
            canvas.drawRect(m, h - m, w - m, h, mPaint);
        }
    }
}
