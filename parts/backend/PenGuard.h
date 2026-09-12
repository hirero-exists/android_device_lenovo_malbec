#pragma once

#include <functional>

namespace malbec {
void RunPenGuard(const char* device_name, const std::function<void(int)>& button_action);
}
