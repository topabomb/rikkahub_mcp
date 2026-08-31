package net.weero.measix.pilot.data.ai.subassistant

import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ToolMetadataDelivery
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import net.weero.measix.pilot.data.ai.attachments.MAX_ASSISTANT_CALL_ATTACHMENTS
import net.weero.measix.pilot.data.ai.tools.local.GENERATE_IMAGE_TOOL_NAME
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.AssistantAffectScope
import net.weero.measix.pilot.data.model.replaceRegexes
import net.weero.measix.pilot.data.files.FileFolders
import net.weero.measix.pilot.data.files.FileUtils
import net.weero.measix.pilot.data.files.ArtifactStore
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
 * Extract user-visible deliverables from a Child run. This is shape extraction only; the
 * coordinator must pass the result through [validateDeliverableArtifacts] before persisting or
 * projecting it. ArtifactStore remains the sole metadata/lifecycle owner.
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
 * Validates extracted deliverables at the coordinator boundary before they become durable
 * sub-assistant metadata. Stale/unregistered refs and output URLs that disagree with the managed
 * artifact are rejected, so metadata and direct Tool.output cannot describe different files.
 */
suspend fun validateDeliverableArtifacts(
    extracted: SubAssistantExtractedArtifacts,
    artifactStore: ArtifactStore,
): SubAssistantExtractedArtifacts {
    val valid = extracted.artifacts.mapNotNull { candidate ->
        val canonicalRef = AttachmentRefs.parse(candidate.ref)?.let(AttachmentRefs::format)
            ?: return@mapNotNull null
        val artifact = candidate.artifact ?: return@mapNotNull null
        val materialized = artifactStore.materialize(artifact) ?: return@mapNotNull null
        val managedFile = runCatching { artifactStore.file(materialized).canonicalFile }.getOrNull()
            ?: return@mapNotNull null
        val outputFile = candidate.fileUrl?.let { rawUrl ->
            val parsed = AttachmentRefs.parseFileUrl(rawUrl) ?: return@mapNotNull null
            runCatching { parsed.canonicalFile }.getOrNull() ?: return@mapNotNull null
        }
        if (outputFile != null && outputFile != managedFile) return@mapNotNull null
        if (candidate.type == ARTIFACT_TYPE_IMAGE &&
            artifactStore.resolveImagePreviewForArtifact(materialized) == null
        ) return@mapNotNull null
        candidate.copy(
            ref = canonicalRef,
            artifact = materialized,
            mime = materialized.mimeType,
            fileUrl = outputFile?.let { AttachmentRefs.fileToFileUrl(managedFile) },
        )
    }
    return extracted.copy(
        artifacts = valid,
        // Extraction already applied the cap and recorded the omitted tail. Validation may
        // reject entries from that bounded prefix, but it must not erase the original truncation
        // fact reported to the caller.
        omitted = extracted.omitted,
    )
}

/**
 * Project extracted artifacts into Caller Tool.output parts.
 *
 * Caller native/reference 投影统一交给 AttachmentProjectionTransformer 按本次请求的
 * resolved model 决定：这里只投影带 stable ref 的 native Image parts，
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
    if (!tool.hasReplayResult) return false
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

/**
 * 从 Child 会话消息中提取 final answer。
 *
 * 优先取最后一个 Target ASSISTANT step 中、最后一个“工作工具”
 * 之后的顶层可见 Text。`text_to_speech` 等副作用工具不挡住答案。
 * 最后一步只有 Reasoning/空 Text 时，回退到更早 step 的 post-tool 文本；
 * 仍为空时取最后一条有文本的 ASSISTANT 消息的末段 Text island，避免主助手拿到空 content。
 */
internal fun extractFinalAnswerInternal(
    messages: List<UIMessage>,
    childTaskNodeId: Uuid,
): String {
    val range = messagesInRunRange(messages, childTaskNodeId) ?: return ""
    val assistants = range.filter { it.role == MessageRole.ASSISTANT }
    if (assistants.isEmpty()) return ""

    extractTextAfterLastWorkTool(assistants.last())
        .takeIf { it.isNotBlank() }
        ?.let { return it }

    for (i in assistants.lastIndex - 1 downTo 0) {
        extractTextAfterLastWorkTool(assistants[i])
            .takeIf { it.isNotBlank() }
            ?.let { return it }
    }

    for (msg in assistants.asReversed()) {
        lastTextIsland(msg).takeIf { it.isNotBlank() }?.let { return it }
    }
    return ""
}

