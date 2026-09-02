package net.weero.measix.pilot.data.ai.tools

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.model.AssistantMemory

/**
 * memory_tool 的唯一注册入口。
 *
 * 工具定义必须是常量文本：名称、description、parameters schema 和列表排序共同构成 Provider
 * 请求的可缓存前缀（Anthropic 的层级是 tools -> system -> messages，OpenAI 同样要求工具定义
 * 稳定），任何日期、Memory 内容或其他 live disclosure 都会按日击穿整条前缀。当前时间由现有
 * Time Reminder 上下文负责，不写进工具描述（权威方案 §10.2、§15）。
 */
fun buildMemoryTools(
    onCreation: suspend (String) -> AssistantMemory,
    onUpdate: suspend (Int, String) -> AssistantMemory,
    onDelete: suspend (Int) -> Unit,
    isStillAllowed: suspend () -> Boolean = { true },
): List<Tool> = listOf(
    Tool(
        name = "memory_tool",
        description = """
            Store long-term notes across conversations (create/edit/delete).
            Merge similar records; prefer edit over create.
            Do not store sensitive personal attributes.
            Do not show memory content unless the user asks.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        put(
                            "enum",
                            buildJsonArray {
                                add("create")
                                add("edit")
                                add("delete")
                            }
                        )
                        put("description", "create, edit, or delete")
                    })
                    put("id", buildJsonObject {
                        put("type", "integer")
                        put("description", "Record id (required for edit/delete)")
                    })
                    put("content", buildJsonObject {
                        put("type", "string")
                        put("description", "Note text (required for create/edit)")
                    })
                },
                required = listOf("action")
            )
        },
        execute = execute@{
            if (!isStillAllowed()) {
                failToolResult("tool_not_permitted")
            }
            val params = it.jsonObject
            val action = params["action"]?.jsonPrimitive?.contentOrNull ?: error("action is required")
            val payload = when (action) {
                "create" -> {
                    val content = params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
                    val created = onCreation(content)
                    buildJsonObject { put("id", created.id) }
                }

                "edit" -> {
                    val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
                    val content = params["content"]?.jsonPrimitive?.contentOrNull ?: error("content is required")
                    val updated = onUpdate(id, content)
                    buildJsonObject {
                        put("success", true)
                        put("id", updated.id)
                    }
                }

                "delete" -> {
                    val id = params["id"]?.jsonPrimitive?.intOrNull ?: error("id is required")
                    onDelete(id)
                    buildJsonObject {
                        put("success", true)
                        put("id", id)
                    }
                }

                else -> error("unknown action: $action, must be one of [create, edit, delete]")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )
)
