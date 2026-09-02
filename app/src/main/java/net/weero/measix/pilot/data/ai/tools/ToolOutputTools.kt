package net.weero.measix.pilot.data.ai.tools

import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolOutputPolicy
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.ToolOutputProtocolLimits

/** 内置保留名：回查工具始终注册，动态/MCP 工具占用同名在装配阶段拒绝。 */
internal object ToolOutputToolNames {
    /** 按稳定虚拟行分页读取归档文本。 */
    const val READ = "read_tool_output"
    /** 使用 RE2 搜索归档文本并返回有界编号块。 */
    const val GREP = "grep_tool_output"
    val RESERVED: Set<String> = setOf(READ, GREP)
}

/**
 * 归档 Tool Result 的回查工具。输出严格有界（16 KiB），并与其他纯文本结果一样在
 * 被模型成功消费后参与统一滚动压缩；折叠时不再复制为新的 Artifact。
 */
internal fun createToolOutputLookupTools(
    store: ToolOutputStore,
    conversationId: Uuid,
): List<Tool> = listOf(
    Tool(
        name = ToolOutputToolNames.READ,
        // 描述补齐 marker 语义与回查时机，并约束模型不向用户透露归档机制。
        description = "Reads lines from a tool output marked as [Archived tool result: ref=...]. Use when target line numbers are known. Keep internal archiving hidden from the user.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("ref", buildJsonObject {
                        put("type", "integer")
                        put("description", "Reference from the archive marker.")
                    })
                    put("start", buildJsonObject {
                        put("type", "integer")
                        put("description", "1-based first line to read. Default 1.")
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Lines to read, 1..${ToolOutputProtocolLimits.TOOL_OUTPUT_MAX_READ_LINES}. " +
                                "Default ${ToolOutputProtocolLimits.TOOL_OUTPUT_DEFAULT_READ_LINES}.",
                        )
                    })
                },
                required = listOf("ref"),
            )
        },
        outputPolicy = ToolOutputPolicy.REGENERABLE_TEXT,
        validateArguments = validate@{ element ->
            val args = element as? JsonObject ?: return@validate rejection("invalid_arguments", "arguments must be a JSON object")
            positiveLongField(args, "ref", required = true)?.let { return@validate it }
            intInRange(args, "start", 1, Int.MAX_VALUE)?.let { return@validate it }
            intInRange(
                args,
                "limit",
                1,
                ToolOutputProtocolLimits.TOOL_OUTPUT_MAX_READ_LINES,
            )?.let { return@validate it }
            null
        },
        execute = { element ->
            val args = element as JsonObject
            val ref = requireNotNull((args["ref"] as JsonPrimitive).longOrNull)
            val startLine = (args["start"] as? JsonPrimitive)?.intOrNull ?: 1
            val lineCount = (args["limit"] as? JsonPrimitive)?.intOrNull
                ?: ToolOutputProtocolLimits.TOOL_OUTPUT_DEFAULT_READ_LINES
            when (val result = store.read(conversationId, ref, startLine, lineCount)) {
                ToolOutputReadResult.Unavailable -> failToolResult("archive_unavailable")
                is ToolOutputReadResult.Success -> listOf(
                    UIMessagePart.Text(formatReadResult(result)),
                )
            }
        },
    ),
    Tool(
        name = ToolOutputToolNames.GREP,
        // 描述补齐 marker 语义与"先 grep 定位行号再 read"的用法，不向用户暴露机制。
        description = "Searches a tool output marked as [Archived tool result: ref=...] using RE2 regex. Use to locate keywords and line numbers before reading.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("ref", buildJsonObject {
                        put("type", "integer")
                        put("description", "Reference from the archive marker.")
                    })
                    put("pattern", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "RE2 regular expression matched per line; lookaround and backreferences are unsupported. " +
                                "At most ${ToolOutputProtocolLimits.TOOL_OUTPUT_MAX_PATTERN_CHARS} characters.",
                        )
                    })
                    put("ignore_case", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Case-insensitive matching. Default false.")
                    })
                    put("context", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Context lines, 0..${ToolOutputProtocolLimits.TOOL_OUTPUT_MAX_CONTEXT_LINES}. Default 0.",
                        )
                    })
                    put("limit", buildJsonObject {
                        put("type", "integer")
                        put(
                            "description",
                            "Matches, 1..${ToolOutputProtocolLimits.TOOL_OUTPUT_MAX_GREP_MATCHES}. Default 20.",
                        )
                    })
                },
                required = listOf("ref", "pattern"),
            )
        },
        outputPolicy = ToolOutputPolicy.REGENERABLE_TEXT,
        validateArguments = validate@{ element ->
            val args = element as? JsonObject ?: return@validate rejection("invalid_arguments", "arguments must be a JSON object")
            positiveLongField(args, "ref", required = true)?.let { return@validate it }
            val pattern = (args["pattern"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (pattern.isNullOrBlank()) return@validate rejection("invalid_arguments", "pattern must be a non-blank string")
            if (pattern.length > ToolOutputProtocolLimits.TOOL_OUTPUT_MAX_PATTERN_CHARS) {
                return@validate rejection(
                    "invalid_arguments",
                    "pattern exceeds ${ToolOutputProtocolLimits.TOOL_OUTPUT_MAX_PATTERN_CHARS} characters",
                )
            }
            intInRange(
                args,
                "context",
                0,
                ToolOutputProtocolLimits.TOOL_OUTPUT_MAX_CONTEXT_LINES,
            )?.let { return@validate it }
            intInRange(
                args,
                "limit",
                1,
                ToolOutputProtocolLimits.TOOL_OUTPUT_MAX_GREP_MATCHES,
            )?.let { return@validate it }
            booleanField(args, "ignore_case")?.let { return@validate it }
            null
        },
        execute = { element ->
            val args = element as JsonObject
            val ref = requireNotNull((args["ref"] as JsonPrimitive).longOrNull)
            val pattern = (args["pattern"] as JsonPrimitive).content
            val ignoreCase = (args["ignore_case"] as? JsonPrimitive)?.booleanOrNull ?: false
            val contextLines = (args["context"] as? JsonPrimitive)?.intOrNull ?: 0
            val maxMatches = (args["limit"] as? JsonPrimitive)?.intOrNull ?: 20
            when (val result = store.grep(conversationId, ref, pattern, ignoreCase, contextLines, maxMatches)) {
                ToolOutputGrepResult.Unavailable -> failToolResult("archive_unavailable")
                ToolOutputGrepResult.InvalidPattern -> failToolResult("invalid_pattern")
                is ToolOutputGrepResult.Success -> listOf(
                    UIMessagePart.Text(formatGrepResult(result)),
                )
            }
        },
    ),
)

