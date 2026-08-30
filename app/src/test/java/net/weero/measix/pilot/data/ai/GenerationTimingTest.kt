package net.weero.measix.pilot.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationTimingTest {
    @Test
    fun `empty protocol events do not count as first model output`() {
        assertFalse(chunk(emptyList()).hasModelOutputPayload())
        assertFalse(chunk(listOf(UIMessagePart.Text(""))).hasModelOutputPayload())
        assertFalse(chunk(listOf(UIMessagePart.Reasoning(""))).hasModelOutputPayload())
    }

    @Test
    fun `text reasoning and tool payloads count as first model output`() {
        assertTrue(chunk(listOf(UIMessagePart.Text("a"))).hasModelOutputPayload())
        assertTrue(chunk(listOf(UIMessagePart.Reasoning("thinking"))).hasModelOutputPayload())
        assertTrue(
            chunk(
                listOf(
                    UIMessagePart.Tool(
                        toolCallId = "call-1",
                        toolName = "lookup",
                        input = "{}",
                    )
                )
            ).hasModelOutputPayload()
        )
    }

    private fun chunk(parts: List<UIMessagePart>): MessageChunk = MessageChunk(
        id = "chunk",
        model = "model",
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = UIMessage(role = MessageRole.ASSISTANT, parts = parts),
                message = null,
                finishReason = null,
            )
        ),
    )
}
