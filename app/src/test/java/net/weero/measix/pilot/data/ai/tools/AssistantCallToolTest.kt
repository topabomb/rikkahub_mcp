package net.weero.measix.pilot.data.ai.tools

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    private fun createTool(coordinator: SubAssistantCoordinator?): me.rerere.ai.core.Tool {
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
    fun `assistant call description keeps isolation guidance`() {
        val tool = createTool(mockk(relaxed = true))
        assertTrue(tool.description.contains("Do not prescribe how it must work"))
        assertFalse(tool.description.contains("concise, high-value reply"))
        val requestDescription = tool.parameters()!!["properties"]!!
            .jsonObject["request"]!!
            .jsonObject["description"]!!
            .jsonPrimitive
            .content
        assertTrue(requestDescription.contains("It cannot see this chat"))
        assertTrue(requestDescription.contains("concise, high-value reply is usually enough"))
        val extrasDescription = tool.parameters()!!["properties"]!!
            .jsonObject["extras"]!!
            .jsonObject["description"]!!
            .jsonPrimitive
            .content
        assertTrue(extrasDescription.contains("Extra result content"))
        assertTrue(extrasDescription.contains("tts"))
        assertTrue(extrasDescription.contains("tool_calls"))
    }

    @Test
    fun `missing coordinator returns runtime_error with detail`() = runTest {
        val tool = createTool(null)
        val result = tool.executeWithContext(
            executionContext(),
            buildJsonObject {
                put("assistant_id", targetId.toString())
                put("request", "Do the work")
            },
        )
        val payload = Json.parseToJsonElement((result.single() as UIMessagePart.Text).text).jsonObject
        assertEquals("failed", payload["status"]?.jsonPrimitive?.content)
        assertEquals("runtime_error", payload["reason"]?.jsonPrimitive?.content)
        assertTrue(payload["detail"]?.jsonPrimitive?.content?.contains("coordinator") == true)
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

    @Test
    fun `extras are forwarded and unknown values dropped`() = runTest {
        val coordinator = mockk<SubAssistantCoordinator>()
        val tool = createTool(coordinator)
        val expected = listOf(UIMessagePart.Text("done"))
        coEvery {
            coordinator.executeCall(
                callerAssistantId = callerId,
                masterConversationId = masterConversationId,
                targetAssistantId = targetId,
                task = "Speak",
                execContext = any(),
                extras = setOf("tts", "tool_calls"),
            )
        } returns expected

        val result = tool.executeWithContext(
            executionContext(),
            buildJsonObject {
                put("assistant_id", targetId.toString())
                put("request", "Speak")
                put("extras", buildJsonArray {
                    add(JsonPrimitive("tts"))
                    add(JsonPrimitive("tool_calls"))
                    add(JsonPrimitive("preview"))
                })
            },
        )

        assertEquals(expected, result)
        coVerify(exactly = 1) {
            coordinator.executeCall(
                callerAssistantId = callerId,
                masterConversationId = masterConversationId,
                targetAssistantId = targetId,
                task = "Speak",
                execContext = any(),
                extras = setOf("tts", "tool_calls"),
            )
        }
    }
}
