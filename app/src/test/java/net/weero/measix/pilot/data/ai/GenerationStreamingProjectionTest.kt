package net.weero.measix.pilot.data.ai

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.transformers.RegexOutputTransformer
import net.weero.measix.pilot.data.ai.transformers.RequestMessageOriginTracker
import net.weero.measix.pilot.data.ai.transformers.ThinkTagTransformer
import net.weero.measix.pilot.data.ai.transformers.TransformerContext
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.AssistantAffectScope
import net.weero.measix.pilot.data.model.AssistantRegex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.time.Instant
import kotlin.uuid.Uuid

class GenerationStreamingProjectionTest {
    @Test
    fun `terminal pipeline preserves think close time without applying regex twice`() = runTest {
        val raw = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("<think>reasoning</think>a")),
        )
        val capturedAt = Instant.fromEpochMilliseconds(1_234)
        val previousProjection = raw.copy(
            parts = listOf(
                UIMessagePart.Reasoning(
                    reasoning = "reasoning",
                    createdAt = Instant.DISTANT_PAST,
                    finishedAt = capturedAt,
                ),
                UIMessagePart.Text("aa"),
            ),
        )
        val assistant = Assistant(
            regexes = listOf(
                AssistantRegex(
                    id = Uuid.random(),
                    findRegex = "a",
                    replaceString = "aa",
                    affectingScope = setOf(AssistantAffectScope.ASSISTANT),
                ),
            ),
        )

        val result = finishStreamingProjection(
            raw = raw,
            previousProjection = previousProjection,
            ctx = transformerContext(assistant),
            transformers = listOf(ThinkTagTransformer, RegexOutputTransformer),
        )

        assertEquals(
            capturedAt,
            result.parts.filterIsInstance<UIMessagePart.Reasoning>().single().finishedAt,
        )
        assertEquals("aa", result.parts.filterIsInstance<UIMessagePart.Text>().single().text)
    }

    @Test
    fun `terminal pipeline closes an unclosed think projection`() = runTest {
        val raw = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("<think>reasoning")),
        )
        val previousProjection = raw.copy(
            parts = listOf(
                UIMessagePart.Reasoning(
                    reasoning = "reasoning",
                    createdAt = Instant.DISTANT_PAST,
                    finishedAt = null,
                ),
            ),
        )

        val result = finishStreamingProjection(
            raw = raw,
            previousProjection = previousProjection,
            ctx = transformerContext(Assistant()),
            transformers = listOf(ThinkTagTransformer),
        )

        assertNotNull(result.parts.filterIsInstance<UIMessagePart.Reasoning>().single().finishedAt)
    }

    private fun transformerContext(assistant: Assistant) = TransformerContext(
        context = mockk<Context>(relaxed = true),
        model = Model(modelId = "test", displayName = "Test"),
        assistant = assistant,
        settings = Settings(),
        requestOrigins = RequestMessageOriginTracker(),
        registerUnpublishedResource = {},
    )
}
