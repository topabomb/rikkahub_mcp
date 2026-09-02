package net.weero.measix.pilot.data.ai.transformers

import net.weero.measix.pilot.service.runtime.resolveAssistantRequest

import net.weero.measix.pilot.test.testPromptInputs

import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

class ThinkTagTransformerTest {

    private val ctx = TransformerContext(
        context = mockk<android.content.Context>(relaxed = true),
        model = Model(modelId = "test", displayName = "Test"),
        assistant = resolveAssistantRequest(Assistant()),
        promptInputs = testPromptInputs(),
        requestOrigins = RequestMessageOriginTracker(),
        registerUnpublishedResource = {},
    )

    @Test
    fun `think tag at start extracts reasoning and strips tag`() = runTest {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("<think>thinking here</think>actual response")),
        )

        val result = ThinkTagTransformer.transformStreaming(ctx, message)

        val reasoning = result.parts.filterIsInstance<UIMessagePart.Reasoning>().single()
        assertEquals("thinking here", reasoning.reasoning)
        assertNotNull(reasoning.finishedAt)

        val text = result.parts.filterIsInstance<UIMessagePart.Text>().single()
        assertEquals("actual response", text.text)
    }

    @Test
    fun `leading whitespace before think tag is allowed`() = runTest {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("\n  \t  <think>reasoning body</think>actual text")),
        )

        val result = ThinkTagTransformer.transformStreaming(ctx, message)

        val reasoning = result.parts.filterIsInstance<UIMessagePart.Reasoning>().single()
        assertEquals("reasoning body", reasoning.reasoning)
        assertEquals("actual text", result.parts.filterIsInstance<UIMessagePart.Text>().single().text)
    }

    @Test
    fun `literal think tag in body middle is not mistaken for reasoning`() = runTest {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("Normal text with <think>thinking in middle</think>actual")),
        )

        val result = ThinkTagTransformer.transformStreaming(ctx, message)

        assertFalse(result.hasPart<UIMessagePart.Reasoning>())
        assertEquals(message, result)
    }

    @Test
    fun `literal think tag in second Text part is not processed`() = runTest {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("first part"),
                UIMessagePart.Text("<think>second part thinking</think>actual"),
            ),
        )

        val result = ThinkTagTransformer.transformStreaming(ctx, message)

        assertFalse(result.hasPart<UIMessagePart.Reasoning>())
        assertEquals(message, result)
    }

    @Test
    fun `native reasoning suppresses tag fallback`() = runTest {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(
                    reasoning = "native reasoning",
                    createdAt = Instant.DISTANT_PAST,
                    finishedAt = Instant.DISTANT_PAST,
                ),
                UIMessagePart.Text("<think>tag fallback</think>actual response"),
            ),
        )

        val result = ThinkTagTransformer.transformStreaming(ctx, message)

        assertEquals(message, result)
        val reasonings = result.parts.filterIsInstance<UIMessagePart.Reasoning>()
        assertEquals(1, reasonings.size)
        assertEquals("native reasoning", reasonings.single().reasoning)
    }

    @Test
    fun `reasoning from a completed tool step does not suppress current step fallback`() = runTest {
        val completedTool = UIMessagePart.Tool(
            toolCallId = "call-1",
            toolName = "lookup",
            input = "{}",
            output = listOf(UIMessagePart.Text("result")),
        )
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "first step"),
                UIMessagePart.Text("calling tool"),
                completedTool,
                UIMessagePart.Text("<think>second step</think>final answer"),
            ),
        )

        val result = ThinkTagTransformer.transformStreaming(ctx, message)

        assertEquals(listOf("first step", "second step"), result.parts
            .filterIsInstance<UIMessagePart.Reasoning>()
            .map(UIMessagePart.Reasoning::reasoning))
        assertEquals(listOf("calling tool", "final answer"), result.parts
            .filterIsInstance<UIMessagePart.Text>()
            .map(UIMessagePart.Text::text))
        assertEquals(
            ThinkTagTransformer.PhaseContent(hasReasoning = true, hasAnswer = true),
            ThinkTagTransformer.classifyPhase(message),
        )
    }

    @Test
    fun `closed timestamp is reused from the current tool step only`() = runTest {
        val priorClosedAt = Instant.fromEpochMilliseconds(100)
        val currentClosedAt = Instant.fromEpochMilliseconds(200)
        val completedTool = UIMessagePart.Tool(
            toolCallId = "call-1",
            toolName = "lookup",
            input = "{}",
            output = listOf(UIMessagePart.Text("result")),
        )
        val raw = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "first", finishedAt = priorClosedAt),
                completedTool,
                UIMessagePart.Text("<think>second</think>answer grows"),
            ),
        )
        val previousProjection = raw.copy(
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "first", finishedAt = priorClosedAt),
                completedTool,
                UIMessagePart.Reasoning(reasoning = "second", finishedAt = currentClosedAt),
                UIMessagePart.Text("answer"),
            ),
        )

        val result = ThinkTagTransformer.transformStreaming(ctx, raw, previousProjection)

        assertEquals(
            currentClosedAt,
            result.parts.filterIsInstance<UIMessagePart.Reasoning>().last().finishedAt,
        )
    }

    @Test
    fun `unclosed think tag during streaming extracts reasoning with null finishedAt`() = runTest {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("<think>streaming reasoning without closing")),
        )

        val result = ThinkTagTransformer.transformStreaming(ctx, message)

        val reasoning = result.parts.filterIsInstance<UIMessagePart.Reasoning>().single()
        assertEquals("streaming reasoning without closing", reasoning.reasoning)
        assertNull(reasoning.finishedAt)
    }

    @Test
    fun `onStreamingFinish sets finishedAt for unclosed reasoning`() = runTest {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("<think>unclosed reasoning here")),
        )

        val result = ThinkTagTransformer.onStreamingFinish(ctx, message)

        val reasoning = result.parts.filterIsInstance<UIMessagePart.Reasoning>().single()
        assertEquals("unclosed reasoning here", reasoning.reasoning)
        assertNotNull(reasoning.finishedAt)
    }

    @Test
    fun `repeated chunk is idempotent - no duplicate reasoning`() = runTest {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("<think>first</think>actual")),
        )

        val first = ThinkTagTransformer.transformStreaming(ctx, message)
        val second = ThinkTagTransformer.transformStreaming(ctx, first)

        val reasonings = second.parts.filterIsInstance<UIMessagePart.Reasoning>()
        assertEquals(1, reasonings.size)
        assertEquals("first", reasonings.single().reasoning)
        val texts = second.parts.filterIsInstance<UIMessagePart.Text>()
        assertEquals(1, texts.size)
        assertEquals("actual", texts.single().text)
    }

    @Test
    fun `reasoning replaces target text in place without reordering prior parts`() = runTest {
        val image = UIMessagePart.Image(url = "data:image/png;base64,abc")
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                image,
                UIMessagePart.Text("<think>reasoning</think>answer"),
            ),
        )

        val result = ThinkTagTransformer.transformStreaming(ctx, message)

        assertEquals(image, result.parts[0])
        assertTrue(result.parts[1] is UIMessagePart.Reasoning)
        assertEquals("answer", (result.parts[2] as UIMessagePart.Text).text)
    }

    @Test
    fun `non-assistant message is not transformed`() = runTest {
        val message = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("user text")),
        )

        val result = ThinkTagTransformer.transformStreaming(ctx, message)

        assertEquals(message, result)
    }

    @Test
    fun `message without text is not transformed`() = runTest {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Image(url = "data:image/png;base64,abc")),
        )

        val result = ThinkTagTransformer.transformStreaming(ctx, message)

        assertEquals(message, result)
    }

    @Test
    fun `think tag with only whitespace reasoning produces empty reasoning part`() = runTest {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("<think>   </think>actual")),
        )

        val result = ThinkTagTransformer.transformStreaming(ctx, message)

        val reasoning = result.parts.filterIsInstance<UIMessagePart.Reasoning>().single()
        assertEquals("", reasoning.reasoning)
        assertEquals("actual", result.parts.filterIsInstance<UIMessagePart.Text>().single().text)
    }

    @Test
    fun `closed think tag during streaming closes reasoning immediately`() = runTest {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("<think>reasoning here</think>actual text")),
        )

        val result = ThinkTagTransformer.transformStreaming(ctx, message)

        val reasoning = result.parts.filterIsInstance<UIMessagePart.Reasoning>().single()
        assertEquals("reasoning here", reasoning.reasoning)
        assertNotNull(reasoning.finishedAt)
    }

    @Test
    fun `closed think timestamp remains stable across accumulated answer chunks`() = runTest {
        val firstRaw = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("<think>reasoning here</think>answer")),
        )
        val capturedAt = Instant.fromEpochMilliseconds(1_234)
        val firstProjection = ThinkTagTransformer.transformStreaming(ctx, firstRaw).let { projected ->
            projected.copy(
                parts = projected.parts.map { part ->
                    if (part is UIMessagePart.Reasoning) part.copy(finishedAt = capturedAt) else part
                },
            )
        }
        val nextRaw = firstRaw.copy(
            parts = listOf(UIMessagePart.Text("<think>reasoning here</think>answer continues")),
        )

        val nextProjection = ThinkTagTransformer.transformStreaming(ctx, nextRaw, firstProjection)

        assertEquals(
            capturedAt,
            nextProjection.parts.filterIsInstance<UIMessagePart.Reasoning>().single().finishedAt,
        )
        assertEquals(
            "answer continues",
            nextProjection.parts.filterIsInstance<UIMessagePart.Text>().single().text,
        )
    }

    @Test
    fun `phase classification keeps tagged text in reasoning until answer appears`() {
        val reasoningOnly = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("<think>still reasoning")),
        )
        val withAnswer = reasoningOnly.copy(
            parts = listOf(UIMessagePart.Text("<think>done</think>answer")),
        )

        assertEquals(
            ThinkTagTransformer.PhaseContent(hasReasoning = true, hasAnswer = false),
            ThinkTagTransformer.classifyPhase(reasoningOnly),
        )
        assertEquals(
            ThinkTagTransformer.PhaseContent(hasReasoning = true, hasAnswer = true),
            ThinkTagTransformer.classifyPhase(withAnswer),
        )
    }

    @Test
    fun `partial opening tag remains undecided and is hidden until accumulated text resolves it`() = runTest {
        val partial = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("\n<thi")),
        )
        val resolved = partial.copy(parts = listOf(UIMessagePart.Text("\n<thinking is useful")))
        val tagged = partial.copy(parts = listOf(UIMessagePart.Text("\n<think>reason")))

        assertEquals(
            ThinkTagTransformer.PhaseContent(hasReasoning = false, hasAnswer = false, undecided = true),
            ThinkTagTransformer.classifyPhase(partial),
        )
        assertTrue(ThinkTagTransformer.transformStreaming(ctx, partial).parts.none { it is UIMessagePart.Text })
        assertEquals(null, ThinkTagTransformer.classifyPhase(resolved))
        assertEquals("\n<thinking is useful", ThinkTagTransformer.transformStreaming(ctx, resolved).toText())
        assertEquals(
            ThinkTagTransformer.PhaseContent(hasReasoning = true, hasAnswer = false),
            ThinkTagTransformer.classifyPhase(tagged),
        )
        assertEquals(
            "reason",
            ThinkTagTransformer.transformStreaming(ctx, tagged)
                .parts.filterIsInstance<UIMessagePart.Reasoning>().single().reasoning,
        )
    }
}
