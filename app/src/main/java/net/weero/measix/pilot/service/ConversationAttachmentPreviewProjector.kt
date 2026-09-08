package net.weero.measix.pilot.service

import net.weero.measix.pilot.data.configuration.ConfigurationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.attachments.AttachmentReferenceLookup
import net.weero.measix.pilot.data.ai.attachments.AttachmentReferenceTarget
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import net.weero.measix.pilot.data.files.ArtifactMediaPreview
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.LocalToolPath
import net.weero.measix.pilot.utils.JsonInstant
import net.weero.measix.pilot.service.runtime.ConversationPresentationSnapshot

data class AttachmentPreview(val uri: String, val image: ImageSource?)

/**
 * Resolves attachment handles and disclosed upload paths through their original page and realm.
 * ArtifactStore validates publication, scope, root and MIME; image readers retain the validated ID
 * and page authority. Artifact lifecycle changes invalidate this projection independently of messages.
 */
class ConversationAttachmentPreviewProjector(
    private val artifactStore: ArtifactStore,
    private val files: FileManagementApplicationService,
) {
    /** Invalidates previews when attachment metadata or creation handoff changes. */
    fun lifecycleChanges(): Flow<Unit> = artifactStore.lifecycleChanges()

    suspend fun project(snapshot: ConversationPresentationSnapshot, source: ConversationViewLease): Map<String, AttachmentPreview> {
        source.requireOpen()
        check(snapshot.header.scope == source.access.scope &&
            (snapshot.conversationId == source.conversationId || snapshot.header.parentConversationId == source.conversationId)) {
            "attachment_preview_source_mismatch"
        }
        val durable = projectMessages(source, snapshot.nodes.map { it.currentMessage })
        val active = snapshot.stream ?: return durable
        val assistant = active.assistantMessage ?: return durable
        val overlay = projectMessages(source, listOf(assistant))
        return if (overlay.isEmpty()) durable else durable + overlay
    }

    private suspend fun projectMessages(source: ConversationViewLease, messages: List<me.rerere.ai.ui.UIMessage>): Map<String, AttachmentPreview> {
        val scope = source.access.scope
        val projected = LinkedHashMap<String, AttachmentPreview>()
        for ((ref, target) in AttachmentReferenceLookup.index(messages).entries()) {
            val resolved = when (target) {
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
                                    is UIMessagePart.Image -> artifactStore.resolveImagePreviewForFile(scope, file)
                                    is UIMessagePart.Document -> artifactStore.resolveMediaPreviewForFile(scope, file, part.mime)
                                    is UIMessagePart.Audio -> artifactStore.resolveMediaPreviewForFile(scope, file)
                                    is UIMessagePart.Video -> artifactStore.resolveMediaPreviewForFile(scope, file)
                                    else -> null
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                null
                            }
                        }
                }

                is AttachmentReferenceTarget.ManagedArtifact -> resolveManagedPreview(scope, target)

                AttachmentReferenceTarget.Conflict -> null
            }
            if (resolved != null) {
                val image = when (target) {
                    is AttachmentReferenceTarget.MessagePart -> target.part is UIMessagePart.Image
                    is AttachmentReferenceTarget.ManagedArtifact -> target.type == "image"
                    AttachmentReferenceTarget.Conflict -> false
                }
                val preview = AttachmentPreview(resolved.uri, if (image) files.conversationImageSource(source, resolved.artifactId) else null)
                projected[ref] = preview
                val toolPath = when (target) {
                    is AttachmentReferenceTarget.ManagedArtifact -> target.artifact.toolPath()
                    is AttachmentReferenceTarget.MessagePart -> try {
                        AttachmentRefs.parseFileUrl(resolved.uri)?.let { file ->
                            artifactStore.resolveManagedReference(file)?.toolPath()
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    AttachmentReferenceTarget.Conflict -> null
                }
                if (toolPath != null) projected[toolPath] = preview
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
                    artifactStore.resolveImagePreviewForFile(scope, file)?.let {
                        projected[path] = AttachmentPreview(it.uri, files.conversationImageSource(source, it.artifactId))
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Malformed or no-longer-available paths have no preview.
                }
            }
        }
        return projected
    }

    private suspend fun resolveManagedPreview(scope: ConfigurationScope, target: AttachmentReferenceTarget.ManagedArtifact): ArtifactMediaPreview? {
        return try {
            if (target.type == "image") {
                artifactStore.resolveImagePreviewForArtifact(scope, target.artifact)
            } else {
                artifactStore.resolveMediaPreviewForArtifact(scope, target.artifact)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
}
