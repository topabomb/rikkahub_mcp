package net.weero.measix.pilot.data.model

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.configuration.ConfigurationScope

/** Shared logical checks for command commits, persisted loads and backup validation. */
internal object ConversationContextIntegrity {
    fun validate(
        nodes: List<MessageNode>,
        entries: List<ConversationModelContextEntry>,
        admissions: List<ConversationContextAdmission>,
        opening: ConversationOpening?,
        scope: ConfigurationScope,
    ) {
        val nodeOrder = nodes.mapIndexed { index, node -> node.id to index }.toMap()
        require(nodeOrder.size == nodes.size) { "duplicate_context_node_identity" }
        val locatedMessages = nodes.flatMap { node -> node.messages.map { ContextMessageLocator(node.id, it.id) to it } }
        require(locatedMessages.map { it.second.id }.distinct().size == locatedMessages.size) { "duplicate_context_message_identity" }
        val messages = locatedMessages.toMap()
        val byId = entries.associateBy { it.id }
        require(byId.size == entries.size) { "duplicate_context_entry" }
        require(entries.map { Triple(it.ownerNodeId, it.ownerMessageId, it.occurrence) }.distinct().size == entries.size) {
            "duplicate_context_occurrence"
        }
        opening?.let {
            require(scope is ConfigurationScope.Enterprise && scope.authority == it.assistant.authority) {
                "opening_scope_mismatch"
            }
        }
        entries.forEach { entry ->
            require(entry.id == contextEntryIdentity(entry.ownerNodeId, entry.ownerMessageId, entry.occurrence)) {
                "context_entry_identity_mismatch: ${entry.id}"
            }
            val owner = requireNotNull(messages[ContextMessageLocator(entry.ownerNodeId, entry.ownerMessageId)]) {
                "context_owner_missing: ${entry.id}"
            }
            val anchor = requireNotNull(messages[ContextMessageLocator(entry.anchorNodeId, entry.anchorMessageId)]) {
                "context_anchor_missing: ${entry.id}"
            }
            val origin = entry.payload.source is ConversationContextSource.Preset ||
                entry.payload.source is ConversationContextSource.HistorySummary
            (entry.payload.body as? ConversationContextBody.MessageReference)?.let { body ->
                require(origin && body.message == ContextMessageLocator(entry.ownerNodeId, entry.ownerMessageId)) {
                    "context_message_reference_owner_mismatch"
                }
            }
            require(if (origin) entry.ownerNodeId == entry.anchorNodeId && owner.id == anchor.id
                else owner.role == MessageRole.ASSISTANT && anchor.role == MessageRole.USER) {
                "context_owner_role_mismatch: ${entry.id}"
            }
            require(origin || nodeOrder.getValue(entry.anchorNodeId) < nodeOrder.getValue(entry.ownerNodeId)) {
                "context_anchor_not_before_owner: ${entry.id}"
            }
            entry.stepId?.let { stepId ->
                require(owner.parts.filterIsInstance<UIMessagePart.Step>().any { it.stepId == stepId }) {
                    "context_creation_step_missing: ${entry.id}"
                }
            }
            if (entry.payload.source == ConversationContextSource.Starter) {
                requireNotNull(opening) { "context_opening_missing" }
            }
        }
        require(admissions.map { it.id }.distinct().size == admissions.size) { "duplicate_context_admission" }
        admissions.forEach { admission ->
            require(admission.id == contextAdmissionIdentity(admission.owner, admission.stepId)) {
                "context_admission_identity_mismatch"
            }
            val owner = requireNotNull(messages[admission.owner]) { "context_admission_owner_missing" }
            require(owner.role == MessageRole.ASSISTANT) { "context_admission_owner_role" }
            val steps = owner.parts.filterIsInstance<UIMessagePart.Step>()
            val step = requireNotNull(steps.singleOrNull { it.stepId == admission.stepId }) {
                "context_admission_step_missing"
            }
            fun requirePastMessage(locator: ContextMessageLocator, missing: String) {
                require(messages.containsKey(locator)) { missing }
                val index = nodeOrder.getValue(locator.nodeId)
                val ownerIndex = nodeOrder.getValue(admission.owner.nodeId)
                require(index < ownerIndex || index == ownerIndex && locator == admission.owner) {
                    "context_request_location_not_causal"
                }
            }
            fun requireKnownEntry(id: kotlin.uuid.Uuid): ConversationModelContextEntry {
                val entry = requireNotNull(byId[id]) { "context_contribution_missing" }
                val index = nodeOrder.getValue(entry.ownerNodeId)
                val ownerIndex = nodeOrder.getValue(admission.owner.nodeId)
                require(index < ownerIndex || index == ownerIndex && entry.ownerMessageId == admission.owner.messageId) {
                    "context_entry_not_on_request_history"
                }
                if (index == ownerIndex && entry.stepId != null) {
                    require(steps.single { it.stepId == entry.stepId }.ordinal <= step.ordinal) {
                        "context_entry_created_after_request"
                    }
                }
                return entry
            }
            requirePastMessage(admission.windowStart, "context_window_start_missing")
            admission.selection?.let { selection ->
                require(requireKnownEntry(selection.systemEntryId).payload.source is ConversationContextSource.System) {
                    "context_system_selection_missing"
                }
                selection.ruleEntryIds.forEach { id ->
                    require(requireKnownEntry(id).payload.source is ConversationContextSource.PromptRule) {
                        "context_rule_selection_missing"
                    }
                }
            }
            admission.uses.groupBy { it.entryId }.values.forEach { group ->
                require(group.none { it.placement == ContextPlacement.Omitted } || group.size == 1) {
                    "context_omitted_mixed_with_placements"
                }
            }
            admission.uses.forEach { use ->
                val entry = requireKnownEntry(use.entryId)
                when (val placement = use.placement) {
                    ContextPlacement.System -> require(entry.payload.source is ConversationContextSource.System && use.role == MessageRole.SYSTEM) {
                        "context_system_contribution_mismatch"
                    }
                    is ContextPlacement.BeforeMessage -> requirePastMessage(placement.message, "context_placement_message_missing")
                    is ContextPlacement.MessagePart -> {
                        requirePastMessage(placement.message, "context_placement_message_missing")
                        require(messages.getValue(placement.message).role == use.role) { "context_part_role_mismatch" }
                    }
                    ContextPlacement.Omitted -> Unit
                    is ContextPlacement.BeforeStep -> require(steps.any {
                        it.stepId == placement.stepId && it.ordinal <= step.ordinal
                    }) { "context_placement_step_missing" }
                    ContextPlacement.MessageOrigin -> require(
                        entry.payload.source is ConversationContextSource.Preset ||
                            entry.payload.source is ConversationContextSource.HistorySummary,
                    ) { "context_origin_contribution_mismatch" }
                }
            }
        }
        admissions.groupBy { it.owner }.forEach { (owner, requests) ->
            val order = messages.getValue(owner).parts.filterIsInstance<UIMessagePart.Step>().associate { it.stepId to it.ordinal }
            val selections = requests.sortedBy { order.getValue(it.stepId) }.mapNotNull { it.selection }
            require(selections.distinct().size <= 1) { "context_turn_selection_changed" }
            require(requests.minBy { order.getValue(it.stepId) }.selection != null) { "context_initial_selection_missing" }
        }
    }
}
