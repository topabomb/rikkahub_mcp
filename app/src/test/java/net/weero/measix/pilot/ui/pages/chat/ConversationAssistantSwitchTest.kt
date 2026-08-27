package net.weero.measix.pilot.ui.pages.chat

import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.getConversationAssistant
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.service.runtime.ConversationTransition
import net.weero.measix.pilot.service.runtime.MoveToAssistant
import net.weero.measix.pilot.service.runtime.toSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationAssistantSwitchTest {
    @Test
    fun `conversation assistant wins over the global current assistant`() {
        val globalAssistant = Assistant(name = "Global")
        val conversationAssistant = Assistant(name = "Conversation")
        val settings = Settings(
            assistantId = globalAssistant.id,
            assistants = listOf(globalAssistant, conversationAssistant),
        )

        val resolved = settings.getConversationAssistant(conversationAssistant.id)

        assertSame(conversationAssistant, resolved)
    }

    @Test
    fun `deleted conversation assistant falls back to the global current assistant`() {
        val globalAssistant = Assistant(name = "Global")
        val settings = Settings(
            assistantId = globalAssistant.id,
            assistants = listOf(globalAssistant),
        )

        val resolved = settings.getConversationAssistant(Uuid.random())

        assertSame(globalAssistant, resolved)
    }

    @Test
    fun `switching assistant clears the assistant scoped folder`() {
        val targetAssistantId = Uuid.random()
        val conversation = Conversation(
            assistantId = Uuid.random(),
            folderId = Uuid.random(),
            messageNodes = emptyList(),
        )

        val updated = ConversationTransition.apply(
            conversation.toSnapshot(),
            MoveToAssistant(targetAssistantId),
        )

        assertEquals(targetAssistantId, updated.header.assistantId)
        assertEquals(null, updated.header.folderId)
    }

    @Test
    fun `selecting the current assistant preserves its folder`() {
        val assistantId = Uuid.random()
        val conversation = Conversation(
            assistantId = assistantId,
            folderId = Uuid.random(),
            messageNodes = emptyList(),
        )

        val updated = ConversationTransition.apply(
            conversation.toSnapshot(),
            MoveToAssistant(assistantId),
        )

        assertEquals(assistantId, updated.header.assistantId)
        assertEquals(conversation.folderId, updated.header.folderId)
    }
}
