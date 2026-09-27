package net.weero.measix.pilot.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.StepOutcome
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.request.resolveRequestContextUses
import net.weero.measix.pilot.data.model.*
import net.weero.measix.pilot.service.runtime.ConversationAggregateSnapshot
import kotlin.uuid.Uuid

enum class ConversationContextCategory { OPENING, SYSTEM, MEMORY, ASSISTANTS, ENTERPRISE_BACKGROUND, PROMPT_RULE, TIME, RESTORE, ATTACHMENT, HISTORY_SUMMARY, PRESET, HISTORICAL }
enum class ConversationContextRequestState { ADDED, INCOMPLETE, DELIVERY_UNKNOWN, HISTORICAL, SAVED_CONTENT }

/** Only discovery flags cross the continuously observed message projection. */
data class MessageContextSummary(
    val hasContent: Boolean,
    val hasExternalUpdate: Boolean = false,
    val origin: ConversationContextCategory? = null,
    val externalCategories: List<ConversationContextCategory> = emptyList(),
)

data class ConversationContextSummary(
    val messages: Map<Uuid, MessageContextSummary> = emptyMap(),
)

data class ConversationContextItemUiModel(
    val key: String,
    val entryId: Uuid?,
    val categories: List<ConversationContextCategory>,
    val name: String?,
    val role: String?,
    val location: String?,
    val isCurrentUpdate: Boolean = false,
    val updatedCategories: List<ConversationContextCategory> = emptyList(),
)

data class ConversationContextRequestUiModel(
    val id: Uuid?,
    val ordinal: Int?,
    val time: String?,
    val state: ConversationContextRequestState,
    val items: List<ConversationContextItemUiModel>,
)

data class ConversationContextDetailsUiModel(val requests: List<ConversationContextRequestUiModel>)
data class ConversationContextContentUiModel(
    val text: String,
    val source: String?,
    val presentation: ConversationContextPresentationUiModel = ConversationContextPresentationUiModel(),
)

enum class ConversationContextScope { SHARED, ASSISTANT, READ_ONLY, DISABLED, UNKNOWN }
enum class ConversationContextReason { INITIAL, EXTERNAL, RESTORE, HISTORICAL }
enum class ConversationContextSystemOwner { DOMAIN, CONVERSATION, ENTERPRISE_OPENING, APPLICATION, CONTEXT_SYNC, TOOL, WORKSPACE, PROMPT_RULE }
enum class ConversationContextChangeKind { ADDED, MODIFIED, REMOVED }

data class ConversationContextPresentationUiModel(val sections: List<ConversationContextSectionUiModel> = emptyList())
data class ConversationContextSectionUiModel(
    val category: ConversationContextCategory,
    val title: String? = null,
    val scope: ConversationContextScope? = null,
    val reason: ConversationContextReason? = null,
    val rows: List<ConversationContextRowUiModel> = emptyList(),
    val text: String? = null,
    val systemOwner: ConversationContextSystemOwner? = null,
    val exactChanges: Boolean = false,
    val attributes: List<ConversationContextAttributeUiModel> = emptyList(),
    val orderBefore: List<ConversationContextRowUiModel> = emptyList(),
    val orderAfter: List<ConversationContextRowUiModel> = emptyList(),
)
data class ConversationContextRowUiModel(
    val id: String? = null,
    val title: String? = null,
    val before: String? = null,
    val after: String? = null,
    val change: ConversationContextChangeKind? = null,
)
data class ConversationContextAttributeUiModel(val name: String, val before: String?, val after: String)

internal fun DisclosureSection.contextCategory(): ConversationContextCategory = when (this) {
    DisclosureSection.MEMORY -> ConversationContextCategory.MEMORY
    DisclosureSection.SUB_ASSISTANTS -> ConversationContextCategory.ASSISTANTS
    DisclosureSection.ENTERPRISE_MEMORY_SEEDS -> ConversationContextCategory.ENTERPRISE_BACKGROUND
}

