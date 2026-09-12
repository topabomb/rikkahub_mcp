package net.weero.measix.pilot.service.workspace

import android.content.Context
import android.graphics.Typeface
import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.termux.view.TerminalView
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import me.rerere.workspace.ProotLaunchSpec
import me.rerere.workspace.ProotShellRunner
import me.rerere.workspace.RootfsInstaller
import me.rerere.workspace.RootfsCompatibility
import me.rerere.workspace.WorkspaceManager
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.enterprise.EnterpriseAppliedStore
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in matching-ABI Linux fixture; the ordinary PTY tests use Android's system shell. */
@RunWith(AndroidJUnit4::class)
class WorkspaceProotAndroidTest {
    @Test
    fun matchingRootfsRunsProductionShellAndTwoPtysWithCancellationCleanup() = runBlocking {
        val url = InstrumentationRegistry.getArguments().getString("prootRootfsUrl")
        assumeTrue("Supply prootRootfsUrl for a matching-ABI Rootfs acceptance run", !url.isNullOrBlank())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = "proot-test-${UUID.randomUUID()}"
        val native = File(context.applicationInfo.nativeLibraryDir)
        assumeTrue("The standard amd64 Ubuntu fixture requires a 4 KB host; the 16 KB rejection has a separate test",
            RootfsCompatibility(native).architecture != RootfsCompatibility.Architecture.X86_64 ||
                Os.sysconf(OsConstants._SC_PAGESIZE) == 4096L)
        val manager = WorkspaceManager(
            File(context.filesDir, "workspaces"),
            shellRunner = ProotShellRunner(native),
            bindMounts = ProotLaunchSpec.appBindMounts(context.filesDir),
        )
        val stateDir = File(context.cacheDir, root).apply { check(mkdirs()) }
        val sessions = EnterpriseSessionController(EnterpriseAppliedStore(File(stateDir, "enterprise"))).also { it.recover() }
        val scope = AppScope()
        val runtime = WorkspaceTerminalRuntime(context, scope, sessions)
        var stage = "install"
        try {
            withContext(Dispatchers.IO) { RootfsInstaller(manager, native).install(root, requireNotNull(url)) }
            stage = "guest absolute bin link"
            withContext(Dispatchers.IO) {
                val bin = File(manager.linuxDir(root), "bin").toPath()
                assertTrue("The Ubuntu fixture must provide a bin symlink", java.nio.file.Files.isSymbolicLink(bin))
                java.nio.file.Files.delete(bin)
                java.nio.file.Files.createSymbolicLink(bin, File("/usr/bin").toPath())
                assertTrue(manager.hasRootfsFiles(root))
                assertTrue(RootfsCompatibility(native).isCompatible(manager.linuxDir(root)))
            }
            assertTrue(workspaceRootfsReady(context, root))
            stage = "shell file operations and output"
            val result = runInterruptible(Dispatchers.IO) {
                manager.executeCommand(root, "set -e; test \"\$PWD\" = /workspace; mkdir probe; printf CONTENT > probe/a; mv probe/a probe/b; test \"\$(stat -c %s probe/b)\" = 7; cat probe/b; printf '\\n'; uname -r; for i in {1..1000}; do echo LINE_\$i; done")
            }
            assertEquals(result.stderr, 0, result.exitCode)
            assertTrue(result.stdout.contains("CONTENT\n4.14.0\n"))
            assertTrue(result.stdout.contains("LINE_1000"))
            val piped = runInterruptible(Dispatchers.IO) {
                manager.executeCommand(root, "cat; printf STDERR >&2", stdin = "STDIN".toByteArray())
            }
            assertEquals(0, piped.exitCode)
            assertEquals("STDIN", piped.stdout)
            assertEquals("STDERR", piped.stderr)
            stage = "command timeout"
            val timeoutStarted = SystemClock.elapsedRealtime()
            val timeout = runInterruptible(Dispatchers.IO) {
                manager.executeCommand(root, "sleep 30 & echo \$! > timeout-child; wait", timeoutMillis = 1_000)
            }
            assertTrue(timeout.timedOut)
            assertTrue("Timeout must terminate and reap without waiting for sleep", SystemClock.elapsedRealtime() - timeoutStarted < 5_000)
            assertProcessGone(File(manager.filesDir(root), "timeout-child"))
            stage = "command cancellation marker"
            val cancelled = launch(Dispatchers.IO) {
                runInterruptible { manager.executeCommand(root, "sleep 30 & echo \$! > cancel-child; touch cancel-started; wait") }
            }
            withTimeout(10_000) {
                while (!File(manager.filesDir(root), "cancel-started").exists()) delay(20)
                stage = "command cancellation join"
                cancelled.cancelAndJoin()
            }
            assertTrue(cancelled.isCancelled)
            assertProcessGone(File(manager.filesDir(root), "cancel-child"))

            stage = "cancellation during process startup"
            for (cancelDelay in listOf(0L, 2L, 10L, 50L)) {
                val pidFile = File(manager.filesDir(root), "early-$cancelDelay-child")
                val starting = launch(Dispatchers.IO) {
                    runInterruptible {
                        manager.executeCommand(root, "sleep 30 & echo \$! > ${pidFile.name}; wait")
                    }
                }
                delay(cancelDelay)
                withTimeout(5_000) { starting.cancelAndJoin() }
                if (pidFile.exists()) assertProcessGone(pidFile)
                assertFalse("A cancelled invocation must not retain its PRoot process", hasTracer(manager.linuxDir(root)))
            }

            stage = "cancellation while guest children are being forked"
            val childMarker = "proot-child-$root"
            val forking = launch(Dispatchers.IO) {
                runInterruptible {
                    manager.executeCommand(root, "for i in {1..64}; do (exec -a $childMarker sleep 30) & touch fork-started; done; wait")
                }
            }
            withTimeout(10_000) {
                while (!File(manager.filesDir(root), "fork-started").exists()) delay(2)
                forking.cancelAndJoin()
            }
            assertFalse("Late child registration must not escape cancellation", processArguments().any { childMarker in it })
            assertFalse(hasTracer(manager.linuxDir(root)))

            val selection = requireNotNull(sessions.readPresentation().selection)
            val tabs = mutableListOf<String>()
            for (label in listOf("ONE", "TWO")) {
                stage = "PTY $label"
                val created = runtime.create(root, selection) { it() } as WorkspaceTerminalCreateResult.Created
                tabs += created.tabId
                await { runtime.workspaces.value.values.flatMap { it.tabs }.any { it.id == created.tabId && it.readiness == WorkspaceTerminalReadiness.READY } }
                val view = withContext(Dispatchers.Main) {
                    TerminalView(context, null).apply {
                        setTerminalViewClient(WorkspaceTerminalViewClient(context))
                        setTextSize(18)
                        setTypeface(Typeface.MONOSPACE)
                    }
                }
                assertTrue(runtime.bind(selection, created.tabId, view))
                withContext(Dispatchers.Main) {
                    view.layout(0, 0, 800, 600)
                    runtime.write(selection, created.tabId, "stty -echo; sleep 30 & echo \$! > pty-$label-child; test \"\$PWD\" = /workspace && printf 'PTY_%s_OK\\n' $label\n")
                }
                await { view.currentSession?.emulator?.screen?.transcriptText?.contains("PTY_${label}_OK") == true }
            }
            stage = "close first PTY"
            withTimeout(10_000) { runtime.close(root, selection, tabs.first()) }
            assertProcessGone(File(manager.filesDir(root), "pty-ONE-child"))
            assertEquals(listOf(tabs.last()), runtime.workspaces.value.values.flatMap { it.tabs }.map { it.id })
            Os.kill(File(manager.filesDir(root), "pty-TWO-child").readText().trim().toInt(), 0)
            stage = "close remaining PTY"
            withTimeout(10_000) { runtime.closeWorkspace(root) }
            assertProcessGone(File(manager.filesDir(root), "pty-TWO-child"))
            assertTrue(runtime.workspaces.value.values.all { it.tabs.isEmpty() })
        } catch (error: Throwable) {
            throw AssertionError("PRoot acceptance failed at $stage", error)
        } finally {
            withContext(NonCancellable) { runtime.closeWorkspace(root) }
            scope.cancel()
            withContext(Dispatchers.IO) {
                manager.deleteWorkspace(root)
                stateDir.deleteRecursively()
            }
        }
    }

