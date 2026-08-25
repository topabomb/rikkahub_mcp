package net.weero.measix.pilot.service

import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.attachments.AttachmentReferenceLookup
import net.weero.measix.pilot.data.ai.attachments.AttachmentReferenceTarget
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.service.runtime.ConversationSnapshot
import kotlin.uuid.Uuid

/**
 * Query-side indexed projection from stable attachment handles to local preview URLs.
 * Durable nodes are indexed once per structural version; streaming only indexes the active turn
 * overlay, so Compose performs O(1) lookups and never walks conversation history per card.
 */
class ConversationAttachmentPreviewProjector(
    private val artifactStore: ArtifactStore,
) {
    private data class DurableProjection(
        val nodesIdentity: Any,
        val previews: Map<String, String>,
    )

    private val durableCache = object : LinkedHashMap<Uuid, DurableProjection>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Uuid, DurableProjection>?): Boolean = size > 32
    }

    fun project(snapshot: ConversationSnapshot): Map<String, String> {
        val durable = synchronized(durableCache) {
            durableCache[snapshot.conversationId]
                ?.takeIf { it.nodesIdentity === snapshot.nodes }
                ?: DurableProjection(
                    nodesIdentity = snapshot.nodes,
                    previews = projectMessages(snapshot.nodes.map { it.currentMessage }),
                ).also { durableCache[snapshot.conversationId] = it }
        }
        val active = snapshot.activeTurn ?: return durable.previews
        val assistant = active.messages.lastOrNull { it.id == active.assistantMessageId }
            ?: return durable.previews
        val overlay = projectMessages(listOf(assistant))
        return if (overlay.isEmpty()) durable.previews else durable.previews + overlay
    }

    private fun projectMessages(messages: List<me.rerere.ai.ui.UIMessage>): Map<String, String> =
        AttachmentReferenceLookup.index(messages).entries().mapNotNull { (ref, target) ->
            val url = when (target) {
                is AttachmentReferenceTarget.MessagePart -> (target.part as? UIMessagePart.Image)
                    ?.url
                    ?.takeIf { it.startsWith("file:", ignoreCase = true) }

                is AttachmentReferenceTarget.ManagedArtifact -> runCatching {
                    AttachmentRefs.fileToFileUrl(artifactStore.file(target.artifact))
                }.getOrNull()
            }
            url?.let { ref to it }
        }.toMap()
}
