package net.weero.measix.pilot.data.files

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.buildSubAssistantArtifactManifest
import net.weero.measix.pilot.data.ai.subassistant.getSubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.mergeSubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.tools.ATTACHMENT_INSPECTION_TOOL_NAME
import net.weero.measix.pilot.utils.JsonInstant
import java.io.File

/**
 * 附件克隆唯一实现，供 Master fork 与 Child clone 共用。
 *
 * 语义：已由 ArtifactStore 登记且仍为 ACTIVE 的 `file:` 附件复制为新文件（内容级复制，
 * 脱离原会话的 GC 生命周期）；未受管或失效路径原样保留且不会被读取。Tool part 递归克隆
 * 其 output。[ToolArtifactRewriter] 识别并重写 artifact 引用，使产物归属新会话。
 */
internal object AttachmentCloner {

    suspend fun clonePart(
        part: UIMessagePart,
        artifactStore: ArtifactStore,
        createdArtifacts: MutableList<OwnedArtifact>,
        toolArtifactRewriter: ToolArtifactRewriter,
    ): UIMessagePart {
        return clonePartInternal(
            part = part,
            artifactStore = artifactStore,
            createdArtifacts = createdArtifacts,
            toolArtifactRewriter = toolArtifactRewriter,
            copiedArtifacts = linkedMapOf(),
        )
    }

    private suspend fun clonePartInternal(
        part: UIMessagePart,
        artifactStore: ArtifactStore,
        createdArtifacts: MutableList<OwnedArtifact>,
        toolArtifactRewriter: ToolArtifactRewriter,
        copiedArtifacts: MutableMap<String, OwnedArtifact>,
    ): UIMessagePart {
        suspend fun copyUrl(url: String): String {
            if (!url.startsWith("file:", ignoreCase = true)) return url
            val source = AttachmentRefs.parseFileUrl(url)
            val sourceKey = source?.let(::canonicalPath)
            sourceKey?.let { copiedArtifacts[it] }?.let { copied ->
                return copied.uri.toString()
            }
            val managed = artifactStore.resolveManagedReference(source ?: return url) ?: return url
            val sourceFile = artifactStore.file(managed)
            val owned = artifactStore.copyFilePreservingOrigin(
                source = sourceFile,
                mimeType = managed.mimeType,
                displayName = sourceFile.name,
            )
            sourceKey?.let { copiedArtifacts[it] = owned }
            createdArtifacts += owned
            return owned.uri.toString()
        }
        return when (part) {
            is UIMessagePart.Image -> part.copy(url = copyUrl(part.url))
            is UIMessagePart.Document -> part.copy(url = copyUrl(part.url))
            is UIMessagePart.Video -> part.copy(url = copyUrl(part.url))
            is UIMessagePart.Audio -> part.copy(url = copyUrl(part.url))
            is UIMessagePart.Tool -> {
                val subAssistantMetadata = part.getSubAssistantCallMetadata(JsonInstant)
                val cloned = if (subAssistantMetadata != null && subAssistantMetadata.artifacts.isNotEmpty()) {
                    cloneSubAssistantTool(
                        part = part,
                        metadata = subAssistantMetadata,
                        artifactStore = artifactStore,
                        createdArtifacts = createdArtifacts,
                        toolArtifactRewriter = toolArtifactRewriter,
                        copiedArtifacts = copiedArtifacts,
                    )
                } else {
                    val sourceRef = part.metadata?.let(toolArtifactRewriter::decodeArtifactRef)
                    if (sourceRef != null) {
                        val rewritten = toolArtifactRewriter.rewriteToolOutput(
                            output = part.output,
                            metadata = part.metadata,
                            copiedArtifacts = copiedArtifacts,
                        )
                        rewritten.ownedArtifact?.let(createdArtifacts::add)
                        part.copy(output = rewritten.output, metadata = rewritten.metadata)
                    } else {
                        part.copy(
                            output = clonePartsInternal(
                                part.output,
                                artifactStore,
                                createdArtifacts,
                                toolArtifactRewriter,
                                copiedArtifacts,
                            ),
                        )
                    }
                }
                rebindAttachmentInput(cloned, artifactStore, copiedArtifacts)
            }
            else -> part
        }
    }

