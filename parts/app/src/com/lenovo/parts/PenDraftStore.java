/*
 * Copyright (C) 2026 hirero-exists <hirerokazuoa@gmail.com>
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 * Compatible with GNU General Public License, Version 2.0 (GPLv2) or later
 * pursuant to Section 3.3 of the Mozilla Public License, v. 2.0.
 */
package com.lenovo.parts;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.AtomicFile;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.CRC32;

final class PenDraftStore {
    static final ExecutorService IO = Executors.newSingleThreadExecutor();
    static final int MAX_BYTES = 16 * 1024 * 1024, MAX_SAMPLES = 1000000;
    static final int MAX_STROKES = 10000, MAX_BACKGROUND = 48 * 1024 * 1024;
    private static final int MAGIC = 0x50454e44, VERSION = 1;
    private final File mDir;

    static final class Stroke {
        int color, style;
        float width;
        boolean eraser;
        final ArrayList<float[]> samples = new ArrayList<>();
    }
    static final class Draft {
        int mode, width, height, tool, palette, color;
        float brushWidth;
        long timestamp;
        String background = "";
        final ArrayList<Stroke> strokes = new ArrayList<>();
    }

    PenDraftStore(Context context) {
        mDir = new File(context.createCredentialProtectedStorageContext().getNoBackupFilesDir(), "pen_drafts");
    }
    private AtomicFile file(int mode) { return new AtomicFile(new File(mDir, "draft-" + mode)); }
    private File marker(int mode) { return new File(mDir, "incomplete-" + mode); }
    boolean exists(int mode) { return file(mode).exists(); }
    boolean incomplete(int mode) { return marker(mode).exists(); }

    private static void syncDirectory(File directory) throws IOException {
        FileDescriptor fd;
        try { fd = Os.open(directory.getPath(), OsConstants.O_RDONLY | OsConstants.O_CLOEXEC, 0); }
        catch (ErrnoException e) { throw new IOException("Cannot open draft directory for sync", e); }
        IOException failure = null;
        try { Os.fsync(fd); }
        catch (ErrnoException e) { failure = new IOException("Cannot sync draft directory", e); }
        try { Os.close(fd); }
        catch (ErrnoException e) {
            IOException closeFailure = new IOException("Cannot close draft directory", e);
            if (failure == null) failure = closeFailure; else failure.addSuppressed(closeFailure);
        }
        if (failure != null) throw failure;
    }
    private void ensureDirectory() throws IOException {
        if (!mDir.isDirectory() && !mDir.mkdirs()) throw new IOException("Cannot create draft directory");
        syncDirectory(mDir.getParentFile());
    }
    void markIncomplete(int mode) throws IOException {
        ensureDirectory();
        try (FileOutputStream out = new FileOutputStream(marker(mode))) {
            out.write(1);
            out.getFD().sync();
        }
        syncDirectory(mDir);
    }
    void clearIncomplete(int mode) throws IOException {
        File marker = marker(mode);
        if (marker.exists()) {
            if (!marker.delete()) throw new IOException("Cannot clear draft marker");
            try { syncDirectory(mDir); }
            catch (IOException e) {
                try { markIncomplete(mode); } catch (IOException restoreFailure) { e.addSuppressed(restoreFailure); }
                throw e;
            }
        }
    }

    void sweepOrphans() {
        java.util.HashSet<String> retained = new java.util.HashSet<>();
        for (int mode = 1; mode <= 2; mode++) {
            if (exists(mode)) {
                try { retained.add(read(mode).background); }
                catch (IOException e) { return; }
            }
        }
        File[] files = mDir.listFiles();
        if (files == null) return;
        for (File path : files) {
            String name = path.getName();
            if (name.matches("background-[a-f0-9-]+\\.png") && !retained.contains(name)) path.delete();
        }
    }

