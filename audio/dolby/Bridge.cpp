#include "QuadInput.h"

#include <aidl/android/hardware/audio/effect/BnEffect.h>
#include <fmq/AidlMessageQueue.h>
#include <fmq/EventFlag.h>
#include <system/audio_effects/aidl_effects_utils.h>
#include <dlfcn.h>
#include <android/log.h>
#include <atomic>
#include <mutex>
#include <thread>
#include <vector>

using namespace aidl::android::hardware::audio::effect;
using namespace aidl::android::media::audio::common;
using aidl::android::hardware::common::fmq::SynchronizedReadWrite;
using StatusMQ = android::AidlMessageQueue<IEffect::Status, SynchronizedReadWrite>;
using DataMQ = android::AidlMessageQueue<float, SynchronizedReadWrite>;
using EventFlag = android::hardware::EventFlag;

namespace {
using Create = binder_exception_t (*)(const AudioUuid*, std::shared_ptr<IEffect>*);
using Query = binder_exception_t (*)(const AudioUuid*, Descriptor*);
void* library() {
    static void* handle = dlopen("/vendor/lib64/soundfx/libswdapaidl.so", RTLD_NOW | RTLD_LOCAL);
    return handle;
}
ndk::ScopedAStatus invalid() {
    return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
}
int channels(const AudioChannelLayout& layout) {
    if (layout.getTag() != AudioChannelLayout::layoutMask) return 0;
    int mask = layout.get<AudioChannelLayout::layoutMask>();
    return mask == 3 ? 2 : mask == 0x33 ? 4 : 0;
}
bool valid(const Parameter::Common& c) {
    return channels(c.input.base.channelMask) != 0 &&
            channels(c.input.base.channelMask) == channels(c.output.base.channelMask) &&
            c.input.base.format.pcm == PcmType::FLOAT_32_BIT &&
            c.output.base.format.pcm == PcmType::FLOAT_32_BIT &&
            c.input.base.sampleRate == c.output.base.sampleRate &&
            c.input.base.sampleRate > 0 && c.input.frameCount > 0 &&
            c.input.frameCount <= 8192 && c.output.frameCount == c.input.frameCount;
}
Parameter::Common mapped(Parameter::Common c) {
    c.input.base.channelMask = AudioChannelLayout::make<AudioChannelLayout::layoutMask>(3);
    return c;
}

class Bridge final : public BnEffect {
public:
    explicit Bridge(std::shared_ptr<IEffect> engine) : mEngine(std::move(engine)) {}
    ~Bridge() override {
        command(CommandId::STOP);
        close();
        if (mFlag) EventFlag::deleteEventFlag(&mFlag);
        if (mInnerFlag) EventFlag::deleteEventFlag(&mInnerFlag);
    }
    ndk::ScopedAStatus open(const Parameter::Common& c,
            const std::optional<Parameter::Specific>& specific, OpenEffectReturn* out) override {
        std::lock_guard lock(mMutex);
        if (!valid(c) || mClosing) return invalid();
        if (mState != State::INIT) { describe(out); return ndk::ScopedAStatus::ok(); }
        OpenEffectReturn inner;
        auto result = mEngine->open(mapped(c), specific, &inner);
        if (!result.isOk()) return result;
        if (!innerQueues(inner)) { mEngine->close(); return invalid(); }
        mStatus = std::make_unique<StatusMQ>(1, true);
        if (!mStatus->isValid() || EventFlag::createEventFlag(mStatus->getEventFlagWord(), &mFlag) != 0) {
            mEngine->close(); return invalid();
        }
        if (!dataQueues(c)) { mEngine->close(); return invalid(); }
        mState = State::IDLE;
        mExit = false;
        mReady = true;
        describe(out);
        mWorker = std::thread([this] { run(); });
        return ndk::ScopedAStatus::ok();
    }
    ndk::ScopedAStatus close() override {
        {
            std::lock_guard lock(mMutex);
            if (mState == State::INIT) return ndk::ScopedAStatus::ok();
            if (mState == State::PROCESSING || mClosing) return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_STATE);
            mClosing = true;
            mExit = true;
            mFlag->wake(kEventFlagDataMqNotEmpty);
        }
        if (mWorker.joinable()) mWorker.join();
        std::lock_guard lock(mMutex);
        auto result = mEngine->close();
        EventFlag::deleteEventFlag(&mFlag);
        EventFlag::deleteEventFlag(&mInnerFlag);
        mStatus.reset(); mInput.reset(); mOutput.reset();
        mInnerStatus.reset(); mInnerInput.reset(); mInnerOutput.reset();
        mState = State::INIT;
        mClosing = false;
        return result;
    }
    ndk::ScopedAStatus getDescriptor(Descriptor* out) override { return mEngine->getDescriptor(out); }
    ndk::ScopedAStatus getState(State* out) override {
        std::lock_guard lock(mMutex); *out = mState; return ndk::ScopedAStatus::ok();
    }
    ndk::ScopedAStatus command(CommandId id) override {
        std::lock_guard lock(mMutex);
        if (mState == State::INIT || mClosing) return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_STATE);
        auto result = mEngine->command(id);
        if (!result.isOk()) return result;
        mState = id == CommandId::START ? State::PROCESSING : State::IDLE;
        if (id == CommandId::RESET) {
            mInput->read(mWork.data(), mInput->availableToRead());
            mOutput->read(mWork.data(), mOutput->availableToRead());
            IEffect::Status stale;
            mStatus->read(&stale, mStatus->availableToRead());
            mFault = false;
        }
        return result;
    }
    ndk::ScopedAStatus setParameter(const Parameter& p) override {
        std::lock_guard lock(mMutex);
        if (mClosing) return ndk::ScopedAStatus::fromExceptionCode(EX_ILLEGAL_STATE);
        if (p.getTag() != Parameter::common) return mEngine->setParameter(p);
        const auto& c = p.get<Parameter::common>();
        if (!valid(c) || mState == State::INIT) return invalid();
        if (c == mCommon) return ndk::ScopedAStatus::ok();
        auto result = mEngine->setParameter(Parameter::make<Parameter::common>(mapped(c)));
        if (!result.isOk()) return result;
        OpenEffectReturn inner;
        result = mEngine->reopen(&inner);
        if (!result.isOk() || !innerQueues(inner) || !dataQueues(c)) return invalid();
        mReady = false;
        mFlag->wake(kEventFlagDataMqUpdate);
        return ndk::ScopedAStatus::ok();
    }
    ndk::ScopedAStatus getParameter(const Parameter::Id& id, Parameter* out) override {
        std::lock_guard lock(mMutex);
        if (id.getTag() == Parameter::Id::commonTag &&
                id.get<Parameter::Id::commonTag>() == Parameter::common) {
            *out = Parameter::make<Parameter::common>(mCommon);
            return ndk::ScopedAStatus::ok();
        }
        return mEngine->getParameter(id, out);
    }
    ndk::ScopedAStatus reopen(OpenEffectReturn* out) override {
        std::lock_guard lock(mMutex);
        if (mState == State::INIT || mClosing) return invalid();
        describe(out); mReady = true; return ndk::ScopedAStatus::ok();
    }
