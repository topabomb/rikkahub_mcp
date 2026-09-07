package me.rerere.ai.ui

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ProviderUsageSnapshot
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.util.json
import kotlin.time.Clock
import kotlin.uuid.Uuid

// 公共消息抽象, 具体的Provider实现会转换为API接口需要的DTO
@Serializable
data class UIMessage(
    val id: Uuid = Uuid.random(),
    val role: MessageRole,
    val parts: List<UIMessagePart>,
    val annotations: List<UIMessageAnnotation> = emptyList(),
    val createdAt: LocalDateTime = Clock.System.now()
        .toLocalDateTime(TimeZone.currentSystemDefault()),
    val finishedAt: LocalDateTime? = null,
    val modelId: Uuid? = null,
    val usage: TokenUsage? = null,
    val translation: String? = null,
    /** Provider protocol state used for lossless stateless replay; never rendered as user content. */
    val providerMetadata: JsonObject? = null,
    /**
     * Non-success Master turn outcome stored on the visible assistant message.
     * Null means a normal completed (or still-running) message.
     */
    val terminalStatus: MessageTerminalStatus? = null,
    /** Stable English reason for [terminalStatus], e.g. `user_stop`. */
    val terminalReason: String? = null,
    /** Sanitized human-readable diagnostic for reopening a terminal error after restart. */
    val terminalDetail: String? = null,
    /**
     * Request-only replay projection computed by [replaySafeProjection]; never persisted to Room,
     * backup or Conversation snapshot. Null on normal (successful or still-running) messages;
     * non-null on terminal assistant messages so that strict protocols (e.g. DeepSeek V4 with
     * tools) can serialize only the replay-safe provider call/result prefix.
     */
    @Transient
    val providerReplayProjection: ProviderReplayProjection? = null,
) {

    fun summaryAsText(maxLength: Int = Int.MAX_VALUE): String {
        val text = "[${role.name}]: " + parts.joinToString(separator = "\n") { part ->
            when (part) {
                is UIMessagePart.Text -> part.text
                else -> ""
            }
        }
        return if (text.length > maxLength) text.take(maxLength) + "..." else text
    }

    fun toText() = partsToText(parts)

    fun getTools() = parts.filterIsInstance<UIMessagePart.Tool>()

    fun isValidToUpload() = partsAreValidToUpload(parts)

    inline fun <reified P : UIMessagePart> hasPart(): Boolean {
        return parts.any {
            it is P
        }
    }

    fun hasBase64Part(): Boolean = partsContainBase64(parts)

    fun withoutUnpersistableBase64(): UIMessage {
        if (!hasBase64Part()) return this
        return copy(parts = stripUnpersistableBase64(parts))
    }

    companion object {
        fun system(prompt: String) = UIMessage(
            role = MessageRole.SYSTEM,
            parts = listOf(UIMessagePart.Text(prompt))
        )

        fun user(prompt: String) = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text(prompt))
        )

        fun assistant(prompt: String) = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text(prompt))
        )
    }
}

/** True for a finished image URL; raw base64 fragments return false so they can be concatenated. */
fun isCompleteImageUrl(url: String): Boolean {
    val value = url.trim()
    return value.startsWith("data:", ignoreCase = true) ||
        value.startsWith("http://", ignoreCase = true) ||
        value.startsWith("https://", ignoreCase = true) ||
        value.startsWith("file:", ignoreCase = true) ||
        value.startsWith("content:", ignoreCase = true) ||
        value.startsWith("android.resource:", ignoreCase = true)
}

fun renderableImageUrl(url: String, mimeType: String = "image/png"): String {
    val value = url.trim()
    if (value.isEmpty() || isCompleteImageUrl(value)) return value
    return "data:$mimeType;base64,$value"
}

/**
 * 判断这个消息是否有有任何用户**可输入内容**
 *
 * 例如: 文本，图片, 文档
 */
fun List<UIMessagePart>.isEmptyInputMessage(): Boolean {
    if (this.isEmpty()) return true
    return this.all { message ->
        when (message) {
            is UIMessagePart.Text -> message.text.isBlank()
            is UIMessagePart.Image -> message.url.isBlank()
            is UIMessagePart.Document -> message.url.isBlank()
            is UIMessagePart.Video -> message.url.isBlank()
            is UIMessagePart.Audio -> message.url.isBlank()
            else -> true
        }
    }
}

