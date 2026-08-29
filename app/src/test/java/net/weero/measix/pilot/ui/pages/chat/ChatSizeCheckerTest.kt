package net.weero.measix.pilot.ui.pages.chat

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSizeCheckerTest {
    @Test
    fun `size check uses latest request context instead of accumulated input`() {
        val assistantNode = node(
            role = MessageRole.ASSISTANT,
            usage = TokenUsage(
                inputTokens = 900_000,
                latestRequestContextTokens = 120_000,
            ),
        )

        val info = calculateConversationSizeInfo(listOf(assistantNode))

        assertEquals(120_000L, info.lastAssistantInputTokens)
        assertFalse(info.exceedInputTokenThreshold)
    }

    @Test
    fun `warning requires both node and latest request context thresholds`() {
        val userNode = node(MessageRole.USER)
        val assistantNode = node(
            role = MessageRole.ASSISTANT,
            usage = TokenUsage(latestRequestContextTokens = 300_001),
        )

        val info = calculateConversationSizeInfo(List(768) { userNode } + assistantNode)

        assertTrue(info.exceedNodeCountThreshold)
        assertTrue(info.exceedInputTokenThreshold)
        assertTrue(info.showWarning)
    }

    private fun node(role: MessageRole, usage: TokenUsage? = null) = MessageNode.of(
        UIMessage(role = role, parts = emptyList(), usage = usage)
    )
}
