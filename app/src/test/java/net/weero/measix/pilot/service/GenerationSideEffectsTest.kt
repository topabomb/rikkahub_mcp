package net.weero.measix.pilot.service

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.datetime.LocalDateTime
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.core.MessageRole
import net.weero.measix.pilot.data.ai.tools.PendingToolInteraction
import me.rerere.ai.ui.ToolInteractionState
import net.weero.measix.pilot.service.turn.TurnOutcome
import net.weero.measix.pilot.service.turn.TurnPause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class GenerationSideEffectsTest {
    @Test
    fun `approval sound follows committed pending state and is not replayed`() {
        var steps = 0
        var approvals = 0
        val finishedAt = LocalDateTime(2026, 1, 1, 12, 0)
        val initial = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList(), finishedAt = finishedAt)
        val pending = initial.copy(parts = listOf("first", "second").map { call ->
            UIMessagePart.Tool(
                localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = call,
                toolName = "calendar_create", input = "{}",
                interactionState = ToolInteractionState.AwaitingApproval,
            )
        })
        val tracker = GenerationSoundTracker(initial, Json, { steps++ }, { approvals++ })

        tracker.onStreaming(pending, enabled = true)
        assertEquals(0, approvals)
        assertEquals(0, steps)
        tracker.onCheckpoint(pending, enabled = true)
        tracker.onCheckpoint(pending, enabled = true)
        assertEquals(1, approvals)
        assertEquals(0, steps)

        val resumed = GenerationSoundTracker(pending, Json, { steps++ }, { approvals++ })
        resumed.onStreaming(pending, enabled = true)
        resumed.onCheckpoint(pending, enabled = true)
        assertEquals(1, approvals)
        assertEquals(0, steps)
    }

    @Test
    fun `attention observed while sound is disabled is not played later`() {
        var approvals = 0
        val initial = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
        val pending = initial.copy(parts = listOf(UIMessagePart.Tool(
            localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call",
            toolName = "calendar_create", input = "{}",
            interactionState = ToolInteractionState.AwaitingApproval,
        )))
        val tracker = GenerationSoundTracker(initial, Json, {}, { approvals++ })

        tracker.onCheckpoint(pending, enabled = false)
        tracker.onCheckpoint(pending, enabled = true)
        assertEquals(0, approvals)
    }

    @Test
    fun `background generation params preserve model request configuration`() {
        val headers = listOf(CustomHeader(name = "X-Gateway-Token", value = "test-token"))
        val bodies = listOf(CustomBody(key = "gateway_mode", value = JsonPrimitive("strict")))
        val model = Model(modelId = "custom-chat-model", customHeaders = headers, customBodies = bodies)

        val params = backgroundTextGenerationParams(model, ReasoningLevel.LOW)

        assertEquals(model, params.model)
        assertEquals(ReasoningLevel.LOW, params.reasoningLevel)
        assertEquals(headers, params.customHeaders)
        assertEquals(bodies, params.customBody)
    }

    @Test
    fun `only completed turns launch completion side effects`() {
        assertTrue(shouldLaunchCompletionSideEffects(TurnOutcome.Completed(assistantMessage = UIMessage.assistant("done"))))
        assertFalse(shouldLaunchCompletionSideEffects(TurnPause(
            listOf(PendingToolInteraction(me.rerere.ai.core.ToolCallLocator(kotlin.uuid.Uuid.random(), kotlin.uuid.Uuid.random(), kotlin.uuid.Uuid.random()), ToolInteractionState.AwaitingApproval)),
        )))
        assertFalse(shouldLaunchCompletionSideEffects(TurnOutcome.Incomplete("step_limit")))
    }
}
