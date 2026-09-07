package net.weero.measix.pilot.data.ai.tools

/** Tool Output marker、读取和搜索协议的边界唯一来源。 */
internal object ToolOutputProtocol {
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
    const val TOOL_OUTPUT_MAX_RESPONSE_BYTES = 32 * 1024
    /** 为文本 header、行号和 grep block 分隔符预留的字节，保证分页不会二次截断。 */
    const val TOOL_OUTPUT_RESPONSE_FORMAT_RESERVE_BYTES = 1024
    /** 失败归档 marker 最多保留的末行字符数。 */
    const val TOOL_OUTPUT_MARKER_TAIL_CHARS = 160
}
