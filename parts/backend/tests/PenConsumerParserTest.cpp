/*
 * Copyright (C) 2026 hirero-exists <hirerokazuoa@gmail.com>
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 * Compatible with GNU General Public License, Version 2.0 (GPLv2) or later
 * pursuant to Section 3.3 of the Mozilla Public License, v. 2.0.
 */
#include "../PenConsumerParser.h"
#include <cassert>

static input_event Event(unsigned short type, unsigned short code, int value) {
    input_event e{};
    e.type = type;
    e.code = code;
    e.value = value;
    return e;
}

int main() {
    malbec::PenConsumerParser parser;
    malbec::ConsumerEvent out;
    // Captured physical-buttons-hover/events.txt:271-275: release lacks MSC_SCAN.
    assert(!parser.Consume(Event(EV_MSC, MSC_SCAN, 0x0c0600), &out));
    assert(parser.Consume(Event(EV_KEY, KEY_UNKNOWN, 1), &out));
    assert(out.usage == 0x0c0600 && out.pressed && !out.reset);
    assert(!parser.Consume(Event(EV_SYN, SYN_REPORT, 0), &out));
    assert(!parser.Consume(Event(EV_KEY, KEY_UNKNOWN, 2), &out));
    assert(parser.Consume(Event(EV_KEY, KEY_UNKNOWN, 0), &out));
    assert(out.usage == 0x0c0600 && !out.pressed && !out.reset);
    assert(!parser.Consume(Event(EV_SYN, SYN_REPORT, 0), &out));
    assert(!parser.Consume(Event(EV_KEY, KEY_UNKNOWN, 0), &out));
    // Invalid scans and scans in a previous frame never create an action.
    assert(!parser.Consume(Event(EV_MSC, MSC_SCAN, 0x0c0606), &out));
    assert(!parser.Consume(Event(EV_KEY, KEY_UNKNOWN, 1), &out));
    assert(!parser.Consume(Event(EV_MSC, MSC_SCAN, 0x0c0601), &out));
    assert(!parser.Consume(Event(EV_SYN, SYN_REPORT, 0), &out));
    assert(!parser.Consume(Event(EV_KEY, KEY_UNKNOWN, 1), &out));
    assert(!parser.Consume(Event(EV_MSC, MSC_SCAN, 0x0c0601), &out));
    assert(parser.Consume(Event(EV_KEY, KEY_UNKNOWN, 1), &out));
    assert(out.usage == 0x0c0601 && out.pressed);
    assert(parser.Consume(Event(EV_SYN, SYN_DROPPED, 0), &out) && out.reset);
    assert(!parser.Consume(Event(EV_KEY, KEY_UNKNOWN, 0), &out));
    assert(!parser.Consume(Event(EV_SYN, SYN_REPORT, 0), &out));
    assert(!parser.Consume(Event(EV_KEY, KEY_UNKNOWN, 0), &out));
    // A reopened input gets a fresh parser and cannot release the old stream.
    malbec::PenConsumerParser reopened;
    assert(!reopened.Consume(Event(EV_KEY, KEY_UNKNOWN, 0), &out));
    for (int usage = 0x0c0600; usage <= 0x0c0605; ++usage) {
        assert(!reopened.Consume(Event(EV_MSC, MSC_SCAN, usage), &out));
        assert(reopened.Consume(Event(EV_KEY, KEY_UNKNOWN, 1), &out));
        assert(out.usage == static_cast<uint32_t>(usage) && out.pressed);
        assert(!reopened.Consume(Event(EV_KEY, KEY_UNKNOWN, 1), &out));
        assert(!reopened.Consume(Event(EV_SYN, SYN_REPORT, 0), &out));
        assert(reopened.Consume(Event(EV_KEY, KEY_UNKNOWN, 0), &out));
        assert(out.usage == static_cast<uint32_t>(usage) && !out.pressed);
        assert(!reopened.Consume(Event(EV_SYN, SYN_REPORT, 0), &out));
    }
    assert(!reopened.Consume(Event(EV_MSC, MSC_SCAN, 0x0c0604), &out));
    assert(reopened.Consume(Event(EV_KEY, KEY_UNKNOWN, 1), &out));
    assert(out.usage == 0x0c0604);
    assert(!reopened.Consume(Event(EV_SYN, SYN_REPORT, 0), &out));
    assert(!reopened.Consume(Event(EV_MSC, MSC_SCAN, 0x0c0603), &out));
    assert(reopened.Consume(Event(EV_KEY, KEY_UNKNOWN, 1), &out));
    assert(out.usage == 0x0c0603);
    assert(reopened.Consume(Event(EV_KEY, KEY_UNKNOWN, 0), &out));
}
