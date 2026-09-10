package net.weero.measix.pilot.data.ai.mcp

import net.weero.measix.pilot.data.configuration.ConfigurationScope


import me.rerere.common.configuration.ConfigurationReference

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsResult
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
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

/** 回合能力快照：prepare 与 capture 冻结、提交门与审批收紧、槽位门与调用取消边界。 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class McpTurnCapabilitySnapshotTest : McpRuntimeCoordinatorTestBase() {

    @Test
    fun `passive enterprise inspection obeys resource policy gateway choice and confirmed generation without connecting`() = runTest(dispatcher) {
        val example = net.weero.measix.pilot.data.enterprise.EnterprisePackageCodec.decode(
            requireNotNull(javaClass.getResourceAsStream("/enterprise.local.example.json")))
        val scope = example.identity.scope
        val directId = ConfigurationReference.Enterprise(scope.authority, example.configuration.mcpServers.first().id)
        val gateway = example.configuration.gateways.single()
        val gatewayId = ConfigurationReference.Enterprise(scope.authority, gateway.id)
        val personal = serverConfig()
        var currentBindings = example.runtimeBindings.associateBy { it.resourceId }
        val access = net.weero.measix.pilot.data.enterprise.RealmAccess.Enterprise(scope, "inspection")
        var currentVersion = net.weero.measix.pilot.data.enterprise.EnterpriseAppliedVersion("inspection", example.configuration.generation, "configuration", "bindings")
        coEvery { sessions.readBindings<Any?>(access, any()) } coAnswers {
            secondArg<(net.weero.measix.pilot.data.enterprise.EnterpriseAppliedVersion, List<net.weero.measix.pilot.data.enterprise.EnterpriseRuntimeBinding>) -> Any?>()(currentVersion, currentBindings.values.toList())
        }
        val assistant = Assistant(mcpServers = setOf(SERVER_ID))
        val settings = Settings(assistants = listOf(assistant), mcpServers = listOf(personal))
        val document = net.weero.measix.pilot.data.datastore.UserSettingsDocument.empty().copy(
            configuration = net.weero.measix.pilot.data.datastore.UserConfiguration(assistants = listOf(assistant), mcpServers = listOf(personal)))
        val manifest = net.weero.measix.pilot.data.enterprise.EnterpriseManifest.signedOut().copy(
            phase = net.weero.measix.pilot.data.enterprise.EnterpriseSessionPhase.READY,
            session = net.weero.measix.pilot.data.enterprise.EnterpriseSession("inspection", example.identity, Long.MAX_VALUE),
            applied = net.weero.measix.pilot.data.enterprise.EnterpriseAppliedVersion("inspection", example.configuration.generation, "configuration", "bindings"),
            selectedScope = scope)
        fun resolve(allowUser: Boolean, gatewayEnabled: Boolean = true, generation: Long = example.configuration.generation): net.weero.measix.pilot.data.datastore.ExecutionConfigurationSnapshot {
            currentVersion = requireNotNull(manifest.applied).copy(generation = generation)
            val config = example.configuration.copy(generation = generation, policy = example.configuration.policy.copy(allowLocalMcp = allowUser))
            val resolved = net.weero.measix.pilot.data.configuration.ConfigurationResolver.resolve(document.copy(preferences = document.preferences
                .withAssistantUsage(scope, net.weero.measix.pilot.data.configuration.AssistantUsagePreferences(assistant.id,
                    mcpServers = net.weero.measix.pilot.data.configuration.UsageValue(setOf(SERVER_ID, directId))))
                .withGateway(scope, net.weero.measix.pilot.data.configuration.GatewayPreference(gatewayId, gatewayEnabled))), scope,
                net.weero.measix.pilot.data.enterprise.EnterpriseState.Available(manifest.copy(applied = manifest.applied!!.copy(generation = generation)), config))
            return net.weero.measix.pilot.data.datastore.ExecutionConfigurationSnapshot(settings,
                resolved, "test")
        }
        fun catalog(id: ConfigurationReference, tools: List<McpCatalogTool>, managed: McpManagedCatalog?) = McpCatalogSnapshot(
            if (id is ConfigurationReference.User) ConfigurationScope.Personal else scope, id, 1,
            if (id is ConfigurationReference.User) personal.mcpDefinitionDigest() else managedMcpDefinitionDigest(id as ConfigurationReference.Enterprise,
                if (id == gatewayId) gateway.name else example.configuration.mcpServers.first().name,
                currentBindings.getValue(id.id), example.configuration.generation), "catalog", tools, managed)
        catalogs.value = listOf(
            catalog(SERVER_ID, listOf(McpCatalogTool("user_tool", inputSchema = JsonObject(emptyMap()))), null),
            catalog(directId, listOf(McpCatalogTool("direct_tool", inputSchema = JsonObject(emptyMap()))), McpManagedCatalog(example.configuration.generation)),
            catalog(gatewayId, net.weero.measix.pilot.data.enterprise.LocalEnterpriseMcpSurface.gatewayTools, McpManagedCatalog(example.configuration.generation, gateway.surface)),
        ).associateBy { it.key }
        suspend fun inspect(snapshot: net.weero.measix.pilot.data.datastore.ExecutionConfigurationSnapshot) =
            manager.inspectCapabilities(access, snapshot, snapshot.configuration.assistants.getValue(assistant.id))
        val allowed = inspect(resolve(true))
        assertEquals(setOf("user_tool", "direct_tool", "discover_tools", "invoke_tool"), allowed.tools.map { it.name }.toSet())
        assertEquals(setOf("direct_tool", "discover_tools", "invoke_tool"), inspect(resolve(false)).tools.map { it.name }.toSet())
        assertEquals(listOf("direct_tool"), inspect(resolve(false, gatewayEnabled = false)).tools.map { it.name })
        assertEquals(listOf("user_tool"), inspect(resolve(true, generation = example.configuration.generation + 1)).tools.map { it.name })
        currentBindings = currentBindings + (directId.id to currentBindings.getValue(directId.id).copy(credential = "rotated"))
        assertEquals(setOf("user_tool", "discover_tools", "invoke_tool"), inspect(resolve(true)).tools.map { it.name }.toSet())
        assertTrue(allowed.tools.all { it.interactionId == null })
        assertTrue(allowed.tools.all { Regex("[A-Za-z0-9_-]{1,64}").matches("mcp__${it.namespace}__${it.name}") })
        assertTrue(createdTransports.isEmpty())
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
            manager.callTool(net.weero.measix.pilot.data.enterprise.RealmAccess.Personal,
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
            manager.callTool(net.weero.measix.pilot.data.enterprise.RealmAccess.Personal,
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
        val definitionGate = kotlinx.coroutines.sync.Mutex()
        io.mockk.coEvery { settingsStore.withUserMcpDefinitions<Any?>(any()) } coAnswers {
            definitionGate.lock()
            try { firstArg<suspend (List<McpServerConfig>) -> Any?>().invoke(effective.snapshot.mcpServers) }
            finally { definitionGate.unlock() }
        }
        val mutation = async {
            definitionGate.lock()
            try {
                mutationEntered.complete(Unit)
                releaseMutation.await()
                emit(
                    listOf(
                        serverConfig(
                            policies = listOf(McpToolPolicy(name = tool.name, enable = false)),
                        )
                    )
                )
            } finally { definitionGate.unlock() }
        }
        runCurrent()
        mutationEntered.await()

        val call = async {
            runCatching {
                manager.callTool(net.weero.measix.pilot.data.enterprise.RealmAccess.Personal,
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
            manager.callTool(net.weero.measix.pilot.data.enterprise.RealmAccess.Personal,
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
        val isolatedCatalogs = MutableStateFlow<Map<McpCatalogKey, McpCatalogSnapshot>>(emptyMap())
        val isolatedCatalogStore = mockk<McpCatalogStore>()
        coEvery { isolatedCatalogStore.awaitReady() } returns Unit
        val oauthClient = mockk<McpOAuthClient>(relaxed = true)
        val isolatedClients = mutableListOf<Client>()
        isolatedEffective.snapshot = snapshotOf(emptyList())
        stubMcpUserDefinitions(isolatedSettingsStore, isolatedEffective.flow)
        coEvery { isolatedSettingsStore.updateLocal(any()) } coAnswers {
            val transform = firstArg<(Settings) -> Settings>()
            val next = transform(isolatedEffective.snapshot)
            isolatedEffective.publish(next)
            next
        }
        every { isolatedCatalogStore.catalogs } returns isolatedCatalogs
        coEvery { isolatedCatalogStore.rollbackCommitted(any(), any(), any()) } returns Unit
        coEvery { isolatedCatalogStore.remove(any()) } returns Unit
        coEvery { isolatedCatalogStore.commitCandidate(any()) } coAnswers {
            val candidate = firstArg<McpCatalogCandidate>()
            val previous = isolatedCatalogs.value[candidate.key]
            val snapshot = McpCatalogSnapshot(ConfigurationScope.Personal,
                serverId = candidate.serverId,
                revision = (previous?.revision ?: 0L) + 1L,
                definitionDigest = candidate.definitionDigest,
                catalogDigest = candidate.tools.joinToString { it.name },
                tools = candidate.tools,
            )
            isolatedCatalogs.value += candidate.key to snapshot
            McpCatalogCommitResult.Committed(snapshot, previous, snapshot.revision)
        }
        val networkMonitor = mockk<NetworkMonitor>()
        every { networkMonitor.isOnline } returns MutableStateFlow(true)
        val isolatedManager = McpRuntimeCoordinator(
            sessions = io.mockk.mockk(),
            localMcp = io.mockk.mockk(),
            synchronization = io.mockk.mockk(),
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
        val tool = isolatedManager.prepareTurnCapabilities(Assistant(mcpServers = setOf(SERVER_ID))).tools.single()
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
                isolatedManager.callTool(net.weero.measix.pilot.data.enterprise.RealmAccess.Personal,
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
}
