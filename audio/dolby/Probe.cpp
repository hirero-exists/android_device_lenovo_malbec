#include "QuadInput.h"

#include <aidl/android/hardware/audio/effect/IEffect.h>
#include <aidl/android/hardware/audio/effect/CommandId.h>
#include <aidl/android/media/audio/common/AudioChannelLayout.h>
#include <aidl/android/media/audio/common/AudioFormatType.h>
#include <aidl/android/media/audio/common/PcmType.h>
#include <fmq/AidlMessageQueue.h>
#include <fmq/EventFlag.h>
#include <system/audio_effects/aidl_effects_utils.h>

#include <cmath>
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dlfcn.h>
#include <memory>
#include <thread>
#include <time.h>
#include <vector>

using namespace aidl::android::hardware::audio::effect;
using namespace aidl::android::media::audio::common;
using aidl::android::hardware::common::fmq::SynchronizedReadWrite;
using StatusMQ = android::AidlMessageQueue<IEffect::Status, SynchronizedReadWrite>;
using DataMQ = android::AidlMessageQueue<float, SynchronizedReadWrite>;

int main(int argc, char** argv) {
    const bool bridge = argc > 1 && std::strcmp(argv[1], "direct") != 0;
    void* library = dlopen(bridge ? argv[1] : "/vendor/lib64/soundfx/libswdapaidl.so", RTLD_NOW | RTLD_LOCAL);
    if (!library) { std::fprintf(stderr, "%s\n", dlerror()); return 1; }
    using Create = binder_exception_t (*)(const AudioUuid*, std::shared_ptr<IEffect>*);
    auto create = reinterpret_cast<Create>(dlsym(library, "createEffect"));
    if (!create) return 2;
    AudioUuid uuid{};
    uuid.timeLow = static_cast<int32_t>(0x9d4921da);
    uuid.timeMid = static_cast<int32_t>(0x8225);
    uuid.timeHiAndVersion = static_cast<int32_t>(0x4f29);
    uuid.clockSeq = static_cast<int32_t>(0xaefa);
    uuid.node = {0x39, 0x53, 0x7a, 0x04, 0xbc, 0xaa};
    std::shared_ptr<IEffect> engine;
    if (create(&uuid, &engine) != EX_NONE || !engine) return 3;
    Parameter::Common common{};
    common.session = 12345;
    common.ioHandle = 0;
    common.input.base.sampleRate = common.output.base.sampleRate = 48000;
    common.input.base.format.type = common.output.base.format.type = AudioFormatType::PCM;
    common.input.base.format.pcm = common.output.base.format.pcm = PcmType::FLOAT_32_BIT;
    common.input.base.channelMask = AudioChannelLayout::make<AudioChannelLayout::layoutMask>(bridge ? 0x33 : 3);
    common.output.base.channelMask = AudioChannelLayout::make<AudioChannelLayout::layoutMask>(0x33);
    const size_t frames = argc > 2 ? std::strtoul(argv[2], nullptr, 10) : 256;
    const int blocks = argc > 3 ? std::atoi(argv[3]) : 30;
    const bool paced = argc > 4 && std::strcmp(argv[4], "paced") == 0;
    if (!frames || frames > 8192 || blocks < 1 || blocks > 10000) return 7;
    common.input.frameCount = common.output.frameCount = frames;
    IEffect::OpenEffectReturn queues;
    auto result = engine->open(common, std::nullopt, &queues);
    if (!result.isOk()) { std::fprintf(stderr, "open: %s\n", result.getDescription().c_str()); return 4; }
    StatusMQ status(queues.statusMQ);
    DataMQ input(queues.inputDataMQ), output(queues.outputDataMQ);
    android::hardware::EventFlag* flag = nullptr;
    if (!status.isValid() || !input.isValid() || !output.isValid() ||
            android::hardware::EventFlag::createEventFlag(status.getEventFlagWord(), &flag) != 0) return 5;
    int version = 0;
    engine->getInterfaceVersion(&version);
    uint32_t wake = version >= 2 ? kEventFlagDataMqNotEmpty : kEventFlagNotEmpty;
    std::vector<float> quad(frames * 4), stereo(frames * 2), processed(frames * 4);
    double energy[4]{};
    std::vector<double> delays;
    auto cpuMs = [] {
        timespec t{};
        clock_gettime(CLOCK_PROCESS_CPUTIME_ID, &t);
        return t.tv_sec * 1000.0 + t.tv_nsec / 1000000.0;
    };
    const double cpuStart = cpuMs();
    bool good = true;
    for (int cycle = 0; cycle < 3 && good; ++cycle) {
        if (!engine->command(CommandId::START).isOk()) { good = false; break; }
        const auto cycleStart = std::chrono::steady_clock::now();
        for (int block = 0; block < blocks && good; ++block) {
            for (size_t f = 0; f < frames; ++f) {
                const float sample = 0.01f * std::sin(2.0 * 3.141592653589793 * 440.0 *
                        (block * frames + f) / 48000.0);
                for (int channel = 0; channel < 4; ++channel) quad[4 * f + channel] = sample;
                stereo[2 * f] = stereo[2 * f + 1] = sample;
            }
            IEffect::Status response{};
            const auto& send = bridge ? quad : stereo;
            const auto begin = std::chrono::steady_clock::now();
            good = input.write(send.data(), send.size()) && flag->wake(wake) == 0;
            good = good && status.readBlocking(&response, 1, 1000000000);
            good = good && response.status == 0 && response.fmqConsumed == static_cast<int>(send.size())
                    && response.fmqProduced == static_cast<int>(processed.size());
            good = good && output.read(processed.data(), processed.size());
            const double elapsed = std::chrono::duration<double, std::milli>(
                    std::chrono::steady_clock::now() - begin).count();
            if (block >= 10) delays.push_back(elapsed);
            if (!good) { std::fprintf(stderr, "FMQ failed: %s\n", response.toString().c_str()); break; }
            for (size_t n = 0; n < processed.size(); ++n) {
                if (!std::isfinite(processed[n])) good = false;
                energy[n % 4] += processed[n] * processed[n];
            }
            if (paced) std::this_thread::sleep_until(cycleStart + std::chrono::nanoseconds(
                    static_cast<int64_t>((block + 1) * frames) * 1000000000LL / 48000));
        }
        good = engine->command(CommandId::STOP).isOk() && good;
        good = engine->command(CommandId::RESET).isOk() && good;
    }
    const double cpuUsed = cpuMs() - cpuStart;
    if (!delays.empty()) {
        std::sort(delays.begin(), delays.end());
        double sum = 0;
        for (double delay : delays) sum += delay;
        const double audioMs = 3.0 * blocks * frames / 48.0;
        std::printf("BENCH path=%s frames=%zu blocks=%d paced=%d cpu_ms=%.3f audio_ms=%.3f core_percent=%.3f mean_ms=%.3f p50_ms=%.3f p95_ms=%.3f p99_ms=%.3f max_ms=%.3f\n",
                bridge ? "bridge" : "direct", frames, blocks * 3, paced, cpuUsed, audioMs,
                cpuUsed / audioMs * 100, sum / delays.size(), delays[delays.size()/2],
                delays[delays.size()*95/100], delays[delays.size()*99/100], delays.back());
    }
    engine->command(CommandId::STOP);
    if (bridge && good && argc <= 2) {
        common.input.frameCount = common.output.frameCount = 128;
        auto changed = engine->setParameter(Parameter::make<Parameter::common>(common));
        IEffect::OpenEffectReturn resized;
        good = changed.isOk() && engine->reopen(&resized).isOk();
        if (good) {
            StatusMQ changedStatus(resized.statusMQ);
            DataMQ changedInput(resized.inputDataMQ), changedOutput(resized.outputDataMQ);
            IEffect::Status response{};
            good = engine->command(CommandId::START).isOk() &&
                    changedInput.write(quad.data(), 128 * 4) && flag->wake(wake) == 0 &&
                    changedStatus.readBlocking(&response, 1, 1000000000) &&
                    response.status == 0 && response.fmqConsumed == 512 &&
                    response.fmqProduced == 512 && changedOutput.read(processed.data(), 512);
            engine->command(CommandId::STOP);
        }
        std::printf("%s frame-count reconfiguration and reopen\n", good ? "PASS" : "FAIL");
    }
    engine->close();
    android::hardware::EventFlag::deleteEventFlag(&flag);
    std::printf("%s stereo-input/quad-output transport; channel energy %.9f %.9f %.9f %.9f\n",
            good ? "PASS" : "FAIL", energy[0], energy[1], energy[2], energy[3]);
    return good ? 0 : 6;
}
