package net.weero.measix.pilot.data.ai.attachments

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.subassistant.getSubAssistantCallMetadata
import net.weero.measix.pilot.data.files.LocalArtifactRef
import net.weero.measix.pilot.utils.JsonInstant

/** The two durable source shapes behind a stable attachment handle. */
sealed interface AttachmentReferenceTarget {
    data class MessagePart(val part: UIMessagePart) : AttachmentReferenceTarget
    data class ManagedArtifact(val artifact: LocalArtifactRef) : AttachmentReferenceTarget
}

/**
 * Single pure lookup for `attachment:<uuid>` across ordinary message parts and sub-assistant
 * deliverable metadata. Execution and UI projection must resolve the same durable handle shape.
 */
object AttachmentReferenceLookup {
    fun index(messages: List<UIMessage>): AttachmentReferenceIndex {
        val direct = LinkedHashMap<String, AttachmentReferenceTarget.MessagePart>()
        val managed = LinkedHashMap<String, AttachmentReferenceTarget.ManagedArtifact>()
        AttachmentRefs.walkMessageParts(messages).forEach partLoop@ { part ->
            AttachmentRefs.getRef(part)
                ?.let(AttachmentRefs::parse)
                ?.let(AttachmentRefs::format)
                ?.let { ref -> direct.putIfAbsent(ref, AttachmentReferenceTarget.MessagePart(part)) }
            if (part !is UIMessagePart.Tool) return@partLoop
            part.getSubAssistantCallMetadata(JsonInstant)?.artifacts.orEmpty().forEach artifactLoop@ { item ->
                val ref = AttachmentRefs.parse(item.ref)?.let(AttachmentRefs::format) ?: return@artifactLoop
                item.artifact?.let { artifact ->
                    managed.putIfAbsent(ref, AttachmentReferenceTarget.ManagedArtifact(artifact))
                }
            }
        }
        return AttachmentReferenceIndex(managed + direct)
    }
}

/** Immutable request/query projection: one message walk followed by O(1) handle lookup. */
class AttachmentReferenceIndex internal constructor(
    private val targets: Map<String, AttachmentReferenceTarget>,
) {
    operator fun get(rawRef: String): AttachmentReferenceTarget? {
        val normalized = AttachmentRefs.parse(rawRef)?.let(AttachmentRefs::format) ?: return null
        return targets[normalized]
    }

    internal fun entries(): Set<Map.Entry<String, AttachmentReferenceTarget>> = targets.entries
}
