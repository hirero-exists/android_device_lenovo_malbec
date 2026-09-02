/*
 * Copyright (C) 2026 hirero-exists <hirerokazuoa@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/properties.h>
#include <dirent.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>

#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

namespace {

constexpr char kFolioEnabledProperty[] = "persist.sys.folio.enabled";
constexpr char kFolioHallStateProperty[] = "sys.malbec.folio.closed";
constexpr char kPenEnabledProperty[] = "persist.sys.pen.enabled";
constexpr char kPenWakeProperty[] = "persist.sys.pen.wakeup";
constexpr char kHighReportRateProperty[] = "persist.sys.touch.high_report_rate";
constexpr char kGameEdgeProperty[] = "persist.sys.touch.game_edge";
constexpr char kHighReportRateAppliedProperty[] =
        "sys.malbec.touch.high_report_rate_applied";
constexpr char kGameEdgeAppliedProperty[] = "sys.malbec.touch.game_edge_applied";
constexpr char kBypassRequestProperty[] = "sys.malbec.bypass.requested";
constexpr char kBypassHeartbeatProperty[] = "sys.malbec.bypass.heartbeat";
constexpr char kBypassActiveProperty[] = "sys.malbec.bypass.active";
constexpr char kBypassStateProperty[] = "sys.malbec.bypass.state";

constexpr char kFolioModePath[] = "/proc/folio_case_mode";
constexpr char kPenModePath[] = "/proc/pen_type";
constexpr char kPenWakePath[] = "/proc/pen_wakeup_mode";
constexpr char kHighReportRatePath[] = "/proc/HighReportRate";
constexpr char kGameEdgePath[] = "/proc/game_edge";
constexpr char kChargingEnabledPath[] =
        "/sys/class/power_supply/battery/charging_enabled";
constexpr char kInputSuspendPath[] =
        "/sys/class/power_supply/battery/input_suspend";
constexpr char kUsbOnlinePath[] = "/sys/class/power_supply/usb/online";
constexpr char kUsbVoltagePath[] = "/sys/class/power_supply/usb/voltage_now";
constexpr char kUsbCurrentPath[] = "/sys/class/power_supply/usb/current_now";
constexpr char kUsbVoltageMaxPath[] = "/sys/class/power_supply/usb/voltage_max";
constexpr char kUsbCurrentMaxPath[] = "/sys/class/power_supply/usb/current_max";
constexpr char kUsbTypePath[] = "/sys/class/power_supply/usb/usb_type";
constexpr char kBatteryCapacityPath[] =
        "/sys/class/power_supply/battery/capacity";
constexpr char kBatteryVoltagePath[] =
        "/sys/class/power_supply/battery/voltage_now";
constexpr char kBatteryCurrentPath[] =
        "/sys/class/power_supply/battery/current_now";
constexpr char kBatteryTempPath[] = "/sys/class/power_supply/battery/temp";

constexpr char kPowerUsbOnlineProperty[] = "sys.malbec.power.usb_online";
constexpr char kPowerUsbVoltageProperty[] = "sys.malbec.power.usb_voltage_uv";
constexpr char kPowerUsbCurrentProperty[] = "sys.malbec.power.usb_current_ua";
constexpr char kPowerUsbVoltageMaxProperty[] =
        "sys.malbec.power.usb_voltage_max_uv";
constexpr char kPowerUsbCurrentMaxProperty[] =
        "sys.malbec.power.usb_current_max_ua";
constexpr char kPowerUsbTypeProperty[] = "sys.malbec.power.usb_type";
constexpr char kPowerBatteryCapacityProperty[] =
        "sys.malbec.power.battery_capacity";
constexpr char kPowerBatteryVoltageProperty[] =
        "sys.malbec.power.battery_voltage_uv";
constexpr char kPowerBatteryCurrentProperty[] =
        "sys.malbec.power.battery_current_ua";
constexpr char kPowerBatteryTempProperty[] =
        "sys.malbec.power.battery_temp_tenth_c";
constexpr char kPowerChargingEnabledProperty[] =
        "sys.malbec.power.charging_enabled";
constexpr char kPowerInputSuspendProperty[] =
        "sys.malbec.power.battery_input_suspend";

constexpr char kHallInputName[] = "hall_irq";
constexpr int kHallOpenKey = 750;
constexpr int kHallCloseKey = 751;

bool ConfigureUinput(int fd) {
    if (ioctl(fd, UI_SET_EVBIT, EV_SW) < 0 || ioctl(fd, UI_SET_SWBIT, SW_LID) < 0) {
        return false;
    }

    uinput_setup setup = {};
    setup.id.bustype = BUS_HOST;
    setup.id.vendor = 0x17ef;
    setup.id.product = 0x35;
    setup.id.version = 1;
    std::strncpy(setup.name, "Lenovo Folio", UINPUT_MAX_NAME_SIZE - 1);
    return ioctl(fd, UI_DEV_SETUP, &setup) >= 0 && ioctl(fd, UI_DEV_CREATE) >= 0;
}

bool EmitLidState(int fd, bool closed) {
    input_event events[2] = {};
    events[0].type = EV_SW;
    events[0].code = SW_LID;
    events[0].value = closed ? 1 : 0;
    events[1].type = EV_SYN;
    events[1].code = SYN_REPORT;
    return write(fd, events, sizeof(events)) == sizeof(events);
}

bool WriteMode(const char* path, bool enabled) {
    int fd = open(path, O_WRONLY | O_CLOEXEC);
    if (fd < 0) {
        PLOG(WARNING) << "Unable to open " << path;
        return false;
    }
    char value = enabled ? '1' : '0';
    bool success = write(fd, &value, sizeof(value)) == sizeof(value);
    if (!success) {
        PLOG(WARNING) << "Unable to write " << path;
    }
    close(fd);
    return success;
}

int ReadInt(const char* path, int fallback) {
    std::string value;
    if (!android::base::ReadFileToString(path, &value)) {
        return fallback;
    }
    char* end = nullptr;
    errno = 0;
    long parsed = std::strtol(value.c_str(), &end, 10);
    if (errno != 0 || end == value.c_str()) {
        return fallback;
    }
    return static_cast<int>(parsed);
}

std::string ReadUsbType() {
    std::string value;
    if (!android::base::ReadFileToString(kUsbTypePath, &value)) {
        return "Unknown";
    }
    size_t open = value.find('[');
    size_t close = value.find(']', open == std::string::npos ? 0 : open + 1);
    if (open != std::string::npos && close != std::string::npos && close > open + 1) {
        return value.substr(open + 1, close - open - 1);
    }
    while (!value.empty() && (value.back() == '\n' || value.back() == '\r')) {
        value.pop_back();
    }
    return value.empty() ? "Unknown" : value;
}

void SetIntProperty(const char* property, int value) {
    android::base::SetProperty(property, std::to_string(value));
}

void ApplyMode(const char* path, bool desired, int* applied,
        const char* applied_property) {
    int requested = desired ? 1 : 0;
    if (*applied == requested) {
        return;
    }
    if (WriteMode(path, desired)) {
        *applied = requested;
        if (applied_property != nullptr) {
            SetIntProperty(applied_property, requested);
        }
    }
}

void PublishPowerTelemetry() {
    SetIntProperty(kPowerUsbOnlineProperty, ReadInt(kUsbOnlinePath, 0));
    SetIntProperty(kPowerUsbVoltageProperty, ReadInt(kUsbVoltagePath, 0));
    SetIntProperty(kPowerUsbCurrentProperty, ReadInt(kUsbCurrentPath, 0));
    SetIntProperty(kPowerUsbVoltageMaxProperty, ReadInt(kUsbVoltageMaxPath, 0));
    SetIntProperty(kPowerUsbCurrentMaxProperty, ReadInt(kUsbCurrentMaxPath, 0));
    android::base::SetProperty(kPowerUsbTypeProperty, ReadUsbType());
    SetIntProperty(kPowerBatteryCapacityProperty, ReadInt(kBatteryCapacityPath, 0));
    SetIntProperty(kPowerBatteryVoltageProperty, ReadInt(kBatteryVoltagePath, 0));
    SetIntProperty(kPowerBatteryCurrentProperty, ReadInt(kBatteryCurrentPath, 0));
    SetIntProperty(kPowerBatteryTempProperty, ReadInt(kBatteryTempPath, 0));
    SetIntProperty(kPowerChargingEnabledProperty, ReadInt(kChargingEnabledPath, 1));
    SetIntProperty(kPowerInputSuspendProperty, ReadInt(kInputSuspendPath, 0));
}

int BootSeconds() {
    timespec time = {};
    if (clock_gettime(CLOCK_BOOTTIME, &time) != 0) {
        return 0;
    }
    return static_cast<int>(time.tv_sec);
}

void SetBypassState(bool active, int state) {
    android::base::SetProperty(kBypassActiveProperty, active ? "1" : "0");
    SetIntProperty(kBypassStateProperty, state);
}

int OpenHallInput() {
    DIR* directory = opendir("/dev/input");
    if (directory == nullptr) {
        return -1;
    }

    int result = -1;
    dirent* entry;
    while ((entry = readdir(directory)) != nullptr) {
        if (std::strncmp(entry->d_name, "event", 5) != 0) {
            continue;
        }

        char path[64];
        std::snprintf(path, sizeof(path), "/dev/input/%s", entry->d_name);
        int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        if (fd < 0) {
            continue;
        }

        char name[256] = {};
        if (ioctl(fd, EVIOCGNAME(sizeof(name)), name) >= 0
                && std::strcmp(name, kHallInputName) == 0) {
            result = fd;
            break;
        }
        close(fd);
    }
    closedir(directory);
    return result;
}

}

int main() {
    int hall_input = OpenHallInput();
    if (hall_input >= 0) {
        LOG(INFO) << "Opened hall input device";
    }

    int uinput = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (uinput < 0 || !ConfigureUinput(uinput)) {
        PLOG(ERROR) << "Unable to create folio input device";
        return 1;
    }

    bool folio_enabled = android::base::GetBoolProperty(kFolioEnabledProperty, true);
    int folio_applied = -1;
    int pen_applied = -1;
    int pen_wakeup_applied = -1;
    int high_report_applied = -1;
    int game_edge_applied = -1;
    bool bypass_active = android::base::GetBoolProperty(kBypassActiveProperty, false);

    int input_state = -1;
    int emitted_state = 0;
    int hall_retry = 0;
    int hardware_tick = 4;

    while (true) {
        if (hall_input < 0 && hall_retry-- <= 0) {
            hall_input = OpenHallInput();
            hall_retry = 4;
            if (hall_input >= 0) {
                LOG(INFO) << "Opened hall input device";
            }
        }

        pollfd fds[1] = {
                {hall_input, POLLIN, 0},
        };
        int result = poll(fds, hall_input >= 0 ? 1 : 0, 250);
        if (result < 0 && errno != EINTR) {
            PLOG(ERROR) << "Folio input poll failed";
            return 1;
        }

        if (result > 0 && hall_input >= 0 && (fds[0].revents & POLLIN) != 0) {
            input_event events[16];
            ssize_t length;
            while ((length = read(hall_input, events, sizeof(events))) > 0) {
                size_t count = static_cast<size_t>(length) / sizeof(input_event);
                for (size_t index = 0; index < count; ++index) {
                    if (events[index].type != EV_KEY || events[index].value != 1) {
                        continue;
                    }
                    if (events[index].code == kHallCloseKey) {
                        input_state = 1;
                    } else if (events[index].code == kHallOpenKey) {
                        input_state = 0;
                    }
                }
            }
            if (length < 0 && errno != EAGAIN && errno != EINTR) {
                PLOG(ERROR) << "Hall input read failed";
                close(hall_input);
                hall_input = -1;
            }
        }

        bool new_folio_enabled =
                android::base::GetBoolProperty(kFolioEnabledProperty, true);

        int hall_state = android::base::GetIntProperty(kFolioHallStateProperty, -1);
        int known_state = input_state >= 0 ? input_state : hall_state;
        int target_state = new_folio_enabled && known_state >= 0 ? known_state : 0;
        if (target_state != emitted_state || new_folio_enabled != folio_enabled) {
            if (!EmitLidState(uinput, target_state != 0)) {
                PLOG(ERROR) << "Unable to emit folio state";
                return 1;
            }
            emitted_state = target_state;
            LOG(INFO) << "Folio state " << (target_state ? "closed" : "open")
                      << ", mode " << (new_folio_enabled ? "enabled" : "disabled");
        }
        folio_enabled = new_folio_enabled;

        bool new_pen_enabled = android::base::GetBoolProperty(kPenEnabledProperty, true);
        bool new_pen_wakeup = android::base::GetBoolProperty(kPenWakeProperty, false);
        bool new_high_report =
                android::base::GetBoolProperty(kHighReportRateProperty, false);
        bool new_game_edge =
                android::base::GetBoolProperty(kGameEdgeProperty, false);

        if (++hardware_tick >= 4) {
            hardware_tick = 0;
            ApplyMode(kFolioModePath, new_folio_enabled, &folio_applied, nullptr);
            ApplyMode(kPenModePath, new_pen_enabled, &pen_applied, nullptr);
            ApplyMode(kPenWakePath, new_pen_enabled && new_pen_wakeup,
                    &pen_wakeup_applied, nullptr);
            ApplyMode(kHighReportRatePath, new_high_report, &high_report_applied,
                    kHighReportRateAppliedProperty);
            ApplyMode(kGameEdgePath, new_game_edge, &game_edge_applied,
                    kGameEdgeAppliedProperty);

            PublishPowerTelemetry();
            bool bypass_requested =
                    android::base::GetBoolProperty(kBypassRequestProperty, false);
            int usb_online = ReadInt(kUsbOnlinePath, 0);
            int heartbeat = android::base::GetIntProperty(kBypassHeartbeatProperty, 0);
            int heartbeat_age = BootSeconds() - heartbeat;

            if (bypass_requested) {
                if (usb_online != 1) {
                    if (bypass_active && WriteMode(kChargingEnabledPath, true)) {
                        bypass_active = false;
                    }
                    android::base::SetProperty(kBypassRequestProperty, "0");
                    SetBypassState(bypass_active, 2);
                } else if (heartbeat <= 0 || heartbeat_age < 0 || heartbeat_age > 20) {
                    if (bypass_active && WriteMode(kChargingEnabledPath, true)) {
                        bypass_active = false;
                    }
                    android::base::SetProperty(kBypassRequestProperty, "0");
                    SetBypassState(bypass_active, 4);
                } else if (!bypass_active) {
                    if (WriteMode(kChargingEnabledPath, false)
                            && ReadInt(kChargingEnabledPath, 1) == 0) {
                        bypass_active = true;
                        SetBypassState(true, 1);
                    } else {
                        android::base::SetProperty(kBypassRequestProperty, "0");
                        SetBypassState(false, 3);
                    }
                } else if (ReadInt(kChargingEnabledPath, 1) != 0) {
                    bypass_active = false;
                    android::base::SetProperty(kBypassRequestProperty, "0");
                    SetBypassState(false, 5);
                } else {
                    SetBypassState(true, 1);
                }
            } else if (bypass_active) {
                if (WriteMode(kChargingEnabledPath, true)
                        && ReadInt(kChargingEnabledPath, 0) == 1) {
                    bypass_active = false;
                    SetBypassState(false, 0);
                } else {
                    SetBypassState(true, 3);
                }
            } else if (android::base::GetIntProperty(kBypassStateProperty, 0) == 1) {
                SetBypassState(false, 0);
            }
        }

        folio_enabled = new_folio_enabled;
    }
}