private:
    bool innerQueues(const OpenEffectReturn& q) {
        if (mInnerFlag) EventFlag::deleteEventFlag(&mInnerFlag);
        mInnerStatus = std::make_unique<StatusMQ>(q.statusMQ);
        mInnerInput = std::make_unique<DataMQ>(q.inputDataMQ);
        mInnerOutput = std::make_unique<DataMQ>(q.outputDataMQ);
        return mInnerStatus->isValid() && mInnerInput->isValid() && mInnerOutput->isValid() &&
                EventFlag::createEventFlag(mInnerStatus->getEventFlagWord(), &mInnerFlag) == 0;
    }
    bool dataQueues(const Parameter::Common& c) {
        size_t size = c.input.frameCount * channels(c.input.base.channelMask);
        mInput = std::make_unique<DataMQ>(size);
        mOutput = std::make_unique<DataMQ>(size);
        mWork.resize(size); mStereo.resize(c.input.frameCount * 2);
        mCommon = c;
        mFault = false;
        return mInput->isValid() && mOutput->isValid();
    }
    void describe(OpenEffectReturn* out) {
        out->statusMQ = mStatus->dupeDesc();
        out->inputDataMQ = mInput->dupeDesc();
        out->outputDataMQ = mOutput->dupeDesc();
    }
    void run() {
        while (!mExit) {
            uint32_t flags = 0;
            mFlag->wait(kEventFlagDataMqNotEmpty, &flags, 100000000, true);
            if (mExit) break;
            std::lock_guard lock(mMutex);
            if (mState != State::PROCESSING || !mReady) continue;
            size_t count = mInput->availableToRead();
            if (!count) continue;
            const size_t channelCount = channels(mCommon.input.base.channelMask);
            const size_t stereoCount = count / channelCount * 2;
            bool good = !mFault && count <= mWork.size() && count % channelCount == 0 &&
                    mOutput->availableToWrite() >= count && mInput->read(mWork.data(), count);
            if (good) {
                if (channelCount == 4) malbec::audio::FoldQuadToStereo(mWork.data(), mStereo.data(), count / 4);
                const float* data = channelCount == 4 ? mStereo.data() : mWork.data();
                good = mInnerInput->write(data, stereoCount) &&
                        mInnerFlag->wake(kEventFlagDataMqNotEmpty) == 0;
            }
            IEffect::Status status{};
            good = good && mInnerStatus->readBlocking(&status, 1, 100000000) && status.status == 0 &&
                    status.fmqConsumed == static_cast<int>(stereoCount) &&
                    status.fmqProduced == static_cast<int>(count);
            good = good && mInnerOutput->read(mWork.data(), count) && mOutput->write(mWork.data(), count);
            if (!good && !mFault) {
                __android_log_print(ANDROID_LOG_ERROR, "MalbecDapBridge",
                        "PCM transport failed: requested=%zu consumed=%d produced=%d status=%d",
                        stereoCount, status.fmqConsumed, status.fmqProduced, status.status);
                mFault = true;
            }
            IEffect::Status reply{good ? STATUS_OK : STATUS_BAD_VALUE,
                    static_cast<int>(count), good ? static_cast<int>(count) : 0};
            mStatus->writeBlocking(&reply, 1, 100000000);
        }
    }
    std::shared_ptr<IEffect> mEngine;
    std::mutex mMutex;
    State mState = State::INIT;
    Parameter::Common mCommon;
    std::atomic<bool> mExit{false};
    bool mReady = false;
    bool mClosing = false;
    bool mFault = false;
    std::thread mWorker;
    EventFlag* mFlag = nullptr;
    EventFlag* mInnerFlag = nullptr;
    std::unique_ptr<StatusMQ> mStatus, mInnerStatus;
    std::unique_ptr<DataMQ> mInput, mOutput, mInnerInput, mInnerOutput;
    std::vector<float> mWork, mStereo;
};
}

extern "C" binder_exception_t createEffect(const AudioUuid* uuid, std::shared_ptr<IEffect>* out) {
    if (!uuid || !out || !library()) return EX_ILLEGAL_ARGUMENT;
    auto create = reinterpret_cast<Create>(dlsym(library(), "createEffect"));
    std::shared_ptr<IEffect> engine;
    if (!create || create(uuid, &engine) != EX_NONE || !engine) return EX_ILLEGAL_ARGUMENT;
    *out = ndk::SharedRefBase::make<Bridge>(std::move(engine));
    return EX_NONE;
}
extern "C" binder_exception_t queryEffect(const AudioUuid* uuid, Descriptor* out) {
    if (!uuid || !out || !library()) return EX_ILLEGAL_ARGUMENT;
    auto query = reinterpret_cast<Query>(dlsym(library(), "queryEffect"));
    return query ? query(uuid, out) : EX_ILLEGAL_ARGUMENT;
}
extern "C" binder_exception_t destroyEffect(const std::shared_ptr<IEffect>& effect) {
    if (!effect) return EX_ILLEGAL_ARGUMENT;
    effect->command(CommandId::STOP);
    return effect->close().isOk() ? EX_NONE : EX_ILLEGAL_STATE;
}
