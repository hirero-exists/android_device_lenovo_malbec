#include "PenGuard.h"
#include "PenGuardFilter.h"

#include <android-base/logging.h>
#include <android-base/properties.h>
#include <android-base/unique_fd.h>
#include <dirent.h>
#include <fcntl.h>
#include <linux/uinput.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include <cerrno>
#include <cstring>
#include <vector>

namespace malbec {
namespace {
using android::base::unique_fd;
constexpr char kActive[] = "sys.malbec.pen.guard_active";
constexpr char kReady[] = "sys.malbec.pen.guard_ready";
constexpr char kConfig[] = "sys.malbec.pen.guard_config";

unique_fd OpenPen(const char* device_name) {
    DIR* directory = opendir("/dev/input");
    if (!directory) return {};
    unique_fd result;
    while (dirent* entry = readdir(directory)) {
        if (std::strncmp(entry->d_name, "event", 5)) continue;
        unique_fd fd(open((std::string("/dev/input/") + entry->d_name).c_str(),
                          O_RDONLY | O_NONBLOCK | O_CLOEXEC));
        char name[256]{};
        if (fd.ok() && ioctl(fd.get(), EVIOCGNAME(sizeof(name)), name) >= 0
                && std::strcmp(name, device_name) == 0) {
            result = std::move(fd);
            break;
        }
    }
    closedir(directory);
    return result;
}

bool ReadState(int fd, PenState& state) {
    unsigned char keys[(KEY_CNT + 7) / 8]{};
    if (ioctl(fd, EVIOCGKEY(sizeof(keys)), keys) < 0) return false;
    for (int key : kPenKeys) state.keys[key] = (keys[key / 8] >> (key % 8)) & 1;
    for (int axis : kPenAxes) {
        input_absinfo info{};
        if (ioctl(fd, EVIOCGABS(axis), &info) < 0) return false;
        state.axes[axis] = info.value;
    }
    return true;
}

unique_fd CreatePen(int source, const std::string& name) {
    unique_fd fd(open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC));
    if (!fd.ok() || ioctl(fd.get(), UI_SET_EVBIT, EV_KEY) < 0
            || ioctl(fd.get(), UI_SET_EVBIT, EV_ABS) < 0
            || ioctl(fd.get(), UI_SET_PROPBIT, INPUT_PROP_DIRECT) < 0) return {};
    for (int key : kPenKeys) {
        if (ioctl(fd.get(), UI_SET_KEYBIT, key) < 0) return {};
    }
    for (int axis : kPenAxes) {
        uinput_abs_setup setup{};
        setup.code = axis;
        if (ioctl(source, EVIOCGABS(axis), &setup.absinfo) < 0
                || ioctl(fd.get(), UI_SET_ABSBIT, axis) < 0
                || ioctl(fd.get(), UI_ABS_SETUP, &setup) < 0) return {};
    }
    uinput_setup setup{};
    setup.id.bustype = BUS_HOST;
    setup.id.vendor = 0x17ef;
    setup.id.product = 0x36;
    setup.id.version = 1;
    std::snprintf(setup.name, sizeof(setup.name), "%s", name.c_str());
    if (ioctl(fd.get(), UI_DEV_SETUP, &setup) < 0
            || ioctl(fd.get(), UI_DEV_CREATE) < 0) return {};
    return fd;
}

bool EmitState(int fd, const PenState& state) {
    input_event events[std::size(kPenAxes) + std::size(kPenKeys) + 1]{};
    size_t index = 0;
    for (int axis : kPenAxes) {
        events[index].type = EV_ABS;
        events[index].code = axis;
        events[index++].value = state.axes[axis];
    }
    for (int key : kPenKeys) {
        events[index].type = EV_KEY;
        events[index].code = key;
        events[index++].value = state.keys[key];
    }
    events[index].type = EV_SYN;
    events[index].code = SYN_REPORT;
    ssize_t written;
    do { written = write(fd, events, sizeof(events)); } while (written < 0 && errno == EINTR);
    return written == sizeof(events);
}
}