    /** Known attachment inputs follow files already copied by this clone, never create owners. */
    private suspend fun rebindAttachmentInput(
        tool: UIMessagePart.Tool,
        artifactStore: ArtifactStore,
        copiedArtifacts: Map<String, OwnedArtifact>,
    ): UIMessagePart.Tool {
        if (tool.toolName != ATTACHMENT_INSPECTION_TOOL_NAME && tool.toolName != "assistant_call") return tool
        if (copiedArtifacts.isEmpty()) return tool
        val input = runCatching { JsonInstant.parseToJsonElement(tool.input) as? JsonObject }.getOrNull()
            ?: return tool
        val attachments = input["attachments"] as? JsonArray ?: return tool
        if (attachments.any { it !is JsonPrimitive || !it.isString }) return tool
        val rebound = attachments.map { value ->
            val path = (value as JsonPrimitive).content
            if (LocalToolPath.parseUploadToolPath(path) == null) return@map value
            val source = artifactStore.resolveToolPath(path) ?: return@map value
            val copied = copiedArtifacts[canonicalPath(source)] ?: return@map value
            val copiedPath = copied.localRef.toolPath() ?: return@map value
            JsonPrimitive(copiedPath)
        }
        if (rebound == attachments) return tool
        return tool.copy(
            input = JsonObject(input.toMutableMap().apply {
                put("attachments", JsonArray(rebound))
            }).toString(),
        )
    }

    suspend fun cloneParts(
        parts: List<UIMessagePart>,
        artifactStore: ArtifactStore,
        createdArtifacts: MutableList<OwnedArtifact>,
        toolArtifactRewriter: ToolArtifactRewriter,
        copiedArtifacts: MutableMap<String, OwnedArtifact>,
    ): List<UIMessagePart> = clonePartsInternal(
        parts,
        artifactStore,
        createdArtifacts,
        toolArtifactRewriter,
        copiedArtifacts,
    )

    private suspend fun clonePartsInternal(
        parts: List<UIMessagePart>,
        artifactStore: ArtifactStore,
        createdArtifacts: MutableList<OwnedArtifact>,
        toolArtifactRewriter: ToolArtifactRewriter,
        copiedArtifacts: MutableMap<String, OwnedArtifact>,
    ): List<UIMessagePart> = parts.map {
        clonePartInternal(it, artifactStore, createdArtifacts, toolArtifactRewriter, copiedArtifacts)
    }

