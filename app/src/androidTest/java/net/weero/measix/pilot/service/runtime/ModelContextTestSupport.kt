package net.weero.measix.pilot.service.runtime

import net.weero.measix.pilot.data.model.ConversationContextBody
import net.weero.measix.pilot.data.model.ConversationContextPayload
import net.weero.measix.pilot.data.model.ConversationContextSource

/** Historical bytes are wrapped as provenance, never upgraded to the current wire format. */
internal fun disclosurePayload(content: String): ConversationContextPayload = ConversationContextPayload(
    source = ConversationContextSource.Disclosure(namespace = null),
    body = ConversationContextBody.Inline(content),
)

internal fun net.weero.measix.pilot.data.model.ConversationModelContextEntry.inlineContextText(): String =
    (payload.body as ConversationContextBody.Inline).text

internal fun net.weero.measix.pilot.data.db.entity.ConversationModelContextEntity.inlineContextText(): String =
    (net.weero.measix.pilot.data.model.ConversationContextCodec.decode(payload).body as ConversationContextBody.Inline).text

internal fun historicalContextRow(
    ownerNodeId: String,
    ownerMessageId: String,
    anchorNodeId: String,
    anchorMessageId: String,
    content: String,
): net.weero.measix.pilot.data.db.entity.ConversationModelContextEntity =
    net.weero.measix.pilot.data.db.entity.ConversationModelContextEntity(
        id = java.util.UUID.nameUUIDFromBytes("conversation-context/$ownerNodeId/$ownerMessageId/0".encodeToByteArray()).toString(),
        ownerNodeId = ownerNodeId,
        ownerMessageId = ownerMessageId,
        anchorNodeId = anchorNodeId,
        anchorMessageId = anchorMessageId,
        occurrence = 0,
        stepId = null,
        sourceKind = "disclosure",
        payload = net.weero.measix.pilot.data.model.ConversationContextCodec.encode(disclosurePayload(content)),
    )
