package net.weero.measix.pilot.data.ai.tools

import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.core.ToolOutputPolicy
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.ToolResultStatus
import net.weero.measix.pilot.data.ai.request.ContextBudget
import net.weero.measix.pilot.data.ai.request.ModelRequestReceipt
import net.weero.measix.pilot.data.ai.request.ToolOutputBudget
import net.weero.measix.pilot.data.ai.request.estimateStableTextTokens
import kotlin.uuid.Uuid

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
    val messageId: Uuid,
    val batchOrdinal: Int,
)

private data class VisibleInlineToolOutput(
    val locator: ToolCallLocator,
    val batch: ToolOutputBatchKey,
    val estimatedTokens: Long,
    val candidate: ToolOutputCompactionCandidate?,
)

/**
 * 请求成功之后的 Tool Result 滚动压缩规划器（仅请求成功后）。
 *
 * 与 [net.weero.measix.pilot.data.ai.request.RequestContextPlanner]（仅请求前）分属两个 owner：
 * 本类只消费本次成功请求的 [ModelRequestReceipt]，产出压缩计划，不做任何文件或消息写入。
 */
internal class ToolOutputCompactionPlanner {

    /**
     * 滚动压缩决策的唯一入口：只使用稳定估算 token 的高/低水位、最近批次、
     * 最近 token 和最小净回收量；只返回计划，不做文件或消息写入。
     */
    fun planAfterSuccessfulRequest(
        committedMessages: List<UIMessage>,
        receipt: ModelRequestReceipt,
        budget: ToolOutputBudget = ContextBudget.TOOL_OUTPUT_BUDGET,
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
     * 只扫描本次成功请求确实可见的 inline Tool Result。批次身份由 Tool 所属 Step 的 ordinal
     * 决定（`ToolOutputBatchKey(messageId, stepOrdinal)`），不能用相邻 part 猜测。
     * 总压力与可压缩资格分开计算。
     */
    private fun visibleInlineToolOutputs(
        messages: List<UIMessage>,
        receipt: ModelRequestReceipt,
        minimumResultNetReclaimEstimatedTokens: Long,
    ): List<VisibleInlineToolOutput> = buildList {
        messages.forEach { message ->
            var stepOrdinal = 0
            message.parts.forEach { part ->
                if (part is UIMessagePart.Step) {
                    stepOrdinal = part.ordinal
                    return@forEach
                }
                if (part !is UIMessagePart.Tool) {
                    return@forEach
                }
                val locator = ToolCallLocator(message.id, part.stepId, part.localCallId)
                if (!part.hasReplayResult) {
                    return@forEach
                }
                if (locator !in receipt.visibleInlineToolOutputs) return@forEach
                if (part.runtimeState.archive != null) return@forEach

                val textParts = part.output.filterIsInstance<UIMessagePart.Text>()
                val visibleText = textParts.joinToString("\n") { it.text }
                val originalEstimatedTokens = estimateStableTextTokens(visibleText)
                val compactableStatus = part.resultStatus?.takeIf {
                    it == ToolResultStatus.COMPLETED || it == ToolResultStatus.FAILED
                }
                val outputPolicy = part.runtimeState.outputPolicy
                val markerEstimatedTokens = when {
                    compactableStatus == null -> null
                    outputPolicy == ToolOutputPolicy.ARCHIVABLE_TEXT ->
                        estimatedToolOutputMarkerTokens(compactableStatus.wireName, visibleText)
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
                        terminalStatus = requireNotNull(compactableStatus).wireName,
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
                        batch = ToolOutputBatchKey(message.id, stepOrdinal),
                        estimatedTokens = originalEstimatedTokens,
                        candidate = candidate,
                    ),
                )
            }
        }
    }
}
