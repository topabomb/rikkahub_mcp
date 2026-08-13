package net.weero.measix.pilot.data.ai.tools

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.ToolExecutionContext
import me.rerere.ai.core.ToolOutputPolicy
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.tools.local.LocalToolOption
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.service.AssistantManagementService
import net.weero.measix.pilot.service.SubAssistantCoordinator
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class AssistantCallToolTest {
    private val callerId = Uuid.random()
    private val targetId = Uuid.random()
    private val masterConversationId = Uuid.random()

    private val caller = Assistant(
        id = callerId,
        localTools = listOf(LocalToolOption.AssistantDelegation),
        allowedSubAssistantIds = setOf(targetId),
    )
    private val target = Assistant(
        id = targetId,
        allowAsSubAssistant = true,
        description = "Target",
    )

    private fun createTool(coordinator: SubAssistantCoordinator): me.rerere.ai.core.Tool {
        val settingsFlow = MutableStateFlow(
            Settings(
                assistants = listOf(caller, target),
                assistantId = callerId,
            )
        )
        val settingsStore = mockk<SettingsStore>()
        every { settingsStore.settingsFlow } returns settingsFlow
        every { settingsStore.settingsFlow.value } returns settingsFlow.value
        return AssistantToolFactory(
            settingsStore = settingsStore,
            assistantManagementService = mockk<AssistantManagementService>(relaxed = true),
            json = Json,
            subAssistantCoordinator = coordinator,
        ).buildTools(caller, masterConversationId).single { it.name == "assistant_call" }
    }

    private fun executionContext() = ToolExecutionContext(
        messageId = Uuid.random(),
        toolOrdinal = 0,
        toolCallId = "provider-call",
        reportMetadata = { _, _ -> },
    )

    @Test
    fun `assistant call preserves its structured JSON result`() {
        val tool = createTool(mockk(relaxed = true))
        assertEquals(ToolOutputPolicy.PRESERVE, tool.outputPolicy)
    }

    @Test
    fun `blank request is rejected before coordinator starts a run`() = runTest {
        val coordinator = mockk<SubAssistantCoordinator>(relaxed = true)
        val tool = createTool(coordinator)

        val result = tool.executeWithContext(
            executionContext(),
            buildJsonObject {
                put("assistant_id", targetId.toString())
                put("request", "  \n")
            },
        )

        val payload = Json.parseToJsonElement((result.single() as UIMessagePart.Text).text).jsonObject
        assertEquals("request_required", payload["error"]?.jsonPrimitive?.content)
        coVerify(exactly = 0) { coordinator.executeCall(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `nonblank request delegates with caller and master context`() = runTest {
        val coordinator = mockk<SubAssistantCoordinator>()
        val tool = createTool(coordinator)
        val expected = listOf(UIMessagePart.Text("done"))
        coEvery {
            coordinator.executeCall(
                callerAssistantId = callerId,
                masterConversationId = masterConversationId,
                targetAssistantId = targetId,
                task = "Do the work",
                execContext = any(),
            )
        } returns expected

        val result = tool.executeWithContext(
            executionContext(),
            buildJsonObject {
                put("assistant_id", targetId.toString())
                put("request", "Do the work")
            },
        )

        assertEquals(expected, result)
        coVerify(exactly = 1) {
            coordinator.executeCall(callerId, masterConversationId, targetId, "Do the work", any())
        }
    }
}