    private suspend fun cloneSubAssistantTool(
        part: UIMessagePart.Tool,
        metadata: SubAssistantCallMetadata,
        artifactStore: ArtifactStore,
        createdArtifacts: MutableList<OwnedArtifact>,
        toolArtifactRewriter: ToolArtifactRewriter,
        copiedArtifacts: MutableMap<String, OwnedArtifact>,
    ): UIMessagePart.Tool {
        val unavailableRefs = linkedSetOf<String>()
        val unavailablePaths = linkedSetOf<String>()
        var removeUnresolvedLocalMedia = false
        val rewrittenArtifacts = metadata.artifacts.map { item ->
            val sourceRef = item.artifact
            if (sourceRef == null) {
                // A descriptor without an owned artifact is an explicit unavailable
                // placeholder. Remove matching output payloads and manifest paths while
                // retaining the descriptor for durable diagnostics.
                removeUnresolvedLocalMedia = true
                AttachmentRefs.parse(item.ref)?.let { unavailableRefs += AttachmentRefs.format(it) }
                return@map item
            }
            val materialized = artifactStore.materialize(sourceRef)
            if (materialized == null) {
                removeUnresolvedLocalMedia = true
                AttachmentRefs.parse(item.ref)?.let { unavailableRefs += AttachmentRefs.format(it) }
                runCatching { artifactStore.file(sourceRef) }
                    .getOrNull()
                    ?.let { unavailablePaths += canonicalPath(it) }
                return@map item.copy(artifact = null)
            }
            val sourceFile = artifactStore.file(materialized)
            val key = canonicalPath(sourceFile)
            val copied = copiedArtifacts[key] ?: artifactStore.copyFilePreservingOrigin(
                source = sourceFile,
                mimeType = materialized.mimeType,
                displayName = sourceFile.name,
            ).also {
                copiedArtifacts[key] = it
                createdArtifacts += it
            }
            item.copy(artifact = copied.localRef)
        }
        val clonedMetadata = metadata.copy(artifacts = rewrittenArtifacts)
        val clonedOutput = sanitizeUnavailableSubAssistantOutput(
            parts = clonePartsInternal(
                parts = part.output,
                artifactStore = artifactStore,
                createdArtifacts = createdArtifacts,
                toolArtifactRewriter = toolArtifactRewriter,
                copiedArtifacts = copiedArtifacts,
            ),
            unavailableRefs = unavailableRefs,
            unavailablePaths = unavailablePaths,
            removeUnresolvedLocalMedia = removeUnresolvedLocalMedia,
        ).map { output -> rebuildSubAssistantArtifactManifest(output, clonedMetadata) }
        return part
            .copy(output = clonedOutput)
            .mergeSubAssistantCallMetadata(JsonInstant, clonedMetadata)
    }

    /**
     * Removes stale multimedia payloads after a clone.
     * The metadata descriptor remains as an explicit unavailable placeholder, while output
     * never retains a file URL whose ArtifactStore owner was not materialized.
     */
    private fun sanitizeUnavailableSubAssistantOutput(
        parts: List<UIMessagePart>,
        unavailableRefs: Set<String>,
        unavailablePaths: Set<String>,
        removeUnresolvedLocalMedia: Boolean,
    ): List<UIMessagePart> = parts.mapNotNull { part ->
        when (part) {
            is UIMessagePart.Image,
            is UIMessagePart.Document,
            is UIMessagePart.Audio,
            is UIMessagePart.Video -> {
                val url = when (part) {
                    is UIMessagePart.Image -> part.url
                    is UIMessagePart.Document -> part.url
                    is UIMessagePart.Audio -> part.url
                    is UIMessagePart.Video -> part.url
                }
                val stableRef = AttachmentRefs.getStableRef(part)
                val path = AttachmentRefs.parseFileUrl(url)?.let(::canonicalPath)
                val localMedia = url.startsWith("file:", ignoreCase = true)
                if (
                    stableRef in unavailableRefs ||
                    path in unavailablePaths ||
                    (removeUnresolvedLocalMedia && localMedia && stableRef == null)
                ) {
                    null
                } else {
                    part
                }
            }

            is UIMessagePart.Tool -> part.copy(
                output = sanitizeUnavailableSubAssistantOutput(
                    parts = part.output,
                    unavailableRefs = unavailableRefs,
                    unavailablePaths = unavailablePaths,
                    removeUnresolvedLocalMedia = removeUnresolvedLocalMedia,
                ),
            )

            else -> part
        }
    }

    /** Only this tool's result manifest belongs to its metadata; prose and nested tools do not. */
    private fun rebuildSubAssistantArtifactManifest(
        part: UIMessagePart,
        metadata: SubAssistantCallMetadata,
    ): UIMessagePart {
        if (part !is UIMessagePart.Text) return part
        val root = runCatching { JsonInstant.parseToJsonElement(part.text) as? JsonObject }.getOrNull()
            ?: return part
        if ((root["status"] as? JsonPrimitive)?.contentOrNull != "completed" || "artifacts" !in root) return part
        return part.copy(
            text = JsonObject(root.toMutableMap().apply {
                put("artifacts", buildSubAssistantArtifactManifest(metadata.artifacts))
            }).toString(),
        )
    }

    private fun canonicalPath(file: File): String =
        runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
}
