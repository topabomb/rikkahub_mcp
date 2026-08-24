package me.rerere.ai.ui

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.util.json
import kotlin.math.roundToInt
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
) {
    private fun appendChunk(chunk: MessageChunk): UIMessage {
        val choice = chunk.choices.getOrNull(0)
        val message = choice?.delta ?: choice?.message
        return message?.let { delta ->
            /*
             * 一个 UIMessage 会承载同一用户轮次中的多次 assistant -> tool 子步骤，已执行 Tool
             * 就是这些步骤之间的边界。流式协议并不保证 reasoning/content/tool_calls 总在同一
             * delta 中到达，因此当前未完成步骤不能简单按 delta 到达顺序追加：DeepSeek 要求
             * reasoning_content、content 与 tool_calls 在下一次请求中仍属于同一 assistant 消息。
             *
             * 这里仅规范化“最后一个已执行 Tool 之后”的当前步骤，保持既有存储结构和历史步骤
             * 不变，并确保当前步骤始终为 Reasoning -> Content -> pending Tool(s)。
             */
            fun List<UIMessagePart>.currentStepStart(): Int =
                indexOfLast { it is UIMessagePart.Tool && it.isExecuted } + 1

            fun List<UIMessagePart>.firstPendingToolIndex(stepStart: Int): Int {
                val relativeIndex = subList(stepStart, size).indexOfFirst {
                    it is UIMessagePart.Tool && !it.isExecuted
                }
                return if (relativeIndex >= 0) stepStart + relativeIndex else size
            }

            fun List<UIMessagePart>.insertAt(index: Int, part: UIMessagePart): List<UIMessagePart> =
                toMutableList().apply { add(index, part) }

            fun UIMessagePart.hasProviderPartBoundary(): Boolean {
                val googleMetadata = metadataAs<GoogleThoughtMetadata>()
                val claudeMetadata = metadataAs<ClaudeReasoningMetadata>()
                return googleMetadata?.thoughtSignature != null ||
                        googleMetadata?.inlineData != null ||
                        claudeMetadata?.redactedData != null
            }

            // Handle Parts
            var newParts = delta.parts.fold(parts) { acc, deltaPart ->
                when (deltaPart) {
                    is UIMessagePart.Text -> {
                        // Skip empty text deltas
                        if (deltaPart.text.isEmpty() && deltaPart.metadata == null) {
                            acc
                        } else {
                            val stepStart = acc.currentStepStart()
                            val insertIndex = acc.firstPendingToolIndex(stepStart)
                            val lastPart = acc.getOrNull(insertIndex - 1)
                            if (lastPart is UIMessagePart.Text &&
                                !lastPart.hasProviderPartBoundary() &&
                                !deltaPart.hasProviderPartBoundary()
                            ) {
                                // 合并当前步骤的文本，即使 Tool delta 已先到达也不能把文本放到 Tool 后面。
                                acc.mapIndexed { index, part ->
                                    if (index == insertIndex - 1) {
                                        lastPart.copy(text = lastPart.text + deltaPart.text)
                                    } else {
                                        part
                                    }
                                }
                            } else {
                                acc.insertAt(insertIndex, deltaPart)
                            }
                        }
                    }

                    is UIMessagePart.Image -> {
                        val stepStart = acc.currentStepStart()
                        val insertIndex = acc.firstPendingToolIndex(stepStart)
                        val lastPart = acc.getOrNull(insertIndex - 1)
                        val incomingComplete = isCompleteImageUrl(deltaPart.url)
                        if (lastPart is UIMessagePart.Image &&
                            !lastPart.hasProviderPartBoundary() &&
                            !deltaPart.hasProviderPartBoundary() &&
                            !incomingComplete
                        ) {
                            // Raw fragments continue the current image. Complete URLs are new images.
                            acc.mapIndexed { index, part ->
                                if (index == insertIndex - 1) {
                                    lastPart.copy(
                                        url = lastPart.url + deltaPart.url,
                                        metadata = deltaPart.metadata ?: lastPart.metadata
                                    )
                                } else {
                                    part
                                }
                            }
                        } else {
                            acc.insertAt(
                                insertIndex,
                                UIMessagePart.Image(
                                    url = renderableImageUrl(deltaPart.url),
                                    metadata = deltaPart.metadata,
                                )
                            )
                        }
                    }

                    is UIMessagePart.Reasoning -> {
                        // Skip empty reasoning deltas
                        if (deltaPart.reasoning.isEmpty() && deltaPart.metadata == null) {
                            acc
                        } else {
                            val stepStart = acc.currentStepStart()
                            val reasoningIndex = (acc.lastIndex downTo stepStart).firstOrNull { index ->
                                acc[index] is UIMessagePart.Reasoning
                            }
                            if (reasoningIndex != null &&
                                !acc[reasoningIndex].hasProviderPartBoundary() &&
                                !deltaPart.hasProviderPartBoundary()
                            ) {
                                val existing = acc[reasoningIndex] as UIMessagePart.Reasoning
                                acc.mapIndexed { index, part ->
                                    if (index == reasoningIndex) {
                                        UIMessagePart.Reasoning(
                                            reasoning = existing.reasoning + deltaPart.reasoning,
                                            createdAt = existing.createdAt,
                                            finishedAt = null,
                                        ).also {
                                            it.metadata = mergeReasoningPartMetadata(
                                                existing.metadata,
                                                deltaPart.metadata,
                                            )
                                        }
                                    } else {
                                        part
                                    }
                                }
                            } else {
                                // Reasoning 属于整个 assistant 工具步骤，必须位于该步骤内容和 Tool 之前。
                                val insertIndex = reasoningIndex?.plus(1) ?: stepStart
                                acc.insertAt(insertIndex, deltaPart)
                            }
                        }
                    }

                    is UIMessagePart.Tool -> {
                        if (deltaPart.toolCallId.isBlank()) {
                            // A blank-ID delta continues the latest pending tool in this assistant step.
                            // Never cross an executed Tool boundary: that would mutate an earlier request's history.
                            val stepStart = acc.currentStepStart()
                            val lastTool = acc.subList(stepStart, acc.size)
                                .lastOrNull { it is UIMessagePart.Tool && !it.isExecuted } as? UIMessagePart.Tool
                            if (lastTool != null) {
                                acc.map { part ->
                                    if (part === lastTool) part.merge(deltaPart) else part
                                }
                            } else {
                                acc + deltaPart.copy()
                            }
                        } else {
                            // Has ID - only merge inside the current assistant step. Some compatible
                            // services reuse ids; an old executed Tool must remain immutable history.
                            val stepStart = acc.currentStepStart()
                            val existsPart = acc.subList(stepStart, acc.size).find {
                                it is UIMessagePart.Tool && it.toolCallId == deltaPart.toolCallId
                            } as? UIMessagePart.Tool
                            if (existsPart == null) {
                                acc + deltaPart.copy()
                            } else {
                                acc.map { part ->
                                    if (part is UIMessagePart.Tool && part.toolCallId == deltaPart.toolCallId) {
                                        part.merge(deltaPart)
                                    } else part
                                }
                            }
                        }
                    }

                    else -> {
                        println("delta part append not supported: $deltaPart")
                        acc
                    }
                }
            }
            // Handle Reasoning End
            if (parts.filterIsInstance<UIMessagePart.Reasoning>()
                    .isNotEmpty() && delta.parts.filterIsInstance<UIMessagePart.Reasoning>()
                    .isEmpty()
            ) {
                newParts = newParts.map { part ->
                    if (part is UIMessagePart.Reasoning && part.finishedAt == null) {
                        part.copy(finishedAt = Clock.System.now())
                    } else part
                }
            }
            // Handle annotations
            val newAnnotations = delta.annotations.ifEmpty {
                annotations
            }
            copy(
                parts = newParts,
                annotations = newAnnotations,
                providerMetadata = mergeMessageMetadata(providerMetadata, delta.providerMetadata),
            )
        } ?: this
    }

    fun summaryAsText(maxLength: Int = Int.MAX_VALUE): String {
        val text = "[${role.name}]: " + parts.joinToString(separator = "\n") { part ->
            when (part) {
                is UIMessagePart.Text -> part.text
                else -> ""
            }
        }
        return if (text.length > maxLength) text.take(maxLength) + "..." else text
    }

    fun toText() = parts.joinToString(separator = "\n") { part ->
        when (part) {
            is UIMessagePart.Text -> part.text
            else -> ""
        }
    }

    fun getTools() = parts.filterIsInstance<UIMessagePart.Tool>()

    fun isValidToUpload() = parts.any { part ->
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

    operator fun plus(chunk: MessageChunk): UIMessage {
        return this.appendChunk(chunk)
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
internal fun isCompleteImageUrl(url: String): Boolean {
    val value = url.trim()
    return value.startsWith("data:", ignoreCase = true) ||
        value.startsWith("http://", ignoreCase = true) ||
        value.startsWith("https://", ignoreCase = true) ||
        value.startsWith("file:", ignoreCase = true) ||
        value.startsWith("content:", ignoreCase = true) ||
        value.startsWith("android.resource:", ignoreCase = true)
}

internal fun renderableImageUrl(url: String, mimeType: String = "image/png"): String {
    val value = url.trim()
    if (value.isEmpty() || isCompleteImageUrl(value)) return value
    return "data:$mimeType;base64,$value"
}

/**
 * 处理MessageChunk合并
 *
 * @receiver 已有消息列表
 * @param chunk 消息chunk
 * @param model 模型, 可以不传，如果传了，会把模型id写入到消息，标记是哪个模型输出的消息
 * @return 新消息列表
 */
fun List<UIMessage>.handleMessageChunk(
    chunk: MessageChunk,
    model: Model? = null,
    assistantMessageId: Uuid? = null,
): List<UIMessage> {
    require(this.isNotEmpty()) {
        "messages must not be empty"
    }
    val choice = chunk.choices.getOrNull(0) ?: return this
    val message = choice.delta ?: choice.message ?: return this
    if (this.last().role != message.role) {
        val messageId = if (message.role == MessageRole.ASSISTANT) {
            assistantMessageId ?: Uuid.random()
        } else {
            Uuid.random()
        }
        val nextMessage = UIMessage(
            id = messageId,
            modelId = model?.id,
            role = message.role,
            parts = emptyList(),
        ) + chunk
        return this + nextMessage
    } else {
        val last = this.last() + chunk
        return this.dropLast(1) + last
    }
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
            else -> true
        }
    }
}

/**
 * Fraction of the configured threshold kept immediately after a context trim.
 *
 * A lower ratio moves the trim point less often, improving prompt-cache reuse at
 * the cost of discarding more history at each step.
 */
private const val CONTEXT_KEEP_RATIO = 0.5f

/**
 * Limits conversation history with a stepped (hysteresis) strategy.
 *
 * Unlike a sliding window, the start point only moves after [limit] is crossed
 * by a full stride. Appending messages inside the same stride therefore keeps
 * the request prefix stable. The result starts at a user message whenever the
 * retained history contains one, so an assistant reply and its tool activity
 * are never detached from the user turn that caused them.
 *
 * [limit] is a message-count trimming threshold, not a token or model context
 * window limit. Values less than or equal to zero disable automatic trimming.
 */
fun List<UIMessage>.limitContext(limit: Int): List<UIMessage> {
    if (limit <= 0 || size <= limit) return this

    val target = (limit * CONTEXT_KEEP_RATIO).roundToInt().coerceIn(1, limit)
    val stride = (limit - target).coerceAtLeast(1)
    val steppedStartIndex = (
        ((size - limit) / stride + 1) * stride
        ).coerceAtMost(lastIndex)

    return subList(findUserTurnStart(steppedStartIndex), size)
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
    const val OWNER_MESSAGE_MISSING = "owner_message_missing"
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
 */
fun List<UIMessage>.replaySafeProjection(): List<UIMessage> = mapNotNull { message ->
    message.replaySafeProjection()
}

fun UIMessage.replaySafeProjection(): UIMessage? {
    val isTerminalAssistant = role == MessageRole.ASSISTANT && terminalStatus != null
    val projectedParts = parts.replaySafeParts(
        terminalAssistant = isTerminalAssistant,
        toolOutput = false,
    ).toMutableList()
    if (isTerminalAssistant && projectedParts.isNotEmpty()) {
        projectedParts += UIMessagePart.Text(TERMINAL_REPLAY_MARKER)
    }
    val projected = copy(
        parts = projectedParts,
        providerMetadata = if (isTerminalAssistant) null else providerMetadata,
        terminalStatus = if (isTerminalAssistant) null else terminalStatus,
        terminalReason = if (isTerminalAssistant) null else terminalReason,
    )
    return projected.takeIf { it.isValidToUpload() }
}

private fun List<UIMessagePart>.replaySafeParts(
    terminalAssistant: Boolean,
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

        is UIMessagePart.Reasoning -> if (terminalAssistant) null else part

        is UIMessagePart.Image -> {
            if (part.url.startsWith("data:")) {
                if (toolOutput) UIMessagePart.Text(MEDIA_FAILURE_TOOL_RESULT) else null
            } else {
                part
            }
        }

        is UIMessagePart.Tool -> {
            if (terminalAssistant && (!part.isExecuted || !part.hasReplaySafeEnvelope())) {
                null
            } else {
                val output = part.output.replaySafeParts(
                    terminalAssistant = terminalAssistant,
                    toolOutput = true,
                )
                part.copy(
                    output = if (part.isExecuted && output.isEmpty()) {
                        listOf(UIMessagePart.Text(MEDIA_FAILURE_TOOL_RESULT))
                    } else {
                        output
                    },
                )
            }
        }

        else -> part
    }
}

private fun UIMessagePart.Tool.hasReplaySafeEnvelope(): Boolean {
    if (toolCallId.isBlank() || toolName.isBlank()) return false
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
        if (part is UIMessagePart.Tool && !part.isExecuted && part.approvalState is ToolApprovalState.Pending) {
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
 * 标记执行中断的工具（output 为空但 approvalState 非 Pending）。
 * 用于超时/异常导致工具执行被中断但未被正常清理的场景。
 * 保留原 approvalState（不标记为 Denied），仅填充中断标记 output。
 */
fun UIMessage.finishInterruptedTools(
    transform: (UIMessagePart.Tool) -> UIMessagePart.Tool
): UIMessage {
    val updatedParts = parts.map { part ->
        if (part is UIMessagePart.Tool && !part.isExecuted && part.approvalState !is ToolApprovalState.Pending) {
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
    val usage: TokenUsage? = null,
)

@Serializable
data class UIMessageChoice(
    val index: Int,
    val delta: UIMessage?,
    val message: UIMessage?,
    val finishReason: String?
)
