/*
 * Copyright (C) 2026 hirero-exists <hirerokazuoa@gmail.com>
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 * Compatible with GNU General Public License, Version 2.0 (GPLv2) or later
 * pursuant to Section 3.3 of the Mozilla Public License, v. 2.0.
 */
package com.lenovo.parts;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.CRC32;

public final class PenDraftStoreVectors {
    private static void reject(byte[] data) throws Exception {
        try { PenDraftStore.decode(data); throw new AssertionError("accepted invalid data"); }
        catch (IOException expected) { }
    }
    private static void checksum(byte[] data) {
        CRC32 crc = new CRC32(); crc.update(data, 0, data.length - 8);
        ByteBuffer.wrap(data).putLong(data.length - 8, crc.getValue());
    }
    public static void main(String[] args) throws Exception {
        PenDraftStore.Draft d = new PenDraftStore.Draft();
        d.mode = 2; d.width = 100; d.height = 200; d.tool = 6; d.palette = 2;
        d.color = 0xff123456; d.brushWidth = 4; d.timestamp = 123;
        d.background = "background-1234-abcd.png";
        PenDraftStore.Stroke s = new PenDraftStore.Stroke();
        s.color = 0xffabcdef; s.width = 8; s.eraser = true; s.style = 6;
        s.samples.add(new float[] {12.5f, -2, 0.25f});
        s.samples.add(new float[] {25, 49, 1}); d.strokes.add(s);
        byte[] encoded = PenDraftStore.encode(d);
        PenDraftStore.Draft copy = PenDraftStore.decode(encoded);
        if (copy.mode != d.mode || copy.width != d.width || copy.height != d.height
                || copy.tool != d.tool || copy.palette != d.palette || copy.color != d.color
                || copy.brushWidth != d.brushWidth || copy.timestamp != d.timestamp
                || !copy.background.equals(d.background) || copy.strokes.size() != 1
                || copy.strokes.get(0).color != s.color || copy.strokes.get(0).width != s.width
                || !copy.strokes.get(0).eraser || copy.strokes.get(0).style != s.style
                || !Arrays.equals(copy.strokes.get(0).samples.get(0), s.samples.get(0))
                || !Arrays.equals(copy.strokes.get(0).samples.get(1), s.samples.get(1)))
            throw new AssertionError("round trip");
        reject(Arrays.copyOf(encoded, encoded.length - 1));
        byte[] corrupt = encoded.clone(); corrupt[20] ^= 1; reject(corrupt);
        byte[] version = encoded.clone(); ByteBuffer.wrap(version).putInt(4, 2); checksum(version); reject(version);
        byte[] count = encoded.clone();
        int countOffset = 46 + d.background.length();
        ByteBuffer.wrap(count).putInt(countOffset, Integer.MAX_VALUE); checksum(count); reject(count);
        byte[] sampleCount = encoded.clone();
        ByteBuffer.wrap(sampleCount).putInt(countOffset + 4 + 4 + 4 + 1 + 4, Integer.MAX_VALUE);
        checksum(sampleCount); reject(sampleCount);
        byte[] trailing = Arrays.copyOf(encoded, encoded.length + 1); checksum(trailing); reject(trailing);
        reject(new byte[PenDraftStore.MAX_BYTES + 1]);
        s.samples.get(0)[0] = Float.NaN;
        try { PenDraftStore.encode(d); throw new AssertionError("accepted NaN"); }
        catch (IOException expected) { }
        System.out.println("PenDraftStore vectors passed");
    }
}
