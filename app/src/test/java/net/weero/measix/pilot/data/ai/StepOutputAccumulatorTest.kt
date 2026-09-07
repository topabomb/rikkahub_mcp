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
 * The accumulator consumes an existing Step and owns stable call identity.
 */
class StepOutputAccumulatorTest {

    private fun assistantChunk(vararg parts: UIMessagePart, slots: List<Int> = parts.filterIsInstance<UIMessagePart.Tool>().indices.toList()) = MessageChunk(
        id = "chunk",
        model = "test-model",
        choices = listOf(
            UIMessageChoice(
                index = 0,
                delta = UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList()),
                toolCallSlots = slots.map { me.rerere.ai.ui.ProviderToolCallSlot.Index(it) },
                message = null,
                finishReason = null,
            ),
        ),
    )

    private fun newAssistant() = UIMessage(
        id = Uuid.random(),
        role = MessageRole.ASSISTANT,
        parts = listOf(net.weero.measix.pilot.service.runtime.TurnTransition.openStep(0)),
    )

    private fun content(parts: List<UIMessagePart>) = parts.filterNot { it is UIMessagePart.Step }

    @Test
    fun `interleaved slots keep repeated and empty provider IDs distinct`() {
        for (providerId in listOf("duplicate", "")) {
            val acc = StepOutputAccumulator()
            var active = newAssistant()
            fun tool(name: String = "", input: String) = UIMessagePart.Tool(
                localCallId = Uuid.NIL, stepId = Uuid.NIL, providerCallId = providerId,
                toolName = name, input = input,
            )
            active = acc.accumulate(active, assistantChunk(tool("first", "{"), slots = listOf(4)), null)
            active = acc.accumulate(active, assistantChunk(tool("second", "{"), slots = listOf(9)), null)
            val firstIdentity = active.getTools().first().localCallId
            active = acc.accumulate(active, assistantChunk(tool(input = "\"b\":2}"), slots = listOf(9)), null)
            active = acc.accumulate(active, assistantChunk(tool(input = "\"a\":1}"), slots = listOf(4)), null)
            val calls = active.getTools()
            assertEquals(listOf("first", "second"), calls.map { it.toolName })
            assertEquals(listOf("{\"a\":1}", "{\"b\":2}"), calls.map { it.input })
            assertEquals(firstIdentity, calls.first().localCallId)
            assertEquals(2, calls.map { it.localCallId }.distinct().size)
            assertEquals(listOf(providerId, providerId), calls.map { it.providerCallId })
        }
    }

    @Test
    fun `complete response gives every call an independent array slot`() {
        val calls = listOf("same", "same", "", "").map { providerId ->
            UIMessagePart.Tool(localCallId = Uuid.NIL, stepId = Uuid.NIL, providerCallId = providerId,
                toolName = "lookup", input = "{}")
        }
        val chunk = MessageChunk(id = "full", model = "test", choices = listOf(UIMessageChoice(
            index = 0, delta = null, message = UIMessage(role = MessageRole.ASSISTANT, parts = calls), finishReason = "tool_calls",
        )))
        val active = StepOutputAccumulator().accumulate(newAssistant(), chunk, null)
        assertEquals(4, active.getTools().map { it.localCallId }.distinct().size)
        assertEquals(calls.map { it.providerCallId }, active.getTools().map { it.providerCallId })
        assertFalse(kotlinx.serialization.json.Json.encodeToString(UIMessage.serializer(), active).contains("toolCallSlots"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `streaming Tool without transport identity fails before merging`() {
        StepOutputAccumulator().accumulate(newAssistant(), assistantChunk(
            UIMessagePart.Tool(localCallId = Uuid.NIL, stepId = Uuid.NIL, providerCallId = "known", toolName = "lookup", input = "{}"),
            slots = emptyList(),
        ), null)
    }

    @Test
    fun `chunks preserve the preopened Step`() {
        val acc = StepOutputAccumulator()
        var messages = newAssistant()
        messages = acc.accumulate(messages, assistantChunk(UIMessagePart.Text("hello")), null)
        messages = acc.accumulate(messages, assistantChunk(UIMessagePart.Text(" world")), null)
        val parts = messages.parts
        assertEquals(1, parts.count { it is UIMessagePart.Step })
        assertEquals("hello world", content(parts).filterIsInstance<UIMessagePart.Text>().single().text)
    }

    @Test
    fun `out of order tool content and reasoning deltas are normalized within the step`() {
        val acc = StepOutputAccumulator()
        var messages = newAssistant()
        messages = acc.accumulate(
            messages,
            assistantChunk(UIMessagePart.Tool(localCallId = Uuid.NIL, stepId = Uuid.NIL, providerCallId = "call-1", toolName = "lookup", input = "{}")),
            null,
        )
        messages = acc.accumulate(messages, assistantChunk(UIMessagePart.Text("Calling lookup")), null)
        messages = acc.accumulate(messages, assistantChunk(UIMessagePart.Reasoning(reasoning = "Need lookup")), null)

        val parts = content(messages.parts)
        assertEquals(3, parts.size)
        assertTrue(parts[0] is UIMessagePart.Reasoning)
        assertTrue(parts[1] is UIMessagePart.Text)
        assertTrue(parts[2] is UIMessagePart.Tool)
        assertEquals("Need lookup", (parts[0] as UIMessagePart.Reasoning).reasoning)
        assertEquals("Calling lookup", (parts[1] as UIMessagePart.Text).text)
    }

    @Test
    fun `a new tool is stamped with the current step id and a fresh random local call id`() {
        val acc = StepOutputAccumulator()
        var messages = newAssistant()
        messages = acc.accumulate(
            messages,
            assistantChunk(UIMessagePart.Tool(localCallId = Uuid.NIL, stepId = Uuid.NIL, providerCallId = "call-1", toolName = "lookup", input = "{}")),
            null,
        )
        val tool = messages.parts.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals(messages.parts.filterIsInstance<UIMessagePart.Step>().single().stepId, tool.stepId)
        assertEquals("call-1", tool.providerCallId)
        assertTrue(tool.localCallId != Uuid.NIL)
    }

    @Test
    fun `completed tool history from a prior step is not reopened`() {
        val acc = StepOutputAccumulator()
        var messages = newAssistant()
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
        // The result checkpoint has already closed the first Step and opened the next.
        messages = messages.copy(parts = messages.parts.map {
            if (it is UIMessagePart.Step) it.copy(outcome = me.rerere.ai.ui.StepOutcome.Continue) else it
        } + net.weero.measix.pilot.service.runtime.TurnTransition.openStep(1))
        messages = acc.accumulate(
            messages,
            assistantChunk(UIMessagePart.Tool(localCallId = Uuid.NIL, stepId = Uuid.NIL, providerCallId = "call-2", toolName = "second", input = "{}")),
            null,
        )
        messages = acc.accumulate(messages, assistantChunk(UIMessagePart.Text("Second content")), null)

        val parts = messages.parts
        assertEquals(2, parts.count { it is UIMessagePart.Step })
        val tools = parts.filterIsInstance<UIMessagePart.Tool>()
        assertEquals("call-1", tools[0].providerCallId)
        assertEquals("call-2", tools[1].providerCallId)
        // The two tools live in different steps.
        assertTrue(tools[0].stepId != tools[1].stepId)
    }

    @Test
    fun `blank provider ID delta continues its explicit transport slot`() {
        val acc = StepOutputAccumulator()
        var messages = newAssistant()
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
        val tools = messages.parts.filterIsInstance<UIMessagePart.Tool>()
        assertEquals(1, tools.size)
        assertEquals("first", tools[0].toolName)
        assertEquals("{\"a\":1}", tools[0].input)
    }

    @Test
    fun `complete image urls are not prefixed or concatenated`() {
        val urls = listOf(
            "https://example.test/image.png",
            "content://media/external/images/media/42",
            "android.resource://net.weero.measix.pilot/drawable/image",
            "data:image/jpeg;base64,AAA",
            "data:image/png;base64,BBB",
        )
        val acc = StepOutputAccumulator()
        var active = newAssistant()
        urls.forEach { url -> active = acc.accumulate(active, assistantChunk(UIMessagePart.Image(url)), null) }
        assertEquals(urls, active.parts.filterIsInstance<UIMessagePart.Image>().map { it.url })
    }

    @Test
    fun `raw image fragments append to the current data uri`() {
        val acc = StepOutputAccumulator()
        var messages = newAssistant()
        messages = acc.accumulate(messages, assistantChunk(UIMessagePart.Image(url = "AAA")), null)
        messages = acc.accumulate(messages, assistantChunk(UIMessagePart.Image(url = "BBB")), null)
        val images = messages.parts.filterIsInstance<UIMessagePart.Image>()
        assertEquals(1, images.size)
        assertEquals("data:image/png;base64,AAABBB", images[0].url)
    }

    @Test
    fun `openrouter reasoning details accumulate across chunks`() {
        val acc = StepOutputAccumulator()
        var messages = newAssistant()
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
        val reasoning = messages.parts.filterIsInstance<UIMessagePart.Reasoning>().single()
        assertEquals("hidden plan", reasoning.reasoning)
        assertNotNull(reasoning.metadataAs<OpenRouterReasoningMetadata>()?.reasoningDetails)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a closed Step cannot accept another provider chunk`() {
        val message = newAssistant().let { it.copy(parts = it.parts.map { part ->
            (part as UIMessagePart.Step).copy(outcome = me.rerere.ai.ui.StepOutcome.Final)
        }) }
        StepOutputAccumulator().accumulate(message, assistantChunk(UIMessagePart.Text("late")), null)
    }

    @Test
    fun `signed empty text part remains a separate streaming boundary`() {
        val acc = StepOutputAccumulator()
        var messages = newAssistant()
        messages = acc.accumulate(messages, assistantChunk(UIMessagePart.Text("answer")), null)
        messages = acc.accumulate(
            messages,
            assistantChunk(UIMessagePart.Text(text = "", metadata = GoogleThoughtMetadata(thoughtSignature = "signature").toMetadata())),
            null,
        )
        val texts = content(messages.parts).filterIsInstance<UIMessagePart.Text>()
        assertEquals(2, texts.size)
        assertEquals("answer", texts[0].text)
        assertEquals("signature", texts[1].metadataAs<GoogleThoughtMetadata>()?.thoughtSignature)
    }

    @Test
    fun `redacted claude reasoning remains separate from visible thinking`() {
        val acc = StepOutputAccumulator()
        var messages = newAssistant()
        messages = acc.accumulate(messages, assistantChunk(UIMessagePart.Reasoning("visible thinking")), null)
        messages = acc.accumulate(
            messages,
            assistantChunk(UIMessagePart.Reasoning(reasoning = "", metadata = ClaudeReasoningMetadata(redactedData = "opaque").toMetadata())),
            null,
        )
        val reasonings = content(messages.parts).filterIsInstance<UIMessagePart.Reasoning>()
        assertEquals(2, reasonings.size)
        assertEquals("opaque", reasonings.last().metadataAs<ClaudeReasoningMetadata>()?.redactedData)
    }

    @Test
    fun `reasoning text delta and done id merge into one part`() {
        val acc = StepOutputAccumulator()
        var messages = newAssistant()
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
        val reasoning = content(messages.parts).filterIsInstance<UIMessagePart.Reasoning>().single()
        assertEquals("Streaming thinking", reasoning.reasoning)
        assertEquals("rs_stream", reasoning.metadataAs<OpenAIReasoningMetadata>()?.reasoningId)
    }

    @Test
    fun `tool argument fragments concatenate by transport slot`() {
        val acc = StepOutputAccumulator()
        var messages = newAssistant()
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
        val tool = content(messages.parts).filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals("call_1", tool.providerCallId)
        assertEquals("lookup", tool.toolName)
        assertEquals("{\"query\":\"test\"}", tool.input)
    }

    @Test
    fun `sampling preserves the START-preopened Step identity`() {
        val assistantMessageId = Uuid.random()
        val preopenedStepId = Uuid.random()
        val started = UIMessage(
            id = assistantMessageId,
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Step(stepId = preopenedStepId, ordinal = 0, startedAt = kotlin.time.Instant.DISTANT_PAST),
            ),
        )
        val acc = StepOutputAccumulator()
        val messages = acc.accumulate(
            started,
            assistantChunk(UIMessagePart.Text("first")),
            null,
        )
        val steps = messages.parts.filterIsInstance<UIMessagePart.Step>()
        // START 已落 Step(0)：首个采样复用同一 stepId，绝不重复建 Step。
        assertEquals(1, steps.size)
        assertEquals(0, steps.single().ordinal)
        assertEquals(preopenedStepId, steps.single().stepId)
    }

    private fun reasoningWithDetails(text: String, details: kotlinx.serialization.json.JsonArray): UIMessagePart.Reasoning =
        UIMessagePart.Reasoning(reasoning = text, metadata = OpenRouterReasoningMetadata(reasoningDetails = details).toMetadata())
}
