/*
 * Copyright (C) 2026 hirero-exists <hirerokazuoa@gmail.com>
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 * Compatible with GNU General Public License, Version 2.0 (GPLv2) or later
 * pursuant to Section 3.3 of the Mozilla Public License, v. 2.0.
 */
package com.lenovo.parts;

final class PenRefreshHold {
    private boolean mPresent;
    private long mReleaseAt;

    void proximity(boolean present, long now) {
        if (present) {
            mPresent = true;
            mReleaseAt = 0;
        } else if (mPresent) {
            mPresent = false;
            mReleaseAt = now + 2000;
        }
    }

    boolean active(long now) { return mPresent || now < mReleaseAt; }
    long releaseDelay(long now) { return mPresent ? 0 : Math.max(0, mReleaseAt - now); }
    void clear() { mPresent = false; mReleaseAt = 0; }
}
