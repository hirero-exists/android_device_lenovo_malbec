/*
 * Copyright (C) 2026 hirero-exists <hirerokazuoa@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Compatible with GNU General Public License, Version 2.0 (GPLv2) or later
 * pursuant to Section 3.3 of the Mozilla Public License, v. 2.0.
 */
package com.lenovo.parts;

import android.view.WindowManager;

final class PartsOverlayWindows {
    private PartsOverlayWindows() {}

    static WindowManager.LayoutParams prepare(WindowManager.LayoutParams params) {
        params.flags &= ~WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
        params.setBlurBehindRadius(0);
        return params;
    }
}
