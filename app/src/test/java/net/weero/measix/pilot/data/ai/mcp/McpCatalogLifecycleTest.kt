package net.weero.measix.pilot.data.ai.mcp

import net.weero.measix.pilot.data.configuration.ConfigurationScope


import me.rerere.common.configuration.ConfigurationReference

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
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 目录生命周期：durable catalog 增删禁用与删除、工具发现分页与 list_changed 串行化、手动刷新、启动恢复与调用结果投影。 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class McpCatalogLifecycleTest : McpRuntimeCoordinatorTestBase() {

    @Test
    fun `expiry while a committed catalog receipt waits compensates publication before runtime activation`() = runTest(dispatcher) {
        for (initiallyReady in listOf(false, true)) {
            catalogs.value = emptyMap()
            var authorized = true
            val config = McpConnectionDefinition.User(serverConfig())
            val access = net.weero.measix.pilot.data.enterprise.RealmAccess.Enterprise(
                ConfigurationScope.Enterprise(me.rerere.common.configuration.EnterpriseAuthority("local:test", "dep_test"), "user_test"), "session_test")
            val key = McpRuntimeKey(SERVER_ID, access, "int_${kotlin.uuid.Uuid.random()}")
            val states = McpRuntimeStateStore()
            val appScope = AppScope(dispatcher)
            val network = mockk<NetworkMonitor>()
            every { network.isOnline } returns MutableStateFlow(true)
            val runtime = McpServerRuntime(key, object : McpRuntimeDefinition {
                override fun requireAuthority() { check(authorized) { "original_session_expired" } }
                override suspend fun <T> withCurrent(use: McpDefinitionUse, operation: suspend (McpConnectionDefinition?) -> T): T {
                    requireAuthority()
                    return operation(config)
                }
            }, catalogStore, appScope, network, states,
                McpProtocolClientFactory(createHttpClient = { error("unexpected HTTP") },
                    createManagedHttpClient = { error("unexpected managed HTTP") }, createLocalHttpClient = { error("unexpected local HTTP") },
                    transportOverride = { FakeTransport().also(createdTransports::add) }, clientOverride = { fakeClient(it) }),
                oauthCoordinator, kotlinx.coroutines.sync.Semaphore(1), dispatcher, MutableStateFlow(true), McpServerRuntimePolicy { 0 },
                { _, _ -> }, {}, {})
            states.getOrCreate(key) { runtime }
            val releaseReceipt = CompletableDeferred<Unit>()
            try {
                // The initially-ready case starts with the same normal Store receipt, without a suspended publication.
                coEvery { catalogStore.commitCandidate(any()) } coAnswers {
                    val candidate = firstArg<McpCatalogCandidate>()
                    val previous = catalogs.value[candidate.key]
                    val snapshot = candidate.initialSnapshot().copy(revision = (previous?.revision ?: 0L) + 1L)
                    catalogs.value = catalogs.value + (candidate.key to snapshot)
                    McpCatalogCommitResult.Committed(snapshot, previous, snapshot.revision)
                }
                if (initiallyReady) {
                    runtime.reconcile(refreshTools = false)
                    advanceUntilIdle()
                    assertTrue(states.capabilities.value[key]?.status is McpStatus.Ready)
                }
                val previous = catalogs.value[config.catalogKey]
                val written = CompletableDeferred<McpCatalogCommitResult.Committed>()
                coEvery { catalogStore.commitCandidate(any()) } coAnswers {
                    val candidate = firstArg<McpCatalogCandidate>()
                    val snapshot = candidate.initialSnapshot().copy(revision = (previous?.revision ?: 0L) + 1L)
                    val receipt = McpCatalogCommitResult.Committed(snapshot, previous, 42L)
                    catalogs.value = catalogs.value + (candidate.key to snapshot)
                    written.complete(receipt)
                    releaseReceipt.await()
                    receipt
                }
                runtime.reconcile(refreshTools = initiallyReady)
                if (initiallyReady) advanceTimeBy(McpServerRuntimePolicy.CATALOG_REFRESH_DEBOUNCE_MS + 1L)
                runCurrent()
                assertTrue(written.isCompleted)
                authorized = false
                releaseReceipt.complete(Unit)
                advanceUntilIdle()
                val receipt = written.await()
                coVerify(exactly = 1) { catalogStore.rollbackCommitted(receipt.snapshot, previous, receipt.headToken) }
                assertEquals(previous, catalogs.value[config.catalogKey])
                assertFalse(states.capabilities.value[key]?.status is McpStatus.Ready)
                assertEquals(previous, states.capabilities.value[key]?.catalog)
            } finally {
                releaseReceipt.complete(Unit)
                runtime.closeAndAwait()
                appScope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
            }
        }
    }

    @Test
    fun `cancelled connection compensates a catalog already committed before its receipt returns`() = runTest(dispatcher) {
        assertCancelledCommit(initiallyReady = false)
    }

    @Test
    fun `cancelled refresh compensates a catalog already committed before its receipt returns`() = runTest(dispatcher) {
        assertCancelledCommit(initiallyReady = true)
    }

    @Test
    fun `cancellation after refresh activation does not restore the old runtime catalog`() = runTest(dispatcher) {
        assertAcceptedCommitCancellation(kotlinx.coroutines.CancellationException("cancel after activation"))
    }

    @Test
    fun `timeout after refresh activation does not restore the old runtime catalog`() = runTest(dispatcher) {
        val timeout = try {
            kotlinx.coroutines.withTimeout(1) { kotlinx.coroutines.awaitCancellation() }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) { error }
        assertAcceptedCommitCancellation(timeout)
    }

    private suspend fun kotlinx.coroutines.test.TestScope.assertAcceptedCommitCancellation(cause: kotlinx.coroutines.CancellationException) {
        emit(listOf(serverConfig()))
        advanceUntilIdle()
        val previous = catalogs.value.getValue(CATALOG_KEY)
        val fresh = McpCatalogCandidate(ConfigurationScope.Personal, SERVER_ID, previous.definitionDigest, listOf(McpCatalogTool("fresh", inputSchema = JsonObject(emptyMap()))))
            .initialSnapshot().copy(revision = previous.revision + 1)
        var operation: kotlinx.coroutines.Job? = null
        coEvery { catalogStore.awaitReady() } coAnswers {
            operation = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            Unit
        }
        // Hold the separate Store flow delivery so this observation proves Runtime activation itself.
        coEvery { catalogStore.commitCandidate(any()) } returns McpCatalogCommitResult.Committed(fresh, previous, 42L)
        var cancelled = false
        val observer = backgroundScope.async(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            manager.runtimeCapabilities.collect { views ->
                if (!cancelled && views[McpRuntimeKey(SERVER_ID)]?.catalog == fresh) {
                    cancelled = true
                    requireNotNull(operation).cancel(cause)
                }
            }
        }
        val refresh = async { manager.refreshAllRegisteredServers() }
        advanceUntilIdle()
        refresh.await()
        observer.cancel()
        assertTrue(cancelled)
        coVerify(exactly = 0) { catalogStore.rollbackCommitted(any(), any(), any()) }
        assertEquals(fresh, manager.runtimeCapabilities.value[McpRuntimeKey(SERVER_ID)]?.catalog)
    }

    @Test
    fun `borrowed user MCP keeps its confirmed catalog or fails explicitly after enterprise publication advances`() = runTest(dispatcher) {
        for (initiallyReady in listOf(false, true)) {
            catalogs.value = emptyMap()
            var mayPublish = initiallyReady
            val config = McpConnectionDefinition.User(serverConfig())
            val access = net.weero.measix.pilot.data.enterprise.RealmAccess.Enterprise(
                ConfigurationScope.Enterprise(me.rerere.common.configuration.EnterpriseAuthority("local:test", "dep_test"), "user_test"), "session_test")
            val key = McpRuntimeKey(SERVER_ID, access, "int_${kotlin.uuid.Uuid.random()}")
            val states = McpRuntimeStateStore()
            val appScope = AppScope(dispatcher)
            val network = mockk<NetworkMonitor>()
            every { network.isOnline } returns MutableStateFlow(true)
            val runtime = McpServerRuntime(key, object : McpRuntimeDefinition {
                override suspend fun <T> withCurrent(use: McpDefinitionUse, operation: suspend (McpConnectionDefinition?) -> T): T =
                    operation(config.takeIf { use == McpDefinitionUse.EXECUTION || mayPublish })
            }, catalogStore, appScope, network, states,
                McpProtocolClientFactory(createHttpClient = { error("unexpected HTTP") },
                    createManagedHttpClient = { error("unexpected managed HTTP") }, createLocalHttpClient = { error("unexpected local HTTP") },
                    transportOverride = { FakeTransport().also(createdTransports::add) }, clientOverride = { fakeClient(it) }),
                oauthCoordinator, kotlinx.coroutines.sync.Semaphore(1), dispatcher, MutableStateFlow(true), McpServerRuntimePolicy { 0 },
                { _, _ -> }, {}, {})
            states.getOrCreate(key) { runtime }
            try {
                runtime.reconcile(refreshTools = false)
                advanceUntilIdle()
                if (initiallyReady) {
                    val original = requireNotNull(states.capabilities.value[key]?.catalog)
                    mayPublish = false
                    runtime.reconcile(refreshTools = true)
                    advanceUntilIdle()
                    assertEquals(original, states.capabilities.value[key]?.catalog)
                    assertTrue(states.capabilities.value[key]?.status is McpStatus.Ready)
                    runtime.reconcile(refreshTools = false, forceReconnect = true)
                    advanceUntilIdle()
                    assertEquals(original, states.capabilities.value[key]?.catalog)
                    assertTrue(states.capabilities.value[key]?.status is McpStatus.Ready)
                } else {
                    assertEquals(null, states.capabilities.value[key]?.catalog)
                    assertTrue(states.capabilities.value[key]?.status is McpStatus.Error)
                }
            } finally { runtime.closeAndAwait(); appScope.coroutineContext[kotlinx.coroutines.Job]!!.cancel() }
        }
    }

    @Test
    fun `definition owner read failure before publication closes the original transport without a commit`() = runTest(dispatcher) {
        var rejectRead = false
        coEvery { settingsStore.withUserMcpDefinitions<Any?>(any()) } coAnswers {
            if (rejectRead) error("definition_store_unavailable")
            firstArg<suspend (List<McpServerConfig>) -> Any?>().invoke(effective.snapshot.mcpServers)
        }
        listToolsResponder = { _, _ ->
            rejectRead = true
            io.modelcontextprotocol.kotlin.sdk.types.ListToolsResult(listOf(io.modelcontextprotocol.kotlin.sdk.types.Tool(name = "ready", inputSchema = io.modelcontextprotocol.kotlin.sdk.types.ToolSchema())))
        }
        emit(listOf(serverConfig()))
        advanceUntilIdle()
        coVerify(exactly = 0) { catalogStore.commitCandidate(any()) }
        assertTrue(manager.runtimeCapabilities.value[McpRuntimeKey(SERVER_ID)]?.status is McpStatus.Error)
        assertEquals(null, manager.runtimeCapabilities.value[McpRuntimeKey(SERVER_ID)]?.catalog)
        assertTrue(createdTransports.isNotEmpty())
        assertTrue(createdTransports.all { it.closeCalls > 0 })
    }

    private suspend fun kotlinx.coroutines.test.TestScope.assertCancelledCommit(initiallyReady: Boolean) {
        if (initiallyReady) {
            emit(listOf(serverConfig()))
            advanceUntilIdle()
        }
        val previous = catalogs.value[CATALOG_KEY]
        val written = CompletableDeferred<McpCatalogCommitResult.Committed>()
        val releaseReceipt = CompletableDeferred<Unit>()
        coEvery { catalogStore.commitCandidate(any()) } coAnswers {
            val candidate = firstArg<McpCatalogCandidate>()
            val snapshot = candidate.initialSnapshot().copy(revision = (previous?.revision ?: 0L) + 1L)
            val receipt = McpCatalogCommitResult.Committed(snapshot, previous, 42L)
            catalogs.value = mapOf(CATALOG_KEY to snapshot)
            written.complete(receipt)
            releaseReceipt.await()
            receipt
        }
        if (initiallyReady) backgroundScope.async { manager.refreshAllRegisteredServers() }
        else emit(listOf(serverConfig()))
        if (initiallyReady) advanceTimeBy(McpServerRuntimePolicy.CATALOG_REFRESH_DEBOUNCE_MS + 1L)
        runCurrent()
        assertTrue(written.isCompleted)
        emit(listOf(serverConfig(enable = false)))
        runCurrent()
        releaseReceipt.complete(Unit)
        advanceUntilIdle()
        val receipt = written.await()
        coVerify(exactly = 1) { catalogStore.rollbackCommitted(receipt.snapshot, previous, receipt.headToken) }
        assertEquals(previous, catalogs.value[CATALOG_KEY])
    }

    @Test
    fun `deleting a cold disabled definition removes its durable catalog`() = runTest(dispatcher) {
        emit(listOf(serverConfig(enable = false)))
        advanceUntilIdle()
        assertTrue(createdClients.isEmpty())

        emit(emptyList())
        advanceUntilIdle()

        coVerify(exactly = 1) { catalogStore.remove(CATALOG_KEY) }
    }

    @Test
    fun `disabling then deleting a definition removes its durable catalog once`() = runTest(dispatcher) {
        emit(listOf(serverConfig()))
        advanceUntilIdle()
        emit(listOf(serverConfig(enable = false)))
        advanceUntilIdle()
        coVerify(exactly = 0) { catalogStore.remove(CATALOG_KEY) }

        emit(emptyList())
        advanceUntilIdle()

        coVerify(exactly = 1) { catalogStore.remove(CATALOG_KEY) }
    }

    @Test
    fun `manual catalog refresh is bounded across healthy servers`() = runTest(dispatcher) {
        val configs = (1..20).map { index ->
            McpServerConfig.StreamableHTTPServer(
                id = ConfigurationReference.random(),
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
        val catalogRevision = catalogs.value.getValue(CATALOG_KEY).revision

        foregroundAction?.invoke()
        runCurrent()
        networkOnline.value = false
        runCurrent()
        networkOnline.value = true
        advanceUntilIdle()

        assertEquals(1, createdClients.size)
        assertEquals(1, listRequests)
        assertEquals(catalogRevision, catalogs.value.getValue(CATALOG_KEY).revision)
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
        assertEquals(listOf("first", "second"), catalogs.value.getValue(CATALOG_KEY).tools.map { it.name })
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
        assertEquals(listOf("refreshed"), catalogs.value.getValue(CATALOG_KEY).tools.map { it.name })
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
        val oldRunResult = manager.callTool(net.weero.measix.pilot.data.enterprise.RealmAccess.Personal,
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
                id = ConfigurationReference.random(),
                commonOptions = McpCommonOptions(name = "server_$index"),
                url = "https://server-$index.example/mcp",
            )
        }
        isolatedEffective.snapshot = Settings(mcpServers = definitions)
        stubMcpUserDefinitions(isolatedSettingsStore, isolatedEffective.flow)
        val durable = McpCatalogSnapshot(ConfigurationScope.Personal,
            serverId = SERVER_ID,
            revision = 7L,
            definitionDigest = definition.mcpDefinitionDigest(),
            catalogDigest = "durable",
            tools = (1..20).map { McpCatalogTool("tool_$it", null, JsonObject(emptyMap())) },
        )
        val isolatedCatalogStore = mockk<McpCatalogStore>()
        coEvery { isolatedCatalogStore.awaitReady() } returns Unit
        every { isolatedCatalogStore.catalogs } returns MutableStateFlow(mapOf(durable.key to durable))
        coEvery { isolatedCatalogStore.remove(any()) } returns Unit
        val isolatedNetwork = mockk<NetworkMonitor>()
        every { isolatedNetwork.isOnline } returns MutableStateFlow(true)
        val restartClients = mutableListOf<Client>()
        val restarted = McpRuntimeCoordinator(
            sessions = io.mockk.mockk(),
            localMcp = io.mockk.mockk(),
            synchronization = io.mockk.mockk(),
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
            manager.callTool(net.weero.measix.pilot.data.enterprise.RealmAccess.Personal,
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
            manager.callTool(net.weero.measix.pilot.data.enterprise.RealmAccess.Personal,
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
            manager.callTool(net.weero.measix.pilot.data.enterprise.RealmAccess.Personal,
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
            manager.callTool(net.weero.measix.pilot.data.enterprise.RealmAccess.Personal,
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
