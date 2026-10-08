package net.weero.measix.pilot.data.ai.tools

import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.enterprise.reference


import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.core.ToolArgumentsException
import me.rerere.ai.core.ToolInteractionRequirement
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolOutputPolicy
import net.weero.measix.pilot.data.ai.mcp.McpAvailableTool
import net.weero.measix.pilot.data.ai.mcp.McpRuntimeCoordinator
import net.weero.measix.pilot.data.ai.mcp.managedMcpNamespace
import net.weero.measix.pilot.data.ai.mcp.TurnMcpCapabilitySnapshot
import net.weero.measix.pilot.data.ai.tools.local.LocalTools
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.SkillManager
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.service.ConversationQueryService
import net.weero.measix.pilot.service.workspace.WorkspaceApplicationService
import net.weero.measix.pilot.service.workspace.WorkspaceQueryService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnToolSetFactoryTest {
    @Test
    fun `search execution uses frozen realm service and credentials for master and child without fallback`() = kotlinx.coroutines.runBlocking {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        val requests = java.util.concurrent.LinkedBlockingQueue<Pair<java.net.URI, String?>>()
        server.createContext("/") { exchange ->
            requests.add(exchange.requestURI to exchange.requestHeaders.getFirst("Authorization"))
            val response = "{\"results\":[]}".toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val origin = "http://127.0.0.1:${server.address.port}"
            val personal = me.rerere.search.SearchServiceOptions.SearXNGOptions(
                url = "$origin/personal", username = "personal", password = "personal-secret")
            val enterpriseChoice = me.rerere.search.SearchServiceOptions.SearXNGOptions(
                url = "$origin/enterprise", username = "enterprise", password = "enterprise-secret")
            val settings = Settings(searchServices = listOf(personal, enterpriseChoice), selectedSearchServiceId = personal.id)
            val packet = net.weero.measix.pilot.data.enterprise.exampleEnterprisePackage()
            val base = net.weero.measix.pilot.data.datastore.UserSettingsDocument.empty().withPersonalSettings(settings)
            val document = base.copy(preferences = base.preferences.withSelections(packet.identity.scope,
                net.weero.measix.pilot.data.datastore.ResourceSelections(selectedSearchServiceId = enterpriseChoice.id)))
            val resolved = net.weero.measix.pilot.data.configuration.ConfigurationResolver.resolve(
                document, packet.identity.scope, net.weero.measix.pilot.data.configuration.appliedConfiguration(packet))
            val local = mockk<LocalTools>()
            every { local.getTools(any(), any(), any()) } returns emptyList()
            val factory = TurnToolSetFactory(local, mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk())
            val access = net.weero.measix.pilot.data.enterprise.RealmAccess.Enterprise(packet.identity.scope, "session")
            for (kind in listOf(net.weero.measix.pilot.service.runtime.TurnKind.USER, net.weero.measix.pilot.service.runtime.TurnKind.SUB_ASSISTANT)) {
                val tool = factory.buildTools(access, Assistant(enableWebSearch = true), settings = settings,
                    configuration = resolved, capabilityModel = null, turnKind = kind,
                    mcpCapabilities = TurnMcpCapabilitySnapshot.EMPTY).single { it.name == "search_web" }
                tool.execute(buildJsonObject { put("query", "realm query") })
                val request = requireNotNull(requests.poll(5, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals("/enterprise/search", request.first.path)
                assertTrue(request.first.rawQuery.contains("q=realm+query"))
                assertEquals(okhttp3.Credentials.basic("enterprise", "enterprise-secret"), request.second)
            }
            val personalResolved = net.weero.measix.pilot.test.testResolvedConfiguration(settings)
            createSearchTools(settings, personalResolved).single { it.name == "search_web" }
                .execute(buildJsonObject { put("query", "personal query") })
            val personalRequest = requireNotNull(requests.poll(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("/personal/search", personalRequest.first.path)
            assertEquals(okhttp3.Credentials.basic("personal", "personal-secret"), personalRequest.second)
            for (reference in listOf(null, ConfigurationReference.random())) {
                val invalid = resolved.copy(selections = resolved.selections.copy(selectedSearchServiceId = reference))
                val failure = runCatching { factory.buildTools(access, Assistant(enableWebSearch = true), settings = settings,
                    configuration = invalid, capabilityModel = null, mcpCapabilities = TurnMcpCapabilitySnapshot.EMPTY) }.exceptionOrNull()
                assertEquals("search_selection_unavailable", failure?.message)
            }
            assertTrue(requests.isEmpty())
            assertEquals(personal.id, settings.selectedSearchServiceId)
        } finally { server.stop(0) }
    }

    @Test fun `managed AUTO and ALL use existing execution while explicit approval resumes the same call and reaches MCP`() = runTest {
        val packet = net.weero.measix.pilot.data.enterprise.exampleEnterprisePackage()
        val access = net.weero.measix.pilot.data.enterprise.RealmAccess.Enterprise(packet.identity.scope, "session")
        val resolved = net.weero.measix.pilot.data.configuration.ConfigurationResolver.resolve(
            net.weero.measix.pilot.data.datastore.UserSettingsDocument.empty(), packet.identity.scope,
            net.weero.measix.pilot.data.configuration.appliedConfiguration(packet))
        val local = mockk<LocalTools>()
        every { local.getTools(any(), any(), any()) } returns emptyList()
        val manager = mockk<McpRuntimeCoordinator>()
        val approvals = mutableListOf<Pair<String?, Boolean>>()
        val destinations = mutableListOf<Pair<ConfigurationReference, String>>()
        io.mockk.coEvery { manager.callTool(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            approvals += arg<String?>(7) to arg<Boolean>(8)
            destinations += arg<ConfigurationReference>(1) to arg<String>(3)
            listOf(me.rerere.ai.ui.UIMessagePart.Text("success"))
        }
        val factory = TurnToolSetFactory(local, mockk(), mockk(), mockk(), mockk(), manager, mockk(), mockk())
        for (hash in listOf(null, "sha256:" + "a".repeat(64))) {
            for (needsApproval in listOf(false, true)) {
                val reference = packet.identity.reference(packet.configuration.mcpServers.first().id)
                val capability = availableTool(reference, "read",
                    buildJsonObject { put("type", "object") }).copy(needsApproval = needsApproval, contractHash = hash,
                        namespace = managedMcpNamespace(reference))
                val definition = factory.buildTools(access, Assistant(), settings = Settings(), configuration = resolved,
                    capabilityModel = null, mcpCapabilities = TurnMcpCapabilitySnapshot(listOf(capability)))
                    .single { it.name == "mcp__${capability.namespace}__read" }
                val runtime = ToolCallRuntime(Json)
                val source = me.rerere.ai.ui.UIMessagePart.Tool(localCallId = kotlin.uuid.Uuid.random(),
                    stepId = kotlin.uuid.Uuid.random(), providerCallId = "call", toolName = definition.name, input = "{}")
                val messageId = kotlin.uuid.Uuid.random()
                val index = freezeToolSet(listOf(definition)).bindingsByName
                fun prepare(tool: me.rerere.ai.ui.UIMessagePart.Tool, availability: TurnInteractionCapability) =
                    runtime.prepareBatch(messageId, listOf(LocatedToolCall(0, tool)), index, availability)
                val first = prepare(source, TurnInteractionCapability.FULL)
                val prepared = if (needsApproval) {
                    assertEquals(1, first.pending.size)
                    val pending = first.replacements.getValue(source.localCallId)
                    val resumed = prepare(pending.copy(interactionState = me.rerere.ai.ui.ToolInteractionState.Approved), TurnInteractionCapability.FULL)
                    (resumed.resolvedCalls.single() as ResolvedToolCall.Executable).call.also {
                        assertEquals(first.pending.single().locator, it.locator)
                    }
                } else (first.resolvedCalls.single() as ResolvedToolCall.Executable).call
                val outcome = runtime.execute(prepared, ToolExecutionHooks({ me.rerere.ai.core.ToolAttachmentResolution() }, { _, _ -> }, {}, {}))
                assertEquals(me.rerere.ai.ui.ToolResultStatus.COMPLETED, outcome.resultStatus)
                assertEquals(hash to needsApproval, approvals.last())
                assertEquals(reference to "read", destinations.last())
                val child = prepare(source, TurnInteractionCapability.USER_INPUT_ONLY)
                if (needsApproval) {
                    assertTrue(child.pending.isEmpty())
                    assertEquals(1, child.immediateResults.size)
                } else assertTrue(child.resolvedCalls.single() is ResolvedToolCall.Executable)
            }
        }
    }

    @Test
    fun `MCP validates its JSON envelope without interpreting or rewriting remote schema`() = runTest {
        val localTools = mockk<LocalTools>()
        every { localTools.getTools(any(), any(), any()) } returns emptyList()
        val factory = TurnToolSetFactory(
            localTools = localTools,
            conversationQueryService = mockk<ConversationQueryService>(),
            skillManager = mockk<SkillManager>(),
            workspaceApplicationService = mockk<WorkspaceApplicationService>(),
            workspaceQueryService = mockk<WorkspaceQueryService>(),
            mcpManager = mockk<McpRuntimeCoordinator>(),
            providerManager = mockk<ProviderManager>(),
            artifactStore = mockk<ArtifactStore>(),
        )
        val schema = Json.parseToJsonElement("""{"type":"object","${'$'}ref":"#/${'$'}defs/input","${'$'}defs":{"input":{"required":["remote_field"]}}}""")
            as kotlinx.serialization.json.JsonObject
        val tool = factory.buildTools(
            realmAccess = net.weero.measix.pilot.data.enterprise.RealmAccess.Personal,
            assistant = Assistant(), settings = Settings(), configuration = net.weero.measix.pilot.test.testResolvedConfiguration(Settings()), capabilityModel = null,
            mcpCapabilities = TurnMcpCapabilitySnapshot(tools = listOf(
                availableTool(ConfigurationReference.random(), "remote", schema).copy(needsApproval = true),
            )),
        ).single { it.name == "mcp__server__remote" }
        assertEquals(schema, tool.parameters())
        assertThrows(ToolArgumentsException::class.java) { tool.parseArguments("[]", Json) }
        assertThrows(ToolArgumentsException::class.java) { tool.parseArguments("{", Json) }
        val arguments = tool.parseArguments("{}", Json)
        assertEquals(ToolInteractionRequirement.Approval, tool.interactionRequirement(arguments))
        assertEquals("{}", arguments.toString())
    }

    @Test fun `compact managed namespace preserves remote names at the provider length boundary`() = runTest {
        val local = mockk<LocalTools>()
        every { local.getTools(any(), any(), any()) } returns emptyList()
        val factory = TurnToolSetFactory(local, mockk(), mockk(), mockk(), mockk(), mockk(), mockk(), mockk())
        val packet = net.weero.measix.pilot.data.enterprise.exampleEnterprisePackage()
        val reference = packet.identity.reference(packet.configuration.mcpServers.first().id)
        val namespace = managedMcpNamespace(reference)
        val remoteName = "remote_" + "x".repeat(38)
        val schema = buildJsonObject { put("type", "object") }
        val configuration = net.weero.measix.pilot.data.configuration.ConfigurationResolver.resolve(
            net.weero.measix.pilot.data.datastore.UserSettingsDocument.empty(), packet.identity.scope,
            net.weero.measix.pilot.data.configuration.appliedConfiguration(packet))
        val tools = factory.buildTools(
            realmAccess = net.weero.measix.pilot.data.enterprise.RealmAccess.Enterprise(packet.identity.scope, "session"),
            assistant = configuration.assistants.values.first(), settings = Settings(),
            configuration = configuration, capabilityModel = null,
            mcpCapabilities = TurnMcpCapabilitySnapshot(listOf(
                availableTool(reference, remoteName, schema).copy(namespace = namespace),
                availableTool(reference, remoteName + "x", schema).copy(namespace = namespace),
            )),
        )
        val bound = tools.single { it.name.startsWith("mcp__") }
        assertEquals("mcp__${namespace}__${remoteName}", bound.name)
        assertEquals(64, bound.name.length)
        assertEquals(schema, bound.parameters())
    }

    @Test
    fun `invalid remote tool name does not hide valid tools from the same server`() = runTest {
        val localTools = mockk<LocalTools>()
        every { localTools.getTools(any(), any(), any()) } returns emptyList()
        val factory = TurnToolSetFactory(
            localTools = localTools,
            conversationQueryService = mockk<ConversationQueryService>(),
            skillManager = mockk<SkillManager>(),
            workspaceApplicationService = mockk<WorkspaceApplicationService>(),
            workspaceQueryService = mockk<WorkspaceQueryService>(),
            mcpManager = mockk<McpRuntimeCoordinator>(),
            providerManager = mockk<ProviderManager>(),
            artifactStore = mockk<ArtifactStore>(),
        )
        val serverId = ConfigurationReference.random()
        val schema = buildJsonObject { put("type", "object") }
        val tools = factory.buildTools(
            realmAccess = net.weero.measix.pilot.data.enterprise.RealmAccess.Personal,
            assistant = Assistant(),
            settings = Settings(), configuration = net.weero.measix.pilot.test.testResolvedConfiguration(Settings()),
            capabilityModel = null,
            mcpCapabilities = TurnMcpCapabilitySnapshot(
                tools = listOf(
                    availableTool(serverId, "valid_tool", schema),
                    availableTool(serverId, "invalid.tool", schema),
                )
            ),
        )

        assertEquals(listOf("read_tool_output", "grep_tool_output", "mcp__server__valid_tool"), tools.map { it.name })
        assertEquals(ToolOutputPolicy.ARCHIVABLE_TEXT, tools.single { it.name == "mcp__server__valid_tool" }.outputPolicy)
    }

    @Test
    fun `colliding final provider names are rejected while unrelated tools remain`() = runTest {
        val localTools = mockk<LocalTools>()
        every { localTools.getTools(any(), any(), any()) } returns emptyList()
        val factory = TurnToolSetFactory(
            localTools = localTools,
            conversationQueryService = mockk<ConversationQueryService>(),
            skillManager = mockk<SkillManager>(),
            workspaceApplicationService = mockk<WorkspaceApplicationService>(),
            workspaceQueryService = mockk<WorkspaceQueryService>(),
            mcpManager = mockk<McpRuntimeCoordinator>(),
            providerManager = mockk<ProviderManager>(),
            artifactStore = mockk<ArtifactStore>(),
        )
        val schema = buildJsonObject { put("type", "object") }
        val tools = factory.buildTools(
            realmAccess = net.weero.measix.pilot.data.enterprise.RealmAccess.Personal,
            assistant = Assistant(),
            settings = Settings(), configuration = net.weero.measix.pilot.test.testResolvedConfiguration(Settings()),
            capabilityModel = null,
            mcpCapabilities = TurnMcpCapabilitySnapshot(
                tools = listOf(
                    availableTool(ConfigurationReference.random(), "c", schema, serverName = "a__b"),
                    availableTool(ConfigurationReference.random(), "b__c", schema, serverName = "a"),
                    availableTool(ConfigurationReference.random(), "ok", schema, serverName = "safe"),
                )
            ),
        )

        assertEquals(listOf("read_tool_output", "grep_tool_output", "mcp__safe__ok"), tools.map { it.name })
    }

    @Test
    fun `additional tools cannot shadow reserved lookup names`() = runTest {
        val localTools = mockk<LocalTools>()
        every { localTools.getTools(any(), any(), any()) } returns emptyList()
        val factory = TurnToolSetFactory(
            localTools = localTools,
            conversationQueryService = mockk<ConversationQueryService>(),
            skillManager = mockk<SkillManager>(),
            workspaceApplicationService = mockk<WorkspaceApplicationService>(),
            workspaceQueryService = mockk<WorkspaceQueryService>(),
            mcpManager = mockk<McpRuntimeCoordinator>(),
            providerManager = mockk<ProviderManager>(),
            artifactStore = mockk<ArtifactStore>(),
        )
        val conflict = Tool("read_tool_output", "shadow", execute = { emptyList() })

        val failure = runCatching {
            factory.buildTools(
            realmAccess = net.weero.measix.pilot.data.enterprise.RealmAccess.Personal,
                assistant = Assistant(),
                settings = Settings(), configuration = net.weero.measix.pilot.test.testResolvedConfiguration(Settings()),
                capabilityModel = null,
                additionalToolsBeforeMcp = listOf(conflict),
                mcpCapabilities = TurnMcpCapabilitySnapshot(tools = emptyList()),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    private fun availableTool(
        serverId: ConfigurationReference,
        name: String,
        schema: kotlinx.serialization.json.JsonObject,
        serverName: String = "server",
    ) = McpAvailableTool(
        serverId = serverId,
        serverName = serverName,
        catalogRevision = 1L,
        definitionDigest = "definition",
        catalogDigest = "catalog",
        name = name,
        description = null,
        inputSchema = schema,
        needsApproval = false,
    )
}
