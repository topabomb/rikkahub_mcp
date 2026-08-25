package net.weero.measix.pilot.data.ai.attachments

import android.content.Context
import androidx.core.net.toFile
import androidx.core.net.toUri
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.subassistant.getSubAssistantCallMetadata
import net.weero.measix.pilot.data.db.entity.ArtifactOrigin
import net.weero.measix.pilot.data.files.FileFolders
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.LocalArtifactRef
import net.weero.measix.pilot.data.files.LocalToolPath
import net.weero.measix.pilot.data.files.OwnedArtifact
import me.rerere.ai.core.ToolResourceLease
import net.weero.measix.pilot.data.files.requireDiscarded
import net.weero.measix.pilot.data.files.ToolArtifactRewriter
import net.weero.measix.pilot.data.imggen.GeneratedMediaStore
import net.weero.measix.pilot.utils.JsonInstant
import java.io.File

sealed class AttachmentResolveResult {
    data class Success(
        val parts: List<UIMessagePart.Image>,
        /** 本批解析新创建且尚未发布的 artifact；后续持久化失败时由所有权令牌补偿。 */
        val createdArtifacts: List<OwnedArtifact> = emptyList(),
    ) : AttachmentResolveResult()
    data class Failure(val reason: String) : AttachmentResolveResult()
}

/**
 * Turns a model-supplied attachment reference into a durable local Image part.
 */
