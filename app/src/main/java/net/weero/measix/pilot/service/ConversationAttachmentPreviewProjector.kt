package net.weero.measix.pilot.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.attachments.AttachmentReferenceLookup
import net.weero.measix.pilot.data.ai.attachments.AttachmentReferenceTarget
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.LocalToolPath
import net.weero.measix.pilot.utils.JsonInstant
import net.weero.measix.pilot.service.runtime.ConversationPresentationSnapshot

/**
 * Query-side projection from attachment handles and disclosed upload paths to preview URLs.
 *
 * Preview resolution is suspend because ArtifactStore is the lifecycle owner: every local URL
 * and managed artifact is checked against ACTIVE metadata, the allowed root, MIME and signature
 * before a payload URL is returned. There is intentionally no snapshot-only cache because an
 * artifact can be deleted or replaced without changing the conversation snapshot.
 */
class ConversationAttachmentPreviewProjector(
    private val artifactStore: ArtifactStore,
) {
    /** Emits whenever managed upload metadata changes, invalidating any prior preview URL. */
    fun lifecycleChanges(): Flow<Unit> = artifactStore.observe().transform { emit(Unit) }

    suspend fun project(snapshot: ConversationPresentationSnapshot): Map<String, String> {
        val durable = projectMessages(snapshot.nodes.map { it.currentMessage })
        val active = snapshot.activeTurn ?: return durable
        val assistant = active.messages.lastOrNull { it.id == active.assistantMessageId }
            ?: return durable
        val overlay = projectMessages(listOf(assistant))
        return if (overlay.isEmpty()) durable else durable + overlay
    }

    private suspend fun projectMessages(messages: List<me.rerere.ai.ui.UIMessage>): Map<String, String> {
        val projected = LinkedHashMap<String, String>()
        for ((ref, target) in AttachmentReferenceLookup.index(messages).entries()) {
            val url = when (target) {
                is AttachmentReferenceTarget.MessagePart -> {
                    val part = target.part
                    val raw = when (part) {
                        is UIMessagePart.Image -> part.url
                        is UIMessagePart.Document -> part.url
                        is UIMessagePart.Audio -> part.url
                        is UIMessagePart.Video -> part.url
                        else -> null
                    }
                    raw?.takeIf { it.startsWith("file:", ignoreCase = true) }
                        ?.let(AttachmentRefs::parseFileUrl)
                        ?.let { file ->
                            try {
                                when (part) {
                                    is UIMessagePart.Image -> artifactStore.resolveImagePreviewForFile(file)
                                    is UIMessagePart.Document -> artifactStore.resolveMediaPreviewForFile(file, part.mime)
                                    is UIMessagePart.Audio -> artifactStore.resolveMediaPreviewForFile(file)
                                    is UIMessagePart.Video -> artifactStore.resolveMediaPreviewForFile(file)
                                    else -> null
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                null
                            }
                        }
                }

                is AttachmentReferenceTarget.ManagedArtifact -> resolveManagedPreview(target)

                AttachmentReferenceTarget.Conflict -> null
            }
            if (url != null) {
                projected[ref] = url
                val toolPath = when (target) {
                    is AttachmentReferenceTarget.ManagedArtifact -> target.artifact.toolPath()
                    is AttachmentReferenceTarget.MessagePart -> try {
                        AttachmentRefs.parseFileUrl(url)?.let { file ->
                            artifactStore.resolveManagedReference(file)?.toolPath()
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    AttachmentReferenceTarget.Conflict -> null
                }
                if (toolPath != null) projected[toolPath] = url
            }
        }
        // Tool input paths may intentionally reference a file absent from this conversation.
        // They are preview requests, not durable attachment roots or aliases.
        AttachmentRefs.walkMessageParts(messages).filterIsInstance<UIMessagePart.Tool>().forEach { tool ->
            if (tool.toolName != "inspect_attachments" && tool.toolName != "assistant_call") return@forEach
            val arguments = runCatching { JsonInstant.parseToJsonElement(tool.input) as? JsonObject }.getOrNull()
                ?: return@forEach
            val paths = arguments["attachments"] as? JsonArray ?: return@forEach
            for (value in paths) {
                val primitive = value as? JsonPrimitive ?: continue
                if (!primitive.isString) continue
                val path = primitive.content.trim()
                if (LocalToolPath.parseUploadToolPath(path) == null || path in projected) continue
                try {
                    val file = artifactStore.resolveToolPath(path) ?: continue
                    artifactStore.resolveImagePreviewForFile(file)?.let { projected[path] = it }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Malformed or no-longer-available paths have no preview.
                }
            }
        }
        return projected
    }

    private suspend fun resolveManagedPreview(target: AttachmentReferenceTarget.ManagedArtifact): String? {
        return try {
            if (target.type == "image") {
                artifactStore.resolveImagePreviewForArtifact(target.artifact)
            } else {
                artifactStore.resolveMediaPreviewForArtifact(target.artifact)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
}
