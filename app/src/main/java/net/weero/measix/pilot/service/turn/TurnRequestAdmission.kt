package net.weero.measix.pilot.service.turn

import kotlinx.serialization.SerializationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.ui.ToolResultStatus
import me.rerere.ai.ui.ToolInteractionState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.request.*
import net.weero.measix.pilot.data.ai.transformers.RequestMessageOriginTracker
import net.weero.measix.pilot.data.ai.transformers.RequestPartSource
import net.weero.measix.pilot.data.ai.transformers.usedPromptPlaceholderValues
import net.weero.measix.pilot.data.db.entity.ToolExecutionStatus
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.model.*
import net.weero.measix.pilot.service.ConversationDisclosureReconciliation
import net.weero.measix.pilot.service.ConversationDisclosureSnapshotService
import net.weero.measix.pilot.service.DisclosureFact
import net.weero.measix.pilot.service.DisclosureToolOutcome
import net.weero.measix.pilot.service.runtime.TurnHandle
import kotlin.uuid.Uuid
import kotlin.coroutines.coroutineContext

/** One request's application contributions, planned from committed history and admitted before IO. */
internal class TurnRequestAdmission(
    private val context: TurnContext,
    private val history: TurnRequestHistory,
    private val access: TurnRequestContextAccess,
    handle: TurnHandle,
    private val stepId: Uuid,
    private val planner: RequestContextPlanner,
    private val artifacts: ArtifactStore,
) {
    private val snapshot = history.conversation
    private val branch = snapshot.currentMessages()
    private val node = snapshot.nodes.single { it.currentMessage.id == handle.assistantMessageId }
    val owner = ContextMessageLocator(node.id, handle.assistantMessageId)
    private val steps = node.currentMessage.parts.filterIsInstance<UIMessagePart.Step>()
    private val step = steps.single { it.stepId == stepId }
    private val anchor = snapshot.nodes.takeWhile { it.id != node.id }.last { it.currentMessage.role == MessageRole.USER }
    private val entries = snapshot.modelContextEntries.filter { ConversationModelContextApplicability.applicable(it, branch) }
    private val existing = snapshot.contextAdmissions.singleOrNull { it.owner == owner && it.stepId == stepId }
    private val source = context.disclosure
    private val locators = snapshot.nodes.associate { it.currentMessage.id to DurableMessageLocator(it.id, it.currentMessage.id) }

    suspend fun requireReadable() = source.requireReadable()

    fun plan(messages: List<UIMessage>): RequestContextPlan = planner.planRequest(
        durableMessages = messages,
        durableLocators = locators,
        modelContextEntries = snapshot.modelContextEntries,
        contextAdmissions = snapshot.contextAdmissions,
        messageLimit = context.assistant.contextMessageLimit,
        requestOwner = owner,
        requestStepId = stepId,
        applicableDisclosureSections = ::applicableSections,
        classifyTool = ::toolFact,
    ).let { plan ->
        val summaries = entries.filter { it.payload.source is ConversationContextSource.HistorySummary }
            .mapTo(mutableSetOf()) { it.ownerMessageId }
        plan.copy(messages = plan.messages.map { message ->
            if (message.id !in summaries) message else message.copy(parts = listOf(UIMessagePart.Text(buildJsonObject {
                put("type", "conversation_history_summary"); put("format", 1); put("content", message.toText())
            }.toString())))
        })
    }

    suspend fun markOrigins(origins: RequestMessageOriginTracker, requestedMessages: List<UIMessage> = branch) {
        val applicationMessages = entries.filter { it.payload.source is ConversationContextSource.Preset ||
            it.payload.source is ConversationContextSource.HistorySummary }.mapTo(mutableSetOf()) { it.ownerMessageId }
        applicationMessages.forEach(origins::markApplicationHistory)
        val requestedIds = requestedMessages.mapTo(mutableSetOf()) { it.id }
        val previousUsers = mutableMapOf<Uuid, UIMessage?>()
        var previous: UIMessage? = null
        branch.forEach { message ->
            if (message.role == MessageRole.USER && message.id !in applicationMessages) {
                origins.markPreviousRealUserTime(message.id, previous?.createdAt)
                previousUsers[message.id] = previous
                previous = message
            }
        }
        // Parse only bodies needed by this window. Older variants and off-window documents
        // must not cause IO or require access to an otherwise unused artifact.
        entries.asReversed().distinctBy { it.payload.source }.forEach { entry ->
            when (val source = entry.payload.source) {
                is ConversationContextSource.Attachment -> if (source.input == AttachmentContextInput.DOCUMENT_TEXT &&
                    source.message.messageId in requestedIds) {
                    val message = branch.singleOrNull { it.id == source.message.messageId }
                    val document = message?.parts?.getOrNull(source.partIndex) as? UIMessagePart.Document
                    if (document != null && document.url == source.originalReference && document.fileName == source.name) {
                        origins.rememberDocument(source.message.messageId, source.partIndex, textOf(entry))
                    }
                }
                is ConversationContextSource.MessageTime -> if (context.promptInputs.enableTimeReminder &&
                    source.message.messageId in requestedIds) {
                    val index = branch.indexOfFirst { it.id == source.message.messageId }
                    val message = branch.getOrNull(index)
                    val previous = previousUsers[source.message.messageId]
                    if (message?.createdAt?.toString() == source.messageTime &&
                        previous?.id == source.previous?.messageId && previous?.createdAt?.toString() == source.previousTime) {
                        origins.rememberMessageTime(source.message.messageId, textOf(entry))
                    }
                }
                else -> Unit
            }
        }
    }

    private suspend fun textOf(entry: ConversationModelContextEntry): String = when (val body = entry.payload.body) {
        is ConversationContextBody.Inline -> body.text
        is ConversationContextBody.Artifact -> artifacts.readContextText(context.realmAccess.scope, body)
        ConversationContextBody.Opening -> error("opening_requires_structured_rendering")
        is ConversationContextBody.MessageReference -> snapshot.nodes.single { it.id == body.message.nodeId }
            .messages.single { it.id == body.message.messageId }.toText()
    }

    private fun applicableSections(entry: ConversationModelContextEntry): Set<DisclosureSection> {
        val original = (entry.payload.source as? ConversationContextSource.Disclosure)?.namespace
            ?: return emptySet()
        return buildSet {
            if (original.memoryOwner == source.namespace.memoryOwner) add(DisclosureSection.MEMORY)
            if (original.caller == source.namespace.caller) {
                add(DisclosureSection.SUB_ASSISTANTS)
                add(DisclosureSection.ENTERPRISE_MEMORY_SEEDS)
            }
        }
    }

    private fun toolFact(locator: ToolCallLocator, tool: UIMessagePart.Tool): DisclosureFact.Tool? {
        val originalNode = snapshot.nodes.singleOrNull { it.currentMessage.id == locator.assistantMessageId } ?: return null
        val originalSteps = originalNode.currentMessage.parts.filterIsInstance<UIMessagePart.Step>()
        val selection = resolveUsesAt(snapshot.contextAdmissions,
            ContextMessageLocator(originalNode.id, locator.assistantMessageId), locator.stepId, originalSteps).selection
            ?: return null
        val builtin = selection.builtinTools[tool.toolName] ?: return null
        val namespace = selection.disclosureNamespace ?: return null
        val applies = when (builtin) {
            DisclosureBuiltinTool.MEMORY -> namespace.memoryOwner == source.namespace.memoryOwner
            DisclosureBuiltinTool.ASSISTANT_MANAGE -> namespace.caller == source.namespace.caller
        }
        val execution = history.toolOutcomes[locator]
        val outcome = when {
            execution == ToolExecutionStatus.COMPLETED && tool.resultStatus == ToolResultStatus.COMPLETED ->
                DisclosureToolOutcome.CONFIRMED_SUCCESS
            execution != null -> DisclosureToolOutcome.UNCONFIRMED_EXECUTION
            tool.resultStatus == ToolResultStatus.DENIED || tool.interactionState is ToolInteractionState.Denied ->
                DisclosureToolOutcome.NOT_EXECUTED
            locator.assistantMessageId in history.trackedAssistantMessageIds ->
                if (tool.resultStatus == ToolResultStatus.COMPLETED) DisclosureToolOutcome.UNCONFIRMED_EXECUTION
                else DisclosureToolOutcome.NOT_EXECUTED
            // Forks preserve application-committed result status and captured builtin identity,
            // but intentionally do not create local execution records for historical calls.
            tool.resultStatus == ToolResultStatus.COMPLETED -> DisclosureToolOutcome.CONFIRMED_SUCCESS
            else -> DisclosureToolOutcome.UNCONFIRMED_EXECUTION
        }
        return DisclosureFact.Tool(builtin, applies, outcome, jsonObject(tool.input) ?: JsonObject(emptyMap()),
            tool.output.takeIf { it.all { part -> part is UIMessagePart.Text } }
                ?.joinToString("\n") { (it as UIMessagePart.Text).text }?.let(::jsonObject))
    }

    suspend fun <T> admit(
        plan: RequestContextPlan,
        transformed: List<UIMessage>,
        origins: RequestMessageOriginTracker,
        assemble: (List<UIMessage>) -> T,
    ): T {
        val added = mutableListOf<ConversationModelContextEntry>()
        val uses = mutableListOf<ConversationContextUse>()
        var nextOccurrence = (snapshot.modelContextEntries.filter { it.ownerNodeId == owner.nodeId &&
            it.ownerMessageId == owner.messageId }.maxOfOrNull { it.occurrence } ?: -1) + 1
        suspend fun content(payload: ConversationContextPayload, reuse: Boolean = true): ConversationModelContextEntry {
            if (reuse) {
                for (entry in entries + added) {
                    if (entry.payload == payload || entry.payload.source == payload.source &&
                        entry.payload.body is ConversationContextBody.Artifact && payload.body is ConversationContextBody.Inline &&
                        textOf(entry) == payload.body.text) return entry
                }
            }
            return ConversationModelContextEntry(owner.nodeId, owner.messageId, anchor.id, anchor.currentMessage.id,
                payload, occurrence = nextOccurrence++, stepId = stepId).also(added::add)
        }
        suspend fun inline(source: ConversationContextSource, text: String) =
            content(ConversationContextPayload(source = source, body = ConversationContextBody.Inline(text)))
        fun use(entry: ConversationModelContextEntry, role: MessageRole, placement: ContextPlacement) {
            uses += ConversationContextUse(entry.id, role, placement)
        }
        val system = inline(ConversationContextSource.System(context.system.contributions), context.system.text)
        use(system, MessageRole.SYSTEM, ContextPlacement.System)
        val rules = context.promptInputs.promptInjections.associate { rule ->
            rule.id to inline(rule.source(context), rule.content)
        }
        transformed.forEachIndexed { index, message ->
            val target = locators[message.id]
            val before = transformed.drop(index + 1).firstNotNullOfOrNull { locators[it.id] }
            val syntheticPlacement = before?.let { ContextPlacement.BeforeMessage(it.contextLocator()) }
                ?: ContextPlacement.BeforeStep(stepId)
            locatedParts(message.parts).forEach { (partPath, part) ->
                val partIndex = partPath.first()
                val text = (part as? UIMessagePart.Text)?.text ?: return@forEach
                when (val partSource = origins.source(part)) {
                    is RequestPartSource.PromptRule -> use(rules.getValue(partSource.rule.id), message.role, syntheticPlacement)
                    is RequestPartSource.MessageTime -> {
                        val real = partSource.message
                        val preceding = branch.takeWhile { it.id != real.id }.lastOrNull { origins.isRealUser(it) }
                        val timeSource = ConversationContextSource.MessageTime(
                            message = locators.getValue(real.id).contextLocator(), messageTime = real.createdAt.toString(),
                            previous = preceding?.let { locators.getValue(it.id).contextLocator() },
                            previousTime = preceding?.createdAt?.toString(), zoneId = context.promptInputs.zoneId,
                        )
                        val saved = entries.firstOrNull { entry ->
                            val original = entry.payload.source as? ConversationContextSource.MessageTime
                            original != null && original.copy(zoneId = timeSource.zoneId) == timeSource
                        }
                        use(saved ?: inline(timeSource, text), message.role, syntheticPlacement)
                    }
                    is RequestPartSource.DocumentInput -> {
                        val locator = requireNotNull(target) { "document_context_source_missing" }.contextLocator()
                        val original = branch.single { it.id == message.id }.parts.indexOfFirst { it === partSource.document }
                        require(original >= 0) { "document_context_part_missing" }
                        val entry = inline(ConversationContextSource.Attachment(locator, original, partSource.document.fileName,
                            AttachmentContextInput.DOCUMENT_TEXT, partSource.document.url), text)
                        use(entry, message.role, ContextPlacement.MessagePart(locator, partIndex))
                        origins.markPart(part, RequestPartSource.Admitted(entry.id))
                    }
                    is RequestPartSource.AttachmentInput -> {
                        val locator = requireNotNull(target) { "attachment_context_source_missing" }.contextLocator()
                        val original = locatedParts(branch.single { it.id == message.id }.parts)
                            .firstOrNull { it.second === partSource.attachment }?.first
                            ?: error("attachment_context_part_missing")
                        val url = when (val attachment = partSource.attachment) {
                            is UIMessagePart.Image -> attachment.url
                            is UIMessagePart.Document -> attachment.url
                            is UIMessagePart.Audio -> attachment.url
                            is UIMessagePart.Video -> attachment.url
                            else -> error("attachment_context_media_required")
                        }
                        val name = (partSource.attachment as? UIMessagePart.Document)?.fileName.orEmpty()
                        val entry = inline(ConversationContextSource.Attachment(locator, original.first(), name,
                            AttachmentContextInput.valueOf(partSource.mode), url, original.drop(1)), text)
                        use(entry, message.role, ContextPlacement.MessagePart(locator, partIndex, partPath.drop(1)))
                        origins.markPart(part, RequestPartSource.Admitted(entry.id))
                    }
                    else -> Unit
                }
            }
        }
        val firstUser = plan.messages.firstOrNull { origins.isRealUser(it) }
            ?: error("context_request_requires_real_user")
        val newProjections = mutableListOf<ModelContextProjection>()
        snapshot.opening?.let { opening ->
            val blocks = requireNotNull(opening.definition.openingSnapshot).initialContexts
            if (blocks.isNotEmpty()) {
                val entry = content(ConversationContextPayload(source = ConversationContextSource.Starter,
                    body = ConversationContextBody.Opening))
                val placement = ContextPlacement.MessagePart(locators.getValue(firstUser.id).contextLocator(), 0)
                val text = buildJsonObject {
                    put("type", "starter_context"); put("format", 1)
                    putJsonArray("blocks") { blocks.forEach { block -> add(buildJsonObject {
                        put("id", block.id); put("title", block.title); put("content", block.content)
                    }) } }
                }.toString()
                newProjections += ModelContextProjection(entry.id, owner, MessageRole.USER, placement, text, entry.payload.source)
                use(entry, MessageRole.USER, placement)
            }
        }
        if (existing == null) {
            val reconciliation = ConversationDisclosureReconciliation.reconcile(source.read(), plan.disclosureFacts, plan.previouslyDisclosed)
            reconciliation.content?.let { text ->
                val entry = content(ConversationContextPayload(
                    source = ConversationContextSource.Disclosure(source.namespace, reconciliation.reasons, reconciliation.changes),
                    body = ConversationContextBody.Inline(text)), reuse = false)
                val placement = if (step.ordinal == 0) ContextPlacement.MessagePart(
                    ContextMessageLocator(anchor.id, anchor.currentMessage.id), 0) else ContextPlacement.BeforeStep(stepId)
                newProjections.add(0, ModelContextProjection(entry.id, owner, MessageRole.USER, placement, text, entry.payload.source))
                use(entry, MessageRole.USER, placement)
            }
        }
        val previous = resolveUsesAt(snapshot.contextAdmissions.filterNot { it.id == existing?.id }, owner, stepId, steps)
        val selection = TurnContextSelection(systemEntryId = system.id, ruleEntryIds = rules.values.map { it.id },
            timeReminderEnabled = context.promptInputs.enableTimeReminder, timeZoneId = context.promptInputs.zoneId,
            disclosureNamespace = source.namespace,
            builtinTools = buildMap {
                context.toolBindingsByName.forEach { (name, binding) ->
                    DisclosureBuiltinTool.entries.singleOrNull { it.executionIdentity == binding.executionIdentity }
                        ?.let { put(name, it) }
                }
            })
        val projection = planner.applyContextProjections(transformed, plan.contextProjections + newProjections,
            origins.frozenOrigins()) { contribution, part, containerId, synthetic ->
            origins.markPart(part, RequestPartSource.Admitted(contribution.entryId))
            if (synthetic) origins.markSynthetic(containerId, SyntheticMessageKind.APPLICATION_CONTEXT)
        }
        val assembled = assemble(projection)
        // Positions in history are not part offsets in the final request. Prefixes and
        // multiple document expansions must be accounted for before recording those offsets.
        val actualParts = projection.flatMap { message ->
            val locator = locators[message.id]?.contextLocator() ?: return@flatMap emptyList()
            locatedParts(message.parts).mapNotNull { (path, part) ->
                val id = (origins.source(part) as? RequestPartSource.Admitted)?.entryId ?: return@mapNotNull null
                ConversationContextUse(id, message.role, ContextPlacement.MessagePart(locator, path.first(), path.drop(1)))
            }
        }
        val actualByEntry = actualParts.groupBy { it.entryId }
        val locatedUses = uses.groupBy { it.entryId }.flatMap { (id, group) ->
            if (group.all { it.placement is ContextPlacement.MessagePart }) actualByEntry[id] ?: group else group
        }
        if (existing == null) {
            val oldGroups = previous.uses.groupBy { it.entryId }
            val newGroups = locatedUses.groupBy { it.entryId }
            val changed = newGroups.values.filter { group -> oldGroups[group.first().entryId] != group }.flatten() +
                oldGroups.filterKeys { id -> id !in newGroups && entries.single { it.id == id }.payload.source !is ConversationContextSource.Disclosure }
                    .map { (id, group) -> ConversationContextUse(id, group.first().role, ContextPlacement.Omitted) }
            val admission = ConversationContextAdmission(owner, stepId, locators.getValue(plan.messages.first().id).contextLocator(),
                selection.takeIf { previous.selection == null }, changed)
            commit(added, admission)
        } else {
            check(added.isEmpty()) { "admitted_request_application_content_changed" }
            val sealed = resolveUsesAt(snapshot.contextAdmissions, owner, stepId, steps)
            check(sealed.selection == selection) { "admitted_request_selection_changed" }
            check(existing.windowStart == locators.getValue(plan.messages.first().id).contextLocator()) {
                "admitted_request_window_changed"
            }
            val disclosureIds = entries.filter { it.payload.source is ConversationContextSource.Disclosure }
                .mapTo(mutableSetOf()) { it.id }
            check(sealed.uses.filterNot { it.entryId in disclosureIds }.groupBy { it.entryId } ==
                locatedUses.filterNot { it.entryId in disclosureIds }.groupBy { it.entryId }) {
                "admitted_request_placements_changed"
            }
        }
        return assembled
    }

    private suspend fun commit(entries: List<ConversationModelContextEntry>, admission: ConversationContextAdmission) {
        val resources = UnpublishedResourceScope()
        var committed = false
        try {
            val stored = entries.map { entry ->
                val body = entry.payload.body as? ConversationContextBody.Inline
                // State is bounded to 256 KiB and parsed directly by the planner. Other large
                // rendered bodies use the existing immutable Artifact owner instead of hot rows.
                if (body == null || entry.payload.source is ConversationContextSource.Disclosure ||
                    body.text.toByteArray(Charsets.UTF_8).size <= CONTEXT_INLINE_BYTES) entry else {
                    val owned = artifacts.createText(context.realmAccess.scope, body.text,
                        displayName = "context-${entry.id}.txt")
                    resources.register(artifacts.unpublishedLease(owned))
                    entry.copy(payload = entry.payload.copy(body = ConversationContextBody.Artifact(
                        artifactId = owned.entity.id, relativePath = owned.entity.relativePath)))
                }
            }
            coroutineContext.ensureActive()
            // The staged bodies now have an explicit owner. Commit and publication form their
            // handoff boundary; cancellation must not discard a body already rooted in history.
            withContext(NonCancellable) {
                access.admit(stored, admission)
                committed = true
                resources.publishAll()
            }
        } catch (error: Throwable) {
            if (!committed) resources.discardAll()?.let(error::addSuppressed)
            throw error
        }
    }
}

private const val CONTEXT_INLINE_BYTES = 64 * 1024

private fun locatedParts(parts: List<UIMessagePart>, prefix: List<Int> = emptyList()): List<Pair<List<Int>, UIMessagePart>> =
    parts.flatMapIndexed { index, part ->
        val path = prefix + index
        listOf(path to part) + if (part is UIMessagePart.Tool) locatedParts(part.output, path) else emptyList()
    }

private fun DurableMessageLocator.contextLocator() = ContextMessageLocator(nodeId, messageId)

private fun ResolvedPromptInjection.source(context: TurnContext) = ConversationContextSource.PromptRule(
    PromptInjection.ModeInjection(id, name, true, priority, position, template, injectDepth, role),
    usedPromptPlaceholderValues(template, context.promptInputs.placeholderValues),
)

private fun jsonObject(value: String): JsonObject? = try {
    Json.parseToJsonElement(value) as? JsonObject
} catch (_: SerializationException) { null }
