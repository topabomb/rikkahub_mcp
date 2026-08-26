package me.rerere.ai.ui

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.util.json
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.common.http.jsonPrimitiveOrNull

/**
 * [UIMessagePart.metadata] 的类型安全 schema
 *
 * metadata 在序列化层仍然是 [JsonObject], 这里只是为读写提供编译期类型:
 * - 读: `part.metadataAs<ClaudeReasoningMetadata>()?.signature`
 * - 写: `part.metadata = ClaudeReasoningMetadata(signature = ...).toMetadata()`
 *
 * 所有字段必须可空且 key 与历史数据保持一致(必要时用 [SerialName]),
 * 否则旧会话中持久化的 metadata 将无法解析
 */
sealed interface Metadata

sealed interface PartMetadata : Metadata

sealed interface MessageMetadata : Metadata

/**
 * Claude thinking block 的元数据, 回传时需要携带 signature
 */
@Serializable
data class ClaudeReasoningMetadata(
    val signature: String? = null,
    val redactedData: String? = null,
) : PartMetadata

/**
 * OpenAI Responses API reasoning item 的元数据。id/encrypted_content 是 endpoint 产生的不透明状态，
 * 只有 [sourceProfile] 与当前请求来源兼容时才能回传；旧会话缺少来源时保留向后兼容。
 */
@Serializable
data class OpenAIReasoningMetadata(
    @SerialName("reasoning_id")
    val reasoningId: String? = null,
    @SerialName("encrypted_content")
    val encryptedContent: String? = null,
    @SerialName("source_profile")
    val sourceProfile: OpenAIResponseSourceProfile? = null,
) : PartMetadata

/**
 * Marks request-only attachment fact text produced by the input projection layer.
 *
 * Providers normally serialize this like ordinary text in its existing message or tool-result
 * container. OpenAI Responses additionally uses the marker to retain this request-local text when
 * an assistant message is otherwise replayed from opaque [OpenAIResponseMetadata.outputItemGroups].
 */
@Serializable
data class AttachmentProjectionTextMetadata(
    @SerialName("attachment_projection_text")
    val attachmentProjectionText: Boolean? = null,
) : PartMetadata

/**
 * OpenAI Responses 在 `store=false` 时需要由客户端把上一轮完整的 `response.output`
 * 作为下一轮 input 回放。可见的 [UIMessagePart] 只是 UI 投影，无法无损表达 web_search_call、
 * image_generation_call、message.phase 以及未来新增的 output item，因此原始输出项单独保存在消息元数据中。
 *
 * [wireFormat] 记录产生这些输出项的线协议形状，[sourceProfile] 记录更严格的 endpoint 来源。
 * 它们由实际 endpoint 自动确定，不是用户配置；当会话切换到不同形状或来源的 endpoint 时，
 * provider 会回退到 UIMessage 重建，避免跨协议/来源原样发送不透明状态。
 * [outputItemGroups] 按每次 response 保留批次边界，因为协议要求先回放该 response 的全部 output，
 * 再追加这一批函数调用的本地执行结果；把多次工具续轮压平成单列表会破坏并行调用的顺序。
 */
@Serializable
data class OpenAIResponseMetadata(
    @SerialName("wire_format")
    val wireFormat: OpenAIResponseWireFormat,
    @SerialName("output_item_groups")
    val outputItemGroups: List<List<JsonObject>>,
    @SerialName("source_profile")
    val sourceProfile: OpenAIResponseSourceProfile? = null,
) : MessageMetadata

@Serializable
enum class OpenAIResponseWireFormat {
    @SerialName("openai")
    OPENAI,

    @SerialName("deepseek")
    DEEPSEEK,
}

@Serializable
enum class OpenAIResponseSourceProfile {
    @SerialName("openai")
    OPENAI,

    @SerialName("openai_compatible")
    OPENAI_COMPATIBLE,

    @SerialName("volc_ark")
    VOLC_ARK,

    @SerialName("deepseek")
    DEEPSEEK,

    @SerialName("mimo")
    MIMO,
}

/**
 * Google Gemini 部件(functionCall/inlineData)的 thoughtSignature, 回传时需要携带
 */
@Serializable
data class GoogleThoughtMetadata(
    val thoughtSignature: String? = null,
    val functionCallId: String? = null,
    val thought: Boolean? = null,
    val inlineData: JsonObject? = null,
) : PartMetadata

/**
 * 文件编辑类工具(如 workspace_edit_file)输出部件的元数据,
 * 携带 unified diff 文本供 UI 渲染 diff view, 不会发送给 API
 */
@Serializable
data class DiffMetadata(
    val diff: String? = null,
) : PartMetadata

/**
 * OpenRouter Chat Completions 的结构化推理信封。
 *
 * 只在当前请求 host 为 `openrouter.ai` 时回传 [reasoningDetails]。它不是通用 UI 字段，
 * 也不能在切换到其他 OpenAI-compatible host 后继续发送。
 * 流式分片按到达顺序累积：相同 id/index 合并 text/summary，新条目追加。
 */
@Serializable
data class OpenRouterReasoningMetadata(
    @SerialName("reasoning_details")
    val reasoningDetails: JsonArray? = null,
) : PartMetadata

/**
 * 将 metadata 解析为类型化的 [PartMetadata], 解析失败或 metadata 为 null 时返回 null
 *
 * 由于 json 配置了 ignoreUnknownKeys, 不同 provider 的 metadata 互不干扰
 * (例如切换 provider 后, OpenAI 写入的 reasoning 元数据不会影响 Claude 的解析)
 */
