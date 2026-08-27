package me.rerere.workspace

import java.io.File

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

        val spec = launchSpec(context)
        if (!spec.executable.isFile) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "proot executable not found: ${spec.executable.absolutePath}",
            )
        }
        if (!spec.loader.isFile) {
            return WorkspaceCommandResult(
                exitCode = 127,
                stdout = "",
                stderr = "proot loader not found: ${spec.loader.absolutePath}",
            )
        }

        context.tempDir.mkdirs()
        patcher.patch(context.linuxDir)
        val process = ProcessBuilder(spec.commandLine)
            .directory(spec.workingDirectory)
            .redirectErrorStream(false)
            .apply {
                spec.environment.forEach { (k, v) ->
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
    internal fun buildEnvironment(loader: File, tempDir: File): Map<String, String> =
        ProotLaunchSpec.processEnvironment(loader, tempDir)

    internal fun buildCommand(
        context: WorkspaceShellContext,
        proot: File,
    ): List<String> = launchSpec(context).let { spec ->
        require(spec.executable == proot) {
            "PRoot executable must come from the native library directory"
        }
        spec.commandLine
    }

    private fun launchSpec(context: WorkspaceShellContext): ProotLaunchSpec =
        ProotLaunchSpec.from(
            nativeLibraryDir = nativeLibraryDir,
            filesDir = context.filesDir,
            linuxDir = context.linuxDir,
            tempDir = context.tempDir,
            bindMounts = context.bindMounts,
            guestCwd = context.cwd,
            command = context.command,
        )

    private fun File.hasUsableRootfs(): Boolean =
        isDirectory && File(this, "bin/sh").isFile
}