internal data class SubAssistantCallCollectedOutputs(
    val toolCalls: List<Pair<String, Int>> = emptyList(),
    val ttsTexts: List<String> = emptyList(),
    val ttsStats: SubAssistantTtsStats? = null,
)

internal fun collectSubAssistantCallOutputs(
    messages: List<UIMessage>,
    childTaskNodeId: Uuid?,
    extras: Set<String>,
): SubAssistantCallCollectedOutputs {
    if (childTaskNodeId == null) return SubAssistantCallCollectedOutputs()
    val range = messagesInRunRange(messages, childTaskNodeId) ?: return SubAssistantCallCollectedOutputs()

    val needToolCalls = ASSISTANT_CALL_EXTRA_TOOL_CALLS in extras
    val needTtsTexts = ASSISTANT_CALL_EXTRA_TTS in extras
    val toolCounts = if (needToolCalls) linkedMapOf<String, Int>() else null
    val ttsTexts = if (needTtsTexts) mutableListOf<String>() else null
    var ttsCalls = 0
    var ttsChars = 0

    for (message in range) {
        for (part in message.parts) {
            if (part !is UIMessagePart.Tool) continue
            toolCounts?.let { counts ->
                counts[part.toolName] = (counts[part.toolName] ?: 0) + 1
            }
            if (part.toolName != "text_to_speech") continue
            // 次数按发出计；空白或无法解析的入参不计字符，也不进入 extras 文本表。
            ttsCalls++
            val text = parseTtsInputText(part.input)
            if (text != null) {
                ttsChars += text.length
                ttsTexts?.add(text)
            }
        }
    }

    return SubAssistantCallCollectedOutputs(
        toolCalls = toolCounts?.map { it.key to it.value }.orEmpty(),
        ttsTexts = ttsTexts.orEmpty(),
        ttsStats = if (ttsCalls > 0) SubAssistantTtsStats(calls = ttsCalls, chars = ttsChars) else null,
    )
}

private fun parseTtsInputText(input: String): String? = runCatching {
    val obj = kotlinx.serialization.json.Json.parseToJsonElement(input) as? JsonObject
    obj?.get("text")?.let { it as? JsonPrimitive }?.content?.trim()
}.getOrNull()?.takeIf { it.isNotEmpty() }

private val SUB_ASSISTANT_SIDE_EFFECT_TOOLS = setOf("text_to_speech")

private fun extractTextAfterLastWorkTool(message: UIMessage): String {
    val parts = message.parts
    var lastWorkToolEnd = 0
    for ((idx, part) in parts.withIndex()) {
        if (part is UIMessagePart.Tool &&
            part.hasReplayResult &&
            part.toolName !in SUB_ASSISTANT_SIDE_EFFECT_TOOLS
        ) {
            lastWorkToolEnd = idx + 1
        }
    }
    return parts.drop(lastWorkToolEnd)
        .filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .trim()
}

private fun lastTextIsland(message: UIMessage): String {
    val parts = message.parts
    var end = parts.lastIndex
    while (end >= 0 && parts[end] !is UIMessagePart.Text) end--
    if (end < 0) return ""
    var start = end
    while (start >= 0 && parts[start] is UIMessagePart.Text) start--
    return parts.subList(start + 1, end + 1)
        .filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .trim()
}

// ---- 工具结果形状（Tool Result 构建） ----

/**
 * 构建返回给 Caller 模型的 Tool Result parts（状态 JSON + 可选投影附件）。
 */
internal fun buildSubAssistantCallResultParts(
    json: kotlinx.serialization.json.Json,
    status: String,
    assistantName: String,
    content: String = "",
    reason: String? = null,
    detail: String? = null,
    hasNonTextOutput: Boolean = false,
    messages: List<UIMessage> = emptyList(),
    childTaskNodeId: Uuid? = null,
    extras: Set<String> = emptySet(),
    artifacts: List<SubAssistantCallArtifact> = emptyList(),
    artifactsOmitted: Int = 0,
    extraParts: List<UIMessagePart> = emptyList(),
): List<UIMessagePart> {
    val outputs = collectSubAssistantCallOutputs(messages, childTaskNodeId, extras)
    val resultJson = buildSubAssistantCallResult(
        json = json,
        status = status,
        assistantName = assistantName,
        content = content,
        reason = reason,
        detail = detail,
        hasNonTextOutput = hasNonTextOutput,
        toolCalls = outputs.toolCalls,
        ttsTexts = outputs.ttsTexts,
        ttsStats = outputs.ttsStats,
        artifacts = artifacts,
        artifactsOmitted = artifactsOmitted,
    )
    return listOf(UIMessagePart.Text(resultJson)) + extraParts
}