class AttachmentResolver(
    private val context: Context,
    private val artifactStore: ArtifactStore,
    private val fetcher: SafeRemoteMediaFetcher,
    private val artifactRewriter: ToolArtifactRewriter,
) {
    suspend fun resolve(
        masterMessages: List<UIMessage>,
        refs: List<String>,
        deduplicate: Boolean = true,
    ): AttachmentResolveResult {
        if (refs.isEmpty()) return AttachmentResolveResult.Success(emptyList())
        val createdArtifacts = mutableListOf<OwnedArtifact>()
        val resolved = ArrayList<UIMessagePart.Image>(refs.size)
        val seenFiles = LinkedHashSet<String>()
        val referenceIndex = AttachmentReferenceLookup.index(masterMessages)
        // 同一远程 url 在本次批量解析内只 fetch/落盘一次；命中后按调用方 preferredRef 重打标记。
        val remoteResolved = HashMap<String, UIMessagePart.Image>()
        try {
            for (ref in refs) {
                when (val one = resolveOne(masterMessages, referenceIndex, ref, createdArtifacts, remoteResolved)) {
                    is AttachmentResolveResult.Failure -> {
                        discardCreated(createdArtifacts)
                        return one
                    }
                    is AttachmentResolveResult.Success -> {
                        val image = one.parts.singleOrNull()
                            ?: run {
                                discardCreated(createdArtifacts)
                                return AttachmentResolveResult.Failure(AttachmentFailureReasons.ATTACHMENT_NOT_FOUND)
                            }
                        if (deduplicate) {
                            val key = canonicalFileKey(image.url)
                            if (key != null && !seenFiles.add(key)) continue
                        }
                        resolved += image
                    }
                }
            }
            return AttachmentResolveResult.Success(resolved, createdArtifacts.toList())
        } catch (error: Exception) {
            discardCreated(createdArtifacts)
            throw error
        }
    }

    /**
     * `inspect_attachments` 的收紧批量入口：只接受 stable `attachment:<uuid>`，1..4 个，
     * all-or-nothing。安全/存在性校验复用 [resolve]，不在工具层复制路径逻辑。
     *
     * 禁用去重：输入顺序即识别/比较顺序，refs 与产出必须 1:1——
     * 重复 ref 或多个 ref 指向同一文件时保留全部输入，由识别调用内的序号标签消歧。
     */
    suspend fun resolveImages(
        masterMessages: List<UIMessage>,
        refs: List<String>,
    ): AttachmentResolveResult {
        if (refs.isEmpty() || refs.size > MAX_INSPECTION_ATTACHMENTS) {
            return AttachmentResolveResult.Failure(AttachmentFailureReasons.INVALID_ATTACHMENTS)
        }
        val normalized = refs.map { it.trim() }
        if (normalized.any { AttachmentRefs.parse(it) == null }) {
            return AttachmentResolveResult.Failure(AttachmentFailureReasons.INVALID_ATTACHMENTS)
        }
        return resolve(masterMessages, normalized, deduplicate = false)
    }

    private suspend fun resolveOne(
        masterMessages: List<UIMessage>,
        referenceIndex: AttachmentReferenceIndex,
        rawRef: String,
        createdArtifacts: MutableList<OwnedArtifact>,
        remoteResolved: MutableMap<String, UIMessagePart.Image>,
    ): AttachmentResolveResult {
        val ref = rawRef.trim()
        return when {
            AttachmentRefs.parse(ref) != null -> resolveAttachmentHandle(
                masterMessages,
                referenceIndex,
                ref,
                createdArtifacts,
                remoteResolved,
            )
            LocalToolPath.parseUploadToolPath(ref) != null -> resolveUploadPath(masterMessages, ref)
            ref.startsWith("file:", ignoreCase = true) -> resolveFileUrl(masterMessages, ref)
            ref.startsWith("http://", ignoreCase = true) ||
                ref.startsWith("https://", ignoreCase = true) -> resolveRemoteUrl(ref, createdArtifacts, remoteResolved)
            else -> AttachmentResolveResult.Failure(AttachmentFailureReasons.ATTACHMENT_NOT_FOUND)
        }
    }

    private suspend fun resolveAttachmentHandle(
        masterMessages: List<UIMessage>,
        referenceIndex: AttachmentReferenceIndex,
        ref: String,
        createdArtifacts: MutableList<OwnedArtifact>,
        remoteResolved: MutableMap<String, UIMessagePart.Image>,
    ): AttachmentResolveResult {
        val normalized = AttachmentRefs.format(AttachmentRefs.parse(ref) ?: return notFound())
        return when (val target = referenceIndex[normalized]) {
            is AttachmentReferenceTarget.MessagePart -> materializeExistingPart(
                masterMessages,
                target.part,
                preferredRef = normalized,
                createdArtifacts = createdArtifacts,
                remoteResolved = remoteResolved,
            )

            is AttachmentReferenceTarget.ManagedArtifact -> {
                val file = runCatching { artifactStore.file(target.artifact) }.getOrNull() ?: return notFound()
                if (!file.isFile || !isAllowedLocalFile(file)) return notFound()
                wrapLocalImage(masterMessages, file, preferredRef = normalized)
            }

            null -> notFound()
        }
    }

    private suspend fun resolveUploadPath(
        masterMessages: List<UIMessage>,
        toolPath: String,
    ): AttachmentResolveResult {
        val file = artifactStore.resolveToolPath(toolPath) ?: return notFound()
        if (!file.isFile) return notFound()
        if (!isReferencedByMaster(masterMessages, file)) return notFound()
        return wrapLocalImage(masterMessages, file)
    }

    private suspend fun resolveFileUrl(
        masterMessages: List<UIMessage>,
        url: String,
    ): AttachmentResolveResult {
        val file = parseExistingLocalFile(url) ?: return notFound()
        if (!isAllowedLocalFile(file)) return notFound()
        if (!isReferencedByMaster(masterMessages, file)) return notFound()
        return wrapLocalImage(masterMessages, file)
    }

    private suspend fun resolveRemoteUrl(
        url: String,
        createdArtifacts: MutableList<OwnedArtifact>,
        remoteResolved: MutableMap<String, UIMessagePart.Image>,
    ): AttachmentResolveResult {
        // 单次批量解析内的请求级复用：同一 url 不重复 fetch/落盘。
        remoteResolved[url]?.let { return AttachmentResolveResult.Success(listOf(it)) }
        return when (val fetched = fetcher.fetch(url)) {
            is RemoteMediaFetchResult.Failure -> AttachmentResolveResult.Failure(fetched.reason)
            is RemoteMediaFetchResult.Success -> persistFetchedImage(fetched, createdArtifacts).also { result ->
                if (result is AttachmentResolveResult.Success) {
                    result.parts.singleOrNull()?.let { remoteResolved[url] = it }
                }
            }
        }
    }

    private suspend fun persistFetchedImage(
        fetched: RemoteMediaFetchResult.Success,
        createdArtifacts: MutableList<OwnedArtifact>,
    ): AttachmentResolveResult {
        if (ImageMime.isUnsupportedNonImage(fetched.bytes, fetched.mimeType)) {
            return AttachmentResolveResult.Failure(AttachmentFailureReasons.UNSUPPORTED_ATTACHMENT_TYPE)
        }
        if (!ImageMime.isAcceptedImage(fetched.bytes)) {
            return AttachmentResolveResult.Failure(AttachmentFailureReasons.UNSUPPORTED_ATTACHMENT_TYPE)
        }
        val owned = try {
            // 子助手入站链路自动拉取远程附件落盘——系统产物
            artifactStore.createFromBytes(
                folder = FileFolders.UPLOAD,
                bytes = fetched.bytes,
                displayName = fetched.fileName,
                mimeType = fetched.mimeType,
                origin = ArtifactOrigin.SYSTEM,
            )
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            return AttachmentResolveResult.Failure(AttachmentFailureReasons.ATTACHMENT_FETCH_FAILED)
        }
        createdArtifacts += owned
        val file = artifactStore.file(owned.entity)
        if (!file.isFile || !ImageMime.isAcceptedImage(fetched.bytes)) {
            return AttachmentResolveResult.Failure(AttachmentFailureReasons.UNSUPPORTED_ATTACHMENT_TYPE)
        }
        val image = AttachmentRefs.ensureAttachmentRef(
            UIMessagePart.Image(url = AttachmentRefs.fileToFileUrl(file)),
        ) as UIMessagePart.Image
        return AttachmentResolveResult.Success(listOf(image))
    }

    private suspend fun materializeExistingPart(
        masterMessages: List<UIMessage>,
        part: UIMessagePart,
        preferredRef: String,
        createdArtifacts: MutableList<OwnedArtifact>,
        remoteResolved: MutableMap<String, UIMessagePart.Image>,
    ): AttachmentResolveResult {
        val url = partUrl(part) ?: return unsupported()
        if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
            return when (val fetched = resolveRemoteUrl(url, createdArtifacts, remoteResolved)) {
                is AttachmentResolveResult.Failure -> fetched
                is AttachmentResolveResult.Success -> {
                    val image = fetched.parts.single()
                    val withRef = withPreferredRef(image, preferredRef)
                    AttachmentResolveResult.Success(listOf(withRef))
                }
            }
        }
        if (!url.startsWith("file:")) return notFound()
        val file = parseExistingLocalFile(url) ?: return notFound()
        if (!isAllowedLocalFile(file)) return notFound()
        return wrapLocalImage(masterMessages, file, preferredRef = preferredRef, sourcePart = part)
    }

    private fun withPreferredRef(
        image: UIMessagePart.Image,
        preferredRef: String,
    ): UIMessagePart.Image = AttachmentRefs.withMetadata(
        image,
        AttachmentRefs.mergeMetadata(
            image.metadata,
            mapOf(AttachmentRefs.METADATA_KEY to kotlinx.serialization.json.JsonPrimitive(preferredRef)),
        ),
    ) as UIMessagePart.Image

    private suspend fun wrapLocalImage(
        masterMessages: List<UIMessage>,
        file: File,
        preferredRef: String? = null,
        sourcePart: UIMessagePart? = null,
    ): AttachmentResolveResult {
        if (file.length() > GeneratedMediaStore.MAX_IMAGE_BYTES) {
            return AttachmentResolveResult.Failure(AttachmentFailureReasons.UNSAFE_ATTACHMENT_URL)
        }
        val bytes = runCatching { file.readBytes() }.getOrElse {
            return AttachmentResolveResult.Failure(AttachmentFailureReasons.ATTACHMENT_FETCH_FAILED)
        }
        val declared = artifactStore.getByRelativePath(
            net.weero.measix.pilot.data.files.FileUtils.getRelativePathInFilesDir(context.filesDir, file).orEmpty(),
        )?.mimeType
        if (ImageMime.isUnsupportedNonImage(bytes, declared)) return unsupported()
        if (!ImageMime.isAcceptedImage(bytes)) return unsupported()

        val url = AttachmentRefs.fileToFileUrl(file)
        val existingRef = preferredRef
            ?: sourcePart?.let { AttachmentRefs.getRef(it) }
            ?: findRefForFile(masterMessages, file)
        val baseMetadata = sourcePart?.metadata
        val withRef = if (existingRef != null) {
            AttachmentRefs.withMetadata(
                UIMessagePart.Image(url = url, metadata = baseMetadata),
                AttachmentRefs.mergeMetadata(
                    baseMetadata,
                    mapOf(AttachmentRefs.METADATA_KEY to kotlinx.serialization.json.JsonPrimitive(existingRef)),
                ),
            )
        } else {
            AttachmentRefs.ensureAttachmentRef(UIMessagePart.Image(url = url, metadata = baseMetadata))
        }
        return AttachmentResolveResult.Success(listOf(withRef as UIMessagePart.Image))
    }

    private fun isReferencedByMaster(messages: List<UIMessage>, file: File): Boolean {
        val target = runCatching { file.canonicalFile }.getOrNull() ?: return false
        for (part in AttachmentRefs.walkMessageParts(messages)) {
            val url = partUrl(part)
            if (url != null && url.startsWith("file:")) {
                val partFile = parseExistingLocalFile(url)
                if (partFile != null && sameFile(partFile, target)) return true
            }
            if (part is UIMessagePart.Tool) {
                val artifact = part.metadata?.let { artifactRewriter.decodeArtifactRef(it) }
                if (artifact != null) {
                    val artifactFile = artifact.file(context.filesDir)
                    if (sameFile(artifactFile, target)) return true
                }
                val call = part.getSubAssistantCallMetadata(JsonInstant)
                if (call != null) {
                    for (item in call.artifacts) {
                        val callFile = item.artifact?.file(context.filesDir) ?: continue
                        if (sameFile(callFile, target)) return true
                    }
                }
            }
        }
        return false
    }

    private fun findRefForFile(messages: List<UIMessage>, file: File): String? {
        val target = runCatching { file.canonicalFile }.getOrNull() ?: return null
        AttachmentRefs.walkMessageParts(messages).forEach { part ->
            val url = partUrl(part)
            if (url != null) {
                val partFile = parseExistingLocalFile(url)
                if (partFile != null && sameFile(partFile, target)) {
                    AttachmentRefs.getRef(part)?.let { return it }
                }
            }
            if (part is UIMessagePart.Tool) {
                val call = part.getSubAssistantCallMetadata(JsonInstant)
                if (call != null) {
                    for (item in call.artifacts) {
                        val callFile = item.artifact?.file(context.filesDir) ?: continue
                        if (sameFile(callFile, target)) return item.ref
                    }
                }
            }
        }
        return null
    }

    private fun parseExistingLocalFile(url: String): File? {
        val fromHelper = AttachmentRefs.parseFileUrl(url)
        val file = fromHelper ?: runCatching { url.toUri().toFile() }.getOrNull()
        return file?.takeIf { it.isFile }
    }

    private fun isAllowedLocalFile(file: File): Boolean {
        if (!file.isFile) return false
        val upload = File(context.filesDir, FileFolders.UPLOAD)
        val images = File(context.filesDir, "images")
        return LocalToolPath.isInsideDirectory(file, upload) ||
            LocalToolPath.isInsideDirectory(file, images)
    }

    private fun partUrl(part: UIMessagePart): String? = when (part) {
        is UIMessagePart.Image -> part.url
        is UIMessagePart.Document -> part.url
        is UIMessagePart.Audio -> part.url
        is UIMessagePart.Video -> part.url
        else -> null
    }

    private fun sameFile(a: File, b: File): Boolean {
        val left = runCatching { a.canonicalFile }.getOrNull() ?: return false
        val right = runCatching { b.canonicalFile }.getOrNull() ?: return false
        return left == right
    }

    private fun canonicalFileKey(url: String): String? =
        parseExistingLocalFile(url)?.let { runCatching { it.canonicalPath }.getOrNull() }

    fun temporaryLease(owned: OwnedArtifact): ToolResourceLease = ToolResourceLease(
        // Request projection artifacts are inputs, not checkpoint outputs. Their successful
        // completion action is intentionally empty; the tool-execution owner always discards them.
        publish = {},
        discard = { discardCreated(listOf(owned)) },
    )

    private suspend fun discardCreated(artifacts: List<OwnedArtifact>) {
        artifacts.forEach { owned ->
            artifactStore.discardUnpublished(owned).requireDiscarded("attachment batch rollback")
        }
    }

    private fun notFound() = AttachmentResolveResult.Failure(AttachmentFailureReasons.ATTACHMENT_NOT_FOUND)

    private fun unsupported() = AttachmentResolveResult.Failure(AttachmentFailureReasons.UNSUPPORTED_ATTACHMENT_TYPE)
}
