package net.weero.measix.pilot.data.ai.mcp

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpError
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsResult
import io.modelcontextprotocol.kotlin.sdk.types.McpException
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import io.modelcontextprotocol.kotlin.sdk.types.ToolListChangedNotification
import io.modelcontextprotocol.kotlin.sdk.types.Method.Defined.NotificationsToolsListChanged
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import net.weero.measix.pilot.AppScope
import me.rerere.ai.core.ToolExecutionFailure
import net.weero.measix.pilot.data.datastore.EffectiveSettingsSnapshot
import net.weero.measix.pilot.data.datastore.ManagedConfigurationState
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsAccessIndex
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.model.Assistant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * 用受控 transport/client seam 驱动真实 [McpRuntimeCoordinator] 状态机，覆盖 slot 互斥、
 * generation 失效与 reconcile 收敛行为；不复制生产逻辑。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class McpRuntimeCoordinatorTest {
    private val dispatcher = StandardTestDispatcher()
    private val effective = MutableStateFlowHolder()
    private val createdClients = mutableListOf<Client>()
    private val createdTransports = mutableListOf<FakeTransport>()
    private var connectFailure: Throwable? = null
    private val connectGates = mutableMapOf<Uuid, CompletableDeferred<Unit>>()
    private val connectStarted = linkedSetOf<Uuid>()
    private val toolListChangedHandlers = mutableMapOf<Uuid, (ToolListChangedNotification) -> Deferred<Unit>>()
    private var callToolGate: CompletableDeferred<Unit>? = null
    private var callToolResponder: suspend () -> CallToolResult = {
        CallToolResult(content = listOf(TextContent("tool-result")))
    }
    private var listToolsResponder: suspend (McpServerConfig, ListToolsRequest) -> ListToolsResult = { _, _ ->
        ListToolsResult(tools = listOf(serverTool("search")))
    }
    private val settingsStore = mockk<SettingsStore>()
    private val catalogs = MutableStateFlow<Map<Uuid, McpCatalogSnapshot>>(emptyMap())
    private val catalogStore = mockk<McpCatalogStore>()
    private lateinit var networkOnline: MutableStateFlow<Boolean>
    private var foregroundAction: (() -> Unit)? = null
    private lateinit var manager: McpRuntimeCoordinator
    private lateinit var oauthCoordinator: McpOAuthCoordinator

    @Before
    fun setUp() {
        catalogs.value = emptyMap()
        connectGates.clear()
        connectStarted.clear()
        toolListChangedHandlers.clear()
        callToolGate = null
        foregroundAction = null
        callToolResponder = { CallToolResult(content = listOf(TextContent("tool-result"))) }
        listToolsResponder = { _, _ -> ListToolsResult(tools = listOf(serverTool("search"))) }
        effective.snapshot = snapshotOf(emptyList())
        every { settingsStore.effectiveSettings } returns effective.flow
        every { catalogStore.catalogs } returns catalogs
        coEvery { catalogStore.commitCandidate(any()) } coAnswers {
            val candidate = firstArg<McpCatalogCandidate>()
            val previous = catalogs.value[candidate.serverId]
            if (candidate.tools.isEmpty()) {
                McpCatalogCommitResult.RejectedEmpty(
                    previous?.takeIf { it.definitionDigest == candidate.definitionDigest }
                )
            } else {
                val snapshot = McpCatalogSnapshot(
                    serverId = candidate.serverId,
                    revision = (previous?.revision ?: 0L) + 1,
                    definitionDigest = candidate.definitionDigest,
                    catalogDigest = candidate.tools.joinToString { it.name },
                    tools = candidate.tools,
                )
                catalogs.value = catalogs.value + (candidate.serverId to snapshot)
                McpCatalogCommitResult.Committed(snapshot, previous, snapshot.revision)
            }
        }
        coEvery { catalogStore.rollbackCommitted(any(), any(), any()) } coAnswers {
            val committed = firstArg<McpCatalogSnapshot>()
            val previous = secondArg<McpCatalogSnapshot?>()
            if (catalogs.value[committed.serverId] == committed) {
                catalogs.value = if (previous == null) {
                    catalogs.value - committed.serverId
                } else {
                    catalogs.value + (committed.serverId to previous)
                }
            }
        }
        coEvery { catalogStore.remove(any()) } returns Unit
        coEvery { settingsStore.updateLocal(any()) } coAnswers {
            val transform = firstArg<(Settings) -> Settings>()
            val next = transform(effective.snapshot.settings)
            effective.publish(next)
            next
        }
        networkOnline = MutableStateFlow(true)
        val networkMonitor = mockk<NetworkMonitor>(relaxed = true)
        every { networkMonitor.isOnline } returns networkOnline
        oauthCoordinator = McpOAuthCoordinator(
            settingsStore = settingsStore,
            appScope = AppScope(dispatcher),
            oauthClient = mockk(relaxed = true),
            oauthCallbackKeepAlive = NoOpOAuthCallbackKeepAlive,
            ioDispatcher = dispatcher,
            logger = { _, _ -> },
        )
        manager = McpRuntimeCoordinator(
            settingsStore = settingsStore,
            catalogStore = catalogStore,
            appScope = AppScope(dispatcher),
            artifactStore = mockk<ArtifactStore>(relaxed = true),
            networkMonitor = networkMonitor,
            foregroundObserver = ForegroundObserver { },
            ioDispatcher = dispatcher,
            transportOverride = { FakeTransport().also(createdTransports::add) },
            clientOverride = { config -> fakeClient(config) },
            oauthCallbackKeepAlive = NoOpOAuthCallbackKeepAlive,
            retryJitter = { it },
        )
    }

    @After
    fun tearDown() {
        connectFailure = null
    }

    private fun fakeClient(config: McpServerConfig): Client {
        val client = mockk<Client>(relaxed = true)
        val notificationHandler = slot<(ToolListChangedNotification) -> Deferred<Unit>>()
        every {
            client.setNotificationHandler<ToolListChangedNotification>(
                NotificationsToolsListChanged,
                capture(notificationHandler),
            )
        } answers {
            toolListChangedHandlers[config.id] = notificationHandler.captured
        }
        val transportSlot = io.mockk.slot<AbstractTransport>()
        coEvery { client.connect(capture(transportSlot)) } coAnswers {
            connectStarted += config.id
            connectGates[config.id]?.await()
            connectFailure?.let { throw it }
        }
        every { client.transport } answers { if (transportSlot.isCaptured) transportSlot.captured else null }
        every { client.serverCapabilities } returns ServerCapabilities(
            tools = ServerCapabilities.Tools(),
        )
        coEvery { client.close() } returns Unit
        coEvery { client.listTools(any()) } coAnswers {
            listToolsResponder(config, firstArg())
        }
        coEvery {
            client.callTool(any<CallToolRequest>(), any<RequestOptions>())
        } coAnswers {
            callToolGate?.await()
            callToolResponder()
        }
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
        enable: Boolean = true,
        oauth: McpOAuthState? = null,
        policies: List<McpToolPolicy> = emptyList(),
        headers: List<Pair<String, String>> = emptyList(),
    ) = McpServerConfig.StreamableHTTPServer(
        id = SERVER_ID,
        commonOptions = McpCommonOptions(
            enable = enable,
            name = "demo",
            headers = headers,
            oauth = oauth,
            toolPolicies = policies,
        ),
        url = url,
    )

    @Test
    fun `deleting a cold disabled definition removes its durable catalog`() = runTest(dispatcher) {
        emit(listOf(serverConfig(enable = false)))
        advanceUntilIdle()
        assertTrue(createdClients.isEmpty())

        emit(emptyList())
        advanceUntilIdle()

        coVerify(exactly = 1) { catalogStore.remove(SERVER_ID) }
    }

    @Test
    fun `disabling then deleting a definition removes its durable catalog once`() = runTest(dispatcher) {
        emit(listOf(serverConfig()))
        advanceUntilIdle()
        emit(listOf(serverConfig(enable = false)))
        advanceUntilIdle()
        coVerify(exactly = 0) { catalogStore.remove(SERVER_ID) }

        emit(emptyList())
        advanceUntilIdle()

        coVerify(exactly = 1) { catalogStore.remove(SERVER_ID) }
    }

    private fun serverTool(name: String) = Tool(
        name = name,
        inputSchema = ToolSchema(properties = JsonObject(emptyMap())),
    )

    @Test
    fun `enabled server connects once and tool-only changes do not rebuild the client`() = runTest(dispatcher) {
        emit(listOf(serverConfig()))
        advanceUntilIdle()
        assertTrue(manager.syncingStatus.value[SERVER_ID] is McpStatus.Ready)
        assertEquals(1, createdClients.size)

        val toolToggle = serverConfig().copy(
            commonOptions = McpCommonOptions(
                name = "demo",
                toolPolicies = listOf(McpToolPolicy(name = "search", enable = false)),
            ),
        )
        emit(listOf(toolToggle))
        advanceUntilIdle()

        assertEquals(1, createdClients.size)
        assertTrue(manager.syncingStatus.value[SERVER_ID] is McpStatus.Ready)
    }

    @Test
    fun `connection parameter change rebuilds the client and closes the old one`() = runTest(dispatcher) {
        emit(listOf(serverConfig()))
        advanceUntilIdle()
        emit(listOf(serverConfig(url = "https://example.com/other")))
        advanceUntilIdle()

        assertEquals(2, createdClients.size)
        assertTrue(manager.syncingStatus.value[SERVER_ID] is McpStatus.Ready)
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
        assertTrue(manager.syncingStatus.value[SERVER_ID] is McpStatus.Ready)
    }

    @Test
    fun `transport close of the live client schedules one reconnect`() = runTest(dispatcher) {
        emit(listOf(serverConfig()))
        advanceUntilIdle()

        createdTransports.single().simulateClose()
        advanceUntilIdle()

        assertEquals(2, createdClients.size)
        assertTrue(manager.syncingStatus.value[SERVER_ID] is McpStatus.Ready)

        createdTransports.first().simulateClose()
        advanceUntilIdle()
        assertEquals(2, createdClients.size)
    }

    @Test
    fun `remove then re-add creates exactly one replacement connection`() = runTest(dispatcher) {
        emit(listOf(serverConfig()))
        advanceUntilIdle()
        assertEquals(1, createdClients.size)

        emit(emptyList())
        advanceUntilIdle()
        emit(listOf(serverConfig()))
        advanceUntilIdle()
        assertEquals(2, createdClients.size)
        assertTrue(manager.syncingStatus.value[SERVER_ID] is McpStatus.Ready)
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
        assertTrue(manager.syncingStatus.value[SERVER_ID] is McpStatus.Ready)
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
        assertTrue(manager.syncingStatus.value[SERVER_ID] is McpStatus.Ready)
    }

    @Test
    fun `oauth state is not written when the server url changed during authorization`() = runTest(dispatcher) {
        val config = serverConfig(url = "https://a.example/mcp")
        emit(listOf(config))
        val token = McpOAuthState(enabled = true, accessToken = "token")

        val trustBoundary = config.oauthTrustBoundary()
        assertTrue(oauthCoordinator.persistStateFor(trustBoundary, config.id, 0L, token))
        assertEquals(token.copy(revision = 1L), effective.snapshot.settings.mcpServers.single().commonOptions.oauth)

        emit(listOf(config.copy(url = "https://b.example/mcp")))
        assertFalse(oauthCoordinator.persistStateFor(trustBoundary, config.id, 1L, token))
        assertNull(effective.snapshot.settings.mcpServers.single().commonOptions.oauth)
    }

    @Test
    fun `stale oauth completion cannot overwrite a newer revision on the same url`() = runTest(dispatcher) {
        val initial = McpOAuthState(revision = 4L, enabled = true, accessToken = "old")
        val config = serverConfig(oauth = initial)
        emit(listOf(config))
        val trustBoundary = config.oauthTrustBoundary()

        assertTrue(
            oauthCoordinator.persistStateFor(
                trustBoundary,
                config.id,
                expectedRevision = 4L,
                oauth = initial.copy(accessToken = "new"),
            )
        )
        assertFalse(
            oauthCoordinator.persistStateFor(
                trustBoundary,
                config.id,
                expectedRevision = 4L,
                oauth = initial.copy(accessToken = "stale"),
            )
        )

        val stored = effective.snapshot.settings.mcpServers.single().commonOptions.oauth
        assertEquals(5L, stored?.revision)
        assertEquals("new", stored?.accessToken)
    }

    @Test
    fun `oauth state is not written when static headers change during authorization`() = runTest(dispatcher) {
        val config = serverConfig(headers = listOf("X-Tenant" to "first"))
        emit(listOf(config))
        val token = McpOAuthState(enabled = true, accessToken = "old-principal-token")

        emit(listOf(serverConfig(headers = listOf("X-Tenant" to "second"))))

        assertFalse(
            oauthCoordinator.persistStateFor(
                expectedTrustBoundary = config.oauthTrustBoundary(),
                serverId = config.id,
                expectedRevision = 0L,
                oauth = token,
            )
        )
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
        val gatedManager = McpRuntimeCoordinator(
            settingsStore = settingsStore,
            catalogStore = catalogStore,
            appScope = AppScope(dispatcher),
            artifactStore = mockk<ArtifactStore>(relaxed = true),
            networkMonitor = mockk<NetworkMonitor>(relaxed = true).also {
                every { it.isOnline } returns kotlinx.coroutines.flow.MutableStateFlow(true)
            },
            foregroundObserver = ForegroundObserver { },
            ioDispatcher = dispatcher,
            transportOverride = { FakeTransport().also(createdTransports::add) },
            clientOverride = { config -> fakeClient(config) },
            oauthCallbackKeepAlive = NoOpOAuthCallbackKeepAlive,
            oauthClientOverride = oauthClient,
        )
        emit(listOf(serverConfig(url = "https://a.example/mcp", oauth = expiredOauth)))
        val refreshJob = launch { gatedManager.refreshAllRegisteredServers() }
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
    fun `cancelling a refresh waiter does not cancel the app scope session operation`() = runTest(dispatcher) {
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
        val gatedManager = McpRuntimeCoordinator(
            settingsStore = settingsStore,
            catalogStore = catalogStore,
            appScope = AppScope(dispatcher),
            artifactStore = mockk<ArtifactStore>(relaxed = true),
            networkMonitor = mockk<NetworkMonitor>(relaxed = true).also {
                every { it.isOnline } returns kotlinx.coroutines.flow.MutableStateFlow(true)
            },
            foregroundObserver = ForegroundObserver { },
            ioDispatcher = dispatcher,
            transportOverride = { FakeTransport().also(createdTransports::add) },
            clientOverride = { config -> fakeClient(config) },
            oauthCallbackKeepAlive = NoOpOAuthCallbackKeepAlive,
            oauthClientOverride = oauthClient,
        )
        val job = launch { gatedManager.refreshAllRegisteredServers() }
        runCurrent()
        job.cancel()
        assertTrue(job.isCancelled)
        refreshGate.complete(Unit)
        advanceUntilIdle()
        val stored = effective.snapshot.settings.mcpServers.single().commonOptions.oauth
        assertEquals("refresh", stored?.refreshToken)
        assertEquals("should-not-persist", stored?.accessToken)
    }

    @Test
    fun `oauth access token rotation does not change stable definition identity`() {
        val first = serverConfig(
            oauth = McpOAuthState(enabled = true, accessToken = "old-token"),
        )
        val second = serverConfig(
            oauth = McpOAuthState(enabled = true, accessToken = "new-token"),
        )

        assertEquals(first.mcpDefinitionDigest(), second.mcpDefinitionDigest())
    }

    @Test
    fun `manual authorization header change replaces stable definition identity`() {
        val first = serverConfig(
            headers = listOf("Authorization" to "Bearer first-token"),
        )
        val second = serverConfig(
            headers = listOf("Authorization" to "Bearer second-token"),
        )

        assertTrue(first.mcpDefinitionDigest() != second.mcpDefinitionDigest())
    }

    @Test
    fun `error and close burst schedules only one reconnect for the generation`() = runTest(dispatcher) {
        emit(listOf(serverConfig()))
        advanceUntilIdle()

        val transport = createdTransports.single()
        transport.simulateError(java.io.IOException("reset"))
        transport.simulateClose()
        advanceUntilIdle()

        assertEquals(2, createdClients.size)
        assertTrue(manager.syncingStatus.value[SERVER_ID] is McpStatus.Ready)
    }

    @Test
    fun `configuration change cancels in flight connect and only new definition becomes ready`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        connectGates[SERVER_ID] = gate
        emit(listOf(serverConfig(url = "https://old.example/mcp")))
        runCurrent()
        assertEquals(1, createdClients.size)

        emit(listOf(serverConfig(url = "https://new.example/mcp")))
        runCurrent()
        assertEquals(2, createdClients.size)

        gate.complete(Unit)
        advanceUntilIdle()
        val ready = manager.syncingStatus.value[SERVER_ID] as McpStatus.Ready
        assertEquals(
            serverConfig(url = "https://new.example/mcp").mcpDefinitionDigest(),
            catalogs.value.getValue(SERVER_ID).definitionDigest,
        )
        assertTrue(ready.toolCount > 0)
        coVerify { createdClients.first().close() }
    }

    @Test
    fun `cancelling turn preparation does not cancel the app scope connection`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        connectGates[SERVER_ID] = gate
        emit(listOf(serverConfig()))
        val waiter = async {
            manager.prepareTurnCapabilities(Assistant(mcpServers = setOf(SERVER_ID)))
        }
        runCurrent()
        waiter.cancel()
        assertTrue(waiter.isCancelled)
        assertEquals(McpStatus.Connecting, manager.syncingStatus.value[SERVER_ID])

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(manager.syncingStatus.value[SERVER_ID] is McpStatus.Ready)
    }

    @Test
    fun `a received tool result remains authoritative when the session changes afterward`() = runTest(dispatcher) {
        emit(listOf(serverConfig(url = "https://old.example/mcp")))
        advanceUntilIdle()
        val tool = manager.captureTurnCapabilities(Assistant(mcpServers = setOf(SERVER_ID))).tools.single()
        val gate = CompletableDeferred<Unit>()
        callToolGate = gate
        var artifactPublished = false

        val call = async {
            manager.callTool(
                serverId = tool.serverId,
                toolName = tool.name,
                expectedDefinitionDigest = tool.definitionDigest,
                expectedNeedsApproval = tool.needsApproval,
                args = JsonObject(emptyMap()),
            ) { artifactPublished = true }
        }
        runCurrent()
        emit(listOf(serverConfig(url = "https://new.example/mcp")))
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()

        val text = call.await().single() as me.rerere.ai.ui.UIMessagePart.Text
        assertEquals("tool-result", text.text)
        assertFalse(artifactPublished)
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
        assertTrue(manager.syncingStatus.value[SERVER_ID] is McpStatus.Ready)
        assertTrue(manager.syncingStatus.value[secondId] is McpStatus.Ready)
        emit(listOf(serverConfig()))
        advanceUntilIdle()
        assertTrue(manager.syncingStatus.value[SERVER_ID] is McpStatus.Ready)
        assertNull(manager.syncingStatus.value[secondId])
    }

    @Test
    fun `different servers start connecting in parallel`() = runTest(dispatcher) {
        val secondId = Uuid.random()
        val second = McpServerConfig.StreamableHTTPServer(
            id = secondId,
            commonOptions = McpCommonOptions(name = "other"),
            url = "https://other.example/mcp",
        )
        connectGates[SERVER_ID] = CompletableDeferred()
        connectGates[secondId] = CompletableDeferred()

        emit(listOf(serverConfig(), second))
        runCurrent()

        assertEquals(setOf(SERVER_ID, secondId), connectStarted)
        assertEquals(McpStatus.Connecting, manager.syncingStatus.value[SERVER_ID])
        assertEquals(McpStatus.Connecting, manager.syncingStatus.value[secondId])

        connectGates.values.forEach { it.complete(Unit) }
        advanceUntilIdle()
        assertTrue(manager.syncingStatus.value[SERVER_ID] is McpStatus.Ready)
        assertTrue(manager.syncingStatus.value[secondId] is McpStatus.Ready)
    }

    @Test
    fun `connection attempt timeout starts after lifecycle admission`() = runTest(dispatcher) {
        val configs = (1..5).map { index ->
            McpServerConfig.StreamableHTTPServer(
                id = Uuid.random(),
                commonOptions = McpCommonOptions(name = "server_$index"),
                url = "https://server-$index.example/mcp",
            )
        }
        configs.forEach { connectGates[it.id] = CompletableDeferred() }

        emit(configs)
        runCurrent()

        assertEquals(McpRuntimeCoordinator.MAX_PARALLEL_LIFECYCLE_OPERATIONS, connectStarted.size)
        val queuedId = configs.map { it.id }.single { it !in connectStarted }
        advanceTimeBy(McpServerRuntimePolicy.CONNECTION_OPERATION_TIMEOUT_MS - 1_000L)
        connectGates.getValue(connectStarted.first()).complete(Unit)
        runCurrent()
        assertTrue(queuedId in connectStarted)

        advanceTimeBy(1_500L)
        runCurrent()
        assertEquals(McpStatus.Connecting, manager.syncingStatus.value[queuedId])

        connectGates.values.forEach { it.complete(Unit) }
        advanceUntilIdle()
        assertTrue(manager.syncingStatus.value[queuedId] is McpStatus.Ready)
    }

    @Test
    fun `manual catalog refresh is bounded across healthy servers`() = runTest(dispatcher) {
        val configs = (1..20).map { index ->
            McpServerConfig.StreamableHTTPServer(
                id = Uuid.random(),
                commonOptions = McpCommonOptions(name = "server_$index"),
                url = "https://server-$index.example/mcp",
            )
        }
        emit(configs)
        advanceUntilIdle()

        val refreshGate = CompletableDeferred<Unit>()
        var activeRequests = 0
        var maximumActiveRequests = 0
        listToolsResponder = { _, _ ->
            activeRequests += 1
            maximumActiveRequests = maxOf(maximumActiveRequests, activeRequests)
            try {
                refreshGate.await()
                ListToolsResult(tools = listOf(serverTool("refreshed")))
            } finally {
                activeRequests -= 1
            }
        }

        val refresh = async { manager.refreshAllRegisteredServers() }
        runCurrent()
        advanceTimeBy(McpServerRuntimePolicy.CATALOG_REFRESH_DEBOUNCE_MS + 1L)
        runCurrent()

        assertEquals(McpRuntimeCoordinator.MAX_PARALLEL_LIFECYCLE_OPERATIONS, maximumActiveRequests)
        refreshGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(0, refresh.await().continuingServerCount)
    }

    @Test
    fun `foreground and network recovery leave a healthy catalog untouched`() = runTest(dispatcher) {
        var listRequests = 0
        listToolsResponder = { _, _ ->
            listRequests += 1
            ListToolsResult(tools = listOf(serverTool("search")))
        }
        emit(listOf(serverConfig()))
        advanceUntilIdle()
        val catalogRevision = catalogs.value.getValue(SERVER_ID).revision

        foregroundAction?.invoke()
        runCurrent()
        networkOnline.value = false
        runCurrent()
        networkOnline.value = true
        advanceUntilIdle()

        assertEquals(1, createdClients.size)
        assertEquals(1, listRequests)
        assertEquals(catalogRevision, catalogs.value.getValue(SERVER_ID).revision)
    }

    @Test
    fun `network recovery reconnects a disconnected activated slot and discovers once`() = runTest(dispatcher) {
        var listRequests = 0
        listToolsResponder = { _, _ ->
            listRequests += 1
            ListToolsResult(tools = listOf(serverTool("search_$listRequests")))
        }
        emit(listOf(serverConfig()))
        advanceUntilIdle()
        networkOnline.value = false
        runCurrent()
        createdTransports.single().simulateClose()
        runCurrent()
        assertEquals(McpStatus.WaitingNetwork, manager.syncingStatus.value[SERVER_ID])

        networkOnline.value = true
        advanceUntilIdle()

        assertEquals(2, createdClients.size)
        assertEquals(2, listRequests)
        assertTrue(manager.syncingStatus.value[SERVER_ID] is McpStatus.Ready)
    }

    @Test
    fun `concurrent lifecycle triggers reuse an active connection operation`() = runTest(dispatcher) {
        val connectGate = CompletableDeferred<Unit>()
        connectGates[SERVER_ID] = connectGate
        var listRequests = 0
        listToolsResponder = { _, _ ->
            listRequests += 1
            ListToolsResult(tools = listOf(serverTool("search")))
        }
        emit(listOf(serverConfig()))
        runCurrent()
        assertEquals(1, createdClients.size)

        foregroundAction?.invoke()
        networkOnline.value = false
        runCurrent()
        networkOnline.value = true
        val refresh = async { manager.refreshAllRegisteredServers() }
        runCurrent()

        assertEquals(1, createdClients.size)
        connectGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, createdClients.size)
        assertEquals(1, listRequests)
        assertEquals(0, refresh.await().continuingServerCount)
    }

    @Test
    fun `tool discovery follows every pagination cursor before publishing ready`() = runTest(dispatcher) {
        listToolsResponder = { _, request ->
            if (request.params?.cursor == null) {
                ListToolsResult(tools = listOf(serverTool("first")), nextCursor = "page-2")
            } else {
                ListToolsResult(tools = listOf(serverTool("second")))
            }
        }

        emit(listOf(serverConfig()))
        advanceUntilIdle()

        val ready = manager.syncingStatus.value[SERVER_ID] as McpStatus.Ready
        assertEquals(2, ready.toolCount)
        assertEquals(listOf("first", "second"), catalogs.value.getValue(SERVER_ID).tools.map { it.name })
    }

    @Test
    fun `list changed during initial discovery is serialized behind the initial catalog`() = runTest(dispatcher) {
        val firstDiscoveryGate = CompletableDeferred<Unit>()
        var requests = 0
        listToolsResponder = { _, _ ->
            requests += 1
            if (requests == 1) {
                firstDiscoveryGate.await()
                ListToolsResult(tools = listOf(serverTool("initial")))
            } else {
                ListToolsResult(tools = listOf(serverTool("refreshed")))
            }
        }

        emit(listOf(serverConfig()))
        runCurrent()
        assertEquals(McpStatus.Discovering, manager.syncingStatus.value[SERVER_ID])
        toolListChangedHandlers.getValue(SERVER_ID).invoke(ToolListChangedNotification())
        runCurrent()
        assertEquals(1, requests)

        firstDiscoveryGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, requests)
        assertEquals(listOf("refreshed"), catalogs.value.getValue(SERVER_ID).tools.map { it.name })
        assertTrue(manager.syncingStatus.value[SERVER_ID] is McpStatus.Ready)
    }

    @Test
    fun `slow list changed refresh keeps the active catalog available`() = runTest(dispatcher) {
        emit(listOf(serverConfig()))
        advanceUntilIdle()
        val beforeRefresh = manager.captureTurnCapabilities(
            Assistant(mcpServers = setOf(SERVER_ID))
        ).tools.map { it.name }
        val refreshGate = CompletableDeferred<Unit>()
        listToolsResponder = { _, _ ->
            refreshGate.await()
            ListToolsResult(tools = listOf(serverTool("refreshed")))
        }

        toolListChangedHandlers.getValue(SERVER_ID).invoke(ToolListChangedNotification())
        runCurrent()

        assertTrue(manager.syncingStatus.value[SERVER_ID] is McpStatus.Ready)
        assertEquals(
            beforeRefresh,
            manager.captureTurnCapabilities(Assistant(mcpServers = setOf(SERVER_ID))).tools.map { it.name },
        )

        refreshGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(
            listOf("refreshed"),
            manager.captureTurnCapabilities(Assistant(mcpServers = setOf(SERVER_ID))).tools.map { it.name },
        )
    }

    @Test
    fun `manual refresh waits for discovery commit and updates only future snapshots`() = runTest(dispatcher) {
        emit(listOf(serverConfig()))
        advanceUntilIdle()
        val frozen = manager.captureTurnCapabilities(Assistant(mcpServers = setOf(SERVER_ID))).tools.single()
        val refreshGate = CompletableDeferred<Unit>()
        listToolsResponder = { _, _ ->
            refreshGate.await()
            ListToolsResult(tools = listOf(serverTool("refreshed")))
        }

        val refresh = async { manager.refreshAllRegisteredServers() }
        runCurrent()

        assertFalse(refresh.isCompleted)
        assertEquals(
            listOf("search"),
            manager.captureTurnCapabilities(Assistant(mcpServers = setOf(SERVER_ID))).tools.map { it.name },
        )
        refreshGate.complete(Unit)
        advanceUntilIdle()

        assertTrue(refresh.isCompleted)
        assertEquals(
            listOf("refreshed"),
            manager.captureTurnCapabilities(Assistant(mcpServers = setOf(SERVER_ID))).tools.map { it.name },
        )
        val oldRunResult = manager.callTool(
            serverId = frozen.serverId,
            toolName = frozen.name,
            expectedDefinitionDigest = frozen.definitionDigest,
            expectedNeedsApproval = frozen.needsApproval,
            args = JsonObject(emptyMap()),
        ) { }
        assertEquals("tool-result", (oldRunResult.single() as me.rerere.ai.ui.UIMessagePart.Text).text)
    }

    @Test
    fun `manual refresh receipt ends while AppScope discovery continues`() = runTest(dispatcher) {
        emit(listOf(serverConfig()))
        advanceUntilIdle()
        val refreshGate = CompletableDeferred<Unit>()
        listToolsResponder = { _, _ ->
            refreshGate.await()
            ListToolsResult(tools = listOf(serverTool("background_refresh")))
        }

        val refresh = async { manager.refreshAllRegisteredServers() }
        runCurrent()
        advanceTimeBy(McpRuntimeCoordinator.USER_OPERATION_RECEIPT_TIMEOUT_MS + 1L)
        runCurrent()

        val receipt = refresh.await()
        assertEquals(1, receipt.requestedServerCount)
        assertEquals(1, receipt.continuingServerCount)
        assertEquals(
            listOf("search"),
            manager.captureTurnCapabilities(Assistant(mcpServers = setOf(SERVER_ID))).tools.map { it.name },
        )

        refreshGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(
            listOf("background_refresh"),
            manager.captureTurnCapabilities(Assistant(mcpServers = setOf(SERVER_ID))).tools.map { it.name },
        )
    }

    @Test
    fun `single server restart receipt ends without cancelling the connection attempt`() = runTest(dispatcher) {
        emit(listOf(serverConfig()))
        advanceUntilIdle()
        val reconnectGate = CompletableDeferred<Unit>()
        connectGates[SERVER_ID] = reconnectGate

        val restart = async { manager.restartServer(SERVER_ID) }
        runCurrent()
        advanceTimeBy(McpRuntimeCoordinator.USER_OPERATION_RECEIPT_TIMEOUT_MS + 1L)
        runCurrent()

        assertEquals(1, restart.await().continuingServerCount)
        assertEquals(McpStatus.Connecting, manager.syncingStatus.value[SERVER_ID])
        reconnectGate.complete(Unit)
        advanceUntilIdle()
        assertTrue(manager.syncingStatus.value[SERVER_ID] is McpStatus.Ready)
    }

    @Test
    fun `connection operation deadline always leaves connecting`() = runTest(dispatcher) {
        connectGates[SERVER_ID] = CompletableDeferred()
        emit(listOf(serverConfig()))
        runCurrent()
        assertEquals(McpStatus.Connecting, manager.syncingStatus.value[SERVER_ID])

        advanceTimeBy(McpServerRuntimePolicy.CONNECTION_OPERATION_TIMEOUT_MS + 1L)
        runCurrent()

        assertTrue(manager.syncingStatus.value[SERVER_ID] is McpStatus.RetryScheduled)
        emit(emptyList())
        runCurrent()
    }

    @Test
    fun `persistent reconnect failure enters maintenance recovery before pausing`() = runTest(dispatcher) {
        emit(listOf(serverConfig()))
        advanceUntilIdle()
        connectFailure = java.io.IOException("offline")
        createdTransports.single().simulateClose()
        advanceUntilIdle()

        assertTrue(manager.syncingStatus.value[SERVER_ID] is McpStatus.Error)
        assertEquals(McpServerRuntimePolicy.MAX_TOTAL_RECONNECT_ATTEMPTS + 1, createdClients.size)
        assertEquals(
            listOf("search"),
            manager.captureTurnCapabilities(Assistant(mcpServers = setOf(SERVER_ID))).tools.map { it.name },
        )

        val frozen = manager.captureTurnCapabilities(Assistant(mcpServers = setOf(SERVER_ID))).tools.single()
        val failure = runCatching {
            manager.callTool(
                serverId = frozen.serverId,
                toolName = frozen.name,
                expectedDefinitionDigest = frozen.definitionDigest,
                expectedNeedsApproval = frozen.needsApproval,
                args = JsonObject(emptyMap()),
            ) { }
        }.exceptionOrNull() as ToolExecutionFailure
        val envelope = (failure.output.single() as me.rerere.ai.ui.UIMessagePart.Text).text
        val json = Json.parseToJsonElement(envelope).jsonObject
        assertEquals(setOf("status", "reason", "message"), json.keys)
        assertEquals("server_unavailable", json.getValue("reason").toString().trim('"'))
    }

    @Test
    fun `first empty tools list is connected but never ready`() = runTest(dispatcher) {
        listToolsResponder = { _, _ -> ListToolsResult(tools = emptyList()) }

        emit(listOf(serverConfig()))
        advanceUntilIdle()

        assertEquals(McpStatus.CatalogRejectedEmpty, manager.syncingStatus.value[SERVER_ID])
        assertTrue(catalogs.value.isEmpty())
        assertTrue(manager.captureTurnCapabilities(Assistant(mcpServers = setOf(SERVER_ID))).tools.isEmpty())
    }

    @Test
    fun `twenty tool catalog is fully exposed to a selected assistant`() = runTest(dispatcher) {
        listToolsResponder = { _, _ ->
            ListToolsResult(tools = (1..20).map { serverTool("tool_$it") })
        }

        emit(listOf(serverConfig()))
        advanceUntilIdle()

        val ready = manager.syncingStatus.value[SERVER_ID] as McpStatus.Ready
        assertEquals(20, ready.toolCount)
        val snapshot = manager.captureTurnCapabilities(Assistant(mcpServers = setOf(SERVER_ID)))
        assertEquals(20, snapshot.tools.size)
        assertTrue(snapshot.tools.all { it.catalogRevision == ready.catalogRevision })
    }

    @Test
    fun `durable catalog is available after process restart without eager connection`() = runTest(dispatcher) {
        val isolatedEffective = MutableStateFlowHolder()
        val isolatedSettingsStore = mockk<SettingsStore>()
        val definition = serverConfig()
        val definitions = listOf(definition) + (2..20).map { index ->
            McpServerConfig.StreamableHTTPServer(
                id = Uuid.random(),
                commonOptions = McpCommonOptions(name = "server_$index"),
                url = "https://server-$index.example/mcp",
            )
        }
        isolatedEffective.snapshot = EffectiveSettingsSnapshot(
            settings = Settings(mcpServers = definitions),
            access = SettingsAccessIndex(),
            revision = 1L,
            managedState = ManagedConfigurationState.ABSENT,
        )
        every { isolatedSettingsStore.effectiveSettings } returns isolatedEffective.flow
        val durable = McpCatalogSnapshot(
            serverId = SERVER_ID,
            revision = 7L,
            definitionDigest = definition.mcpDefinitionDigest(),
            catalogDigest = "durable",
            tools = (1..20).map { McpCatalogTool("tool_$it", null, JsonObject(emptyMap())) },
        )
        val isolatedCatalogStore = mockk<McpCatalogStore>()
        every { isolatedCatalogStore.catalogs } returns MutableStateFlow(mapOf(SERVER_ID to durable))
        coEvery { isolatedCatalogStore.remove(any()) } returns Unit
        val isolatedNetwork = mockk<NetworkMonitor>()
        every { isolatedNetwork.isOnline } returns MutableStateFlow(true)
        val restartClients = mutableListOf<Client>()
        val restarted = McpRuntimeCoordinator(
            settingsStore = isolatedSettingsStore,
            catalogStore = isolatedCatalogStore,
            appScope = AppScope(dispatcher),
            artifactStore = mockk<ArtifactStore>(relaxed = true),
            networkMonitor = isolatedNetwork,
            foregroundObserver = ForegroundObserver { action -> foregroundAction = action },
            ioDispatcher = dispatcher,
            transportOverride = { FakeTransport() },
            clientOverride = { config -> fakeClient(config).also(restartClients::add) },
            oauthCallbackKeepAlive = NoOpOAuthCallbackKeepAlive,
            retryJitter = { it },
        )
        runCurrent()

        assertTrue("startup must not queue every configured server", restartClients.isEmpty())
        assertEquals(
            20,
            restarted.captureTurnCapabilities(Assistant(mcpServers = setOf(SERVER_ID))).tools.size,
        )
    }

    @Test
    fun `remote tool error preserves server content and marks the invocation failed`() = runTest(dispatcher) {
        emit(listOf(serverConfig()))
        advanceUntilIdle()
        val tool = manager.captureTurnCapabilities(Assistant(mcpServers = setOf(SERVER_ID))).tools.single()
        callToolResponder = {
            CallToolResult(
                content = listOf(TextContent("remote detail")),
                structuredContent = buildJsonObject { put("code", JsonPrimitive("REMOTE_FAILURE")) },
                isError = true,
            )
        }

        val failure = runCatching {
            manager.callTool(
                serverId = tool.serverId,
                toolName = tool.name,
                expectedDefinitionDigest = tool.definitionDigest,
                expectedNeedsApproval = tool.needsApproval,
                args = JsonObject(emptyMap()),
            ) { }
        }.exceptionOrNull() as ToolExecutionFailure

        assertTrue((failure.output.first() as me.rerere.ai.ui.UIMessagePart.Text).text.contains("remote_error"))
        assertEquals("remote detail", (failure.output.last() as me.rerere.ai.ui.UIMessagePart.Text).text)
    }

    @Test
    fun `server without tools capability is rejected before call commitment`() = runTest(dispatcher) {
        emit(listOf(serverConfig()))
        advanceUntilIdle()
        val client = createdClients.single()
        val tool = manager.captureTurnCapabilities(Assistant(mcpServers = setOf(SERVER_ID))).tools.single()
        every { client.serverCapabilities } returns ServerCapabilities()

        val failure = runCatching {
            manager.callTool(
                serverId = tool.serverId,
                toolName = tool.name,
                expectedDefinitionDigest = tool.definitionDigest,
                expectedNeedsApproval = tool.needsApproval,
                args = JsonObject(emptyMap()),
            ) { }
        }.exceptionOrNull() as ToolExecutionFailure

        val envelope = (failure.output.single() as me.rerere.ai.ui.UIMessagePart.Text).text
        val json = Json.parseToJsonElement(envelope).jsonObject
        assertEquals(setOf("status", "reason"), json.keys)
        assertEquals("protocol_incompatible", json.getValue("reason").toString().trim('"'))
        coVerify(exactly = 0) { client.callTool(any<CallToolRequest>(), any<RequestOptions>()) }
    }

    @Test
    fun `explicit MCP error preserves only the bounded remote message`() = runTest(dispatcher) {
        emit(listOf(serverConfig()))
        advanceUntilIdle()
        val tool = manager.captureTurnCapabilities(Assistant(mcpServers = setOf(SERVER_ID))).tools.single()
        callToolResponder = { throw McpException(code = -32_001, message = "Remote validation failed") }

        val failure = runCatching {
            manager.callTool(
                serverId = tool.serverId,
                toolName = tool.name,
                expectedDefinitionDigest = tool.definitionDigest,
                expectedNeedsApproval = tool.needsApproval,
                args = JsonObject(emptyMap()),
            ) { }
        }.exceptionOrNull() as ToolExecutionFailure

        val envelope = (failure.output.single() as me.rerere.ai.ui.UIMessagePart.Text).text
        val json = Json.parseToJsonElement(envelope).jsonObject
        assertEquals(setOf("status", "reason", "message"), json.keys)
        assertEquals("remote_error", json.getValue("reason").toString().trim('"'))
        assertEquals("Remote validation failed", json.getValue("message").toString().trim('"'))
    }

    @Test
    fun `transport failure after call commitment reports an unknown outcome`() = runTest(dispatcher) {
        emit(listOf(serverConfig()))
        advanceUntilIdle()
        val tool = manager.captureTurnCapabilities(Assistant(mcpServers = setOf(SERVER_ID))).tools.single()
        callToolResponder = { throw java.io.IOException("connection reset") }

        val failure = runCatching {
            manager.callTool(
                serverId = tool.serverId,
                toolName = tool.name,
                expectedDefinitionDigest = tool.definitionDigest,
                expectedNeedsApproval = tool.needsApproval,
                args = JsonObject(emptyMap()),
            ) { }
        }.exceptionOrNull() as ToolExecutionFailure
        val envelope = (failure.output.single() as me.rerere.ai.ui.UIMessagePart.Text).text
        val json = Json.parseToJsonElement(envelope).jsonObject
        assertEquals(setOf("status", "reason", "message"), json.keys)
        assertEquals("outcome_unknown", json.getValue("reason").toString().trim('"'))
        assertFalse(envelope.contains("request_sent"))
        assertFalse(envelope.contains("retryable"))
    }

    @Test
    fun `active slot capability view is not torn by a later catalog flow emission`() = runTest(dispatcher) {
        emit(listOf(serverConfig()))
        advanceUntilIdle()
        catalogs.value = emptyMap()

        val snapshot = manager.captureTurnCapabilities(Assistant(mcpServers = setOf(SERVER_ID)))

        assertEquals(1, snapshot.tools.size)
        assertEquals(McpServerCapabilityState.READY, snapshot.serverOutcomes.single().state)
    }

    @Test
    fun `turn preparation waits for selected discovery before freezing capabilities`() = runTest(dispatcher) {
        connectGates[SERVER_ID] = CompletableDeferred()
        listToolsResponder = { _, _ ->
            ListToolsResult(tools = (1..20).map { serverTool("tool_$it") })
        }
        val assistant = Assistant(mcpServers = setOf(SERVER_ID))

        emit(listOf(serverConfig()))
        val prepared = async { manager.prepareTurnCapabilities(assistant) }
        runCurrent()
        assertFalse(prepared.isCompleted)

        connectGates.getValue(SERVER_ID).complete(Unit)
        advanceUntilIdle()

        assertEquals(20, prepared.await().tools.size)
    }

    @Test
    fun `turn preparation cannot settle against the previous definition ready state`() = runTest(dispatcher) {
        emit(listOf(serverConfig(url = "https://old.example/mcp")))
        advanceUntilIdle()
        connectGates[SERVER_ID] = CompletableDeferred()

        emit(listOf(serverConfig(url = "https://new.example/mcp")))
        val prepared = async {
            manager.prepareTurnCapabilities(Assistant(mcpServers = setOf(SERVER_ID)))
        }
        runCurrent()

        assertFalse(prepared.isCompleted)
        assertEquals(McpStatus.Connecting, manager.syncingStatus.value[SERVER_ID])
        connectGates.getValue(SERVER_ID).complete(Unit)
        advanceUntilIdle()

        assertEquals(
            serverConfig(url = "https://new.example/mcp").mcpDefinitionDigest(),
            prepared.await().tools.single().definitionDigest,
        )
    }

    @Test
    fun `policy change does not hide a result for a call already committed`() = runTest(dispatcher) {
        emit(listOf(serverConfig()))
        advanceUntilIdle()
        val tool = manager.captureTurnCapabilities(Assistant(mcpServers = setOf(SERVER_ID))).tools.single()
        val gate = CompletableDeferred<Unit>()
        callToolGate = gate

        val call = async {
            manager.callTool(
                serverId = tool.serverId,
                toolName = tool.name,
                expectedDefinitionDigest = tool.definitionDigest,
                expectedNeedsApproval = tool.needsApproval,
                args = JsonObject(emptyMap()),
            ) { }
        }
        runCurrent()
        emit(
            listOf(
                serverConfig(
                    policies = listOf(McpToolPolicy(name = tool.name, enable = false)),
                )
            )
        )
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()

        val text = call.await().single() as me.rerere.ai.ui.UIMessagePart.Text
        assertEquals("tool-result", text.text)
    }

    @Test
    fun `local revoke that wins the invocation commit gate prevents the remote call`() = runTest(dispatcher) {
        emit(listOf(serverConfig()))
        advanceUntilIdle()
        val tool = manager.captureTurnCapabilities(Assistant(mcpServers = setOf(SERVER_ID))).tools.single()
        val mutationEntered = CompletableDeferred<Unit>()
        val releaseMutation = CompletableDeferred<Unit>()
        var remoteStarted = false
        callToolResponder = {
            remoteStarted = true
            CallToolResult(content = listOf(TextContent("must-not-run")))
        }
        val mutation = async {
            manager.withConfigurationMutation {
                mutationEntered.complete(Unit)
                releaseMutation.await()
                emit(
                    listOf(
                        serverConfig(
                            policies = listOf(McpToolPolicy(name = tool.name, enable = false)),
                        )
                    )
                )
            }
        }
        runCurrent()
        mutationEntered.await()

        val call = async {
            runCatching {
                manager.callTool(
                    serverId = tool.serverId,
                    toolName = tool.name,
                    expectedDefinitionDigest = tool.definitionDigest,
                    expectedNeedsApproval = tool.needsApproval,
                    args = JsonObject(emptyMap()),
                ) { }
            }
        }
        runCurrent()

        assertFalse(call.isCompleted)
        assertFalse(remoteStarted)
        releaseMutation.complete(Unit)
        advanceUntilIdle()

        val failure = call.await().exceptionOrNull() as ToolExecutionFailure
        assertTrue((failure.output.single() as me.rerere.ai.ui.UIMessagePart.Text).text.contains("tool_unavailable"))
        assertFalse(remoteStarted)
        mutation.await()
    }

    @Test
    fun `approval tightening rejects a tool frozen without approval`() = runTest(dispatcher) {
        emit(listOf(serverConfig()))
        advanceUntilIdle()
        val tool = manager.captureTurnCapabilities(Assistant(mcpServers = setOf(SERVER_ID))).tools.single()

        emit(
            listOf(
                serverConfig(
                    policies = listOf(McpToolPolicy(name = tool.name, needsApproval = true)),
                )
            )
        )
        runCurrent()

        val failure = runCatching {
            manager.callTool(
                serverId = tool.serverId,
                toolName = tool.name,
                expectedDefinitionDigest = tool.definitionDigest,
                expectedNeedsApproval = tool.needsApproval,
                args = JsonObject(emptyMap()),
            ) { }
        }.exceptionOrNull() as ToolExecutionFailure
        assertTrue((failure.output.single() as me.rerere.ai.ui.UIMessagePart.Text).text.contains("tool_unavailable"))
    }

    @Test
    fun `oauth refresh during tool admission does not hold the slot gate`() = runTest(dispatcher) {
        val isolatedEffective = MutableStateFlowHolder()
        val isolatedSettingsStore = mockk<SettingsStore>()
        val isolatedCatalogs = MutableStateFlow<Map<Uuid, McpCatalogSnapshot>>(emptyMap())
        val isolatedCatalogStore = mockk<McpCatalogStore>()
        val oauthClient = mockk<McpOAuthClient>(relaxed = true)
        val isolatedClients = mutableListOf<Client>()
        isolatedEffective.snapshot = snapshotOf(emptyList())
        every { isolatedSettingsStore.effectiveSettings } returns isolatedEffective.flow
        coEvery { isolatedSettingsStore.updateLocal(any()) } coAnswers {
            val transform = firstArg<(Settings) -> Settings>()
            val next = transform(isolatedEffective.snapshot.settings)
            isolatedEffective.publish(next)
            next
        }
        every { isolatedCatalogStore.catalogs } returns isolatedCatalogs
        coEvery { isolatedCatalogStore.rollbackCommitted(any(), any(), any()) } returns Unit
        coEvery { isolatedCatalogStore.remove(any()) } returns Unit
        coEvery { isolatedCatalogStore.commitCandidate(any()) } coAnswers {
            val candidate = firstArg<McpCatalogCandidate>()
            val previous = isolatedCatalogs.value[candidate.serverId]
            val snapshot = McpCatalogSnapshot(
                serverId = candidate.serverId,
                revision = (previous?.revision ?: 0L) + 1L,
                definitionDigest = candidate.definitionDigest,
                catalogDigest = candidate.tools.joinToString { it.name },
                tools = candidate.tools,
            )
            isolatedCatalogs.value += candidate.serverId to snapshot
            McpCatalogCommitResult.Committed(snapshot, previous, snapshot.revision)
        }
        val networkMonitor = mockk<NetworkMonitor>()
        every { networkMonitor.isOnline } returns MutableStateFlow(true)
        val isolatedManager = McpRuntimeCoordinator(
            settingsStore = isolatedSettingsStore,
            catalogStore = isolatedCatalogStore,
            appScope = AppScope(dispatcher),
            artifactStore = mockk<ArtifactStore>(relaxed = true),
            networkMonitor = networkMonitor,
            foregroundObserver = ForegroundObserver { },
            ioDispatcher = dispatcher,
            transportOverride = { FakeTransport() },
            clientOverride = { config -> fakeClient(config).also(isolatedClients::add) },
            oauthCallbackKeepAlive = NoOpOAuthCallbackKeepAlive,
            oauthClientOverride = oauthClient,
        )
        val authorized = McpOAuthState(
            enabled = true,
            clientId = "client",
            accessToken = "old-token",
            refreshToken = "refresh-token",
            tokenEndpoint = "https://auth.example/token",
            expiresAt = Long.MAX_VALUE,
        )
        isolatedEffective.publish(Settings(mcpServers = listOf(serverConfig(url = "https://old.example/mcp", oauth = authorized))))
        advanceUntilIdle()
        val tool = isolatedManager.captureTurnCapabilities(Assistant(mcpServers = setOf(SERVER_ID))).tools.single()
        val refreshGate = CompletableDeferred<Unit>()
        coEvery {
            oauthClient.refreshToken(any(), any(), any(), any(), any(), any())
        } coAnswers {
            refreshGate.await()
            McpOAuthClient.TokenResponse(accessToken = "new-token")
        }

        isolatedEffective.publish(
            Settings(mcpServers = listOf(
                serverConfig(
                    url = "https://old.example/mcp",
                    oauth = authorized.copy(expiresAt = 1L),
                )
            ))
        )
        runCurrent()
        val call = async {
            runCatching {
                isolatedManager.callTool(
                    serverId = tool.serverId,
                    toolName = tool.name,
                    expectedDefinitionDigest = tool.definitionDigest,
                    expectedNeedsApproval = tool.needsApproval,
                    args = JsonObject(emptyMap()),
                ) { }
            }
        }
        runCurrent()
        assertFalse(call.isCompleted)

        isolatedEffective.publish(Settings(mcpServers = listOf(serverConfig(url = "https://new.example/mcp", oauth = authorized))))
        runCurrent()
        assertTrue("definition reconcile must acquire the slot while token refresh waits", isolatedClients.size >= 2)

        refreshGate.complete(Unit)
        advanceUntilIdle()
        val failure = call.await().exceptionOrNull() as ToolExecutionFailure
        assertTrue((failure.output.single() as me.rerere.ai.ui.UIMessagePart.Text).text.contains("tool_unavailable"))
    }

    @Test
    fun `runtime policy separates mobile fast retries from maintenance`() {
        val policy = McpServerRuntimePolicy(retryJitter = { it })
        assertEquals(2_000L, policy.reconnectDelay(1))
        assertEquals(6_000L, policy.reconnectDelay(2))
        assertEquals(15_000L, policy.reconnectDelay(3))
        assertEquals(30_000L, policy.reconnectDelay(4))
        assertEquals(McpServerRuntimePolicy.MAX_MAINTENANCE_RETRY_DELAY_MS, policy.reconnectDelay(50))
    }

    @Test
    fun `isConnectionError separates authorization from connection failures`() {
        assertTrue(McpProtocolFailureClassifier.isConnectionError(java.io.IOException("network reset")))
        assertTrue(
            McpProtocolFailureClassifier.isConnectionError(
                StreamableHttpError(code = 503, message = "Service Unavailable")
            )
        )
        assertTrue(McpProtocolFailureClassifier.isConnectionError(RuntimeException("Connection refused")))
        assertFalse(
            McpProtocolFailureClassifier.isConnectionError(
                StreamableHttpError(code = 401, message = "Unauthorized")
            )
        )
        assertFalse(McpProtocolFailureClassifier.isConnectionError(RuntimeException("Tool not found")))
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

/** Test-only status projection; production exposes only the atomic runtime capability map. */
private val McpRuntimeCoordinator.syncingStatus: TestStatusSnapshot
    get() = TestStatusSnapshot(runtimeCapabilities.value.mapValues { it.value.status })

private data class TestStatusSnapshot(val value: Map<Uuid, McpStatus>)
