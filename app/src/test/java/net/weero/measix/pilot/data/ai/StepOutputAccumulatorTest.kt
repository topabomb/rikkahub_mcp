package net.weero.measix.pilot.data.ai
import net.weero.measix.pilot.service.turn.StepOutputAccumulator

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ClaudeReasoningMetadata
import me.rerere.ai.ui.GoogleThoughtMetadata
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.OpenAIReasoningMetadata
import me.rerere.ai.ui.OpenRouterReasoningMetadata
import me.rerere.ai.ui.ToolResultStatus
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.toMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * The streaming-merge contract, moved from the retired `:ai` `handleMessageChunk` tests.
 * The accumulator owns Step emission and stable call identity; providers only ever emit NIL ids.
 */
class StepOutputAccumulatorTest {

    private fun assistantChunk(vararg parts: UIMessagePart) = MessageChunk(
        id = "chunk",
        model = "test-model",
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList()),
                message = null,
                finishReason = null,
            ),
        ),
    )

    private fun newAssistant() = UIMessage(
        id = Uuid.random(),
        role = MessageRole.ASSISTANT,
        parts = emptyList(),
    )

    private fun content(parts: List<UIMessagePart>) = parts.filterNot { it is UIMessagePart.Step }

    @Test
    fun `first chunk opens a Step and later chunks stay in it`() {
        val acc = StepOutputAccumulator(Uuid.random())
        acc.beginStep()
        var messages = listOf(UIMessage.user("hi"), newAssistant())
        messages = acc.accumulate(messages, assistantChunk(UIMessagePart.Text("hello")), null)
        messages = acc.accumulate(messages, assistantChunk(UIMessagePart.Text(" world")), null)
        val parts = messages.last().parts
        assertEquals(1, parts.count { it is UIMessagePart.Step })
        assertEquals("hello world", content(parts).filterIsInstance<UIMessagePart.Text>().single().text)
    }

    @Test
    fun `out of order tool content and reasoning deltas are normalized within the step`() {
        val acc = StepOutputAccumulator(Uuid.random())
        acc.beginStep()
        var messages = listOf(UIMessage.user("Use a tool"), newAssistant())
        messages = acc.accumulate(
            messages,
            assistantChunk(UIMessagePart.Tool(localCallId = Uuid.NIL, stepId = Uuid.NIL, providerCallId = "call-1", toolName = "lookup", input = "{}")),
            null,
        )
        messages = acc.accumulate(messages, assistantChunk(UIMessagePart.Text("Calling lookup")), null)
        messages = acc.accumulate(messages, assistantChunk(UIMessagePart.Reasoning(reasoning = "Need lookup")), null)

        val parts = content(messages.last().parts)
        assertEquals(3, parts.size)
        assertTrue(parts[0] is UIMessagePart.Reasoning)
        assertTrue(parts[1] is UIMessagePart.Text)
        assertTrue(parts[2] is UIMessagePart.Tool)
        assertEquals("Need lookup", (parts[0] as UIMessagePart.Reasoning).reasoning)
        assertEquals("Calling lookup", (parts[1] as UIMessagePart.Text).text)
    }

    @Test
    fun `a new tool is stamped with the current step id and a fresh random local call id`() {
        val acc = StepOutputAccumulator(Uuid.random())
        acc.beginStep()
        var messages = listOf(UIMessage.user("hi"), newAssistant())
        messages = acc.accumulate(
            messages,
            assistantChunk(UIMessagePart.Tool(localCallId = Uuid.NIL, stepId = Uuid.NIL, providerCallId = "call-1", toolName = "lookup", input = "{}")),
            null,
        )
        val tool = messages.last().parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals(acc.currentStepId, tool.stepId)
        assertEquals("call-1", tool.providerCallId)
        assertTrue(tool.localCallId != Uuid.NIL)
    }

    @Test
    fun `completed tool history from a prior step is not reopened`() {
        val acc = StepOutputAccumulator(Uuid.random())
        acc.beginStep()
        var messages = listOf(UIMessage.user("Use tools"), newAssistant())
        messages = acc.accumulate(
            messages,
            assistantChunk(
                UIMessagePart.Reasoning(reasoning = "First reasoning"),
                UIMessagePart.Text("First content"),
                UIMessagePart.Tool(
                    localCallId = Uuid.NIL,
                    stepId = Uuid.NIL,
                    providerCallId = "call-1",
                    toolName = "first",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("first result")),
                ),
            ),
            null,
        )
        // Second step: reasoning after a completed tool opens a new Step.
        acc.beginStep()
        messages = acc.accumulate(
            messages,
            assistantChunk(UIMessagePart.Tool(localCallId = Uuid.NIL, stepId = Uuid.NIL, providerCallId = "call-2", toolName = "second", input = "{}")),
            null,
        )
        messages = acc.accumulate(messages, assistantChunk(UIMessagePart.Text("Second content")), null)

        val parts = messages.last().parts
        assertEquals(2, parts.count { it is UIMessagePart.Step })
        val tools = parts.filterIsInstance<UIMessagePart.Tool>()
        assertEquals("call-1", tools[0].providerCallId)
        assertEquals("call-2", tools[1].providerCallId)
        // The two tools live in different steps.
        assertTrue(tools[0].stepId != tools[1].stepId)
    }

    @Test
    fun `blank tool delta continues the latest pending tool in the step`() {
        val acc = StepOutputAccumulator(Uuid.random())
        acc.beginStep()
        var messages = listOf(UIMessage.user("hi"), newAssistant())
        messages = acc.accumulate(
            messages,
            assistantChunk(UIMessagePart.Tool(localCallId = Uuid.NIL, stepId = Uuid.NIL, providerCallId = "call-1", toolName = "first", input = "{")),
            null,
        )
        messages = acc.accumulate(
            messages,
            assistantChunk(UIMessagePart.Tool(localCallId = Uuid.NIL, stepId = Uuid.NIL, providerCallId = "", toolName = "", input = "\"a\":1}")),
            null,
        )
        val tools = messages.last().parts.filterIsInstance<UIMessagePart.Tool>()
        assertEquals(1, tools.size)
        assertEquals("first", tools[0].toolName)
        assertEquals("{\"a\":1}", tools[0].input)
    }

    @Test
    fun `complete image urls are not concatenated`() {
        val acc = StepOutputAccumulator(Uuid.random())
        acc.beginStep()
        var messages = listOf(UIMessage.user("Draw"), newAssistant())
        messages = acc.accumulate(messages, assistantChunk(UIMessagePart.Image(url = "data:image/jpeg;base64,AAA")), null)
        messages = acc.accumulate(messages, assistantChunk(UIMessagePart.Image(url = "data:image/png;base64,BBB")), null)
        val images = messages.last().parts.filterIsInstance<UIMessagePart.Image>()
        assertEquals(2, images.size)
        assertEquals("data:image/jpeg;base64,AAA", images[0].url)
        assertEquals("data:image/png;base64,BBB", images[1].url)
    }

    @Test
    fun `raw image fragments append to the current data uri`() {
        val acc = StepOutputAccumulator(Uuid.random())
        acc.beginStep()
        var messages = listOf(UIMessage.user("Stream"), newAssistant())
        messages = acc.accumulate(messages, assistantChunk(UIMessagePart.Image(url = "AAA")), null)
        messages = acc.accumulate(messages, assistantChunk(UIMessagePart.Image(url = "BBB")), null)
        val images = messages.last().parts.filterIsInstance<UIMessagePart.Image>()
        assertEquals(1, images.size)
        assertEquals("data:image/png;base64,AAABBB", images[0].url)
    }

    @Test
    fun `openrouter reasoning details accumulate across chunks`() {
        val acc = StepOutputAccumulator(Uuid.random())
        acc.beginStep()
        var messages = listOf(UIMessage.user("Plan"), newAssistant())
        messages = acc.accumulate(
            messages,
            assistantChunk(
                reasoningWithDetails("hidden ", buildJsonArray {
                    add(buildJsonObject { put("id", "rd-1"); put("type", "reasoning.text"); put("text", "hidden "); put("index", 0) })
                }),
            ),
            null,
        )
        messages = acc.accumulate(
            messages,
            assistantChunk(
                reasoningWithDetails("plan", buildJsonArray {
                    add(buildJsonObject { put("id", "rd-1"); put("type", "reasoning.text"); put("text", "plan"); put("index", 0) })
                    add(buildJsonObject { put("id", "rd-2"); put("type", "reasoning.summary"); put("text", "summary"); put("index", 1) })
                }),
            ),
            null,
        )
        val reasoning = messages.last().parts.filterIsInstance<UIMessagePart.Reasoning>().single()
        assertEquals("hidden plan", reasoning.reasoning)
        assertNotNull(reasoning.metadataAs<OpenRouterReasoningMetadata>()?.reasoningDetails)
    }

    @Test
    fun `begin step increments ordinal and resets the open flag`() {
        val acc = StepOutputAccumulator(Uuid.random())
        acc.beginStep()
        val firstStep = acc.currentStepId
        var messages = listOf(UIMessage.user("hi"), newAssistant())
        messages = acc.accumulate(messages, assistantChunk(UIMessagePart.Text("a")), null)
        acc.beginStep()
        assertFalse(firstStep == acc.currentStepId)
        messages = acc.accumulate(messages, assistantChunk(UIMessagePart.Text("b")), null)
        assertEquals(2, messages.last().parts.count { it is UIMessagePart.Step })
    }

    @Test
    fun `signed empty text part remains a separate streaming boundary`() {
        val acc = StepOutputAccumulator(Uuid.random())
        acc.beginStep()
        var messages = listOf(UIMessage.user("hi"), newAssistant())
        messages = acc.accumulate(messages, assistantChunk(UIMessagePart.Text("answer")), null)
        messages = acc.accumulate(
            messages,
            assistantChunk(UIMessagePart.Text(text = "", metadata = GoogleThoughtMetadata(thoughtSignature = "signature").toMetadata())),
            null,
        )
        val texts = content(messages.last().parts).filterIsInstance<UIMessagePart.Text>()
        assertEquals(2, texts.size)
        assertEquals("answer", texts[0].text)
        assertEquals("signature", texts[1].metadataAs<GoogleThoughtMetadata>()?.thoughtSignature)
    }

    @Test
    fun `redacted claude reasoning remains separate from visible thinking`() {
        val acc = StepOutputAccumulator(Uuid.random())
        acc.beginStep()
        var messages = listOf(UIMessage.user("hi"), newAssistant())
        messages = acc.accumulate(messages, assistantChunk(UIMessagePart.Reasoning("visible thinking")), null)
        messages = acc.accumulate(
            messages,
            assistantChunk(UIMessagePart.Reasoning(reasoning = "", metadata = ClaudeReasoningMetadata(redactedData = "opaque").toMetadata())),
            null,
        )
        val reasonings = content(messages.last().parts).filterIsInstance<UIMessagePart.Reasoning>()
        assertEquals(2, reasonings.size)
        assertEquals("opaque", reasonings.last().metadataAs<ClaudeReasoningMetadata>()?.redactedData)
    }

    @Test
    fun `reasoning text delta and done id merge into one part`() {
        val acc = StepOutputAccumulator(Uuid.random())
        acc.beginStep()
        var messages = listOf(UIMessage.user("hi"), newAssistant())
        messages = acc.accumulate(messages, assistantChunk(UIMessagePart.Reasoning("Streaming thinking")), null)
        messages = acc.accumulate(
            messages,
            assistantChunk(
                UIMessagePart.Reasoning(
                    reasoning = "",
                    metadata = OpenAIReasoningMetadata(reasoningId = "rs_stream").toMetadata(),
                ),
            ),
            null,
        )
        val reasoning = content(messages.last().parts).filterIsInstance<UIMessagePart.Reasoning>().single()
        assertEquals("Streaming thinking", reasoning.reasoning)
        assertEquals("rs_stream", reasoning.metadataAs<OpenAIReasoningMetadata>()?.reasoningId)
    }

    @Test
    fun `tool argument fragments concatenate by provider call id`() {
        val acc = StepOutputAccumulator(Uuid.random())
        acc.beginStep()
        var messages = listOf(UIMessage.user("hi"), newAssistant())
        messages = acc.accumulate(
            messages,
            assistantChunk(UIMessagePart.Tool(localCallId = Uuid.NIL, stepId = Uuid.NIL, providerCallId = "call_1", toolName = "lookup", input = "")),
            null,
        )
        messages = acc.accumulate(
            messages,
            assistantChunk(UIMessagePart.Tool(localCallId = Uuid.NIL, stepId = Uuid.NIL, providerCallId = "call_1", toolName = "", input = "{\"query\":")),
            null,
        )
        messages = acc.accumulate(
            messages,
            assistantChunk(UIMessagePart.Tool(localCallId = Uuid.NIL, stepId = Uuid.NIL, providerCallId = "call_1", toolName = "", input = "\"test\"}")),
            null,
        )
        val tool = content(messages.last().parts).filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals("call_1", tool.providerCallId)
        assertEquals("lookup", tool.toolName)
        assertEquals("{\"query\":\"test\"}", tool.input)
    }

    @Test
    fun `fromDraft continues the ordinal after the last committed Step on a continuation`() {
        val assistantId = Uuid.random()
        val priorStepId = Uuid.random()
        val resumed = UIMessage(
            id = assistantId,
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Step(stepId = priorStepId, ordinal = 0, startedAt = kotlin.time.Instant.DISTANT_PAST),
                UIMessagePart.Tool(
                    localCallId = Uuid.random(), stepId = priorStepId, providerCallId = "c0",
                    toolName = "lookup", input = "{}", output = listOf(UIMessagePart.Text("r")),
                    resultStatus = ToolResultStatus.COMPLETED,
                ),
            ),
        )
        val acc = StepOutputAccumulator.fromDraft(assistantId, resumed)
        acc.beginStep()
        val messages = acc.accumulate(
            listOf(UIMessage.user("hi"), resumed),
            assistantChunk(UIMessagePart.Text("next")),
            null,
        )
        val steps = messages.last().parts.filterIsInstance<UIMessagePart.Step>()
        // 续跑不得重发 ordinal=0：新 Step 严格递增，且带自己的新 stepId。
        assertEquals(2, steps.size)
        assertEquals(1, steps.last().ordinal)
        assertEquals(acc.currentStepId, steps.last().stepId)
        assertTrue(steps.last().stepId != priorStepId)
    }

    @Test
    fun `fromDraft reuses a START-preopened empty Step for the first sampling`() {
        val assistantId = Uuid.random()
        val preopenedStepId = Uuid.random()
        val started = UIMessage(
            id = assistantId,
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Step(stepId = preopenedStepId, ordinal = 0, startedAt = kotlin.time.Instant.DISTANT_PAST),
            ),
        )
        val acc = StepOutputAccumulator.fromDraft(assistantId, started)
        acc.beginStep()
        val messages = acc.accumulate(
            listOf(UIMessage.user("hi"), started),
            assistantChunk(UIMessagePart.Text("first")),
            null,
        )
        val steps = messages.last().parts.filterIsInstance<UIMessagePart.Step>()
        // START 已落 Step(0)：首个采样复用同一 stepId，绝不重复建 Step。
        assertEquals(1, steps.size)
        assertEquals(0, steps.single().ordinal)
        assertEquals(preopenedStepId, steps.single().stepId)
    }

    private fun reasoningWithDetails(text: String, details: kotlinx.serialization.json.JsonArray): UIMessagePart.Reasoning =
        UIMessagePart.Reasoning(reasoning = text, metadata = OpenRouterReasoningMetadata(reasoningDetails = details).toMetadata())
}