internal fun projectConversationContextSummary(snapshot: ConversationAggregateSnapshot): ConversationContextSummary {
    if (snapshot.modelContextEntries.isEmpty() && snapshot.contextAdmissions.isEmpty() && snapshot.opening == null) return ConversationContextSummary()
    val branch = snapshot.currentMessages()
    val branchIndex = ConversationModelContextApplicability.index(branch)
    val entries = snapshot.modelContextEntries.filter(branchIndex::applicable)
    val selected = branchIndex.selectedMessageIds
    val admittedEntries = snapshot.contextAdmissions.groupBy { it.owner.messageId }.mapValues { (_, admissions) ->
        admissions.flatMapTo(mutableSetOf()) { it.uses.map(ConversationContextUse::entryId) }
    }
    val summaries = linkedMapOf<Uuid, MessageContextSummary>()
    fun add(id: Uuid, external: List<ConversationContextCategory> = emptyList(), origin: ConversationContextCategory? = null) {
        if (id !in selected) return
        val old = summaries[id]
        summaries[id] = MessageContextSummary(true, old?.hasExternalUpdate == true || external.isNotEmpty(), origin ?: old?.origin,
            (old?.externalCategories.orEmpty() + external).distinct().sortedBy { it.ordinal })
    }
    entries.forEach { entry ->
        val source = entry.payload.source
        val origin = when (source) {
            is ConversationContextSource.Preset -> ConversationContextCategory.PRESET
            is ConversationContextSource.HistorySummary -> ConversationContextCategory.HISTORY_SUMMARY
            else -> null
        }
        val external = if (source is ConversationContextSource.Disclosure && entry.id in admittedEntries[entry.ownerMessageId].orEmpty()) {
            source.reasons.filterValues { it == ContextAdmissionReason.EXTERNAL_STATE }.keys.map { it.contextCategory() }
        } else emptyList()
        add(entry.ownerMessageId, external, origin)
        // The causal USER retains access when the assistant has no renderable content.
        if (origin == null) add(entry.anchorMessageId)
    }
    snapshot.contextAdmissions.filter { it.owner.messageId in selected }.forEach { admission ->
        add(admission.owner.messageId)
        branchIndex.previousUser(admission.owner.messageId)?.let { add(it) }
    }
    if (snapshot.opening != null) branch.forEach { add(it.id) }
    return ConversationContextSummary(summaries)
}

internal fun contextOwnerForMessage(snapshot: ConversationAggregateSnapshot, messageId: Uuid): Uuid {
    val branch = snapshot.currentMessages()
    val index = branch.indexOfFirst { it.id == messageId }
    require(index >= 0) { "context_message_off_selected_branch" }
    if (branch[index].role != MessageRole.USER) return messageId
    return branch.drop(index + 1).takeWhile { it.role != MessageRole.USER }
        .firstOrNull { it.role == MessageRole.ASSISTANT }?.id ?: messageId
}

