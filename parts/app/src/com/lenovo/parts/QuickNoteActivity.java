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
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class QuickNoteActivity extends Activity {
    private static final int TOOL_PEN = 0;
    private static final int TOOL_HIGHLIGHTER = 1;
    private static final int TOOL_ERASER = 2;

    private NoteCanvas mCanvasView;
    private int mCurrentTool = TOOL_PEN;
    private Button mBtnPen;
    private Button mBtnHighlighter;
    private Button mBtnEraser;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window window = getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }

        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setBackgroundColor(0x00000000);

        mCanvasView = new NoteCanvas(this);
        FrameLayout.LayoutParams canvasLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        mCanvasView.setLayoutParams(canvasLp);
        root.addView(mCanvasView);

        LinearLayout topBar = new LinearLayout(this);
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(50));
        barLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        barLp.topMargin = dp(40);
        topBar.setLayoutParams(barLp);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(16), 0, dp(16), 0);

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int topInset = insets.getInsets(WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout()).top;
            barLp.topMargin = topInset + dp(12);
            topBar.setLayoutParams(barLp);
            return insets;
        });

        GradientDrawable barBg = new GradientDrawable();
        barBg.setShape(GradientDrawable.RECTANGLE);
        barBg.setCornerRadius(dp(25));
        barBg.setColor(0xE61E1E22);
        barBg.setStroke(dp(1), 0x33FFFFFF);
        topBar.setBackground(barBg);
        topBar.setElevation(dp(12));

        TextView title = new TextView(this);
        title.setText("Quick Note");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTextColor(0xFFFFFFFF);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.setMarginEnd(dp(12));
        topBar.addView(title, titleLp);

        mBtnPen = createToolButton("Pen", true, () -> selectTool(TOOL_PEN));
        mBtnHighlighter = createToolButton("Highlighter", false, () -> selectTool(TOOL_HIGHLIGHTER));
        mBtnEraser = createToolButton("Eraser", false, () -> selectTool(TOOL_ERASER));

        Button btnClear = createActionButton("Clear", 0xFFAAAAAA, () -> mCanvasView.clear());
        Button btnSave = createActionButton("Save", getColor(android.R.color.system_accent1_300), this::saveNote);
        Button btnClose = createActionButton("✕", 0xFFFFFFFF, this::finish);

        topBar.addView(mBtnPen);
        topBar.addView(mBtnHighlighter);
        topBar.addView(mBtnEraser);
        topBar.addView(btnClear);
        topBar.addView(btnSave);
        topBar.addView(btnClose);

        root.addView(topBar);
        setContentView(root);
    }

    private Button createToolButton(String label, boolean selected, Runnable onClick) {
        Button btn = new Button(this, null, android.R.attr.borderlessButtonStyle);
        btn.setText(label);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        btn.setAllCaps(false);
        int padH = dp(12);
        btn.setPadding(padH, 0, padH, 0);
        btn.setMinHeight(dp(40));
        btn.setMinimumHeight(dp(40));
        updateToolButtonState(btn, selected);
        btn.setOnClickListener(v -> onClick.run());
        return btn;
    }

    private void updateToolButtonState(Button btn, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(12));
        if (selected) {
            bg.setColor(getColor(android.R.color.system_accent1_600));
            btn.setTextColor(0xFFFFFFFF);
        } else {
            bg.setColor(0x00000000);
            btn.setTextColor(0xFFAAAAAA);
        }
        btn.setBackground(bg);
    }

    private Button createActionButton(String label, int textColor, Runnable onClick) {
        Button btn = new Button(this, null, android.R.attr.borderlessButtonStyle);
        btn.setText(label);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        btn.setAllCaps(false);
        btn.setTextColor(textColor);
        btn.setTypeface(null, android.graphics.Typeface.BOLD);
        int padH = dp(12);
        btn.setPadding(padH, 0, padH, 0);
        btn.setMinHeight(dp(40));
        btn.setMinimumHeight(dp(40));
        btn.setOnClickListener(v -> onClick.run());
        return btn;
    }

    private void selectTool(int tool) {
        mCurrentTool = tool;
        updateToolButtonState(mBtnPen, tool == TOOL_PEN);
        updateToolButtonState(mBtnHighlighter, tool == TOOL_HIGHLIGHTER);
        updateToolButtonState(mBtnEraser, tool == TOOL_ERASER);
        mCanvasView.setTool(tool);
    }

    private void saveNote() {
        Bitmap bitmap = mCanvasView.exportBitmap();
        if (bitmap == null) {
            Toast.makeText(this, "Empty note", Toast.LENGTH_SHORT).show();
            return;
        }

        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String fileName = "QuickNote_" + timeStamp + ".png";

        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/QuickNotes");

        ContentResolver resolver = getContentResolver();
        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

        if (uri != null) {
            try (OutputStream out = resolver.openOutputStream(uri)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                Toast.makeText(this, "Note saved to Pictures/QuickNotes", Toast.LENGTH_SHORT).show();
                finish();
            } catch (Exception e) {
                Toast.makeText(this, "Failed to save note", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    private static class NoteCanvas extends View {
        private static final PorterDuffXfermode CLEAR_MODE =
                new PorterDuffXfermode(PorterDuff.Mode.CLEAR);
        private static final PorterDuffXfermode SRC_OVER_MODE =
                new PorterDuffXfermode(PorterDuff.Mode.SRC_OVER);

        private final List<Stroke> mStrokes = new ArrayList<>();
        private Stroke mCurrentStroke;
        private int mTool = TOOL_PEN;

        private static class Stroke {
            Path path = new Path();
            int color;
            float width;
            boolean isEraser;
            boolean isHighlighter;
        }

        public NoteCanvas(Context context) {
            super(context);
            setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }

        void setTool(int tool) {
            mTool = tool;
        }

        void clear() {
            mStrokes.clear();
            mCurrentStroke = null;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            Paint paint = new Paint();
            paint.setAntiAlias(true);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);

            for (Stroke s : mStrokes) {
                drawStroke(canvas, paint, s);
            }
            if (mCurrentStroke != null) {
                drawStroke(canvas, paint, mCurrentStroke);
            }
        }

        private void drawStroke(Canvas canvas, Paint paint, Stroke s) {
            if (s.isEraser) {
                paint.setXfermode(CLEAR_MODE);
                paint.setStrokeWidth(s.width);
            } else if (s.isHighlighter) {
                paint.setXfermode(SRC_OVER_MODE);
                paint.setColor(s.color);
                paint.setStrokeWidth(s.width);
                paint.setAlpha(110);
            } else {
                paint.setXfermode(SRC_OVER_MODE);
                paint.setColor(s.color);
                paint.setStrokeWidth(s.width);
                paint.setAlpha(255);
            }
            canvas.drawPath(s.path, paint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            float x = event.getX();
            float y = event.getY();

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mCurrentStroke = new Stroke();
                    mCurrentStroke.path.moveTo(x, y);
                    if (mTool == TOOL_ERASER) {
                        mCurrentStroke.isEraser = true;
                        mCurrentStroke.width = 48f;
                    } else if (mTool == TOOL_HIGHLIGHTER) {
                        mCurrentStroke.isHighlighter = true;
                        mCurrentStroke.color = 0xFFFFD700;
                        mCurrentStroke.width = 28f;
                    } else {
                        mCurrentStroke.color = 0xFF00E5FF;
                        mCurrentStroke.width = 6f;
                    }
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (mCurrentStroke != null) {
                        mCurrentStroke.path.lineTo(x, y);
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (mCurrentStroke != null) {
                        mStrokes.add(mCurrentStroke);
                        mCurrentStroke = null;
                        invalidate();
                    }
                    return true;
            }
            return super.onTouchEvent(event);
        }

        Bitmap exportBitmap() {
            if (getWidth() <= 0 || getHeight() <= 0) return null;
            Bitmap bitmap = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            draw(canvas);
            return bitmap;
        }
    }
}