    @Test
    fun sixteenKPageHostRejectsFourKGuestBeforeReplacingExistingFiles() = runBlocking {
        val url = InstrumentationRegistry.getArguments().getString("prootRootfsUrl")
        assumeTrue("Supply prootRootfsUrl for a matching-ABI Rootfs acceptance run", !url.isNullOrBlank())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val native = File(context.applicationInfo.nativeLibraryDir)
        assumeTrue("This rejection fixture is for x86_64 16 KB hosts",
            RootfsCompatibility(native).architecture == RootfsCompatibility.Architecture.X86_64 &&
                Os.sysconf(OsConstants._SC_PAGESIZE) == 16384L)
        val root = "proot-page-test-${UUID.randomUUID()}"
        val manager = WorkspaceManager(File(context.filesDir, "workspaces"))
        manager.ensureWorkspace(root)
        val preserved = File(manager.linuxDir(root), "preserved").apply { writeText("original") }
        try {
            withContext(Dispatchers.IO) {
                try {
                    RootfsInstaller(manager, native).install(root, requireNotNull(url))
                    fail("A 4 KB guest must not be installed on this 16 KB host")
                } catch (expected: IllegalArgumentException) {
                    assertTrue(expected.message, expected.message.orEmpty().contains("16384"))
                }
                assertEquals("original", preserved.readText())
                assertFalse(File(manager.linuxDir(root), "usr/bin/env").exists())
            }
        } finally { withContext(Dispatchers.IO) { manager.deleteWorkspace(root) } }
    }

