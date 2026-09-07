package net.weero.measix.pilot.data.ai.request

/** 请求裁剪的生产阈值唯一来源。 */
internal object ContextBudget {
    /** inline Tool 文本估算 token 达到该高水位时启动滚动压缩。 */
    const val TOOL_OUTPUT_HIGH_WATERMARK_ESTIMATED_TOKENS = 48 * 1024L
    /** 每次滚动压缩尽量把 inline Tool 文本降到该低水位。 */
    const val TOOL_OUTPUT_LOW_WATERMARK_ESTIMATED_TOKENS = 16 * 1024L
    /** 整批净回收不足该值时不改写历史。 */
    const val TOOL_OUTPUT_MINIMUM_BATCH_NET_RECLAIM_ESTIMATED_TOKENS = 24 * 1024L
    /** 单个结果替换 marker 后至少净回收的估算 token。 */
    const val TOOL_OUTPUT_MINIMUM_RESULT_NET_RECLAIM_ESTIMATED_TOKENS = 128L
    /** 最近两个连续 Tool Result 批次不参与普通滚动压缩。 */
    const val TOOL_OUTPUT_PROTECTED_RECENT_BATCHES = 2
    /** 从尾部累计的最近估算 token 不参与普通滚动压缩。 */
    const val TOOL_OUTPUT_PROTECTED_RECENT_ESTIMATED_TOKENS = 8 * 1024L

    /** TurnRunner 唯一生产入口使用的默认滚动压缩预算。 */
    val TOOL_OUTPUT_BUDGET = ToolOutputBudget(
        highWatermarkEstimatedTokens = TOOL_OUTPUT_HIGH_WATERMARK_ESTIMATED_TOKENS,
        lowWatermarkEstimatedTokens = TOOL_OUTPUT_LOW_WATERMARK_ESTIMATED_TOKENS,
        minimumBatchNetReclaimEstimatedTokens = TOOL_OUTPUT_MINIMUM_BATCH_NET_RECLAIM_ESTIMATED_TOKENS,
        minimumResultNetReclaimEstimatedTokens = TOOL_OUTPUT_MINIMUM_RESULT_NET_RECLAIM_ESTIMATED_TOKENS,
        protectedRecentBatches = TOOL_OUTPUT_PROTECTED_RECENT_BATCHES,
        protectedRecentEstimatedTokens = TOOL_OUTPUT_PROTECTED_RECENT_ESTIMATED_TOKENS,
    )
}

/** Planner 的 typed 预算；测试可显式覆盖，生产只使用 [ContextBudget.TOOL_OUTPUT_BUDGET]。 */
internal data class ToolOutputBudget(
    val highWatermarkEstimatedTokens: Long = ContextBudget.TOOL_OUTPUT_HIGH_WATERMARK_ESTIMATED_TOKENS,
    val lowWatermarkEstimatedTokens: Long = ContextBudget.TOOL_OUTPUT_LOW_WATERMARK_ESTIMATED_TOKENS,
    val minimumBatchNetReclaimEstimatedTokens: Long =
        ContextBudget.TOOL_OUTPUT_MINIMUM_BATCH_NET_RECLAIM_ESTIMATED_TOKENS,
    val minimumResultNetReclaimEstimatedTokens: Long =
        ContextBudget.TOOL_OUTPUT_MINIMUM_RESULT_NET_RECLAIM_ESTIMATED_TOKENS,
    val protectedRecentBatches: Int = ContextBudget.TOOL_OUTPUT_PROTECTED_RECENT_BATCHES,
    val protectedRecentEstimatedTokens: Long = ContextBudget.TOOL_OUTPUT_PROTECTED_RECENT_ESTIMATED_TOKENS,
) {
    init {
        require(highWatermarkEstimatedTokens > lowWatermarkEstimatedTokens && lowWatermarkEstimatedTokens >= 0)
        require(minimumBatchNetReclaimEstimatedTokens >= 0)
        require(minimumResultNetReclaimEstimatedTokens >= 0)
        require(protectedRecentBatches >= 0)
        require(protectedRecentEstimatedTokens >= 0)
    }
}

/**
 * 跨 Provider 的稳定文本粗估。拉丁词与空白仍按约 4 个 ASCII code point 1 token；
 * 连续 ASCII 数字段按最多 3 位 1 token、连续 ASCII 符号段按约 2 字符 1 token，
 * 其他 Unicode code point 各计 1。按段而非全文字符总数计价，避免把随机数/JSON
 * 当成英文散文。该值用于请求摘要与确定性滚动预算，不冒充 Provider 计费 token。
 */
internal fun estimateStableTextTokens(text: String): Long {
    var tokens = 0L
    var plainAscii = 0L
    var nonAscii = 0L
    var digitRun = 0L
    var symbolRun = 0L
    val n = text.length
    var i = 0
    while (i < n) {
        val ch = text[i]
        val code = ch.code
        if (code < 0x80) {
            when {
                code in 0x30..0x39 -> {
                    if (symbolRun != 0L) {
                        tokens += (symbolRun + 1L) / 2L
                        symbolRun = 0L
                    }
                    digitRun++
                }
                code in 0x41..0x5A ||
                    code in 0x61..0x7A ||
                    code == 0x20 ||
                    code in 0x09..0x0D ||
                    code in 0x1C..0x1F -> {
                    if (digitRun != 0L) {
                        tokens += (digitRun + 2L) / 3L
                        digitRun = 0L
                    }
                    if (symbolRun != 0L) {
                        tokens += (symbolRun + 1L) / 2L
                        symbolRun = 0L
                    }
                    plainAscii++
                }
                else -> {
                    if (digitRun != 0L) {
                        tokens += (digitRun + 2L) / 3L
                        digitRun = 0L
                    }
                    symbolRun++
                }
            }
            i++
        } else {
            if (digitRun != 0L) {
                tokens += (digitRun + 2L) / 3L
                digitRun = 0L
            }
            if (symbolRun != 0L) {
                tokens += (symbolRun + 1L) / 2L
                symbolRun = 0L
            }
            nonAscii++
            i += if (ch.isHighSurrogate() && i + 1 < n && text[i + 1].isLowSurrogate()) 2 else 1
        }
    }
    if (digitRun != 0L) tokens += (digitRun + 2L) / 3L
    if (symbolRun != 0L) tokens += (symbolRun + 1L) / 2L
    return tokens + (plainAscii + 3L) / 4L + nonAscii
}