inline fun <reified T : PartMetadata> UIMessagePart.metadataAs(): T? = metadata?.let {
    runCatching { json.decodeFromJsonElement<T>(it) }.getOrNull()
}

inline fun <reified T : MessageMetadata> UIMessage.metadataAs(): T? = providerMetadata?.let {
    runCatching { json.decodeFromJsonElement<T>(it) }.getOrNull()
}

/**
 * 将类型化的 [PartMetadata] 编码为 metadata [JsonObject]
 *
 * 由于 json 配置了 explicitNulls = false, 值为 null 的字段不会写入
 */
inline fun <reified T : Metadata> T.toMetadata(): JsonObject =
    json.encodeToJsonElement(this).jsonObject

/**
 * 流式 Responses 会在终态一次交付一组 output items，而同一个 UIMessage 可能包含多次
 * assistant -> tool 子步骤。相同线协议的元数据必须按生成顺序累积，不能覆盖前一工具步骤。
 */
/**
 * OpenRouter 把 reasoning_details 拆成增量 delta。合并层必须拼回完整有序序列，
 * 否则工具续轮只会带回最后一片，既丢 details 也会挡住 reasoning_content 兜底。
 */
internal fun mergeReasoningPartMetadata(
    existing: JsonObject?,
    incoming: JsonObject?,
): JsonObject? {
    if (incoming == null) return existing
    if (existing == null) return incoming
    val existingDetails = existing.openRouterReasoningDetailsOrNull()
    val incomingDetails = incoming.openRouterReasoningDetailsOrNull()
    if (existingDetails.isNullOrEmpty() && incomingDetails.isNullOrEmpty()) return incoming
    if (incomingDetails.isNullOrEmpty()) return existing
    if (existingDetails.isNullOrEmpty()) return incoming
    return OpenRouterReasoningMetadata(
        reasoningDetails = mergeOpenRouterReasoningDetails(existingDetails, incomingDetails),
    ).toMetadata()
}

internal fun mergeOpenRouterReasoningDetails(
    existing: JsonArray?,
    incoming: JsonArray?,
): JsonArray? {
    if (incoming.isNullOrEmpty()) return existing
    if (existing.isNullOrEmpty()) return incoming
    val merged = existing.toMutableList()
    for (item in incoming) {
        val incomingObject = item.jsonObjectOrNull
        if (incomingObject == null) {
            merged.add(item)
            continue
        }
        val matchIndex = merged.indexOfFirst { candidate ->
            val existingObject = candidate.jsonObjectOrNull ?: return@indexOfFirst false
            isSameOpenRouterReasoningItem(existingObject, incomingObject)
        }
        if (matchIndex >= 0) {
            val existingObject = merged[matchIndex].jsonObjectOrNull
            merged[matchIndex] = if (existingObject != null) {
                mergeOpenRouterReasoningItem(existingObject, incomingObject)
            } else {
                item
            }
        } else {
            merged.add(item)
        }
    }
    return JsonArray(merged)
}

private fun JsonObject.openRouterReasoningDetailsOrNull(): JsonArray? {
    if (!containsKey("reasoning_details")) return null
    return runCatching {
        json.decodeFromJsonElement<OpenRouterReasoningMetadata>(this).reasoningDetails
    }.getOrNull()
}

private fun isSameOpenRouterReasoningItem(existing: JsonObject, incoming: JsonObject): Boolean {
    val existingId = existing["id"]?.jsonPrimitiveOrNull?.contentOrNull
    val incomingId = incoming["id"]?.jsonPrimitiveOrNull?.contentOrNull
    if (!existingId.isNullOrEmpty() && existingId == incomingId) return true
    val existingIndex = existing["index"]
    val incomingIndex = incoming["index"]
    if (existingIndex == null || incomingIndex == null || existingIndex != incomingIndex) return false
    val existingType = existing["type"]?.jsonPrimitiveOrNull?.contentOrNull
    val incomingType = incoming["type"]?.jsonPrimitiveOrNull?.contentOrNull
    return existingType == null || incomingType == null || existingType == incomingType
}

private fun mergeOpenRouterReasoningItem(existing: JsonObject, incoming: JsonObject): JsonObject {
    return buildJsonObject {
        existing.forEach { key, value -> put(key, value) }
        incoming.forEach { key, value ->
            when (key) {
                "text", "summary" -> {
                    val previous = existing[key]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty()
                    val next = value.jsonPrimitiveOrNull?.contentOrNull.orEmpty()
                    put(key, JsonPrimitive(previous + next))
                }
                else -> put(key, value)
            }
        }
    }
}

internal fun mergeMessageMetadata(current: JsonObject?, incoming: JsonObject?): JsonObject? {
    if (incoming == null) return current
    if (current == null) return incoming

    val currentResponse = runCatching {
        json.decodeFromJsonElement<OpenAIResponseMetadata>(current)
    }.getOrNull()
    val incomingResponse = runCatching {
        json.decodeFromJsonElement<OpenAIResponseMetadata>(incoming)
    }.getOrNull()
    val sourceProfilesCompatible = currentResponse?.sourceProfile == null ||
            incomingResponse?.sourceProfile == null ||
            currentResponse.sourceProfile == incomingResponse.sourceProfile
    return if (currentResponse != null &&
        incomingResponse != null &&
        currentResponse.wireFormat == incomingResponse.wireFormat &&
        sourceProfilesCompatible
    ) {
        currentResponse.copy(
            outputItemGroups = currentResponse.outputItemGroups + incomingResponse.outputItemGroups,
            sourceProfile = incomingResponse.sourceProfile ?: currentResponse.sourceProfile,
        ).toMetadata()
    } else {
        incoming
    }
}