/**
 * 判断这个消息在UI上是否显示任何内容
 */
fun List<UIMessagePart>.isEmptyUIMessage(): Boolean {
    if (this.isEmpty()) return true
    return this.all { message ->
        when (message) {
            is UIMessagePart.Text -> message.text.isBlank()
            is UIMessagePart.Image -> message.url.isBlank()
            is UIMessagePart.Document -> message.url.isBlank()
            is UIMessagePart.Reasoning -> message.reasoning.isBlank()
            is UIMessagePart.Video -> message.url.isBlank()
            is UIMessagePart.Audio -> message.url.isBlank()
            // 工具调用本身在 UI 上有卡片与操作入口，与是否执行、是否产出无关。
            is UIMessagePart.Tool -> false
            else -> true
        }
    }
}

/**
 * Finds the nearest user-message boundary at or before [startIndex].
 *
 * This is shared by request-time trimming and persistent conversation
 * compression so neither path starts from an orphaned assistant/tool response.
 */
fun List<UIMessage>.findUserTurnStart(startIndex: Int): Int {
    if (isEmpty()) return 0
    val safeStartIndex = startIndex.coerceIn(0, lastIndex)
    for (index in safeStartIndex downTo 0) {
        if (this[index].role == MessageRole.USER) return index
    }
    return safeStartIndex
}

/**
 * Request-only replay projection for a terminal (non-success) assistant message.
 *
 * [completePartCount] is the number of parts forming replay-safe provider call/result steps; the
 * remainder is an incomplete tail. Strict protocols (DeepSeek V4 with tools) must only
 * serialize `parts.take(completePartCount)` and drop the tail entirely. Other protocols
 * may still replay partial text with the terminal marker for compatibility.
 *
 * This type is not persisted and only lives on the projected [UIMessage] returned by
 * [replaySafeProjection].
 */
data class ProviderReplayProjection(
    val completePartCount: Int,
    val hasIncompleteTail: Boolean,
)

@Serializable
enum class MessageTerminalStatus {
    @SerialName("cancelled")
    CANCELLED,

    @SerialName("failed")
    FAILED,

    @SerialName("incomplete")
    INCOMPLETE,

    @SerialName("interrupted")
    INTERRUPTED,
}

/** A durable, renderer-visible replacement for media bytes that could not be persisted. */
@Serializable
enum class MessageMediaFailureReason {
    @SerialName("persistence_failed")
    PERSISTENCE_FAILED,
}

@Serializable
enum class MessageMediaKind {
    @SerialName("image")
    IMAGE,
}

/**
 * Typed metadata stored on an empty Text placeholder. Keeping the placeholder out of Image avoids
 * treating a fake or blank URL as loading media while preserving its attachment metadata.
 */
@Serializable
data class MessageMediaFailureMetadata(
    @SerialName("media_failure")
    val reason: MessageMediaFailureReason? = null,
    @SerialName("media_kind")
    val mediaKind: MessageMediaKind? = null,
) : PartMetadata

object TurnTerminalReasons {
    const val USER_STOP = "user_stop"
    const val SUPERSEDED_BY_NEW_TURN = "superseded_by_new_turn"
    const val PROVIDER_FAILED = "provider_failed"
    const val PROVIDER_INCOMPLETE = "provider_incomplete"
    const val RUNTIME_ERROR = "runtime_error"
    const val TOOL_LOOP_LIMIT = "tool_loop_limit"
    const val INTERACTION_LIMIT = "interaction_limit"
    const val PROCESS_RESTARTED = "process_restarted"
    const val TURN_CONTEXT_MATERIALIZE = "turn_context_materialize"
}

/** Shared part-level predicate behind `UIMessage.isValidToUpload` and `ModelRequestMessage.isValidToUpload`. */
fun partsAreValidToUpload(parts: List<UIMessagePart>): Boolean = parts.any { part ->
    when (part) {
        is UIMessagePart.Text -> part.text.isNotBlank()
        is UIMessagePart.Image -> part.url.isNotBlank()
        is UIMessagePart.Video -> part.url.isNotBlank()
        is UIMessagePart.Audio -> part.url.isNotBlank()
        is UIMessagePart.Document -> part.url.isNotBlank()
        is UIMessagePart.Reasoning -> part.reasoning.isNotBlank()
        else -> true
    }
}

