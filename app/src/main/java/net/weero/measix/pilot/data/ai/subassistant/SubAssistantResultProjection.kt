package net.weero.measix.pilot.data.ai.subassistant

import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import net.weero.measix.pilot.data.ai.attachments.MAX_ASSISTANT_CALL_ATTACHMENTS
import net.weero.measix.pilot.data.ai.tools.local.GENERATE_IMAGE_TOOL_NAME
import net.weero.measix.pilot.data.files.FileFolders
import net.weero.measix.pilot.data.files.FileUtils
import net.weero.measix.pilot.data.files.LocalArtifactRef
import net.weero.measix.pilot.data.files.ToolArtifactRewriter
import net.weero.measix.pilot.utils.JsonInstant
import kotlin.uuid.Uuid

const val ARTIFACT_TYPE_IMAGE = "image"
const val ARTIFACT_TYPE_DOCUMENT = "document"
const val ARTIFACT_TYPE_AUDIO = "audio"
const val ARTIFACT_TYPE_VIDEO = "video"

/**
 * A deliverable extracted from a Child run. [fileUrl] is a local `file://` used for
 * Native projection and is never written into model JSON.
 */
data class SubAssistantDeliverableArtifact(
    val ref: String,
    val type: String,
    val mime: String,
    val artifact: LocalArtifactRef? = null,
    val fileUrl: String? = null,
) {
    fun toMetadata(): SubAssistantCallArtifact = SubAssistantCallArtifact(
        ref = ref,
        type = type,
        mime = mime,
        artifact = artifact,
    )
}

data class SubAssistantExtractedArtifacts(
    val artifacts: List<SubAssistantDeliverableArtifact>,
    val omitted: Int,
    val hasNonTextOutput: Boolean,
)

data class CallerArtifactProjection(
    val extraParts: List<UIMessagePart> = emptyList(),
)

internal fun messagesInRunRange(
    messages: List<UIMessage>,
    childTaskNodeId: Uuid,
): List<UIMessage>? {
    val startIndex = messages.indexOfFirst { it.id == childTaskNodeId }
    if (startIndex == -1) return null
    var endIndex = messages.size
    for (i in (startIndex + 1) until messages.size) {
        if (messages[i].role == MessageRole.USER) {
            endIndex = i
            break
        }
    }
    return messages.subList(startIndex, endIndex)
}

/**
 * Extract user-visible deliverables from a Child run. Capability judgment belongs
 * in [projectArtifactsForCaller], not here.
 */
fun extractDeliverableArtifacts(
    messages: List<UIMessage>,
    childTaskNodeId: Uuid,
    filesDir: File? = null,
): SubAssistantExtractedArtifacts {
    val range = messagesInRunRange(messages, childTaskNodeId)
        ?: return SubAssistantExtractedArtifacts(emptyList(), 0, hasNonTextOutput = false)
    val inboundUserId = childTaskNodeId
    val lastAssistant = range.lastOrNull { it.role == MessageRole.ASSISTANT }
    val collected = ArrayList<SubAssistantDeliverableArtifact>()
    val seenKeys = LinkedHashSet<String>()

    fun addCandidate(candidate: SubAssistantDeliverableArtifact?) {
        if (candidate == null) return
        val key = dedupKey(candidate)
        if (!seenKeys.add(key)) return
        collected += candidate
    }

    for (message in range) {
        val skipInboundMedia = message.id == inboundUserId && message.role == MessageRole.USER
        for (part in message.parts) {
            if (part is UIMessagePart.Tool && isSuccessfulGenerateImage(part)) {
                val toolArtifact = decodeLocalArtifactRef(part.metadata)
                for (output in part.output) {
                    if (output is UIMessagePart.Image) {
                        addCandidate(imageDeliverable(output, toolArtifact, filesDir))
                    }
                }
            }
        }
        if (!skipInboundMedia && message.id == lastAssistant?.id) {
            for (part in message.parts) {
                addCandidate(topLevelDeliverable(part, filesDir))
            }
        }
    }

    val persistable = collected.filter { it.artifact != null }
    val omitted = (persistable.size - MAX_ASSISTANT_CALL_ATTACHMENTS).coerceAtLeast(0)
    return SubAssistantExtractedArtifacts(
        artifacts = persistable.take(MAX_ASSISTANT_CALL_ATTACHMENTS),
        omitted = omitted,
        hasNonTextOutput = collected.isNotEmpty(),
    )
}

/**
 * Project extracted artifacts into Caller Tool.output parts.
 *
 * Caller native/reference 投影统一交给 AttachmentProjectionTransformer 按本次请求的
 * resolved model 决定（设计文档 §11.4）：这里只投影带 stable ref 的 native Image parts，
 * 不判断 Caller 能力、不调用识别模型。
 */
suspend fun projectArtifactsForCaller(
    artifacts: List<SubAssistantDeliverableArtifact>,
    extras: Set<String>,
): CallerArtifactProjection {
    if (ASSISTANT_CALL_EXTRA_ARTIFACTS !in extras) {
        return CallerArtifactProjection()
    }
    val images = artifacts.filter { it.type == ARTIFACT_TYPE_IMAGE && !it.fileUrl.isNullOrBlank() }
    return CallerArtifactProjection(
        extraParts = images.map { it.toNativeImagePart() },
    )
}

internal fun isSuccessfulGenerateImage(tool: UIMessagePart.Tool): Boolean {
    if (tool.toolName != GENERATE_IMAGE_TOOL_NAME) return false
    if (!tool.isExecuted) return false
    if (generateImageOutputFailed(tool)) return false
    val hasImage = tool.output.any { it is UIMessagePart.Image }
    if (!hasImage) return false
    val hasArtifact = decodeLocalArtifactRef(tool.metadata) != null
    val hasLocalFile = tool.output.any { part ->
        part is UIMessagePart.Image && isLocalFileUrl(part.url)
    }
    return hasArtifact || hasLocalFile
}

