package net.weero.measix.pilot.data.ai.request

import kotlin.math.roundToInt
import me.rerere.ai.core.FrozenToolDefinition
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ToolCallLocator
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.confirmedReplayableToolOrdinals
import me.rerere.ai.ui.findUserTurnStart
import me.rerere.ai.ui.replaySafeProjection
import net.weero.measix.pilot.data.model.ConversationModelContextEntry
import kotlin.uuid.Uuid

/** 一条请求消息在 durable 树中的精确位置。 */
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

/** 一条 context 投影：content 放到 anchor USER message 的最前面。 */
internal data class ModelContextProjection(
    val anchorMessageId: Uuid,
    val content: String,
)

/**
 * Turn 启动时确定的请求侧 context 投影：START 用唯一适用谓词过滤后的冻结 entries +
 * durable 树的位置表。同一 Turn 的 continuation / 重试只复用这份投影。
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
internal data class ModelRequestReceipt(
    val visibleInlineToolOutputs: Set<ToolCallLocator>,
)

/** 唯一历史窗口、context 选择 planner（仅请求前）。 */
internal class RequestContextPlanner {

    /**
     * 请求窗口的唯一规划入口：
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
     * 裁剪后的 context 选择：baseline = retained 第一条真实 USER 之前（含同位置）
     * 最近一条 Snapshot，投影到该 USER；窗口内更晚 anchor 的 Snapshot 保持在各自 anchor 前；
     * baseline 之前与窗口外的条目不发送。只移动请求投影位置，不修改 durable owner/anchor。
     *
     * [branch] 是 replay-safe projection 之后的 durable 序列；anchor 被投影丢弃（如空白
     * USER）视同“retained anchor 找不到”，fail-closed。
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
     * transformers 完成后、token estimate 与 Provider 调用之前，把 context
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
     * 从 Step 已丢弃、即将交给 Provider 的最终 durable 投影生成保守 receipt：终态消息只看完整前缀；
     * Responses opaque replay 只登记原始 function_call 容器实际能配对的本地结果。
     */
    fun receiptOf(providerVisibleMessages: List<UIMessage>): ModelRequestReceipt = ModelRequestReceipt(
        providerVisibleMessages.flatMap { message ->
            val confirmedOrdinals = message.confirmedReplayableToolOrdinals()
            message.parts.filterIsInstance<UIMessagePart.Tool>().mapIndexedNotNull { ordinal, tool ->
                ToolCallLocator(message.id, tool.stepId, tool.localCallId).takeIf {
                    ordinal in confirmedOrdinals && tool.runtimeState.archive == null
                }
            }
        }.toSet(),
    )

    /**
     * 在 Provider 调用前，对经过全部输入 transformer 的最终请求投影做稳定粗估。
     * 文本与工具 schema 使用统一 code-point 规则；媒体使用固定占位，避免 base64 长度冒充模型 token。
     */
    fun estimateRequestContextTokens(
        providerVisibleMessages: List<UIMessage>,
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
                    is UIMessagePart.Step -> Unit
                    is UIMessagePart.Tool -> {
                        addText(part.providerCallId)
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

        providerVisibleMessages.forEach { message ->
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
