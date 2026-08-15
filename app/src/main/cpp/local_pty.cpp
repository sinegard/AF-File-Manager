#include <jni.h>

#include <atomic>
#include <cerrno>
#include <chrono>
#include <csignal>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <memory>
#include <mutex>
#include <poll.h>
#include <string>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <thread>
#include <unordered_map>
#include <unistd.h>
#include <vector>

namespace {

constexpr std::size_t kMaxPathBytes = 4096;
constexpr std::size_t kMaxEnvironmentEntries = 64;
constexpr std::size_t kMaxEnvironmentEntryBytes = 4096;
constexpr int kMinTerminalDimension = 2;
constexpr int kMaxTerminalDimension = 500;
constexpr int kReadPollMillis = 250;
constexpr int kWritePollMillis = 1000;

struct PtySession {
    std::mutex mutex;
    int master_fd = -1;
    pid_t child_pid = -1;
    bool closed = false;
};

std::mutex sessions_mutex;
std::unordered_map<std::int64_t, std::shared_ptr<PtySession>> sessions;
std::atomic<std::int64_t> next_handle{1};

void throw_java(JNIEnv* env, const char* class_name, const std::string& message) {
    jclass exception_class = env->FindClass(class_name);
    if (exception_class != nullptr) {
        env->ThrowNew(exception_class, message.c_str());
    }
}

std::string byte_array_to_string(
    JNIEnv* env,
    jbyteArray value,
    std::size_t maximum,
    const char* field_name
) {
    if (value == nullptr) {
        throw_java(env, "java/lang/IllegalArgumentException", std::string(field_name) + " is required");
        return {};
    }
    const jsize size = env->GetArrayLength(value);
    if (size <= 0 || static_cast<std::size_t>(size) > maximum) {
        throw_java(env, "java/lang/IllegalArgumentException", std::string(field_name) + " has an invalid size");
        return {};
    }
    std::string result(static_cast<std::size_t>(size), '\0');
    env->GetByteArrayRegion(value, 0, size, reinterpret_cast<jbyte*>(result.data()));
    if (env->ExceptionCheck()) return {};
    if (result.find('\0') != std::string::npos) {
        throw_java(env, "java/lang/IllegalArgumentException", std::string(field_name) + " contains NUL");
        return {};
    }
    return result;
}

std::shared_ptr<PtySession> find_session(std::int64_t handle) {
    std::lock_guard<std::mutex> lock(sessions_mutex);
    auto found = sessions.find(handle);
    return found == sessions.end() ? nullptr : found->second;
}

bool valid_dimensions(int rows, int columns) {
    return rows >= kMinTerminalDimension && rows <= kMaxTerminalDimension &&
        columns >= kMinTerminalDimension && columns <= kMaxTerminalDimension;
}

void set_window_size(int fd, int rows, int columns) {
    winsize size{};
    size.ws_row = static_cast<unsigned short>(rows);
    size.ws_col = static_cast<unsigned short>(columns);
    ioctl(fd, TIOCSWINSZ, &size);
}

void wait_for_child(pid_t child_pid) {
    if (child_pid <= 0) return;
    auto wait_bounded = [child_pid](int attempts) {
        for (int i = 0; i < attempts; ++i) {
            int status = 0;
            const pid_t result = waitpid(child_pid, &status, WNOHANG);
            if (result == child_pid || (result < 0 && errno == ECHILD)) return true;
            std::this_thread::sleep_for(std::chrono::milliseconds(25));
        }
        return false;
    };

    kill(-child_pid, SIGHUP);
    if (wait_bounded(12)) return;
    kill(-child_pid, SIGTERM);
    if (wait_bounded(12)) return;
    kill(-child_pid, SIGKILL);
    int status = 0;
    while (waitpid(child_pid, &status, 0) < 0 && errno == EINTR) {}
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_affilemanager_app_terminal_LocalPtyNative_spawn(
    JNIEnv* env,
    jobject,
    jbyteArray shell_bytes,
    jbyteArray working_directory_bytes,
    jobjectArray environment_values,
    jint rows,
    jint columns
) {
    if (!valid_dimensions(rows, columns)) {
        throw_java(env, "java/lang/IllegalArgumentException", "Invalid terminal dimensions");
        return 0;
    }
    const std::string shell = byte_array_to_string(env, shell_bytes, kMaxPathBytes, "shell");
    if (env->ExceptionCheck()) return 0;
    if (shell != "/system/bin/sh") {
        throw_java(env, "java/lang/SecurityException", "Only the Android system shell is allowed");
        return 0;
    }
    const std::string working_directory =
        byte_array_to_string(env, working_directory_bytes, kMaxPathBytes, "working directory");
    if (env->ExceptionCheck()) return 0;
    if (environment_values == nullptr) {
        throw_java(env, "java/lang/IllegalArgumentException", "Environment is required");
        return 0;
    }
    const jsize environment_count = env->GetArrayLength(environment_values);
    if (environment_count < 1 || static_cast<std::size_t>(environment_count) > kMaxEnvironmentEntries) {
        throw_java(env, "java/lang/IllegalArgumentException", "Invalid environment size");
        return 0;
    }

    std::vector<std::string> environment;
    environment.reserve(static_cast<std::size_t>(environment_count));
    for (jsize index = 0; index < environment_count; ++index) {
        auto entry = static_cast<jbyteArray>(env->GetObjectArrayElement(environment_values, index));
        std::string value =
            byte_array_to_string(env, entry, kMaxEnvironmentEntryBytes, "environment entry");
        env->DeleteLocalRef(entry);
        if (env->ExceptionCheck()) return 0;
        const std::size_t separator = value.find('=');
        if (separator == std::string::npos || separator == 0) {
            throw_java(env, "java/lang/IllegalArgumentException", "Invalid environment entry");
            return 0;
        }
        environment.push_back(std::move(value));
    }
    std::vector<char*> environment_pointers;
    environment_pointers.reserve(environment.size() + 1);
    for (std::string& value : environment) environment_pointers.push_back(value.data());
    environment_pointers.push_back(nullptr);

    const int master_fd = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
    if (master_fd < 0) {
        throw_java(env, "java/io/IOException", "Could not open a local pseudo-terminal");
        return 0;
    }
    if (grantpt(master_fd) != 0 || unlockpt(master_fd) != 0) {
        close(master_fd);
        throw_java(env, "java/io/IOException", "Could not prepare a local pseudo-terminal");
        return 0;
    }
    char slave_name[256]{};
    if (ptsname_r(master_fd, slave_name, sizeof(slave_name)) != 0) {
        close(master_fd);
        throw_java(env, "java/io/IOException", "Could not resolve the local pseudo-terminal");
        return 0;
    }
    const long configured_open_max = sysconf(_SC_OPEN_MAX);
    const int open_max = configured_open_max > 0 && configured_open_max <= 1'048'576
        ? static_cast<int>(configured_open_max)
        : 32'768;

    const pid_t child_pid = fork();
    if (child_pid < 0) {
        close(master_fd);
        throw_java(env, "java/io/IOException", "Could not start the Android shell");
        return 0;
    }
    if (child_pid == 0) {
        prctl(PR_SET_PDEATHSIG, SIGHUP);
        if (getppid() == 1) _exit(126);
        if (setsid() < 0) _exit(126);
        const int slave_fd = open(slave_name, O_RDWR);
        if (slave_fd < 0) _exit(126);
        if (ioctl(slave_fd, TIOCSCTTY, 0) < 0) _exit(126);
        set_window_size(slave_fd, rows, columns);
        if (dup2(slave_fd, STDIN_FILENO) < 0 ||
            dup2(slave_fd, STDOUT_FILENO) < 0 ||
            dup2(slave_fd, STDERR_FILENO) < 0) {
            _exit(126);
        }
        if (slave_fd > STDERR_FILENO) close(slave_fd);
        close(master_fd);
        for (int fd = STDERR_FILENO + 1; fd < open_max; ++fd) close(fd);
        umask(0077);
        if (chdir(working_directory.c_str()) != 0) {
            constexpr char message[] = "AF File Manager: working directory is unavailable\r\n";
            write(STDERR_FILENO, message, sizeof(message) - 1);
            _exit(125);
        }
        char* arguments[] = {const_cast<char*>(shell.c_str()), const_cast<char*>("-i"), nullptr};
        execve(shell.c_str(), arguments, environment_pointers.data());
        constexpr char message[] = "AF File Manager: Android shell could not start\r\n";
        write(STDERR_FILENO, message, sizeof(message) - 1);
        _exit(127);
    }

    const int current_flags = fcntl(master_fd, F_GETFL, 0);
    if (current_flags >= 0) fcntl(master_fd, F_SETFL, current_flags | O_NONBLOCK);
    set_window_size(master_fd, rows, columns);

    auto session = std::make_shared<PtySession>();
    session->master_fd = master_fd;
    session->child_pid = child_pid;
    const std::int64_t handle = next_handle.fetch_add(1);
    {
        std::lock_guard<std::mutex> lock(sessions_mutex);
        sessions.emplace(handle, std::move(session));
    }
    return static_cast<jlong>(handle);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_affilemanager_app_terminal_LocalPtyNative_read(
    JNIEnv* env,
    jobject,
    jlong raw_handle,
    jbyteArray destination
) {
    auto session = find_session(static_cast<std::int64_t>(raw_handle));
    if (session == nullptr || destination == nullptr) return -1;
    const jsize capacity = env->GetArrayLength(destination);
    if (capacity <= 0 || capacity > 65536) {
        throw_java(env, "java/lang/IllegalArgumentException", "Invalid terminal read buffer");
        return -1;
    }
    std::vector<jbyte> buffer(static_cast<std::size_t>(capacity));
    std::lock_guard<std::mutex> lock(session->mutex);
    if (session->closed || session->master_fd < 0) return -1;
    pollfd descriptor{session->master_fd, POLLIN, 0};
    int poll_result = 0;
    do {
        poll_result = poll(&descriptor, 1, kReadPollMillis);
    } while (poll_result < 0 && errno == EINTR);
    if (poll_result == 0) return 0;
    if (poll_result < 0) return -1;
    const ssize_t count = ::read(session->master_fd, buffer.data(), buffer.size());
    if (count > 0) {
        env->SetByteArrayRegion(destination, 0, static_cast<jsize>(count), buffer.data());
        return static_cast<jint>(count);
    }
    if (count < 0 && (errno == EAGAIN || errno == EINTR)) return 0;
    return -1;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_affilemanager_app_terminal_LocalPtyNative_write(
    JNIEnv* env,
    jobject,
    jlong raw_handle,
    jbyteArray source,
    jint offset,
    jint length
) {
    auto session = find_session(static_cast<std::int64_t>(raw_handle));
    if (session == nullptr || source == nullptr) return -1;
    const jsize capacity = env->GetArrayLength(source);
    if (offset < 0 || length < 0 || length > 65536 || offset > capacity - length) {
        throw_java(env, "java/lang/IllegalArgumentException", "Invalid terminal write range");
        return -1;
    }
    std::vector<jbyte> buffer(static_cast<std::size_t>(length));
    env->GetByteArrayRegion(source, offset, length, buffer.data());
    if (env->ExceptionCheck()) return -1;
    std::lock_guard<std::mutex> lock(session->mutex);
    if (session->closed || session->master_fd < 0) return -1;
    pollfd descriptor{session->master_fd, POLLOUT, 0};
    int poll_result = 0;
    do {
        poll_result = poll(&descriptor, 1, kWritePollMillis);
    } while (poll_result < 0 && errno == EINTR);
    if (poll_result <= 0) return poll_result;
    const ssize_t count = ::write(session->master_fd, buffer.data(), buffer.size());
    if (count < 0 && (errno == EAGAIN || errno == EINTR)) return 0;
    return count < 0 ? -1 : static_cast<jint>(count);
}

extern "C" JNIEXPORT void JNICALL
Java_com_affilemanager_app_terminal_LocalPtyNative_resize(
    JNIEnv* env,
    jobject,
    jlong raw_handle,
    jint rows,
    jint columns
) {
    if (!valid_dimensions(rows, columns)) {
        throw_java(env, "java/lang/IllegalArgumentException", "Invalid terminal dimensions");
        return;
    }
    auto session = find_session(static_cast<std::int64_t>(raw_handle));
    if (session == nullptr) return;
    std::lock_guard<std::mutex> lock(session->mutex);
    if (session->closed || session->master_fd < 0) return;
    set_window_size(session->master_fd, rows, columns);
    if (session->child_pid > 0) kill(-session->child_pid, SIGWINCH);
}

extern "C" JNIEXPORT void JNICALL
Java_com_affilemanager_app_terminal_LocalPtyNative_close(
    JNIEnv*,
    jobject,
    jlong raw_handle
) {
    const std::int64_t handle = static_cast<std::int64_t>(raw_handle);
    std::shared_ptr<PtySession> session;
    {
        std::lock_guard<std::mutex> lock(sessions_mutex);
        auto found = sessions.find(handle);
        if (found == sessions.end()) return;
        session = found->second;
        sessions.erase(found);
    }
    int master_fd = -1;
    pid_t child_pid = -1;
    {
        std::lock_guard<std::mutex> lock(session->mutex);
        if (session->closed) return;
        session->closed = true;
        master_fd = session->master_fd;
        child_pid = session->child_pid;
        session->master_fd = -1;
        session->child_pid = -1;
    }
    if (master_fd >= 0) close(master_fd);
    wait_for_child(child_pid);
}
