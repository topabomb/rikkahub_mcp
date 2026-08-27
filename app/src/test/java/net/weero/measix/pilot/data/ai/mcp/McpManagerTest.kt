package net.weero.measix.pilot.data.ai.mcp

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpError
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.datastore.EffectiveSettingsSnapshot
import net.weero.measix.pilot.data.datastore.ManagedConfigurationState
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsAccessIndex
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.event.AppEventBus
import net.weero.measix.pilot.data.files.ArtifactStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.uuid.Uuid

/**
 * 用受控 transport/client seam 驱动真实 [McpManager] 状态机，覆盖 slot 互斥、
 * generation 失效与 reconcile 收敛行为；不复制生产逻辑。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class McpManagerTest {
    private val dispatcher = StandardTestDispatcher()
    private val effective = MutableStateFlowHolder()
    private val createdClients = mutableListOf<Client>()
    private val createdTransports = mutableListOf<FakeTransport>()
    private var connectFailure: Throwable? = null
    private val settingsStore = mockk<SettingsStore>()
    private lateinit var manager: McpManager

    @Before
    fun setUp() {
        effective.snapshot = snapshotOf(emptyList())
        every { settingsStore.effectiveSettings } returns effective.flow
        coEvery { settingsStore.updateLocal(any()) } coAnswers {
            val transform = firstArg<(Settings) -> Settings>()
            val next = transform(effective.snapshot.settings)
            effective.publish(next)
            next
        }
        val networkMonitor = mockk<NetworkMonitor>(relaxed = true)
        every { networkMonitor.isOnline } returns kotlinx.coroutines.flow.MutableStateFlow(true)
        manager = McpManager(
            settingsStore = settingsStore,
            appScope = AppScope(dispatcher),
            artifactStore = mockk<ArtifactStore>(relaxed = true),
            networkMonitor = networkMonitor,
            appEventBus = AppEventBus(),
            foregroundObserver = ForegroundObserver { },
            ioDispatcher = dispatcher,
            transportOverride = { FakeTransport().also(createdTransports::add) },
            clientOverride = { config -> fakeClient(config) },
        )
    }

    @After
    fun tearDown() {
        connectFailure = null
    }

    private fun fakeClient(config: McpServerConfig): Client {
        val client = mockk<Client>(relaxed = true)
        val transportSlot = io.mockk.slot<AbstractTransport>()
        coEvery { client.connect(capture(transportSlot)) } coAnswers {
            connectFailure?.let { throw it }
        }
        every { client.transport } answers { if (transportSlot.isCaptured) transportSlot.captured else null }
        coEvery { client.close() } returns Unit
        coEvery { client.listTools() } returns ListToolsResult(tools = emptyList())
        createdClients.add(client)
        return client
    }

    private fun snapshotOf(servers: List<McpServerConfig>): EffectiveSettingsSnapshot =
        EffectiveSettingsSnapshot(
            settings = Settings(mcpServers = servers),
            access = SettingsAccessIndex(),
            revision = effective.revision.incrementAndGet(),
            managedState = ManagedConfigurationState.ABSENT,
        )

    private fun emit(servers: List<McpServerConfig>) {
        effective.snapshot = snapshotOf(servers)
    }

    private fun serverConfig(
        url: String = "https://example.com/mcp",
        oauth: McpOAuthState? = null,
    ) = McpServerConfig.StreamableHTTPServer(
        id = SERVER_ID,
        commonOptions = McpCommonOptions(name = "demo", oauth = oauth),
        url = url,
    )

    @Test
    fun `enabled server connects once and tool-only changes do not rebuild the client`() = runTest(dispatcher) {
        emit(listOf(serverConfig()))
        advanceUntilIdle()
        assertEquals(McpStatus.Connected, manager.syncingStatus.value[SERVER_ID])
        assertEquals(1, createdClients.size)

        val toolToggle = serverConfig().copy(
            commonOptions = McpCommonOptions(
                name = "demo",
                tools = listOf(McpTool(name = "search", enable = false)),
            ),
        )
        emit(listOf(toolToggle))
        advanceUntilIdle()

        assertEquals(1, createdClients.size)
        assertEquals(McpStatus.Connected, manager.syncingStatus.value[SERVER_ID])
        assertSame(createdClients.single(), manager.getClient(SERVER_ID))
    }

    @Test
    fun `connection parameter change rebuilds the client and closes the old one`() = runTest(dispatcher) {
        emit(listOf(serverConfig()))
        advanceUntilIdle()
        emit(listOf(serverConfig(url = "https://example.com/other")))
        advanceUntilIdle()

        assertEquals(2, createdClients.size)
        assertEquals(McpStatus.Connected, manager.syncingStatus.value[SERVER_ID])
        assertSame(createdClients.last(), manager.getClient(SERVER_ID))
        coVerify { createdClients.first().close() }
    }

    @Test
    fun `stale transport callback from a replaced client cannot trigger reconnect`() = runTest(dispatcher) {
        emit(listOf(serverConfig()))
        advanceUntilIdle()
        emit(listOf(serverConfig(url = "https://example.com/other")))
        advanceUntilIdle()
        assertEquals(2, createdClients.size)

        createdTransports.first().simulateClose()
        advanceUntilIdle()

        assertEquals(2, createdClients.size)
        assertEquals(McpStatus.Connected, manager.syncingStatus.value[SERVER_ID])
        assertSame(createdClients.last(), manager.getClient(SERVER_ID))
    }

    @Test
    fun `transport close of the live client schedules one reconnect`() = runTest(dispatcher) {
        emit(listOf(serverConfig()))
        advanceUntilIdle()

        createdTransports.single().simulateClose()
        advanceUntilIdle()

        assertEquals(2, createdClients.size)
        assertEquals(McpStatus.Connected, manager.syncingStatus.value[SERVER_ID])
        assertSame(createdClients.last(), manager.getClient(SERVER_ID))

        createdTransports.first().simulateClose()
        advanceUntilIdle()
        assertEquals(2, createdClients.size)
        assertSame(createdClients.last(), manager.getClient(SERVER_ID))
    }

    @Test
    fun `stale remove from an older reconcile cannot tear down the re-added connection`() = runTest(dispatcher) {
        emit(listOf(serverConfig()))
        advanceUntilIdle()
        assertEquals(1, createdClients.size)

        // 让一个 refreshConnections 持有 slot mutex 挂起，随后排入 remove → re-add 两个 reconcile；
        // 无重校验的 remove 会在 add 之后执行并拆除新连接（表现为重建第二个 client）。
        val gate = CompletableDeferred<Unit>()
        val gateOpen = AtomicBoolean(true)
        coEvery { settingsStore.updateLocal(any()) } coAnswers {
            if (gateOpen.compareAndSet(true, false)) gate.await()
            val transform = firstArg<(Settings) -> Settings>()
            val next = transform(effective.snapshot.settings)
            effective.publish(next)
            next
        }
        launch { manager.refreshConnections() }
        advanceUntilIdle()

        emit(emptyList())
        advanceUntilIdle()
        emit(listOf(serverConfig()))
        advanceUntilIdle()

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, createdClients.size)
        assertEquals(McpStatus.Connected, manager.syncingStatus.value[SERVER_ID])
        assertSame(createdClients.single(), manager.getClient(SERVER_ID))
    }

    @Test
    fun `re-add during client close keeps the mapped slot as the live owner`() = runTest(dispatcher) {
        emit(listOf(serverConfig()))
        advanceUntilIdle()
        val first = createdClients.single()
        val closeGate = CompletableDeferred<Unit>()
        coEvery { first.close() } coAnswers { closeGate.await() }

        emit(emptyList())
        advanceUntilIdle()
        emit(listOf(serverConfig()))
        advanceUntilIdle()

        closeGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, createdClients.size)
        assertEquals(McpStatus.Connected, manager.syncingStatus.value[SERVER_ID])
        assertSame(createdClients.last(), manager.getClient(SERVER_ID))
        coVerify { first.close() }
    }

    @Test
    fun `url change on a server awaiting authorization reconnects`() = runTest(dispatcher) {
        connectFailure = StreamableHttpError(code = 401, message = "Unauthorized")
        emit(listOf(serverConfig(oauth = McpOAuthState(enabled = true, clientId = "id"))))
        advanceUntilIdle()
        assertEquals(McpStatus.NeedsAuthorization, manager.syncingStatus.value[SERVER_ID])

        connectFailure = null
        emit(listOf(serverConfig(url = "https://example.com/other")))
        advanceUntilIdle()

        assertEquals(2, createdClients.size)
        assertEquals(McpStatus.Connected, manager.syncingStatus.value[SERVER_ID])
    }

    @Test
    fun `oauth state is not written when the server url changed during authorization`() = runTest(dispatcher) {
        val config = serverConfig(url = "https://a.example/mcp")
        emit(listOf(config))
        val token = McpOAuthState(enabled = true, accessToken = "token")

        val resource = McpOAuthClient.canonicalResource("https://a.example/mcp")
        assertTrue(manager.persistOAuthStateFor(resource, config.id, token))
        assertEquals(token, effective.snapshot.settings.mcpServers.single().commonOptions.oauth)

        emit(listOf(config.copy(url = "https://b.example/mcp")))
        assertFalse(manager.persistOAuthStateFor(resource, config.id, token))
        assertNull(effective.snapshot.settings.mcpServers.single().commonOptions.oauth)
    }

    @Test
    fun `token refresh is discarded when the server url changes during the request`() = runTest(dispatcher) {
        val refreshGate = CompletableDeferred<Unit>()
        val oauthClient = mockk<McpOAuthClient>(relaxed = true)
        val refreshCalls = java.util.concurrent.atomic.AtomicInteger(0)
        coEvery {
            oauthClient.refreshToken(any(), any(), any(), any(), any(), any())
        } coAnswers {
            val call = refreshCalls.incrementAndGet()
            if (call == 1) {
                refreshGate.await()
                McpOAuthClient.TokenResponse(accessToken = "token-for-old-url")
            } else {
                McpOAuthClient.TokenResponse(accessToken = "token-for-new-url")
            }
        }
        val expiredOauth = McpOAuthState(
            enabled = true,
            clientId = "id",
            refreshToken = "refresh",
            tokenEndpoint = "https://a.example/token",
            expiresAt = 1L,
        )
        val gatedManager = McpManager(
            settingsStore = settingsStore,
            appScope = AppScope(dispatcher),
            artifactStore = mockk<ArtifactStore>(relaxed = true),
            networkMonitor = mockk<NetworkMonitor>(relaxed = true).also {
                every { it.isOnline } returns kotlinx.coroutines.flow.MutableStateFlow(true)
            },
            appEventBus = AppEventBus(),
            foregroundObserver = ForegroundObserver { },
            ioDispatcher = dispatcher,
            transportOverride = { FakeTransport().also(createdTransports::add) },
            clientOverride = { config -> fakeClient(config) },
            oauthClientOverride = oauthClient,
        )
        emit(listOf(serverConfig(url = "https://a.example/mcp", oauth = expiredOauth)))
        val refreshJob = launch { gatedManager.refreshConnections() }
        advanceUntilIdle()
        emit(listOf(serverConfig(url = "https://b.example/mcp", oauth = expiredOauth)))
        refreshGate.complete(Unit)
        refreshJob.join()
        advanceUntilIdle()
        val stored = effective.snapshot.settings.mcpServers.single().commonOptions.oauth
        assertEquals("refresh", stored?.refreshToken)
        assertTrue(stored?.accessToken != "token-for-old-url")
    }

    @Test
    fun `token refresh cancellation is not swallowed`() = runTest(dispatcher) {
        val refreshGate = CompletableDeferred<Unit>()
        val oauthClient = mockk<McpOAuthClient>(relaxed = true)
        coEvery {
            oauthClient.refreshToken(any(), any(), any(), any(), any(), any())
        } coAnswers {
            refreshGate.await()
            McpOAuthClient.TokenResponse(accessToken = "should-not-persist")
        }
        val expiredOauth = McpOAuthState(
            enabled = true,
            clientId = "id",
            refreshToken = "refresh",
            tokenEndpoint = "https://a.example/token",
            expiresAt = 1L,
        )
        effective.snapshot = snapshotOf(listOf(serverConfig(url = "https://a.example/mcp", oauth = expiredOauth)))
        val gatedManager = McpManager(
            settingsStore = settingsStore,
            appScope = AppScope(StandardTestDispatcher()),
            artifactStore = mockk<ArtifactStore>(relaxed = true),
            networkMonitor = mockk<NetworkMonitor>(relaxed = true).also {
                every { it.isOnline } returns kotlinx.coroutines.flow.MutableStateFlow(true)
            },
            appEventBus = AppEventBus(),
            foregroundObserver = ForegroundObserver { },
            ioDispatcher = dispatcher,
            transportOverride = { FakeTransport().also(createdTransports::add) },
            clientOverride = { config -> fakeClient(config) },
            oauthClientOverride = oauthClient,
        )
        val job = launch { gatedManager.refreshConnections() }
        advanceUntilIdle()
        job.cancel()
        advanceUntilIdle()
        assertTrue(job.isCancelled)
        val stored = effective.snapshot.settings.mcpServers.single().commonOptions.oauth
        assertEquals("refresh", stored?.refreshToken)
        assertNull(stored?.accessToken)
    }

    @Test
    fun `two servers publish independent statuses without regressing a neighbor`() = runTest(dispatcher) {
        val secondId = Uuid.random()
        val second = McpServerConfig.StreamableHTTPServer(
            id = secondId,
            commonOptions = McpCommonOptions(name = "other"),
            url = "https://other.example/mcp",
        )
        emit(listOf(serverConfig(), second))
        advanceUntilIdle()
        assertEquals(McpStatus.Connected, manager.syncingStatus.value[SERVER_ID])
        assertEquals(McpStatus.Connected, manager.syncingStatus.value[secondId])
        emit(listOf(serverConfig()))
        advanceUntilIdle()
        assertEquals(McpStatus.Connected, manager.syncingStatus.value[SERVER_ID])
        assertNull(manager.syncingStatus.value[secondId])
    }

    @Test
    fun `calculateBackoffDelay uses exponential backoff capped at the maximum`() {
        assertEquals(1000L, manager.calculateBackoffDelay(1))
        assertEquals(2000L, manager.calculateBackoffDelay(2))
        assertEquals(16000L, manager.calculateBackoffDelay(5))
        assertEquals(30000L, manager.calculateBackoffDelay(6))
        assertEquals(30000L, manager.calculateBackoffDelay(50))
    }

    @Test
    fun `isConnectionError separates authorization from connection failures`() {
        assertTrue(manager.isConnectionError(java.io.IOException("network reset")))
        assertTrue(manager.isConnectionError(StreamableHttpError(code = 503, message = "Service Unavailable")))
        assertTrue(manager.isConnectionError(RuntimeException("Connection refused")))
        assertFalse(manager.isConnectionError(StreamableHttpError(code = 401, message = "Unauthorized")))
        assertFalse(manager.isConnectionError(RuntimeException("Tool not found")))
    }

    private companion object {
        val SERVER_ID = Uuid.random()
    }
}

private class FakeTransport : AbstractTransport() {
    override suspend fun start() = Unit
    override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) = Unit
    override suspend fun close() = Unit

    fun simulateClose() = invokeOnCloseCallback()
    fun simulateError(error: Throwable) {
        _onError(error)
    }
}

/** 快照容器：revision 只增不减，保证 collector 每次写入都收到新值。 */
private class MutableStateFlowHolder {
    val revision = java.util.concurrent.atomic.AtomicLong(0)
    private val _flow = kotlinx.coroutines.flow.MutableStateFlow(
        EffectiveSettingsSnapshot(
            settings = Settings(),
            access = SettingsAccessIndex(),
            revision = 0,
            managedState = ManagedConfigurationState.ABSENT,
        ),
    )
    val flow: kotlinx.coroutines.flow.StateFlow<EffectiveSettingsSnapshot> = _flow

    var snapshot: EffectiveSettingsSnapshot
        get() = _flow.value
        set(value) {
            _flow.value = value
        }

    fun publish(settings: Settings) {
        snapshot = snapshot.copy(settings = settings, revision = revision.incrementAndGet())
    }
}
