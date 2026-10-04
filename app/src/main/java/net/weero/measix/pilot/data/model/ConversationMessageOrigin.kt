package net.weero.measix.pilot.data.model

/** Application-authored history points at its immutable variant instead of duplicating its body. */
internal fun messageOriginEntry(node: MessageNode, source: ConversationContextSource): ConversationModelContextEntry {
    require(source is ConversationContextSource.Preset || source is ConversationContextSource.HistorySummary) {
        "context_message_origin_source_required"
    }
    val locator = ContextMessageLocator(node.id, node.currentMessage.id)
    return ConversationModelContextEntry(node.id, locator.messageId, node.id, locator.messageId,
        ConversationContextPayload(version = 2, source = source, body = ConversationContextBody.MessageReference(locator)))
}
