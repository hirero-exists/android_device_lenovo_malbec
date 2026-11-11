/*
 * Copyright (C) 2026 hirero-exists <hirerokazuoa@gmail.com>
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 * Compatible with GNU General Public License, Version 2.0 (GPLv2) or later
 * pursuant to Section 3.3 of the Mozilla Public License, v. 2.0.
 */
package com.lenovo.parts;

public final class PenPalmSessionTest {
    private static final class Hardware implements PenPalmSession.Provider {
        int mode = 1, writes;
        boolean reject, mismatch, dead;
        @Override public int get() throws Exception {
            if (dead) throw new Exception("disconnected");
            return mode;
        }
        @Override public boolean set(int desired) throws Exception {
            writes++;
            if (dead) throw new Exception("disconnected");
            if (reject) return false;
            if (!mismatch) mode = desired;
            return true;
        }
    }
    private static void fails(PenPalmSession session, Hardware hw, Integer mode) {
        try { session.apply(hw, mode); throw new AssertionError("Accepted failure"); }
        catch (AssertionError error) { throw error; }
        catch (Exception expected) { }
    }
    public static void main(String[] args) throws Exception {
        Hardware hw = new Hardware();
        PenPalmSession session = new PenPalmSession();
        assert session.apply(hw, null) == 1 && hw.writes == 0;
        assert session.apply(hw, 0) == 0;
        assert session.apply(hw, 2) == 2;
        int writes = hw.writes;
        assert session.apply(hw, 2) == 2 && hw.writes == writes;
        assert session.apply(hw, null) == 1 && !session.needsRestore();
        hw.reject = true;
        fails(session, hw, 0);
        assert session.needsRestore();
        hw.reject = false;
        assert session.apply(hw, null) == 1;
        hw.mismatch = true;
        fails(session, hw, 0);
        hw.mismatch = false;
        assert session.apply(hw, 0) == 0;
        hw.dead = true;
        fails(session, hw, null);
        assert session.needsRestore();
        // A replacement service must not become the baseline after death.
        Hardware replacement = new Hardware();
        replacement.mode = 0;
        assert session.apply(replacement, null) == 1;
        replacement.mode = -1;
        fails(session, replacement, 2);
        assert replacement.writes == 1;
        replacement.mode = 1;
        fails(session, replacement, 3);
        assert replacement.writes == 1;
        Integer[] saved = { null };
        PenPalmSession durable = new PenPalmSession(null, baseline -> { saved[0] = baseline; return true; });
        replacement.mode = 1;
        assert durable.apply(replacement, 0) == 0 && saved[0] == 1;
        PenPalmSession restarted = new PenPalmSession(saved[0], baseline -> { saved[0] = baseline; return true; });
        assert restarted.apply(replacement, null) == 1 && saved[0] == null;
        PenPalmSession failedStore = new PenPalmSession(null, baseline -> false);
        writes = replacement.writes;
        fails(failedStore, replacement, 0);
        assert replacement.writes == writes;
    }
}
