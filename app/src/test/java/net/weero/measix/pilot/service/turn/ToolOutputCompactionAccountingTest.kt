package net.weero.measix.pilot.service.turn

import me.rerere.ai.core.CURRENT_TOKEN_USAGE_SEMANTICS_VERSION
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.core.UsageCompleteness
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid
import me.rerere.ai.ui.ToolOutputArchive
import me.rerere.ai.ui.ToolOutputArchiveRef
import net.weero.measix.pilot.data.ai.tools.ToolOutputStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ToolOutputCompactionAccountingTest {
    @Test
    fun `one non-empty archive batch increments the owning turn exactly once`() {
        val message = assistantWithTwoTools()
        val replacements = (0..1).associate { ordinal ->
            val tool = message.getTools()[ordinal]
            ToolCallLocator(message.id, tool.stepId, tool.localCallId) to ToolOutputStore.CompactionReplacement(
                marker = UIMessagePart.Text("archived-$ordinal"),
                archive = ToolOutputArchive(
                    ref = (ordinal + 1).toLong(),
                    artifact = ToolOutputArchiveRef("tool_outputs/$ordinal.txt", "text/plain"),
                    characters = 10,
                    lines = 1,
                ),
            )
        }

        val checkpoint = applyToolOutputCompactionBatchToCheckpoint(listOf(message), replacements).single()

        assertEquals(1, checkpoint.usage?.successfulToolOutputCompactionBatchCount)
        assertEquals(listOf("archived-0", "archived-1"), checkpoint.getTools().map {
            (it.output.single() as UIMessagePart.Text).text
        })
    }

    @Test
    fun `empty archive plan neither copies messages nor increments the counter`() {
        val messages = listOf(assistantWithTwoTools())
        assertSame(messages, applyToolOutputCompactionBatchToCheckpoint(messages, emptyMap()))
    }

    private fun assistantWithTwoTools() = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Tool(localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "a", toolName = "tool", input = "{}", output = listOf(UIMessagePart.Text("first"))),
            UIMessagePart.Tool(localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "b", toolName = "tool", input = "{}", output = listOf(UIMessagePart.Text("second"))),
        ),
        usage = TokenUsage(
            observedProviderRequestCount = 1,
            observedUsageReportedRequestCount = 1,
            providerRequestDurationMillis = 1,
            inputCompleteness = UsageCompleteness.COMPLETE,
            coreCompleteness = UsageCompleteness.COMPLETE,
            cacheReadCompleteness = UsageCompleteness.COMPLETE,
            semanticsVersion = CURRENT_TOKEN_USAGE_SEMANTICS_VERSION,
        ),
    )
}
