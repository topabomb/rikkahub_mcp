package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProotShellRunnerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var nativeDir: File
    private lateinit var filesDir: File
    private lateinit var linuxDir: File
    private lateinit var tempDir: File
    private lateinit var proot: File

    private fun setupContext(
        command: String = "echo hello",
        cwd: String = "",
        bindMounts: List<WorkspaceBindMount> = emptyList(),
        stdin: ByteArray? = null,
    ): Pair<ProotShellRunner, WorkspaceShellContext> {
        nativeDir = tmp.newFolder("native")
        filesDir = tmp.newFolder("files")
        linuxDir = tmp.newFolder("linux")
        tempDir = tmp.newFolder("tmp")
        proot = File(nativeDir, "libproot_exec.so").apply { writeText("fake") }
        File(nativeDir, "libproot_loader.so").apply { writeText("fake") }

        val runner = ProotShellRunner(nativeDir)
        val ctx = WorkspaceShellContext(
            root = "test",
            command = command,
            cwd = cwd,
            filesDir = filesDir,
            linuxDir = linuxDir,
            tempDir = tempDir,
            workingDir = filesDir,
            timeoutMillis = 5_000,
            stdin = stdin,
            bindMounts = bindMounts,
        )
        return runner to ctx
    }

    @Test
    fun buildCommand_includesKernelVersionSpoof() {
        val (runner, ctx) = setupContext()
        val cmd = runner.buildCommand(ctx, proot)

        val kIndex = cmd.indexOf("-k")
        assertTrue("Command must contain -k flag for kernel version spoofing", kIndex >= 0)
        assertTrue("-k must be followed by a version string", kIndex + 1 < cmd.size)
        assertEquals("4.14.0", cmd[kIndex + 1])
    }

    @Test
    fun buildCommand_includesPwdEnvironmentVariable() {
        val (runner, ctx) = setupContext(cwd = "subdir")
        val cmd = runner.buildCommand(ctx, proot)

        val pwdEntry = cmd.find { it.startsWith("PWD=") }
        assertTrue("Command must set PWD environment variable", pwdEntry != null)
        val expectedPwd = "${WorkspaceManager.ROOTFS_WORKSPACE_DIR}/subdir"
        assertEquals(expectedPwd, pwdEntry!!.removePrefix("PWD="))
    }

    @Test
    fun buildCommand_includesCoreProotFlags() {
        val (runner, ctx) = setupContext()
        val cmd = runner.buildCommand(ctx, proot)

        assertTrue("Must include --root-id", cmd.contains("--root-id"))
        assertTrue("Must include --link2symlink", cmd.contains("--link2symlink"))
        assertTrue("Must include --kill-on-exit", cmd.contains("--kill-on-exit"))
        assertTrue("Must include -r (rootfs path)", cmd.contains("-r"))
        assertTrue("Must include -w (working directory)", cmd.contains("-w"))
    }

    @Test
    fun buildCommand_includesWorkspaceBindMount() {
        val (runner, ctx) = setupContext()
        val cmd = runner.buildCommand(ctx, proot)

        val bIndex = cmd.indexOf("-b")
        assertTrue("Must have at least one -b bind mount", bIndex >= 0)
        val mountSpec = cmd[bIndex + 1]
        assertTrue(
            "Workspace files dir must be bind-mounted to $WORKSPACE_DIR",
            mountSpec.endsWith(":$WORKSPACE_DIR")
        )
    }

    @Test
    fun buildCommand_includesCustomBindMounts() {
        val skillsDir = tmp.newFolder("skills")
        val uploadDir = tmp.newFolder("upload")
        val (runner, ctx) = setupContext(
            bindMounts = listOf(
                WorkspaceBindMount(skillsDir, "/skills"),
                WorkspaceBindMount(uploadDir, "/upload"),
            )
        )
        val cmd = runner.buildCommand(ctx, proot)

        val mountSpecs = cmd.mapIndexedNotNull { i, arg ->
            if (arg == "-b" && i + 1 < cmd.size) cmd[i + 1] else null
        }
        assertTrue(
            "Skills bind mount must be present",
            mountSpecs.any { it.endsWith(":/skills") }
        )
        assertTrue(
            "Upload bind mount must be present",
            mountSpecs.any { it.endsWith(":/upload") }
        )
    }

    @Test
    fun buildCommand_usesEnvDashI() {
        val (runner, ctx) = setupContext()
        val cmd = runner.buildCommand(ctx, proot)

        val envIndex = cmd.indexOf("/usr/bin/env")
        assertTrue("Must use /usr/bin/env", envIndex >= 0)
        assertTrue(
            "env must be called with -i (clean environment)",
            envIndex + 1 < cmd.size && cmd[envIndex + 1] == "-i"
        )
    }

    @Test
    fun buildCommand_includesLocaleAndPath() {
        val (runner, ctx) = setupContext()
        val cmd = runner.buildCommand(ctx, proot)

        assertTrue("Must set LANG=C.UTF-8", cmd.contains("LANG=C.UTF-8"))
        assertTrue("Must set LC_ALL=C.UTF-8", cmd.contains("LC_ALL=C.UTF-8"))
        assertTrue(
            "Must set PATH",
            cmd.any { it.startsWith("PATH=") }
        )
    }

    @Test
    fun buildCommand_bashReceivesCommandViaPositionalArgs() {
        val (runner, ctx) = setupContext(command = "ls -la /workspace")
        val cmd = runner.buildCommand(ctx, proot)

        val bashIndex = cmd.indexOf("/bin/bash")
        assertTrue("Must invoke /bin/bash", bashIndex >= 0)
        assertTrue(
            "bash must use -c for command execution",
            bashIndex + 1 < cmd.size && cmd[bashIndex + 1] == "-c"
        )
        // The command text should NOT be interpolated into the -c string directly;
        // it should be passed as a positional argument to avoid escaping issues.
        val evalScript = cmd.getOrNull(bashIndex + 2)
        assertTrue(
            "bash -c script must use positional parameter eval",
            evalScript != null && evalScript.contains("eval")
        )
        // The script should NOT use "cd": proot's -w flag already sets the CWD,
        // making an explicit cd redundant. Removing it also avoids edge cases where
        // chdir() might fail (e.g. if PROOT_NO_SECCOMP is mistakenly re-enabled).
        assertFalse(
            "bash -c script should NOT use cd (redundant with -w, avoids chdir edge cases)",
            evalScript!!.contains("cd ")
        )
        // The actual command text must be the last positional argument
        assertEquals(
            "Command text must be passed as positional arg",
            "ls -la /workspace",
            cmd.last()
        )
    }

    @Test
    fun buildCommand_prootCwdMatchesPwdForRootDirectory() {
        val (runner, ctx) = setupContext(cwd = "")
        val cmd = runner.buildCommand(ctx, proot)

        val wIndex = cmd.indexOf("-w")
        assertTrue("-w flag must be present", wIndex >= 0)
        val prootCwd = cmd[wIndex + 1]

        val pwdEntry = cmd.find { it.startsWith("PWD=") }!!
        val pwdValue = pwdEntry.removePrefix("PWD=")

        assertEquals(
            "proot -w and PWD must match for consistent CWD",
            prootCwd, pwdValue
        )
    }

    @Test
    fun buildCommand_prootCwdMatchesPwdForSubdirectory() {
        val (runner, ctx) = setupContext(cwd = "projects/myapp")
        val cmd = runner.buildCommand(ctx, proot)

        val wIndex = cmd.indexOf("-w")
        val prootCwd = cmd[wIndex + 1]

        val pwdEntry = cmd.find { it.startsWith("PWD=") }!!
        val pwdValue = pwdEntry.removePrefix("PWD=")

        assertEquals(
            "proot -w and PWD must match for subdirectory",
            prootCwd, pwdValue
        )
        assertEquals(
            "CWD must be under /workspace",
            "$WORKSPACE_DIR/projects/myapp",
            prootCwd
        )
    }

    @Test
    fun buildEnvironment_doesNotSetProotNoSeccomp() {
        val (runner, ctx) = setupContext()
        val loader = File(nativeDir, "libproot_loader.so")
        val env = runner.buildEnvironment(loader, ctx.tempDir)

        assertFalse(
            "PROOT_NO_SECCOMP must NOT be set: proot's seccomp filter is required " +
                "for reliable syscall interception (mkdir, stat, etc.) on Android 14+",
            env.containsKey("PROOT_NO_SECCOMP")
        )
        assertTrue("PROOT_LOADER must be set", env.containsKey("PROOT_LOADER"))
        assertTrue("PROOT_TMP_DIR must be set", env.containsKey("PROOT_TMP_DIR"))
        assertTrue("TMPDIR must be set", env.containsKey("TMPDIR"))
    }

    @Test
    fun execute_returnsErrorWhenRootfsNotInstalled() {
        val (runner, ctx) = setupContext()
        // linuxDir is an empty temp folder — no bin/sh
        val result = runner.execute(ctx)

        assertEquals(127, result.exitCode)
        assertEquals("", result.stdout)
        assertTrue(
            "stderr must mention rootfs not installed",
            result.stderr.contains("Rootfs is not installed")
        )
    }

    @Test
    fun execute_returnsErrorWhenProotBinaryMissing() {
        // Create a valid rootfs (bin/sh exists) but no proot binary
        linuxDir = tmp.newFolder("linux-real")
        filesDir = tmp.newFolder("files-real")
        File(linuxDir, "bin").mkdirs()
        File(linuxDir, "bin/sh").writeText("#!/bin/sh\n")

        val emptyNativeDir = tmp.newFolder("empty-native")
        val runner = ProotShellRunner(emptyNativeDir)
        val ctx = WorkspaceShellContext(
            root = "test",
            command = "echo hello",
            cwd = "",
            filesDir = filesDir,
            linuxDir = linuxDir,
            tempDir = tmp.newFolder("tmp-real"),
            workingDir = filesDir,
            timeoutMillis = 5_000,
        )
        val result = runner.execute(ctx)

        assertEquals(127, result.exitCode)
        assertTrue(
            "stderr must mention proot executable not found",
            result.stderr.contains("proot executable not found")
        )
    }

    private companion object {
        private const val WORKSPACE_DIR = WorkspaceManager.ROOTFS_WORKSPACE_DIR
    }
}
