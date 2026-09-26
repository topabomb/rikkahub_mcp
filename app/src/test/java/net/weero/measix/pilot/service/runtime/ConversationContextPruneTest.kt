package net.weero.measix.pilot.service.runtime

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ConversationContextPruneTest {
    private fun step(ordinal: Int) = UIMessagePart.Step(Uuid.random(), ordinal, Instant.parse("2026-09-26T10:00:00Z"))
    private val firstStep = step(0)
    private val secondStep = step(1)
    private val user = MessageNode.of(UIMessage.user("first"))
    private val oldOwner = MessageNode.of(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(firstStep)))
    private val nextUser = MessageNode.of(UIMessage.user("next"))
    private val nextOwner = MessageNode.of(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(secondStep)))
    private fun MessageNode.locator() = ContextMessageLocator(id, currentMessage.id)
    private val entry = ConversationModelContextEntry(oldOwner.id, oldOwner.currentMessage.id, user.id, user.currentMessage.id,
        ConversationContextPayload(source = ConversationContextSource.System(emptyList()),
            body = ConversationContextBody.Inline("stable original")), stepId = firstStep.stepId)
    private fun admission(owner: MessageNode, step: UIMessagePart.Step, anchor: MessageNode, withUse: Boolean = true) =
        ConversationContextAdmission(owner.locator(), step.stepId, anchor.locator(),
            TurnContextSelection(systemEntryId = entry.id, ruleEntryIds = emptyList(), timeReminderEnabled = false, timeZoneId = "UTC"),
            if (withUse) listOf(ConversationContextUse(entry.id, MessageRole.SYSTEM, ContextPlacement.System)) else emptyList())
    private fun base() = Conversation.ofId(Uuid.random()).copy(newConversation = false).toSnapshot().copy(
        nodes = listOf(user, oldOwner, nextUser, nextOwner), modelContextEntries = listOf(entry),
        contextAdmissions = listOf(admission(oldOwner, firstStep, user), admission(nextOwner, secondStep, nextUser)))
    private fun validate(snapshot: ConversationAggregateSnapshot) = ConversationContextIntegrity.validate(snapshot.nodes,
        snapshot.modelContextEntries, snapshot.contextAdmissions, snapshot.opening, snapshot.header.scope)

    @Test fun `retired owner hands shared body to retained request without dangling selection or uses`() {
        val original = base()
        val pruned = ConversationContextTransition.prune(original.copy(nodes = listOf(user, nextUser, nextOwner)))
        validate(pruned)
        val retained = pruned.modelContextEntries.single()
        val request = pruned.contextAdmissions.single()
        assertEquals(entry.payload, retained.payload)
        assertEquals(nextOwner.id, retained.ownerNodeId)
        assertEquals(nextUser.id, retained.anchorNodeId)
        assertEquals(secondStep.stepId, retained.stepId)
        assertNotEquals(entry.id, retained.id)
        assertEquals(retained.id, request.selection!!.systemEntryId)
        assertEquals(retained.id, request.uses.single().entryId)
        assertEquals(pruned, ConversationContextTransition.prune(pruned))
    }

    @Test fun `zero contribution boundary survives and still retains selection body`() {
        val original = base().copy(contextAdmissions = listOf(admission(nextOwner, secondStep, nextUser, withUse = false)))
        val pruned = ConversationContextTransition.prune(original.copy(nodes = listOf(nextUser, nextOwner)))
        validate(pruned)
        assertEquals(1, pruned.contextAdmissions.size)
        assertTrue(pruned.contextAdmissions.single().uses.isEmpty())
        assertEquals(pruned.modelContextEntries.single().id, pruned.contextAdmissions.single().selection!!.systemEntryId)
    }

    @Test fun `removed contribution location closes that entry while preserving both sealed requests`() {
        val removedUser = MessageNode.of(UIMessage.user("removed source"))
        val valid = admission(nextOwner, secondStep, nextUser, withUse = false)
        val changed = admission(oldOwner, firstStep, user).copy(uses = listOf(
            ConversationContextUse(entry.id, MessageRole.USER, ContextPlacement.BeforeMessage(removedUser.locator()))))
        val original = base().copy(nodes = listOf(user, removedUser, oldOwner, nextUser, nextOwner),
            contextAdmissions = listOf(changed, valid))
        validate(original)
        val pruned = ConversationContextTransition.prune(original.copy(nodes = listOf(user, oldOwner, nextUser, nextOwner)), original)
        validate(pruned)
        assertEquals(2, pruned.contextAdmissions.size)
        assertEquals(ContextPlacement.Omitted, pruned.contextAdmissions.first().uses.single().placement)
        assertEquals(valid, pruned.contextAdmissions.last())
        assertEquals(listOf(entry), pruned.modelContextEntries)
    }

    @Test fun `removed creation step hands body to a later step in the same surviving owner variant`() {
        val both = oldOwner.copy(messages = listOf(oldOwner.currentMessage.copy(parts = listOf(firstStep, secondStep))))
        val later = admission(both, secondStep, user, withUse = false).copy(selection = null)
        val original = base().copy(nodes = listOf(user, both), contextAdmissions = listOf(admission(both, firstStep, user), later))
        val surviving = both.copy(messages = listOf(both.currentMessage.copy(parts = listOf(secondStep))))
        val pruned = ConversationContextTransition.prune(original.copy(nodes = listOf(user, surviving)), original)
        validate(pruned)
        assertEquals(listOf(secondStep.stepId), pruned.contextAdmissions.map { it.stepId })
        assertEquals(secondStep.stepId, pruned.modelContextEntries.single().stepId)
        assertEquals(entry.payload, pruned.modelContextEntries.single().payload)
        val retainedId = pruned.modelContextEntries.single().id
        assertEquals(listOf(ConversationContextUse(retainedId, MessageRole.SYSTEM, ContextPlacement.System)),
            pruned.contextAdmissions.single().uses)
        assertEquals(retainedId, pruned.contextAdmissions.single().selection!!.systemEntryId)
        assertEquals(pruned, ConversationContextTransition.prune(pruned))
    }

    @Test fun `removing a middle admission materializes its moved position once for later empty seals`() {
        val third = step(2)
        val fourth = step(3)
        val owner = oldOwner.copy(messages = listOf(oldOwner.currentMessage.copy(parts = listOf(firstStep, secondStep, third, fourth))))
        val first = admission(owner, firstStep, user).copy(uses = listOf(
            ConversationContextUse(entry.id, MessageRole.SYSTEM, ContextPlacement.BeforeStep(firstStep.stepId))))
        val moved = admission(owner, secondStep, user).copy(selection = null, uses = listOf(
            first.uses.single().copy(placement = ContextPlacement.BeforeStep(secondStep.stepId))))
        val later = admission(owner, third, user, withUse = false).copy(selection = null)
        val last = admission(owner, fourth, user, withUse = false).copy(selection = null)
        val original = base().copy(nodes = listOf(user, owner), contextAdmissions = listOf(first, moved, later, last))
        validate(original)
        val pruned = ConversationContextTransition.prune(original.copy(contextAdmissions = listOf(first, later, last)), original)
        validate(pruned)
        assertEquals(moved.uses, pruned.contextAdmissions[1].uses)
        assertTrue(pruned.contextAdmissions.last().uses.isEmpty())
        assertEquals(pruned, ConversationContextTransition.prune(pruned))
    }

    @Test fun `retired body gets separate owners for sibling variants without cross variant references`() {
        val otherMessage = nextOwner.currentMessage.copy(id = Uuid.random())
        val variants = nextOwner.copy(messages = listOf(nextOwner.currentMessage, otherMessage))
        val first = admission(nextOwner, secondStep, nextUser)
        val other = first.copy(owner = ContextMessageLocator(variants.id, otherMessage.id),
            id = contextAdmissionIdentity(ContextMessageLocator(variants.id, otherMessage.id), secondStep.stepId))
        val original = base().copy(nodes = listOf(user, oldOwner, nextUser, variants), contextAdmissions = listOf(first, other))
        validate(original)
        val pruned = ConversationContextTransition.prune(original.copy(nodes = listOf(user, nextUser, variants)), original)
        validate(pruned)
        assertEquals(2, pruned.modelContextEntries.size)
        pruned.contextAdmissions.forEach { admission ->
            val body = pruned.modelContextEntries.single { it.id == admission.selection!!.systemEntryId }
            assertEquals(admission.owner.messageId, body.ownerMessageId)
            assertEquals(entry.payload, body.payload)
            assertEquals(body.id, admission.uses.single().entryId)
        }
    }
}
