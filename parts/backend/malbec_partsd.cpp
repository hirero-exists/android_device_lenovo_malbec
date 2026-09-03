/*
 * Copyright (C) 2026 hirero-exists <hirerokazuoa@gmail.com>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Compatible with GNU General Public License, Version 2.0 (GPLv2) or later
 * pursuant to Section 3.3 of the Mozilla Public License, v. 2.0.
 */

#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/properties.h>
#include <android-base/strings.h>

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
#include <cmath>
#include <sstream>
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

constexpr char kEdgeGridZoneProperty[] = "persist.sys.touch.edge_grid_zone";
constexpr char kEdgeGridZoneAppliedProperty[] =
        "sys.malbec.touch.edge_grid_zone_applied";
constexpr char kPerfOverlayActiveProperty[] = "sys.malbec.perf.overlay_active";
constexpr char kPerfCpuUsageProperty[] = "sys.malbec.perf.cpu_usage";
constexpr char kPerfCpuFreqProperty[] = "sys.malbec.perf.cpu_freq_mhz";
constexpr char kPerfCpuTempProperty[] = "sys.malbec.perf.cpu_temp_c";
constexpr char kPerfCpuPowerProperty[] = "sys.malbec.perf.cpu_power_mw";
constexpr char kPerfGpuUsageProperty[] = "sys.malbec.perf.gpu_usage";
constexpr char kPerfGpuFreqProperty[] = "sys.malbec.perf.gpu_freq_mhz";
constexpr char kPerfGpuTempProperty[] = "sys.malbec.perf.gpu_temp_c";
constexpr char kPerfGpuPowerProperty[] = "sys.malbec.perf.gpu_power_mw";
constexpr char kPerfSocPowerProperty[] = "sys.malbec.perf.soc_power_mw";
constexpr char kPerfPowerProperty[] = "sys.malbec.perf.power_mw";


constexpr char kFolioModePath[] = "/proc/folio_case_mode";
constexpr char kPenModePath[] = "/proc/pen_type";
constexpr char kPenWakePath[] = "/proc/pen_wakeup_mode";
constexpr char kHighReportRatePath[] = "/proc/HighReportRate";
constexpr char kGameEdgePath[] = "/proc/game_edge";
constexpr char kEdgeGridZonePath[] = "/proc/edge_grid_zone";
constexpr char kGpuLoadPath[] = "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load";
constexpr char kGpuClockPath[] = "/sys/class/kgsl/kgsl-3d0/clock_mhz";
constexpr char kGpuTempPath[] = "/sys/class/kgsl/kgsl-3d0/temp";
constexpr char kCpuFreqPaths[][64] = {
        "/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq",
        "/sys/devices/system/cpu/cpu2/cpufreq/scaling_cur_freq",
        "/sys/devices/system/cpu/cpu5/cpufreq/scaling_cur_freq",
        "/sys/devices/system/cpu/cpu7/cpufreq/scaling_cur_freq",
};

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

struct CpuTicks {
    unsigned long long user = 0;
    unsigned long long nice = 0;
    unsigned long long system = 0;
    unsigned long long idle = 0;
    unsigned long long iowait = 0;
    unsigned long long irq = 0;
    unsigned long long softirq = 0;
    unsigned long long steal = 0;
};

CpuTicks g_prev_ticks = {};
bool g_has_prev_ticks = false;
std::string g_cpu_temp_path;
std::string g_gpu_temp_path;

std::string FindThermalZone(const std::string& target_type) {
    for (int index = 0; index < 90; ++index) {
        std::string type_path =
                "/sys/class/thermal/thermal_zone" + std::to_string(index) + "/type";
        std::string type;
        if (android::base::ReadFileToString(type_path, &type)) {
            type = android::base::Trim(type);
            if (type == target_type) {
                return "/sys/class/thermal/thermal_zone" + std::to_string(index) +
                        "/temp";
            }
        }
    }
    return "";
}

