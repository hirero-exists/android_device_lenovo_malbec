#include <android-base/logging.h>
#include <android-base/properties.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include <cerrno>
#include <cstring>

namespace {

constexpr char kEnabledProperty[] = "persist.sys.folio.enabled";
constexpr char kHardwareModePath[] = "/proc/folio_case_mode";
constexpr char kCloseMessage[] = "The keyboard close!";
constexpr char kStillCloseMessage[] = "The keyboard still close!";
constexpr char kOpenMessage[] = "The keyboard open!";

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
    if (ioctl(fd, UI_DEV_SETUP, &setup) < 0 || ioctl(fd, UI_DEV_CREATE) < 0) {
        return false;
    }
    return true;
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

bool SetHardwareMode(bool enabled) {
    int fd = open(kHardwareModePath, O_WRONLY | O_CLOEXEC);
    if (fd < 0) {
        return false;
    }
    char value = enabled ? '1' : '0';
    bool success = write(fd, &value, sizeof(value)) == sizeof(value);
    close(fd);
    return success;
}

int ParseState(const char* message) {
    if (std::strstr(message, kStillCloseMessage) != nullptr ||
        std::strstr(message, kCloseMessage) != nullptr) {
        return 1;
    }
    if (std::strstr(message, kOpenMessage) != nullptr) {
        return 0;
    }
    return -1;
}

}

int main() {
    int kmsg = open("/dev/kmsg", O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    if (kmsg < 0) {
        PLOG(ERROR) << "Unable to open /dev/kmsg";
        return 1;
    }

    int uinput = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (uinput < 0 || !ConfigureUinput(uinput)) {
        PLOG(ERROR) << "Unable to create folio input device";
        return 1;
    }

    char message[8192];
    int known_state = -1;
    int emitted_state = 0;
    bool was_enabled = android::base::GetBoolProperty(kEnabledProperty, true);
    if (!SetHardwareMode(was_enabled)) {
        PLOG(ERROR) << "Unable to configure folio hardware mode";
        return 1;
    }

    while (true) {
        pollfd fd = {kmsg, POLLIN, 0};
        int result = poll(&fd, 1, 250);
        if (result < 0 && errno != EINTR) {
            PLOG(ERROR) << "Kernel log poll failed";
            return 1;
        }

        if (result > 0 && (fd.revents & POLLIN) != 0) {
            ssize_t length;
            while ((length = read(kmsg, message, sizeof(message) - 1)) > 0) {
                message[length] = '\0';
                int state = ParseState(message);
                if (state >= 0) {
                    known_state = state;
                }
            }
            if (length < 0 && errno != EAGAIN && errno != EINTR) {
                PLOG(ERROR) << "Kernel log read failed";
                return 1;
            }
        }

        bool enabled = android::base::GetBoolProperty(kEnabledProperty, true);
        if (enabled != was_enabled && !SetHardwareMode(enabled)) {
            PLOG(ERROR) << "Unable to configure folio hardware mode";
            return 1;
        }
        if (!enabled) {
            known_state = -1;
        }
        int target_state = enabled && known_state >= 0 ? known_state : 0;
        if (target_state != emitted_state || enabled != was_enabled) {
            if (!EmitLidState(uinput, target_state != 0)) {
                PLOG(ERROR) << "Unable to emit folio state";
                return 1;
            }
            emitted_state = target_state;
            LOG(INFO) << "Folio state " << (target_state ? "closed" : "open")
                      << ", mode " << (enabled ? "enabled" : "disabled");
        }
        was_enabled = enabled;
    }
}
