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
 * 通用工具执行上下文，提供 metadata 回写能力。
 * 不引入 App 的 Conversation 类型，保持 ai 模块的平台无关性。
 *
 * [messageId] + [toolOrdinal] 是本次执行在当前 ASSISTANT message 中的内部精确 locator；
 * [toolCallId] 保留给 Provider 协议，不能作为内存更新的唯一键。
 */
data class ToolExecutionContext(
    val messageId: Uuid,
    val toolOrdinal: Int,
    val toolCallId: String,
    val reportMetadata: suspend (patch: JsonObject, checkpoint: Boolean) -> Unit,
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
     * 带 receiver 的 contextual execute，可回写 metadata。
     * 优先于 [execute] 使用；为 null 时回退到 [execute]。
     * 现有工具不需要修改，只需在需要 metadata 回写的工具中设置此字段。
     */
    @Transient
    val contextualExecute: (suspend ToolExecutionContext.(JsonElement) -> List<UIMessagePart>)? = null,
) {
    /**
     * 统一执行入口：优先使用 contextualExecute，否则回退到 execute。
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
