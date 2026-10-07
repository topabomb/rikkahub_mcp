package net.weero.measix.pilot.data.ai.mcp

import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpError
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsResult
import me.rerere.ai.core.ToolExecutionFailure
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.model.Assistant
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal class McpRecoveryPolicyTest : McpRuntimeCoordinatorTestBase() {
    @Test fun `network restoration while backgrounded cannot replace pending recovery`() = runTest(dispatcher) {
        emit(listOf(serverConfig()))
        advanceUntilIdle()
        foreground.value = false
        networkOnline.value = false
        createdTransports.single().simulateClose()
        runCurrent()
        networkOnline.value = true
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, createdClients.size)
        foreground.value = true
        foregroundAction?.invoke()
        advanceUntilIdle()
        assertEquals(2, createdClients.size)
        assertTrue(manager.runtimeCapabilities.value.getValue(McpRuntimeKey(SERVER_ID)).sessionCallable)
    }

    @Test fun `call after exhausted recovery reconnects and revalidates directory changes while disconnected`() = runTest(dispatcher) {
        val config = serverConfig()
        emit(listOf(config))
        advanceUntilIdle()
        val frozen = manager.captureTurnCapabilities(Assistant(mcpServers = setOf(SERVER_ID))).tools.single()
        val originalCatalog = manager.runtimeCapabilities.value.getValue(McpRuntimeKey(SERVER_ID)).catalog!!
        connectFailure = java.io.IOException("connection remains unavailable")
        createdTransports.single().simulateClose()
        advanceUntilIdle()
        assertTrue(manager.syncingStatus.value[SERVER_ID] is McpStatus.Error)
        assertEquals(McpServerRuntimePolicy.MAX_TOTAL_RECONNECT_ATTEMPTS + 1, createdClients.size)
        assertEquals(originalCatalog, manager.runtimeCapabilities.value.getValue(McpRuntimeKey(SERVER_ID)).catalog)

        // No foreground/network/config event follows: only the next frozen tool call wakes recovery.
        connectFailure = null
        var discoveries = 0
        listToolsResponder = { _, _ ->
            discoveries++
            ListToolsResult(tools = listOf(serverTool("replacement_tool")))
        }
        val clientsBeforeCall = createdClients.size
        val failure = try {
            manager.callTool(RealmAccess.Personal,
                serverId = frozen.serverId,
                toolName = frozen.name,
                expectedDefinitionDigest = frozen.definitionDigest,
                expectedNeedsApproval = frozen.needsApproval,
                args = JsonObject(emptyMap()),
            ) { }
            null
        } catch (error: ToolExecutionFailure) { error }
        assertNotNull("The triggering call remains unavailable while recovery starts", failure)
        advanceUntilIdle()

        val recovered = manager.runtimeCapabilities.value.getValue(McpRuntimeKey(SERVER_ID))
        assertEquals(clientsBeforeCall + 1, createdClients.size)
        assertEquals(1, discoveries)
        assertTrue(recovered.sessionCallable)
        val recoveredCatalog = requireNotNull(recovered.catalog)
        assertEquals(listOf("replacement_tool"), recoveredCatalog.tools.map { it.name })
        assertTrue(recoveredCatalog.revision > originalCatalog.revision)
        assertEquals("search", frozen.name)
    }

    @Test fun `non retryable transport error keeps diagnostics and requires explicit recovery`() = runTest(dispatcher) {
        emit(listOf(serverConfig()))
        advanceUntilIdle()
        createdTransports.single().simulateError(StreamableHttpError(400, "invalid notification protocol"))
        advanceUntilIdle()
        foregroundAction?.invoke()
        networkOnline.value = false
        runCurrent()
        networkOnline.value = true
        advanceUntilIdle()
        assertEquals(1, createdClients.size)
        val capability = manager.runtimeCapabilities.value.getValue(McpRuntimeKey(SERVER_ID))
        assertTrue(capability.status is McpStatus.Error)
        assertFalse(capability.sessionCallable)
        assertTrue((capability.status as McpStatus.Error).detail!!.contains("invalid notification protocol"))
        manager.restartServer(SERVER_ID)
        assertEquals(2, createdClients.size)
    }

    @Test fun `refresh during a cached connection handshake is not lost`() = runTest(dispatcher) {
        emit(listOf(serverConfig()))
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        connectGates[SERVER_ID] = gate
        // Credential rotation reconnects the existing definition without discarding its catalog.
        val config = serverConfig(oauth = McpOAuthState(enabled = true, accessToken = "rotated"))
        emit(listOf(config))
        runCurrent()
        listToolsResponder = { _, _ -> ListToolsResult(tools = listOf(serverTool("new_tool"))) }
        val refresh = async { manager.refreshAllRegisteredServers() }
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(0, refresh.await().continuingServerCount)
        assertEquals(listOf("new_tool"), manager.runtimeCapabilities.value.getValue(McpRuntimeKey(SERVER_ID)).catalog!!.tools.map { it.name })
    }

    @Test fun `failed refresh and notification degradation are independent of command readiness`() = runTest(dispatcher) {
        emit(listOf(serverConfig()))
        advanceUntilIdle()
        createdTransports.single().simulateError(McpNotificationStreamExhausted())
        runCurrent()
        listToolsResponder = { _, _ -> throw IllegalArgumentException("malformed tool directory") }
        manager.refreshAllRegisteredServers()
        val failed = manager.runtimeCapabilities.value.getValue(McpRuntimeKey(SERVER_ID))
        assertTrue(failed.sessionCallable)
        assertTrue(failed.status is McpStatus.Ready)
        assertTrue(failed.catalogRefresh is McpCatalogRefresh.Failed)
        assertTrue(failed.notifications is McpNotificationHealth.Unavailable)
        listToolsResponder = { _, _ -> ListToolsResult(tools = listOf(serverTool("updated"))) }
        manager.refreshAllRegisteredServers()
        val refreshed = manager.runtimeCapabilities.value.getValue(McpRuntimeKey(SERVER_ID))
        assertEquals(McpCatalogRefresh.Idle, refreshed.catalogRefresh)
        assertTrue(refreshed.notifications is McpNotificationHealth.Unavailable)
        assertEquals(listOf("updated"), refreshed.catalog!!.tools.map { it.name })
    }
}
