/*
 * Copyright (C) 2026 hirero-exists <hirerokazuoa@gmail.com>
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 * Compatible with GNU General Public License, Version 2.0 (GPLv2) or later
 * pursuant to Section 3.3 of the Mozilla Public License, v. 2.0.
 */
package com.lenovo.parts;

final class PenEventOrder {
    private long mGeneration = -1;
    private long mSequence = -1;
    private long mTime;

    void reset() {
        mGeneration = mSequence = -1;
        mTime = 0;
    }

    boolean accept(long generation, long sequence, long time, int kind, int usage) {
        if (generation < 1 || sequence < 0 || time <= 0 || kind < 0 || kind > 6
                || (kind == 2 && (usage < 0x0c0600 || usage > 0x0c0605))
                || ((kind == 4 || kind == 5) && (usage < 1 || usage > 2))
                || (kind != 2 && kind != 4 && kind != 5 && usage != 0)) return false;
        if (generation < mGeneration || (generation == mGeneration
                && (sequence <= mSequence || time < mTime))) return false;
        mGeneration = generation;
        mSequence = sequence;
        mTime = time;
        return true;
    }
}
