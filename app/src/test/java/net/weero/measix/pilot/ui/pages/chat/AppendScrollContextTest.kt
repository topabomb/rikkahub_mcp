package net.weero.measix.pilot.ui.pages.chat

import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.service.runtime.ConversationHeader
import net.weero.measix.pilot.service.runtime.ConversationSnapshot
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
        val context = AppendScrollContext.from(before, targetMessage.id)
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
        val context = AppendScrollContext.from(before, Uuid.random())

        assertEquals(
            AppendScrollStatus.WAITING_FOR_APPEND,
            evaluateAppendScroll(
                requestContext = context,
                snapshot = before,
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
        val context = AppendScrollContext.from(before, targetMessage.id)
        val appended = before.copy(nodes = listOf(MessageNode.of(targetMessage)))

        assertEquals(
            AppendScrollStatus.READY,
            evaluateAppendScroll(
                requestContext = context,
                snapshot = appended,
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
        val context = AppendScrollContext.from(before, targetMessage.id)
        val appended = before.copy(nodes = before.nodes + MessageNode.of(targetMessage))

        assertEquals(
            AppendScrollStatus.WAITING_FOR_LAYOUT,
            evaluateAppendScroll(context, appended, actualItemCount = 3, expectedItemCount = 4, imeBottom = 0),
        )
        assertEquals(
            AppendScrollStatus.WAITING_FOR_IME,
            evaluateAppendScroll(context, appended, actualItemCount = 4, expectedItemCount = 4, imeBottom = 120),
        )
        assertEquals(
            AppendScrollStatus.READY,
            evaluateAppendScroll(context, appended, actualItemCount = 4, expectedItemCount = 4, imeBottom = 0),
        )
    }

    @Test
    fun `branch change invalidates pending request`() {
        val first = MessageNode.of(UIMessage.user("first"))
        val second = MessageNode.of(UIMessage.assistant("second"))
        val before = snapshot(nodes = listOf(first, second))
        val targetMessage = UIMessage.user("new")
        val context = AppendScrollContext.from(before, targetMessage.id)
        val changedBranch = before.copy(
            nodes = listOf(first, second.copy(selectIndex = 1), MessageNode.of(targetMessage)),
        )

        assertEquals(
            AppendScrollStatus.INVALIDATED,
            evaluateAppendScroll(context, changedBranch, actualItemCount = 4, expectedItemCount = 4, imeBottom = 0),
        )
    }

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
