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
    int emitted_state = 0;

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

        bool new_folio_enabled =
                android::base::GetBoolProperty(kFolioEnabledProperty, true);
        if (new_folio_enabled != folio_enabled
                && !WriteMode(kFolioModePath, new_folio_enabled)) {
            PLOG(ERROR) << "Unable to configure folio mode";
            return 1;
        }

        int hall_state = android::base::GetIntProperty(kFolioHallStateProperty, -1);
        int known_state = hall_state >= 0 ? hall_state : kmsg_state;
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
