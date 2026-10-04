package net.weero.measix.pilot.data.ai.request

import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.mergeMessageMetadata
import net.weero.measix.pilot.data.model.ContextMessageLocator
import net.weero.measix.pilot.data.model.ContextPlacement
import java.util.UUID

/** Per-Step protocol state is the only safe metadata source when a message is split. */
internal fun metadataForParts(parts: List<UIMessagePart>): JsonObject? = parts
    .filterIsInstance<UIMessagePart.Step>().fold(null as JsonObject?) { metadata, step ->
        mergeMessageMetadata(metadata, step.modelResult?.providerMetadata)
    }

internal fun projectAdmittedContext(
    messages: List<UIMessage>,
    projections: List<ModelContextProjection>,
    origins: Map<kotlin.uuid.Uuid, RequestMessageOrigin>,
    onProjected: (ModelContextProjection, UIMessagePart.Text, kotlin.uuid.Uuid, Boolean) -> Unit,
): List<UIMessage> {
    if (projections.isEmpty()) return messages.filter { it.isValidToUpload() }
    val before = linkedMapOf<kotlin.uuid.Uuid, MutableList<ModelContextProjection>>()
    val parts = linkedMapOf<kotlin.uuid.Uuid, MutableList<ModelContextProjection>>()
    val steps = linkedMapOf<kotlin.uuid.Uuid, MutableList<ModelContextProjection>>()
    val system = mutableListOf<ModelContextProjection>()
    fun requireDurable(locator: ContextMessageLocator) {
        val source = origins[locator.messageId] as? RequestMessageOrigin.Durable
        check(source?.locator == DurableMessageLocator(locator.nodeId, locator.messageId)) {
            "context_placement_not_durable: $locator"
        }
        check(messages.count { it.id == locator.messageId } == 1) { "context_placement_message_missing_or_duplicated: $locator" }
    }
    projections.forEach { projection ->
        when (val placement = projection.placement) {
            ContextPlacement.System -> system += projection
            is ContextPlacement.BeforeMessage -> {
                requireDurable(placement.message)
                before.getOrPut(placement.message.messageId) { mutableListOf() } += projection
            }
            is ContextPlacement.MessagePart -> {
                requireDurable(placement.message)
                val message = messages.single { it.id == placement.message.messageId }
                check(projection.role == message.role && placement.partIndex <= message.parts.size) { "context_part_placement_invalid" }
                parts.getOrPut(placement.message.messageId) { mutableListOf() } += projection
            }
            is ContextPlacement.BeforeStep -> {
                requireDurable(projection.owner)
                val message = messages.single { it.id == projection.owner.messageId }
                check(message.role == MessageRole.ASSISTANT && message.parts.count {
                    it is UIMessagePart.Step && it.stepId == placement.stepId } == 1) { "context_placement_step_missing" }
                steps.getOrPut(projection.owner.messageId) { mutableListOf() } += projection
            }
            ContextPlacement.MessageOrigin, ContextPlacement.Omitted -> Unit
        }
    }
    fun rendered(projection: ModelContextProjection, containerId: kotlin.uuid.Uuid, synthetic: Boolean): UIMessagePart.Text =
        UIMessagePart.Text(renderContextModelText(projection.source, projection.content, projection.payloadVersion)).also { onProjected(projection, it, containerId, synthetic) }
    fun synthetic(projection: ModelContextProjection): UIMessage {
        val id = kotlin.uuid.Uuid.parse(UUID.nameUUIDFromBytes(
            "request-context/${projection.entryId}/${projection.owner}/${projection.role}/${projection.placement}".encodeToByteArray()).toString())
        return UIMessage(id = id, role = projection.role, parts = listOf(rendered(projection, id, true)))
    }
    return buildList {
        addAll(system.map(::synthetic))
        messages.forEach { message ->
            val prefix = before[message.id].orEmpty()
            // Same-role USER context can share a container without pretending to be user-authored.
            // ASSISTANT/opaque containers must remain distinct or their serializer can drop the text.
            val inlinePrefix = prefix.takeIf { message.role == MessageRole.USER && it.all { p -> p.role == message.role } }.orEmpty()
            if (inlinePrefix.isEmpty()) addAll(prefix.map(::synthetic))
            val insertions = parts[message.id].orEmpty().groupBy { (it.placement as ContextPlacement.MessagePart).partIndex }
            val stepInsertions = steps[message.id].orEmpty().groupBy { (it.placement as ContextPlacement.BeforeStep).stepId }
            if (stepInsertions.isEmpty()) {
                val renderedParts = buildList {
                    addAll(inlinePrefix.map { rendered(it, message.id, false) })
                    message.parts.forEachIndexed { index, part ->
                        addAll(insertions[index].orEmpty().map { rendered(it, message.id, false) })
                        add(part)
                    }
                    addAll(insertions[message.parts.size].orEmpty().map { rendered(it, message.id, false) })
                }
                val output = message.copy(parts = renderedParts, providerReplayProjection = message.providerReplayProjection?.let { replay ->
                    replay.copy(completePartCount = replay.completePartCount + inlinePrefix.size +
                        insertions.filterKeys { it < replay.completePartCount }.values.sumOf { it.size })
                })
                if (output.isValidToUpload()) add(output)
            } else {
                check(insertions.isEmpty() && inlinePrefix.isEmpty()) { "context_mixed_assistant_part_and_step_placement" }
                var start = 0
                fun appendSegment(end: Int) {
                    if (end <= start) return
                    val segment = message.parts.subList(start, end)
                    val metadata = metadataForParts(segment)
                    check(message.providerMetadata == null || metadata != null || segment.none { it is UIMessagePart.Step && it.modelResult != null }) {
                        "context_split_missing_step_protocol_state"
                    }
                    val replay = message.providerReplayProjection?.let { original -> original.copy(
                        completePartCount = (original.completePartCount - start).coerceIn(0, segment.size),
                        hasIncompleteTail = end > original.completePartCount && original.hasIncompleteTail,
                    ) }
                    val copy = message.copy(parts = segment, providerMetadata = metadata, providerReplayProjection = replay)
                    if (copy.isValidToUpload()) add(copy)
                }
                message.parts.forEachIndexed { index, part ->
                    val injected = (part as? UIMessagePart.Step)?.let { stepInsertions[it.stepId] }.orEmpty()
                    if (injected.isNotEmpty()) {
                        appendSegment(index)
                        addAll(injected.map(::synthetic))
                        start = index
                    }
                }
                appendSegment(message.parts.size)
            }
        }
    }
}
