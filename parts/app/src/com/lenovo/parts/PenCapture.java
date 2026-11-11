/*
 * Copyright (C) 2026 hirero-exists <hirerokazuoa@gmail.com>
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 * Compatible with GNU General Public License, Version 2.0 (GPLv2) or later
 * pursuant to Section 3.3 of the Mozilla Public License, v. 2.0.
 */
package com.lenovo.parts;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.provider.MediaStore;
import android.view.WindowManagerGlobal;
import android.window.ScreenCaptureInternal;
import java.io.OutputStream;

final class PenCapture {
    private PenCapture() { }

    static Bitmap screen(int displayId) {
        try {
            ScreenCaptureInternal.SynchronousScreenCaptureListener listener =
                    ScreenCaptureInternal.createSyncCaptureListener();
            WindowManagerGlobal.getWindowManagerService().captureDisplay(displayId, null, listener);
            ScreenCaptureInternal.ScreenshotHardwareBuffer buffer = listener.getBuffer();
            if (buffer == null) return null;
            try {
                if (buffer.containsSecureLayers()) return null;
                Bitmap source = buffer.asBitmap();
                if (source == null) return null;
                Bitmap copy = source.copy(Bitmap.Config.ARGB_8888, false);
                source.recycle();
                return copy;
            } finally {
                buffer.getHardwareBuffer().close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    static Uri save(Context context, Bitmap bitmap, boolean pdf) throws Exception {
        String name = "Pen_" + System.currentTimeMillis() + (pdf ? ".pdf" : ".png");
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, pdf ? "application/pdf" : "image/png");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, pdf ? "Download/Pen" : "Pictures/Pen");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri collection = pdf ? MediaStore.Downloads.EXTERNAL_CONTENT_URI
                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        Uri uri = context.getContentResolver().insert(collection, values);
        if (uri == null) throw new IllegalStateException("MediaStore insert failed");
        try {
            try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("MediaStore stream unavailable");
                if (pdf) {
                    PdfDocument document = new PdfDocument();
                    try {
                        PdfDocument.Page page = document.startPage(new PdfDocument.PageInfo.Builder(
                                bitmap.getWidth(), bitmap.getHeight(), 1).create());
                        page.getCanvas().drawBitmap(bitmap, 0, 0, null);
                        document.finishPage(page);
                        document.writeTo(out);
                    } finally {
                        document.close();
                    }
                } else if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw new IllegalStateException("Image encoding failed");
                }
            }
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            context.getContentResolver().update(uri, values, null, null);
            return uri;
        } catch (Exception e) {
            context.getContentResolver().delete(uri, null, null);
            throw e;
        }
    }

    static void share(Context context, Uri uri, boolean pdf) {
        Intent send = new Intent(Intent.ACTION_SEND).setType(pdf ? "application/pdf" : "image/png")
                .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(android.content.ClipData.newRawUri("Pen export", uri));
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
}
