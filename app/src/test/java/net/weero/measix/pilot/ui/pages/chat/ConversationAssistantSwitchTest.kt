package net.weero.measix.pilot.ui.pages.chat

import net.weero.measix.pilot.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationAssistantSwitchTest {
    @Test
    fun `switching assistant clears the assistant scoped folder`() {
        val targetAssistantId = Uuid.random()
        val conversation = Conversation(
            assistantId = Uuid.random(),
            folderId = Uuid.random(),
            messageNodes = emptyList(),
        )

        val updated = conversation.withAssistant(targetAssistantId)

        assertEquals(targetAssistantId, updated.assistantId)
        assertEquals(null, updated.folderId)
    }

    @Test
    fun `selecting the current assistant is a no-op`() {
        val assistantId = Uuid.random()
        val conversation = Conversation(
            assistantId = assistantId,
            folderId = Uuid.random(),
            messageNodes = emptyList(),
        )

        val updated = conversation.withAssistant(assistantId)

        assertSame(conversation, updated)
        assertEquals(conversation.folderId, updated.folderId)
    }
}
