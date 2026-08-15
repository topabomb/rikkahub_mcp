package net.weero.measix.pilot.ui.pages.chat

import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import net.weero.measix.pilot.data.ai.mcp.McpCommonOptions
import net.weero.measix.pilot.data.ai.mcp.McpServerConfig
import net.weero.measix.pilot.data.ai.tools.local.LocalToolOption
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.model.Assistant
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
            mcpServers = listOf(selectedServer, otherServer),
        ).buildConversationReadiness(
            assistant = assistant,
            workspaceNamesById = mapOf(workspaceId to "Factory project"),
            memoryCount = 3,
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
            mcpServers = listOf(disabledServer),
        ).buildConversationReadiness(
            assistant = assistant,
            workspaceNamesById = emptyMap(),
            memoryCount = 0,
        )

        assertEquals(McpReadiness.ALL_DISABLED, readiness.mcpState)
        assertEquals(0, readiness.enabledMcpCount)
        assertEquals(0, readiness.selectedMcpCount)
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
}