    private static void validate(Draft d) throws IOException {
        if ((d.mode != 1 && d.mode != 2) || d.width < 1 || d.height < 1
                || (long) d.width * d.height > 16000000 || d.tool < 0 || d.tool > 6
                || d.palette < 0 || d.palette > 5 || !Float.isFinite(d.brushWidth)
                || d.brushWidth <= 0 || d.brushWidth > 256 || d.strokes.size() > MAX_STROKES
                || (d.mode == 2 && !d.background.matches("background-[a-f0-9-]+\\.png"))
                || (d.mode == 1 && !d.background.isEmpty())) throw new IOException("Invalid draft");
        int count = 0;
        for (Stroke s : d.strokes) {
            if (s.style < 0 || s.style > 6 || !Float.isFinite(s.width) || s.width <= 0
                    || s.width > 4096 || s.samples.isEmpty()) throw new IOException("Invalid stroke");
            count += s.samples.size();
            if (count > MAX_SAMPLES) throw new IOException("Too many samples");
            for (float[] p : s.samples) {
                if (p.length != 3 || !Float.isFinite(p[0]) || !Float.isFinite(p[1])
                        || !Float.isFinite(p[2]) || Math.abs(p[0]) > 1000000
                        || Math.abs(p[1]) > 1000000 || p[2] < 0 || p[2] > 1)
                    throw new IOException("Invalid sample");
            }
        }
    }
    static byte[] encode(Draft d) throws IOException {
        validate(d);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(MAGIC); out.writeInt(VERSION); out.writeInt(d.mode);
        out.writeInt(d.width); out.writeInt(d.height); out.writeLong(d.timestamp);
        out.writeInt(d.tool); out.writeInt(d.palette); out.writeInt(d.color); out.writeFloat(d.brushWidth);
        out.writeUTF(d.background); out.writeInt(d.strokes.size());
        for (Stroke s : d.strokes) {
            out.writeInt(s.color); out.writeFloat(s.width); out.writeBoolean(s.eraser);
            out.writeInt(s.style); out.writeInt(s.samples.size());
            for (float[] p : s.samples) { out.writeFloat(p[0]); out.writeFloat(p[1]); out.writeFloat(p[2]); }
        }
        out.flush();
        if (bytes.size() + 8 > MAX_BYTES) throw new IOException("Draft too large");
        CRC32 crc = new CRC32(); crc.update(bytes.toByteArray());
        out.writeLong(crc.getValue()); out.flush();
        return bytes.toByteArray();
    }
    static Draft decode(byte[] bytes) throws IOException {
        if (bytes.length < 8 || bytes.length > MAX_BYTES) throw new IOException("Invalid size");
        CRC32 crc = new CRC32(); crc.update(bytes, 0, bytes.length - 8);
        DataInputStream tail = new DataInputStream(new ByteArrayInputStream(bytes, bytes.length - 8, 8));
        if (tail.readLong() != crc.getValue()) throw new IOException("Checksum mismatch");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes, 0, bytes.length - 8));
        if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IOException("Invalid version");
        Draft d = new Draft(); d.mode = in.readInt(); d.width = in.readInt(); d.height = in.readInt();
        d.timestamp = in.readLong(); d.tool = in.readInt(); d.palette = in.readInt();
        d.color = in.readInt(); d.brushWidth = in.readFloat(); d.background = in.readUTF();
        int count = in.readInt(), samples = 0;
        if (count < 0 || count > MAX_STROKES) throw new IOException("Invalid stroke count");
        for (int i = 0; i < count; i++) {
            Stroke s = new Stroke(); s.color = in.readInt(); s.width = in.readFloat();
            s.eraser = in.readBoolean(); s.style = in.readInt(); int n = in.readInt();
            if (n < 1 || n > MAX_SAMPLES - samples || n > in.available() / 12)
                throw new IOException("Invalid sample count");
            samples += n;
            for (int j = 0; j < n; j++) s.samples.add(new float[] {in.readFloat(), in.readFloat(), in.readFloat()});
            d.strokes.add(s);
        }
        if (in.available() != 0) throw new IOException("Trailing data");
        validate(d); return d;
    }
    Draft read(int mode) throws IOException {
        byte[] bytes;
        try (FileInputStream in = file(mode).openRead()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] chunk = new byte[8192]; int n;
            while ((n = in.read(chunk)) != -1) {
                if (out.size() + n > MAX_BYTES) throw new IOException("Draft too large");
                out.write(chunk, 0, n);
            }
            bytes = out.toByteArray();
        }
        Draft d = decode(bytes);
        if (d.mode != mode) throw new IOException("Wrong mode");
        return d;
    }
    void write(Draft d) throws IOException {
        byte[] bytes = encode(d);
        ensureDirectory();
        String oldBackground = referencedBackground(d.mode);
        AtomicFile file = file(d.mode); FileOutputStream out = null;
        try { out = file.startWrite(); out.write(bytes); out.getFD().sync(); file.finishWrite(out); }
        catch (IOException e) { if (out != null) file.failWrite(out); throw e; }
        try (FileInputStream in = file.openRead()) {
            byte[] committed = new byte[bytes.length]; int offset = 0, n;
            while (offset < committed.length && (n = in.read(committed, offset, committed.length - offset)) != -1)
                offset += n;
            if (offset != bytes.length || in.read() != -1 || !java.util.Arrays.equals(bytes, committed))
                throw new IOException("Draft commit failed");
        }
        syncDirectory(mDir);
        if (!oldBackground.equals(d.background)) removeBackground(oldBackground, d.mode);
    }
    String writeBackground(Bitmap bitmap) throws IOException {
        if ((long) bitmap.getWidth() * bitmap.getHeight() > 16000000) throw new IOException("Image too large");
        ensureDirectory();
        String name = "background-" + java.util.UUID.randomUUID().toString() + ".png";
        File path = new File(mDir, name);
        try (FileOutputStream out = new FileOutputStream(path)) {
            OutputStream bounded = new OutputStream() {
                int count;
                @Override public void write(int b) throws IOException {
                    if (count >= MAX_BACKGROUND) throw new IOException("Image too large");
                    out.write(b); count++;
                }
                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                    if (length > MAX_BACKGROUND - count) throw new IOException("Image too large");
                    out.write(bytes, offset, length); count += length;
                }
            };
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, bounded)) throw new IOException("Image compression failed");
            out.getFD().sync();
            syncDirectory(mDir);
        } catch (IOException | RuntimeException e) { path.delete(); throw e; }
        return name;
    }
    Bitmap readBackground(Draft d) throws IOException {
        File path = new File(mDir, d.background);
        if (!path.isFile() || path.length() > MAX_BACKGROUND) throw new IOException("Missing image");
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path.getPath(), bounds);
        if (bounds.outWidth != d.width || bounds.outHeight != d.height
                || (long) bounds.outWidth * bounds.outHeight > 16000000) throw new IOException("Invalid image");
        Bitmap bitmap = BitmapFactory.decodeFile(path.getPath());
        if (bitmap == null) throw new IOException("Invalid image");
        return bitmap;
    }
    boolean delete(int mode) {
        String oldBackground = referencedBackground(mode);
        file(mode).delete();
        boolean deleted = !exists(mode);
        if (deleted) {
            try {
                if (mDir.isDirectory()) syncDirectory(mDir);
                clearIncomplete(mode);
            }
            catch (IOException e) { return false; }
            removeBackground(oldBackground, mode);
        }
        return deleted;
    }
    private String referencedBackground(int mode) {
        try { return exists(mode) ? read(mode).background : ""; }
        catch (IOException e) { return ""; }
    }
    private void removeBackground(String name, int mode) {
        if (name.isEmpty()) return;
        int other = mode == 1 ? 2 : 1;
        if (exists(other)) {
            try { if (name.equals(read(other).background)) return; }
            catch (IOException e) { return; }
        }
        new File(mDir, name).delete();
    }
}
