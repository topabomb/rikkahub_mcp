package me.rerere.ai.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

@Serializable
data class ToolCallLocator(
    val messageId: Uuid,
    val toolOrdinal: Int,
)

/**
 * 工具按 stable attachment refs 请求 Runtime 解析附件的统一结果。
 *
 * [parts] 是解析成功的只读 parts（如 Image）；[failureReason] 非空表示解析失败，
 * 原因字符串由 Runtime 定义，工具应原样透传给模型，不做本地解释。
 */
data class ToolAttachmentResolution(
    val parts: List<UIMessagePart> = emptyList(),
    val failureReason: String? = null,
)

/**
 * A resource created while producing tool output but not yet rooted by a durable checkpoint.
 * The generation owner must either publish the resource after the checkpoint succeeds or call
 * [discard] during failure/cancellation compensation.
 */
class ToolResourceLease(
    val publish: suspend () -> Unit,
    val discard: suspend () -> Unit,
)

/**
 * A domain tool failure whose model-visible result is already projected by the tool owner.
 * Generation records a FAILED terminal fact and preserves [output] verbatim.
 */
class ToolExecutionFailure(
    val output: List<UIMessagePart>,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * 通用工具执行上下文，提供 metadata 回写能力。
 * 不引入 App 的 Conversation 类型，保持 ai 模块的平台无关性。
 *
 * [messageId] + [toolOrdinal] 是本次执行在当前 ASSISTANT message 中的内部精确 locator；
 * [toolCallId] 保留给 Provider 协议，不能作为内存更新的唯一键。
 *
 * 工具获得的是执行时的资源访问能力，不是 Agent 的完整会话状态：
 * [resolveAttachments] 按 stable ref 批量解析附件，底层可以使用执行时刻的 durable
 * 消息快照（含本 run 内已完成的 Tool 结果），但这是 Runtime 的实现细节，不暴露给工具。
 */
data class ToolExecutionContext(
    val messageId: Uuid,
    val toolOrdinal: Int,
    val toolCallId: String,
    val reportMetadata: suspend (patch: JsonObject, checkpoint: Boolean) -> Unit,
    /** 按 stable attachment refs 批量解析附件 parts。 */
    val resolveAttachments: suspend (refs: List<String>) -> ToolAttachmentResolution,
    /** 委派类工具在派生会话确定后回写其 id，并入本次工具执行的 durable 事实。 */
    val reportChildConversation: suspend (childConversationId: String) -> Unit,
    /** Transfers an unpublished output resource to the generation/checkpoint owner. */
    val registerUnpublishedResource: (ToolResourceLease) -> Unit,
)

@Serializable
data class Tool(
    val name: String,
    val description: String,
    val parameters: () -> JsonObject? = { null },
    val systemPrompt: (model: Model, messages: List<UIMessage>) -> String = { _, _ -> "" },
    val needsApproval: (JsonElement) -> Boolean = { false },
    val outputPolicy: ToolOutputPolicy = ToolOutputPolicy.TRUNCATABLE_TEXT,
    val execute: suspend (JsonElement) -> List<UIMessagePart>,
    /**
     * 带 receiver 的 contextual execute，可回写 metadata 和使用请求级资源能力。
     * 未声明时由 [execute] 处理普通无上下文工具。
     */
    @Transient
    val contextualExecute: (suspend ToolExecutionContext.(JsonElement) -> List<UIMessagePart>)? = null,
) {
    /**
     * 统一执行入口：上下文工具使用 [contextualExecute]，普通工具使用 [execute]。
     */
    suspend fun executeWithContext(
        context: ToolExecutionContext,
        args: JsonElement,
    ): List<UIMessagePart> {
        return if (contextualExecute != null) {
            context.contextualExecute(args)
        } else {
            execute(args)
        }
    }
}

/**
 * Convenience builders for tool input JSON Schema documents.
 *
 * [Tool.parameters] deliberately exposes [JsonObject] rather than a closed Kotlin model: JSON
 * Schema is extensible and MCP tools may use keywords such as `$defs`, `$ref`, or future dialect
 * additions that the client must preserve losslessly.
 */
object InputSchema {
    @Suppress("FunctionName")
    fun Obj(
        properties: JsonObject,
        required: List<String>? = null,
    ): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", properties)
        required?.let { names ->
            put("required", JsonArray(names.map(::JsonPrimitive)))
        }
    }
}

@Serializable
enum class ToolOutputPolicy {
    TRUNCATABLE_TEXT,
    PRESERVE,
}
