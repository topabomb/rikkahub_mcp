package net.weero.measix.pilot.ui.pages.chat

import me.rerere.common.configuration.ConfigurationReference


import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.service.runtime.ConversationTransition
import net.weero.measix.pilot.service.runtime.MoveToAssistant
import net.weero.measix.pilot.service.runtime.toSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationAssistantSwitchTest {
    @Test
    fun `switching assistant clears the assistant scoped folder`() {
        val targetAssistantId = ConfigurationReference.random()
        val conversation = Conversation(
            assistantId = ConfigurationReference.random(),
            folderId = Uuid.random(),
            workspaceCwd = "/workspace/old",
            messageNodes = emptyList(),
        )

        val updated = ConversationTransition.apply(
            conversation.toSnapshot(),
            MoveToAssistant(targetAssistantId),
        )

        assertEquals(targetAssistantId, updated.header.assistantId)
        assertEquals(null, updated.header.folderId)
        assertEquals(null, updated.header.workspaceCwd)
    }

    @Test
    fun `selecting the current assistant preserves its folder`() {
        val assistantId = ConfigurationReference.random()
        val conversation = Conversation(
            assistantId = assistantId,
            folderId = Uuid.random(),
            workspaceCwd = "/workspace/old",
            messageNodes = emptyList(),
        )

        val updated = ConversationTransition.apply(
            conversation.toSnapshot(),
            MoveToAssistant(assistantId),
        )

        assertEquals(assistantId, updated.header.assistantId)
        assertEquals(conversation.folderId, updated.header.folderId)
        assertEquals(conversation.workspaceCwd, updated.header.workspaceCwd)
    }
}