int CalculateCpuUsage() {
    std::string stat_content;
    if (!android::base::ReadFileToString("/proc/stat", &stat_content)) {
        return 0;
    }
    std::istringstream stream(stat_content);
    std::string line;
    if (!std::getline(stream, line) || line.rfind("cpu ", 0) != 0) {
        return 0;
    }
    CpuTicks curr = {};
    std::sscanf(line.c_str(), "cpu %llu %llu %llu %llu %llu %llu %llu %llu",
            &curr.user, &curr.nice, &curr.system, &curr.idle, &curr.iowait,
            &curr.irq, &curr.softirq, &curr.steal);
    if (!g_has_prev_ticks) {
        g_prev_ticks = curr;
        g_has_prev_ticks = true;
        return 0;
    }
    unsigned long long prev_idle = g_prev_ticks.idle + g_prev_ticks.iowait;
    unsigned long long curr_idle = curr.idle + curr.iowait;
    unsigned long long prev_total = prev_idle + g_prev_ticks.user +
            g_prev_ticks.nice + g_prev_ticks.system + g_prev_ticks.irq +
            g_prev_ticks.softirq + g_prev_ticks.steal;
    unsigned long long curr_total = curr_idle + curr.user + curr.nice +
            curr.system + curr.irq + curr.softirq + curr.steal;
    g_prev_ticks = curr;
    if (curr_total <= prev_total) {
        return 0;
    }
    unsigned long long total_diff = curr_total - prev_total;
    unsigned long long idle_diff = curr_idle - prev_idle;
    if (total_diff == 0 || idle_diff > total_diff) {
        return 0;
    }
    return static_cast<int>((total_diff - idle_diff) * 100 / total_diff);
}