    private suspend fun await(predicate: () -> Boolean) = withTimeout(15_000) {
        while (!withContext(Dispatchers.Main) { predicate() }) delay(20)
    }

    private suspend fun assertProcessGone(pidFile: File) = withTimeout(5_000) {
        val pid = pidFile.readText().trim().toInt()
        while (true) {
            try {
                Os.kill(pid, 0)
            } catch (error: ErrnoException) {
                assertEquals(OsConstants.ESRCH, error.errno)
                return@withTimeout
            }
            val status = try {
                File("/proc/$pid/status").readText()
            } catch (error: java.io.IOException) {
                try {
                    Os.kill(pid, 0)
                } catch (gone: ErrnoException) {
                    assertEquals(OsConstants.ESRCH, gone.errno)
                    return@withTimeout
                }
                throw error
            }
            val fields = status.lineSequence().mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator < 0) null else line.substring(0, separator) to line.substring(separator + 1).trim()
            }.toMap()
            // A tracer can finish reaping before init removes its orphaned zombie's PID.
            // Any running, stopped, or still-traced child is an immediate ownership failure.
            assertTrue("Guest process $pid survived its owner: $status", fields["State"]?.startsWith("Z ") == true)
            assertEquals(status, "1", fields["PPid"])
            assertEquals(status, "0", fields["TracerPid"])
            delay(10)
        }
    }

    private fun hasTracer(linuxDir: File): Boolean = processArguments().any { arguments ->
        arguments.firstOrNull()?.endsWith("/libproot_exec.so") == true && linuxDir.absolutePath in arguments
    }

    private fun processArguments(): List<List<String>> = File("/proc").listFiles().orEmpty().mapNotNull { entry ->
        if (entry.name.toIntOrNull() == null) return@mapNotNull null
        try { File(entry, "cmdline").readText().split('\u0000') }
        catch (_: java.io.IOException) { null }
    }
}
