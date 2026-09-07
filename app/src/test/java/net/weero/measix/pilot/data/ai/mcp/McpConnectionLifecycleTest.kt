package net.weero.measix.pilot.data.ai.mcp

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpError
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import net.weero.measix.pilot.AppScope
import me.rerere.ai.core.ToolExecutionFailure
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/** 连接生命周期：建连与重连、传输关闭、定义变更重建客户端、授权状态写入、超时与维护退避、并行准入与按服务器状态。 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class McpConnectionLifecycleTest : McpRuntimeCoordinatorTestBase() {

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
}
