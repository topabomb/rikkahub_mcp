package net.weero.measix.pilot.service.workspace

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.just
import io.mockk.Runs
import com.termux.terminal.TerminalSession
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
        val runtime = WorkspaceTerminalRuntime(context, AppScope(dispatcher))
        val created = (1..WorkspaceTerminalRuntime.MAX_TABS_PER_WORKSPACE).map {
            runtime.createForTest("workspace") as WorkspaceTerminalCreateResult.Created
        }

        assertTrue(runtime.createForTest("workspace") is WorkspaceTerminalCreateResult.LimitReached)
        assertEquals(created.last().tabId, runtime.workspaces.value.getValue("workspace").selectedTabId)

        runtime.select("workspace", created.first().tabId)
        assertEquals(created.first().tabId, runtime.workspaces.value.getValue("workspace").selectedTabId)

        runtime.rename("workspace", created.first().tabId, "Build")
        assertEquals("Build", runtime.workspaces.value.getValue("workspace").tabs.first().customTitle)

        runtime.rename("workspace", created.first().tabId, "  ")
        assertEquals(null, runtime.workspaces.value.getValue("workspace").tabs.first().customTitle)

        val reversed = created.map { it.tabId }.reversed()
        runtime.reorder("workspace", reversed)
        assertEquals(reversed, runtime.workspaces.value.getValue("workspace").tabs.map { it.id })

        runtime.close("workspace", created.first().tabId)
        assertTrue(runtime.workspaces.value["workspace"]?.tabs.orEmpty().none { it.id == created.first().tabId })

        runtime.closeWorkspace("workspace")
        assertTrue("workspace" !in runtime.workspaces.value)
    }

    @Test
    fun `successful session creation is closed exactly by the runtime owner`() = runTest(dispatcher) {
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        val session = mockk<TerminalSession>()
        every { session.finishIfRunning() } just Runs
        val host = object : WorkspaceTerminalHost {
            override fun prepare(context: Context, root: String) = true
            override fun create(
                context: Context,
                root: String,
                client: WorkspaceTerminalSessionClient,
            ) = session
        }
        val runtime = WorkspaceTerminalRuntime(context, AppScope(dispatcher), host)
        val created = runtime.createForTest("workspace") as WorkspaceTerminalCreateResult.Created
        advanceUntilIdle()
        withTimeout(5_000) {
            runtime.workspaces.map { it["workspace"]?.tabs?.singleOrNull()?.readiness }
                .first { it == WorkspaceTerminalReadiness.READY }
        }

        runtime.close("workspace", created.tabId)

        verify(exactly = 1) { session.finishIfRunning() }
        assertTrue("workspace" !in runtime.workspaces.value)
    }

    @Test
    fun `concurrent creates are serialized and cannot exceed the workspace limit`() = runTest(dispatcher) {
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        val host = mockk<WorkspaceTerminalHost>()
        val session = mockk<TerminalSession>()
        every { session.finishIfRunning() } just Runs
        every { host.prepare(any(), any()) } returns true
        every { host.create(any(), any(), any()) } returns session
        val runtime = WorkspaceTerminalRuntime(context, AppScope(dispatcher), host)

        val results = (1..12).map { async { runtime.createForTest("workspace") } }.awaitAll()

        assertEquals(
            WorkspaceTerminalRuntime.MAX_TABS_PER_WORKSPACE,
            results.count { it is WorkspaceTerminalCreateResult.Created },
        )
        assertEquals(
            WorkspaceTerminalRuntime.MAX_TABS_PER_WORKSPACE,
            runtime.workspaces.value.getValue("workspace").tabs.size,
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
        val runtime = WorkspaceTerminalRuntime(context, AppScope(dispatcher), host)
        val created = runtime.createForTest("workspace") as WorkspaceTerminalCreateResult.Created

        runCurrent()
        assertTrue(preparationStarted.await(5, TimeUnit.SECONDS))
        val closeJob = launch { runtime.close("workspace", created.tabId) }
        runCurrent()
        assertTrue("workspace" !in runtime.workspaces.value)

        allowPreparationToFinish.countDown()
        closeJob.join()
        verify(exactly = 0) { host.create(any(), any(), any()) }
    }

    @Test
    fun `shell exit removes the tab through the runtime owner`() = runTest(dispatcher) {
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        val session = mockk<TerminalSession>()
        var capturedClient: WorkspaceTerminalSessionClient? = null
        val host = object : WorkspaceTerminalHost {
            override fun prepare(context: Context, root: String) = true
            override fun create(
                context: Context,
                root: String,
                client: WorkspaceTerminalSessionClient,
            ): TerminalSession {
                capturedClient = client
                return session
            }
        }
        val runtime = WorkspaceTerminalRuntime(context, AppScope(dispatcher), host)
        runtime.createForTest("workspace")
        runCurrent()
        withTimeout(5_000) {
            runtime.workspaces.map { it["workspace"]?.tabs?.singleOrNull()?.readiness }
                .first { it == WorkspaceTerminalReadiness.READY }
        }

        requireNotNull(capturedClient).onSessionFinished(session)
        advanceUntilIdle()

        assertTrue("workspace" !in runtime.workspaces.value)
    }

    @Test
    fun `rootfs preparation failure removes the tab and publishes typed not-ready state`() = runTest(dispatcher) {
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        val host = mockk<WorkspaceTerminalHost>()
        every { host.prepare(context, "workspace") } returns false
        val runtime = WorkspaceTerminalRuntime(context, AppScope(dispatcher), host)

        runtime.createForTest("workspace")
        advanceUntilIdle()
        val failure = withTimeout(5_000) {
            runtime.workspaces.map { it["workspace"]?.lastFailure }.first { it != null }
        }

        assertTrue(runtime.workspaces.value.getValue("workspace").tabs.isEmpty())
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
        val runtime = WorkspaceTerminalRuntime(context, AppScope(dispatcher), host)

        runtime.createForTest("workspace")
        advanceUntilIdle()
        val failure = withTimeout(5_000) {
            runtime.workspaces.map { it["workspace"]?.lastFailure }.first { it != null }
        }

        assertTrue(runtime.workspaces.value.getValue("workspace").tabs.isEmpty())
        assertEquals(WorkspaceTerminalFailureReason.Unexpected, failure?.reason)
    }

}

private suspend fun WorkspaceTerminalRuntime.createForTest(root: String): WorkspaceTerminalCreateResult =
    create(root) { preparation -> preparation() }
