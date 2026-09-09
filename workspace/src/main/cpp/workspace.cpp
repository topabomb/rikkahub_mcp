#include <jni.h>
#include <fcntl.h>
#include <dirent.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <linux/fs.h>
#include <unistd.h>
#include <algorithm>
#include <atomic>
#include <cerrno>
#include <climits>
#include <cstdio>
#include <cstring>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
class Fd {
public:
    explicit Fd(int value = -1) : value(value) {}
    ~Fd() { if (value >= 0) ::close(value); }
    Fd(const Fd&) = delete;
    Fd& operator=(const Fd&) = delete;
    int get() const { return value; }
    void reset(int next) { if (value >= 0) ::close(value); value = next; }
private:
    int value;
};

struct Handle {
    Fd parent;
    Fd original;
    std::string leaf;
    struct stat initial{};
    bool existed = false;
    bool writable = false;
    Handle(int parent, std::string leaf, bool writable)
        : parent(parent), leaf(std::move(leaf)), writable(writable) {}
};

class FileError : public std::runtime_error {
public:
    const int number;
    FileError(const char* action, int number)
        : std::runtime_error(std::string(action) + ": " + std::strerror(number)), number(number) {}
};

void failure(const char* action) { throw FileError(action, errno); }

void interrupted(JNIEnv* env) {
    jclass thread = env->FindClass("java/lang/Thread");
    jmethodID current = env->GetStaticMethodID(thread, "currentThread", "()Ljava/lang/Thread;");
    jmethodID isInterrupted = env->GetMethodID(thread, "isInterrupted", "()Z");
    jobject instance = env->CallStaticObjectMethod(thread, current);
    bool cancelled = env->CallBooleanMethod(instance, isInterrupted);
    env->DeleteLocalRef(instance);
    env->DeleteLocalRef(thread);
    if (cancelled) {
        env->ThrowNew(env->FindClass("java/lang/InterruptedException"), "Rootfs file operation interrupted");
        throw std::runtime_error("interrupted");
    }
}

void report(JNIEnv* env, const std::exception& error) {
    if (!env->ExceptionCheck()) {
        auto* fileError = dynamic_cast<const FileError*>(&error);
        const char* type = fileError && fileError->number == ENOENT ? "java/io/FileNotFoundException" : "java/io/IOException";
        env->ThrowNew(env->FindClass(type), error.what());
    }
}

std::string text(JNIEnv* env, jbyteArray bytes) {
    const jsize size = env->GetArrayLength(bytes);
    std::string result(size, '\0');
    if (size) env->GetByteArrayRegion(bytes, 0, size, reinterpret_cast<jbyte*>(&result[0]));
    if (result.find('\0') != std::string::npos) throw std::runtime_error("Invalid Rootfs path");
    return result;
}

std::vector<std::string> components(const std::string& path) {
    std::vector<std::string> result;
    size_t start = 0;
    while (start < path.size()) {
        const size_t end = path.find('/', start);
        auto component = path.substr(start, end == std::string::npos ? end : end - start);
        if (!component.empty()) {
            if (component == "." || component == "..") throw std::runtime_error("Unnormalized Rootfs path");
            result.push_back(component);
        }
        if (end == std::string::npos) break;
        start = end + 1;
    }
    return result;
}

