package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Tests for [HostShellRunner] that execute real shell commands via /bin/sh.
 *
 * These tests verify the full shell execution pipeline: ProcessBuilder → stdout/stderr
 * collection → timeout handling → stdin piping. They exercise the shared [readResult],
 * [StreamCollector] and [StreamWriter] code paths that ProotShellRunner also relies on.
 *
 * Skipped on Windows (no /bin/sh available) — run on Linux/macOS or CI to exercise.
 */
class HostShellRunnerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun assumeShellAvailable() {
        val shellExists = File("/system/bin/sh").exists() || File("/bin/sh").exists()
        assumeTrue("HostShellRunner tests require /bin/sh (skipped on Windows)", shellExists)
    }

    private fun createContext(
        command: String,
        stdin: ByteArray? = null,
        timeoutMillis: Long = 10_000,
    ): WorkspaceShellContext {
        val filesDir = tmp.newFolder("files")
        return WorkspaceShellContext(
            root = "test",
            command = command,
            cwd = "",
            filesDir = filesDir,
            linuxDir = tmp.newFolder("linux"),
            tempDir = tmp.newFolder("tmp"),
            workingDir = filesDir,
            timeoutMillis = timeoutMillis,
            stdin = stdin,
        )
    }

    @Test
    fun executesEchoCommandAndCapturesStdout() {
        val runner = HostShellRunner()
        val ctx = createContext("echo hello_world")
        val result = runner.execute(ctx)

        assertEquals(0, result.exitCode)
        assertEquals("hello_world", result.stdout.trim())
        assertEquals("", result.stderr.trim())
        assertFalse("Should not time out", result.timedOut)
        assertFalse("Should not be truncated", result.truncated)
    }

    @Test
    fun capturesStderrSeparatelyFromStdout() {
        val runner = HostShellRunner()
        val ctx = createContext("echo to_stdout; echo to_stderr >&2")
        val result = runner.execute(ctx)

        assertEquals(0, result.exitCode)
        assertTrue("stdout must contain to_stdout", result.stdout.contains("to_stdout"))
        assertTrue("stderr must contain to_stderr", result.stderr.contains("to_stderr"))
        assertFalse("stdout must not contain stderr", result.stdout.contains("to_stderr"))
        assertFalse("stderr must not contain stdout", result.stderr.contains("to_stdout"))
    }

    @Test
    fun pipesStdinToCommand() {
        val runner = HostShellRunner()
        val ctx = createContext(
            command = "cat",
            stdin = "piped_input_data".toByteArray(),
        )
        val result = runner.execute(ctx)

        assertEquals(0, result.exitCode)
        assertEquals("piped_input_data", result.stdout)
    }

    @Test
    fun returnsNonZeroExitCodeForFailedCommand() {
        val runner = HostShellRunner()
        val ctx = createContext("exit 42")
        val result = runner.execute(ctx)

        assertEquals(42, result.exitCode)
    }

    @Test
    fun handlesMultilineOutput() {
        val runner = HostShellRunner()
        val ctx = createContext("printf 'line1\\nline2\\nline3\\n'")
        val result = runner.execute(ctx)

        assertEquals(0, result.exitCode)
        assertEquals(listOf("line1", "line2", "line3"), result.stdout.trim().lines())
    }

    @Test
    fun timesOutOnLongRunningCommand() {
        val runner = HostShellRunner()
        val ctx = createContext(
            command = "sleep 30",
            timeoutMillis = 500,
        )
        val result = runner.execute(ctx)

        assertTrue("Should time out", result.timedOut)
        assertEquals(-1, result.exitCode)
    }

    @Test
    fun executesCompoundCommandWithPipe() {
        val runner = HostShellRunner()
        val ctx = createContext("echo 'a b c' | wc -w")
        val result = runner.execute(ctx)

        assertEquals(0, result.exitCode)
        assertEquals("3", result.stdout.trim())
    }

    @Test
    fun executesFileWriteAndReadRoundTrip() {
        val filesDir = tmp.newFolder("files")
        val runner = HostShellRunner()
        val ctx = WorkspaceShellContext(
            root = "test",
            command = "echo 'test_content' > test.txt && cat test.txt",
            cwd = "",
            filesDir = filesDir,
            linuxDir = tmp.newFolder("linux"),
            tempDir = tmp.newFolder("tmp"),
            workingDir = filesDir,
            timeoutMillis = 10_000,
        )
        val result = runner.execute(ctx)

        assertEquals(0, result.exitCode)
        assertEquals("test_content", result.stdout.trim())
        assertTrue("File should exist on host filesystem", File(filesDir, "test.txt").isFile)
    }

    @Test
    fun truncatesExcessiveOutput() {
        val runner = HostShellRunner()
        // Generate ~256KB of output, but the collector caps at MAX_OUTPUT_CHARS (128KB)
        val ctx = createContext("yes 'x' | head -c 262144")
        val result = runner.execute(ctx)

        assertTrue("Output should be truncated", result.truncated)
        assertTrue(
            "Captured output should not exceed max",
            result.stdout.length <= MAX_OUTPUT_CHARS
        )
    }
}
