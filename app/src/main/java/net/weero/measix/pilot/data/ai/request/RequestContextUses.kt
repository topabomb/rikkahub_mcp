package net.weero.measix.pilot.data.ai.request

import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.model.*
import net.weero.measix.pilot.service.runtime.ConversationAggregateSnapshot

/** The explicit contributions of one Turn at its saved request boundary. */
internal data class LocatedContextUse(
    val owner: ContextMessageLocator,
    val use: ConversationContextUse,
)

internal fun resolveRequestContextUses(
    snapshot: ConversationAggregateSnapshot,
    admission: ConversationContextAdmission,
): List<LocatedContextUse> {
    val branch = snapshot.nodes
    val end = branch.indexOfFirst { it.id == admission.owner.nodeId && it.currentMessage.id == admission.owner.messageId }
    require(end >= 0) { "context_request_owner_off_branch" }
    val start = branch.indexOfFirst { it.id == admission.windowStart.nodeId && it.messages.any { message -> message.id == admission.windowStart.messageId } }
    require(start in 0..end) { "context_request_window_off_branch" }
    val steps = branch[end].currentMessage.parts.filterIsInstance<UIMessagePart.Step>()
    val boundary = steps.single { it.stepId == admission.stepId }
    val resolved = resolveUsesAt(snapshot.contextAdmissions, admission.owner, admission.stepId, steps)
    val entries = snapshot.modelContextEntries.associateBy { it.id }
    // Historical USER variants remain readable even after the user selects or edits another one.
    // The admission does not record all earlier Assistant selections, so never reconstruct those
    // requests from today's branch and attribute their contributions to this request.
    val visible = branch.subList(start, end + 1).associateBy { it.id }
    fun saved(locator: ContextMessageLocator) = visible[locator.nodeId]?.messages?.any { it.id == locator.messageId } == true
    fun retained(owner: ContextMessageLocator, use: ConversationContextUse): Boolean = when (val placement = use.placement) {
        ContextPlacement.System -> true
        is ContextPlacement.BeforeMessage -> saved(placement.message)
        is ContextPlacement.MessagePart -> saved(placement.message)
        is ContextPlacement.BeforeStep -> visible[owner.nodeId]?.messages?.singleOrNull { it.id == owner.messageId }?.parts?.any {
            it is UIMessagePart.Step && it.stepId == placement.stepId && (owner != admission.owner || it.ordinal <= boundary.ordinal)
        } == true
        ContextPlacement.MessageOrigin -> saved(owner)
        ContextPlacement.Omitted -> false
    }
    val result = mutableListOf<LocatedContextUse>()
    resolved.uses.forEach { use ->
        val entry = requireNotNull(entries[use.entryId]) { "context_contribution_missing: ${use.entryId}" }
        val selected = when (entry.payload.source) {
            is ConversationContextSource.System -> use.entryId == resolved.selection?.systemEntryId
            is ConversationContextSource.PromptRule -> use.entryId in resolved.selection?.ruleEntryIds.orEmpty()
            is ConversationContextSource.MessageTime -> resolved.selection?.timeReminderEnabled == true
            is ConversationContextSource.Disclosure -> {
                val source = entry.payload.source
                val namespace = resolved.selection?.disclosureNamespace
                source.namespace != null && namespace != null && source.reasons.isNotEmpty() &&
                    source.reasons.keys.all { section -> when (section) {
                        DisclosureSection.MEMORY -> source.namespace.memoryOwner == namespace.memoryOwner
                        DisclosureSection.SUB_ASSISTANTS, DisclosureSection.ENTERPRISE_MEMORY_SEEDS -> source.namespace.caller == namespace.caller
                    } }
            }
            else -> true
        }
        if (selected && retained(admission.owner, use)) result += LocatedContextUse(admission.owner, use)
    }
    return result.distinctBy { it.use }
}
