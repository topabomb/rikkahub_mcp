package net.weero.measix.pilot.data.ai.mcp

import io.mockk.*
import java.io.IOException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import me.rerere.common.configuration.EnterpriseAuthority
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.enterprise.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

internal class McpRuntimeUsageTest : McpRuntimeCoordinatorTestBase() {
    @Test fun `only managed transport completion refreshes original enterprise usage on success and failure`() = runTest(dispatcher) {
        val deployment = "dep_${Uuid.random()}"
        val resource = "mcp_${Uuid.random()}"
        val access = RealmAccess.Enterprise(ConfigurationScope.Enterprise(EnterpriseAuthority(deployment), "user_usage"), "ses_${Uuid.random()}")
        val interaction = "int_${Uuid.random()}"
        val personal = McpConnectionDefinition.User(serverConfig())
        val connection = PlatformConnection("https://example.test", PlatformDiscovery(
            PlatformDiscoveryProduct.MEASIX_AGENT_PLATFORM, "1", deployment, "test", "/api/client/v1", "/runtime/v1", listOf(4)))
        val managed = McpConnectionDefinition.ManagedPlatform(access,
            me.rerere.common.configuration.ConfigurationReference.Enterprise(access.scope.authority, resource), "Managed",
            EnterpriseExecution.Platform(connection, "rel_${Uuid.random()}", "sha256:" + "a".repeat(64), mapOf(resource to "/mcp/v1")),
            PlatformMcpDefinitionAuthOwnership.NONE, EnterpriseAppliedVersion(Uuid.random().toString(), 1, "config", "execution"), interaction) { "fixture-token" }
        // Bind real server lifecycles to the coordinator; only the transport is controlled by the fixture.
        val states = McpRuntimeCoordinator::class.java.getDeclaredField("runtimeState").apply { isAccessible = true }
            .get(manager) as McpRuntimeStateStore
        val scope = AppScope(dispatcher)
        try {
            for (definition in listOf(personal, managed)) {
                clearMocks(platform, answers = false)
                val key = McpRuntimeKey(definition.id, access, interaction)
                val client = fakeClient(serverConfig())
                val factory = mockk<McpProtocolClientFactory>()
                coEvery { factory.createTransport(any()) } returns FakeTransport()
                every { factory.createClient(any()) } returns client
                val runtime = McpServerRuntime(key, object : McpRuntimeDefinition {
                    override suspend fun <T> withCurrent(use: McpDefinitionUse, operation: suspend (McpConnectionDefinition?) -> T): T = operation(definition)
                }, catalogStore, scope, mockk { every { isOnline } returns MutableStateFlow(true) }, states,
                    factory, oauthCoordinator, Semaphore(1), dispatcher, MutableStateFlow(true), McpServerRuntimePolicy { 0 },
                    { _, _ -> }, {}, {})
                states.getOrCreate(key) { runtime }
                runtime.bootstrap()
                runtime.reconcile(refreshTools = false)
                runtime.awaitCurrentOperations()
                runCurrent()
                assertTrue(states.capabilities.value.getValue(key).toString(), states.capabilities.value.getValue(key).sessionCallable)
                val tool = requireNotNull(states.capabilities.value.getValue(key).catalog).tools.single()
                for (failed in listOf(false, true)) {
                    callToolResponder = { if (failed) throw IOException("original MCP connection failed")
                        else io.modelcontextprotocol.kotlin.sdk.types.CallToolResult(content = listOf(io.modelcontextprotocol.kotlin.sdk.types.TextContent("result"))) }
                    val result = runCatching { manager.callTool(access, definition.id, interaction, tool.name, definition.mcpDefinitionDigest(),
                        false, JsonObject(emptyMap()), onArtifactCreated = {}) }
                    assertEquals(failed, result.isFailure)
                }
                verify(exactly = if (definition is McpConnectionDefinition.ManagedPlatform) 2 else 0) { platform.runtimeCompleted(access) }
                runtime.closeAndAwait()
            }
        } finally { scope.cancel() }
    }
}
