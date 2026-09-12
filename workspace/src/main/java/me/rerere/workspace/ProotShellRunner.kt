package me.rerere.workspace

import java.io.File

class ProotShellRunner(
    private val nativeLibraryDir: File,
    private val patcher: RootfsPatcher = RootfsPatcher(),
) : WorkspaceShellRunner {
    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        if (!context.linuxDir.isDirectory || context.linuxDir.list()?.isNotEmpty() != true) {
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

        RootfsCompatibility(nativeLibraryDir).requireCompatible(context.linuxDir)

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

    /** Both entry points retain PRoot's syscall translation policy from the shared launch spec. */
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

}
