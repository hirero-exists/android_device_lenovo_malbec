/*
 * Copyright (C) 2026 hirero-exists <hirerokazuoa@gmail.com>
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 * Compatible with GNU General Public License, Version 2.0 (GPLv2) or later
 * pursuant to Section 3.3 of the Mozilla Public License, v. 2.0.
 */
#pragma once

#include <linux/input.h>
#include <cstdint>

namespace malbec {
struct ConsumerEvent {
    uint32_t usage = 0;
    bool pressed = false;
    bool reset = false;
};

// One parser per opened Consumer Control node. Linux emits a scan on press;
// the captured release has no scan, so it must retain the active usage.
class PenConsumerParser {
  public:
    bool Consume(const input_event& event, ConsumerEvent* output) {
        if (event.type == EV_SYN && event.code == SYN_DROPPED) {
            pending_ = active_ = 0;
            dropped_ = true;
            *output = ConsumerEvent{0, false, true};
            return true;
        }
        if (event.type == EV_SYN && event.code == SYN_REPORT) {
            pending_ = 0;
            dropped_ = false;
            return false;
        }
        if (dropped_) return false;
        if (event.type == EV_MSC && event.code == MSC_SCAN) {
            uint32_t scan = static_cast<uint32_t>(event.value);
            pending_ = scan >= 0x0c0600 && scan <= 0x0c0605 ? scan : 0;
            return false;
        }
        if (event.type == EV_KEY && event.code == KEY_UNKNOWN) {
            if (event.value == 1 && pending_ != 0 && active_ != pending_) {
                active_ = pending_;
                pending_ = 0;
                *output = ConsumerEvent{active_, true, false};
                return true;
            }
            if (event.value == 0 && active_ != 0) {
                *output = ConsumerEvent{active_, false, false};
                active_ = 0;
                pending_ = 0;
                return true;
            }
        }
        return false;
    }

  private:
    uint32_t pending_ = 0;
    uint32_t active_ = 0;
    bool dropped_ = false;
};
}  // namespace malbec
