package net.weero.measix.pilot.service

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.data.ai.tools.PendingToolInteraction
import me.rerere.ai.ui.ToolInteractionState
import net.weero.measix.pilot.service.turn.TurnOutcome
import net.weero.measix.pilot.service.turn.TurnPause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationSideEffectsTest {
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
