package net.weero.measix.pilot.data.model

import java.util.UUID
import kotlin.uuid.Uuid

/** A committed request boundary, including the case where it adds no application content. */
internal data class ConversationContextAdmission(
    val owner: ContextMessageLocator,
    val stepId: Uuid,
    val windowStart: ContextMessageLocator,
    val selection: TurnContextSelection?,
    val uses: List<ConversationContextUse>,
    val id: Uuid = contextAdmissionIdentity(owner, stepId),
)

internal fun contextAdmissionIdentity(owner: ContextMessageLocator, stepId: Uuid): Uuid =
    Uuid.parse(UUID.nameUUIDFromBytes(
        "conversation-request/${owner.nodeId}/${owner.messageId}/$stepId".encodeToByteArray(),
    ).toString())

/** Clone identities are scoped to the new nodes; Step IDs remain inside their copied message. */
internal fun remapContextAdmissionsForClone(
    admissions: List<ConversationContextAdmission>,
    entries: List<ConversationModelContextEntry>,
    nodeIdMap: Map<Uuid, Uuid>,
    messageIdMap: Map<Uuid, Uuid>,
): List<ConversationContextAdmission> {
    require(nodeIdMap.values.distinct().size == nodeIdMap.size) { "cloned_context_node_identity_collision" }
    require(messageIdMap.values.distinct().size == messageIdMap.size) { "cloned_context_message_identity_collision" }
    fun ContextMessageLocator.remap(): ContextMessageLocator = ContextMessageLocator(
        requireNotNull(nodeIdMap[nodeId]) { "cloned_context_locator_missing: $nodeId" },
        messageIdMap[messageId] ?: messageId,
    )
    val entryIds = entries.filter { it.ownerNodeId in nodeIdMap && it.anchorNodeId in nodeIdMap }.associate { entry ->
        entry.id to contextEntryIdentity(nodeIdMap.getValue(entry.ownerNodeId),
            messageIdMap[entry.ownerMessageId] ?: entry.ownerMessageId, entry.occurrence)
    }
    return admissions.filter { it.owner.nodeId in nodeIdMap }.map { admission ->
        val owner = admission.owner.remap()
        admission.copy(
            id = contextAdmissionIdentity(owner, admission.stepId),
            owner = owner,
            windowStart = admission.windowStart.remap(),
            selection = admission.selection?.let { selection -> selection.copy(
                systemEntryId = entryIds.getValue(selection.systemEntryId),
                ruleEntryIds = selection.ruleEntryIds.map(entryIds::getValue),
            ) },
            uses = admission.uses.map { use -> use.copy(
                entryId = entryIds.getValue(use.entryId),
                placement = when (val placement = use.placement) {
                    is ContextPlacement.BeforeMessage -> placement.copy(message = placement.message.remap())
                    is ContextPlacement.MessagePart -> placement.copy(message = placement.message.remap())
                    else -> placement
                },
            ) },
        )
    }
}
