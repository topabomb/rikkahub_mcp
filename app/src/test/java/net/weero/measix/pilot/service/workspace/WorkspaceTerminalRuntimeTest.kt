package net.weero.measix.pilot.service.workspace

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.just
import io.mockk.Runs
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import net.weero.measix.pilot.AppScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceTerminalRuntimeTest {
    @get:org.junit.Rule val temporary = org.junit.rules.TemporaryFolder()
    private suspend fun sessionOwner() = net.weero.measix.pilot.data.enterprise.EnterpriseSessionController(
        net.weero.measix.pilot.data.enterprise.EnterpriseAppliedStore(temporary.newFolder())).also { it.recover() }
    private val selection = net.weero.measix.pilot.data.enterprise.RealmSelection(net.weero.measix.pilot.data.enterprise.RealmAccess.Personal, 0)
    private val owner = WorkspaceTerminalOwner("workspace", selection.access)
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `tab commands have one ordered owner and enforce the resource limit`() = runTest(dispatcher) {
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        every { context.filesDir } returns File(System.getProperty("java.io.tmpdir"), "missing-terminal-root-${System.nanoTime()}")
        val host = object : WorkspaceTerminalHost {
            override fun prepare(context: Context, root: String) = true
            override fun create(context: Context, root: String, client: WorkspaceTerminalSessionClient): WorkspacePtySession =
                mockk { every { pid } returns 0 }
        }
        val runtime = WorkspaceTerminalRuntime(context, AppScope(dispatcher), sessionOwner(), host)
        val created = (1..WorkspaceTerminalRuntime.MAX_TABS_PER_WORKSPACE).map {
            runtime.createForTest("workspace") as WorkspaceTerminalCreateResult.Created
        }

        assertTrue(runtime.createForTest("workspace") is WorkspaceTerminalCreateResult.LimitReached)
        assertEquals(created.last().tabId, runtime.workspaces.value.getValue(owner).selectedTabId)

        runtime.select("workspace", selection, created.first().tabId)
        assertEquals(created.first().tabId, runtime.workspaces.value.getValue(owner).selectedTabId)

        runtime.rename("workspace", selection, created.first().tabId, "Build")
        assertEquals("Build", runtime.workspaces.value.getValue(owner).tabs.first().customTitle)

        runtime.rename("workspace", selection, created.first().tabId, "  ")
        assertEquals(null, runtime.workspaces.value.getValue(owner).tabs.first().customTitle)

        val reversed = created.map { it.tabId }.reversed()
        runtime.reorder("workspace", selection, reversed)
        assertEquals(reversed, runtime.workspaces.value.getValue(owner).tabs.map { it.id })

        runtime.close("workspace", selection, created.first().tabId)
        assertTrue(runtime.workspaces.value[owner]?.tabs.orEmpty().none { it.id == created.first().tabId })

        runtime.closeWorkspace("workspace")
        assertTrue(owner !in runtime.workspaces.value)
    }

    @Test
    fun `unstarted session is closed without signaling process group zero`() = runTest(dispatcher) {
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        val session = mockk<WorkspacePtySession>()
        every { session.finishIfRunning() } just Runs
        every { session.pid } returns 0
        val host = object : WorkspaceTerminalHost {
            override fun prepare(context: Context, root: String) = true
            override fun create(
                context: Context,
                root: String,
                client: WorkspaceTerminalSessionClient,
            ) = session
        }
        val runtime = WorkspaceTerminalRuntime(context, AppScope(dispatcher), sessionOwner(), host)
        val created = runtime.createForTest("workspace") as WorkspaceTerminalCreateResult.Created
        advanceUntilIdle()
        withTimeout(5_000) {
            runtime.workspaces.map { it[owner]?.tabs?.singleOrNull()?.readiness }
                .first { it == WorkspaceTerminalReadiness.READY }
        }

        runtime.close("workspace", selection, created.tabId)

        verify(exactly = 0) { session.finishIfRunning() }
        assertTrue(owner !in runtime.workspaces.value)
    }

    @Test
    fun `failed close retains closing identity and retry waits for actual termination`() = runTest(dispatcher) {
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        val session = mockk<WorkspacePtySession>()
        every { session.pid } returns 42
        lateinit var client: WorkspaceTerminalSessionClient
        var attempts = 0
        every { session.finishIfRunning() } answers {
            if (++attempts == 1) throw IllegalStateException("kill failed")
            client.onSessionFinished(session)
        }
        val host = object : WorkspaceTerminalHost {
            override fun prepare(context: Context, root: String) = true
            override fun create(context: Context, root: String, callbacks: WorkspaceTerminalSessionClient): WorkspacePtySession {
                client = callbacks
                return session
            }
        }
        val runtime = WorkspaceTerminalRuntime(context, AppScope(dispatcher), sessionOwner(), host)
        val created = runtime.createForTest("workspace") as WorkspaceTerminalCreateResult.Created
        withTimeout(5_000) {
            runtime.workspaces.first { it[owner]?.tabs?.singleOrNull()?.readiness == WorkspaceTerminalReadiness.READY }
        }
        try {
            runtime.close("workspace", selection, created.tabId)
            org.junit.Assert.fail("Expected close failure")
        } catch (error: IllegalStateException) { assertEquals("kill failed", error.message) }
        assertEquals(WorkspaceTerminalReadiness.CLOSING, runtime.workspaces.value.getValue(owner).tabs.single().readiness)
        runtime.close("workspace", selection, created.tabId)
        assertEquals(2, attempts)
        assertTrue(runtime.workspaces.value[owner]?.tabs.orEmpty().isEmpty())
    }

    @Test
    fun `concurrent creates are serialized and cannot exceed the workspace limit`() = runTest(dispatcher) {
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        val host = mockk<WorkspaceTerminalHost>()
        val session = mockk<WorkspacePtySession>()
        every { session.finishIfRunning() } just Runs
        every { session.pid } returns 0
        every { host.prepare(any(), any()) } returns true
        every { host.create(any(), any(), any()) } returns session
        val runtime = WorkspaceTerminalRuntime(context, AppScope(dispatcher), sessionOwner(), host)

        val results = (1..12).map { async { runtime.createForTest("workspace") } }.awaitAll()

        assertEquals(
            WorkspaceTerminalRuntime.MAX_TABS_PER_WORKSPACE,
            results.count { it is WorkspaceTerminalCreateResult.Created },
        )
        assertEquals(
            WorkspaceTerminalRuntime.MAX_TABS_PER_WORKSPACE,
            runtime.workspaces.value.getValue(owner).tabs.size,
        )
        runtime.closeWorkspace("workspace")
    }

    @Test
    fun `closing a tab during preparation prevents the session from being created`() = runTest(dispatcher) {
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        val preparationStarted = CountDownLatch(1)
        val allowPreparationToFinish = CountDownLatch(1)
        val host = mockk<WorkspaceTerminalHost>()
        every { host.prepare(context, "workspace") } answers {
            preparationStarted.countDown()
            assertTrue(allowPreparationToFinish.await(5, TimeUnit.SECONDS))
            true
        }
        val runtime = WorkspaceTerminalRuntime(context, AppScope(dispatcher), sessionOwner(), host)
        val created = runtime.createForTest("workspace") as WorkspaceTerminalCreateResult.Created

        runCurrent()
        assertTrue(preparationStarted.await(5, TimeUnit.SECONDS))
        val closeJob = launch { runtime.close("workspace", selection, created.tabId) }
        runCurrent()

        allowPreparationToFinish.countDown()
        closeJob.join()
        verify(exactly = 0) { host.create(any(), any(), any()) }
    }

    @Test
    fun `shell exit removes the tab through the runtime owner`() = runTest(dispatcher) {
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        val session = mockk<WorkspacePtySession>()
        every { session.pid } returns -1
        var capturedClient: WorkspaceTerminalSessionClient? = null
        val host = object : WorkspaceTerminalHost {
            override fun prepare(context: Context, root: String) = true
            override fun create(
                context: Context,
                root: String,
                client: WorkspaceTerminalSessionClient,
            ): WorkspacePtySession {
                capturedClient = client
                return session
            }
        }
        val runtime = WorkspaceTerminalRuntime(context, AppScope(dispatcher), sessionOwner(), host)
        runtime.createForTest("workspace")
        runCurrent()
        withTimeout(5_000) {
            runtime.workspaces.map { it[owner]?.tabs?.singleOrNull()?.readiness }
                .first { it == WorkspaceTerminalReadiness.READY }
        }

        requireNotNull(capturedClient).onSessionFinished(session)
        advanceUntilIdle()

        assertTrue(owner !in runtime.workspaces.value)
    }

    @Test
    fun `rootfs preparation failure removes the tab and publishes typed not-ready state`() = runTest(dispatcher) {
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        val host = mockk<WorkspaceTerminalHost>()
        every { host.prepare(context, "workspace") } returns false
        val runtime = WorkspaceTerminalRuntime(context, AppScope(dispatcher), sessionOwner(), host)

        runtime.createForTest("workspace")
        advanceUntilIdle()
        val failure = withTimeout(5_000) {
            runtime.workspaces.map { it[owner]?.lastFailure }.first { it != null }
        }

        assertTrue(runtime.workspaces.value.getValue(owner).tabs.isEmpty())
        assertTrue(failure?.reason is WorkspaceTerminalFailureReason.NotReady)
        verify(exactly = 0) { host.create(any(), any(), any()) }
    }

    @Test
    fun `session creation failure removes the tab and publishes an unexpected reason`() = runTest(dispatcher) {
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        val expected = IllegalStateException("pty failed")
        val host = mockk<WorkspaceTerminalHost>()
        every { host.prepare(context, "workspace") } returns true
        every { host.create(context, "workspace", any()) } throws expected
        val runtime = WorkspaceTerminalRuntime(context, AppScope(dispatcher), sessionOwner(), host)

        runtime.createForTest("workspace")
        advanceUntilIdle()
        val failure = withTimeout(5_000) {
            runtime.workspaces.map { it[owner]?.lastFailure }.first { it != null }
        }

        assertTrue(runtime.workspaces.value.getValue(owner).tabs.isEmpty())
        assertEquals(WorkspaceTerminalFailureReason.Unexpected, failure?.reason)
    }

}

private suspend fun WorkspaceTerminalRuntime.createForTest(root: String): WorkspaceTerminalCreateResult =
    create(root, net.weero.measix.pilot.data.enterprise.RealmSelection(net.weero.measix.pilot.data.enterprise.RealmAccess.Personal, 0)) { preparation -> preparation() }