private fun generateImageOutputFailed(tool: UIMessagePart.Tool): Boolean {
    val text = tool.output.filterIsInstance<UIMessagePart.Text>().firstOrNull()?.text ?: return false
    val obj = runCatching { JsonInstant.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return false
    val status = obj["status"]?.jsonPrimitive?.contentOrNull
    return status != null && status != "completed"
}

private fun topLevelDeliverable(
    part: UIMessagePart,
    filesDir: File?,
): SubAssistantDeliverableArtifact? = when (part) {
    is UIMessagePart.Image -> imageDeliverable(part, toolArtifact = null, filesDir = filesDir)
    is UIMessagePart.Document -> mediaDeliverable(
        part = part,
        type = ARTIFACT_TYPE_DOCUMENT,
        url = part.url,
        mime = part.mime,
        filesDir = filesDir,
    )
    is UIMessagePart.Audio -> mediaDeliverable(
        part = part,
        type = ARTIFACT_TYPE_AUDIO,
        url = part.url,
        mime = guessMime(part.url, "audio/*"),
        filesDir = filesDir,
    )
    is UIMessagePart.Video -> mediaDeliverable(
        part = part,
        type = ARTIFACT_TYPE_VIDEO,
        url = part.url,
        mime = guessMime(part.url, "video/*"),
        filesDir = filesDir,
    )
    else -> null
}

private fun imageDeliverable(
    part: UIMessagePart.Image,
    toolArtifact: LocalArtifactRef?,
    filesDir: File?,
): SubAssistantDeliverableArtifact? {
    if (!isLocalFileUrl(part.url)) return null
    val file = AttachmentRefs.parseFileUrl(part.url)
    val artifact = toolArtifact
        ?: file?.let { localArtifactFromFile(it, filesDir, guessMime(part.url, "image/png")) }
    val ref = existingOrNewRef(part)
    return SubAssistantDeliverableArtifact(
        ref = ref,
        type = ARTIFACT_TYPE_IMAGE,
        mime = artifact?.mimeType ?: guessMime(part.url, "image/png"),
        artifact = artifact,
        fileUrl = part.url,
    )
}

private fun mediaDeliverable(
    part: UIMessagePart,
    type: String,
    url: String,
    mime: String,
    filesDir: File?,
): SubAssistantDeliverableArtifact? {
    if (!isLocalFileUrl(url)) return null
    val file = AttachmentRefs.parseFileUrl(url) ?: return null
    val artifact = localArtifactFromFile(file, filesDir, mime)
    val ref = existingOrNewRef(part)
    return SubAssistantDeliverableArtifact(
        ref = ref,
        type = type,
        mime = artifact?.mimeType ?: mime,
        artifact = artifact,
        fileUrl = url,
    )
}

private fun localArtifactFromFile(
    file: File,
    filesDir: File?,
    mime: String,
): LocalArtifactRef? {
    if (filesDir == null) return null
    val relative = FileUtils.getRelativePathInFilesDir(filesDir, file) ?: return null
    val folder = relative.substringBefore('/')
    if (folder != FileFolders.UPLOAD && folder != "images") return null
    return LocalArtifactRef(relativePath = relative, mimeType = mime)
}

private fun existingOrNewRef(part: UIMessagePart): String {
    val existing = AttachmentRefs.getRef(part)?.let { raw ->
        AttachmentRefs.parse(raw)?.let { AttachmentRefs.format(it) }
    }
    return existing ?: AttachmentRefs.format(Uuid.random())
}

private fun dedupKey(artifact: SubAssistantDeliverableArtifact): String {
    val parsedRef = AttachmentRefs.parse(artifact.ref)
    if (parsedRef != null) return "ref:${AttachmentRefs.format(parsedRef)}"
    val file = artifact.fileUrl?.let { AttachmentRefs.parseFileUrl(it) }
    val canonical = file?.let { runCatching { it.canonicalPath }.getOrNull() }
    if (canonical != null) return "file:$canonical"
    return "url:${artifact.fileUrl.orEmpty()}|${artifact.type}|${artifact.mime}"
}

private fun isLocalFileUrl(url: String): Boolean = url.startsWith("file:", ignoreCase = true)

private fun SubAssistantDeliverableArtifact.toNativeImagePart(): UIMessagePart.Image {
    val image = UIMessagePart.Image(url = fileUrl.orEmpty())
    return AttachmentRefs.withMetadata(
        image,
        AttachmentRefs.mergeMetadata(
            image.metadata,
            mapOf(AttachmentRefs.METADATA_KEY to JsonPrimitive(ref)),
        ),
    ) as UIMessagePart.Image
}

internal fun decodeLocalArtifactRef(metadata: JsonObject?): LocalArtifactRef? {
    val raw = metadata?.get(ToolArtifactRewriter.ARTIFACT_KEY) ?: return null
    return runCatching {
        JsonInstant.decodeFromJsonElement(LocalArtifactRef.serializer(), raw)
    }.getOrNull()?.takeIf { it.version == LocalArtifactRef.CURRENT_VERSION }
}

internal fun guessMime(path: String, fallback: String): String {
    val name = path.substringAfterLast('/').substringBefore('?')
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "heic", "heif" -> "image/heic"
        "pdf" -> "application/pdf"
        "mp3", "m4a", "wav", "ogg" -> "audio/*"
        "mp4", "webm", "mov" -> "video/*"
        else -> fallback
    }
}