int directoryAt(int parent, const std::string& name, bool create) {
    int fd = openat(parent, name.c_str(), O_PATH | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0 && errno == ENOENT && create) {
        if (mkdirat(parent, name.c_str(), 0755) < 0 && errno != EEXIST) failure("Create parent directory");
        fd = openat(parent, name.c_str(), O_PATH | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    }
    if (fd < 0) failure("Open directory without symbolic links");
    return fd;
}

bool sameFile(const struct stat& first, const struct stat& second) {
    return first.st_dev == second.st_dev && first.st_ino == second.st_ino;
}

void validateWritable(const struct stat& value) {
    if (!S_ISREG(value.st_mode)) throw std::runtime_error("Path is not a regular file");
    if (value.st_nlink != 1) throw std::runtime_error("Writing a file with multiple hard links is not allowed");
}

Handle* from(jlong pointer) {
    if (!pointer) throw std::runtime_error("Closed Rootfs file handle");
    return reinterpret_cast<Handle*>(pointer);
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_me_rerere_workspace_WorkspaceFileAccess_open(
    JNIEnv* env, jobject, jbyteArray rootBytes, jbyteArray pathBytes,
    jboolean writable, jboolean create, jboolean overwrite
) {
    try {
        interrupted(env);
        const auto root = text(env, rootBytes);
        if (root.empty() || root[0] != '/') throw std::runtime_error("Rootfs anchor must be absolute");
        Fd parent(::open("/", O_PATH | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
        if (parent.get() < 0) failure("Open filesystem root");
        // Anchor ancestors are traversed too: a replaced mount or /tmp must never be followed.
        for (const auto& segment : components(root)) parent.reset(directoryAt(parent.get(), segment, false));
        const auto segments = components(text(env, pathBytes));
        if (segments.empty()) throw std::runtime_error("Path is not a file");
        for (size_t index = 0; index + 1 < segments.size(); ++index) {
            interrupted(env);
            parent.reset(directoryAt(parent.get(), segments[index], writable && create));
        }
        auto handle = std::make_unique<Handle>(fcntl(parent.get(), F_DUPFD_CLOEXEC, 0), segments.back(), writable);
        if (handle->parent.get() < 0) failure("Keep parent directory");
        // O_NONBLOCK prevents a replaced FIFO from blocking before fstat can reject it.
        const int access = writable ? (create ? O_WRONLY : O_RDWR) : O_RDONLY;
        int original = openat(handle->parent.get(), handle->leaf.c_str(), access | O_NONBLOCK | O_NOFOLLOW | O_CLOEXEC);
        if (original < 0) {
            if (errno != ENOENT || !writable || !create) failure("Open file without symbolic links");
        } else {
            handle->original.reset(original);
            handle->existed = true;
            if (fstat(original, &handle->initial) < 0) failure("Read file metadata");
            if (!S_ISREG(handle->initial.st_mode)) throw std::runtime_error("Path is not a regular file");
            if (writable) {
                validateWritable(handle->initial);
                if (!overwrite) throw std::runtime_error("File already exists");
            }
        }
        interrupted(env);
        return reinterpret_cast<jlong>(handle.release());
    } catch (const std::exception& error) { report(env, error); return 0; }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_me_rerere_workspace_WorkspaceFileAccess_read(JNIEnv* env, jobject, jlong pointer, jlong maxBytes) {
    try {
        auto* handle = from(pointer);
        if (!handle->existed) throw std::runtime_error("File does not exist");
        if (maxBytes < 0 || maxBytes > INT_MAX) throw std::runtime_error("Invalid read limit");
        struct stat current{};
        if (fstat(handle->original.get(), &current) < 0) failure("Read file metadata");
        if (current.st_size > maxBytes) throw std::runtime_error("File exceeds read limit; use workspace_shell to read portions");
        std::vector<jbyte> bytes;
        bytes.reserve(static_cast<size_t>(current.st_size));
        char buffer[65536];
        off_t offset = 0;
        while (true) {
            interrupted(env);
            const ssize_t count = pread(handle->original.get(), buffer, sizeof(buffer), offset);
            if (count < 0) { if (errno == EINTR) continue; failure("Read file"); }
            if (count == 0) break;
            if (offset + count > maxBytes) throw std::runtime_error("File exceeds read limit; use workspace_shell to read portions");
            bytes.insert(bytes.end(), buffer, buffer + count);
            offset += count;
        }
        auto result = env->NewByteArray(static_cast<jsize>(bytes.size()));
        if (result && !bytes.empty()) env->SetByteArrayRegion(result, 0, static_cast<jsize>(bytes.size()), bytes.data());
        return result;
    } catch (const std::exception& error) { report(env, error); return nullptr; }
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_me_rerere_workspace_WorkspaceFileAccess_write(JNIEnv* env, jobject, jlong pointer, jbyteArray bytes) {
    std::string temporary;
    struct stat ownedTemporary{};
    bool ownsTemporary = false;
    Handle* handle = nullptr;
    try {
        handle = from(pointer);
        if (!handle->writable) throw std::runtime_error("File handle is read-only");
        interrupted(env);
        static std::atomic<unsigned long long> sequence{0};
        Fd output;
        for (int attempt = 0; attempt < 64; ++attempt) {
            temporary = ".workspace-write-" + std::to_string(getpid()) + "-" + std::to_string(++sequence);
            if (temporary == handle->leaf) { temporary.clear(); continue; }
            int fd = openat(handle->parent.get(), temporary.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, 0600);
            if (fd >= 0) { output.reset(fd); break; }
            if (errno != EEXIST) { temporary.clear(); failure("Create atomic write file"); }
            temporary.clear();
        }
        if (output.get() < 0) throw std::runtime_error("Cannot reserve atomic write file");
        if (fstat(output.get(), &ownedTemporary) < 0) failure("Read atomic write metadata");
        ownsTemporary = true;
        const jsize size = env->GetArrayLength(bytes);
        jbyte buffer[65536];
        for (jsize offset = 0; offset < size;) {
            interrupted(env);
            const jsize count = std::min<jsize>(sizeof(buffer), size - offset);
            env->GetByteArrayRegion(bytes, offset, count, buffer);
            if (env->ExceptionCheck()) throw std::runtime_error("Read write buffer");
            ssize_t written = 0;
            while (written < count) {
                interrupted(env);
                const auto part = ::write(output.get(), buffer + written, count - written);
                if (part < 0) { if (errno == EINTR) continue; failure("Write file"); }
                if (part == 0) throw std::runtime_error("Write file made no progress");
                written += part;
            }
            offset += count;
        }
        if (fchmod(output.get(), handle->existed ? (handle->initial.st_mode & 0777) : 0644) < 0) failure("Set file mode");
        if (fsync(output.get()) < 0) failure("Sync file");
        struct stat written{};
        if (fstat(output.get(), &written) < 0) failure("Read written metadata");
        struct stat staged{};
        if (fstatat(handle->parent.get(), temporary.c_str(), &staged, AT_SYMLINK_NOFOLLOW) < 0) failure("Check atomic write file");
        if (!sameFile(written, staged) || staged.st_nlink != 1) throw std::runtime_error("Atomic write file changed");
        interrupted(env);
        if (handle->existed) {
            struct stat current{};
            if (fstatat(handle->parent.get(), handle->leaf.c_str(), &current, AT_SYMLINK_NOFOLLOW) < 0) failure("Check target before replacement");
            validateWritable(current);
            if (!sameFile(handle->initial, current)
                || current.st_size != handle->initial.st_size
                || current.st_mtim.tv_sec != handle->initial.st_mtim.tv_sec
                || current.st_mtim.tv_nsec != handle->initial.st_mtim.tv_nsec
                || current.st_ctim.tv_sec != handle->initial.st_ctim.tv_sec
                || current.st_ctim.tv_nsec != handle->initial.st_ctim.tv_nsec) {
                throw std::runtime_error("File changed during operation");
            }
            // Replacing the entry itself never follows a substituted leaf symlink.
            if (renameat(handle->parent.get(), temporary.c_str(), handle->parent.get(), handle->leaf.c_str()) < 0) failure("Replace file");
        } else {
            // New targets must not replace files concurrently created after open.
            if (syscall(SYS_renameat2, handle->parent.get(), temporary.c_str(),
                        handle->parent.get(), handle->leaf.c_str(), RENAME_NOREPLACE) < 0) failure("Create file");
        }
        temporary.clear();
        jlong metadata[] = { written.st_size, written.st_mtim.tv_sec * 1000LL + written.st_mtim.tv_nsec / 1000000LL };
        auto result = env->NewLongArray(2);
        if (result) env->SetLongArrayRegion(result, 0, 2, metadata);
        return result;
    } catch (const std::exception& error) {
        if (handle && ownsTemporary && !temporary.empty()) {
            struct stat current{};
            if (fstatat(handle->parent.get(), temporary.c_str(), &current, AT_SYMLINK_NOFOLLOW) == 0
                && sameFile(ownedTemporary, current)) {
                unlinkat(handle->parent.get(), temporary.c_str(), 0);
            }
        }
        report(env, error);
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_me_rerere_workspace_WorkspaceFileAccess_close(JNIEnv*, jobject, jlong pointer) {
    delete reinterpret_cast<Handle*>(pointer);
}

// Directory capabilities share the same NOFOLLOW traversal as Rootfs file operations.
namespace {
std::string childName(JNIEnv* env, jbyteArray bytes) {
    auto name = text(env, bytes);
    if (name.empty() || name == "." || name == ".." || name.find('/') != std::string::npos)
        throw std::runtime_error("Invalid document name");
    return name;
}

jbyteArray utf8(JNIEnv* env, const std::string& value) {
    auto result = env->NewByteArray(static_cast<jsize>(value.size()));
    if (result && !value.empty()) env->SetByteArrayRegion(result, 0, static_cast<jsize>(value.size()),
        reinterpret_cast<const jbyte*>(value.data()));
    return result;
}
}

extern "C" JNIEXPORT jint JNICALL
Java_me_rerere_workspace_WorkspaceFileAccess_openDirectory(JNIEnv* env, jobject, jbyteArray rootBytes) {
    try {
        interrupted(env);
        const auto root = text(env, rootBytes);
        if (root.empty() || root[0] != '/') throw std::runtime_error("Directory anchor must be absolute");
        Fd directory(::open("/", O_PATH | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC));
        if (directory.get() < 0) failure("Open filesystem root");
        for (const auto& segment : components(root)) directory.reset(directoryAt(directory.get(), segment, false));
        int result = fcntl(directory.get(), F_DUPFD_CLOEXEC, 0);
        if (result < 0) failure("Keep document directory");
        return result;
    } catch (const std::exception& error) { report(env, error); return -1; }
}

extern "C" JNIEXPORT jint JNICALL
Java_me_rerere_workspace_WorkspaceFileAccess_openChild(JNIEnv* env, jobject, jint parent, jbyteArray nameBytes, jint flags, jboolean metadataOnly, jboolean directory) {
    try {
        interrupted(env);
        const auto name = childName(env, nameBytes);
        int fd = openat(parent, name.c_str(), (metadataOnly ? O_PATH : flags) | (directory ? O_DIRECTORY : 0) | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC, 0600);
        if (fd < 0 && errno != ENOENT && errno != EEXIST) failure("Open document without symbolic links");
        return fd;
    } catch (const std::exception& error) { report(env, error); return -1; }
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_me_rerere_workspace_WorkspaceFileAccess_statDescriptor(JNIEnv* env, jobject, jint descriptor) {
    try {
        struct stat value{};
        if (fstat(descriptor, &value) < 0) failure("Read document metadata");
        jlong values[] = { value.st_mode, value.st_size,
            value.st_mtim.tv_sec * 1000LL + value.st_mtim.tv_nsec / 1000000LL,
            static_cast<jlong>(value.st_dev), static_cast<jlong>(value.st_ino), static_cast<jlong>(value.st_nlink) };
        auto result = env->NewLongArray(6);
        if (result) env->SetLongArrayRegion(result, 0, 6, values);
        return result;
    } catch (const std::exception& error) { report(env, error); return nullptr; }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_me_rerere_workspace_WorkspaceFileAccess_listDirectory(JNIEnv* env, jobject, jint descriptor) {
    try {
        interrupted(env);
        int fd = openat(descriptor, ".", O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        if (fd < 0) failure("Open directory listing");
        DIR* opened = fdopendir(fd);
        if (!opened) { const int saved = errno; ::close(fd); errno = saved; failure("Read directory listing"); }
        std::unique_ptr<DIR, decltype(&closedir)> directory(opened, closedir);
        std::vector<std::string> names;
        while (true) {
            interrupted(env);
            errno = 0;
            dirent* entry = readdir(directory.get());
            if (!entry) { if (errno) failure("Read directory entry"); break; }
            std::string name(entry->d_name);
            if (name != "." && name != "..") names.push_back(std::move(name));
        }
        auto result = env->NewObjectArray(static_cast<jsize>(names.size()), env->FindClass("[B"), nullptr);
        if (!result) return nullptr;
        for (size_t index = 0; index < names.size(); ++index) {
            auto name = utf8(env, names[index]);
            if (!name) return nullptr;
            env->SetObjectArrayElement(result, static_cast<jsize>(index), name);
            env->DeleteLocalRef(name);
        }
        return result;
    } catch (const std::exception& error) { report(env, error); return nullptr; }
}

extern "C" JNIEXPORT jint JNICALL
Java_me_rerere_workspace_WorkspaceFileAccess_createDirectory(JNIEnv* env, jobject, jint parent, jbyteArray nameBytes) {
    try {
        interrupted(env);
        const auto name = childName(env, nameBytes);
        if (mkdirat(parent, name.c_str(), 0700) < 0) {
            if (errno == EEXIST) return -1;
            failure("Create document directory");
        }
        int fd = openat(parent, name.c_str(), O_PATH | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        if (fd < 0) {
            const int saved = errno;
            unlinkat(parent, name.c_str(), AT_REMOVEDIR);
            errno = saved;
            failure("Open created directory");
        }
        return fd;
    } catch (const std::exception& error) { report(env, error); }
    return -1;
}

extern "C" JNIEXPORT void JNICALL
Java_me_rerere_workspace_WorkspaceFileAccess_removeChild(JNIEnv* env, jobject, jint parent, jbyteArray nameBytes, jboolean directory) {
    try {
        const auto name = childName(env, nameBytes);
        if (unlinkat(parent, name.c_str(), directory ? AT_REMOVEDIR : 0) < 0 && errno != ENOENT)
            failure("Remove document");
    } catch (const std::exception& error) { report(env, error); }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_me_rerere_workspace_WorkspaceFileAccess_renameChild(JNIEnv* env, jobject, jint source, jbyteArray nameBytes,
    jint destination, jbyteArray targetBytes) {
    try {
        interrupted(env);
        const auto name = childName(env, nameBytes);
        const auto target = childName(env, targetBytes);
        if (syscall(SYS_renameat2, source, name.c_str(), destination, target.c_str(), RENAME_NOREPLACE) == 0) return true;
        if (errno == EEXIST) return false;
        failure("Move document");
    } catch (const std::exception& error) { report(env, error); }
    return false;
}
