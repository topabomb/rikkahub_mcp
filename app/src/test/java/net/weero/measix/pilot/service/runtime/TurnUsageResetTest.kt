package net.weero.measix.pilot.service.runtime

import me.rerere.ai.core.CURRENT_TOKEN_USAGE_SEMANTICS_VERSION
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.core.UsageCompleteness
import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.MessageNode
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.uuid.Uuid

class TurnUsageResetTest {
    @Test
    fun `fresh start creates a new assistant slot without inheriting prior turn usage`() {
        val previous = UIMessage.assistant("done").copy(
            usage = TokenUsage(
                inputTokens = 10_000,
                outputTokens = 500,
                peakRequestContextTokens = 10_500,
                observedProviderRequestCount = 2,
                successfulToolOutputCompactionBatchCount = 1,
                inputCompleteness = UsageCompleteness.COMPLETE,
                coreCompleteness = UsageCompleteness.COMPLETE,
                semanticsVersion = CURRENT_TOKEN_USAGE_SEMANTICS_VERSION,
            ),
        )
        val initial = Conversation.ofId(Uuid.random(), Uuid.random()).copy(
            messageNodes = listOf(MessageNode.of(previous), MessageNode.of(UIMessage.user("next"))),
        ).toSnapshot()
        val assistantMessageId = Uuid.random()

        val started = ConversationTransition.apply(
            initial,
            StartTurn(Uuid.random(), assistantMessageId, resume = false, epoch = 1),
        )

        assertNull(started.nodes.last().currentMessage.usage)
        assertNull(started.activeTurn?.messages?.lastOrNull()?.usage)
    }
}
