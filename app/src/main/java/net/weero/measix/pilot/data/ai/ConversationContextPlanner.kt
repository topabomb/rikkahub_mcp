package net.weero.measix.pilot.data.ai

import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.FrozenToolDefinition
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.core.ToolOutputPolicy
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.confirmedReplayableToolOrdinals
import me.rerere.ai.ui.findUserTurnStart
import me.rerere.ai.ui.replaySafeProjection
import net.weero.measix.pilot.data.model.ConversationModelContextEntry
import net.weero.measix.pilot.data.ai.tools.ToolRuntimeMetadata
import net.weero.measix.pilot.data.ai.tools.REGENERABLE_TOOL_OUTPUT_FOLDED_MARKER
import net.weero.measix.pilot.data.ai.tools.estimatedToolOutputMarkerTokens
import kotlin.uuid.Uuid

/** 一条请求消息在 durable 树中的精确位置（权威方案 §8.1）。 */
data class DurableMessageLocator(
    val nodeId: Uuid,
    val messageId: Uuid,
)

/**
 * 请求消息的唯一来源事实。来源只能显式登记，不得靠 role、列表位置或对象引用猜测：
 * Durable 携带 locator；管线合成的内容携带 kind。
 */
sealed interface RequestMessageOrigin {
    data class Durable(val locator: DurableMessageLocator) : RequestMessageOrigin
    data class Synthetic(val kind: SyntheticMessageKind) : RequestMessageOrigin
}

/** 本管线会合成的全部消息来源；新增注入路径必须同步登记 kind，不允许匿名 synthetic。 */
enum class SyntheticMessageKind {
    SYSTEM_PROMPT,
    PROMPT_INJECTION,
    TIME_REMINDER,
    WORKSPACE_REMINDER,
}

/** 一条 context 投影：content 放到 anchor USER message 的最前面（§8.4）。 */
internal data class ModelContextProjection(
    val anchorMessageId: Uuid,
    val content: String,
)

/**
 * Turn 启动时确定的请求侧 context 投影：START 用唯一适用谓词过滤后的冻结 entries +
 * durable 树的位置表。同一 Turn 的 continuation / 重试只复用这份投影（§7.3）。
 */
internal data class TurnModelContextProjection(
    val entries: List<ConversationModelContextEntry>,
    val locators: Map<Uuid, DurableMessageLocator>,
)

