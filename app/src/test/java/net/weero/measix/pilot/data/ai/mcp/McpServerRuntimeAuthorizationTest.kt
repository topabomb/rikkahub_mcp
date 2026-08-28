package net.weero.measix.pilot.data.ai.mcp

import android.content.Context
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.datastore.EffectiveSettingsSnapshot
import net.weero.measix.pilot.data.datastore.ManagedConfigurationState
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsAccessIndex
import net.weero.measix.pilot.data.datastore.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class McpServerRuntimeAuthorizationTest {
    @Test
    fun `replacement authorization waits for cancellation and seals the previous lease`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val appScope = AppScope(dispatcher)
        val serverId = Uuid.random()
        val config = McpServerConfig.StreamableHTTPServer(
            id = serverId,
            commonOptions = McpCommonOptions(name = "oauth-server", oauth = McpOAuthState(enabled = true)),
            url = "https://oauth.example/mcp",
        )
        val settings = MutableStateFlow(
            EffectiveSettingsSnapshot(
                settings = Settings(mcpServers = listOf(config)),
                access = SettingsAccessIndex(),
                revision = 1L,
                managedState = ManagedConfigurationState.ABSENT,
            )
        )
        val settingsStore = mockk<SettingsStore> {
            every { effectiveSettings } returns settings
        }
        val oauthCoordinator = mockk<McpOAuthCoordinator>()
        val events = mutableListOf<String>()
        val calls = AtomicInteger()
        val allowFirstCancellationToFinish = CompletableDeferred<Unit>()
        coEvery { oauthCoordinator.touchState(serverId) } coAnswers {
            events += "seal"
        }
        coEvery { oauthCoordinator.authorize(any(), any()) } coAnswers {
            if (calls.incrementAndGet() == 1) {
                events += "first-start"
                try {
                    awaitCancellation()
                } catch (cancelled: CancellationException) {
                    events += "first-cancelled"
                    withContext(NonCancellable) { allowFirstCancellationToFinish.await() }
                    events += "first-finished"
                    throw cancelled
                }
            } else {
                events += "second-start"
                awaitCancellation()
            }
        }
        val networkMonitor = mockk<NetworkMonitor> {
            every { isOnline } returns MutableStateFlow(true)
        }
        val stateStore = McpRuntimeStateStore()
        lateinit var runtime: McpServerRuntime
        runtime = McpServerRuntime(
            serverId = serverId,
            settingsStore = settingsStore,
            catalogStore = mockk(relaxed = true),
            appScope = appScope,
            networkMonitor = networkMonitor,
            stateStore = stateStore,
            protocolClientFactory = mockk(relaxed = true),
            oauthCoordinator = oauthCoordinator,
            lifecycleOperationSemaphore = Semaphore(1),
            ioDispatcher = dispatcher,
            foregroundState = MutableStateFlow(true),
            policy = McpServerRuntimePolicy { 0L },
            logger = { _, _ -> },
        )
        stateStore.getOrCreate(serverId) { runtime }
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context

        try {
            runtime.startAuthorization(context)
            runCurrent()
            assertEquals(listOf("seal", "first-start"), events)

            runtime.startAuthorization(context)
            runCurrent()
            assertEquals(listOf("seal", "first-start", "first-cancelled"), events)
            assertFalse("second authorization must wait for the first Job to finish", "second-start" in events)

            allowFirstCancellationToFinish.complete(Unit)
            runCurrent()
            assertEquals(
                listOf("seal", "first-start", "first-cancelled", "first-finished", "seal", "second-start"),
                events,
            )
        } finally {
            appScope.cancel()
        }
    }
}
