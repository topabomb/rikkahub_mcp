package net.weero.measix.pilot.service

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.MessageTerminalStatus
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.FinishedReason
import net.weero.measix.pilot.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ChatServiceTest {

    // region backgroundTextGenerationParams

    @Test
    fun `background generation params include model custom request configuration`() {
        val headers = listOf(CustomHeader(name = "X-Gateway-Token", value = "test-token"))
        val bodies = listOf(CustomBody(key = "gateway_mode", value = JsonPrimitive("strict")))
        val model = Model(
            modelId = "custom-chat-model",
            customHeaders = headers,
            customBodies = bodies,
        )

        val params = backgroundTextGenerationParams(model)

        assertEquals(model, params.model)
        assertEquals(ReasoningLevel.AUTO, params.reasoningLevel)
        assertEquals(headers, params.customHeaders)
        assertEquals(bodies, params.customBody)
    }

    @Test
    fun `background generation params default reasoning level is AUTO`() {
        val model = Model(modelId = "test-model")
        val params = backgroundTextGenerationParams(model)
        assertEquals(ReasoningLevel.AUTO, params.reasoningLevel)
    }

    @Test
    fun `background generation params with custom reasoning level`() {
        val model = Model(modelId = "test-model")
        val params = backgroundTextGenerationParams(model, ReasoningLevel.LOW)
        assertEquals(ReasoningLevel.LOW, params.reasoningLevel)
    }

    @Test
    fun `background generation params with empty custom headers and bodies`() {
        val model = Model(modelId = "test-model")
        val params = backgroundTextGenerationParams(model)
        assertTrue(params.customHeaders.isEmpty())
        assertTrue(params.customBody.isEmpty())
    }

    @Test
    fun `background generation params preserves model id`() {
        val model = Model(modelId = "gpt-4o-mini")
        val params = backgroundTextGenerationParams(model)
        assertEquals("gpt-4o-mini", params.model.modelId)
    }

    // endregion

    // region generation lifecycle

    @Test
    fun `awaiting approval does not launch completion side effects`() {
        assertFalse(shouldLaunchCompletionSideEffects(FinishedReason.AWAITING_APPROVAL))
    }

    @Test
    fun `retainValidMessageNodes keeps finalized interrupted assistant with open tools`() {
        val openTool = UIMessagePart.Tool(
            toolCallId = "call-1",
            toolName = "generate_image",
            input = "{}",
        )
        val cancelled = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(openTool),
            terminalStatus = MessageTerminalStatus.CANCELLED,
            terminalReason = "user_stop",
        ).toMessageNode()
        val resumable = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(openTool.copy(approvalState = ToolApprovalState.Approved)),
        ).toMessageNode()
        val illegal = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(openTool),
        ).toMessageNode()

        val pending = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(openTool.copy(approvalState = ToolApprovalState.Pending)),
        ).toMessageNode()
        val retained = retainValidMessageNodes(listOf(cancelled, resumable, pending, illegal))
        assertEquals(3, retained.size)
        assertEquals(cancelled.id, retained[0].id)
        assertEquals(resumable.id, retained[1].id)
        assertEquals(pending.id, retained[2].id)
    }

    @Test
    fun `only completed turns launch completion side effects`() {
        assertTrue(shouldLaunchCompletionSideEffects(FinishedReason.COMPLETED))
        assertFalse(shouldLaunchCompletionSideEffects(FinishedReason.STEP_LIMIT_REACHED))
        assertFalse(shouldLaunchCompletionSideEffects(FinishedReason.INTERACTION_LIMIT_REACHED))
        assertFalse(shouldLaunchCompletionSideEffects(null))
    }

    // endregion

    // region ChatError

    @Test
    fun `chat error has unique id`() {
        val error1 = ChatError(error = RuntimeException("test1"))
        val error2 = ChatError(error = RuntimeException("test2"))
        assertTrue(error1.id != error2.id)
    }

    @Test
    fun `chat error preserves title and message`() {
        val error = ChatError(
            title = "Generation Failed",
            error = RuntimeException("API rate limit exceeded"),
            conversationId = Uuid.random()
        )
        assertEquals("Generation Failed", error.title)
        assertEquals("API rate limit exceeded", error.error.message)
        assertNotNull(error.conversationId)
    }

    @Test
    fun `chat error timestamp is set`() {
        val before = System.currentTimeMillis()
        val error = ChatError(error = RuntimeException("test"))
        val after = System.currentTimeMillis()
        assertTrue(error.timestamp in before..after)
    }

    // endregion
}
