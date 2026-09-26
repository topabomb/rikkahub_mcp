package net.weero.measix.pilot.data.model

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ConversationContextCloneTest {
    @Test fun `clone remaps selection message placements and source locators while preserving ordered bodies`() {
        val previous = MessageNode.of(UIMessage.user("previous"))
        val user = MessageNode.of(UIMessage.user("question"))
        val step = UIMessagePart.Step(Uuid.random(), 0, Instant.parse("2026-09-26T10:00:00Z"))
        val owner = MessageNode.of(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(step)))
        val location = ContextMessageLocator(user.id, user.currentMessage.id)
        val entries = listOf(
            ConversationContextSource.System(emptyList()),
            ConversationContextSource.PromptRule(PromptInjection.ModeInjection(content = "original rule"), emptyMap()),
            ConversationContextSource.Attachment(location, 0, "原文件.txt", AttachmentContextInput.DOCUMENT_TEXT, "attachment:original"),
            ConversationContextSource.MessageTime(location, "2026-09-26T10:00:00", ContextMessageLocator(previous.id,
                previous.currentMessage.id), "2026-09-26T08:00:00", "Asia/Shanghai"),
        ).mapIndexed { index, source -> ConversationModelContextEntry(owner.id, owner.currentMessage.id,
            user.id, user.currentMessage.id, ConversationContextPayload(source = source,
                body = ConversationContextBody.Inline("original $index {{literal}}")), occurrence = index, stepId = step.stepId) }
        val placements = listOf(ContextPlacement.System, ContextPlacement.BeforeMessage(location),
            ContextPlacement.MessagePart(location, 0), ContextPlacement.BeforeStep(step.stepId))
        val admission = ConversationContextAdmission(ContextMessageLocator(owner.id, owner.currentMessage.id), step.stepId,
            location, TurnContextSelection(systemEntryId = entries[0].id, ruleEntryIds = listOf(entries[1].id),
                timeReminderEnabled = true, timeZoneId = "Asia/Shanghai"),
            placements.mapIndexed { index, placement -> ConversationContextUse(entries[if (index == 0) 0 else 2].id,
                if (index == 0) MessageRole.SYSTEM else MessageRole.USER, placement) })
        val nodes = listOf(previous, user, owner)
        val nodeMap = nodes.associate { it.id to Uuid.random() }
        val messageMap = nodes.associate { it.currentMessage.id to Uuid.random() }
        val clonedNodes = nodes.map { it.copy(id = nodeMap.getValue(it.id), messages = it.messages.map { message ->
            message.copy(id = messageMap.getValue(message.id)) }) }
        val mappedEntries = ConversationModelContextApplicability.remapForClone(entries, nodeMap, messageMap, clonedNodes)
        val mapped = remapContextAdmissionsForClone(listOf(admission), entries, nodeMap, messageMap).single()
        val mappedUser = ContextMessageLocator(nodeMap.getValue(user.id), messageMap.getValue(user.currentMessage.id))
        assertNotEquals(admission.id, mapped.id)
        assertEquals(contextAdmissionIdentity(mapped.owner, step.stepId), mapped.id)
        assertEquals(step.stepId, mapped.stepId)
        assertEquals(mappedUser, mapped.windowStart)
        assertEquals(mappedEntries[0].id, mapped.selection!!.systemEntryId)
        assertEquals(listOf(mappedEntries[1].id), mapped.selection.ruleEntryIds)
        assertEquals(listOf(mappedEntries[0].id, mappedEntries[2].id, mappedEntries[2].id, mappedEntries[2].id), mapped.uses.map { it.entryId })
        assertEquals(ContextPlacement.BeforeMessage(mappedUser), mapped.uses[1].placement)
        assertEquals(ContextPlacement.MessagePart(mappedUser, 0), mapped.uses[2].placement)
        assertEquals(ContextPlacement.BeforeStep(step.stepId), mapped.uses[3].placement)
        assertEquals(entries.map { it.payload.body }, mappedEntries.map { it.payload.body })
        assertEquals(mappedUser, (mappedEntries[2].payload.source as ConversationContextSource.Attachment).message)
        val time = mappedEntries[3].payload.source as ConversationContextSource.MessageTime
        assertEquals(mappedUser, time.message)
        assertEquals(ContextMessageLocator(nodeMap.getValue(previous.id), messageMap.getValue(previous.currentMessage.id)), time.previous)
        assertEquals("2026-09-26T08:00:00", time.previousTime)
        ConversationContextIntegrity.validate(clonedNodes, mappedEntries, listOf(mapped), null,
            net.weero.measix.pilot.data.configuration.ConfigurationScope.Personal)
    }

    @Test fun `clone keeps zero contribution boundaries and rejects a missing referenced node`() {
        val owner = ContextMessageLocator(Uuid.random(), Uuid.random())
        val window = ContextMessageLocator(Uuid.random(), Uuid.random())
        val admission = ConversationContextAdmission(owner, Uuid.random(), window, null, emptyList())
        val map = mapOf(owner.nodeId to Uuid.random(), window.nodeId to Uuid.random())
        val copy = remapContextAdmissionsForClone(listOf(admission), emptyList(), map, emptyMap()).single()
        assertTrue(copy.uses.isEmpty())
        assertEquals(owner.messageId, copy.owner.messageId)
        assertTrue(remapContextAdmissionsForClone(listOf(admission), emptyList(), emptyMap(), emptyMap()).isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            remapContextAdmissionsForClone(listOf(admission), emptyList(), map - window.nodeId, emptyMap())
        }
    }

    @Test fun `clone refuses identity maps that merge different nodes or messages`() {
        val target = Uuid.random()
        assertThrows(IllegalArgumentException::class.java) {
            remapContextAdmissionsForClone(emptyList(), emptyList(), mapOf(Uuid.random() to target, Uuid.random() to target), emptyMap())
        }
        assertThrows(IllegalArgumentException::class.java) {
            remapContextAdmissionsForClone(emptyList(), emptyList(), emptyMap(), mapOf(Uuid.random() to target, Uuid.random() to target))
        }
    }
}
