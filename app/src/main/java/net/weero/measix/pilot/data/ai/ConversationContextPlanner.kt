package net.weero.measix.pilot.data.ai

import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.core.ToolOutputPolicy
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.confirmedReplayableToolOrdinals
import me.rerere.ai.ui.findUserTurnStart
import me.rerere.ai.ui.replaySafeProjection
import net.weero.measix.pilot.data.ai.tools.ToolRuntimeMetadata
import net.weero.measix.pilot.data.ai.tools.REGENERABLE_TOOL_OUTPUT_FOLDED_MARKER
import net.weero.measix.pilot.data.ai.tools.estimatedToolOutputMarkerTokens

/** Tool system prompt 与 Provider 请求共用的请求级投影。 */
internal data class RequestContextPlan(
    val messages: List<UIMessage>,
)

/** 成功 Provider 请求中可保守确认已进入最终投影的 inline Tool Result locator。 */
internal data class ModelStepReceipt(
    val visibleInlineToolOutputs: Set<ToolCallLocator>,
)

internal data class ToolOutputCompactionCandidate(
    val locator: ToolCallLocator,
    val toolName: String,
    val terminalStatus: String,
    val outputPolicy: ToolOutputPolicy,
    val text: String,
    val characters: Long,
    val originalEstimatedTokens: Long,
    val markerEstimatedTokens: Long,
    /** 归档结果以最长合法 ref 估算；可再生结果使用固定 marker。 */
    val netReclaimEstimatedTokens: Long,
)

internal data class ToolOutputCompactionPlan(
    val candidates: List<ToolOutputCompactionCandidate>,
    val netReclaimedEstimatedTokens: Long,
)

private data class ToolOutputBatchKey(
    val messageId: kotlin.uuid.Uuid,
    val batchOrdinal: Int,
)

private data class VisibleInlineToolOutput(
    val locator: ToolCallLocator,
    val batch: ToolOutputBatchKey,
    val estimatedTokens: Long,
    val candidate: ToolOutputCompactionCandidate?,
)

/** 请求窗口与成功 step 后 Tool Result 滚动压缩的唯一纯规划器。 */
internal class ConversationContextPlanner {
    fun planRequest(durableMessages: List<UIMessage>, messageLimit: Int): RequestContextPlan =
        RequestContextPlan(limitContext(durableMessages.replaySafeProjection(), messageLimit))

    /**
     * 从交给 Provider adapter 的最终消息投影生成保守 receipt：终态消息只看完整前缀；
     * Responses opaque replay 只登记原始 function_call 容器实际能配对的本地结果。
     */
    fun receiptOf(providerMessages: List<UIMessage>): ModelStepReceipt = ModelStepReceipt(
        providerMessages.flatMap { message ->
            val confirmedOrdinals = message.confirmedReplayableToolOrdinals()
            message.parts.filterIsInstance<UIMessagePart.Tool>().mapIndexedNotNull { ordinal, tool ->
                ToolCallLocator(message.id, ordinal).takeIf {
                    ordinal in confirmedOrdinals &&
                        !ToolRuntimeMetadata.isInvalid(tool.metadata) &&
                        ToolRuntimeMetadata.archiveOf(tool.metadata) == null
                }
            }
        }.toSet(),
    )

