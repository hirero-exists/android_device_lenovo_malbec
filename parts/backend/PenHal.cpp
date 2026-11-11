/*
 * Copyright (C) 2026 hirero-exists <hirerokazuoa@gmail.com>
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 * Compatible with GNU General Public License, Version 2.0 (GPLv2) or later
 * pursuant to Section 3.3 of the Mozilla Public License, v. 2.0.
 */
#include "PenHal.h"
#include <aidl/vendor/lenovo/hardware/pen/BnPen.h>
#include <android/binder_manager.h>
#include <android/binder_process.h>
#include <android/binder_ibinder.h>
#include <time.h>
#include <condition_variable>
#include <deque>
#include <mutex>
#include <thread>

namespace malbec {
namespace {
constexpr uid_t kSystemUid = 1000;  // Android AID_SYSTEM.
using aidl::vendor::lenovo::hardware::pen::BnPen;
using aidl::vendor::lenovo::hardware::pen::IPenCallback;
using aidl::vendor::lenovo::hardware::pen::PenEvent;
using ndk::ScopedAStatus;

int64_t Now() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

class PenService final : public BnPen {
  public:
    PenService() : death_(AIBinder_DeathRecipient_new(&PenService::Died)) {
        state_.kind = PenEvent::STATE_DISCONNECTED;
        state_.generation = 1;
        state_.monotonicNanos = Now();
        std::thread(&PenService::Dispatch, this).detach();
    }
    ScopedAStatus registerCallback(const std::shared_ptr<IPenCallback>& cb) override {
        if (AIBinder_getCallingUid() != kSystemUid) return ScopedAStatus::fromExceptionCode(EX_SECURITY);
        if (!cb) return ScopedAStatus::fromExceptionCode(EX_ILLEGAL_ARGUMENT);
        std::lock_guard<std::mutex> lock(mutex_);
        if (callback_) AIBinder_unlinkToDeath(callback_->asBinder().get(), death_.get(), this);
        callback_ = cb;
        queue_.clear();
        if (AIBinder_linkToDeath(cb->asBinder().get(), death_.get(), this) != STATUS_OK) {
            callback_.reset();
            return ScopedAStatus::fromExceptionCode(EX_ILLEGAL_STATE);
        }
        return ScopedAStatus::ok();
    }
    ScopedAStatus unregisterCallback(const std::shared_ptr<IPenCallback>& cb) override {
        if (AIBinder_getCallingUid() != kSystemUid) return ScopedAStatus::fromExceptionCode(EX_SECURITY);
        std::lock_guard<std::mutex> lock(mutex_);
        if (callback_ && cb && callback_->asBinder() == cb->asBinder()) {
            AIBinder_unlinkToDeath(callback_->asBinder().get(), death_.get(), this);
            callback_.reset();
            queue_.clear();
        }
        return ScopedAStatus::ok();
    }
    ScopedAStatus getState(PenEvent* result) override {
        if (AIBinder_getCallingUid() != kSystemUid) return ScopedAStatus::fromExceptionCode(EX_SECURITY);
        std::lock_guard<std::mutex> lock(mutex_);
        *result = state_;
        return ScopedAStatus::ok();
    }
    void Connection(bool connected) {
        std::lock_guard<std::mutex> lock(mutex_);
        ++state_.generation;
        state_.sequence = 0;
        state_.kind = connected ? PenEvent::STATE_CONNECTED : PenEvent::STATE_DISCONNECTED;
        state_.usage = 0;
        state_.pressed = false;
        state_.monotonicNanos = Now();
        queue_.clear();
        Push(state_);
    }
    void Reset() {
        std::lock_guard<std::mutex> lock(mutex_);
        ResetLocked();
    }
    void Gesture(uint32_t usage, bool pressed) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_.kind != PenEvent::STATE_CONNECTED) return;
        EventLocked(PenEvent::GESTURE, static_cast<int32_t>(usage), pressed);
    }
    void Digitizer(int kind, int usage) {
        if (usage < 1 || usage > 2) return;
        std::lock_guard<std::mutex> lock(mutex_);
        EventLocked(kind, usage, true);
    }
    void Proximity(bool in_range) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_.inRange == in_range) return;
        state_.inRange = in_range;
        EventLocked(PenEvent::PROXIMITY, 0, in_range);
    }
  private:
    void EventLocked(int kind, int usage, bool pressed) {
        PenEvent event = state_;
        event.kind = kind;
        event.usage = usage;
        event.pressed = pressed;
        event.sequence = ++state_.sequence;
        event.monotonicNanos = state_.monotonicNanos = Now();
        Push(event);
    }
    static void Died(void* cookie) {
        auto* self = static_cast<PenService*>(cookie);
        std::lock_guard<std::mutex> lock(self->mutex_);
        if (self->callback_ && !AIBinder_isAlive(self->callback_->asBinder().get())) {
            self->callback_.reset();
            self->queue_.clear();
        }
    }
    void ResetLocked() {
        ++state_.generation;
        state_.sequence = 0;
        state_.monotonicNanos = Now();
        queue_.clear();
        PenEvent reset = state_;
        reset.kind = PenEvent::RESET;
        Push(reset);
    }
    void Push(const PenEvent& event) {
        if (!callback_) return;
        if (queue_.size() >= 64) {
            ResetLocked();
            return;
        }
        queue_.push_back(event);
        ready_.notify_one();
    }
    void Dispatch() {
        for (;;) {
            std::shared_ptr<IPenCallback> callback;
            PenEvent event;
            {
                std::unique_lock<std::mutex> lock(mutex_);
                ready_.wait(lock, [this] { return !queue_.empty(); });
                event = queue_.front();
                queue_.pop_front();
                callback = callback_;
            }
            if (callback) callback->onEvent(event);
        }
    }
    std::mutex mutex_;
    std::condition_variable ready_;
    std::deque<PenEvent> queue_;
    std::shared_ptr<IPenCallback> callback_;
    ndk::ScopedAIBinder_DeathRecipient death_;
    PenEvent state_;
};
std::shared_ptr<PenService> service;
}
bool StartPenHal() {
    if (!service) {
        ABinderProcess_setThreadPoolMaxThreadCount(2);
        ABinderProcess_startThreadPool();
        service = ndk::SharedRefBase::make<PenService>();
    }
    return AServiceManager_addService(service->asBinder().get(),
            "vendor.lenovo.hardware.pen.IPen/default") == STATUS_OK;
}
void PenConnection(bool connected) { if (service) service->Connection(connected); }
void PenGesture(uint32_t usage, bool pressed) { if (service) service->Gesture(usage, pressed); }
void PenStreamReset() { if (service) service->Reset(); }
void PenCorner(int side) { if (service) service->Digitizer(PenEvent::CORNER, side); }
void PenDigitizerTap(int button) { if (service) service->Digitizer(PenEvent::DIGITIZER_TAP, button); }
void PenProximity(bool in_range) { if (service) service->Proximity(in_range); }
}
