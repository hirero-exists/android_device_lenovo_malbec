/*
 * Copyright (C) 2026 hirero-exists <hirerokazuoa@gmail.com>
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 * Compatible with GNU General Public License, Version 2.0 (GPLv2) or later
 * pursuant to Section 3.3 of the Mozilla Public License, v. 2.0.
 */
#include "../PenCornerFilter.h"
#include <cassert>

using namespace malbec;

static PenState Contact(int rotation, int x, int y) {
    PenState state;
    state.keys[BTN_TOOL_PEN] = state.keys[BTN_TOUCH] = 1;
    state.axes[ABS_PRESSURE] = 127;
    if (rotation == 0) { state.axes[ABS_X] = x; state.axes[ABS_Y] = y; }
    if (rotation == 1) { state.axes[ABS_X] = 1000 - y; state.axes[ABS_Y] = x; }
    if (rotation == 2) { state.axes[ABS_X] = 1000 - x; state.axes[ABS_Y] = 1000 - y; }
    if (rotation == 3) { state.axes[ABS_X] = y; state.axes[ABS_Y] = 1000 - x; }
    return state;
}

int main() {
    input_absinfo axis{};
    axis.maximum = 999;
    PenGuardConfig display;
    assert(display.Parse("0,1000,1000,20,20,30"));
    PenCornerConfig config;
    assert(config.Parse("3,48,48", display));
    PenCornerConfig invalid;
    for (const char* text : {"", "3,-1,48", "4,48,48", "3,250,48", "3,48,48x"}) {
        assert(!invalid.Parse(text, display));
        assert(invalid.mask == 0);
    }
    for (int rotation = 0; rotation < 4; ++rotation) {
        display.rotation = rotation;
        for (int side = 1; side <= 2; ++side) {
            PenCornerFilter filter;
            auto start = Contact(rotation, side == 1 ? 20 : 980, 980);
            auto move = Contact(rotation, side == 1 ? 70 : 930, 930);
            auto r = filter.Process(start, display, config, axis, axis, 100);
            assert(r.reserved && r.action == 0);
            r = filter.Process(move, display, config, axis, axis, 200);
            assert(r.reserved && r.action == side);
            assert(filter.Process(move, display, config, axis, axis, 300).action == 0);
            move.keys[BTN_TOUCH] = 0;
            assert(filter.Process(move, display, config, axis, axis, 400).reserved);
            auto center = Contact(rotation, 500, 500);
            assert(!filter.Process(center, display, config, axis, axis, 500).reserved);
            center = Contact(rotation, 20, 980);
            assert(!filter.Process(center, display, config, axis, axis, 600).reserved);
        }
    }
    display.rotation = 0;
    auto start = Contact(0, 20, 980);
    auto move = Contact(0, 70, 930);
    {
        PenCornerFilter filter;
        auto eraser = start;
        eraser.keys[BTN_TOOL_PEN] = 0;
        eraser.keys[BTN_TOOL_RUBBER] = 1;
        assert(!filter.Process(eraser, display, config, axis, axis, 1).reserved);
    }
    {
        PenCornerFilter filter;
        PenCornerConfig right;
        assert(right.Parse("2,48,48", display));
        assert(!filter.Process(start, display, right, axis, axis, 1).reserved);
    }
    {
        PenCornerFilter filter;
        filter.Process(start, display, config, axis, axis, 1);
        auto wrong = Contact(0, 20, 900);
        assert(filter.Process(wrong, display, config, axis, axis, 100).action == 0);
        assert(filter.Process(move, display, config, axis, axis, 1002).action == 0);
        assert(filter.Process(move, display, config, axis, axis, 1003).reserved);
    }
    {
        PenCornerFilter filter;
        filter.Process(start, display, config, axis, axis, 1);
        auto changed = display;
        changed.rotation = 1;
        assert(filter.Process(move, changed, config, axis, axis, 100).action == 0);
        assert(filter.Process(move, display, config, axis, axis, 200).action == 0);
        assert(filter.Process(move, display, config, axis, axis, 300).reserved);
    }
    {
        PenCornerFilter filter;
        filter.CancelUntilLift(start);
        assert(filter.Process(move, display, config, axis, axis, 100).reserved);
        assert(filter.Process(move, display, config, axis, axis, 100).action == 0);
        move.keys[BTN_TOUCH] = 0;
        filter.Process(move, display, config, axis, axis, 200);
        assert(filter.Process(start, display, config, axis, axis, 300).reserved);
    }
    PenGuardFilter edges;
    auto center = Contact(0, 500, 500);
    auto forwarded = edges.Process(center, true, display, axis, axis);
    assert(forwarded.axes == center.axes && forwarded.keys == center.keys);
}
