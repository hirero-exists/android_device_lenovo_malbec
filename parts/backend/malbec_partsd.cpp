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

#include <android-base/logging.h>
#include <android-base/properties.h>
#include <dirent.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include <cerrno>
#include <cstdio>
#include <cstring>

namespace {

constexpr char kFolioEnabledProperty[] = "persist.sys.folio.enabled";
constexpr char kFolioHallStateProperty[] = "sys.malbec.folio.closed";
constexpr char kPenEnabledProperty[] = "persist.sys.pen.enabled";
constexpr char kPenWakeProperty[] = "persist.sys.pen.wakeup";
constexpr char kFolioModePath[] = "/proc/folio_case_mode";
constexpr char kPenModePath[] = "/proc/pen_type";
constexpr char kPenWakePath[] = "/proc/pen_wakeup_mode";
constexpr char kCloseMessage[] = "The keyboard close!";
constexpr char kStillCloseMessage[] = "The keyboard still close!";
constexpr char kOpenMessage[] = "The keyboard open!";
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
        return false;
    }
    char value = enabled ? '1' : '0';
    bool success = write(fd, &value, sizeof(value)) == sizeof(value);
    close(fd);
    return success;
}

int ParseFolioState(const char* message) {
    if (std::strstr(message, kStillCloseMessage) != nullptr
            || std::strstr(message, kCloseMessage) != nullptr) {
        return 1;
    }
    if (std::strstr(message, kOpenMessage) != nullptr) {
        return 0;
    }
    return -1;
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
    int kmsg = open("/dev/kmsg", O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    if (kmsg < 0) {
        PLOG(ERROR) << "Unable to open /dev/kmsg";
        return 1;
    }
    if (lseek(kmsg, 0, SEEK_END) < 0) {
        PLOG(ERROR) << "Unable to seek /dev/kmsg";
        return 1;
    }

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
    bool pen_enabled = android::base::GetBoolProperty(kPenEnabledProperty, true);
    bool pen_wakeup = android::base::GetBoolProperty(kPenWakeProperty, false);
    if (!WriteMode(kFolioModePath, folio_enabled)
            || !WriteMode(kPenModePath, pen_enabled)
            || !WriteMode(kPenWakePath, pen_wakeup)) {
        PLOG(ERROR) << "Unable to initialize Lenovo hardware modes";
        return 1;
    }

    char message[8192];
    int kmsg_state = -1;
    int input_state = -1;
    int emitted_state = 0;
    int hall_retry = 0;

    while (true) {
        if (hall_input < 0 && hall_retry-- <= 0) {
            hall_input = OpenHallInput();
            hall_retry = 4;
            if (hall_input >= 0) {
                LOG(INFO) << "Opened hall input device";
            }
        }

        pollfd fds[2] = {
                {kmsg, POLLIN, 0},
                {hall_input, POLLIN, 0},
        };
        int result = poll(fds, hall_input >= 0 ? 2 : 1, 250);
        if (result < 0 && errno != EINTR) {
            PLOG(ERROR) << "Folio input poll failed";
            return 1;
        }

        if (result > 0 && (fds[0].revents & POLLIN) != 0) {
            ssize_t length;
            while ((length = read(kmsg, message, sizeof(message) - 1)) > 0) {
                message[length] = '\0';
                int state = ParseFolioState(message);
                if (state >= 0) {
                    kmsg_state = state;
                }
            }
            if (length < 0 && errno != EAGAIN && errno != EINTR) {
                PLOG(ERROR) << "Kernel log read failed";
                return 1;
            }
        }

        if (result > 0 && hall_input >= 0 && (fds[1].revents & POLLIN) != 0) {
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
                return 1;
            }
        }

        bool new_folio_enabled =
                android::base::GetBoolProperty(kFolioEnabledProperty, true);
        if (new_folio_enabled != folio_enabled
                && !WriteMode(kFolioModePath, new_folio_enabled)) {
            PLOG(ERROR) << "Unable to configure folio mode";
            return 1;
        }

        int hall_state = android::base::GetIntProperty(kFolioHallStateProperty, -1);
        int known_state = input_state >= 0 ? input_state
                : (hall_state >= 0 ? hall_state : kmsg_state);
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
        if (new_pen_enabled != pen_enabled) {
            if (!WriteMode(kPenModePath, new_pen_enabled)) {
                PLOG(ERROR) << "Unable to configure pen mode";
                return 1;
            }
            pen_enabled = new_pen_enabled;
        }

        bool new_pen_wakeup = android::base::GetBoolProperty(kPenWakeProperty, false);
        if (new_pen_wakeup != pen_wakeup) {
            if (!WriteMode(kPenWakePath, new_pen_wakeup)) {
                PLOG(ERROR) << "Unable to configure pen wake mode";
                return 1;
            }
            pen_wakeup = new_pen_wakeup;
        }
    }
}
