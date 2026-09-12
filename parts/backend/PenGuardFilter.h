#pragma once

#include <linux/input.h>

#include <algorithm>
#include <array>
#include <cstdio>
#include <string>

namespace malbec {

inline constexpr int kPenAxes[] = {
        ABS_X, ABS_Y, ABS_PRESSURE, ABS_DISTANCE, ABS_TILT_X, ABS_TILT_Y};
inline constexpr int kPenKeys[] = {
        BTN_TOOL_PEN, BTN_TOOL_RUBBER, BTN_TOUCH, BTN_STYLUS, BTN_STYLUS2};

struct PenState {
    std::array<int, ABS_CNT> axes{};
    std::array<int, KEY_CNT> keys{};

    bool InRange() const {
        return keys[BTN_TOOL_PEN] || keys[BTN_TOOL_RUBBER] || keys[BTN_TOUCH];
    }

    void Update(const input_event& event) {
        if (event.type == EV_ABS && event.code < ABS_CNT) axes[event.code] = event.value;
        if (event.type == EV_KEY && event.code < KEY_CNT) keys[event.code] = event.value;
    }
};

struct PenGuardConfig {
    int rotation = 0;
    int width = 0;
    int height = 0;
    int left = 0;
    int right = 0;
    int bottom = 0;

    bool Parse(const std::string& text) {
        char extra;
        return std::sscanf(text.c_str(), "%d,%d,%d,%d,%d,%d%c", &rotation,
                           &width, &height, &left, &right, &bottom, &extra) == 6
                && rotation >= 0 && rotation < 4 && width > 0 && height > 0
                && left >= 0 && right >= 0 && bottom >= 0
                && left < width / 4 && right < width / 4 && bottom < height / 4;
    }

    bool HasEdges() const { return left || right || bottom; }

    bool Contains(int raw_x, int raw_y, const input_absinfo& x_axis,
                  const input_absinfo& y_axis) const {
        double x = std::clamp((raw_x - static_cast<double>(x_axis.minimum)) /
                (1.0 + x_axis.maximum - x_axis.minimum), 0.0, 1.0);
        double y = std::clamp((raw_y - static_cast<double>(y_axis.minimum)) /
                (1.0 + y_axis.maximum - y_axis.minimum), 0.0, 1.0);
        const double original_x = x;
        if (rotation == 1) { x = y; y = 1.0 - original_x; }
        if (rotation == 2) { x = 1.0 - x; y = 1.0 - y; }
        if (rotation == 3) { x = 1.0 - y; y = original_x; }
        return (left > 0 && x * width <= left)
                || (right > 0 && x * width >= width - right)
                || (bottom > 0 && y * height >= height - bottom);
    }
};

class PenGuardFilter {
public:
    PenState Process(const PenState& state, bool enabled, const PenGuardConfig& config,
                     const input_absinfo& x_axis, const input_absinfo& y_axis) {
        const bool touching = state.keys[BTN_TOUCH] != 0;
        if (touching && !mTouching) {
            mBlocked = enabled && config.Contains(state.axes[ABS_X], state.axes[ABS_Y],
                                                 x_axis, y_axis);
        }
        if (!touching) mBlocked = false;
        mTouching = touching;
        PenState result = state;
        if (mBlocked) {
            result.keys[BTN_TOUCH] = 0;
            result.axes[ABS_PRESSURE] = 0;
            result.axes[ABS_DISTANCE] = 1;
        }
        return result;
    }

    void CancelUntilLift(const PenState& state) {
        mTouching = state.keys[BTN_TOUCH] != 0;
        mBlocked = mTouching;
    }

private:
    bool mTouching = false;
    bool mBlocked = false;
};

}
