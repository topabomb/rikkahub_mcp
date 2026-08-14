package net.weero.measix.pilot.data.ai.subassistant

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

/**
 * 子助手调用的稳定状态枚举。
 * 终态后不可回到 running。
 */
@Serializable
enum class SubAssistantCallState {
    @SerialName("starting") STARTING,
    @SerialName("running") RUNNING,
    @SerialName("completed") COMPLETED,
    @SerialName("failed") FAILED,
    @SerialName("stopped") STOPPED,
    @SerialName("unavailable") UNAVAILABLE;

    fun isTerminal(): Boolean = this == COMPLETED || this == FAILED || this == STOPPED || this == UNAVAILABLE

    fun canTransitionTo(next: SubAssistantCallState): Boolean {
        if (this == next) return true
        return when (this) {
            STARTING -> next == RUNNING || next == UNAVAILABLE || next == FAILED || next == STOPPED
            RUNNING -> next == COMPLETED || next == FAILED || next == STOPPED
            else -> false // 终态不可再变
        }
    }
}

/**
 * 子助手调用过程的阶段标记。
 * phase 不使用本地化字符串。
 */
@Serializable
enum class SubAssistantCallPhase {
    @SerialName("preparing") PREPARING,
    @SerialName("model_waiting") MODEL_WAITING,
    @SerialName("reasoning_streaming") REASONING_STREAMING,
    @SerialName("answer_streaming") ANSWER_STREAMING,
    @SerialName("tool_executing") TOOL_EXECUTING,
    @SerialName("between_steps") BETWEEN_STEPS,
    @SerialName("awaiting_user") AWAITING_USER,
}

@Serializable
data class SubAssistantUserInteraction(
    @SerialName("interaction_id") val interactionId: String,
    @SerialName("message_id") val messageId: String,
    @SerialName("tool_ordinal") val toolOrdinal: Int,
    @SerialName("tool_name") val toolName: String,
    @SerialName("input") val input: String,
)

/**
 * 嵌入在 UIMessagePart.Tool.metadata["sub_assistant_call"] 中的类型安全结构。
 */
@Serializable
data class SubAssistantCallMetadata(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    @SerialName("run_id") val runId: String,
    @SerialName("previous_run_id") val previousRunId: String? = null,
    @SerialName("target_assistant_id") val targetAssistantId: String,
    @SerialName("target_name_snapshot") val targetNameSnapshot: String,
    @SerialName("child_conversation_id") val childConversationId: String? = null,
    @SerialName("child_task_node_id") val childTaskNodeId: String? = null,
    @SerialName("state") val state: SubAssistantCallState = SubAssistantCallState.STARTING,
    @SerialName("phase") val phase: SubAssistantCallPhase? = null,
    @SerialName("active_tool_name") val activeToolName: String? = null,
    @SerialName("preview") val preview: String? = null,
    @SerialName("reason") val reason: String? = null,
    @SerialName("has_non_text_output") val hasNonTextOutput: Boolean = false,
    @SerialName("user_interaction") val userInteraction: SubAssistantUserInteraction? = null,
)

private const val METADATA_KEY = "sub_assistant_call"

/**
 * 从 Tool.metadata 中读取 sub_assistant_call，不存在返回 null。
 */
fun UIMessagePart.Tool.getSubAssistantCallMetadata(json: Json): SubAssistantCallMetadata? {
    val raw = this.metadata?.get(METADATA_KEY) ?: return null
    return runCatching {
        json.decodeFromJsonElement(SubAssistantCallMetadata.serializer(), raw)
    }.getOrNull()
}

/**
 * 将 [patch] merge 到 Tool.metadata 的 sub_assistant_call 字段下。
 * 绝不替换整个 metadata，保留 Provider 不透明字段（Gemini functionCallId / thoughtSignature 等）。
 */
fun UIMessagePart.Tool.mergeSubAssistantCallMetadata(
    json: Json,
    patch: SubAssistantCallMetadata,
): UIMessagePart.Tool {
    val existingMetadata = this.metadata ?: JsonObject(emptyMap())
    val encoded = json.encodeToJsonElement(SubAssistantCallMetadata.serializer(), patch)
    val newMetadata = JsonObject(
        existingMetadata.toMutableMap().apply {
            put(METADATA_KEY, encoded)
        }
    )
    return this.copy(metadata = newMetadata)
}

