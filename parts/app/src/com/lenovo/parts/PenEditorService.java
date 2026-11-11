/*
 * Copyright (C) 2026 hirero-exists <hirerokazuoa@gmail.com>
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 * Compatible with GNU General Public License, Version 2.0 (GPLv2) or later
 * pursuant to Section 3.3 of the Mozilla Public License, v. 2.0.
 */
package com.lenovo.parts;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Rect;
import android.graphics.Region;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.UserManager;
import android.view.Display;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.Toast;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PenEditorService extends Service implements PenActionRouter.Tool {
    private static PenEditorService sInstance;
    static final String MODE = "mode";
    static final int INK = 1, EASY_JOT = 2;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ExecutorService mIo = Executors.newSingleThreadExecutor();
    private PenDraftStore mDrafts;
    private AlertDialog mDialog;
    private Bitmap mPendingImage;
    private DraftGeneration mGeneration;
    private boolean mDraftError;
    private final Runnable mSaveDraft = this::saveDraft;
    private static final class DraftGeneration {
        int mode;
        String background = "";
        boolean failed, draining;
        PenDraftStore.Draft pending;
    }
    private WindowManager mWindows;
    private Editor mEditor;
    private HorizontalScrollView mBar;
    private LinearLayout mButtons;
    private Bitmap mBackground;
    private int mMode;
    private int mSession;
    private boolean mInking;
    private boolean mClosed, mExporting;
    private final BroadcastReceiver mScreen = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { close(); }
    };

    static void launch(Context context, int mode) {
        context.startForegroundService(new Intent(context, PenEditorService.class).putExtra(MODE, mode));
    }

    @Override public void onCreate() {
        super.onCreate();
        sInstance = this;
        mWindows = getSystemService(WindowManager.class);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("pen_editor", getString(R.string.pen_tools_title),
                NotificationManager.IMPORTANCE_LOW));
        startForeground(1011, new Notification.Builder(this, "pen_editor")
                .setSmallIcon(R.drawable.ic_lenovo_parts).setContentTitle(getString(R.string.pen_tools_title))
                .setOngoing(true).build());
        registerReceiver(mScreen, new IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED);
    }

    static void cycleBrush() {
        if (sInstance != null && sInstance.mEditor != null) {
            sInstance.mEditor.finishCurrent();
            int[] tools = {0, 5, 6, 1, 2};
            int current = sInstance.mEditor.mTool, next = 0;
            for (int i = 0; i < tools.length; i++) if (tools[i] == current) next = tools[(i + 1) % tools.length];
            sInstance.mEditor.mTool = next;
            sInstance.scheduleDraft();
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent == null) return START_NOT_STICKY;
        flushDraft();
        mSession++;
        clearWindows();
        mClosed = false;
        mExporting = mInking = false;
        mDraftError = false;
        mMode = intent.getIntExtra(MODE, 0);
        if (mMode != INK && mMode != EASY_JOT) { close(); return START_NOT_STICKY; }
        if (locked()) { close(); return START_NOT_STICKY; }
        if (mDrafts == null) mDrafts = new PenDraftStore(this);
        PenActionRouter.get(this).setTool(this);
        final int session = mSession;
        int mode = mMode;
        PenDraftStore.IO.execute(() -> {
            mDrafts.sweepOrphans();
            boolean incomplete = mDrafts.incomplete(mode);
            PenDraftStore.Draft draft = null; Bitmap background = null; boolean exists = mDrafts.exists(mode);
            try { if (exists) { draft = mDrafts.read(mode); if (mode == EASY_JOT) background = mDrafts.readBackground(draft); } }
            catch (Exception e) { draft = null; }
            final PenDraftStore.Draft loaded = draft; final Bitmap image = background;
            mMain.post(() -> {
                if (mClosed || session != mSession || locked()) {
                    if (image != null) image.recycle();
                    if (!mClosed && session == mSession) close();
                    return;
                }
                if (incomplete) Toast.makeText(this, R.string.pen_draft_incomplete, Toast.LENGTH_LONG).show();
                if (!exists) { beginNew(); return; }
                if (loaded == null) { showCorrupt(incomplete); return; }
                showResume(loaded, image, incomplete);
            });
        });
        return START_NOT_STICKY;
    }

    private void beginNew() {
        Choreographer frames = Choreographer.getInstance(); int session = mSession;
        frames.postFrameCallback(first -> frames.postFrameCallback(second -> {
            if (!mClosed && session == mSession) showEditor(null, null);
        }));
    }

    private void showResume(PenDraftStore.Draft draft, Bitmap image, boolean incomplete) {
        mPendingImage = image;
        String message = getString(mMode == EASY_JOT ? R.string.pen_draft_resume_jot : R.string.pen_draft_resume_ink);
        if (incomplete) message += "\n\n" + getString(R.string.pen_draft_incomplete);
        mDialog = new AlertDialog.Builder(this).setTitle(R.string.pen_draft_title)
                .setMessage(message)
                .setPositiveButton(R.string.pen_draft_resume, (d, w) -> { mPendingImage = null; showEditor(draft, image); })
                .setNegativeButton(R.string.pen_draft_start_new, (d, w) -> {
                    releasePendingImage();
                    deleteAndStart();
                }).setNeutralButton(android.R.string.cancel, (d, w) -> {
                    releasePendingImage(); close();
                }).setOnCancelListener(d -> { releasePendingImage(); close(); }).create();
        mDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        PartsOverlayWindows.prepare(mDialog.getWindow().getAttributes());
        mDialog.show();
    }

    private void deleteAndStart() {
        int mode = mMode, session = mSession;
        PenDraftStore.IO.execute(() -> {
            boolean deleted = mDrafts.delete(mode);
            if (deleted) clearDraftNotification(mode);
            mMain.post(() -> {
                if (mClosed || session != mSession) return;
                if (deleted) beginNew(); else { draftProblem(); close(); }
            });
        });
    }
    private void showCorrupt(boolean incomplete) {
        String message = getString(R.string.pen_draft_corrupt);
        if (incomplete) message += "\n\n" + getString(R.string.pen_draft_incomplete);
        mDialog = new AlertDialog.Builder(this).setTitle(R.string.pen_draft_title)
                .setMessage(message)
                .setPositiveButton(R.string.pen_draft_start_new, (d, w) -> deleteAndStart())
                .setNegativeButton(android.R.string.cancel, (d, w) -> close())
                .setOnCancelListener(d -> close()).create();
        mDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        PartsOverlayWindows.prepare(mDialog.getWindow().getAttributes());
        mDialog.show();
    }

    private void showEditor(PenDraftStore.Draft draft, Bitmap image) {
        if (locked()) { if (image != null) image.recycle(); close(); return; }
        mDialog = null;
        mGeneration = new DraftGeneration();
        DraftGeneration generation = mGeneration;
        generation.mode = mMode;
        if (mMode == EASY_JOT) {
            mBackground = draft == null ? PenCapture.screen(Display.DEFAULT_DISPLAY) : image;
            if (mBackground == null) { error(); return; }
            if (draft != null) generation.background = draft.background;
            else {
                Bitmap captured = mBackground; int session = mSession;
                PenDraftStore.IO.execute(() -> {
                    try { generation.background = mDrafts.writeBackground(captured); }
                    catch (Exception e) { generation.failed = true; mMain.post(() -> {
                        if (!mClosed && session == mSession) draftProblem();
                    }); }
                });
            }
        }
        mEditor = new Editor();
        if (draft != null) mEditor.restore(draft);
        WindowManager.LayoutParams scene = new WindowManager.LayoutParams(-1, -1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | (mMode == INK ? WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : 0), PixelFormat.TRANSLUCENT);
        scene.gravity = Gravity.TOP | Gravity.LEFT;
        scene.setFitInsetsTypes(0);
        mWindows.addView(mEditor, PartsOverlayWindows.prepare(scene));
        mBar = new HorizontalScrollView(this);
        mButtons = new LinearLayout(this);
        mButtons.setBackgroundColor(0xee202024);
        mBar.addView(mButtons);
        button(R.string.pen_brush, () -> { mEditor.mTool = 0; scheduleDraft(); });
        button(R.string.pen_pencil, () -> { mEditor.mTool = 5; scheduleDraft(); });
        button(R.string.pen_fountain, () -> { mEditor.mTool = 6; scheduleDraft(); });
        button(R.string.pen_marker, () -> { mEditor.mTool = 1; scheduleDraft(); });
        button(R.string.pen_eraser, () -> { mEditor.mTool = 2; scheduleDraft(); });
        button(R.string.pen_color, () -> {
            int[] palette = {0xffe53935, 0xff1565c0, 0xff43a047, 0xff000000, 0xffffffff, 0xffffb300};
            mEditor.mPalette = (mEditor.mPalette + 1) % palette.length;
            mEditor.mColor = palette[mEditor.mPalette];
            scheduleDraft();
            Toast.makeText(this, getString(R.string.pen_color_value, mEditor.mPalette + 1), Toast.LENGTH_SHORT).show();
        });
        button(R.string.pen_width, () -> {
            mEditor.mWidth = mEditor.mWidth >= 16 ? 2 : mEditor.mWidth * 2;
            scheduleDraft();
            Toast.makeText(this, getString(R.string.pen_width_value, (int) mEditor.mWidth), Toast.LENGTH_SHORT).show();
        });
        button(R.string.pen_select, () -> { mEditor.mTool = 3; scheduleDraft(); });
        button(R.string.pen_lasso, () -> { mEditor.mTool = 4; scheduleDraft(); });
        button(R.string.pen_delete_selection, () -> mEditor.selection(false));
        button(R.string.pen_duplicate_selection, () -> mEditor.selection(true));
        button(R.string.pen_undo, () -> mEditor.history(false));
        button(R.string.pen_redo, () -> mEditor.history(true));
        button(R.string.pen_save_image, () -> export(false, false));
        button(R.string.pen_save_pdf, () -> export(true, false));
        button(R.string.pen_share_image, () -> export(false, true));
        button(R.string.pen_share_pdf, () -> export(true, true));
        button(R.string.pen_draft_discard, this::confirmDiscard);
        button(R.string.pen_close_tool, this::close);
        WindowManager.LayoutParams bar = new WindowManager.LayoutParams(
                Math.min(getResources().getDisplayMetrics().widthPixels - dp(24), dp(1100)), -2,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        bar.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        bar.y = dp(40);
        mWindows.addView(mBar, PartsOverlayWindows.prepare(bar));
        if (draft == null) mEditor.post(this::scheduleDraft);
    }

    private void button(int title, Runnable action) {
        Button button = new Button(this);
        button.setText(title);
        button.setOnClickListener(v -> { mEditor.finishCurrent(); action.run(); });
        mButtons.addView(button);
    }

    @Override public boolean onStylus(PenStylusState state) {
        if (mClosed || mEditor == null || mMode != INK || state.displayId != Display.DEFAULT_DISPLAY) return false;
        int[] position = new int[2];
        mBar.getLocationOnScreen(position);
        if (!mInking && state.x >= position[0] && state.x <= position[0] + mBar.getWidth()
                && state.y >= position[1] && state.y <= position[1] + mBar.getHeight()) return false;
        if (state.action == MotionEvent.ACTION_DOWN) mInking = true;
        if (!mInking) return false;
        mEditor.stroke(state.action, state.x, state.y, state.pressure,
                state.tool == MotionEvent.TOOL_TYPE_ERASER);
        if (state.action == MotionEvent.ACTION_UP || state.action == MotionEvent.ACTION_CANCEL) mInking = false;
        return true;
    }

    private void export(boolean pdf, boolean share) {
        if (mExporting || mClosed) return;
        final int session = mSession;
        final Editor editor = mEditor;
        mExporting = true;
        mBar.setVisibility(View.INVISIBLE);
        mEditor.mShowSelection = false;
        mEditor.invalidate();
        editor.postOnAnimation(() -> editor.postOnAnimation(() -> {
            if (mClosed || session != mSession) return;
            Bitmap bitmap = mMode == INK ? PenCapture.screen(Display.DEFAULT_DISPLAY) : mEditor.bitmap();
            mBar.setVisibility(View.VISIBLE);
            mEditor.mShowSelection = true;
            mEditor.invalidate();
            if (bitmap == null) { mExporting = false; error(); return; }
            mIo.execute(() -> {
                try {
                    Uri uri = PenCapture.save(this, bitmap, pdf);
                    mMain.post(() -> {
                        if (mClosed || session != mSession) return;
                        mExporting = false;
                        if (share) PenCapture.share(this, uri, pdf);
                        else Toast.makeText(this, R.string.quick_note_saved, Toast.LENGTH_SHORT).show();
                    });
                } catch (Exception e) { mMain.post(() -> {
                    if (!mClosed && session == mSession) { mExporting = false; error(); }
                }); }
                finally { bitmap.recycle(); }
            });
        }));
    }

    private void error() {
        Toast.makeText(this, R.string.pen_capture_failed, Toast.LENGTH_LONG).show();
        if (mEditor == null) close();
    }
    @Override public void close() {
        flushDraft();
        mClosed = true;
        mSession++;
        clearWindows();
        PenActionRouter.get(this).releaseTool(this);
        stopSelf();
    }
    private void clearWindows() {
        if (mDialog != null) { mDialog.dismiss(); mDialog = null; }
        releasePendingImage();
        if (mEditor != null && mEditor.isAttachedToWindow()) mWindows.removeViewImmediate(mEditor);
        if (mBar != null && mBar.isAttachedToWindow()) mWindows.removeViewImmediate(mBar);
        mEditor = null;
        mBar = null;
        if (mBackground != null) { Bitmap image = mBackground; PenDraftStore.IO.execute(image::recycle); }
        mBackground = null;
        mGeneration = null;
    }
    @Override public void onConfigurationChanged(Configuration c) { super.onConfigurationChanged(c); close(); }
    @Override public void onDestroy() {
        flushDraft();
        if (sInstance == this) sInstance = null;
        mClosed = true;
        PenActionRouter.get(this).releaseTool(this);
        unregisterReceiver(mScreen);
        clearWindows();
        mMain.removeCallbacksAndMessages(null);
        mIo.shutdown();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private boolean locked() {
        UserManager users = getSystemService(UserManager.class);
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        return users == null || !users.isUserUnlocked() || (keyguard != null && keyguard.isKeyguardLocked());
    }

    private void releasePendingImage() {
        if (mPendingImage != null) mPendingImage.recycle();
        mPendingImage = null;
    }
    private void draftProblem() {
        if (!mDraftError) { mDraftError = true; Toast.makeText(this, R.string.pen_draft_failed, Toast.LENGTH_LONG).show(); }
    }
    private void scheduleDraft() {
        mMain.removeCallbacks(mSaveDraft);
        mMain.postDelayed(mSaveDraft, 400);
    }
    private void flushDraft() {
        mMain.removeCallbacks(mSaveDraft);
        if (mEditor != null) saveDraft();
    }
    private void saveDraft() {
        if (mEditor == null || mGeneration == null) return;
        PenDraftStore.Draft draft = mEditor.snapshot();
        if (draft == null) {
            int mode = mGeneration.mode;
            PenDraftStore.IO.execute(() -> {
                try { mDrafts.markIncomplete(mode); } catch (Exception ignored) { }
                postDraftFailure(mode);
            });
            draftProblem(); return;
        }
        DraftGeneration generation = mGeneration; int session = mSession;
        synchronized (generation) {
            generation.pending = draft;
            if (generation.draining) return;
            generation.draining = true;
        }
        PenDraftStore.IO.execute(() -> drainDrafts(generation, session));
    }
    private void drainDrafts(DraftGeneration generation, int session) {
        try {
            while (true) {
                PenDraftStore.Draft draft;
                synchronized (generation) {
                    draft = generation.pending;
                    generation.pending = null;
                    if (draft == null) { generation.draining = false; return; }
                }
                try {
                    mDrafts.markIncomplete(draft.mode);
                    if (generation.failed) throw new java.io.IOException("Background unavailable");
                    draft.background = generation.background;
                    mDrafts.write(draft);
                    mDrafts.clearIncomplete(draft.mode);
                    clearDraftNotification(draft.mode);
                } catch (Exception e) {
                    postDraftFailure(draft.mode);
                    mMain.post(() -> { if (!mClosed && session == mSession) draftProblem(); });
                }
            }
        } catch (Throwable failure) {
            synchronized (generation) { generation.pending = null; generation.draining = false; }
            try { mDrafts.markIncomplete(generation.mode); } catch (Exception ignored) { }
            postDraftFailure(generation.mode);
            mMain.post(() -> { if (!mClosed && session == mSession) draftProblem(); });
        }
    }
    private void postDraftFailure(int mode) {
        Intent intent = new Intent(this, PenEditorService.class).putExtra(MODE, mode);
        PendingIntent reopen = PendingIntent.getForegroundService(this, mode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, "pen_editor")
                .setSmallIcon(R.drawable.ic_lenovo_parts).setContentTitle(getString(R.string.pen_draft_failure_title))
                .setContentText(getString(R.string.pen_draft_failure_notification))
                .setStyle(new Notification.BigTextStyle().bigText(getString(R.string.pen_draft_failure_notification)))
                .setContentIntent(reopen).setOngoing(true).build();
        try { getSystemService(NotificationManager.class).notify(1100 + mode, notification); }
        catch (SecurityException ignored) { }
    }
    private void clearDraftNotification(int mode) {
        getSystemService(NotificationManager.class).cancel(1100 + mode);
    }
    private void confirmDiscard() {
        mDialog = new AlertDialog.Builder(this).setTitle(R.string.pen_draft_discard)
                .setMessage(R.string.pen_draft_discard_message)
                .setPositiveButton(R.string.pen_draft_discard, (d, w) -> {
                    mMain.removeCallbacks(mSaveDraft);
                    if (mGeneration != null) synchronized (mGeneration) { mGeneration.pending = null; }
                    int mode = mMode;
                    if (mEditor != null && mEditor.isAttachedToWindow()) mWindows.removeViewImmediate(mEditor);
                    mEditor = null;
                    PenDraftStore.IO.execute(() -> {
                        if (mDrafts.delete(mode)) clearDraftNotification(mode);
                        else { postDraftFailure(mode); mMain.post(() -> Toast.makeText(this,
                                R.string.pen_draft_failed, Toast.LENGTH_LONG).show()); }
                    });
                    close();
                }).setNegativeButton(android.R.string.cancel, null).create();
        mDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        PartsOverlayWindows.prepare(mDialog.getWindow().getAttributes());
        mDialog.show();
    }

    private static final class Stroke {
        final Path path;
        final int color;
        final float width;
        final boolean eraser;
        final int style;
        final ArrayList<float[]> samples = new ArrayList<>();
        Stroke(Path path, int color, float width, boolean eraser, int style) {
            this.path = path; this.color = color; this.width = width; this.eraser = eraser; this.style = style;
        }
        Stroke copy() {
            Stroke result = new Stroke(new Path(path), color, width, eraser, style);
            for (float[] sample : samples) result.samples.add(sample.clone());
            return result;
        }
        void offset(float x, float y) {
            path.offset(x, y);
            for (float[] sample : samples) { sample[0] += x; sample[1] += y; }
        }
    }

    private final class Editor extends View {
        private List<Stroke> mStrokes = new ArrayList<>();
        private final ArrayDeque<List<Stroke>> mUndo = new ArrayDeque<>(), mRedo = new ArrayDeque<>();
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mSelection = new RectF();
        private final Path mLasso = new Path();
        private final Region mRegion = new Region();
        private boolean mLassoSelection, mMoving;
        private boolean mShowSelection = true;
        private Stroke mCurrent;
        private float mDownX, mDownY;
        private int mTool, mPalette, mColor = 0xffe53935;
        private float mWidth = 4;
        private int mModelWidth, mModelHeight;
        private int mSampleCount;
        private boolean mLimitNotified;

        Editor() { super(PenEditorService.this); }
        void finishCurrent() {
            if (mCurrent != null) {
                mSampleCount += mCurrent.samples.size(); mStrokes.add(mCurrent);
                mCurrent = null; scheduleDraft(); invalidate();
            }
        }
        private void dimensions() {
            if (mModelWidth == 0 && getWidth() > 0 && getHeight() > 0) {
                mModelWidth = mBackground != null ? mBackground.getWidth() : getWidth();
                mModelHeight = mBackground != null ? mBackground.getHeight() : getHeight();
            }
        }
        private float scale() { dimensions(); return Math.min((float) getWidth() / mModelWidth, (float) getHeight() / mModelHeight); }
        private float left(float scale) { return (getWidth() - mModelWidth * scale) / 2; }
        private float top(float scale) { return (getHeight() - mModelHeight * scale) / 2; }
        private int samples(List<Stroke> strokes) {
            int total = 0; for (Stroke s : strokes) total += s.samples.size(); return total;
        }
        PenDraftStore.Draft snapshot() {
            dimensions();
            if (mModelWidth < 1 || mModelHeight < 1) return null;
            PenDraftStore.Draft d = new PenDraftStore.Draft();
            d.mode = mMode; d.width = mModelWidth; d.height = mModelHeight;
            d.timestamp = System.currentTimeMillis(); d.tool = mTool; d.palette = mPalette;
            d.color = mColor; d.brushWidth = mWidth;
            List<Stroke> all = new ArrayList<>(mStrokes); if (mCurrent != null) all.add(mCurrent);
            if (all.size() > PenDraftStore.MAX_STROKES || samples(all) > PenDraftStore.MAX_SAMPLES) return null;
            for (Stroke s : all) {
                PenDraftStore.Stroke item = new PenDraftStore.Stroke();
                item.color = s.color; item.width = s.width; item.eraser = s.eraser; item.style = s.style;
                for (float[] p : s.samples) item.samples.add(p.clone());
                d.strokes.add(item);
            }
            return d;
        }
        void restore(PenDraftStore.Draft d) {
            mModelWidth = d.width; mModelHeight = d.height; mTool = d.tool;
            mPalette = d.palette; mColor = d.color; mWidth = d.brushWidth;
            for (PenDraftStore.Stroke item : d.strokes) {
                Path path = new Path(); float[] first = item.samples.get(0);
                path.moveTo(first[0], first[1]); path.lineTo(first[0] + 0.1f, first[1]);
                Stroke s = new Stroke(path, item.color, item.width, item.eraser, item.style);
                for (float[] p : item.samples) { s.samples.add(p.clone()); path.lineTo(p[0], p[1]); }
                mStrokes.add(s);
            }
            mSampleCount = samples(mStrokes);
        }
        private List<Stroke> copy() {
            List<Stroke> result = new ArrayList<>();
            for (Stroke stroke : mStrokes) result.add(stroke.copy());
            return result;
        }
        private void checkpoint() {
            int current = samples(mStrokes), total = current;
            for (List<Stroke> entry : mUndo) total += samples(entry);
            while (!mUndo.isEmpty() && (mUndo.size() >= 16 || total > 200000)) {
                total -= samples(mUndo.removeFirst());
            }
            if (current <= 200000) mUndo.addLast(copy());
            mRedo.clear();
        }
        void history(boolean redo) {
            ArrayDeque<List<Stroke>> source = redo ? mRedo : mUndo;
            ArrayDeque<List<Stroke>> destination = redo ? mUndo : mRedo;
            if (source.isEmpty()) return;
            if (samples(mStrokes) <= 200000) {
                int total = samples(mStrokes);
                for (List<Stroke> entry : destination) total += samples(entry);
                while (!destination.isEmpty() && (destination.size() >= 16 || total > 200000))
                    total -= samples(destination.removeFirst());
                destination.addLast(copy());
            }
            mStrokes = source.removeLast();
            mSampleCount = samples(mStrokes);
            mCurrent = null;
            mSelection.setEmpty(); mLasso.reset(); mRegion.setEmpty();
            invalidate();
            scheduleDraft();
        }
        void selection(boolean duplicate) {
            if (mSelection.isEmpty()) return;
            if (duplicate) {
                int addedStrokes = 0, addedSamples = 0;
                for (Stroke s : mStrokes) if (selected(s)) { addedStrokes++; addedSamples += s.samples.size(); }
                if (mStrokes.size() + addedStrokes > PenDraftStore.MAX_STROKES
                        || samples(mStrokes) + addedSamples > PenDraftStore.MAX_SAMPLES) { draftProblem(); return; }
            }
            checkpoint();
            List<Stroke> result = new ArrayList<>();
            for (Stroke stroke : mStrokes) {
                boolean selected = selected(stroke);
                if (duplicate || !selected) result.add(stroke);
                if (duplicate && selected) {
                    Stroke clone = stroke.copy(); clone.offset(dp(20), dp(20)); result.add(clone);
                }
            }
            mStrokes = result;
            mSampleCount = samples(mStrokes);
            mSelection.setEmpty();
            mLasso.reset();
            mRegion.setEmpty();
            invalidate();
            scheduleDraft();
        }
        private boolean selected(Stroke stroke) {
            RectF bounds = new RectF();
            stroke.path.computeBounds(bounds, true);
            bounds.inset(-stroke.width / 2, -stroke.width / 2);
            return mLassoSelection ? mRegion.contains((int) bounds.centerX(), (int) bounds.centerY())
                    : RectF.intersects(bounds, mSelection);
        }
        private boolean inside(float x, float y) {
            return mLassoSelection ? mRegion.contains((int) x, (int) y) : mSelection.contains(x, y);
        }
        void stroke(int action, float x, float y, float pressure, boolean eraser) {
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(pressure)) return;
            dimensions();
            if (mModelWidth < 1 || mModelHeight < 1) return;
            float scale = scale(); if (scale <= 0) return;
            x = (x - left(scale)) / scale; y = (y - top(scale)) / scale;
            pressure = Math.max(0, Math.min(1, pressure));
            if (action == MotionEvent.ACTION_DOWN) {
                mDownX = x; mDownY = y;
                if (mTool == 3 || mTool == 4) {
                    mMoving = !mSelection.isEmpty() && inside(x, y);
                    if (mMoving) checkpoint();
                    else {
                        mSelection.set(x, y, x, y);
                        mLasso.reset(); mLasso.moveTo(x, y);
                        mLassoSelection = mTool == 4;
                        mRegion.setEmpty();
                    }
                }
                else {
                    if (mStrokes.size() >= PenDraftStore.MAX_STROKES || mSampleCount >= PenDraftStore.MAX_SAMPLES) {
                        if (!mLimitNotified) { mLimitNotified = true; draftProblem(); }
                        return;
                    }
                    checkpoint();
                    Path path = new Path(); path.moveTo(x, y); path.lineTo(x + 0.1f, y);
                    int color = mTool == 1 ? (mColor & 0x00ffffff) | 0x70000000 : mColor;
                    mCurrent = new Stroke(path, color, dp((int) mWidth)
                            * (mTool == 1 ? 4 : 1), eraser || mTool == 2, mTool);
                    mCurrent.samples.add(new float[] {x, y, pressure});
                }
            } else if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) {
                if (mTool == 3 || mTool == 4) {
                    if (mMoving) {
                        float dx = x - mDownX, dy = y - mDownY;
                        for (Stroke stroke : mStrokes) if (selected(stroke)) stroke.offset(dx, dy);
                        mSelection.offset(dx, dy); mLasso.offset(dx, dy);
                        if (mLassoSelection) mRegion.setPath(mLasso, new Region(0, 0, mModelWidth, mModelHeight));
                        mDownX = x; mDownY = y;
                    } else if (mLassoSelection) {
                        mLasso.lineTo(x, y);
                        if (action == MotionEvent.ACTION_UP) mLasso.close();
                        mLasso.computeBounds(mSelection, true);
                        mRegion.setPath(mLasso, new Region(0, 0, mModelWidth, mModelHeight));
                    } else mSelection.set(Math.min(mDownX, x), Math.min(mDownY, y),
                            Math.max(mDownX, x), Math.max(mDownY, y));
                    if (action == MotionEvent.ACTION_UP) { mMoving = false; scheduleDraft(); }
                }
                else if (mCurrent != null) {
                    if (mCurrent.samples.size() + mSampleCount >= PenDraftStore.MAX_SAMPLES) {
                        finishCurrent();
                        if (!mLimitNotified) { mLimitNotified = true; draftProblem(); }
                        invalidate(); return;
                    }
                    mCurrent.path.lineTo(x, y);
                    mCurrent.samples.add(new float[] {x, y, pressure});
                    if (action == MotionEvent.ACTION_UP) finishCurrent();
                }
            } else if (action == MotionEvent.ACTION_CANCEL && mCurrent != null) {
                finishCurrent();
            }
            invalidate();
        }
        private void render(Canvas canvas, boolean selection) {
            dimensions(); if (mModelWidth < 1 || mModelHeight < 1) return;
            int transform = canvas.save(); float scale = scale();
            canvas.translate(left(scale), top(scale)); canvas.scale(scale, scale);
            if (mBackground != null) canvas.drawBitmap(mBackground, 0, 0, null);
            int layer = canvas.saveLayer(0, 0, mModelWidth, mModelHeight, null);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeCap(Paint.Cap.ROUND);
            mPaint.setStrokeJoin(Paint.Join.ROUND);
            for (Stroke stroke : mStrokes) draw(canvas, stroke);
            if (mCurrent != null) draw(canvas, mCurrent);
            canvas.restoreToCount(layer);
            mPaint.setXfermode(null);
            if (selection && !mSelection.isEmpty()) {
                mPaint.setColor(0xff00bfff); mPaint.setStrokeWidth(dp(2));
                if (mLassoSelection) canvas.drawPath(mLasso, mPaint);
                else canvas.drawRect(mSelection, mPaint);
            }
            canvas.restoreToCount(transform);
        }
        private void draw(Canvas canvas, Stroke stroke) {
            mPaint.setColor(stroke.color);
            mPaint.setXfermode(stroke.eraser ? new PorterDuffXfermode(PorterDuff.Mode.CLEAR) : null);
            if (stroke.samples.isEmpty()) return;
            float[] previous = stroke.samples.get(0);
            for (float[] sample : stroke.samples) {
                float pressure = Math.max(0, Math.min(1, sample[2]));
                float scale = stroke.style == 1 ? 1 : stroke.style == 6 ? 0.3f + pressure * 1.7f
                        : stroke.style == 5 ? 0.2f + pressure * 0.8f : 0.3f + pressure * 0.7f;
                mPaint.setStrokeWidth(stroke.eraser ? dp(32) : stroke.width * scale);
                if (stroke.style == 5) mPaint.setAlpha((int) (80 + pressure * 175));
                canvas.drawLine(previous[0], previous[1], sample[0] + 0.1f, sample[1], mPaint);
                previous = sample;
            }
            mPaint.setAlpha(255);
        }
        @Override protected void onDraw(Canvas canvas) { render(canvas, mShowSelection); }
        Bitmap bitmap() {
            if (getWidth() < 1 || getHeight() < 1) return null;
            Bitmap bitmap = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
            render(new Canvas(bitmap), false);
            return bitmap;
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            stroke(event.getActionMasked(), event.getX(), event.getY(), event.getPressure(),
                    event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER);
            return true;
        }
    }
}
