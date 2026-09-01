package net.weero.measix.pilot.data.ai.tools.local

import com.whl.quickjs.wrapper.QuickJSContext
import com.whl.quickjs.wrapper.QuickJSObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
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
    execute = {
        val logs = arrayListOf<String>()
        val context = QuickJSContext.create()
        try {
            context.setConsole(object : QuickJSContext.Console {
                override fun log(info: String?) {
                    logs.add("[LOG] $info")
                }

                override fun info(info: String?) {
                    logs.add("[INFO] $info")
                }

                override fun warn(info: String?) {
                    logs.add("[WARN] $info")
                }

                override fun error(info: String?) {
                    logs.add("[ERROR] $info")
                }
            })
            val code = it.jsonObject["code"]?.jsonPrimitive?.contentOrNull
            val result = context.evaluate(code)
            listOf(
                UIMessagePart.Text(
                    formatJavascriptOutput(
                        logs = logs,
                        result = when (result) {
                            null -> "null"
                            is QuickJSObject -> result.stringify()
                            else -> result.toString()
                        },
                    )
                )
            )
        } finally {
            // 确保无论执行成功或抛异常都释放原生 JS runtime, 避免内存泄漏
            context.destroy()
        }
    }
)

/** 模型可见结果保持真实换行，确保 Tool Output 的按行归档与 grep 语义忠实。 */
internal fun formatJavascriptOutput(logs: List<String>, result: String): String = buildString {
    if (logs.isNotEmpty()) {
        appendLine("[console]")
        appendLine(logs.joinToString("\n"))
    }
    appendLine("[result]")
    append(result)
}
