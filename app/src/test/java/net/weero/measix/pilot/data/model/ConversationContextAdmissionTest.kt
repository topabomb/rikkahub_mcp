package net.weero.measix.pilot.data.model

import me.rerere.ai.core.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationContextAdmissionTest {
    @Test fun `request identity scopes step by both node and message variant`() {
        val owner = ContextMessageLocator(Uuid.random(), Uuid.random())
        val step = Uuid.random()
        val empty = ConversationContextAdmission(owner, step, owner, null, emptyList())
        assertEquals(empty.id, ConversationContextAdmission(owner, step, owner, null, emptyList()).id)
        assertNotEquals(empty.id, contextAdmissionIdentity(owner.copy(nodeId = Uuid.random()), step))
        assertNotEquals(empty.id, contextAdmissionIdentity(owner.copy(messageId = Uuid.random()), step))
        assertNotEquals(empty.id, contextAdmissionIdentity(owner, Uuid.random()))
        val use = ConversationContextUse(Uuid.random(), MessageRole.USER, ContextPlacement.BeforeStep(step))
        assertEquals(empty.id, ConversationContextAdmission(owner, step, owner, null, listOf(use)).id)
    }

    @Test fun `entry occurrence identifies contribution independently of body and clone node identity`() {
        val node = Uuid.random()
        val message = Uuid.random()
        val payload = ConversationContextPayload(source = ConversationContextSource.Disclosure(null),
            body = ConversationContextBody.Inline("historical bytes"))
        val first = ConversationModelContextEntry(node, message, Uuid.random(), Uuid.random(), payload)
        val changedBody = ConversationModelContextEntry(node, message, first.anchorNodeId, first.anchorMessageId,
            payload.copy(body = ConversationContextBody.Inline("different bytes")))
        assertEquals(first.id, changedBody.id)
        assertNotEquals(first.id, contextEntryIdentity(node, message, 1))
        assertNotEquals(first.id, contextEntryIdentity(Uuid.random(), message, 0))
        assertThrows(IllegalArgumentException::class.java) { first.copy(occurrence = -1) }
    }
}