    /**
     * 在 Provider 调用前，对经过全部输入 transformer 的最终请求投影做稳定粗估。
     * 文本与工具 schema 使用统一 code-point 规则；媒体使用固定占位，避免 base64 长度冒充模型 token。
     */
    fun estimateRequestContextTokens(
        providerMessages: List<UIMessage>,
        tools: List<Tool>,
    ): Long {
        var total = 0L
        fun add(tokens: Long) {
            total = if (Long.MAX_VALUE - total < tokens) Long.MAX_VALUE else total + tokens
        }
        fun addText(value: String) = add(estimateStableTextTokens(value))
        fun addParts(parts: List<UIMessagePart>) {
            parts.forEach { part ->
                add(REQUEST_PART_OVERHEAD_ESTIMATED_TOKENS)
                when (part) {
                    is UIMessagePart.Text -> addText(part.text)
                    is UIMessagePart.Reasoning -> addText(part.reasoning)
                    is UIMessagePart.Tool -> {
                        addText(part.toolCallId)
                        addText(part.toolName)
                        addText(part.input)
                        addParts(part.output)
                    }
                    is UIMessagePart.Document -> {
                        addText(part.fileName)
                        addText(part.mime)
                        add(REQUEST_MEDIA_PLACEHOLDER_ESTIMATED_TOKENS)
                    }
                    is UIMessagePart.Image,
                    is UIMessagePart.Audio,
                    is UIMessagePart.Video,
                    -> add(REQUEST_MEDIA_PLACEHOLDER_ESTIMATED_TOKENS)
                }
            }
        }

        providerMessages.forEach { message ->
            add(REQUEST_MESSAGE_OVERHEAD_ESTIMATED_TOKENS)
            addText(message.role.name)
            addParts(message.parts)
        }
        tools.forEach { tool ->
            add(REQUEST_TOOL_DEFINITION_OVERHEAD_ESTIMATED_TOKENS)
            addText(tool.name)
            addText(tool.description)
            tool.parameters()?.toString()?.let(::addText)
        }
        return total
    }

    /**
     * 滚动压缩决策的唯一入口：只使用稳定估算 token 的高/低水位、最近批次、
     * 最近 token 和最小净回收量；只返回计划，不做文件或消息写入。
     */
    fun planPostStepCompaction(
        committedMessages: List<UIMessage>,
        receipt: ModelStepReceipt,
        budget: ToolOutputBudget = ContextTrimmingPolicy.TOOL_OUTPUT_BUDGET,
    ): ToolOutputCompactionPlan {
        val visible = visibleInlineToolOutputs(
            committedMessages,
            receipt,
            budget.minimumResultNetReclaimEstimatedTokens,
        )
        val totalEstimatedTokens = visible.sumOf { it.estimatedTokens }
        if (totalEstimatedTokens < budget.highWatermarkEstimatedTokens) {
            return ToolOutputCompactionPlan(emptyList(), 0)
        }

        val protectedBatches = visible.map(VisibleInlineToolOutput::batch)
            .distinct()
            .takeLast(budget.protectedRecentBatches)
            .toSet()
        var protectedTokens = 0L
        val protectedLocators = mutableSetOf<ToolCallLocator>()
        for (output in visible.asReversed()) {
            if (output.batch in protectedBatches || protectedTokens < budget.protectedRecentEstimatedTokens) {
                protectedLocators += output.locator
                protectedTokens += output.estimatedTokens
            }
        }
        val eligible = visible.mapNotNull(VisibleInlineToolOutput::candidate)
            .filterNot { it.locator in protectedLocators }
        val targetReclaim = maxOf(
            totalEstimatedTokens - budget.lowWatermarkEstimatedTokens,
            budget.minimumBatchNetReclaimEstimatedTokens,
        )
        val selected = mutableListOf<ToolOutputCompactionCandidate>()
        var reclaimed = 0L
        for (candidate in eligible) {
            if (reclaimed >= targetReclaim) break
            selected += candidate
            reclaimed += candidate.netReclaimEstimatedTokens
        }
        return if (reclaimed >= budget.minimumBatchNetReclaimEstimatedTokens) {
            ToolOutputCompactionPlan(selected, reclaimed)
        } else {
            ToolOutputCompactionPlan(emptyList(), 0)
        }
    }

