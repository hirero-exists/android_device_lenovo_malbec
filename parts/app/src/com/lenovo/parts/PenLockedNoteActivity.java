/*
 * Copyright (C) 2026 hirero-exists <hirerokazuoa@gmail.com>
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 * Compatible with GNU General Public License, Version 2.0 (GPLv2) or later
 * pursuant to Section 3.3 of the Mozilla Public License, v. 2.0.
 */
package com.lenovo.parts;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Bitmap;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

public final class PenLockedNoteActivity extends Activity {
    private QuickNoteService.NoteCanvas mCanvas;
    private boolean mSaving;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().setAttributes(PartsOverlayWindows.prepare(getWindow().getAttributes()));
        FrameLayout root = new FrameLayout(this);
        mCanvas = new QuickNoteService.NoteCanvas(this);
        mCanvas.toggleBackground();
        root.addView(mCanvas, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout bar = new LinearLayout(this);
        button(bar, R.string.pen_brush, () -> mCanvas.setTool(0));
        button(bar, R.string.pen_marker, () -> mCanvas.setTool(1));
        button(bar, R.string.pen_eraser, () -> mCanvas.setTool(2));
        button(bar, R.string.pen_undo, mCanvas::undo);
        button(bar, R.string.pen_save_image, this::save);
        button(bar, R.string.pen_close_tool, this::finish);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.topMargin = Math.round(40 * getResources().getDisplayMetrics().density);
        root.addView(bar, params);
        setContentView(root);
    }

    private void button(LinearLayout bar, int text, Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(v -> action.run());
        bar.addView(button);
    }

    private void save() {
        if (mSaving) return;
        Bitmap bitmap = mCanvas.exportBitmap();
        if (bitmap == null) return;
        mSaving = true;
        new Thread(() -> {
            boolean saved;
            try { PenCapture.save(this, bitmap, false); saved = true; }
            catch (Exception e) { saved = false; }
            finally { bitmap.recycle(); }
            boolean success = saved;
            runOnUiThread(() -> {
                mSaving = false;
                if (isDestroyed()) return;
                Toast.makeText(this, success ? R.string.quick_note_saved : R.string.quick_note_save_error, Toast.LENGTH_LONG).show();
                if (success) finish();
            });
        }, "PenLockedNoteSave").start();
    }
}