internal fun projectConversationContextDetails(
    snapshot: ConversationAggregateSnapshot,
    messageId: Uuid,
    requestIds: Set<Uuid?>? = null,
): ConversationContextDetailsUiModel {
    val branch = snapshot.currentMessages()
    val ownerId = contextOwnerForMessage(snapshot, messageId)
    val branchIndex = ConversationModelContextApplicability.index(branch)
    val entries = snapshot.modelContextEntries.associateBy { it.id }
    val steps = branch.single { it.id == ownerId }.parts.filterIsInstance<UIMessagePart.Step>().associateBy { it.stepId }
    val admissions = snapshot.contextAdmissions.filter {
        it.owner.messageId == ownerId && (requestIds == null || it.id in requestIds)
    }.sortedBy { steps.getValue(it.stepId).ordinal }
    val requests = admissions.map { admission ->
        val step = steps.getValue(admission.stepId)
        val items = resolveRequestContextUses(snapshot, admission).mapIndexed { index, located ->
            val entry = requireNotNull(entries[located.use.entryId]) { "context_contribution_missing" }
            entry.toUiItem("${admission.id}/${entry.id}/$index", located.use.role.name,
                "${located.owner.nodeId}/${located.owner.messageId} · ${located.use.placement}",
                currentRequest = entry.stepId == admission.stepId && entry.ownerMessageId == admission.owner.messageId)
        }.toMutableList()
        if (snapshot.opening != null && items.none { ConversationContextCategory.OPENING in it.categories }) items.add(0, openingContextItem())
        ConversationContextRequestUiModel(admission.id, step.ordinal, step.startedAt.toString(), when {
            step.modelResult != null -> ConversationContextRequestState.ADDED
            step.outcome is StepOutcome.Failed || step.outcome is StepOutcome.Cancelled || step.outcome is StepOutcome.Interrupted || step.outcome is StepOutcome.Incomplete -> ConversationContextRequestState.INCOMPLETE
            else -> ConversationContextRequestState.DELIVERY_UNKNOWN
        }, items)
    }.toMutableList()
    if (requestIds == null || null in requestIds) {
        val admittedEntryIds = snapshot.contextAdmissions.flatMapTo(mutableSetOf()) { admission -> admission.uses.map { it.entryId } }
        val historic = entries.values.filter { entry ->
            branchIndex.applicable(entry) && (entry.ownerMessageId == ownerId || entry.ownerMessageId == messageId) &&
                entry.id !in admittedEntryIds
        }.map { it.toUiItem(it.id.toString(), null, null) }.toMutableList()
        if (snapshot.opening != null && requests.isEmpty()) historic.add(0, openingContextItem())
        if (historic.isNotEmpty()) requests.add(0, ConversationContextRequestUiModel(null, null, null,
            if (historic.all { item -> item.categories.all { it == ConversationContextCategory.OPENING ||
                    it == ConversationContextCategory.PRESET || it == ConversationContextCategory.HISTORY_SUMMARY } })
                ConversationContextRequestState.SAVED_CONTENT else ConversationContextRequestState.HISTORICAL, historic))
    }
    return ConversationContextDetailsUiModel(requests.asReversed())
}

private fun openingContextItem() = ConversationContextItemUiModel("opening", null, listOf(ConversationContextCategory.OPENING), null, null, null)

private fun ConversationModelContextEntry.toUiItem(key: String, role: String?, location: String?, currentRequest: Boolean = false): ConversationContextItemUiModel {
    val source = payload.source
    val categories = when (source) {
        is ConversationContextSource.Disclosure -> when {
            source.reasons.isEmpty() -> listOf(ConversationContextCategory.HISTORICAL)
            source.reasons.values.all { it == ContextAdmissionReason.BASELINE_RESTORE } -> listOf(ConversationContextCategory.RESTORE)
            else -> source.reasons.keys.map { when (it) {
                DisclosureSection.MEMORY -> ConversationContextCategory.MEMORY
                DisclosureSection.SUB_ASSISTANTS -> ConversationContextCategory.ASSISTANTS
                DisclosureSection.ENTERPRISE_MEMORY_SEEDS -> ConversationContextCategory.ENTERPRISE_BACKGROUND
            } }
        }
        is ConversationContextSource.System -> listOf(ConversationContextCategory.SYSTEM)
        is ConversationContextSource.PromptRule -> listOf(ConversationContextCategory.PROMPT_RULE)
        is ConversationContextSource.MessageTime -> listOf(ConversationContextCategory.TIME)
        is ConversationContextSource.Attachment -> listOf(ConversationContextCategory.ATTACHMENT)
        ConversationContextSource.Starter -> listOf(ConversationContextCategory.OPENING)
        is ConversationContextSource.Preset -> listOf(ConversationContextCategory.PRESET)
        is ConversationContextSource.HistorySummary -> listOf(ConversationContextCategory.HISTORY_SUMMARY)
    }
    val name = when (source) {
        is ConversationContextSource.PromptRule -> source.definition.name
        is ConversationContextSource.Attachment -> source.name
        else -> null
    }
    val updated = if (currentRequest && source is ConversationContextSource.Disclosure) {
        source.reasons.filterValues { it == ContextAdmissionReason.EXTERNAL_STATE }.keys.map { it.contextCategory() }
    } else emptyList()
    return ConversationContextItemUiModel(key, id, categories, name, role, location, updated.isNotEmpty(), updated)
}
