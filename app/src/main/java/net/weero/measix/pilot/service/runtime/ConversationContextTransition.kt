package net.weero.measix.pilot.service.runtime

import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.request.resolveUsesAt
import net.weero.measix.pilot.data.model.*
import net.weero.measix.pilot.data.model.ConversationContextIntegrity
import net.weero.measix.pilot.data.model.ContextMessageLocator
import net.weero.measix.pilot.data.model.ContextPlacement
import net.weero.measix.pilot.data.model.ConversationModelContextApplicability
import net.weero.measix.pilot.data.model.contextEntryIdentity
import me.rerere.ai.core.MessageRole
import kotlin.uuid.Uuid

/** Application input shares the conversation command transaction and active Turn ownership. */
internal object ConversationContextTransition {
    /** Retired request locations are removed; bodies still used by retained requests are handed over. */
    fun prune(
        snapshot: ConversationAggregateSnapshot,
        previous: ConversationAggregateSnapshot = snapshot,
    ): ConversationAggregateSnapshot {
        val messages = snapshot.nodes.flatMap { node -> node.messages.map {
            ContextMessageLocator(node.id, it.id) to it
        } }.toMap()
        val oldMessages = previous.nodes.flatMap { node -> node.messages.map {
            ContextMessageLocator(node.id, it.id) to it
        } }.toMap()
        val nodeOrder = snapshot.nodes.mapIndexed { index, node -> node.id to index }.toMap()
        val oldNodeOrder = previous.nodes.mapIndexed { index, node -> node.id to index }.toMap()
        val byId = snapshot.modelContextEntries.associateBy { it.id }
        fun steps(owner: ContextMessageLocator) = messages[owner]?.parts?.filterIsInstance<UIMessagePart.Step>().orEmpty()
        val surviving = snapshot.contextAdmissions.filter { admission ->
            steps(admission.owner).any { it.stepId == admission.stepId }
        }.sortedWith(compareBy({ nodeOrder.getValue(it.owner.nodeId) }, { it.owner.messageId.toString() }, { admission ->
            steps(admission.owner).single { it.stepId == admission.stepId }.ordinal
        }))
        val retained = mutableListOf<ConversationContextAdmission>()
        val relocatedSteps = mutableMapOf<Pair<ContextMessageLocator, ContextPlacement.BeforeStep>, ContextPlacement.BeforeStep>()
        surviving.forEach { admission ->
            val ownerIndex = nodeOrder.getValue(admission.owner.nodeId)
            val windowStart = if (admission.windowStart in messages) admission.windowStart else {
                val oldStart = oldNodeOrder[admission.windowStart.nodeId] ?: return@forEach
                snapshot.nodes.take(ownerIndex).firstOrNull { node ->
                    (oldNodeOrder[node.id] ?: -1) >= oldStart && node.currentMessage.role == MessageRole.USER
                }?.let { ContextMessageLocator(it.id, it.currentMessage.id) } ?: return@forEach
            }
            val currentSteps = steps(admission.owner)
            val oldSteps = oldMessages[admission.owner]?.parts?.filterIsInstance<UIMessagePart.Step>().orEmpty()
            val oldBoundaryExists = previous.contextAdmissions.any { it.id == admission.id } &&
                oldSteps.any { it.stepId == admission.stepId }
            val desired = if (oldBoundaryExists) resolveUsesAt(previous.contextAdmissions, admission.owner, admission.stepId, oldSteps)
                else resolveUsesAt(snapshot.contextAdmissions, admission.owner, admission.stepId, currentSteps)
            fun normalize(use: ConversationContextUse): ConversationContextUse = use.copy(placement = when (val placement = use.placement) {
                is ContextPlacement.BeforeStep -> if (currentSteps.any { it.stepId == placement.stepId }) placement
                    else relocatedSteps.getOrPut(admission.owner to placement) { ContextPlacement.BeforeStep(admission.stepId) }
                is ContextPlacement.BeforeMessage -> if (placement.message in messages) placement else ContextPlacement.Omitted
                is ContextPlacement.MessagePart -> if (placement.message in messages) placement else ContextPlacement.Omitted
                ContextPlacement.MessageOrigin -> byId[use.entryId]?.takeIf {
                    ConversationModelContextApplicability.stillExists(it, snapshot.nodes)
                }?.let { placement } ?: ContextPlacement.Omitted
                else -> placement
            })
            fun normalizeGroups(uses: List<ConversationContextUse>) = uses.map(::normalize).groupBy { it.entryId }.mapValues { (_, group) ->
                group.filterNot { it.placement == ContextPlacement.Omitted }.ifEmpty { listOf(group.first()) }
            }
            val ownGroups = normalizeGroups(admission.uses).toMutableMap()
            val provisional = admission.copy(windowStart = windowStart, uses = ownGroups.values.flatten())
            val actual = resolveUsesAt(retained + provisional, admission.owner, admission.stepId, currentSteps)
            val wantedGroups = normalizeGroups(desired.uses)
            val actualGroups = actual.uses.groupBy { it.entryId }
            // Materialize only the inherited groups whose carrier disappeared. Later empty seals
            // inherit this first retained baseline instead of repeating the complete request list.
            wantedGroups.forEach { (id, wanted) ->
                val closed = wanted.singleOrNull()?.placement == ContextPlacement.Omitted
                if (actualGroups[id] != wanted && (!closed || id in actualGroups)) ownGroups[id] = wanted
            }
            (actualGroups.keys - wantedGroups.keys).forEach { id ->
                ownGroups[id] = listOf(actualGroups.getValue(id).first().copy(placement = ContextPlacement.Omitted))
            }
            retained += provisional.copy(
                selection = admission.selection ?: desired.selection?.takeIf { it != actual.selection },
                uses = ownGroups.values.flatten(),
            )
        }
        fun hasCreationOwner(entry: ConversationModelContextEntry): Boolean =
            ConversationModelContextApplicability.stillExists(entry, snapshot.nodes) &&
                (entry.stepId == null || steps(ContextMessageLocator(entry.ownerNodeId, entry.ownerMessageId)).any { it.stepId == entry.stepId })
        val entries = snapshot.modelContextEntries.filter(::hasCreationOwner).toMutableList()
        val retired = snapshot.modelContextEntries.filterNot(::hasCreationOwner).associateBy { it.id }
        val replacements = mutableMapOf<Uuid, MutableList<ConversationModelContextEntry>>()
        fun transfer(id: Uuid, consumer: ConversationContextAdmission): Uuid? {
            val original = retired[id] ?: return id
            val oldSource = original.payload.source
            if (oldSource is ConversationContextSource.Preset || oldSource is ConversationContextSource.HistorySummary) return null
            val ownerIndex = nodeOrder.getValue(consumer.owner.nodeId)
            replacements[id]?.firstOrNull { replacement ->
                val index = nodeOrder.getValue(replacement.ownerNodeId)
                index < ownerIndex || index == ownerIndex && replacement.ownerMessageId == consumer.owner.messageId
            }?.let { return it.id }
            val anchorNode = snapshot.nodes.take(ownerIndex).lastOrNull { it.currentMessage.role == MessageRole.USER }
                ?: throw ConversationCommandConflictException("retained_context_requires_causal_user")
            val occurrence = (snapshot.modelContextEntries.plus(entries).filter { it.ownerNodeId == consumer.owner.nodeId &&
                it.ownerMessageId == consumer.owner.messageId }.maxOfOrNull { it.occurrence } ?: -1) + 1
            val replacement = original.copy(id = contextEntryIdentity(consumer.owner.nodeId, consumer.owner.messageId, occurrence),
                ownerNodeId = consumer.owner.nodeId, ownerMessageId = consumer.owner.messageId,
                anchorNodeId = anchorNode.id, anchorMessageId = anchorNode.currentMessage.id,
                occurrence = occurrence, stepId = consumer.stepId)
            entries += replacement
            replacements.getOrPut(id) { mutableListOf() } += replacement
            return replacement.id
        }
        // Active references choose a valid consumer first. Omitted markers never keep dead bodies alive.
        retained.forEach { admission ->
            admission.selection?.let { selection ->
                transfer(selection.systemEntryId, admission)
                selection.ruleEntryIds.forEach { transfer(it, admission) }
            }
            admission.uses.filterNot { it.placement == ContextPlacement.Omitted }.forEach { transfer(it.entryId, admission) }
        }
        val admissions = retained.map { admission ->
            admission.copy(uses = admission.uses.mapNotNull { use ->
                if (use.entryId in retired && use.placement == ContextPlacement.Omitted) {
                    val ownerIndex = nodeOrder.getValue(admission.owner.nodeId)
                    replacements[use.entryId]?.firstOrNull { replacement ->
                        val index = nodeOrder.getValue(replacement.ownerNodeId)
                        index < ownerIndex || index == ownerIndex && replacement.ownerMessageId == admission.owner.messageId &&
                            steps(admission.owner).single { it.stepId == replacement.stepId }.ordinal <=
                            steps(admission.owner).single { it.stepId == admission.stepId }.ordinal
                    }?.let { use.copy(entryId = it.id) }
                } else transfer(use.entryId, admission)?.let { use.copy(entryId = it) }
            }, selection = admission.selection?.let { selection -> selection.copy(
                systemEntryId = requireNotNull(transfer(selection.systemEntryId, admission)),
                ruleEntryIds = selection.ruleEntryIds.map { requireNotNull(transfer(it, admission)) },
            ) })
        }
        return snapshot.copy(modelContextEntries = entries, contextAdmissions = admissions)
    }

