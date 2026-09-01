package net.weero.measix.pilot.data.ai.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.ToolExecutionFailure
import me.rerere.ai.ui.UIMessagePart

/**
 * App 内建工具的统一失败信封。成功结果直接返回领域数据，避免为每次成功重复增加状态字段；
 * 失败统一为短小 JSON，并通过 [ToolExecutionFailure] 让 Runtime 写入 typed failed 终态。
 */
internal fun failToolResult(
    reason: String,
    detail: String? = null,
): Nothing {
    val output = listOf(
        UIMessagePart.Text(
            buildJsonObject {
                put("status", "failed")
                put("reason", reason)
                detail?.trim()?.takeIf { it.isNotEmpty() }?.let { put("detail", it) }
            }.toString(),
        ),
    )
    throw ToolExecutionFailure(output, reason)
}

/** 保留领域专用失败正文，但仍通过同一 typed 异常协议提交 failed 终态。 */
internal fun failToolResult(
    output: List<UIMessagePart>,
    reason: String,
): Nothing = throw ToolExecutionFailure(output, reason)
