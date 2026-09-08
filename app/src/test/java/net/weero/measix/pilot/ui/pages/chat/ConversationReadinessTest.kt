package net.weero.measix.pilot.ui.pages.chat

import me.rerere.ai.provider.Model
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.ai.mcp.McpStatus
import net.weero.measix.pilot.data.ai.tools.local.LocalToolOption
import net.weero.measix.pilot.data.configuration.ConfigurationUnavailableReason
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.service.AssistantMcpChoice
import net.weero.measix.pilot.service.McpToolPresentation
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationReadinessTest {
    @Test
    fun `removed assistant cannot send despite other available models and does not suggest configuring a provider`() {
        val readiness = Settings().buildConversationReadiness(null, emptyMap(), 0,
            selectedModel = null, hasAvailableChatModel = true, modelUnavailableReason = ConfigurationUnavailableReason.REFERENCE_MISSING)
        assertFalse(readiness.canSend)
        assertFalse(readiness.requiresProviderConfiguration)
        assertEquals(0, readiness.localToolCount)
        assertEquals(ConfigurationUnavailableReason.REFERENCE_MISSING, readiness.modelUnavailableReason)
    }

    @Test
    fun `unavailable explicit model stays blocked until an admitted model is selected`() {
        val assistant = Assistant(chatModelId = ConfigurationReference.random())
        val blocked = Settings().buildConversationReadiness(assistant, emptyMap(), 0,
            selectedModel = null, hasAvailableChatModel = true, modelUnavailableReason = ConfigurationUnavailableReason.USER_CATEGORY_NOT_ALLOWED)
        val ready = Settings().buildConversationReadiness(assistant, emptyMap(), 0,
            selectedModel = Model(displayName = "Enterprise model"), hasAvailableChatModel = true)
        assertEquals(ModelReadiness.NOT_SELECTED, blocked.modelState)
        assertFalse(blocked.canSend)
        assertTrue(ready.canSend)
        assertEquals("Enterprise model", ready.modelName)
    }

    @Test
    fun `optional tools do not block model readiness and directory capability is not a runtime catalog`() {
        val workspace = Uuid.random()
        val assistant = Assistant(workspaceId = workspace, localTools = listOf(LocalToolOption.TimeInfo, LocalToolOption.TextToImage))
        val choice = AssistantMcpChoice(ConfigurationReference.random(), "Managed", true, false, null, McpStatus.Idle, emptyList())
        val readiness = Settings().buildConversationReadiness(assistant, mapOf(workspace to "Shared workspace"), 3,
            mcpServers = listOf(choice), selectedModel = Model(), hasAvailableChatModel = true)
        assertTrue(readiness.canSend)
        assertEquals(McpReadiness.UNAVAILABLE, readiness.mcpState)
        assertEquals(0, readiness.readyMcpCount)
        assertEquals(1, readiness.localToolCount)
        assertEquals(2, readiness.persistedLocalToolCount)
        assertEquals("Shared workspace", readiness.workspaceName)
        assertEquals(3, readiness.memoryCount)
    }

    @Test
    fun `catalog and runtime failure states retain their distinct display semantics`() {
        val states = listOf(McpStatus.Discovering to McpReadiness.CONNECTING,
            McpStatus.NeedsAuthorization to McpReadiness.AUTHORIZATION_REQUIRED,
            McpStatus.Reconnecting(1, 5) to McpReadiness.RECONNECTING,
            McpStatus.Error("unavailable") to McpReadiness.UNAVAILABLE)
        val assistant = Assistant()
        states.forEach { (status, expected) ->
            val choice = AssistantMcpChoice(ConfigurationReference.random(), "MCP", true, true, null, status, emptyList())
            val blocked = Settings().buildConversationReadiness(assistant, emptyMap(), 0,
                mcpServers = listOf(choice), selectedModel = Model(), hasAvailableChatModel = true)
            assertEquals(expected, blocked.mcpState)
            val withCatalog = choice.copy(tools = listOf(McpToolPresentation("tool", null, JsonObject(emptyMap()), true, false)))
            val ready = Settings().buildConversationReadiness(assistant, emptyMap(), 0,
                mcpServers = listOf(withCatalog), selectedModel = Model(), hasAvailableChatModel = true)
            assertEquals(McpReadiness.READY, ready.mcpState)
        }
    }
}
