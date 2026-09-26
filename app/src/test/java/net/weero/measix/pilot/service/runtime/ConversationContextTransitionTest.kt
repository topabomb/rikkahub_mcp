package net.weero.measix.pilot.service.runtime

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.StepOutcome
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.model.ContextMessageLocator
import net.weero.measix.pilot.data.model.ContextPlacement
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.ConversationContextAdmission
import net.weero.measix.pilot.data.model.ConversationContextBody
import net.weero.measix.pilot.data.model.ConversationContextPayload
import net.weero.measix.pilot.data.model.ConversationContextSource
import net.weero.measix.pilot.data.model.ConversationContextUse
import net.weero.measix.pilot.data.model.ConversationModelContextEntry
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.model.SystemContextContribution
import net.weero.measix.pilot.data.model.SystemContextKind
import net.weero.measix.pilot.data.model.TurnContextSelection
import net.weero.measix.pilot.testkit.sampledModelResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ConversationContextTransitionTest {
    private val step = UIMessagePart.Step(Uuid.random(), 0, Instant.parse("2026-09-26T10:00:00Z"))
    private val userNode = MessageNode.of(UIMessage.user("question"))
    private val ownerNode = MessageNode.of(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(step)))
    private val owner = ContextMessageLocator(ownerNode.id, ownerNode.currentMessage.id)
    private val anchor = ContextMessageLocator(userNode.id, userNode.currentMessage.id)
    private val base = Conversation.ofId(Uuid.random()).copy(messageNodes = listOf(userNode, ownerNode), newConversation = false).toSnapshot()
    private val handle = TurnHandle(base.conversationId, 0, Uuid.random(), owner.messageId)
    private val system = ConversationModelContextEntry(owner.nodeId, owner.messageId, anchor.nodeId, anchor.messageId,
        ConversationContextPayload(source = ConversationContextSource.System(listOf(
            SystemContextContribution(SystemContextKind.DOMAIN, "Domain", "stable system"))),
            body = ConversationContextBody.Inline("stable system")), stepId = step.stepId)
    private val firstAdmission = ConversationContextAdmission(owner, step.stepId, anchor,
        TurnContextSelection(systemEntryId = system.id, ruleEntryIds = emptyList(), timeReminderEnabled = false, timeZoneId = "UTC"),
        listOf(ConversationContextUse(system.id, MessageRole.SYSTEM, ContextPlacement.System)))
    private fun first() = AdmitRequestContext(handle, listOf(system), firstAdmission)

    @Test fun `first input and same retry commit once with unchanged transcript`() {
        val committed = ConversationTransition.apply(base, first())
        assertEquals(base.nodes, committed.nodes)
        assertEquals(listOf(system), committed.modelContextEntries)
        assertEquals(listOf(firstAdmission), committed.contextAdmissions)
        assertEquals(committed, ConversationTransition.apply(committed, first()))
        assertEquals(committed, ConversationTransition.apply(committed, first().copy(entries = emptyList())))
    }

    @Test fun `retry cannot replace admitted content or placement`() {
        val committed = ConversationTransition.apply(base, first())
        val changed = system.copy(payload = system.payload.copy(body = ConversationContextBody.Inline("changed")))
        assertThrows(ConversationCommandConflictException::class.java) {
            ConversationTransition.apply(committed, first().copy(entries = listOf(changed)))
        }
        val moved = firstAdmission.copy(uses = listOf(ConversationContextUse(system.id, MessageRole.USER,
            ContextPlacement.BeforeMessage(anchor))))
        assertThrows(ConversationCommandConflictException::class.java) {
            ConversationTransition.apply(committed, first().copy(admission = moved))
        }
        assertEquals(listOf(system), committed.modelContextEntries)
    }

    @Test fun `later zero entry admission seals the boundary without duplicating system`() {
        val committed = ConversationTransition.apply(base, first())
        val nextStep = UIMessagePart.Step(Uuid.random(), 1, Instant.parse("2026-09-26T10:01:00Z"))
        val completedStep = step.copy(modelResult = sampledModelResult("tool_calls"), outcome = StepOutcome.Continue,
            finishedAt = nextStep.startedAt)
        val nextOwner = ownerNode.copy(messages = listOf(ownerNode.currentMessage.copy(parts = listOf(completedStep, nextStep))))
        val awaiting = committed.copy(nodes = listOf(userNode, nextOwner))
        val admission = ConversationContextAdmission(owner, nextStep.stepId, anchor, null, emptyList())
        val command = AdmitRequestContext(handle, emptyList(), admission)
        val after = ConversationTransition.apply(awaiting, command)
        assertEquals(2, after.contextAdmissions.size)
        assertEquals(listOf(system), after.modelContextEntries)
        assertTrue(after.contextAdmissions.last().uses.isEmpty())
        assertEquals(after, ConversationTransition.apply(after, command))
    }

    @Test fun `wrong conversation owner or already sampled step cannot accept new input`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConversationTransition.apply(base, first().copy(handle = handle.copy(conversationId = Uuid.random())))
        }
        val wrongOwner = firstAdmission.copy(owner = owner.copy(messageId = Uuid.random()))
        assertThrows(IllegalArgumentException::class.java) { ConversationTransition.apply(base, first().copy(admission = wrongOwner)) }
        val sampled = ownerNode.copy(messages = listOf(ownerNode.currentMessage.copy(parts = listOf(
            step.copy(modelResult = sampledModelResult("stop"))))))
        assertThrows(IllegalArgumentException::class.java) {
            ConversationTransition.apply(base.copy(nodes = listOf(userNode, sampled)), first())
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConversationTransition.apply(base, first().copy(entries = listOf(system.copy(stepId = Uuid.random()))))
        }
    }

    @Test fun `selection cannot refer to absent system or rule content`() {
        assertThrows(IllegalArgumentException::class.java) { ConversationTransition.apply(base, first().copy(entries = emptyList())) }
        val selection = requireNotNull(firstAdmission.selection).copy(ruleEntryIds = listOf(Uuid.random()))
        assertThrows(IllegalArgumentException::class.java) {
            ConversationTransition.apply(base, first().copy(admission = firstAdmission.copy(selection = selection)))
        }
    }
}
