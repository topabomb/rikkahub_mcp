package net.weero.measix.pilot.data.model

import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.service.runtime.ConversationTransition
import net.weero.measix.pilot.service.runtime.ReplaceMessageTree
import net.weero.measix.pilot.service.runtime.toSnapshot
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationMessageOriginTest {
    @Test fun `summary commits its source with the replacement tree and keeps ordinary content`() {
        val assistant = Assistant()
        val original = Conversation.ofId(Uuid.random(), assistant.id)
            .copy(messageNodes = listOf(MessageNode.of(UIMessage.user("old request")))).toSnapshot()
        val node = MessageNode.of(UIMessage.user("Summary with literal {{name}}"))
        val entry = messageOriginEntry(node, ConversationContextSource.HistorySummary(null, "Summarize", "Focus on units", 100))
        val result = ConversationTransition.apply(original, ReplaceMessageTree(listOf(node), messageOrigins = listOf(entry)))
        assertEquals("Summary with literal {{name}}", result.currentMessages().single().toText())
        assertEquals(listOf(entry), result.modelContextEntries)
        assertTrue(entry.payload.body is ConversationContextBody.MessageReference)
        ConversationContextIntegrity.validate(result.nodes, result.modelContextEntries, emptyList(), null, ConfigurationScope.Personal)
    }

    @Test fun `fork remaps immutable message body together with its origin owner`() {
        val assistant = Assistant()
        val source = MessageNode.of(UIMessage.user("preset"))
        val entry = messageOriginEntry(source, ConversationContextSource.Preset(assistant.id, 0))
        val newMessage = source.currentMessage.copy(id = Uuid.random())
        val copied = MessageNode.of(newMessage)
        val mapped = ConversationModelContextApplicability.remapForClone(listOf(entry),
            mapOf(source.id to copied.id), mapOf(source.currentMessage.id to newMessage.id), listOf(copied)).single()
        assertEquals(ConversationContextBody.MessageReference(ContextMessageLocator(copied.id, newMessage.id)), mapped.payload.body)
        assertEquals(entry.payload.source, mapped.payload.source)
        ConversationContextIntegrity.validate(listOf(copied), listOf(mapped), emptyList(), null, ConfigurationScope.Personal)
    }

    @Test fun `an origin cannot borrow another message body`() {
        val first = MessageNode.of(UIMessage.user("first"))
        val second = MessageNode.of(UIMessage.user("second"))
        val entry = messageOriginEntry(first, ConversationContextSource.HistorySummary(null, "summary"))
        val invalid = entry.copy(payload = entry.payload.copy(body = ConversationContextBody.MessageReference(
            ContextMessageLocator(second.id, second.currentMessage.id))))
        val error = assertThrows(IllegalArgumentException::class.java) {
            ConversationContextIntegrity.validate(listOf(first, second), listOf(invalid), emptyList(), null, ConfigurationScope.Personal)
        }
        assertEquals("context_message_reference_owner_mismatch", error.message)
    }
}