/**
 * 构建初始 metadata。
 */
fun buildInitialSubAssistantCallMetadata(
    runId: String,
    targetAssistantId: Uuid,
    targetNameSnapshot: String,
    previousRunId: String? = null,
): SubAssistantCallMetadata = SubAssistantCallMetadata(
    runId = runId,
    previousRunId = previousRunId,
    targetAssistantId = targetAssistantId.toString(),
    targetNameSnapshot = targetNameSnapshot,
    state = SubAssistantCallState.STARTING,
)

internal const val ASSISTANT_CALL_EXTRA_TTS = "tts"
internal const val ASSISTANT_CALL_EXTRA_TOOL_CALLS = "tool_calls"

private val ASSISTANT_CALL_EXTRAS = setOf(
    ASSISTANT_CALL_EXTRA_TTS,
    ASSISTANT_CALL_EXTRA_TOOL_CALLS,
)

/** 本次 run 的 TTS 调用次数与朗读字符合计；有调用才写入结果。 */
data class SubAssistantTtsStats(
    val calls: Int,
    val chars: Int,
)

/**
 * 解析 `extras`：只保留约定项，忽略未知值。
 */
internal fun parseAssistantCallExtras(raw: kotlinx.serialization.json.JsonElement?): Set<String> {
    val array = raw as? JsonArray ?: return emptySet()
    return array.mapNotNull { element ->
        (element as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
    }.filter { it in ASSISTANT_CALL_EXTRAS }.toSet()
}

internal fun parseAssistantCallExtrasFromInput(input: String): Set<String> {
    val obj = runCatching {
        Json.parseToJsonElement(input) as? JsonObject
    }.getOrNull() ?: return emptySet()
    return parseAssistantCallExtras(obj["extras"])
}

/**
 * 构建终态 Tool Result JSON。
 *
 * [ttsStats] 有调用才写入。 [toolCalls] / [ttsTexts] 仅在 Caller 通过 `extras` 请求且非空时写入。
 * [detail] 仅在 [reason] 为 `runtime_error` 且非空时写入，并按字符上限裁剪。
 */
fun buildSubAssistantCallResult(
    json: Json,
    status: String,
    assistantName: String,
    content: String,
    reason: String? = null,
    detail: String? = null,
    hasNonTextOutput: Boolean = false,
    toolCalls: List<Pair<String, Int>> = emptyList(),
    ttsTexts: List<String> = emptyList(),
    ttsStats: SubAssistantTtsStats? = null,
): String {
    val obj = buildJsonObject {
        put("status", status)
        // 只有 completed 才返回 assistant_name 和 content
        if (status == "completed") {
            put("assistant_name", assistantName)
            put("content", content)
        }
        if (reason != null) put("reason", reason)
        if (reason == "runtime_error") {
            val clipped = clipRuntimeErrorDetail(detail.orEmpty())
            if (clipped.isNotEmpty()) put("detail", clipped)
        }
        if (hasNonTextOutput) put("has_non_text_output", true)
        if (ttsStats != null && ttsStats.calls > 0) {
            put("tts_stats", buildJsonObject {
                put("calls", ttsStats.calls)
                put("chars", ttsStats.chars)
            })
        }
        if (toolCalls.isNotEmpty()) {
            put("tool_calls", buildJsonObject {
                put(
                    "header",
                    JsonArray(listOf(JsonPrimitive("name"), JsonPrimitive("count"))),
                )
                put(
                    "rows",
                    JsonArray(
                        toolCalls.map { (name, count) ->
                            JsonArray(listOf(JsonPrimitive(name), JsonPrimitive(count)))
                        },
                    ),
                )
            })
        }
        if (ttsTexts.isNotEmpty()) {
            put("tts", buildJsonObject {
                put(
                    "header",
                    JsonArray(listOf(JsonPrimitive("n"), JsonPrimitive("text"))),
                )
                put(
                    "rows",
                    JsonArray(
                        ttsTexts.mapIndexed { index, text ->
                            JsonArray(listOf(JsonPrimitive(index + 1), JsonPrimitive(text)))
                        },
                    ),
                )
            })
        }
    }
    return obj.toString()
}
