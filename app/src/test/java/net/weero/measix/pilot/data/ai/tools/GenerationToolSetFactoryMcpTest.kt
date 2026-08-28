package net.weero.measix.pilot.data.ai.tools

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.ProviderManager
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
import org.junit.Test
import kotlin.uuid.Uuid

class GenerationToolSetFactoryMcpTest {
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

        assertEquals(listOf("mcp__server__valid_tool"), tools.map { it.name })
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

        assertEquals(listOf("mcp__safe__ok"), tools.map { it.name })
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
