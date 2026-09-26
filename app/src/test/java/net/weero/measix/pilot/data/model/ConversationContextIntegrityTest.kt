package net.weero.measix.pilot.data.model

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ConversationContextIntegrityTest {
    private val first = UIMessagePart.Step(Uuid.random(), 0, Instant.parse("2026-09-26T10:00:00Z"))
    private val next = first.copy(stepId = Uuid.random(), ordinal = 1)
    private val user = MessageNode.of(UIMessage.user("question"))
    private val assistant = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(first, next))
    private val node = MessageNode.of(assistant)
    private val owner = ContextMessageLocator(node.id, assistant.id)
    private val anchor = ContextMessageLocator(user.id, user.currentMessage.id)
    private val system = ConversationModelContextEntry(node.id, assistant.id, user.id, user.currentMessage.id,
        ConversationContextPayload(source = ConversationContextSource.System(emptyList()), body = ConversationContextBody.Inline("system")),
        stepId = first.stepId)
    private val selection = TurnContextSelection(systemEntryId = system.id, ruleEntryIds = emptyList(), timeReminderEnabled = false, timeZoneId = "UTC")
    private val admission = ConversationContextAdmission(owner, first.stepId, anchor, selection,
        listOf(ConversationContextUse(system.id, MessageRole.SYSTEM, ContextPlacement.System)))
    private fun validate(entries: List<ConversationModelContextEntry> = listOf(system),
        requests: List<ConversationContextAdmission> = listOf(admission), nodes: List<MessageNode> = listOf(user, node)) =
        ConversationContextIntegrity.validate(nodes, entries, requests, null, ConfigurationScope.Personal)

    @Test fun `future entries future locations and sibling variant references fail closed`() {
        assertThrows(IllegalArgumentException::class.java) { validate(listOf(system.copy(stepId = next.stepId))) }
        val sibling = assistant.copy(id = Uuid.random())
        val siblingEntry = system.copy(ownerMessageId = sibling.id, id = contextEntryIdentity(node.id, sibling.id, 0))
        assertThrows(IllegalArgumentException::class.java) {
            validate(listOf(siblingEntry), listOf(admission.copy(selection = selection.copy(systemEntryId = siblingEntry.id), uses = emptyList())),
                listOf(user, node.copy(messages = listOf(assistant, sibling))))
        }
        val future = MessageNode.of(UIMessage.user("future"))
        val futureLocation = ContextMessageLocator(future.id, future.currentMessage.id)
        assertThrows(IllegalArgumentException::class.java) { validate(requests = listOf(admission.copy(windowStart = futureLocation)),
            nodes = listOf(user, node, future)) }
        assertThrows(IllegalArgumentException::class.java) { validate(requests = listOf(admission.copy(uses = listOf(
            ConversationContextUse(system.id, MessageRole.USER, ContextPlacement.BeforeMessage(futureLocation))))),
            nodes = listOf(user, node, future)) }
    }

    @Test fun `first admission requires selection and later nonnull selections cannot change frozen sources`() {
        assertThrows(IllegalArgumentException::class.java) { validate(requests = listOf(admission.copy(selection = null))) }
        val second = ConversationContextAdmission(owner, next.stepId, anchor, null, emptyList())
        validate(requests = listOf(admission, second))
        assertThrows(IllegalArgumentException::class.java) {
            validate(requests = listOf(admission, second.copy(selection = selection.copy(timeReminderEnabled = true))))
        }
    }

    @Test fun `omitted is a whole entry closure and retains no executable location`() {
        val omitted = ConversationContextUse(system.id, MessageRole.SYSTEM, ContextPlacement.Omitted)
        val second = ConversationContextAdmission(owner, next.stepId, anchor, null, listOf(omitted))
        validate(requests = listOf(admission, second))
        assertEquals(ContextPlacement.Omitted, ConversationContextCodec.decodePlacement(ConversationContextCodec.encodePlacement(ContextPlacement.Omitted)))
        assertThrows(IllegalArgumentException::class.java) {
            validate(requests = listOf(admission, second.copy(uses = listOf(omitted, admission.uses.single()))))
        }
    }

    @Test fun `historical entries with unknown step remain legal without fabricated admissions`() {
        validate(entries = listOf(system.copy(stepId = null)), requests = emptyList())
        assertThrows(IllegalArgumentException::class.java) { validate(entries = listOf(system.copy(id = Uuid.random()))) }
        assertThrows(IllegalArgumentException::class.java) { validate(nodes = listOf(node, user)) }
    }
}
