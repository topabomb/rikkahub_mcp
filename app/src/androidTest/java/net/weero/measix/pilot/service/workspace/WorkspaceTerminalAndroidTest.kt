package net.weero.measix.pilot.service.workspace

import android.content.Context
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.autofill.AutofillValue
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.termux.view.TerminalView
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.enterprise.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkspaceTerminalAndroidTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun blockedNativeInputAndAutomaticResponseDoNotBlockMainOrClose() = runBlocking {
        withRuntime { runtime, sessions, host, _ ->
            val marker = File(host.root, "respond")
            host.command = "stty -echo -icanon; printf READY; while [ ! -f '${marker.absolutePath}' ]; do sleep 0.02; done; printf '\\033[6nRESPONSE_PROCESSED'; exec sleep 60"
            val selection = requireNotNull(sessions.readPresentation().selection)
            val tab = ready(runtime, selection)
            val view = bind(runtime, selection, tab)
            await { host.created.single().emulator?.screen?.transcriptText?.contains("READY") == true }
            withContext(Dispatchers.Main) { runtime.write(selection, tab, "x".repeat(512 * 1024)) }
            marker.writeText("ready")
            await { host.created.single().emulator?.screen?.transcriptText?.contains("RESPONSE_PROCESSED") == true }
            withTimeout(5_000) { runtime.close("root", selection, tab) }
            withContext(Dispatchers.Main) {
                assertTrue(view.isRetired)
                assertEquals(-1, host.created.single().pid)
                assertEquals(-9, host.created.single().exitStatus)
                assertTrue(runtime.workspaces.value.values.all { it.tabs.isEmpty() })
            }
        }
    }

    @Test
    fun overflowAndApplicationCancellationTerminateAnAlreadyBlockedWriter() = runBlocking {
        for (overflow in listOf(true, false)) withRuntime { runtime, sessions, host, scope ->
            host.command = "stty -echo -icanon; printf READY; exec sleep 60"
            val selection = requireNotNull(sessions.readPresentation().selection)
            val tab = ready(runtime, selection)
            bind(runtime, selection, tab)
            await { host.created.single().emulator?.screen?.transcriptText?.contains("READY") == true }
            withContext(Dispatchers.Main) { runtime.write(selection, tab, "x".repeat(512 * 1024)) }
            // The native input pipe has finite capacity and the process never reads it.
            delay(100)
            withContext(Dispatchers.Main) {
                if (overflow) runtime.write(selection, tab, "x".repeat(4 * 1024 * 1024))
                else scope.cancel()
            }
            await { host.created.single().pid == -1 && runtime.workspaces.value.values.all { it.tabs.isEmpty() } }
        }
    }

    @Test
    fun failedSwitchAndTabReattachmentUseFreshViewsWhileRetainingOriginalPty() = runBlocking {
        withRuntime { runtime, sessions, host, _ ->
            val example = context.assets.open(LocalEnterpriseSource.EXAMPLE_ASSET).use(EnterprisePackageCodec::decode)
            sessions.enrollLocal(example.identity, { example.identity }, { example })
            val original = requireNotNull(sessions.readPresentation().selection)
            val first = ready(runtime, original)
            val second = ready(runtime, original)
            val oldView = bind(runtime, original, first)
            await { host.created.first().emulator?.screen?.transcriptText?.contains("READY") == true }
            val pid = withContext(Dispatchers.Main) { host.created.first().pid }
            val oldInput = withContext(Dispatchers.Main) { oldView.onCreateInputConnection(EditorInfo()) }
            try {
                sessions.switchRealm(RealmSwitchRequest(original, RealmAccess.Personal)) {
                    runtime.revokeViewports(it)
                    throw java.io.IOException("portal close failed")
                }
                fail("Expected failed switch")
            } catch (_: java.io.IOException) { }
            val refreshed = requireNotNull(sessions.readPresentation().selection)
            assertEquals(original.access, refreshed.access)
            assertNotEquals(original, refreshed)
            withContext(Dispatchers.Main) {
                assertTrue(oldView.isRetired)
                oldInput?.commitText("RETIRED_INPUT", 1)
                oldInput?.finishComposingText()
                oldView.autofill(AutofillValue.forText("RETIRED_AUTOFILL"))
                oldView.onKeyDown(KeyEvent.KEYCODE_A, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A))
                assertEquals(pid, host.created.first().pid)
            }
            val replacement = bind(runtime, refreshed, first)
            withContext(Dispatchers.Main) { runtime.unbind(first, replacement) }
            val peerView = bind(runtime, refreshed, second)
            withContext(Dispatchers.Main) { runtime.unbind(second, peerView) }
            val back = bind(runtime, refreshed, first)
            withContext(Dispatchers.Main) {
                assertTrue(replacement.isRetired)
                assertNotSame(replacement, back)
                assertEquals(pid, host.created.first().pid)
                runtime.write(refreshed, first, "CURRENT_INPUT\n")
            }
            await { host.created.first().emulator.screen.transcriptText.contains("CURRENT_INPUT") }
            withContext(Dispatchers.Main) {
                assertFalse(host.created.first().emulator.screen.transcriptText.contains("RETIRED_"))
            }
        }
    }

    @Test
    fun multibyteImePasteIsBufferedAsBytesWithoutRejectingItsCharacterCount() = runBlocking {
        withRuntime { runtime, sessions, host, _ ->
            val selection = requireNotNull(sessions.readPresentation().selection)
            val tab = ready(runtime, selection)
            val view = bind(runtime, selection, tab)
            await { host.created.single().emulator?.screen?.transcriptText?.contains("READY") == true }
            withContext(Dispatchers.Main) {
                val input = requireNotNull(view.onCreateInputConnection(EditorInfo()))
                assertTrue(input.commitText("中".repeat(8192) + "\n中文🙂_COMPLETE\n", 1))
            }
            await { host.created.single().emulator.screen.transcriptText.contains("中文🙂_COMPLETE") }
            withContext(Dispatchers.Main) {
                assertTrue(host.created.single().pid > 0)
                assertFalse(view.isRetired)
            }
        }
    }

    @Test
    fun inputWaitsForBusySessionAndPreservesOrder() = runBlocking {
        withRuntime { runtime, sessions, host, _ ->
            val selection = requireNotNull(sessions.readPresentation().selection)
            val tab = ready(runtime, selection)
            bind(runtime, selection, tab)
            await { host.created.single().emulator?.screen?.transcriptText?.contains("READY") == true }
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val holder = launch {
                sessions.withSelectedRealmSelection(selection) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            entered.await()
            try {
                withContext(Dispatchers.Main) {
                    runtime.write(selection, tab, "FIRST_")
                    runtime.write(selection, tab, "SECOND\n")
                }
            } finally { release.complete(Unit) }
            holder.join()
            await { host.created.single().emulator.screen.transcriptText.contains("FIRST_SECOND") }
        }
    }

    @Test
    fun retiredBeforeFirstLayoutCannotStartAPty() = runBlocking {
        withRuntime { runtime, sessions, host, _ ->
            val selection = requireNotNull(sessions.readPresentation().selection)
            val tab = ready(runtime, selection)
            val view = withContext(Dispatchers.Main) { newView() }
            assertTrue(runtime.bind(selection, tab, view))
            runtime.revokeViewports(selection.access)
            withContext(Dispatchers.Main) { view.layout(0, 0, 800, 600) }
            delay(50)
            withContext(Dispatchers.Main) { assertEquals(0, host.created.single().pid) }
            runtime.close("root", selection, tab)
        }
    }

    private suspend fun ready(runtime: WorkspaceTerminalRuntime, selection: RealmSelection): String {
        val result = runtime.create("root", selection) { it() } as WorkspaceTerminalCreateResult.Created
        await { runtime.workspaces.value.values.flatMap { it.tabs }.any { it.id == result.tabId && it.readiness == WorkspaceTerminalReadiness.READY } }
        return result.tabId
    }

    private fun newView() = TerminalView(context, null).apply {
        setTerminalViewClient(WorkspaceTerminalViewClient(context))
        setTextSize(18)
        setTypeface(Typeface.MONOSPACE)
    }

    private suspend fun bind(runtime: WorkspaceTerminalRuntime, selection: RealmSelection, tab: String): TerminalView {
        val view = withContext(Dispatchers.Main) { newView() }
        assertTrue(runtime.bind(selection, tab, view))
        withContext(Dispatchers.Main) { view.layout(0, 0, 800, 600) }
        return view
    }

    private suspend fun await(predicate: () -> Boolean) = withTimeout(5_000) {
        while (!withContext(Dispatchers.Main) { predicate() }) delay(20)
    }

    private suspend fun withRuntime(block: suspend (WorkspaceTerminalRuntime, EnterpriseSessionController, NativeHost, AppScope) -> Unit) {
        val root = File(context.cacheDir, "pty-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val sessions = EnterpriseSessionController(EnterpriseAppliedStore(File(root, "enterprise"))).also { it.recover() }
        val scope = AppScope()
        val host = NativeHost(root)
        val runtime = WorkspaceTerminalRuntime(context, scope, sessions, host)
        try { block(runtime, sessions, host, scope) }
        finally {
            withContext(NonCancellable) { runtime.closeWorkspace("root") }
            scope.cancel()
            root.deleteRecursively()
        }
    }

    private class NativeHost(val root: File) : WorkspaceTerminalHost {
        var command = "stty -echo -icanon; printf READY; exec cat"
        val created = mutableListOf<WorkspacePtySession>()
        override fun prepare(context: Context, root: String) = true
        override fun create(context: Context, root: String, client: WorkspaceTerminalSessionClient): WorkspacePtySession =
            WorkspacePtySession("/system/bin/sh", this.root.absolutePath, arrayOf("-c", command),
                arrayOf("PATH=/system/bin", "TERM=xterm-256color"), 2000, client).also { created += it }
    }
}
