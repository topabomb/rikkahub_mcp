package net.weero.measix.pilot.data.ai.attachments

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.subassistant.getSubAssistantCallMetadata
import net.weero.measix.pilot.data.files.LocalArtifactRef
import net.weero.measix.pilot.utils.JsonInstant

/** The two durable source shapes behind a stable attachment handle. */
sealed interface AttachmentReferenceTarget {
    data class MessagePart(val part: UIMessagePart) : AttachmentReferenceTarget
    data class ManagedArtifact(val artifact: LocalArtifactRef, val type: String? = null) : AttachmentReferenceTarget
    /** A stable ref was claimed by incompatible durable sources; resolution must fail closed. */
    data object Conflict : AttachmentReferenceTarget
}

/**
 * Single pure lookup for `attachment:<uuid>` across ordinary message parts and sub-assistant
 * deliverable metadata. Execution and UI projection must resolve the same durable handle shape.
 */
object AttachmentReferenceLookup {
    fun index(messages: List<UIMessage>): AttachmentReferenceIndex {
        val direct = LinkedHashMap<String, AttachmentReferenceTarget.MessagePart>()
        val directConflicts = HashSet<String>()
        val managed = LinkedHashMap<String, AttachmentReferenceTarget.ManagedArtifact>()
        val managedConflicts = HashSet<String>()
        fun registerDirect(ref: String, target: AttachmentReferenceTarget.MessagePart) {
            val previous = direct[ref]
            if (previous == null) {
                direct[ref] = target
            } else if (!sameDirectResource(previous.part, target.part)) {
                directConflicts += ref
            }
        }
        fun registerManaged(ref: String, target: AttachmentReferenceTarget.ManagedArtifact) {
            val previous = managed[ref]
            if (previous == null) {
                managed[ref] = target
            } else if (previous != target) {
                managedConflicts += ref
            }
        }
        AttachmentRefs.walkMessageParts(messages).forEach partLoop@ { part ->
            if (AttachmentRefs.isMultimedia(part)) {
                AttachmentRefs.getStableRef(part)
                    ?.let { ref -> registerDirect(ref, AttachmentReferenceTarget.MessagePart(part)) }
            }
            if (part !is UIMessagePart.Tool) return@partLoop
            part.getSubAssistantCallMetadata(JsonInstant)?.artifacts.orEmpty().forEach artifactLoop@ { item ->
                val ref = AttachmentRefs.parse(item.ref)?.let(AttachmentRefs::format) ?: return@artifactLoop
                item.artifact?.let { artifact ->
                    registerManaged(ref, AttachmentReferenceTarget.ManagedArtifact(artifact, item.type))
                }
            }
        }
        val targets = LinkedHashMap<String, AttachmentReferenceTarget>()
        (direct.keys + managed.keys).forEach { ref ->
            targets[ref] = when {
                ref in directConflicts || (ref !in direct && ref in managedConflicts) ->
                    AttachmentReferenceTarget.Conflict
                ref in direct -> direct.getValue(ref)
                else -> managed.getValue(ref)
            }
        }
        return AttachmentReferenceIndex(targets)
    }

    private fun sameDirectResource(left: UIMessagePart, right: UIMessagePart): Boolean {
        if (left::class != right::class) return false
        val leftUrl = when (left) {
            is UIMessagePart.Image -> left.url
            is UIMessagePart.Document -> left.url
            is UIMessagePart.Audio -> left.url
            is UIMessagePart.Video -> left.url
            else -> return false
        }
        val rightUrl = when (right) {
            is UIMessagePart.Image -> right.url
            is UIMessagePart.Document -> right.url
            is UIMessagePart.Audio -> right.url
            is UIMessagePart.Video -> right.url
            else -> return false
        }
        return canonicalResourceUrl(leftUrl) == canonicalResourceUrl(rightUrl)
    }

    private fun canonicalResourceUrl(url: String): String =
        if (url.startsWith("file:", ignoreCase = true)) {
            AttachmentRefs.parseFileUrl(url)?.let { file ->
                runCatching { file.canonicalPath }.getOrNull()
            } ?: url.trim()
        } else {
            url.trim()
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