    /**
     * 只扫描本次成功请求确实可见的 inline Tool Result。批次身份由 Runtime 在收到同一
     * Provider tool-call batch 时写入；老消息没有该字段时退化为“每个结果一个批次”，
     * 不能用 messageId 或相邻 part 猜测。总压力与可压缩资格分开计算。
     */
    private fun visibleInlineToolOutputs(
        messages: List<UIMessage>,
        receipt: ModelStepReceipt,
        minimumResultNetReclaimEstimatedTokens: Long,
    ): List<VisibleInlineToolOutput> = buildList {
        messages.forEach { message ->
            var toolOrdinal = 0
            message.parts.forEach { part ->
                if (part !is UIMessagePart.Tool) {
                    return@forEach
                }
                val locator = ToolCallLocator(message.id, toolOrdinal++)
                if (!part.hasReplayResult) {
                    return@forEach
                }
                if (locator !in receipt.visibleInlineToolOutputs) return@forEach
                if (ToolRuntimeMetadata.isInvalid(part.metadata)) return@forEach
                if (ToolRuntimeMetadata.archiveOf(part.metadata) != null) return@forEach

                val textParts = part.output.filterIsInstance<UIMessagePart.Text>()
                val visibleText = textParts.joinToString("\n") { it.text }
                val originalEstimatedTokens = estimateStableTextTokens(visibleText)
                val terminalStatus = ToolRuntimeMetadata.terminalStatusOf(part.metadata)
                val compactableStatus = terminalStatus?.takeIf { it == "completed" || it == "failed" }
                val outputPolicy = ToolRuntimeMetadata.outputPolicyOf(part.metadata)
                    ?.let { name -> ToolOutputPolicy.entries.firstOrNull { it.name == name } }
                val markerEstimatedTokens = when {
                    compactableStatus == null -> null
                    outputPolicy == ToolOutputPolicy.ARCHIVABLE_TEXT ->
                        estimatedToolOutputMarkerTokens(compactableStatus, visibleText)
                    outputPolicy == ToolOutputPolicy.REGENERABLE_TEXT -> estimateStableTextTokens(
                        REGENERABLE_TOOL_OUTPUT_FOLDED_MARKER,
                    )
                    else -> null
                }
                val netReclaimEstimatedTokens = markerEstimatedTokens
                    ?.let { originalEstimatedTokens - it }
                    ?.takeIf { it >= minimumResultNetReclaimEstimatedTokens }
                val candidate = visibleText.takeIf {
                    visibleText != REGENERABLE_TOOL_OUTPUT_FOLDED_MARKER &&
                        netReclaimEstimatedTokens != null &&
                        part.output.isNotEmpty() &&
                        part.output.size == textParts.size
                }?.let { text ->
                    ToolOutputCompactionCandidate(
                        locator = locator,
                        toolName = part.toolName,
                        terminalStatus = requireNotNull(compactableStatus),
                        outputPolicy = requireNotNull(outputPolicy),
                        text = text,
                        characters = text.length.toLong(),
                        originalEstimatedTokens = originalEstimatedTokens,
                        markerEstimatedTokens = requireNotNull(markerEstimatedTokens),
                        netReclaimEstimatedTokens = requireNotNull(netReclaimEstimatedTokens),
                    )
                }
                add(
                    VisibleInlineToolOutput(
                        locator = locator,
                        batch = ToolOutputBatchKey(
                            message.id,
                            ToolRuntimeMetadata.resultBatchOrdinalOf(part.metadata) ?: locator.toolOrdinal,
                        ),
                        estimatedTokens = originalEstimatedTokens,
                        candidate = candidate,
                    ),
                )
            }
        }
    }

    private fun limitContext(messages: List<UIMessage>, limit: Int): List<UIMessage> {
        if (limit <= 0 || messages.size <= limit) return messages
        val target = (limit * CONTEXT_KEEP_RATIO).roundToInt().coerceIn(1, limit)
        val stride = (limit - target).coerceAtLeast(1)
        val steppedStartIndex = (((messages.size - limit) / stride + 1) * stride).coerceAtMost(messages.lastIndex)
        return messages.subList(messages.findUserTurnStart(steppedStartIndex), messages.size)
    }

    private companion object {
        /** 普通历史超限时每次保留窗口的一半，形成稳定的阶梯式请求边界。 */
        const val CONTEXT_KEEP_RATIO = 0.5f
        const val REQUEST_MESSAGE_OVERHEAD_ESTIMATED_TOKENS = 4L
        const val REQUEST_PART_OVERHEAD_ESTIMATED_TOKENS = 1L
        const val REQUEST_TOOL_DEFINITION_OVERHEAD_ESTIMATED_TOKENS = 8L
        const val REQUEST_MEDIA_PLACEHOLDER_ESTIMATED_TOKENS = 256L
    }
}
