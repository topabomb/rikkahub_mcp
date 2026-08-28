package net.weero.measix.pilot.ui.pages.chat

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import net.weero.measix.pilot.data.ai.mcp.McpCommonOptions
import net.weero.measix.pilot.data.ai.mcp.McpServerConfig
import net.weero.measix.pilot.data.ai.mcp.McpStatus
import net.weero.measix.pilot.data.ai.tools.local.LocalToolOption
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.service.McpServerPresentation
import net.weero.measix.pilot.service.McpToolPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationReadinessTest {
    @Test
    fun `empty configuration reports only model as blocking`() {
        val assistant = Assistant()
        val readiness = Settings(
            providers = emptyList(),
            assistants = listOf(assistant),
            mcpServers = emptyList(),
        ).buildConversationReadiness(
            assistant = assistant,
            workspaceNamesById = emptyMap(),
            memoryCount = 0,
        )

        assertEquals(ModelReadiness.NOT_CONFIGURED, readiness.modelState)
        assertEquals(McpReadiness.NOT_CONFIGURED, readiness.mcpState)
        assertEquals(WorkspaceReadiness.NOT_CONFIGURED, readiness.workspaceState)
        assertEquals(MemoryReadiness.READY, readiness.memoryState)
        assertFalse(readiness.canSend)
        assertTrue(readiness.requiresProviderConfiguration)
    }

    @Test
    fun `configured models with an unresolved selection require model selection`() {
        val model = Model(modelId = "chat-model", displayName = "Chat Model")
        val assistant = Assistant(chatModelId = Uuid.random())
        val readiness = Settings(
            providers = listOf(ProviderSetting.OpenAI(models = listOf(model))),
            assistants = listOf(assistant),
        ).buildConversationReadiness(
            assistant = assistant,
            workspaceNamesById = emptyMap(),
            memoryCount = 0,
        )

        assertEquals(ModelReadiness.NOT_SELECTED, readiness.modelState)
        assertFalse(readiness.canSend)
        assertFalse(readiness.requiresProviderConfiguration)
    }

    @Test
    fun `models from disabled providers cannot satisfy conversation readiness`() {
        val model = Model(modelId = "disabled-chat-model", displayName = "Disabled Chat Model")
        val assistant = Assistant(chatModelId = model.id)
        val readiness = Settings(
            providers = listOf(
                ProviderSetting.OpenAI(
                    enabled = false,
                    models = listOf(model),
                ),
            ),
            assistants = listOf(assistant),
        ).buildConversationReadiness(
            assistant = assistant,
            workspaceNamesById = emptyMap(),
            memoryCount = 0,
        )

        assertEquals(ModelReadiness.NOT_CONFIGURED, readiness.modelState)
        assertEquals(null, readiness.modelName)
        assertFalse(readiness.canSend)
    }

    @Test
    fun `non-chat models do not make a conversation sendable`() {
        val imageModel = Model(
            modelId = "image-model",
            displayName = "Image Model",
            type = ModelType.IMAGE,
        )
        val assistant = Assistant(chatModelId = imageModel.id)
        val readiness = Settings(
            providers = listOf(ProviderSetting.OpenAI(models = listOf(imageModel))),
            assistants = listOf(assistant),
        ).buildConversationReadiness(
            assistant = assistant,
            workspaceNamesById = emptyMap(),
            memoryCount = 0,
        )

        assertEquals(ModelReadiness.NOT_CONFIGURED, readiness.modelState)
        assertFalse(readiness.canSend)
    }

    @Test
    fun `selected capabilities report ready counts and names`() {
        val model = Model(modelId = "chat-model", displayName = "Chat Model")
        val selectedServer = McpServerConfig.StreamableHTTPServer()
        val otherServer = McpServerConfig.SseTransportServer()
        val workspaceId = Uuid.random()
        val assistant = Assistant(
            chatModelId = model.id,
            mcpServers = setOf(selectedServer.id),
            workspaceId = workspaceId,
        )
        val readiness = Settings(
            providers = listOf(ProviderSetting.OpenAI(models = listOf(model))),
            assistants = listOf(assistant),
        ).buildConversationReadiness(
            assistant = assistant,
            workspaceNamesById = mapOf(workspaceId to "Factory project"),
            memoryCount = 3,
            mcpServers = listOf(
                selectedServer.presentation(McpStatus.Ready(toolCount = 1, catalogRevision = 1)),
                otherServer.presentation(),
            ),
        )

        assertEquals(ModelReadiness.READY, readiness.modelState)
        assertEquals("Chat Model", readiness.modelName)
        assertEquals(McpReadiness.READY, readiness.mcpState)
        assertEquals(1, readiness.selectedMcpCount)
        assertEquals(2, readiness.enabledMcpCount)
        assertEquals(3, readiness.localToolCount)
        assertEquals(WorkspaceReadiness.READY, readiness.workspaceState)
        assertEquals("Factory project", readiness.workspaceName)
        assertEquals(MemoryReadiness.READY, readiness.memoryState)
        assertEquals(3, readiness.memoryCount)
        assertTrue(readiness.canSend)
    }

    @Test
    fun `configured but disabled mcp servers remain optional and visible`() {
        val disabledServer = McpServerConfig.SseTransportServer(
            commonOptions = McpCommonOptions(enable = false),
        )
        val assistant = Assistant(mcpServers = setOf(disabledServer.id))
        val readiness = Settings(
            providers = emptyList(),
            assistants = listOf(assistant),
        ).buildConversationReadiness(
            assistant = assistant,
            workspaceNamesById = emptyMap(),
            memoryCount = 0,
            mcpServers = listOf(disabledServer.presentation()),
        )

        assertEquals(McpReadiness.ALL_DISABLED, readiness.mcpState)
        assertEquals(0, readiness.enabledMcpCount)
        assertEquals(0, readiness.selectedMcpCount)
    }

    @Test
    fun `selected mcp is not reported ready while catalog discovery is running`() {
        val server = McpServerConfig.StreamableHTTPServer()
        val assistant = Assistant(mcpServers = setOf(server.id))
        val readiness = Settings(
            assistants = listOf(assistant),
        ).buildConversationReadiness(
            assistant = assistant,
            workspaceNamesById = emptyMap(),
            memoryCount = 0,
            mcpServers = listOf(server.presentation(McpStatus.Discovering)),
        )

        assertEquals(McpReadiness.CONNECTING, readiness.mcpState)
        assertEquals(1, readiness.selectedMcpCount)
        assertEquals(0, readiness.readyMcpCount)
    }

    @Test
    fun `selected mcp is ready only with an active verified catalog`() {
        val server = McpServerConfig.StreamableHTTPServer()
        val assistant = Assistant(mcpServers = setOf(server.id))
        val readiness = Settings(
            assistants = listOf(assistant),
        ).buildConversationReadiness(
            assistant = assistant,
            workspaceNamesById = emptyMap(),
            memoryCount = 0,
            mcpServers = listOf(
                server.presentation(McpStatus.Ready(toolCount = 20, catalogRevision = 1))
            ),
        )

        assertEquals(McpReadiness.READY, readiness.mcpState)
        assertEquals(1, readiness.selectedMcpCount)
        assertEquals(1, readiness.readyMcpCount)
    }

    @Test
    fun `selected mcp remains ready from its catalog while transport is unavailable`() {
        val server = McpServerConfig.StreamableHTTPServer()
        val assistant = Assistant(mcpServers = setOf(server.id))
        val readiness = Settings(assistants = listOf(assistant)).buildConversationReadiness(
            assistant = assistant,
            workspaceNamesById = emptyMap(),
            memoryCount = 0,
            mcpServers = listOf(
                server.presentation(
                    status = McpStatus.Error("transport unavailable"),
                    hasCatalog = true,
                )
            ),
        )

        assertEquals(McpReadiness.READY, readiness.mcpState)
        assertEquals(1, readiness.readyMcpCount)
    }

    @Test
    fun `authorization and reconnect states are not collapsed into discovery`() {
        val authorizationServer = McpServerConfig.StreamableHTTPServer()
        val reconnectingServer = McpServerConfig.SseTransportServer()

        val authorizationReadiness = Settings().buildConversationReadiness(
            assistant = Assistant(mcpServers = setOf(authorizationServer.id)),
            workspaceNamesById = emptyMap(),
            memoryCount = 0,
            mcpServers = listOf(authorizationServer.presentation(McpStatus.NeedsAuthorization)),
        )
        val reconnectingReadiness = Settings().buildConversationReadiness(
            assistant = Assistant(mcpServers = setOf(reconnectingServer.id)),
            workspaceNamesById = emptyMap(),
            memoryCount = 0,
            mcpServers = listOf(
                reconnectingServer.presentation(McpStatus.Reconnecting(attempt = 1, maxAttempts = 5))
            ),
        )

        assertEquals(McpReadiness.AUTHORIZATION_REQUIRED, authorizationReadiness.mcpState)
        assertEquals(McpReadiness.RECONNECTING, reconnectingReadiness.mcpState)
    }

    @Test
    fun `partially available mcp selection preserves selected and ready counts`() {
        val readyServer = McpServerConfig.StreamableHTTPServer()
        val unavailableServer = McpServerConfig.SseTransportServer()
        val assistant = Assistant(mcpServers = setOf(readyServer.id, unavailableServer.id))
        val readiness = Settings(
            assistants = listOf(assistant),
        ).buildConversationReadiness(
            assistant = assistant,
            workspaceNamesById = emptyMap(),
            memoryCount = 0,
            mcpServers = listOf(
                readyServer.presentation(McpStatus.Ready(toolCount = 20, catalogRevision = 1)),
                unavailableServer.presentation(McpStatus.Error("unavailable")),
            ),
        )

        assertEquals(McpReadiness.PARTIAL, readiness.mcpState)
        assertEquals(2, readiness.selectedMcpCount)
        assertEquals(1, readiness.readyMcpCount)
    }

    @Test
    fun `disabled memory reports disabled state`() {
        val assistant = Assistant(enableMemory = false)
        val readiness = Settings(
            providers = emptyList(),
            assistants = listOf(assistant),
            mcpServers = emptyList(),
        ).buildConversationReadiness(
            assistant = assistant,
            workspaceNamesById = emptyMap(),
            memoryCount = 0,
        )

        assertEquals(MemoryReadiness.DISABLED, readiness.memoryState)
        assertEquals(0, readiness.memoryCount)
    }

    @Test
    fun `enabled memory with entries reports ready state and count`() {
        val assistant = Assistant(enableMemory = true)
        val readiness = Settings(
            providers = emptyList(),
            assistants = listOf(assistant),
            mcpServers = emptyList(),
        ).buildConversationReadiness(
            assistant = assistant,
            workspaceNamesById = emptyMap(),
            memoryCount = 5,
        )

        assertEquals(MemoryReadiness.READY, readiness.memoryState)
        assertEquals(5, readiness.memoryCount)
    }

    @Test
    fun `enabled but unavailable text to image is not counted as effective`() {
        val assistant = Assistant(
            localTools = listOf(LocalToolOption.TimeInfo, LocalToolOption.TextToImage),
        )
        val readiness = Settings(assistants = listOf(assistant)).buildConversationReadiness(
            assistant = assistant,
            workspaceNamesById = emptyMap(),
            memoryCount = 0,
            imageGenerationAvailable = false,
        )
        assertEquals(1, readiness.localToolCount)
        assertEquals(2, readiness.persistedLocalToolCount)
    }

    private fun McpServerConfig.presentation(
        status: McpStatus = McpStatus.Idle,
        hasCatalog: Boolean = status is McpStatus.Ready || status is McpStatus.CatalogStale,
    ): McpServerPresentation = McpServerPresentation(
        serverId = id,
        name = commonOptions.name,
        enabled = commonOptions.enable,
        definition = this,
        status = status,
        tools = if (hasCatalog) {
            listOf(
                McpToolPresentation(
                    name = "tool",
                    description = null,
                    inputSchema = kotlinx.serialization.json.JsonObject(emptyMap()),
                    enabled = true,
                    needsApproval = false,
                )
            )
        } else {
            emptyList()
        },
    )
}
