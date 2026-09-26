package net.weero.measix.pilot.data.ai.request

import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.core.ToolOutputPolicy
import me.rerere.ai.ui.*
import net.weero.measix.pilot.data.model.*
import net.weero.measix.pilot.service.ConversationDisclosureSnapshotService
import net.weero.measix.pilot.service.DisclosureFact
import net.weero.measix.pilot.service.DisclosureToolOutcome
import net.weero.measix.pilot.service.runtime.stableCandidate
import net.weero.measix.pilot.service.runtime.toSnapshot
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.testkit.sampledModelResult
import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Instant
import kotlin.uuid.Uuid

class AdmittedContextPlannerTest {
    private val planner = RequestContextPlanner()
    private val allSections = DisclosureSection.entries.toSet()
    private fun step(ordinal: Int) = UIMessagePart.Step(Uuid.random(), ordinal, Instant.parse("2026-09-26T10:00:00Z"))
    private fun locators(messages: List<UIMessage>) = messages.associate { it.id to DurableMessageLocator(Uuid.random(), it.id) }
    private fun DurableMessageLocator.context() = ContextMessageLocator(nodeId, messageId)
    private fun content(vararg sections: DisclosureSection) = ConversationDisclosureSnapshotService.renderSections(
        ConversationDisclosureSnapshotService.readSections(stableCandidate(1)).filterKeys { it in sections })
    private fun entry(owner: ContextMessageLocator, anchor: ContextMessageLocator, text: String, occurrence: Int = 0,
        creationStep: Uuid? = null) = ConversationModelContextEntry(owner.nodeId, owner.messageId, anchor.nodeId, anchor.messageId,
        ConversationContextPayload(source = ConversationContextSource.Disclosure(null), body = ConversationContextBody.Inline(text)),
        occurrence, creationStep)
    private fun projection(entry: ConversationModelContextEntry, placement: ContextPlacement, role: MessageRole = MessageRole.USER) =
        ModelContextProjection(entry.id, ContextMessageLocator(entry.ownerNodeId, entry.ownerMessageId), role, placement,
            (entry.payload.body as ConversationContextBody.Inline).text, entry.payload.source)
    private fun tool(step: UIMessagePart.Step) = UIMessagePart.Tool(Uuid.random(), step.stepId, "call-write", "memory_tool",
        "{\"action\":\"edit\",\"id\":1,\"content\":\"new\"}", listOf(UIMessagePart.Text("{\"success\":true,\"id\":1}")),
        resultStatus = ToolResultStatus.COMPLETED, runtimeState = ToolRuntimeState(ToolOutputPolicy.PRESERVE))
    private fun classify(locator: ToolCallLocator, tool: UIMessagePart.Tool): DisclosureFact.Tool = DisclosureFact.Tool(
        DisclosureBuiltinTool.MEMORY, true, DisclosureToolOutcome.CONFIRMED_SUCCESS,
        Json.parseToJsonElement(tool.input).jsonObject,
        tool.output.filterIsInstance<UIMessagePart.Text>().singleOrNull()?.text?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() })

    @Test fun `format3 sections replay in causal order around visible own writes`() {
        val user = UIMessage.user("question")
        val first = step(0)
        val second = step(1)
        val write = tool(first)
        val assistant = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(first, write, second, UIMessagePart.Text("answer")))
        val messages = listOf(user, assistant)
        val origins = locators(messages)
        val owner = origins.getValue(assistant.id).context()
        val anchor = origins.getValue(user.id).context()
        val memory = entry(owner, anchor, content(DisclosureSection.MEMORY), creationStep = first.stepId)
        val directory = entry(owner, anchor, content(DisclosureSection.SUB_ASSISTANTS), 1, second.stepId)
        val admissions = listOf(
            ConversationContextAdmission(owner, first.stepId, anchor, null, listOf(
                ConversationContextUse(memory.id, MessageRole.USER, ContextPlacement.BeforeMessage(anchor)))),
            ConversationContextAdmission(owner, second.stepId, anchor, null, listOf(
                ConversationContextUse(directory.id, MessageRole.USER, ContextPlacement.BeforeStep(second.stepId)))))
        val plan = planner.planRequest(messages, origins, listOf(memory, directory), admissions, 0,
            applicableDisclosureSections = { allSections }, classifyTool = ::classify)
        assertEquals(listOf(DisclosureFact.Snapshot::class, DisclosureFact.Tool::class, DisclosureFact.Snapshot::class),
            plan.disclosureFacts.map { it::class })
        assertEquals(setOf(DisclosureSection.MEMORY, DisclosureSection.SUB_ASSISTANTS), plan.previouslyDisclosed)
        val rendered = planner.applyContextProjections(plan.messages, plan.contextProjections, plan.originsByMessageId)
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT, MessageRole.USER, MessageRole.ASSISTANT), rendered.map { it.role })
        assertEquals(write, rendered[1].parts.filterIsInstance<UIMessagePart.Tool>().single())
        assertEquals(directory.payload.body, ConversationContextBody.Inline(rendered[2].toText()))
    }

    @Test fun `window removes baseline instead of moving stale state ahead of retained tools`() {
        val messages = (0..4).map { if (it % 2 == 0) UIMessage.user("u$it") else UIMessage.assistant("a$it") }
        val origins = locators(messages)
        val old = entry(origins.getValue(messages[1].id).context(), origins.getValue(messages[0].id).context(), stableCandidate(1))
        val plan = planner.planRequest(messages, origins, listOf(old), messageLimit = 3,
            applicableDisclosureSections = { allSections })
        assertEquals(messages.drop(2), plan.messages)
        assertTrue(plan.contextProjections.isEmpty())
        assertEquals(listOf(DisclosureFact.Missing(allSections)), plan.disclosureFacts)
        assertEquals(allSections, plan.previouslyDisclosed)
    }

    @Test fun `partially applicable namespace never leaks other sections of an old package`() {
        val messages = listOf(UIMessage.user("question"), UIMessage.assistant("answer"))
        val origins = locators(messages)
        val old = entry(origins.getValue(messages[1].id).context(), origins.getValue(messages[0].id).context(), stableCandidate(1))
        val plan = planner.planRequest(messages, origins, listOf(old), messageLimit = 0,
            applicableDisclosureSections = { setOf(DisclosureSection.SUB_ASSISTANTS) })
        assertTrue(plan.contextProjections.isEmpty())
        assertEquals(listOf(DisclosureFact.Missing(setOf(DisclosureSection.SUB_ASSISTANTS))), plan.disclosureFacts)
    }

    @Test fun `unknown historical namespace supplies only restoration evidence while known foreign namespace is ignored`() {
        val messages = listOf(UIMessage.user("question"), UIMessage.assistant("answer"))
        val origins = locators(messages)
        val old = entry(origins.getValue(messages[1].id).context(), origins.getValue(messages[0].id).context(), stableCandidate(1))
        val unknown = planner.planRequest(messages, origins, listOf(old), messageLimit = 0)
        assertTrue(unknown.contextProjections.isEmpty())
        assertEquals(allSections, unknown.previouslyDisclosed)
        assertEquals(listOf(DisclosureFact.Missing(allSections)), unknown.disclosureFacts)
        val foreign = old.copy(payload = old.payload.copy(source = ConversationContextSource.Disclosure(
            DisclosureNamespace("__global__", ConfigurationReference.random()))))
        val excluded = planner.planRequest(messages, origins, listOf(foreign), messageLimit = 0)
        assertTrue(excluded.contextProjections.isEmpty())
        assertTrue(excluded.previouslyDisclosed.isEmpty())
        assertTrue(excluded.disclosureFacts.isEmpty())
    }

    @Test fun `historical disclosure omission survives empty deltas and keeps earlier request details unchanged`() {
        val user = UIMessage.user("question")
        val first = step(0)
        val second = step(1)
        val third = step(2)
        val assistant = UIMessage(role = MessageRole.ASSISTANT,
            parts = listOf(first, UIMessagePart.Text("first"), second, UIMessagePart.Text("second"), third))
        val messages = listOf(user, assistant)
        val origins = locators(messages)
        val owner = origins.getValue(assistant.id).context()
        val anchor = origins.getValue(user.id).context()
        val namespace = DisclosureNamespace("__global__", ConfigurationReference.random())
        val saved = entry(owner, anchor, content(DisclosureSection.MEMORY), creationStep = first.stepId).let {
            it.copy(payload = it.payload.copy(source = ConversationContextSource.Disclosure(namespace,
                mapOf(DisclosureSection.MEMORY to ContextAdmissionReason.INITIAL)))) }
        val selected = TurnContextSelection(systemEntryId = Uuid.random(), ruleEntryIds = emptyList(), timeReminderEnabled = false,
            timeZoneId = "UTC", disclosureNamespace = namespace)
        val use = ConversationContextUse(saved.id, MessageRole.USER, ContextPlacement.BeforeMessage(anchor))
        val admissions = listOf(ConversationContextAdmission(owner, first.stepId, anchor, selected, listOf(use)),
            ConversationContextAdmission(owner, second.stepId, anchor, null, listOf(use.copy(placement = ContextPlacement.Omitted))),
            ConversationContextAdmission(owner, third.stepId, anchor, null, emptyList()))
        val original = planner.planRequest(messages, origins, listOf(saved), admissions, 0, owner, first.stepId, { allSections })
        val closed = planner.planRequest(messages, origins, listOf(saved), admissions, 0, owner, third.stepId, { allSections })
        assertEquals(listOf(saved.id), original.contextProjections.map { it.entryId })
        assertTrue(closed.contextProjections.isEmpty())
        assertEquals(setOf(DisclosureSection.MEMORY), closed.previouslyDisclosed)
        assertEquals(listOf(DisclosureFact.Missing(setOf(DisclosureSection.MEMORY))), closed.disclosureFacts)
        val snapshot = Conversation.ofId(Uuid.random()).toSnapshot().copy(
            nodes = messages.map { MessageNode(origins.getValue(it.id).nodeId, listOf(it)) },
            modelContextEntries = listOf(saved), contextAdmissions = admissions)
        assertEquals(listOf(LocatedContextUse(owner, use)), resolveRequestContextUses(snapshot, admissions.first()))
        assertTrue(resolveRequestContextUses(snapshot, admissions.last()).isEmpty())
    }

    @Test fun `reused disclosure resolves each owner separately and preserves the retained causal placement`() {
        val first = step(0)
        val second = step(0)
        val user = UIMessage.user("first")
        val initial = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(first, UIMessagePart.Text("answer")))
        val laterUser = UIMessage.user("second")
        val later = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(second))
        val messages = listOf(user, initial, laterUser, later)
        val origins = locators(messages)
        val firstOwner = origins.getValue(initial.id).context()
        val nextOwner = origins.getValue(later.id).context()
        val firstAnchor = origins.getValue(user.id).context()
        val nextAnchor = origins.getValue(laterUser.id).context()
        val saved = entry(firstOwner, firstAnchor, content(DisclosureSection.MEMORY), creationStep = first.stepId)
        val admissions = listOf(
            ConversationContextAdmission(firstOwner, first.stepId, firstAnchor, null,
                listOf(ConversationContextUse(saved.id, MessageRole.USER, ContextPlacement.BeforeMessage(firstAnchor)))),
            ConversationContextAdmission(nextOwner, second.stepId, nextAnchor, null,
                listOf(ConversationContextUse(saved.id, MessageRole.USER, ContextPlacement.BeforeStep(second.stepId)))))
        val plan = planner.planRequest(messages, origins, listOf(saved), admissions, 2, nextOwner, second.stepId, { allSections })
        assertEquals(nextOwner, plan.contextProjections.single().owner)
        assertEquals(ContextPlacement.BeforeStep(second.stepId), plan.contextProjections.single().placement)
        val output = planner.applyContextProjections(plan.messages, plan.contextProjections, plan.originsByMessageId)
        assertEquals(laterUser, output.first())
        assertEquals((saved.payload.body as ConversationContextBody.Inline).text, output.last().toText())
    }

    @Test fun `archived own write invalidates knowledge and confirmed nonexecution does not`() {
        val first = step(0)
        val original = tool(first)
        val archived = original.copy(output = listOf(UIMessagePart.Text("[Archived tool result: ref=tool_outputs/a.txt]")),
            runtimeState = original.runtimeState.copy(archive = ToolOutputArchive(1,
            ToolOutputArchiveRef("tool_outputs/a.txt", "text/plain"), 100, 10)))
        val user = UIMessage.user("question")
        val assistant = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(first, archived))
        val messages = listOf(user, assistant)
        val origins = locators(messages)
        val old = entry(origins.getValue(assistant.id).context(), origins.getValue(user.id).context(), stableCandidate(1))
        val plan = planner.planRequest(messages, origins, listOf(old), messageLimit = 0,
            applicableDisclosureSections = { allSections }, classifyTool = ::classify)
        assertEquals(DisclosureFact.Missing(setOf(DisclosureSection.MEMORY)), plan.disclosureFacts.last())
        val notExecuted = planner.planRequest(messages, origins, listOf(old), messageLimit = 0,
            applicableDisclosureSections = { allSections }, classifyTool = { locator, tool ->
                classify(locator, tool).copy(outcome = DisclosureToolOutcome.NOT_EXECUTED) })
        assertEquals(1, notExecuted.disclosureFacts.size)
    }

    @Test fun `retry cutoff excludes later results and admission while inheriting unchanged request uses`() {
        val user = UIMessage.user("question")
        val first = step(0)
        val second = step(1)
        val third = step(2)
        val assistant = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(first, tool(first), second,
            UIMessagePart.Text("future output"), third, tool(third)))
        val messages = listOf(user, assistant)
        val origins = locators(messages)
        val owner = origins.getValue(assistant.id).context()
        val anchor = origins.getValue(user.id).context()
        val original = entry(owner, anchor, stableCandidate(1), creationStep = first.stepId)
        val future = entry(owner, anchor, stableCandidate(2), 1, third.stepId)
        val use = ConversationContextUse(original.id, MessageRole.USER, ContextPlacement.BeforeMessage(anchor))
        val admissions = listOf(ConversationContextAdmission(owner, first.stepId, anchor, null, listOf(use)),
            ConversationContextAdmission(owner, second.stepId, anchor, null, emptyList()),
            ConversationContextAdmission(owner, third.stepId, anchor, null, listOf(
                ConversationContextUse(future.id, MessageRole.USER, ContextPlacement.BeforeStep(third.stepId)))))
        val plan = planner.planRequest(messages, origins, listOf(original, future), admissions, 0, owner, second.stepId,
            { allSections }, ::classify)
        assertEquals(listOf(original.id), plan.contextProjections.map { it.entryId })
        assertFalse(plan.messages.last().parts.any { it is UIMessagePart.Text && it.text == "future output" })
        assertEquals(1, plan.disclosureFacts.filterIsInstance<DisclosureFact.Tool>().size)
        assertEquals(listOf(use), resolveUsesAt(admissions, owner, second.stepId, listOf(first, second, third)).uses)
    }

    @Test fun `beforeStep split preserves each opaque response group once and tool identity unchanged`() {
        val first = step(0)
        val second = step(1)
        val write = tool(first)
        val firstMetadata = OpenAIResponseMetadata(OpenAIResponseWireFormat.OPENAI, listOf(listOf(buildJsonObject {
            put("type", "function_call"); put("call_id", write.providerCallId); put("unknown_future_field", "keep") }))).toMetadata()
        val secondMetadata = OpenAIResponseMetadata(OpenAIResponseWireFormat.OPENAI, listOf(listOf(buildJsonObject {
            put("type", "message"); put("id", "second-output") }))).toMetadata()
        val sampledFirst = first.copy(modelResult = sampledModelResult("tool_calls").copy(providerMetadata = firstMetadata))
        val sampledSecond = second.copy(modelResult = sampledModelResult("stop").copy(providerMetadata = secondMetadata))
        val assistant = UIMessage(role = MessageRole.ASSISTANT,
            parts = listOf(sampledFirst, write, sampledSecond, UIMessagePart.Text("answer")),
            providerMetadata = mergeMessageMetadata(firstMetadata, secondMetadata))
        val user = UIMessage.user("question")
        val messages = listOf(user, assistant)
        val origins = locators(messages)
        val entry = entry(origins.getValue(assistant.id).context(), origins.getValue(user.id).context(), content(DisclosureSection.MEMORY))
        val output = planner.applyContextProjections(messages, listOf(projection(entry, ContextPlacement.BeforeStep(second.stepId))),
            origins.mapValues { RequestMessageOrigin.Durable(it.value) })
        assertEquals(firstMetadata, output[1].providerMetadata)
        assertEquals(secondMetadata, output[3].providerMetadata)
        assertEquals(listOf(write), output.flatMap { it.parts }.filterIsInstance<UIMessagePart.Tool>())
        assertEquals(setOf(ToolCallLocator(assistant.id, first.stepId, write.localCallId)), planner.receiptOf(output).visibleInlineToolOutputs)
    }

    @Test fun `typed projection preserves original user parts and rejects synthetic anchors`() {
        val user = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("question"), UIMessagePart.Image("image://x")))
        val origin = DurableMessageLocator(Uuid.random(), user.id)
        val old = entry(ContextMessageLocator(Uuid.random(), Uuid.random()), origin.context(), stableCandidate(1))
        val projection = projection(old, ContextPlacement.BeforeMessage(origin.context()))
        val registered = mutableListOf<UIMessagePart.Text>()
        val rendered = planner.applyContextProjections(listOf(user), listOf(projection), mapOf(user.id to RequestMessageOrigin.Durable(origin))) {
                supplied, part, containerId, synthetic ->
            assertEquals(projection, supplied)
            assertEquals(user.id, containerId)
            assertFalse(synthetic)
            registered += part
        }
        assertSame(rendered.single().parts.first(), registered.single())
        assertEquals(user.parts, rendered.single().parts.drop(1))
        assertSame(user.parts[1], rendered.single().parts.last())
        assertThrows(IllegalStateException::class.java) {
            planner.applyContextProjections(listOf(user), listOf(projection), mapOf(user.id to RequestMessageOrigin.Synthetic(SyntheticMessageKind.TIME_REMINDER)))
        }
        assertThrows(IllegalStateException::class.java) {
            planner.applyContextProjections(listOf(user, user), listOf(projection), mapOf(user.id to RequestMessageOrigin.Durable(origin)))
        }
    }

    @Test fun `request uses inherit selection but never include later step or another owner`() {
        val owner = ContextMessageLocator(Uuid.random(), Uuid.random())
        val first = step(0)
        val second = step(1)
        val third = step(2)
        val systemId = Uuid.random()
        val ruleId = Uuid.random()
        val selection = TurnContextSelection(systemEntryId = systemId, ruleEntryIds = listOf(ruleId),
            timeReminderEnabled = true, timeZoneId = "UTC")
        val original = ConversationContextUse(ruleId, MessageRole.USER, ContextPlacement.BeforeStep(first.stepId))
        val moved = original.copy(placement = ContextPlacement.BeforeStep(second.stepId))
        val admissions = listOf(
            ConversationContextAdmission(owner, third.stepId, owner, selection.copy(ruleEntryIds = emptyList()), emptyList()),
            ConversationContextAdmission(owner, first.stepId, owner, selection, listOf(original)),
            ConversationContextAdmission(owner, second.stepId, owner, null, listOf(moved)))
        assertEquals(ResolvedContextUses(selection, listOf(original)), resolveUsesAt(admissions, owner, first.stepId, listOf(first, second, third)))
        assertEquals(ResolvedContextUses(selection, listOf(moved)), resolveUsesAt(admissions, owner, second.stepId, listOf(first, second, third)))
        assertEquals(ResolvedContextUses(null, emptyList()), resolveUsesAt(admissions, owner.copy(messageId = Uuid.random()), second.stepId, listOf(first, second)))
    }

    @Test fun `empty active step is a placement boundary without consuming history window budget`() {
        val history = (0..3).map { if (it % 2 == 0) UIMessage.user("u$it") else UIMessage.assistant("a$it") }
        val step = step(0)
        val active = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(step))
        val messages = history + active
        val origins = locators(messages)
        val plan = planner.planRequest(messages, origins, messageLimit = 4,
            requestOwner = origins.getValue(active.id).context(), requestStepId = step.stepId)
        assertEquals(messages, plan.messages)
        assertEquals(history, planner.applyContextProjections(plan.messages, emptyList(), plan.originsByMessageId))
        val old = entry(origins.getValue(active.id).context(), origins.getValue(history[2].id).context(), stableCandidate(1))
        val result = planner.applyContextProjections(plan.messages, listOf(projection(old, ContextPlacement.BeforeStep(step.stepId))),
            plan.originsByMessageId)
        assertEquals(history, result.dropLast(1))
        assertEquals(MessageRole.USER, result.last().role)
        assertEquals(stableCandidate(1), result.last().toText())
    }

    @Test fun `explicit omission closes inherited positions without resurrecting them at an empty boundary`() {
        val owner = ContextMessageLocator(Uuid.random(), Uuid.random())
        val first = step(0)
        val second = step(1)
        val third = step(2)
        val id = Uuid.random()
        val use = ConversationContextUse(id, MessageRole.USER, ContextPlacement.BeforeStep(first.stepId))
        val closed = use.copy(placement = ContextPlacement.Omitted)
        val admissions = listOf(ConversationContextAdmission(owner, first.stepId, owner, null, listOf(use)),
            ConversationContextAdmission(owner, second.stepId, owner, null, listOf(closed)),
            ConversationContextAdmission(owner, third.stepId, owner, null, emptyList()))
        assertEquals(listOf(use), resolveUsesAt(admissions, owner, first.stepId, listOf(first, second, third)).uses)
        assertTrue(resolveUsesAt(admissions, owner, third.stepId, listOf(first, second, third)).uses.isEmpty())
        val message = UIMessage.user("original")
        val projection = ModelContextProjection(id, owner, MessageRole.USER, ContextPlacement.Omitted,
            "must never be sent", ConversationContextSource.System(emptyList()))
        val projected = planner.applyContextProjections(listOf(message), listOf(projection), emptyMap()) { _, _, _, _ ->
            fail("omission must not produce a model part")
        }
        assertEquals(listOf(message), projected)
    }
}
