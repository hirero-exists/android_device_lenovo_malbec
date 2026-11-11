/*
 * Copyright (C) 2026 hirero-exists <hirerokazuoa@gmail.com>
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 * Compatible with GNU General Public License, Version 2.0 (GPLv2) or later
 * pursuant to Section 3.3 of the Mozilla Public License, v. 2.0.
 */
#pragma once
#include <cstdint>
namespace malbec {
bool StartPenHal();
void PenConnection(bool connected);
void PenGesture(uint32_t usage, bool pressed);
void PenStreamReset();
void PenCorner(int side);
void PenDigitizerTap(int button);
void PenProximity(bool in_range);
}