/** Tool system prompt 与 Provider 请求共用的请求级投影。 */
internal data class RequestContextPlan(
    val messages: List<UIMessage>,
    val originsByMessageId: Map<Uuid, RequestMessageOrigin>,
    val contextProjections: List<ModelContextProjection>,
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

/** 唯一历史窗口、context 选择与 Tool Result compaction planner（权威方案 §8）。 */
internal class ConversationContextPlanner {

    /**
     * 请求窗口的唯一规划入口（§8.2 / §8.3 步骤 1-3）：
     * selected durable branch → replay-safe projection → messageLimit 裁剪 →
     * 按 anchor 位置选择 context 投影与 durable origin 表。
     *
     * [modelContextEntries] 必须是 START 时经唯一适用谓词过滤的冻结集合；窗口内不再做
     * 适用性推断。开启 context 时每条窗口消息都必须已有 durable locator，否则请求失败，
     * 绝不按 role 或位置伪造来源。
     */
    fun planRequest(
        durableMessages: List<UIMessage>,
        durableLocators: Map<Uuid, DurableMessageLocator> = emptyMap(),
        modelContextEntries: List<ConversationModelContextEntry> = emptyList(),
        messageLimit: Int,
    ): RequestContextPlan {
        val projected = durableMessages.replaySafeProjection()
        val window = limitContext(projected, messageLimit)
        // 位置对齐必须以投影后列表为基准：replay projection 会丢弃空白等不可上传消息，
        // 用未投影 branch 推导窗口起点会把无关数据形状放大成永久请求失败。
        val contextProjections = planContextProjections(
            branch = projected,
            window = window,
            entries = modelContextEntries,
        )
        if (contextProjections.isNotEmpty()) {
            window.forEach { message ->
                require(durableLocators.containsKey(message.id)) {
                    "retained request message has no durable locator: ${message.id}"
                }
            }
        }
        val origins = window.mapNotNull { message ->
            durableLocators[message.id]?.let { message.id to RequestMessageOrigin.Durable(it) }
        }.toMap()
        return RequestContextPlan(window, origins, contextProjections)
    }

    /**
     * 裁剪后的 context 选择（§8.2）：baseline = retained 第一条真实 USER 之前（含同位置）
     * 最近一条 Snapshot，投影到该 USER；窗口内更晚 anchor 的 Snapshot 保持在各自 anchor 前；
     * baseline 之前与窗口外的条目不发送。只移动请求投影位置，不修改 durable owner/anchor。
     *
     * [branch] 是 replay-safe projection 之后的 durable 序列；anchor 被投影丢弃（如空白
     * USER）视同 §8.1 的“retained anchor 找不到”，fail-closed。
     */
    private fun planContextProjections(
        branch: List<UIMessage>,
        window: List<UIMessage>,
        entries: List<ConversationModelContextEntry>,
    ): List<ModelContextProjection> {
        if (entries.isEmpty()) return emptyList()
        val firstUserWindowIndex = window.indexOfFirst { it.role == MessageRole.USER }
        check(firstUserWindowIndex >= 0) { "retained request window contains no real USER turn" }
        // limitContext 产生连续后缀窗口：branch 索引 = 窗口起点偏移 + 窗口内索引。
        val windowStart = branch.size - window.size
        check(windowStart >= 0 && branch.subList(windowStart, branch.size).map { it.id } == window.map { it.id }) {
            "request window is not a contiguous suffix of the durable branch"
        }
        val branchIndexOf = HashMap<Uuid, Int>(branch.size)
        branch.forEachIndexed { index, message -> branchIndexOf.putIfAbsent(message.id, index) }
        val firstUserBranchIndex = windowStart + firstUserWindowIndex
        val placements = entries.map { entry ->
            val anchorIndex = branchIndexOf[entry.anchorMessageId]
                ?: error("applicable model-context anchor is off the request branch: ${entry.anchorMessageId}")
            anchorIndex to entry
        }
        val baseline = placements.filter { (anchorIndex, _) -> anchorIndex <= firstUserBranchIndex }
            .maxByOrNull { (anchorIndex, _) -> anchorIndex }
        return buildList {
            baseline?.let { (_, entry) ->
                add(ModelContextProjection(window[firstUserWindowIndex].id, entry.content))
            }
            placements
                .filter { (anchorIndex, entry) ->
                    anchorIndex > firstUserBranchIndex && anchorIndex >= windowStart
                }
                .sortedBy { (anchorIndex, _) -> anchorIndex }
                .forEach { (_, entry) ->
                    add(ModelContextProjection(entry.anchorMessageId, entry.content))
                }
        }
    }

    /**
     * §8.3 步骤 6：transformers 完成后、token estimate 与 Provider 调用之前，把 context
     * part 作为 anchor USER 的第一个 part 注入，用户原始 parts 的顺序与内容保持不变。
     * retained anchor 在变换后缺失、重复、或不是 Durable USER 时请求失败：绝不把 Snapshot
     * 附着到 synthetic 消息。
     */
    fun applyContextProjections(
        transformedMessages: List<UIMessage>,
        projections: List<ModelContextProjection>,
        originsByMessageId: Map<Uuid, RequestMessageOrigin>,
    ): List<UIMessage> {
        if (projections.isEmpty()) return transformedMessages
        val contentByAnchor = LinkedHashMap<Uuid, MutableList<String>>()
        projections.forEach { projection ->
            contentByAnchor.getOrPut(projection.anchorMessageId) { mutableListOf() }.add(projection.content)
        }
        contentByAnchor.forEach { (anchorMessageId, _) ->
            val matches = transformedMessages.count { it.id == anchorMessageId }
            check(matches == 1) { "model-context anchor appears $matches times after transforms: $anchorMessageId" }
        }
        return transformedMessages.map { message ->
            val contents = contentByAnchor[message.id] ?: return@map message
            check(message.role == MessageRole.USER) {
                "model-context anchor must be a USER message: ${message.id}"
            }
            check(originsByMessageId[message.id] is RequestMessageOrigin.Durable) {
                "model-context must not attach to a synthetic message: ${message.id}"
            }
            message.copy(
                parts = contents.map { UIMessagePart.Text(it) } + message.parts,
            )
        }
    }

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
        tools: List<FrozenToolDefinition>,
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
            tool.parameters?.toString()?.let(::addText)
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
