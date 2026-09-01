package net.weero.measix.pilot.data.ai.tools

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
import kotlin.uuid.Uuid

class GenerationToolSetFactoryMcpTest {
    @Test
    fun `MCP validates its JSON envelope without interpreting or rewriting remote schema`() = runTest {
        val localTools = mockk<LocalTools>()
        every { localTools.getTools(any(), any(), any()) } returns emptyList()
        val factory = GenerationToolSetFactory(
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
            assistant = Assistant(), settings = Settings(), capabilityModel = null,
            mcpCapabilities = TurnMcpCapabilitySnapshot(tools = listOf(
                availableTool(Uuid.random(), "remote", schema).copy(needsApproval = true),
            )),
        ).single { it.name == "mcp__server__remote" }
        assertEquals(schema, tool.parameters())
        assertThrows(ToolArgumentsException::class.java) { tool.parseArguments("[]", Json) }
        assertThrows(ToolArgumentsException::class.java) { tool.parseArguments("{", Json) }
        val arguments = tool.parseArguments("{}", Json)
        assertEquals(ToolInteractionRequirement.Approval, tool.interactionRequirement(arguments))
        assertEquals("{}", arguments.toString())
    }

    @Test
    fun `invalid remote tool name does not hide valid tools from the same server`() = runTest {
        val localTools = mockk<LocalTools>()
        every { localTools.getTools(any(), any(), any()) } returns emptyList()
        val factory = GenerationToolSetFactory(
            localTools = localTools,
            conversationQueryService = mockk<ConversationQueryService>(),
            skillManager = mockk<SkillManager>(),
            workspaceApplicationService = mockk<WorkspaceApplicationService>(),
            workspaceQueryService = mockk<WorkspaceQueryService>(),
            mcpManager = mockk<McpRuntimeCoordinator>(),
            providerManager = mockk<ProviderManager>(),
            artifactStore = mockk<ArtifactStore>(),
        )
        val serverId = Uuid.random()
        val schema = buildJsonObject { put("type", "object") }
        val tools = factory.buildTools(
            assistant = Assistant(),
            settings = Settings(),
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
        val factory = GenerationToolSetFactory(
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
            assistant = Assistant(),
            settings = Settings(),
            capabilityModel = null,
            mcpCapabilities = TurnMcpCapabilitySnapshot(
                tools = listOf(
                    availableTool(Uuid.random(), "c", schema, serverName = "a__b"),
                    availableTool(Uuid.random(), "b__c", schema, serverName = "a"),
                    availableTool(Uuid.random(), "ok", schema, serverName = "safe"),
                )
            ),
        )

        assertEquals(listOf("read_tool_output", "grep_tool_output", "mcp__safe__ok"), tools.map { it.name })
    }

    @Test
    fun `additional tools cannot shadow reserved lookup names`() = runTest {
        val localTools = mockk<LocalTools>()
        every { localTools.getTools(any(), any(), any()) } returns emptyList()
        val factory = GenerationToolSetFactory(
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
                assistant = Assistant(),
                settings = Settings(),
                capabilityModel = null,
                additionalToolsBeforeMcp = listOf(conflict),
                mcpCapabilities = TurnMcpCapabilitySnapshot(tools = emptyList()),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    private fun availableTool(
        serverId: Uuid,
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
