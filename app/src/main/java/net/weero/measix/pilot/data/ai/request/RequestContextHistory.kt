package net.weero.measix.pilot.data.ai.request

import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.model.ContextPlacement
import net.weero.measix.pilot.data.model.ContextMessageLocator
import net.weero.measix.pilot.data.model.ConversationContextAdmission
import net.weero.measix.pilot.data.model.ConversationContextUse
import net.weero.measix.pilot.data.model.TurnContextSelection
import kotlin.uuid.Uuid

internal data class ResolvedContextUses(
    val selection: TurnContextSelection?,
    val uses: List<ConversationContextUse>,
)

/** An empty delta inherits the preceding request; a new owner starts its own selection. */
internal fun resolveUsesAt(
    admissions: List<ConversationContextAdmission>,
    owner: ContextMessageLocator,
    stepId: Uuid,
    steps: List<UIMessagePart.Step>,
    includeOmitted: Boolean = false,
): ResolvedContextUses {
    val order = steps.associate { it.stepId to it.ordinal }
    val cutoff = requireNotNull(order[stepId]) { "context_request_step_missing" }
    var selection: TurnContextSelection? = null
    val byEntry = linkedMapOf<Uuid, List<ConversationContextUse>>()
    admissions.filter { it.owner == owner && (order[it.stepId] ?: Int.MAX_VALUE) <= cutoff }
        .sortedBy { order.getValue(it.stepId) }.forEach { admission ->
            admission.selection?.let { selection = it }
            admission.uses.groupBy { it.entryId }.forEach { (id, uses) -> byEntry[id] = uses }
        }
    return ResolvedContextUses(selection, byEntry.values.flatten().filter { includeOmitted || it.placement != ContextPlacement.Omitted })
}
