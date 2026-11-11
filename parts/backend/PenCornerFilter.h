/*
 * Copyright (C) 2026 hirero-exists <hirerokazuoa@gmail.com>
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 * Compatible with GNU General Public License, Version 2.0 (GPLv2) or later
 * pursuant to Section 3.3 of the Mozilla Public License, v. 2.0.
 */
#pragma once

#include "PenGuardFilter.h"
#include <cstdint>

namespace malbec {
struct PenCornerConfig {
    int mask = 0;
    int size = 0;
    int travel = 0;

    bool Parse(const std::string& text, const PenGuardConfig& display) {
        char extra;
        if (std::sscanf(text.c_str(), "%d,%d,%d%c", &mask, &size, &travel, &extra) != 3
                || mask < 0 || mask > 3 || size <= 0 || travel <= 0
                || size >= std::min(display.width, display.height) / 4
                || travel >= std::min(display.width, display.height) / 4) {
            *this = {};
            return false;
        }
        return true;
    }
};

struct PenCornerResult {
    bool reserved = false;
    int action = 0;
};

class PenCornerFilter {
  public:
    PenCornerResult Process(const PenState& state, const PenGuardConfig& display,
                            const PenCornerConfig& config, const input_absinfo& x_axis,
                            const input_absinfo& y_axis, int64_t now_ms) {
        const bool touching = state.keys[BTN_TOUCH] != 0;
        if (!touching) {
            const bool reserved = mReserved;
            *this = {};
            return {reserved, 0};
        }
        const auto point = display.Position(state.axes[ABS_X], state.axes[ABS_Y], x_axis, y_axis);
        if (!mTouching) {
            mTouching = true;
            mDisplay = display;
            mConfig = config;
            mStartX = point[0];
            mStartY = point[1];
            mStartMs = now_ms;
            if (state.keys[BTN_TOOL_PEN] && !state.keys[BTN_TOOL_RUBBER]
                    && point[1] >= display.height - config.size) {
                if ((config.mask & 1) && point[0] <= config.size) mSide = 1;
                else if ((config.mask & 2) && point[0] >= display.width - config.size) mSide = 2;
            }
            mReserved = mSide != 0;
        }
        if (!mReserved) return {};
        if (mDisplay.rotation != display.rotation || mDisplay.width != display.width
                || mDisplay.height != display.height || mConfig.mask != config.mask
                || mConfig.size != config.size || mConfig.travel != config.travel
                || now_ms < mStartMs || now_ms - mStartMs > 1000) mCanceled = true;
        if (!mCanceled && !mFired && mSide != 0) {
            const double inward = mSide == 1 ? point[0] - mStartX : mStartX - point[0];
            if (inward >= config.travel && mStartY - point[1] >= config.travel) {
                mFired = true;
                return {true, mSide};
            }
        }
        return {true, 0};
    }

    void CancelUntilLift(const PenState& state) {
        *this = {};
        mTouching = state.keys[BTN_TOUCH] != 0;
        mReserved = mCanceled = mTouching;
    }

  private:
    bool mTouching = false;
    bool mReserved = false;
    bool mCanceled = false;
    bool mFired = false;
    int mSide = 0;
    double mStartX = 0;
    double mStartY = 0;
    int64_t mStartMs = 0;
    PenGuardConfig mDisplay;
    PenCornerConfig mConfig;
};
}
