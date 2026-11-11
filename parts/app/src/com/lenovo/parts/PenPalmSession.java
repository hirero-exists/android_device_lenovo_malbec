/*
 * Copyright (C) 2026 hirero-exists <hirerokazuoa@gmail.com>
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 * Compatible with GNU General Public License, Version 2.0 (GPLv2) or later
 * pursuant to Section 3.3 of the Mozilla Public License, v. 2.0.
 */
package com.lenovo.parts;

final class PenPalmSession {
    interface Store { boolean save(Integer baseline); }
    interface Provider {
        int get() throws Exception;
        boolean set(int mode) throws Exception;
    }
    private Integer mBaseline;
    private boolean mOwned;
    private final Store mStore;

    PenPalmSession() { this(null, baseline -> true); }
    PenPalmSession(Integer restoredBaseline, Store store) {
        mStore = store;
        if (restoredBaseline != null && valid(restoredBaseline)) {
            mBaseline = restoredBaseline;
            mOwned = true;
        }
    }

    int apply(Provider provider, Integer desired) throws Exception {
        int current = provider.get();
        if (!valid(current)) throw new IllegalStateException("Unsupported palm mode");
        if (desired == null) {
            if (!mOwned) { mBaseline = null; return current; }
            int restore = mBaseline;
            if (current != restore && !provider.set(restore)) {
                throw new IllegalStateException("Palm restore rejected");
            }
            int actual = provider.get();
            if (actual != restore) throw new IllegalStateException("Palm restore mismatch");
            if (!mStore.save(null)) throw new IllegalStateException("Palm ownership clear failed");
            mOwned = false;
            mBaseline = null;
            return actual;
        }
        if (!valid(desired)) throw new IllegalArgumentException("Invalid palm mode");
        if (mBaseline == null) mBaseline = current;
        if (current != desired) {
            if (!mStore.save(mBaseline)) throw new IllegalStateException("Palm ownership save failed");
            // A transaction can reach the provider even when its reply is lost.
            mOwned = true;
            if (!provider.set(desired)) throw new IllegalStateException("Palm mode rejected");
        }
        int actual = provider.get();
        if (actual != desired) throw new IllegalStateException("Palm readback mismatch");
        return actual;
    }
    boolean needsRestore() { return mOwned; }
    static boolean valid(int mode) { return mode >= 0 && mode <= 2; }
}