void PublishPerformanceTelemetry() {
    if (g_cpu_temp_path.empty()) {
        g_cpu_temp_path = FindThermalZone("ap-therm");
        if (g_cpu_temp_path.empty()) {
            g_cpu_temp_path = FindThermalZone("sys-therm-0");
        }
        if (g_cpu_temp_path.empty()) {
            g_cpu_temp_path = FindThermalZone("cpuss-0-0");
        }
    }
    if (g_gpu_temp_path.empty()) {
        g_gpu_temp_path = FindThermalZone("sys-therm-0");
        if (g_gpu_temp_path.empty()) {
            g_gpu_temp_path = FindThermalZone("ap-therm");
        }
    }

    int cpu_usage = CalculateCpuUsage();
    int c0_freq = ReadInt(kCpuFreqPaths[0], 0) / 1000;
    int c1_freq = ReadInt(kCpuFreqPaths[1], 0) / 1000;
    int c2_freq = ReadInt(kCpuFreqPaths[2], 0) / 1000;
    int c3_freq = ReadInt(kCpuFreqPaths[3], 0) / 1000;
    int cpu_freq_mhz = std::max(std::max(c0_freq, c1_freq), std::max(c2_freq, c3_freq));
    int cpu_temp_c = !g_cpu_temp_path.empty()
            ? ReadInt(g_cpu_temp_path.c_str(), 0) / 1000 : 0;

    int gpu_busy = ReadInt("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", -1);
    if (gpu_busy < 0) {
        gpu_busy = ReadInt(kGpuLoadPath, 0);
    }
    gpu_busy = std::max(0, std::min(100, gpu_busy));
    int gpu_freq_mhz = ReadInt(kGpuClockPath, 0);
    int gpu_temp_c = !g_gpu_temp_path.empty()
            ? ReadInt(g_gpu_temp_path.c_str(), 0) / 1000
            : ReadInt(kGpuTempPath, 0) / 1000;

    double load_ratio = static_cast<double>(cpu_usage) / 100.0;
    double r0 = std::max(0.1, static_cast<double>(c0_freq) / 2016.0);
    double r1 = std::max(0.1, static_cast<double>(c1_freq) / 3014.0);
    double r2 = std::max(0.1, static_cast<double>(c2_freq) / 2803.0);
    double r3 = std::max(0.1, static_cast<double>(c3_freq) / 3206.0);
    int cpu_power_mw = static_cast<int>(350.0 + load_ratio * (
            r0 * r0 * 500.0 +
            r1 * r1 * 1600.0 +
            r2 * r2 * 1400.0 +
            r3 * r3 * 2000.0));

    double gpu_load_ratio = static_cast<double>(gpu_busy) / 100.0;
    double gpu_r = std::max(0.1, static_cast<double>(gpu_freq_mhz) / 1150.0);
    int gpu_power_mw = static_cast<int>(150.0 + gpu_load_ratio * gpu_r * gpu_r * 4500.0);

    int usb_online = ReadInt(kUsbOnlinePath, 0);
    long long power_mw = 0;
    if (usb_online == 1) {
        long long voltage_uv = ReadInt(kUsbVoltagePath, 0);
        long long current_ua = ReadInt(kUsbCurrentPath, 0);
        power_mw = (voltage_uv * current_ua) / 1000000000LL;
    } else {
        long long voltage_uv = std::abs(ReadInt(kBatteryVoltagePath, 0));
        long long current_ua = std::abs(ReadInt(kBatteryCurrentPath, 0));
        power_mw = (voltage_uv * current_ua) / 1000000000LL;
    }

    int soc_power_mw = cpu_power_mw + gpu_power_mw;

    SetIntProperty(kPerfCpuUsageProperty, cpu_usage);
    SetIntProperty(kPerfCpuFreqProperty, cpu_freq_mhz);
    SetIntProperty(kPerfCpuTempProperty, cpu_temp_c);
    SetIntProperty(kPerfCpuPowerProperty, cpu_power_mw);

    SetIntProperty(kPerfGpuUsageProperty, gpu_busy);
    SetIntProperty(kPerfGpuFreqProperty, gpu_freq_mhz);
    SetIntProperty(kPerfGpuTempProperty, gpu_temp_c);
    SetIntProperty(kPerfGpuPowerProperty, gpu_power_mw);

    SetIntProperty(kPerfSocPowerProperty, soc_power_mw);
    SetIntProperty(kPerfPowerProperty, static_cast<int>(power_mw));
    SetIntProperty("sys.malbec.power.usb_online", usb_online);
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
    std::string edge_grid_zone_applied;
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

        if (android::base::GetBoolProperty(kPerfOverlayActiveProperty, false)) {
            PublishPerformanceTelemetry();
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
        bool new_high_report =
                android::base::GetBoolProperty(kHighReportRateProperty, false);
        bool new_game_edge =
                android::base::GetBoolProperty(kGameEdgeProperty, false);

        if (++hardware_tick >= 4) {
            hardware_tick = 0;
            ApplyMode(kFolioModePath, new_folio_enabled, &folio_applied, nullptr);
            ApplyMode(kPenModePath, new_pen_enabled, &pen_applied, nullptr);
            ApplyMode(kPenWakePath, true, &pen_wakeup_applied, nullptr);
            ApplyMode(kHighReportRatePath, new_high_report, &high_report_applied,
                    kHighReportRateAppliedProperty);
            ApplyMode(kGameEdgePath, new_game_edge, &game_edge_applied,
                    kGameEdgeAppliedProperty);

            std::string new_edge_grid =
                    android::base::GetProperty(kEdgeGridZoneProperty, "");
            if (!new_edge_grid.empty() && new_edge_grid != edge_grid_zone_applied) {
                if (android::base::WriteStringToFile(new_edge_grid, kEdgeGridZonePath)) {
                    edge_grid_zone_applied = new_edge_grid;
                    android::base::SetProperty(kEdgeGridZoneAppliedProperty, "1");
                }
            }


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
