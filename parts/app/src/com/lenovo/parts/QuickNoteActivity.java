package com.lenovo.parts;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;
import android.window.ScreenCaptureInternal;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class QuickNoteActivity extends Activity {
    private static final String TAG = "QuickNoteActivity";

    private static final int TOOL_PEN = 0;
    private static final int TOOL_HIGHLIGHTER = 1;
    private static final int TOOL_ERASER = 2;

    private NoteCanvas mCanvasView;
    private int mCurrentTool = TOOL_PEN;
    private ImageView mBtnPen;
    private ImageView mBtnHighlighter;
    private ImageView mBtnEraser;
    private ImageView mBtnBg;

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

        Bitmap screenCapture = captureScreen();

        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setBackgroundColor(0x00000000);

        mCanvasView = new NoteCanvas(this);
        mCanvasView.setBackgroundBitmap(screenCapture);
        FrameLayout.LayoutParams canvasLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        mCanvasView.setLayoutParams(canvasLp);
        root.addView(mCanvasView);

        LinearLayout topBar = new LinearLayout(this);
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        barLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        barLp.topMargin = dp(40);
        topBar.setLayoutParams(barLp);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(8), dp(4), dp(8), dp(4));

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int topInset = insets.getInsets(WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout()).top;
            barLp.topMargin = topInset + dp(12);
            topBar.setLayoutParams(barLp);
            return insets;
        });

        GradientDrawable barBg = new GradientDrawable();
        barBg.setShape(GradientDrawable.RECTANGLE);
        barBg.setCornerRadius(dp(22));
        barBg.setColor(0xE61E1E22);
        barBg.setStroke(dp(1), 0x33FFFFFF);
        topBar.setBackground(barBg);
        topBar.setElevation(dp(12));

        mBtnPen = createToolChip(R.drawable.ic_tool_pen, true, () -> selectTool(TOOL_PEN));
        mBtnHighlighter = createToolChip(R.drawable.ic_tool_highlighter, false, () -> selectTool(TOOL_HIGHLIGHTER));
        mBtnEraser = createToolChip(R.drawable.ic_tool_eraser, false, () -> selectTool(TOOL_ERASER));

        mBtnBg = createActionChip(R.drawable.ic_tool_bg, () -> {
            boolean showingBg = mCanvasView.toggleBackground();
            mBtnBg.setAlpha(showingBg ? 1.0f : 0.45f);
            Toast.makeText(this, showingBg ? R.string.quick_note_background_screen : R.string.quick_note_background_canvas, Toast.LENGTH_SHORT).show();
        });
        if (screenCapture == null) {
            mBtnBg.setVisibility(View.GONE);
        }

        ImageView btnUndo = createActionChip(R.drawable.ic_tool_undo, () -> mCanvasView.undo());
        ImageView btnClear = createActionChip(R.drawable.ic_tool_clear, () -> mCanvasView.clear());
        ImageView btnSave = createActionChip(R.drawable.ic_tool_save, this::saveNote);
        ImageView btnClose = createActionChip(R.drawable.ic_tool_close, this::finish);

        topBar.addView(mBtnPen);
        topBar.addView(mBtnHighlighter);
        topBar.addView(mBtnEraser);

        View divider1 = createDivider();
        topBar.addView(divider1);

        topBar.addView(mBtnBg);
        topBar.addView(btnUndo);
        topBar.addView(btnClear);

        View divider2 = createDivider();
        topBar.addView(divider2);

        topBar.addView(btnSave);
        topBar.addView(btnClose);

        root.addView(topBar);
        setContentView(root);
    }

    private View createDivider() {
        View v = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(1), dp(20));
        lp.setMargins(dp(6), 0, dp(6), 0);
        v.setLayoutParams(lp);
        v.setBackgroundColor(0x33FFFFFF);
        return v;
    }

    private ImageView createToolChip(int resId, boolean selected, Runnable onClick) {
        ImageView iv = new ImageView(this);
        int size = dp(36);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMargins(dp(2), 0, dp(2), 0);
        iv.setLayoutParams(lp);
        iv.setImageResource(resId);
        int pad = dp(8);
        iv.setPadding(pad, pad, pad, pad);
        updateToolChipState(iv, selected);
        iv.setOnClickListener(v -> onClick.run());
        return iv;
    }

    private void updateToolChipState(ImageView iv, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(18));
        if (selected) {
            bg.setColor(getColor(android.R.color.system_accent1_600));
            iv.setColorFilter(0xFFFFFFFF);
        } else {
            bg.setColor(0x00000000);
            iv.setColorFilter(0xFFAAAAAA);
        }
        iv.setBackground(bg);
    }

    private ImageView createActionChip(int resId, Runnable onClick) {
        ImageView iv = new ImageView(this);
        int size = dp(36);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMargins(dp(2), 0, dp(2), 0);
        iv.setLayoutParams(lp);
        iv.setImageResource(resId);
        iv.setColorFilter(0xFFE0E0E0);
        int pad = dp(8);
        iv.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(18));
        bg.setColor(0x00000000);
        iv.setBackground(bg);
        iv.setOnClickListener(v -> onClick.run());
        return iv;
    }

    private void selectTool(int tool) {
        mCurrentTool = tool;
        updateToolChipState(mBtnPen, tool == TOOL_PEN);
        updateToolChipState(mBtnHighlighter, tool == TOOL_HIGHLIGHTER);
        updateToolChipState(mBtnEraser, tool == TOOL_ERASER);
        mCanvasView.setTool(tool);
    }

    private Bitmap captureScreen() {
        try {
            ScreenCaptureInternal.SynchronousScreenCaptureListener syncScreenCapture =
                    ScreenCaptureInternal.createSyncCaptureListener();
            WindowManagerGlobal.getWindowManagerService().captureDisplay(
                    Display.DEFAULT_DISPLAY, null, syncScreenCapture);
            ScreenCaptureInternal.ScreenshotHardwareBuffer buffer = syncScreenCapture.getBuffer();
            if (buffer != null) {
                Bitmap b = buffer.asBitmap();
                if (b != null) {
                    return b.copy(Bitmap.Config.ARGB_8888, false);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "captureScreen error", t);
        }
        return null;
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
                Toast.makeText(this, R.string.quick_note_saved, Toast.LENGTH_SHORT).show();
                finish();
            } catch (Exception e) {
                Toast.makeText(this, R.string.quick_note_save_error, Toast.LENGTH_SHORT).show();
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
        private Bitmap mBackgroundBitmap;
        private boolean mShowBackground = true;

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

        void setBackgroundBitmap(Bitmap bitmap) {
            mBackgroundBitmap = bitmap;
            mShowBackground = bitmap != null;
            invalidate();
        }

        boolean toggleBackground() {
            if (mBackgroundBitmap == null) {
                return false;
            }
            mShowBackground = !mShowBackground;
            invalidate();
            return mShowBackground;
        }

        void setTool(int tool) {
            mTool = tool;
        }

        void undo() {
            if (!mStrokes.isEmpty()) {
                mStrokes.remove(mStrokes.size() - 1);
                invalidate();
            }
        }

        void clear() {
            mStrokes.clear();
            mCurrentStroke = null;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            int width = getWidth();
            int height = getHeight();
            if (width <= 0 || height <= 0) {
                return;
            }

            if (mBackgroundBitmap != null && mShowBackground) {
                Rect src = new Rect(0, 0, mBackgroundBitmap.getWidth(), mBackgroundBitmap.getHeight());
                Rect dst = new Rect(0, 0, width, height);
                canvas.drawBitmap(mBackgroundBitmap, src, dst, null);
            } else {
                canvas.drawColor(0xE6141416);
            }

            int layer = canvas.saveLayer(0, 0, width, height, null);

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

            canvas.restoreToCount(layer);
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
            int width = getWidth();
            int height = getHeight();
            if (width <= 0 || height <= 0) {
                return null;
            }
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);

            if (mBackgroundBitmap != null && mShowBackground) {
                Rect src = new Rect(0, 0, mBackgroundBitmap.getWidth(), mBackgroundBitmap.getHeight());
                Rect dst = new Rect(0, 0, width, height);
                canvas.drawBitmap(mBackgroundBitmap, src, dst, null);
            } else {
                canvas.drawColor(0xFF141416);
            }

            int layer = canvas.saveLayer(0, 0, width, height, null);

            Paint paint = new Paint();
            paint.setAntiAlias(true);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);

            for (Stroke s : mStrokes) {
                drawStroke(canvas, paint, s);
            }

            canvas.restoreToCount(layer);
            return bitmap;
        }
    }
}
