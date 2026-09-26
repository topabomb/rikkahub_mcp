package net.weero.measix.pilot.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.request.resolveRequestContextUses
import net.weero.measix.pilot.data.model.*
import net.weero.measix.pilot.service.runtime.toSnapshot
import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ConversationContextPresentationTest {
    private val user = MessageNode.of(UIMessage.user("question"))
    private val first = UIMessagePart.Step(Uuid.random(), 0, Instant.parse("2026-09-26T10:00:00Z"))
    private val second = first.copy(stepId = Uuid.random(), ordinal = 1)
    private val assistant = MessageNode.of(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(first, second)))
    private fun MessageNode.locator() = ContextMessageLocator(id, currentMessage.id)
    private val namespace = DisclosureNamespace("__global__", me.rerere.common.configuration.ConfigurationReference.random())
    private fun entry(source: ConversationContextSource, occurrence: Int = 0, step: UIMessagePart.Step? = first) =
        ConversationModelContextEntry(assistant.id, assistant.currentMessage.id, user.id, user.currentMessage.id,
            ConversationContextPayload(source = source, body = ConversationContextBody.Inline("{{original}}")),
            occurrence, step?.stepId)
    private val system = entry(ConversationContextSource.System(emptyList()))
    private fun admission(step: UIMessagePart.Step, entries: List<ConversationModelContextEntry>) = ConversationContextAdmission(
        assistant.locator(), step.stepId, user.locator(),
        if (step == first) TurnContextSelection(systemEntryId = system.id, ruleEntryIds = emptyList(),
            timeReminderEnabled = false, timeZoneId = "UTC", disclosureNamespace = namespace) else null,
        entries.map { ConversationContextUse(it.id, if (it == system) MessageRole.SYSTEM else MessageRole.USER,
            if (it == system) ContextPlacement.System else ContextPlacement.BeforeStep(step.stepId)) })
    private fun snapshot(entries: List<ConversationModelContextEntry>, admissions: List<ConversationContextAdmission>) =
        Conversation.ofId(Uuid.random()).toSnapshot().copy(nodes = listOf(user, assistant), modelContextEntries = entries, contextAdmissions = admissions)

    @Test fun `several external updates produce one message flag and preserve each causal request`() {
        val initial = entry(ConversationContextSource.Disclosure(namespace, mapOf(DisclosureSection.MEMORY to ContextAdmissionReason.INITIAL)), 1)
        val update = entry(ConversationContextSource.Disclosure(namespace, mapOf(DisclosureSection.MEMORY to ContextAdmissionReason.EXTERNAL_STATE)), 2, second)
        val state = snapshot(listOf(system, initial, update), listOf(admission(first, listOf(system, initial)), admission(second, listOf(update))))
        val summary = projectConversationContextSummary(state)
        assertTrue(summary.messages.getValue(assistant.currentMessage.id).hasExternalUpdate)
        assertFalse(summary.messages.getValue(user.currentMessage.id).hasExternalUpdate)
        assertTrue(summary.messages.getValue(user.currentMessage.id).hasContent)
        val detail = projectConversationContextDetails(state, user.currentMessage.id)
        assertEquals(listOf(1, 0), detail.requests.map { it.ordinal })
        assertEquals(3, detail.requests.first().items.size)
        assertEquals(2, detail.requests.last().items.size)
    }

    @Test fun `restoration and unknown historical content never claim external updates`() {
        val restored = entry(ConversationContextSource.Disclosure(namespace, mapOf(DisclosureSection.MEMORY to ContextAdmissionReason.BASELINE_RESTORE)), 1)
        val state = snapshot(listOf(system, restored), listOf(admission(first, listOf(system, restored))))
        assertFalse(projectConversationContextSummary(state).messages.values.any { it.hasExternalUpdate })
        assertEquals(listOf(ConversationContextCategory.RESTORE), projectConversationContextDetails(state, user.currentMessage.id).requests.single().items.last().categories)
        val old = restored.copy(stepId = null, payload = restored.payload.copy(source = ConversationContextSource.Disclosure(null)))
        val historic = snapshot(listOf(old), emptyList())
        val item = projectConversationContextDetails(historic, user.currentMessage.id).requests.single().items.single()
        assertEquals(ConversationContextRequestState.HISTORICAL, projectConversationContextDetails(historic, user.currentMessage.id).requests.single().state)
        assertNull(projectConversationContextDetails(historic, user.currentMessage.id).requests.single().id)
        assertFalse(projectConversationContextSummary(historic).messages.values.any { it.hasExternalUpdate })
    }

    @Test fun `switching variant cannot reveal sibling admissions or infer source from user text`() {
        val state = snapshot(listOf(system), listOf(admission(first, listOf(system))))
        val sibling = UIMessage.assistant("other answer")
        val switched = state.copy(nodes = listOf(user, assistant.copy(messages = assistant.messages + sibling, selectIndex = 1)))
        assertTrue(projectConversationContextSummary(switched).messages.isEmpty())
        assertTrue(projectConversationContextDetails(switched, user.currentMessage.id).requests.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { projectConversationContextDetails(switched, assistant.currentMessage.id) }
        val ordinary = state.copy(modelContextEntries = emptyList(), contextAdmissions = emptyList())
        assertTrue(projectConversationContextSummary(ordinary).messages.isEmpty())
    }

    @Test fun `later omission removes prior attachment without removing its earlier history`() {
        val document = entry(ConversationContextSource.Attachment(user.locator(), 2, "original.txt", AttachmentContextInput.DOCUMENT_TEXT, "attachment:id"), 1)
        val firstAdmission = admission(first, listOf(system, document)).copy(uses = listOf(
            ConversationContextUse(system.id, MessageRole.SYSTEM, ContextPlacement.System),
            ConversationContextUse(document.id, MessageRole.USER, ContextPlacement.MessagePart(user.locator(), 4))))
        val omitted = admission(second, emptyList()).copy(uses = listOf(ConversationContextUse(document.id, MessageRole.USER, ContextPlacement.Omitted)))
        val state = snapshot(listOf(system, document), listOf(firstAdmission, omitted))
        assertEquals(listOf(system.id), resolveRequestContextUses(state, omitted).map { it.use.entryId })
        val old = resolveRequestContextUses(state, firstAdmission).last().use
        assertEquals(ContextPlacement.MessagePart(user.locator(), 4), old.placement)
        assertEquals(2, (document.payload.source as ConversationContextSource.Attachment).partIndex)
    }

    @Test fun `window clipping and namespace change exclude prior disclosure instead of relocating it`() {
        val memory = entry(ConversationContextSource.Disclosure(namespace, mapOf(DisclosureSection.MEMORY to ContextAdmissionReason.INITIAL)), 1)
        val original = admission(first, listOf(system, memory)).copy(uses = listOf(
            ConversationContextUse(system.id, MessageRole.SYSTEM, ContextPlacement.System),
            ConversationContextUse(memory.id, MessageRole.USER, ContextPlacement.BeforeMessage(user.locator()))))
        val clipped = admission(second, emptyList()).copy(windowStart = assistant.locator())
        val state = snapshot(listOf(system, memory), listOf(original, clipped))
        assertEquals(listOf(system.id), resolveRequestContextUses(state, clipped).map { it.use.entryId })
        val changed = original.copy(selection = original.selection!!.copy(disclosureNamespace = namespace.copy(memoryOwner = null)))
        assertEquals(listOf(system.id), resolveRequestContextUses(state.copy(contextAdmissions = listOf(changed)), changed).map { it.use.entryId })
    }

    @Test fun `summary traverses a large selected branch once while preserving causal user and external flags`() {
        val pairs = (0 until 500).map { index ->
            MessageNode.of(UIMessage.user("question $index")) to
                MessageNode.of(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(first.copy(stepId = Uuid.random()))))
        }
        val nodes = pairs.flatMap { listOf(it.first, it.second) }
        val entries = pairs.map { (question, answer) ->
            val step = answer.currentMessage.parts.filterIsInstance<UIMessagePart.Step>().single()
            ConversationModelContextEntry(answer.id, answer.currentMessage.id, question.id, question.currentMessage.id,
                ConversationContextPayload(source = ConversationContextSource.Disclosure(namespace,
                    mapOf(DisclosureSection.MEMORY to ContextAdmissionReason.EXTERNAL_STATE)),
                    body = ConversationContextBody.Inline("never added to message body")), stepId = step.stepId)
        }
        val admissions = pairs.zip(entries).map { (pair, entry) ->
            ConversationContextAdmission(pair.second.locator(), entry.stepId!!, pair.first.locator(), null,
                listOf(ConversationContextUse(entry.id, MessageRole.USER, ContextPlacement.BeforeStep(requireNotNull(entry.stepId)))))
        }
        var reads = 0
        val counted = object : AbstractList<MessageNode>() {
            override val size get() = nodes.size
            override fun get(index: Int): MessageNode { reads++; return nodes[index] }
        }
        val state = snapshot(entries, admissions).copy(nodes = counted)
        val summary = projectConversationContextSummary(state)
        assertEquals(1000, summary.messages.size)
        assertEquals(500, summary.messages.values.count { it.hasExternalUpdate })
        assertTrue("Repeated selected-branch traversal: $reads", reads <= nodes.size * 2)
        assertTrue(pairs.all { summary.messages.getValue(it.first.currentMessage.id).hasContent })
    }

    @Test fun `a body request resolves only its chosen request and retains the same contribution directory`() {
        val update = entry(ConversationContextSource.Disclosure(namespace,
            mapOf(DisclosureSection.MEMORY to ContextAdmissionReason.EXTERNAL_STATE)), 1, second)
        val state = snapshot(listOf(system, update), listOf(admission(first, listOf(system)), admission(second, listOf(update))))
        val all = projectConversationContextDetails(state, user.currentMessage.id)
        all.requests.forEach { request ->
            assertEquals(listOf(request), projectConversationContextDetails(state, user.currentMessage.id, setOf(request.id)).requests)
        }
    }

    @Test fun `historical request keeps saved user variants at its window and contribution positions`() {
        val olderUser = MessageNode.of(UIMessage.user("older question"))
        val time = entry(ConversationContextSource.MessageTime(user.locator(), user.currentMessage.createdAt.toString(),
            olderUser.locator(), olderUser.currentMessage.createdAt.toString(), "UTC"), 1)
        val original = admission(first, listOf(system, time)).copy(windowStart = olderUser.locator(),
            selection = admission(first, emptyList()).selection!!.copy(timeReminderEnabled = true),
            uses = listOf(ConversationContextUse(system.id, MessageRole.SYSTEM, ContextPlacement.System),
                ConversationContextUse(time.id, MessageRole.USER, ContextPlacement.BeforeMessage(user.locator()))))
        val state = snapshot(listOf(system, time), listOf(original)).copy(nodes = listOf(olderUser, user, assistant))
        val expected = projectConversationContextDetails(state, assistant.currentMessage.id)
        fun changed(node: MessageNode) = node.copy(messages = node.messages + UIMessage.user("edited"), selectIndex = 1)
        // Both the window boundary and a contribution in its middle retain their saved locators.
        for (nodes in listOf(listOf(changed(olderUser), user, assistant),
            listOf(olderUser, changed(user), assistant), listOf(changed(olderUser), changed(user), assistant))) {
            assertEquals(expected, projectConversationContextDetails(state.copy(nodes = nodes), assistant.currentMessage.id))
        }
        val missing = state.copy(nodes = listOf(olderUser.copy(messages = listOf(UIMessage.user("replaced"))), user, assistant))
        assertThrows(IllegalArgumentException::class.java) { resolveRequestContextUses(missing, original) }
    }

    @Test fun `earlier assistant variants cannot be attributed to a later request without an explicit use`() {
        val memory = entry(ConversationContextSource.Disclosure(namespace,
            mapOf(DisclosureSection.MEMORY to ContextAdmissionReason.INITIAL)), 1)
        val nextUser = MessageNode.of(UIMessage.user("next question"))
        val nextAssistant = MessageNode.of(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(first)))
        val later = admission(first, listOf(system)).copy(owner = nextAssistant.locator())
        val state = snapshot(listOf(system, memory), listOf(admission(first, listOf(system, memory)), later))
            .copy(nodes = listOf(user, assistant, nextUser, nextAssistant))
        assertEquals(listOf(system.id), resolveRequestContextUses(state, later).map { it.use.entryId })
        val switched = state.copy(nodes = listOf(user, assistant.copy(messages = assistant.messages + UIMessage.assistant("sibling"), selectIndex = 1),
            nextUser, nextAssistant))
        assertEquals(resolveRequestContextUses(state, later), resolveRequestContextUses(switched, later))
        assertTrue(projectConversationContextDetails(state, assistant.currentMessage.id).requests.single().items.any { it.entryId == memory.id })
    }

    @Test fun `streaming reuses lightweight context while durable admission invalidates it`() {
        val state = snapshot(listOf(system), listOf(admission(first, listOf(system))))
        val projector = net.weero.measix.pilot.service.runtime.ConversationPresentationProjector()
        val runtime = net.weero.measix.pilot.service.runtime.ConversationRuntimeSnapshot(state, null)
        val initial = projector.project(runtime)
        assertEquals(state.nodes, initial.nodes)
        val streamed = runtime.copy(stream = net.weero.measix.pilot.service.runtime.TurnStreamProjection(
            epoch = 1, turnId = Uuid.random(), assistantMessageId = assistant.currentMessage.id,
            assistantMessage = assistant.currentMessage.copy(parts = listOf(first, me.rerere.ai.ui.UIMessagePart.Text("stream")))))
        assertSame(initial.context, projector.project(streamed).context)
        val update = entry(ConversationContextSource.Disclosure(namespace, mapOf(DisclosureSection.MEMORY to ContextAdmissionReason.EXTERNAL_STATE)), 1, second)
        val changed = state.copy(modelContextEntries = listOf(system, update), contextAdmissions = state.contextAdmissions + admission(second, listOf(update)))
        val projected = projector.project(streamed.copy(durable = changed))
        assertNotSame(initial.context, projected.context)
        assertEquals(user.currentMessage, projected.nodes.first().currentMessage)
        assertEquals(streamed.stream!!.assistantMessage, projected.nodes.last().currentMessage)
        assertEquals("stream", projected.nodes.last().currentMessage.toText())
        assertTrue(projected.context.messages.getValue(assistant.currentMessage.id).hasExternalUpdate)
    }
}
