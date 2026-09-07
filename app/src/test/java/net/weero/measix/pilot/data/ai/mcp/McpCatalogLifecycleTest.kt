package net.weero.measix.pilot.data.ai.mcp

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsResult
import io.modelcontextprotocol.kotlin.sdk.types.McpException
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolListChangedNotification
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/** 目录生命周期：durable catalog 增删禁用与删除、工具发现分页与 list_changed 串行化、手动刷新、启动恢复与调用结果投影。 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class McpCatalogLifecycleTest : McpRuntimeCoordinatorTestBase() {

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
}