    fun admit(current: ConversationAggregateSnapshot, command: AdmitRequestContext): ConversationAggregateSnapshot {
        val admission = command.admission
        require(command.handle.conversationId == current.conversationId &&
            command.handle.assistantMessageId == admission.owner.messageId) { "context_admission_turn_mismatch" }
        val owner = current.nodes.singleOrNull { it.id == admission.owner.nodeId }?.currentMessage
        require(owner?.id == admission.owner.messageId) { "context_admission_unselected_owner" }
        val old = current.contextAdmissions.singleOrNull { it.id == admission.id }
        if (old != null) {
            if (old != admission || command.entries.any { candidate ->
                    current.modelContextEntries.singleOrNull { it.id == candidate.id } != candidate
                }) throw ConversationCommandConflictException("context_admission_already_committed_different_input")
            return current
        }
        val step = requireNotNull(owner).parts.filterIsInstance<UIMessagePart.Step>().lastOrNull()
        require(step?.stepId == admission.stepId && step.modelResult == null && step.outcome == null) {
            "context_admission_requires_unsampled_step"
        }
        require(command.entries.all { it.ownerNodeId == admission.owner.nodeId &&
            it.ownerMessageId == admission.owner.messageId && it.stepId == admission.stepId }) {
            "context_admission_entry_owner_mismatch"
        }
        val existing = current.modelContextEntries.associateBy { it.id }
        command.entries.forEach { candidate ->
            if (existing[candidate.id]?.let { it != candidate } == true) {
                throw ConversationCommandConflictException("context_entry_already_committed_different_input")
            }
        }
        val next = current.copy(
            modelContextEntries = current.modelContextEntries + command.entries.filter { it.id !in existing },
            contextAdmissions = current.contextAdmissions + admission,
        )
        ConversationContextIntegrity.validate(next.nodes, next.modelContextEntries, next.contextAdmissions,
            next.opening, next.header.scope)
        return next
    }
}
