package net.weero.measix.pilot.data.ai.mcp

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
import kotlin.uuid.Uuid

/** 回合能力快照：prepare 与 capture 冻结、提交门与审批收紧、槽位门与调用取消边界。 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class McpTurnCapabilitySnapshotTest : McpRuntimeCoordinatorTestBase() {

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
}
