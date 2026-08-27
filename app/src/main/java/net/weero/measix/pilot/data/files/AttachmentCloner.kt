package net.weero.measix.pilot.data.files

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.getSubAssistantCallMetadata
import net.weero.measix.pilot.data.ai.subassistant.mergeSubAssistantCallMetadata
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
                if (subAssistantMetadata != null && subAssistantMetadata.artifacts.isNotEmpty()) {
                    return cloneSubAssistantTool(
                        part = part,
                        metadata = subAssistantMetadata,
                        artifactStore = artifactStore,
                        createdArtifacts = createdArtifacts,
                        toolArtifactRewriter = toolArtifactRewriter,
                        copiedArtifacts = copiedArtifacts,
                    )
                }
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
            else -> part
        }
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
        val pathRewrites = linkedMapOf<String, String>()
        val unavailableRefs = linkedSetOf<String>()
        val unavailablePaths = linkedSetOf<String>()
        val unavailableToolPaths = linkedSetOf<String>()
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
                sourceRef.toolPath()?.let { unavailableToolPaths += it }
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
            sourceRef.toolPath()?.let { sourcePath ->
                copied.localRef.toolPath()?.let { targetPath ->
                    pathRewrites[sourcePath] = targetPath
                }
            }
            item.copy(artifact = copied.localRef)
        }
        val clonedOutput = rewriteSubAssistantArtifactPaths(
            sanitizeUnavailableSubAssistantOutput(
                parts = clonePartsInternal(
                    parts = part.output,
                    artifactStore = artifactStore,
                    createdArtifacts = createdArtifacts,
                    toolArtifactRewriter = toolArtifactRewriter,
                    copiedArtifacts = copiedArtifacts,
                ),
                unavailableRefs = unavailableRefs,
                unavailablePaths = unavailablePaths,
                unavailableToolPaths = unavailableToolPaths,
                removeUnresolvedLocalMedia = removeUnresolvedLocalMedia,
            ),
            pathRewrites,
        )
        return part
            .copy(output = clonedOutput)
            .mergeSubAssistantCallMetadata(JsonInstant, metadata.copy(artifacts = rewrittenArtifacts))
    }

    /**
     * Removes stale multimedia payloads and strips their old manifest paths after a clone.
     * The metadata descriptor remains as an explicit unavailable placeholder, while output
     * never retains a file URL whose ArtifactStore owner was not materialized.
     */
    private fun sanitizeUnavailableSubAssistantOutput(
        parts: List<UIMessagePart>,
        unavailableRefs: Set<String>,
        unavailablePaths: Set<String>,
        unavailableToolPaths: Set<String>,
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
                    unavailableToolPaths = unavailableToolPaths,
                    removeUnresolvedLocalMedia = removeUnresolvedLocalMedia,
                ),
            )

            is UIMessagePart.Text -> sanitizeUnavailableArtifactManifest(
                part = part,
                unavailableRefs = unavailableRefs,
                unavailableToolPaths = unavailableToolPaths,
            )

            else -> part
        }
    }

    private fun sanitizeUnavailableArtifactManifest(
        part: UIMessagePart.Text,
        unavailableRefs: Set<String>,
        unavailableToolPaths: Set<String>,
    ): UIMessagePart.Text {
        val root = runCatching { JsonInstant.parseToJsonElement(part.text) as? JsonObject }.getOrNull()
            ?: return part
        val artifacts = root["artifacts"] as? JsonArray ?: return part
        var changed = false
        val sanitizedArtifacts = JsonArray(artifacts.map { element ->
            val artifact = element as? JsonObject ?: return@map element
            val ref = (artifact["ref"] as? JsonPrimitive)?.contentOrNull
                ?.let { AttachmentRefs.parse(it)?.let(AttachmentRefs::format) }
            val path = (artifact["path"] as? JsonPrimitive)?.contentOrNull
            if (ref !in unavailableRefs && path !in unavailableToolPaths) return@map element
            if (path == null) return@map element
            changed = true
            JsonObject(artifact.toMutableMap().apply { remove("path") })
        })
        if (!changed) return part
        return part.copy(
            text = JsonObject(root.toMutableMap().apply { put("artifacts", sanitizedArtifacts) }).toString(),
        )
    }

    /**
     * Rewrites only the structured `artifacts[].path` fields emitted by assistant_call.
     * A global string replacement would corrupt explanatory text and unrelated tool payloads.
     */
    private fun rewriteSubAssistantArtifactPaths(
        parts: List<UIMessagePart>,
        pathRewrites: Map<String, String>,
    ): List<UIMessagePart> {
        if (pathRewrites.isEmpty()) return parts
        return parts.map { part ->
            when (part) {
                is UIMessagePart.Tool -> part.copy(
                    output = rewriteSubAssistantArtifactPaths(part.output, pathRewrites),
                )

                is UIMessagePart.Text -> rewriteSubAssistantArtifactPathText(part, pathRewrites)

                else -> part
            }
        }
    }

    private fun rewriteSubAssistantArtifactPathText(
        part: UIMessagePart.Text,
        pathRewrites: Map<String, String>,
    ): UIMessagePart.Text {
        val root = runCatching { JsonInstant.parseToJsonElement(part.text) as? JsonObject }.getOrNull()
            ?: return part
        val artifacts = root["artifacts"] as? JsonArray ?: return part
        var changed = false
        val rewrittenArtifacts = JsonArray(artifacts.map { item ->
            val artifact = item as? JsonObject ?: return@map item
            val oldPath = (artifact["path"] as? JsonPrimitive)?.contentOrNull
            val newPath = oldPath?.let(pathRewrites::get) ?: return@map item
            changed = true
            JsonObject(artifact.toMutableMap().apply { put("path", JsonPrimitive(newPath)) })
        })
        if (!changed) return part
        return part.copy(text = JsonObject(root.toMutableMap().apply { put("artifacts", rewrittenArtifacts) }).toString())
    }

    private fun canonicalPath(file: File): String =
        runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
}