void RunPenGuard(const char* device_name, const std::function<void(int)>& button_action) {
    unsigned int generation = 0;
    for (;;) {
        android::base::SetProperty(kActive, "0");
        unique_fd source = OpenPen(device_name);
        if (!source.ok()) { poll(nullptr, 0, 1000); continue; }
        PenState state;
        input_absinfo x_axis{}, y_axis{};
        if (!ReadState(source.get(), state)
                || ioctl(source.get(), EVIOCGABS(ABS_X), &x_axis) < 0
                || ioctl(source.get(), EVIOCGABS(ABS_Y), &y_axis) < 0
                || x_axis.maximum <= x_axis.minimum || y_axis.maximum <= y_axis.minimum) {
            poll(nullptr, 0, 1000);
            continue;
        }
        unique_fd output;
        std::string output_name;
        PenGuardFilter filter;
        bool grabbed = false;
        bool dropped = false;
        bool failed = false;
        bool pending_frame = false;
        while (!failed) {
            PenGuardConfig config;
            const bool valid = config.Parse(android::base::GetProperty(kConfig, ""));
            const bool enabled = valid && config.HasEdges()
                    && android::base::GetBoolProperty("persist.sys.pen.gestures_disabled", true)
                    && android::base::GetBoolProperty("persist.sys.pen.enabled", true);
            if (enabled && !output.ok()) {
                output_name = "Lenovo Pen Guard " + std::to_string(getpid()) + "-"
                        + std::to_string(++generation);
                output = CreatePen(source.get(), output_name);
                if (!output.ok()) {
                    PLOG(ERROR) << "Unable to create pen guard device";
                    break;
                }
            }
            if (!pending_frame && !state.InRange()) {
                if (enabled && !grabbed && output.ok()
                        && android::base::GetProperty(kReady, "") == output_name) {
                    if (ioctl(source.get(), EVIOCGRAB, 1) < 0) {
                        PLOG(ERROR) << "Unable to acquire pen guard input";
                        break;
                    }
                    grabbed = true;
                    if (!ReadState(source.get(), state)) break;
                    if (state.InRange()) {
                        ioctl(source.get(), EVIOCGRAB, 0);
                        grabbed = false;
                    } else {
                        filter = PenGuardFilter{};
                        if (!EmitState(output.get(), state)) break;
                        android::base::SetProperty(kActive, "1");
                    }
                } else if (!enabled && output.ok()) {
                    if (grabbed && ioctl(source.get(), EVIOCGRAB, 0) < 0) break;
                    grabbed = false;
                    output.reset();
                    android::base::SetProperty(kActive, "0");
                }
            }
            pollfd descriptor{source.get(), POLLIN, 0};
            const int result = poll(&descriptor, 1, 100);
            if (result < 0 && errno == EINTR) continue;
            if (result < 0 || (descriptor.revents & (POLLERR | POLLHUP | POLLNVAL))) break;
            if (result == 0) continue;
            input_event events[64];
            ssize_t length;
            while ((length = read(source.get(), events, sizeof(events))) > 0 && !failed) {
                if (length % sizeof(input_event)) { failed = true; break; }
                for (size_t i = 0; i < static_cast<size_t>(length) / sizeof(input_event); ++i) {
                    const auto& event = events[i];
                    if (event.type == EV_SYN && event.code == SYN_DROPPED) {
                        dropped = true;
                        continue;
                    }
                    if (event.type == EV_SYN && event.code == SYN_REPORT) {
                        if (dropped) {
                            if (!ReadState(source.get(), state)) { failed = true; break; }
                            filter.CancelUntilLift(state);
                            dropped = false;
                        }
                        pending_frame = false;
                        if (grabbed && !EmitState(output.get(), filter.Process(
                                state, enabled, config, x_axis, y_axis))) {
                            failed = true;
                            break;
                        }
                    } else if (!dropped) {
                        pending_frame = true;
                        state.Update(event);
                        if (event.type == EV_KEY && event.value == 1 && button_action
                                && android::base::GetBoolProperty("persist.sys.pen.enabled", true)) {
                            if (event.code == BTN_STYLUS) button_action(1);
                            if (event.code == BTN_STYLUS2) button_action(2);
                        }
                    }
                }
            }
            if (length == 0 || (length < 0 && errno != EAGAIN && errno != EINTR)) break;
        }
        if (grabbed) ioctl(source.get(), EVIOCGRAB, 0);
        output.reset();
        source.reset();
        android::base::SetProperty(kActive, "0");
        poll(nullptr, 0, 1000);
    }
}
}