/** Shared part-level projection behind `UIMessage.toText` and `ModelRequestMessage.toText`. */
fun partsToText(parts: List<UIMessagePart>): String = parts.joinToString(separator = "\n") { part ->
    when (part) {
        is UIMessagePart.Text -> part.text
        else -> ""
    }
}

fun partsContainBase64(parts: List<UIMessagePart>): Boolean = parts.any { part ->
    when (part) {
        is UIMessagePart.Image -> part.url.startsWith("data:")
        is UIMessagePart.Tool -> partsContainBase64(part.output)
        else -> false
    }
}

fun mediaPersistenceFailurePart(image: UIMessagePart.Image): UIMessagePart.Text {
    val marker = MessageMediaFailureMetadata(
        reason = MessageMediaFailureReason.PERSISTENCE_FAILED,
        mediaKind = MessageMediaKind.IMAGE,
    ).toMetadata()
    return UIMessagePart.Text(
        text = "",
        metadata = JsonObject(image.metadata.orEmpty() + marker),
    )
}

fun UIMessagePart.mediaFailureMetadataOrNull(): MessageMediaFailureMetadata? {
    val failure = metadataAs<MessageMediaFailureMetadata>() ?: return null
    return failure.takeIf { it.reason != null && it.mediaKind != null }
}

fun List<UIMessagePart>.countMediaPersistenceFailures(): Int = sumOf { part ->
    when (part) {
        is UIMessagePart.Tool -> part.output.countMediaPersistenceFailures()
        else -> if (
            part.mediaFailureMetadataOrNull()?.reason == MessageMediaFailureReason.PERSISTENCE_FAILED
        ) {
            1
        } else {
            0
        }
    }
}

fun stripUnpersistableBase64(parts: List<UIMessagePart>): List<UIMessagePart> = parts.map { part ->
    when (part) {
        is UIMessagePart.Image -> if (part.url.startsWith("data:")) mediaPersistenceFailurePart(part) else part
        is UIMessagePart.Tool -> part.copy(output = stripUnpersistableBase64(part.output))
        else -> part
    }
}

private const val MEDIA_FAILURE_TOOL_RESULT =
    "{\"status\":\"failed\",\"error\":\"Image bytes could not be persisted and are unavailable.\"}"
private const val TERMINAL_REPLAY_MARKER = "[Previous assistant response did not complete.]"

/**
 * Builds the Provider-facing history without mutating the visible/persisted conversation.
 *
 * A non-success assistant message is a draft, not lossless Provider state: opaque metadata and
 * reasoning may represent an incomplete wire item, and an open tool call cannot be replayed as a
 * completed call. Valid text/media and fully paired tool facts remain available, followed by an
 * explicit request-only marker so partial text is not presented as a normal completed answer.
 *
 * Terminal messages are projected atomically by replay-safe provider steps: a contiguous prefix
 * of Content (Text/Image/Reasoning) followed by Tools with a valid call envelope and replayable
 * result output forms a complete wire pair. This does not describe live tool execution state.
 * Reasoning within a complete step is preserved; the incomplete tail after the last safe Tool
 * keeps its Text/Image for context but loses Reasoning and opaque metadata. An unsafe/pending
 * Tool boundary causes fail-closed truncation: nothing past that boundary is replayed, preventing
 * partial steps from being spliced together.
 */
fun List<UIMessage>.replaySafeProjection(): List<UIMessage> = mapNotNull { message ->
    message.replaySafeProjection()
}

