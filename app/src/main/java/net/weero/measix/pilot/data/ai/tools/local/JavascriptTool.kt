package net.weero.measix.pilot.data.ai.tools.local

import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.QuickJsInterruptedException
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolErrorProtocol
import me.rerere.ai.core.ToolExecutionFailure
import me.rerere.ai.ui.UIMessagePart

internal fun buildJavascriptTool(): Tool = Tool(
    name = "eval_javascript",
    description = """
        Execute JavaScript (QuickJS, ES2020). Result is the last expression.
        Use toFixed() for decimal precision. No DOM or Node.js APIs. Console output precedes the result.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("code", buildJsonObject {
                    put("type", "string")
                    put("description", "The JavaScript code to execute")
                })
            },
            required = listOf("code")
        )
    },
    validateArguments = { args ->
        val code = (args as? JsonObject)?.get("code") as? JsonPrimitive
        if (code?.isString == true) null else buildJsonObject {
            put("reason", "invalid_arguments")
            put("detail", "code must be a string.")
        }
    },
    execute = {
        val code = requireNotNull(it.jsonObject["code"]?.jsonPrimitive?.contentOrNull)
        listOf(UIMessagePart.Text(evaluateJavascript(code)))
    }
)

internal const val JS_EXECUTION_TIMEOUT_MS = 10_000L
internal const val JS_MEMORY_LIMIT_BYTES = 64L * 1024 * 1024
internal const val JS_MAX_STACK_BYTES = 256L * 1024
internal const val JS_MAX_LOG_CHARS = 64 * 1024
internal const val JS_MAX_RESULT_CHARS = 1024 * 1024

internal suspend fun evaluateJavascript(
    code: String,
    timeoutMillis: Long = JS_EXECUTION_TIMEOUT_MS,
    onLog: (String) -> Unit = {},
): String = withContext(Dispatchers.Default) {
    require(timeoutMillis > 0)
    val logs = StringBuilder()
    var logsTruncated = false
    var resultTooLarge = false
    fun limitFailure(reason: String, detail: String, cause: Throwable): Nothing = throw ToolExecutionFailure(
        output = listOf(UIMessagePart.Text(ToolErrorProtocol.envelope("failed", reason, detail).toString())),
        message = detail,
        cause = cause,
    )
    try {
        withTimeout(timeoutMillis) {
            // The binding interrupts native execution on cancellation and closes after it has stopped.
            quickJs(Dispatchers.Default) {
                memoryLimit = JS_MEMORY_LIMIT_BYTES
                maxStackSize = JS_MAX_STACK_BYTES
                evaluationTimeoutMillis = timeoutMillis
                function("__measixLog") { args ->
                    val line = args[0] as String
                    val entry = if (logs.isEmpty()) line else "\n$line"
                    val remaining = JS_MAX_LOG_CHARS - logs.length
                    var end = minOf(entry.length, remaining)
                    if (end > 0 && end < entry.length && entry[end - 1].isHighSurrogate()) end--
                    logs.append(entry, 0, end)
                    logsTruncated = logsTruncated || end < entry.length
                    onLog(line)
                }
                function("__measixResultLimit") { _: Array<Any?> -> resultTooLarge = true }
                // User getters, toJSON and log formatting remain inside the same timed native evaluation.
                val result = evaluate<String?>(
                    """
                    (() => {
                        const log = globalThis.__measixLog;
                        const markLimit = globalThis.__measixResultLimit;
                        delete globalThis.__measixLog;
                        delete globalThis.__measixResultLimit;
                        const stringify = JSON.stringify;
                        const toString = String;
                        const slice = Function.call.bind(String.prototype.slice);
                        const ErrorType = Error;
                        const format = value => value !== null && typeof value === 'object'
                            ? stringify(value) : toString(value);
                        globalThis.console = {};
                        for (const level of ['log', 'info', 'warn', 'error', 'debug']) {
                            const label = level === 'debug' ? 'LOG' : level.toUpperCase();
                            console[level] = (...args) => {
                                let line = '[' + label + ']';
                                for (const arg of args) {
                                    line += ' ' + format(arg);
                                    if (line.length > $JS_MAX_LOG_CHARS) break;
                                }
                                log(slice(line, 0, $JS_MAX_LOG_CHARS + 1));
                            };
                        }
                        const result = (0, eval)(${JsonPrimitive(code)});
                        if (result == null) return null;
                        const text = typeof result === 'object' || typeof result === 'function'
                            ? stringify(result) : toString(result);
                        if (text != null && text.length > $JS_MAX_RESULT_CHARS) {
                            markLimit();
                            throw new ErrorType('JavaScript result exceeds $JS_MAX_RESULT_CHARS characters');
                        }
                        return text == null ? null : text;
                    })()
                    """.trimIndent(),
                )
                formatJavascriptOutput(
                    logs = if (logs.isEmpty()) emptyList() else listOf(
                        logs.toString() + if (logsTruncated) "\n[Logs truncated]" else ""),
                    result = result ?: "null",
                )
            }
        }
    } catch (timeout: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        limitFailure("javascript_timeout", "JavaScript execution exceeded ${timeoutMillis}ms.", timeout)
    } catch (interrupted: QuickJsInterruptedException) {
        currentCoroutineContext().ensureActive()
        limitFailure("javascript_timeout", "JavaScript execution exceeded ${timeoutMillis}ms.", interrupted)
    } catch (error: QuickJsException) {
        currentCoroutineContext().ensureActive()
        if (resultTooLarge) limitFailure("javascript_output_limit", "JavaScript result exceeds $JS_MAX_RESULT_CHARS characters.", error)
        throw error
    }
}

/** 模型可见结果保持真实换行，确保 Tool Output 的按行归档与 grep 语义忠实。 */
internal fun formatJavascriptOutput(logs: List<String>, result: String): String = buildString {
    if (logs.isNotEmpty()) {
        appendLine("[console]")
        appendLine(logs.joinToString("\n"))
    }
    appendLine("[result]")
    append(result)
}
