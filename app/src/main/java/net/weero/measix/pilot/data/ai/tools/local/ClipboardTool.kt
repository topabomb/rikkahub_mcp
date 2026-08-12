package net.weero.measix.pilot.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.utils.readClipboardText
import net.weero.measix.pilot.utils.writeClipboardText

internal fun buildClipboardTool(context: Context): Tool = Tool(
    name = "clipboard_tool",
    description = """
        Read or write the device clipboard. Do not write unless the user explicitly asks.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put(
                        "enum",
                        kotlinx.serialization.json.buildJsonArray {
                            add("read")
                            add("write")
                        }
                    )
                    put("description", "read or write")
                })
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "Text to write (required for write)")
                })
            },
            required = listOf("action")
        )
    },
    execute = {
        val params = it.jsonObject
        val action = params["action"]?.jsonPrimitive?.contentOrNull ?: error("action is required")
        when (action) {
            "read" -> {
                val payload = buildJsonObject {
                    put("text", context.readClipboardText())
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }

            "write" -> {
                val text = params["text"]?.jsonPrimitive?.contentOrNull ?: error("text is required")
                context.writeClipboardText(text)
                val payload = buildJsonObject {
                    put("success", true)
                }
                listOf(UIMessagePart.Text(payload.toString()))
            }

            else -> error("unknown action: $action, must be one of [read, write]")
        }
    }
)
