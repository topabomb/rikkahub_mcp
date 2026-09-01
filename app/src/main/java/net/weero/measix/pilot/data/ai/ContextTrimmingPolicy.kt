package net.weero.measix.pilot.data.ai

/** 请求裁剪的生产阈值唯一来源。 */
internal object ContextTrimmingPolicy {
    /** inline Tool 文本估算 token 达到该高水位时启动滚动压缩。 */
    const val TOOL_OUTPUT_HIGH_WATERMARK_ESTIMATED_TOKENS = 48 * 1024L
    /** 每次滚动压缩尽量把 inline Tool 文本降到该低水位。 */
    const val TOOL_OUTPUT_LOW_WATERMARK_ESTIMATED_TOKENS = 16 * 1024L
    /** 整批净回收不足该值时不改写历史。 */
    const val TOOL_OUTPUT_MINIMUM_BATCH_NET_RECLAIM_ESTIMATED_TOKENS = 24 * 1024L
    /** 单个结果替换 marker 后至少净回收的估算 token。 */
    const val TOOL_OUTPUT_MINIMUM_RESULT_NET_RECLAIM_ESTIMATED_TOKENS = 512L
    /** 最近两个连续 Tool Result 批次不参与普通滚动压缩。 */
    const val TOOL_OUTPUT_PROTECTED_RECENT_BATCHES = 2
    /** 从尾部累计的最近估算 token 不参与普通滚动压缩。 */
    const val TOOL_OUTPUT_PROTECTED_RECENT_ESTIMATED_TOKENS = 4 * 1024L

    /** GenerationLoop 唯一生产入口使用的默认滚动压缩预算。 */
    val TOOL_OUTPUT_BUDGET = ToolOutputBudget(
        highWatermarkEstimatedTokens = TOOL_OUTPUT_HIGH_WATERMARK_ESTIMATED_TOKENS,
        lowWatermarkEstimatedTokens = TOOL_OUTPUT_LOW_WATERMARK_ESTIMATED_TOKENS,
        minimumBatchNetReclaimEstimatedTokens = TOOL_OUTPUT_MINIMUM_BATCH_NET_RECLAIM_ESTIMATED_TOKENS,
        minimumResultNetReclaimEstimatedTokens = TOOL_OUTPUT_MINIMUM_RESULT_NET_RECLAIM_ESTIMATED_TOKENS,
        protectedRecentBatches = TOOL_OUTPUT_PROTECTED_RECENT_BATCHES,
        protectedRecentEstimatedTokens = TOOL_OUTPUT_PROTECTED_RECENT_ESTIMATED_TOKENS,
    )
}

/** Tool Output marker、读取和搜索协议的边界唯一来源。 */
internal object ToolOutputProtocolLimits {
    /** 超长物理行按该字符数建立稳定返回行号；底层扫描的峰值仍受最长物理行长度影响。 */
    const val TOOL_OUTPUT_VIRTUAL_LINE_CHARS = 4096
    /** read_tool_output 未指定行数时的默认分页大小。 */
    const val TOOL_OUTPUT_DEFAULT_READ_LINES = 200
    /** read_tool_output 单次最多返回的虚拟行数。 */
    const val TOOL_OUTPUT_MAX_READ_LINES = 500
    /** grep_tool_output 单次最多接受的匹配数。 */
    const val TOOL_OUTPUT_MAX_GREP_MATCHES = 100
    /** grep_tool_output 每个匹配最多携带的前后文行数。 */
    const val TOOL_OUTPUT_MAX_CONTEXT_LINES = 5
    /** RE2 pattern 的最大字符数，限制编译和扫描成本。 */
    const val TOOL_OUTPUT_MAX_PATTERN_CHARS = 1024
    /** 两个回查工具最终返回给 Provider 的 UTF-8 字节硬上限。 */
    const val TOOL_OUTPUT_MAX_RESPONSE_BYTES = 16 * 1024
    /** 为文本 header、行号和 grep block 分隔符预留的字节，保证分页不会二次截断。 */
    const val TOOL_OUTPUT_RESPONSE_FORMAT_RESERVE_BYTES = 1024
    /** 失败归档 marker 最多保留的末行字符数。 */
    const val TOOL_OUTPUT_MARKER_TAIL_CHARS = 160
}

/** Planner 的 typed 预算；测试可显式覆盖，生产只使用 [ContextTrimmingPolicy.TOOL_OUTPUT_BUDGET]。 */
internal data class ToolOutputBudget(
    val highWatermarkEstimatedTokens: Long = ContextTrimmingPolicy.TOOL_OUTPUT_HIGH_WATERMARK_ESTIMATED_TOKENS,
    val lowWatermarkEstimatedTokens: Long = ContextTrimmingPolicy.TOOL_OUTPUT_LOW_WATERMARK_ESTIMATED_TOKENS,
    val minimumBatchNetReclaimEstimatedTokens: Long =
        ContextTrimmingPolicy.TOOL_OUTPUT_MINIMUM_BATCH_NET_RECLAIM_ESTIMATED_TOKENS,
    val minimumResultNetReclaimEstimatedTokens: Long =
        ContextTrimmingPolicy.TOOL_OUTPUT_MINIMUM_RESULT_NET_RECLAIM_ESTIMATED_TOKENS,
    val protectedRecentBatches: Int = ContextTrimmingPolicy.TOOL_OUTPUT_PROTECTED_RECENT_BATCHES,
    val protectedRecentEstimatedTokens: Long = ContextTrimmingPolicy.TOOL_OUTPUT_PROTECTED_RECENT_ESTIMATED_TOKENS,
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
 * 跨 Provider 的稳定文本粗估：ASCII code point 数除以 4 并向上取整，其他 Unicode code point 各算 1 token。
 * 该值用于请求摘要与确定性滚动预算，不冒充 Provider 计费 token。
 */
internal fun estimateStableTextTokens(text: String): Long {
    var ascii = 0L
    var nonAscii = 0L
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        if (codePoint <= 0x7f) ascii++ else nonAscii++
        index += Character.charCount(codePoint)
    }
    return (ascii + 3L) / 4L + nonAscii
}
