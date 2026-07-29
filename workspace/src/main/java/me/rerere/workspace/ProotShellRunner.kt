package me.rerere.workspace

import java.io.File

data class WorkspaceBindMount(
    val source: File,
    val target: String,
) {
    init {
        require(target.startsWith("/")) { "Bind mount target must be absolute: $target" }
    }
}

class ProotShellRunner(
    private val nativeLibraryDir: File,
    private val patcher: RootfsPatcher = RootfsPatcher(),
) : WorkspaceShellRunner {
    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        if (!context.linuxDir.hasUsableRootfs()) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "Rootfs is not installed",
            )
        }

        val proot = File(nativeLibraryDir, PROOT_EXEC)
        val loader = File(nativeLibraryDir, PROOT_LOADER)
        if (!proot.isFile) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "proot executable not found: ${proot.absolutePath}",
            )
        }
        if (!loader.isFile) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "proot loader not found: ${loader.absolutePath}",
            )
        }

        context.tempDir.mkdirs()
        patcher.patch(context.linuxDir)
        val process = ProcessBuilder(buildCommand(context, proot))
            .directory(context.filesDir)
            .redirectErrorStream(false)
            .apply {
                buildEnvironment(loader, context.tempDir).forEach { (k, v) ->
                    environment()[k] = v
                }
            }
            .start()

        return process.readResult(context.timeoutMillis, context.stdin)
    }

    /**
     * Builds the environment variables for the proot process.
     *
     * IMPORTANT: Do NOT add PROOT_NO_SECCOMP=1 here. proot's built-in seccomp filter is
     * essential for reliable syscall interception on Android 14+. The filter returns
     * SECCOMP_RET_TRACE for syscalls proot needs to translate (mkdirat, newfstatat,
     * getcwd, etc.), triggering PTRACE_EVENT_SECCOMP which is far more reliable than
     * the fallback PTRACE_SYSCALL approach.
     *
     * The previous PROOT_NO_SECCOMP=1 was added to fix SIGILL on x86_64 emulators,
     * but that was actually caused by architecture mismatch (arm64 rootfs on x86_64
     * CPU), not by seccomp conflicts. On real devices with matching architecture,
     * proot's seccomp filter works correctly and is required for mkdir/stat/rename
     * etc. to function.
     */
    internal fun buildEnvironment(loader: File, tempDir: File): Map<String, String> = mapOf(
        "PROOT_LOADER" to loader.absolutePath,
        "PROOT_TMP_DIR" to tempDir.absolutePath,
        "TMPDIR" to tempDir.absolutePath,
    )

    internal fun buildCommand(
        context: WorkspaceShellContext,
        proot: File,
    ): List<String> {
        val command = mutableListOf(
            proot.absolutePath,
            "--root-id",
            "--link2symlink",
            "--kill-on-exit",
            // Spoof kernel version: modern glibc (2.33+) uses newer syscalls like
            // faccessat2 (added in kernel 5.8) that proot's ptrace interception cannot
            // handle, causing getcwd() and other calls to return ENOSYS ("Function not
            // implemented"). Reporting kernel 4.14.0 (a widely-used LTS version) makes
            // glibc fall back to older, well-supported syscalls that proot intercepts
            // correctly. See: qaliblog/xterm PR #12 and #13.
            "-k", KERNEL_RELEASE,
            "-r",
            context.linuxDir.absolutePath,
            "-w",
            context.prootCwd(),
            "-b",
            "${context.filesDir.absolutePath}:$WORKSPACE_DIR",
        )

        context.bindMounts.forEach { mount ->
            if (mount.source.exists()) {
                command += "-b"
                command += "${mount.source.absolutePath}:${mount.target.trimEnd('/')}"
            }
        }

        WorkspaceManager.KERNEL_FS_MOUNTS.forEach { path ->
            if (File(path).exists()) {
                command += "-b"
                command += path
            }
        }

        val prootCwd = context.prootCwd()
        command += listOf(
            "/usr/bin/env",
            "-i",
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
            // PWD gives bash a reliable CWD fallback that matches proot's -w flag,
            // avoiding the "shell-init: error retrieving current directory" warning
            // when getcwd() returns ENOSYS on Android 14+.
            "PWD=$prootCwd",
            "/bin/bash",
            "-c",
            // CWD is set by proot's -w flag (virtual path translation, no chdir
            // syscall needed). The upstream uses `cd -- "$1" && eval "$2"` here,
            // but the `cd` is redundant since -w already sets the initial CWD.
            // We omit it for simplicity: fewer positional args, and avoids any
            // edge case where chdir() might fail (e.g. if someone re-enables
            // PROOT_NO_SECCOMP on a device with a known kernel seccomp bug).
            //
            // Command text is passed as positional arg ($1) to avoid any escaping;
            // eval "$1" evaluates it exactly once, equivalent to bash -c "$cmd".
            "eval \"\$1\"",
            "MeasixPilot",
            context.command,
        )
        return command
    }

    private fun WorkspaceShellContext.prootCwd(): String {
        val normalized = cwd.trim().trim('/')
        return if (normalized.isBlank()) {
            WORKSPACE_DIR
        } else {
            "$WORKSPACE_DIR/$normalized"
        }
    }

    private fun File.hasUsableRootfs(): Boolean =
        isDirectory && File(this, "bin/sh").isFile

    private companion object {
        private const val PROOT_EXEC = "libproot_exec.so"
        private const val PROOT_LOADER = "libproot_loader.so"
        private const val WORKSPACE_DIR = WorkspaceManager.ROOTFS_WORKSPACE_DIR
        // 4.14.0 is the LTS kernel used by Android 8-9; old enough to avoid newer syscalls
        // (faccessat2 etc.) that proot can't intercept, new enough for all modern glibc.
        private const val KERNEL_RELEASE = "4.14.0"
    }
}