private val JsonPrimitive.booleanOrNull: Boolean?
    get() = (content == "true").takeIf { this.isString.not() && (content == "true" || content == "false") }

internal fun formatReadResult(result: ToolOutputReadResult.Success): String = buildString {
    if (result.lines.isEmpty()) {
        append("[lines=none; total=").append(result.totalLines)
    } else {
        append("[lines=").append(result.startLine).append('-').append(result.endLine)
            .append('/').append(result.totalLines)
    }
    result.nextStartLine?.let { append("; next=").append(it) }
    if (result.byteLimited) append("; truncated=true")
    append(']')
    result.lines.forEach { line -> append('\n').append(line.number).append(": ").append(line.text) }
}.also(::requireBoundedToolOutputText)

internal fun formatGrepResult(result: ToolOutputGrepResult.Success): String = buildString {
    append("[matches=").append(result.matchCount)
        .append("; total_lines=").append(result.totalLines)
    if (result.truncated) append("; truncated=true")
    append(']')
    result.blocks.forEachIndexed { index, block ->
        if (index > 0) append("\n--")
        block.lines.forEach { line -> append('\n').append(line.number).append(": ").append(line.text) }
    }
}.also(::requireBoundedToolOutputText)

private fun requireBoundedToolOutputText(text: String) {
    check(text.toByteArray(Charsets.UTF_8).size <= ToolOutputProtocolLimits.TOOL_OUTPUT_MAX_RESPONSE_BYTES) {
        "Tool Output lookup response exceeded its protocol byte limit"
    }
}

private fun rejection(error: String, detail: String): JsonObject = buildJsonObject {
    put("error", error)
    put("detail", detail)
}

private fun intInRange(args: JsonObject, key: String, min: Int, max: Int): JsonObject? {
    val value = args[key] ?: return null
    val primitive = value as? JsonPrimitive
    if (primitive == null || primitive.isString) {
        return rejection("invalid_arguments", "$key must be an integer")
    }
    val number = primitive.intOrNull
    if (number == null || number < min || number > max) {
        return rejection("invalid_arguments", "$key must be an integer in $min..$max")
    }
    return null
}

private fun positiveLongField(args: JsonObject, key: String, required: Boolean): JsonObject? {
    val value = args[key]
    if (value == null) {
        return if (required) rejection("invalid_arguments", "$key is required") else null
    }
    val primitive = value as? JsonPrimitive
    val number = primitive?.takeUnless { it.isString }?.longOrNull
    return if (number == null || number <= 0) {
        rejection("invalid_arguments", "$key must be a positive integer")
    } else {
        null
    }
}

private fun booleanField(args: JsonObject, key: String): JsonObject? {
    val value = args[key] ?: return null
    val primitive = value as? JsonPrimitive
    return if (primitive == null || primitive.isString || primitive.booleanOrNull == null) {
        rejection("invalid_arguments", "$key must be a boolean")
    } else {
        null
    }
}
