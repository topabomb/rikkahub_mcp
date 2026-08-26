package net.weero.measix.pilot.ui.pages.chat

import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.service.runtime.ConversationHeader
import net.weero.measix.pilot.service.runtime.ConversationSnapshot
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
        val context = AppendScrollContext.from(before)

        assertTrue(context.matches(before.copy(nodes = before.nodes + MessageNode.of(UIMessage.user("new")))))
        assertFalse(context.matches(before.copy(conversationId = Uuid.random())))
        assertFalse(context.matches(before.copy(nodes = listOf(first, second.copy(selectIndex = 1)))))
        assertFalse(context.matches(before.copy(nodes = listOf(first))))
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
