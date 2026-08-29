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

/**
 * Shared PRoot argv/env/bind/cwd assembly for shell commands and the interactive terminal.
 * This value does not start a process, hold a PTY, or read Settings.
 */
data class ProotLaunchSpec(
    val executable: File,
    val loader: File,
    val arguments: List<String>,
    val environment: Map<String, String>,
    val workingDirectory: File,
) {
    val commandLine: List<String>
        get() = listOf(executable.absolutePath) + arguments

    companion object {
        const val WORKSPACE_DIR = WorkspaceManager.ROOTFS_WORKSPACE_DIR
        const val SKILLS_DIR = "/skills"
        const val TOOL_OUTPUTS_DIR = "/tool_outputs"
        const val UPLOAD_DIR = "/upload"
        const val KERNEL_RELEASE = "4.14.0"
        const val PROOT_EXEC = "libproot_exec.so"
        const val PROOT_LOADER = "libproot_loader.so"

        /**
         * Host directories that both shell and interactive terminal bind into the guest.
         * Directory names match the app-private layout used by Artifact/Skill storage.
         */
        fun appBindMounts(appFilesDir: File): List<WorkspaceBindMount> = listOf(
            WorkspaceBindMount(File(appFilesDir, "skills").apply { mkdirs() }, SKILLS_DIR),
            WorkspaceBindMount(File(appFilesDir, "tool_outputs").apply { mkdirs() }, TOOL_OUTPUTS_DIR),
            WorkspaceBindMount(File(appFilesDir, "upload").apply { mkdirs() }, UPLOAD_DIR),
        )

        fun from(
            nativeLibraryDir: File,
            filesDir: File,
            linuxDir: File,
            tempDir: File,
            bindMounts: List<WorkspaceBindMount>,
            guestCwd: String = "",
            command: String? = null,
        ): ProotLaunchSpec {
            val executable = File(nativeLibraryDir, PROOT_EXEC)
            val loader = File(nativeLibraryDir, PROOT_LOADER)
            val prootCwd = guestCwd(guestCwd)
            val arguments = mutableListOf(
                "--root-id",
                "--link2symlink",
                "--kill-on-exit",
                "-k",
                KERNEL_RELEASE,
                "-r",
                linuxDir.absolutePath,
                "-w",
                prootCwd,
                "-b",
                "${filesDir.absolutePath}:$WORKSPACE_DIR",
            )
            bindMounts.forEach { mount ->
                if (mount.source.exists()) {
                    arguments += "-b"
                    arguments += "${mount.source.absolutePath}:${mount.target.trimEnd('/')}"
                }
            }
            WorkspaceManager.KERNEL_FS_MOUNTS.forEach { path ->
                if (File(path).exists()) {
                    arguments += "-b"
                    arguments += path
                }
            }
            arguments += listOf(
                "/usr/bin/env",
                "-i",
                "HOME=/root",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "TERM=xterm-256color",
                "LANG=C.UTF-8",
                "LC_ALL=C.UTF-8",
                "USER=root",
                "SHELL=/bin/bash",
                "PWD=$prootCwd",
            )
            // 非交互执行没有可回退的终端：显式告知子进程，避免 git/man/apt 等挂起或输出转义序列。
            // 交互式 terminal 保持原环境，用户仍可自行设置分页与颜色。
            if (command != null) {
                arguments += listOf(
                    "CI=1",
                    "NO_COLOR=1",
                    "PAGER=cat",
                )
            }
            if (command != null) {
                arguments += listOf(
                    "/bin/bash",
                    "-c",
                    "eval \"\$1\"",
                    "MeasixPilot",
                    command,
                )
            } else {
                arguments += "/bin/bash"
            }
            return ProotLaunchSpec(
                executable = executable,
                loader = loader,
                arguments = arguments,
                environment = processEnvironment(loader, tempDir),
                workingDirectory = filesDir,
            )
        }

        fun processEnvironment(loader: File, tempDir: File): Map<String, String> = mapOf(
            "PROOT_LOADER" to loader.absolutePath,
            "PROOT_TMP_DIR" to tempDir.absolutePath,
            "TMPDIR" to tempDir.absolutePath,
        )

        fun guestCwd(cwd: String): String {
            val normalized = cwd.trim().trim('/')
            return if (normalized.isBlank()) {
                WORKSPACE_DIR
            } else {
                "$WORKSPACE_DIR/$normalized"
            }
        }
    }
}