fun UIMessage.replaySafeProjection(): UIMessage? {
    val isTerminalAssistant = role == MessageRole.ASSISTANT && terminalStatus != null
    if (!isTerminalAssistant) {
        val projectedParts = parts.replaySafeParts(
            toolOutput = false,
        )
        val projected = copy(parts = projectedParts)
        return projected.takeIf { it.isValidToUpload() }
    }

    val (completeParts, tailParts) = splitTerminalCompletePrefix(parts)
    val projectedComplete = completeParts.replaySafeParts(
        toolOutput = false,
    )
    val projectedTail = tailParts.replaySafeTailParts()
    val projectedParts = (projectedComplete + projectedTail).toMutableList()
    val hasIncompleteTail = tailParts.isNotEmpty()
    if (projectedParts.isNotEmpty() && hasIncompleteTail) {
        projectedParts += UIMessagePart.Text(TERMINAL_REPLAY_MARKER)
    }
    val completePartCount = projectedComplete.size
    val projected = copy(
        parts = projectedParts,
        providerMetadata = null,
        terminalStatus = null,
        terminalReason = null,
        terminalDetail = null,
        providerReplayProjection = ProviderReplayProjection(
            completePartCount = completePartCount,
            hasIncompleteTail = hasIncompleteTail,
        ),
    )
    return projected.takeIf { it.isValidToUpload() }
}

/**
 * 返回最终消息投影中可保守确认会被 Provider 回放的 Tool Result ordinal。
 * 普通协议使用完整安全前缀；Responses opaque 历史还要求本地结果能与原始 function_call 配对。
 * 无法确认时宁可漏报，调用方不得把它解释成 serializer 的精确发送清单。
 */
fun UIMessage.confirmedReplayableToolOrdinals(): Set<Int> {
    val completeParts = providerReplayProjection?.let { projection ->
        parts.take(projection.completePartCount)
    } ?: parts
    val opaqueCallCounts = metadataAs<OpenAIResponseMetadata>()
        ?.outputItemGroups
        ?.flatten()
        ?.mapNotNull { item ->
            item["call_id"]?.jsonPrimitive?.contentOrNull?.takeIf {
                item["type"]?.jsonPrimitive?.contentOrNull == "function_call"
            }
        }
        ?.groupingBy { it }
        ?.eachCount()
        ?.toMutableMap()
    return completeParts.filterIsInstance<UIMessagePart.Tool>().mapIndexedNotNull { ordinal, tool ->
        val opaqueVisible = if (opaqueCallCounts == null) {
            true
        } else {
            val remaining = opaqueCallCounts.getOrDefault(tool.providerCallId, 0)
            if (remaining > 0) opaqueCallCounts[tool.providerCallId] = remaining - 1
            remaining > 0
        }
        ordinal.takeIf { opaqueVisible && tool.hasReplayResult }
    }.toSet()
}

/**
 * Splits the parts of a terminal assistant message into a replay-safe call/result prefix and an
 * incomplete tail.
 *
 * A replay-safe provider step is: Content (Reasoning/Text/Image) followed by Tools with valid
 * call envelopes and replayable result output.
 * The split happens at the first unsafe boundary: a pending, unexecuted, or envelope-damaged Tool
 * causes fail-closed truncation — everything from that point is the incomplete tail, even if a
 * a later Tool with output appears (non-contiguous structure).
 */
private fun splitTerminalCompletePrefix(
    parts: List<UIMessagePart>,
): Pair<List<UIMessagePart>, List<UIMessagePart>> {
    var splitIndex = parts.size
    for (i in parts.indices) {
        val part = parts[i]
        if (part is UIMessagePart.Tool && (!part.hasReplayResult || !part.hasReplaySafeEnvelope())) {
            splitIndex = i
            break
        }
    }
    // The complete prefix includes the Content and safe Tools up to (not including) the unsafe
    // boundary. If the split is at a Tool boundary, we must also ensure that Content parts
    // immediately before an unsafe Tool are still part of the tail (they belong to the
    // incomplete step), not the complete prefix.
    // Walk back from splitIndex to exclude trailing Content parts that belong to the
    // incomplete step (the unsafe Tool's own preceding reasoning/text).
    var completeEnd = splitIndex
    while (completeEnd > 0) {
        val prev = parts[completeEnd - 1]
        if (prev is UIMessagePart.Tool && prev.hasReplayResult && prev.hasReplaySafeEnvelope()) {
            break
        }
        if (prev is UIMessagePart.Tool) {
            // Another unsafe tool earlier — shouldn't happen since we break at first, but guard.
            break
        }
        // This is a Content part (Reasoning/Text/Image/etc) trailing before the unsafe boundary.
        // It belongs to the incomplete step, not a complete one.
        completeEnd--
    }
    val complete = parts.subList(0, completeEnd)
    val tail = parts.subList(completeEnd, parts.size)
    return complete to tail
}

