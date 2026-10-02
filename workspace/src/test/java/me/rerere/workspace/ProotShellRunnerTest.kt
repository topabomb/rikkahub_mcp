package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
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
    fun shellContextIsForwardedToTheSharedLaunchSpec() {
        val skills = tmp.newFolder("skills")
        val (runner, context) = setupContext(
            command = "printf '%s' 'quoted command'",
            cwd = "projects/myapp",
            bindMounts = listOf(WorkspaceBindMount(skills, "/skills")),
        )
        val spec = ProotLaunchSpec.from(
            nativeLibraryDir = nativeDir,
            filesDir = context.filesDir,
            linuxDir = context.linuxDir,
            tempDir = context.tempDir,
            bindMounts = context.bindMounts,
            guestCwd = context.cwd,
            command = context.command,
        )

        assertEquals(spec.commandLine, runner.buildCommand(context, proot))
        assertEquals(spec.environment, runner.buildEnvironment(spec.loader, context.tempDir))
    }

    @Test
    fun execute_rejectsForeignRootfsBeforeStartingProot() {
        val (runner, context) = setupContext()
        writeTestElf(proot, 62)
        writeTestElf(File(linuxDir, "usr/bin/env"), 183)
        writeTestElf(File(linuxDir, "bin/bash"), 183)
        val failure = assertThrows(IllegalArgumentException::class.java) { runner.execute(context) }
        assertTrue(failure.message!!.contains("architecture mismatch"))
    }

    @Test
    fun execute_returnsErrorWhenRootfsNotInstalled() {
        val (runner, ctx) = setupContext()
        // An empty workspace has no installed Rootfs content.
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
        // Native diagnostics precede compatibility checks for an existing Rootfs tree.
        linuxDir = tmp.newFolder("linux-real")
        filesDir = tmp.newFolder("files-real")
        File(linuxDir, "etc").mkdirs()

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

}
