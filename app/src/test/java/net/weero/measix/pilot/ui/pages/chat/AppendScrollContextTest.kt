package net.weero.measix.pilot.ui.pages.chat

import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.service.runtime.ConversationHeader
import net.weero.measix.pilot.service.runtime.ConversationPresentation
import net.weero.measix.pilot.service.runtime.ConversationSnapshot
import net.weero.measix.pilot.service.runtime.ConversationTurnPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class AppendScrollContextTest {
    @Test
    fun `request remains valid only for an append on the same selected branch`() {
        val first = MessageNode.of(UIMessage.user("first"))
        val second = MessageNode.of(UIMessage.assistant("second"))
        val before = snapshot(nodes = listOf(first, second))
        val targetMessage = UIMessage.user("new")
        val turnId = Uuid.random()
        val context = AppendScrollContext.from(before, targetMessage.id, turnId)
        val appended = before.copy(nodes = before.nodes + MessageNode.of(targetMessage))

        assertFalse(context.hasTargetMessage(before))
        assertTrue(context.matches(appended))
        assertTrue(context.hasTargetMessage(appended))
        assertFalse(context.matches(before.copy(conversationId = Uuid.random())))
        assertFalse(context.matches(before.copy(nodes = listOf(first, second.copy(selectIndex = 1)))))
        assertFalse(context.matches(before.copy(nodes = listOf(first))))
    }

    @Test
    fun `loading item growth does not complete request before durable append`() {
        val before = snapshot(nodes = listOf(MessageNode.of(UIMessage.user("first"))))
        val turnId = Uuid.random()
        val context = AppendScrollContext.from(before, Uuid.random(), turnId)

        assertEquals(
            AppendScrollStatus.WAITING_FOR_APPEND,
            evaluateAppendScroll(
                requestContext = context,
                snapshot = before,
                presentation = preparing(turnId),
                actualItemCount = 4,
                expectedItemCount = 4,
                imeBottom = 0,
            ),
        )
    }

    @Test
    fun `draft append can become ready even when total item count stays unchanged`() {
        val before = snapshot(nodes = emptyList())
        val targetMessage = UIMessage.user("first")
        val turnId = Uuid.random()
        val context = AppendScrollContext.from(before, targetMessage.id, turnId)
        val appended = before.copy(nodes = listOf(MessageNode.of(targetMessage)))

        assertEquals(
            AppendScrollStatus.READY,
            evaluateAppendScroll(
                requestContext = context,
                snapshot = appended,
                presentation = preparing(turnId),
                actualItemCount = 3,
                expectedItemCount = 3,
                imeBottom = 0,
            ),
        )
    }

    @Test
    fun `request waits for matching layout and completed IME animation`() {
        val before = snapshot(nodes = listOf(MessageNode.of(UIMessage.user("first"))))
        val targetMessage = UIMessage.user("new")
        val turnId = Uuid.random()
        val context = AppendScrollContext.from(before, targetMessage.id, turnId)
        val appended = before.copy(nodes = before.nodes + MessageNode.of(targetMessage))

        assertEquals(
            AppendScrollStatus.WAITING_FOR_LAYOUT,
            evaluateAppendScroll(context, appended, preparing(turnId), 3, 4, 0),
        )
        assertEquals(
            AppendScrollStatus.WAITING_FOR_IME,
            evaluateAppendScroll(context, appended, preparing(turnId), 4, 4, 120),
        )
        assertEquals(
            AppendScrollStatus.READY,
            evaluateAppendScroll(context, appended, preparing(turnId), 4, 4, 0),
        )
    }

    @Test
    fun `branch change invalidates pending request`() {
        val first = MessageNode.of(UIMessage.user("first"))
        val second = MessageNode.of(UIMessage.assistant("second"))
        val before = snapshot(nodes = listOf(first, second))
        val targetMessage = UIMessage.user("new")
        val turnId = Uuid.random()
        val context = AppendScrollContext.from(before, targetMessage.id, turnId)
        val changedBranch = before.copy(
            nodes = listOf(first, second.copy(selectIndex = 1), MessageNode.of(targetMessage)),
        )

        assertEquals(
            AppendScrollStatus.INVALIDATED,
            evaluateAppendScroll(context, changedBranch, preparing(turnId), 4, 4, 0),
        )
    }

    @Test
    fun `released active request without the target message invalidates the wait`() {
        val before = snapshot(nodes = listOf(MessageNode.of(UIMessage.user("first"))))
        val turnId = Uuid.random()
        val context = AppendScrollContext.from(before, Uuid.random(), turnId)

        assertEquals(
            AppendScrollStatus.INVALIDATED,
            evaluateAppendScroll(
                requestContext = context,
                snapshot = before,
                presentation = ConversationPresentation.IDLE,
                activeRequestObserved = true,
                actualItemCount = 4,
                expectedItemCount = 4,
                imeBottom = 0,
            ),
        )
    }

    @Test
    fun `stale idle presentation waits until append or owner is observed`() {
        val before = snapshot(nodes = listOf(MessageNode.of(UIMessage.user("first"))))
        val turnId = Uuid.random()
        val context = AppendScrollContext.from(before, Uuid.random(), turnId)

        assertEquals(
            AppendScrollStatus.WAITING_FOR_APPEND,
            evaluateAppendScroll(
                requestContext = context,
                snapshot = before,
                presentation = ConversationPresentation.IDLE,
                actualItemCount = 4,
                expectedItemCount = 4,
                imeBottom = 0,
            ),
        )
    }

    private fun preparing(turnId: Uuid) = ConversationPresentation(
        activeRequestTurnId = turnId,
        phase = ConversationTurnPhase.PREPARING,
        processingText = null,
        toolCallPhases = emptyMap(),
    )

    private fun snapshot(nodes: List<MessageNode>): ConversationSnapshot {
        val conversationId = Uuid.random()
        return ConversationSnapshot(
            conversationId = conversationId,
            header = ConversationHeader(
                id = conversationId,
                title = "Chat",
                assistantId = Uuid.random(),
                folderId = null,
                isPinned = false,
                chatSuggestions = emptyList(),
                customSystemPrompt = null,
                modeInjectionIds = emptySet(),
                workspaceCwd = null,
                parentConversationId = null,
                newConversation = false,
                createAt = 1,
                updateAt = 1,
            ),
            nodes = nodes,
            activeTurn = null,
        )
    }
}