/** 调用被拒（readiness/lease/附件失败等）：UNAVAILABLE metadata + 不可用结果。 */
internal suspend fun buildUnavailableCallResult(
    json: kotlinx.serialization.json.Json,
    execContext: me.rerere.ai.core.ToolExecutionContext,
    targetAssistantId: Uuid,
    assistantName: String,
    reason: String,
    runId: String = Uuid.random().toString(),
): List<UIMessagePart> {
    val metadata = buildInitialSubAssistantCallMetadata(
        runId = runId,
        targetAssistantId = targetAssistantId,
        targetNameSnapshot = assistantName,
    ).copy(
        state = SubAssistantCallState.UNAVAILABLE,
        reason = reason,
    )
    reportSubAssistantMetadataPatch(json, execContext, metadata, delivery = ToolMetadataDelivery.DEFERRED)
    return buildSubAssistantCallResultParts(
        json = json,
        status = "unavailable",
        assistantName = assistantName,
        reason = reason,
    )
}

/** 调用前置失败（持久化/初始 metadata 写入异常）：FAILED metadata + 分类失败结果。 */
internal suspend fun buildClassifiedFailureResult(
    json: kotlinx.serialization.json.Json,
    error: Exception,
    execContext: me.rerere.ai.core.ToolExecutionContext,
    targetAssistantId: Uuid,
    assistantName: String,
    extras: Set<String>,
    runId: String,
): List<UIMessagePart> {
    val failureReason = classifySubAssistantFailure(error)
    val metadata = buildInitialSubAssistantCallMetadata(
        runId = runId,
        targetAssistantId = targetAssistantId,
        targetNameSnapshot = assistantName,
    ).copy(
        state = SubAssistantCallState.FAILED,
        reason = failureReason,
    )
    reportSubAssistantMetadataPatch(json, execContext, metadata, delivery = ToolMetadataDelivery.DEFERRED)
    return buildSubAssistantCallResultParts(
        json = json,
        status = "failed",
        assistantName = assistantName,
        reason = failureReason,
        detail = modelVisibleFailureDetail(failureReason, error),
        extras = extras,
    )
}

/** 将 sub_assistant_call metadata 补丁及其事实阶段交给生成管道。 */
internal suspend fun reportSubAssistantMetadataPatch(
    json: kotlinx.serialization.json.Json,
    execContext: me.rerere.ai.core.ToolExecutionContext,
    meta: SubAssistantCallMetadata,
    delivery: ToolMetadataDelivery,
) {
    val patch = kotlinx.serialization.json.JsonObject(
        mapOf("sub_assistant_call" to json.encodeToJsonElement(SubAssistantCallMetadata.serializer(), meta))
    )
    execContext.reportMetadata(patch, delivery)
}

/** 流式 phase 事件 → 卡片 phase 枚举。 */
internal fun mapSubAssistantCallPhase(phase: String): SubAssistantCallPhase? = when (phase) {
    "preparing" -> SubAssistantCallPhase.PREPARING
    "model_waiting" -> SubAssistantCallPhase.MODEL_WAITING
    "reasoning_streaming" -> SubAssistantCallPhase.REASONING_STREAMING
    "answer_streaming" -> SubAssistantCallPhase.ANSWER_STREAMING
    "tool_executing" -> SubAssistantCallPhase.TOOL_EXECUTING
    "between_steps" -> SubAssistantCallPhase.BETWEEN_STEPS
    else -> null
}

// ---- 入站任务投影 ----

/** Child 任务消息 parts：request 文本 + 解析后的入站附件。 */
internal fun buildChildUserParts(
    processedTask: String,
    images: List<UIMessagePart.Image>,
): List<UIMessagePart> = buildList {
    add(UIMessagePart.Text(processedTask))
    addAll(images)
}

/** 对 Child 任务文本应用 Target 的用户侧正则替换。 */
internal fun preprocessSubAssistantTask(
    task: String,
    target: Assistant,
): String = task.replaceRegexes(
    assistant = target,
    scope = AssistantAffectScope.USER,
    visual = false,
)

/** 取消原因归一（撤权/重启/用户取消的受控词表）。 */
internal fun normalizeSubAssistantCancellationReason(message: String?): String = when (message) {
    "target_removed",
    "target_disabled",
    "target_access_revoked",
    "target_model_unavailable",
    "caller_model_unavailable",
    "app_restarted",
    "child_missing",
    "user_cancelled" -> message
    "assistant_removed" -> "target_removed"
    else -> "user_cancelled"
}
