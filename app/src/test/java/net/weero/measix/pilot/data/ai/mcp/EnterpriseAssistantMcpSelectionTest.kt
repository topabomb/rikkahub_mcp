package net.weero.measix.pilot.data.ai.mcp

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import me.rerere.ai.core.ToolExecutionFailure
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.configuration.*
import net.weero.measix.pilot.data.datastore.*
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.service.CapturedModelConfiguration
import net.weero.measix.pilot.service.runtime.ConversationRuntime
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

/** Real resolver, preference commands, Turn/runtime admission and SDK HTTP; owners use controlled IO. */
internal class EnterpriseAssistantMcpSelectionTest : McpRuntimeCoordinatorTestBase() {
    @Test fun `optional managed service executes and live selection binding and server limits remain authoritative`() = runBlocking {
        withTimeout(30_000) {
            val original = exampleEnterprisePackage()
            val definition = original.configuration.assistants.first().copy(mcpBindings = emptyList())
            var packet = original.copy(configuration = original.configuration.copy(assistants = listOf(definition),
                policy = original.configuration.policy.copy(allowLocalMcp = false, allowLocalAssistants = false)))
            val server = packet.configuration.mcpServers.first()
            val id = packet.identity.reference(definition.id)
            val ref = packet.identity.reference(server.id)
            var document = UserSettingsDocument.empty()
            fun resolve() = ConfigurationResolver.resolve(document, packet.identity.scope, appliedConfiguration(packet))
            assertTrue(resolve().assistants.getValue(id).mcpServers.isEmpty())
            document = document.changeAssistantPreference(packet.identity.scope, appliedConfiguration(packet), id, AssistantPreferenceChange.Mcp(ref, true))
            val configuration = resolve()
            val assistant = configuration.assistants.getValue(id)
            val access = RealmAccess.Enterprise(packet.identity.scope, "selection-session")
            val version = EnterpriseAppliedVersion("selection-revision", packet.configuration.generation, "configuration", "execution")
            val state = appliedConfiguration(packet).copy(manifest = appliedConfiguration(packet).manifest.copy(applied = version))
            every { sessions.requirePublishedRealmAccess(access) } returns Unit
            coEvery { sessions.withAppliedConfiguration<Any?>(access, any()) } coAnswers {
                secondArg<suspend (EnterpriseState.Available) -> Any?>().invoke(state)
            }
            coEvery { settingsStore.withExecutionConfiguration<Any?>(access.scope, state, any()) } coAnswers {
                thirdArg<suspend (ExecutionConfigurationSnapshot) -> Any?>().invoke(ExecutionConfigurationSnapshot(document.personalSettings(), resolve(), "user"))
            }
            val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            val calls = AtomicInteger()
            http.createContext("/") { exchange -> exchange.use {
                if (it.requestMethod == "GET" || it.requestMethod == "DELETE") it.sendResponseHeaders(405, -1) else {
                    val request = Json.parseToJsonElement(it.requestBody.bufferedReader().readText()).jsonObject
                    val result = when (request.getValue("method").jsonPrimitive.content) {
                        "initialize" -> """{"protocolVersion":"2025-11-25","serverInfo":{"name":"selection","version":"1"},"capabilities":{"tools":{}}}"""
                        "notifications/initialized" -> null
                        "tools/list" -> """{"tools":[{"name":"search","inputSchema":{"type":"object"}}]}"""
                        "tools/call" -> { calls.incrementAndGet(); """{"content":[{"type":"text","text":"selected-result"}]}""" }
                        else -> error("unexpected method")
                    }
                    if (result == null) it.sendResponseHeaders(202, -1) else {
                        val body = """{"jsonrpc":"2.0","id":${request["id"]},"result":$result}""".toByteArray()
                        it.responseHeaders.set("Content-Type", "application/json")
                        it.sendResponseHeaders(200, body.size.toLong()); it.responseBody.write(body)
                    }
                }
            } }
            val candidate = platformCandidate(packet).let {
                val execution = it.execution as EnterpriseExecution.Platform
                it.copy(execution = execution.copy(connection = execution.connection.copy(origin = "http://127.0.0.1:${http.address.port}")))
            }
            val appScope = AppScope(Dispatchers.IO)
            manager = McpRuntimeCoordinator(settingsStore, sessions, catalogStore, appScope, mockk(relaxed = true),
                mockk { every { isOnline } returns MutableStateFlow(true) }, platform,
                foregroundObserver = ForegroundObserver {}, ioDispatcher = Dispatchers.IO,
                oauthCallbackKeepAlive = NoOpOAuthCallbackKeepAlive, foregroundState = MutableStateFlow(true))
            coEvery { platform.accessToken(access.sessionId, any()) } returns PlatformAccessToken("fixture-token", Long.MAX_VALUE)
            val binding = EnterpriseExecutionLease("lease", access.sessionId, access.scope, version, candidate) {}
            coEvery { sessions.captureExecution(access, version) } returns binding
            val owner = mockk<ConversationRuntime>(relaxed = true) { every { durable.header.scope } returns access.scope }
            val lease = slot<McpExecutionLease>()
            every { owner.bindMcpExecution(any(), any(), any(), capture(lease)) } returns Unit
            val captured = CapturedModelConfiguration(document.personalSettings(), configuration, assistant,
                mockk { every { enterpriseVersion } returns version }, 0, Uuid.random())
            val worker = Job()
            http.start()
            try {
                val capabilities = manager.prepareTurnCapabilities(access, captured, owner, Uuid.random(), worker) {}
                assertEquals("outcomes=${capabilities.serverOutcomes}; runtime=${manager.runtimeCapabilities.value}", 1, capabilities.tools.size)
                val tool = capabilities.tools.single()
                assertEquals(ref, tool.serverId)
                suspend fun invoke(hash: String? = tool.contractHash, approval: Boolean = tool.needsApproval, approved: Boolean = false) =
                    manager.callTool(access, ref, tool.interactionId, tool.name, tool.definitionDigest, approval, JsonObject(emptyMap()),
                        expectedContractHash = hash, approvedByUser = approved, onArtifactCreated = { error("unexpected artifact") })
                assertTrue(invoke().isNotEmpty())
                assertEquals(1, calls.get())
                fun restrictAssistant(names: List<String>) {
                    packet = packet.copy(configuration = packet.configuration.copy(assistants = listOf(definition.copy(mcpBindings =
                        listOf(PlatformAssistantMcpBinding(server.id, PlatformAssistantMcpBindingToolSelection.ALLOWLIST, names))))))
                }
                restrictAssistant(listOf("other"))
                try { invoke(); fail("Mandatory binding must cap a previously selected extra") }
                catch (failure: ToolExecutionFailure) { assertEquals("MCP tool failure: tool_unavailable", failure.message) }
                assertEquals(1, calls.get())
                packet = packet.copy(configuration = packet.configuration.copy(assistants = listOf(definition)))
                assertTrue("Removing the binding preserves the real user selection", invoke().isNotEmpty())
                assertEquals(2, calls.get())
                val catalog = catalogs.value.values.single()
                val hash = catalog.tools.single().contractHash()
                packet = packet.copy(configuration = packet.configuration.copy(mcpServers = listOf(server.copy(
                    toolAccessMode = PlatformMcpDefinitionToolAccessMode.ALLOWLIST,
                    allowedTools = listOf(PlatformMcpToolGrant(tool.name, hash, PlatformMcpToolGrantApprovalPolicy.REQUIRE_CONFIRMATION))))))
                try { invoke(); fail("Server hash and confirmation ceiling must still apply") }
                catch (failure: ToolExecutionFailure) { assertEquals("MCP tool failure: tool_unavailable", failure.message) }
                assertTrue(invoke(hash, true, true).isNotEmpty())
                assertEquals(3, calls.get())
                document = document.changeAssistantPreference(packet.identity.scope, appliedConfiguration(packet), id, AssistantPreferenceChange.Mcp(ref, false))
                try { invoke(hash, true, true); fail("Deselected service must reject an existing Turn runtime") }
                catch (failure: ToolExecutionFailure) { assertEquals("MCP tool failure: tool_unavailable", failure.message) }
                assertEquals(3, calls.get())
            } finally {
                if (lease.isCaptured) lease.captured.release()
                worker.cancel()
                appScope.cancel()
                http.stop(0)
            }
        }
    }
}
