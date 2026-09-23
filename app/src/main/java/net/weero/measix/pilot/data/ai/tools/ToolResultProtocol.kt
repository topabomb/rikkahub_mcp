package net.weero.measix.pilot.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.ToolErrorProtocol
import me.rerere.ai.core.ToolExecutionFailure
import me.rerere.ai.ui.UIMessagePart

internal fun invalidToolArguments(detail: String): JsonObject = buildJsonObject {
    put("reason", "invalid_arguments")
    put("detail", detail)
}

/**
 * App 内建工具的统一失败信封。成功结果直接返回领域数据，避免为每次成功重复增加状态字段；
 * 失败统一为有界 JSON，并通过 [ToolExecutionFailure] 让 Runtime 写入 typed failed 终态。
 */
internal fun failToolResult(
    reason: String,
    detail: String? = null,
): Nothing {
    val output = listOf(UIMessagePart.Text(ToolErrorProtocol.envelope("failed", reason, detail).toString()))
    throw ToolExecutionFailure(output, reason)
}

/** 保留领域专用字段，并统一第一段 Text 中的错误信封。 */
internal fun failToolResult(
    output: List<UIMessagePart>,
    reason: String,
    detail: String? = null,
    status: String = "failed",
): Nothing {
    val first = output.firstOrNull() as? UIMessagePart.Text
    val existing = first?.text?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
    val envelope = ToolErrorProtocol.envelope(status, reason, detail)
    val result = if (existing == null) {
        listOf(UIMessagePart.Text(envelope.toString())) + output
    } else {
        listOf(UIMessagePart.Text(buildJsonObject {
            existing.forEach { (key, value) ->
                if (key !in setOf("status", "reason", "detail", "error", "type", "message")) put(key, value)
            }
            envelope.forEach { (key, value) -> put(key, value) }
        }.toString())) + output.drop(1)
    }
    throw ToolExecutionFailure(result, reason)
}
