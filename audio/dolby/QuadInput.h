#pragma once

#include <cstddef>

namespace malbec::audio {
inline void FoldQuadToStereo(const float* input, float* output, size_t frames) {
    for (size_t frame = 0; frame < frames; ++frame) {
        output[2 * frame] = 0.5f * (input[4 * frame] + input[4 * frame + 2]);
        output[2 * frame + 1] = 0.5f * (input[4 * frame + 1] + input[4 * frame + 3]);
    }
}
}
