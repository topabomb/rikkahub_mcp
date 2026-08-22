package net.weero.measix.pilot.data.ai.attachments

import android.content.Context
import androidx.core.net.toFile
import androidx.core.net.toUri
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.subassistant.getSubAssistantCallMetadata
import net.weero.measix.pilot.data.files.FileFolders
import net.weero.measix.pilot.data.files.FilesManager
import net.weero.measix.pilot.data.files.LocalArtifactRef
import net.weero.measix.pilot.data.files.LocalToolPath
import net.weero.measix.pilot.data.files.ManagedLocalArtifactStore
import net.weero.measix.pilot.data.files.ToolArtifactRewriter
import net.weero.measix.pilot.data.imggen.GeneratedMediaStore
import net.weero.measix.pilot.utils.JsonInstant
import java.io.File

sealed class AttachmentResolveResult {
    data class Success(
        val parts: List<UIMessagePart.Image>,
        /** 本批解析新登记的 managed 文件 id；调用方在后续持久化失败时应删除它们。 */
        val createdManagedFileIds: List<Long> = emptyList(),
    ) : AttachmentResolveResult()
    data class Failure(val reason: String) : AttachmentResolveResult()
}

/**
 * Turns a model-supplied attachment reference into a durable local Image part.
 */
class AttachmentResolver(
    private val context: Context,
    private val filesManager: FilesManager,
    private val artifactStore: ManagedLocalArtifactStore,
    private val fetcher: SafeRemoteMediaFetcher,
    private val artifactRewriter: ToolArtifactRewriter,
) {
    suspend fun resolve(
        masterMessages: List<UIMessage>,
        refs: List<String>,
    ): AttachmentResolveResult {
        if (refs.isEmpty()) return AttachmentResolveResult.Success(emptyList())
        val createdIds = mutableListOf<Long>()
        val resolved = ArrayList<UIMessagePart.Image>(refs.size)
        val seenFiles = LinkedHashSet<String>()
        try {
            for (ref in refs) {
                when (val one = resolveOne(masterMessages, ref, createdIds)) {
                    is AttachmentResolveResult.Failure -> {
                        deleteCreated(createdIds)
                        return one
                    }
                    is AttachmentResolveResult.Success -> {
                        val image = one.parts.singleOrNull()
                            ?: run {
                                deleteCreated(createdIds)
                                return AttachmentResolveResult.Failure(AttachmentFailureReasons.ATTACHMENT_NOT_FOUND)
                            }
                        val key = canonicalFileKey(image.url)
                        if (key != null && !seenFiles.add(key)) continue
                        resolved += image
                    }
                }
            }
            return AttachmentResolveResult.Success(resolved, createdIds.toList())
        } catch (error: Exception) {
            deleteCreated(createdIds)
            throw error
        }
    }

    /**
     * `inspect_attachments` 的收紧批量入口：只接受 stable `attachment:<uuid>`，1..4 个，
     * all-or-nothing。安全/存在性校验复用 [resolve]，不在工具层复制路径逻辑。
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
        return resolve(masterMessages, normalized)
    }

    private suspend fun resolveOne(
        masterMessages: List<UIMessage>,
        rawRef: String,
        createdIds: MutableList<Long>,
    ): AttachmentResolveResult {
        val ref = rawRef.trim()
        return when {
            AttachmentRefs.parse(ref) != null -> resolveAttachmentHandle(masterMessages, ref, createdIds)
            LocalToolPath.parseUploadToolPath(ref) != null -> resolveUploadPath(masterMessages, ref)
            ref.startsWith("file:", ignoreCase = true) -> resolveFileUrl(masterMessages, ref)
            ref.startsWith("http://", ignoreCase = true) ||
                ref.startsWith("https://", ignoreCase = true) -> resolveRemoteUrl(ref, createdIds)
            else -> AttachmentResolveResult.Failure(AttachmentFailureReasons.ATTACHMENT_NOT_FOUND)
        }
    }

    private suspend fun resolveAttachmentHandle(
        masterMessages: List<UIMessage>,
        ref: String,
        createdIds: MutableList<Long>,
    ): AttachmentResolveResult {
        val normalized = AttachmentRefs.format(AttachmentRefs.parse(ref) ?: return notFound())
        val part = AttachmentRefs.walkMessageParts(masterMessages).firstOrNull { candidate ->
            AttachmentRefs.getRef(candidate)?.let { AttachmentRefs.parse(it) }?.let { AttachmentRefs.format(it) } ==
                normalized
        }
        if (part != null) {
            return materializeExistingPart(masterMessages, part, preferredRef = normalized, createdIds = createdIds)
        }
        val metadataArtifact = findMetadataArtifact(masterMessages, normalized) ?: return notFound()
        val file = metadataArtifact.file(context.filesDir)
        if (!file.isFile || !isAllowedLocalFile(file)) return notFound()
        return wrapLocalImage(masterMessages, file, preferredRef = normalized)
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

    private suspend fun resolveRemoteUrl(url: String, createdIds: MutableList<Long>): AttachmentResolveResult {
        return when (val fetched = fetcher.fetch(url)) {
            is RemoteMediaFetchResult.Failure -> AttachmentResolveResult.Failure(fetched.reason)
            is RemoteMediaFetchResult.Success -> persistFetchedImage(fetched, createdIds)
        }
    }

    private suspend fun persistFetchedImage(
        fetched: RemoteMediaFetchResult.Success,
        createdIds: MutableList<Long>,
    ): AttachmentResolveResult {
        if (ImageMime.isUnsupportedNonImage(fetched.bytes, fetched.mimeType)) {
            return AttachmentResolveResult.Failure(AttachmentFailureReasons.UNSUPPORTED_ATTACHMENT_TYPE)
        }
        if (!ImageMime.isAcceptedImage(fetched.bytes, fetched.mimeType)) {
            return AttachmentResolveResult.Failure(AttachmentFailureReasons.UNSUPPORTED_ATTACHMENT_TYPE)
        }
        val entity = runCatching {
            filesManager.saveManagedFromBytes(
                folder = FileFolders.UPLOAD,
                bytes = fetched.bytes,
                displayName = fetched.fileName,
                mimeType = fetched.mimeType,
            )
        }.getOrElse {
            return AttachmentResolveResult.Failure(AttachmentFailureReasons.ATTACHMENT_FETCH_FAILED)
        }
        createdIds += entity.id
        val file = filesManager.getFile(entity)
        if (!file.isFile || !ImageMime.isAcceptedImage(fetched.bytes, entity.mimeType)) {
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
        createdIds: MutableList<Long>,
    ): AttachmentResolveResult {
        val url = partUrl(part) ?: return unsupported()
        if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
            return when (val fetched = resolveRemoteUrl(url, createdIds)) {
                is AttachmentResolveResult.Failure -> fetched
                is AttachmentResolveResult.Success -> {
                    val image = fetched.parts.single()
                    val withRef = AttachmentRefs.withMetadata(
                        image,
                        AttachmentRefs.mergeMetadata(
                            image.metadata,
                            mapOf(
                                AttachmentRefs.METADATA_KEY to kotlinx.serialization.json.JsonPrimitive(preferredRef),
                            ),
                        ),
                    ) as UIMessagePart.Image
                    AttachmentResolveResult.Success(listOf(withRef))
                }
            }
        }
        if (!url.startsWith("file:")) return notFound()
        val file = parseExistingLocalFile(url) ?: return notFound()
        if (!isAllowedLocalFile(file)) return notFound()
        return wrapLocalImage(masterMessages, file, preferredRef = preferredRef, sourcePart = part)
    }

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
        val declared = filesManager.getByRelativePath(
            net.weero.measix.pilot.data.files.FileUtils.getRelativePathInFilesDir(context.filesDir, file).orEmpty(),
        )?.mimeType
        if (ImageMime.isUnsupportedNonImage(bytes, declared)) return unsupported()
        if (!ImageMime.isAcceptedImage(bytes, declared)) return unsupported()

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

    private fun findMetadataArtifact(
        messages: List<UIMessage>,
        normalizedRef: String,
    ): LocalArtifactRef? {
        for (part in AttachmentRefs.walkMessageParts(messages)) {
            if (part !is UIMessagePart.Tool) continue
            val call = part.getSubAssistantCallMetadata(JsonInstant) ?: continue
            for (item in call.artifacts) {
                val itemRef = AttachmentRefs.parse(item.ref)?.let { AttachmentRefs.format(it) }
                if (itemRef == normalizedRef) {
                    return item.artifact
                }
            }
        }
        return null
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

    private suspend fun deleteCreated(ids: List<Long>) {
        ids.forEach { id ->
            runCatching { filesManager.deleteManagedFilePermanently(id, deleteFromDisk = true) }
        }
    }

    private fun notFound() = AttachmentResolveResult.Failure(AttachmentFailureReasons.ATTACHMENT_NOT_FOUND)

    private fun unsupported() = AttachmentResolveResult.Failure(AttachmentFailureReasons.UNSUPPORTED_ATTACHMENT_TYPE)
}