private fun List<UIMessagePart>.replaySafeParts(
    toolOutput: Boolean,
): List<UIMessagePart> = mapNotNull { part ->
    when (part) {
        is UIMessagePart.Text -> {
            if (part.mediaFailureMetadataOrNull() == null) {
                part
            } else if (toolOutput) {
                UIMessagePart.Text(MEDIA_FAILURE_TOOL_RESULT)
            } else {
                null
            }
        }

        is UIMessagePart.Reasoning -> part

        is UIMessagePart.Image -> {
            if (part.url.startsWith("data:")) {
                if (toolOutput) UIMessagePart.Text(MEDIA_FAILURE_TOOL_RESULT) else null
            } else {
                part
            }
        }

        is UIMessagePart.Tool -> {
            val output = part.output.replaySafeParts(
                toolOutput = true,
            )
            part.copy(
                output = if (part.hasReplayResult && output.isEmpty()) {
                    listOf(UIMessagePart.Text(MEDIA_FAILURE_TOOL_RESULT))
                } else {
                    output
                },
            )
        }

        else -> part
    }
}

/**
 * Projects the incomplete tail of a terminal assistant message: Text/Image are kept as
 * context, but Reasoning and opaque metadata are removed so partial provider state is
 * never replayed as if it were complete.
 */
private fun List<UIMessagePart>.replaySafeTailParts(): List<UIMessagePart> = mapNotNull { part ->
    when (part) {
        is UIMessagePart.Text -> {
            if (part.mediaFailureMetadataOrNull() == null) {
                part.copy(metadata = part.metadata.withoutOpaqueReplayMetadata())
            } else {
                null
            }
        }

        is UIMessagePart.Reasoning -> null

        is UIMessagePart.Image -> if (part.url.startsWith("data:")) {
            null
        } else {
            part.copy(metadata = part.metadata.withoutOpaqueReplayMetadata())
        }

        is UIMessagePart.Tool -> null

        else -> part
    }
}

private fun UIMessagePart.Tool.hasReplaySafeEnvelope(): Boolean {
    if (providerCallId.isBlank() || toolName.isBlank()) return false
    return runCatching {
        json.parseToJsonElement(input.ifBlank { "{}" })
    }.isSuccess
}

fun UIMessage.finishReasoning(): UIMessage {
    return copy(
        parts = parts.map { part ->
            when (part) {
                is UIMessagePart.Reasoning -> {
                    if (part.finishedAt == null) {
                        part.copy(
                            finishedAt = Clock.System.now()
                        )
                    } else {
                        part
                    }
                }

                else -> part
            }
        }
    )
}

fun UIMessage.finishPendingTools(
    transform: (UIMessagePart.Tool) -> UIMessagePart.Tool
): UIMessage {
    val updatedParts = parts.map { part ->
        if (part is UIMessagePart.Tool && !part.hasReplayResult && part.isPending) {
            transform(part)
        } else {
            part
        }
    }

    if (updatedParts == parts) {
        return this
    }

    return copy(
        parts = updatedParts,
        finishedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    ).finishReasoning()
}

/**
 * 标记执行中断的工具（output 为空但非等待用户）。
 * 用于超时/异常导致工具执行被中断但未被正常清理的场景。
 * 保留原 interactionState（不标记为 Denied），仅填充中断标记 output。
 */
fun UIMessage.finishInterruptedTools(
    transform: (UIMessagePart.Tool) -> UIMessagePart.Tool
): UIMessage {
    val updatedParts = parts.map { part ->
        if (part is UIMessagePart.Tool && !part.hasReplayResult && !part.isPending) {
            transform(part)
        } else {
            part
        }
    }

    if (updatedParts == parts) {
        return this
    }

    return copy(
        parts = updatedParts,
        finishedAt = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
    ).finishReasoning()
}

@Serializable
data class MessageChunk(
    val id: String,
    val model: String,
    val choices: List<UIMessageChoice>,
    val usage: ProviderUsageSnapshot? = null,
)

@Serializable
data class UIMessageChoice(
    val index: Int,
    val delta: UIMessage?,
    val message: UIMessage?,
    val finishReason: String?
)
