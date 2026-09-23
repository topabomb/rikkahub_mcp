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
import net.weero.measix.pilot.data.repository.MemoryNotFoundException
import net.weero.measix.pilot.service.MemoryAccessRejectedException
import net.weero.measix.pilot.data.enterprise.EnterpriseConfigurationException
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.model.AssistantMemory

/**
 * memory_tool 的唯一注册入口。
 *
 * 工具定义必须是常量文本：名称、description、parameters schema 和列表排序共同构成 Provider
 * 请求的可缓存前缀（Anthropic 的层级是 tools -> system -> messages，OpenAI 同样要求工具定义
 * 稳定），任何日期、Memory 内容或其他 live disclosure 都会按日击穿整条前缀。当前时间由现有
 * Time Reminder 上下文负责，不写进工具描述。
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
        validateArguments = ::validateMemoryArguments,
        execute = execute@{
            if (!isStillAllowed()) {
                failToolResult("tool_not_permitted", "Memory access is no longer permitted in this run.")
            }
            val params = it.jsonObject
            val action = requireNotNull(params["action"]?.jsonPrimitive?.contentOrNull)
            val payload = try { when (action) {
                "create" -> {
                    val content = requireNotNull(params["content"]?.jsonPrimitive?.contentOrNull)
                    val created = onCreation(content)
                    buildJsonObject { put("id", created.id) }
                }

                "edit" -> {
                    val id = requireNotNull(params["id"]?.jsonPrimitive?.intOrNull)
                    val content = requireNotNull(params["content"]?.jsonPrimitive?.contentOrNull)
                    val updated = onUpdate(id, content)
                    buildJsonObject {
                        put("success", true)
                        put("id", updated.id)
                    }
                }

                "delete" -> {
                    val id = requireNotNull(params["id"]?.jsonPrimitive?.intOrNull)
                    onDelete(id)
                    buildJsonObject {
                        put("success", true)
                        put("id", id)
                    }
                }

                else -> error("Validated memory action changed: $action")
            } } catch (missing: MemoryNotFoundException) {
                failToolResult("memory_not_found_in_namespace",
                    "Memory ${missing.memoryId} does not exist in the current namespace. Do not retry this ID unchanged.")
            } catch (rejected: MemoryAccessRejectedException) {
                failToolResult("tool_not_permitted", "Memory access was revoked: ${rejected.reason}.")
            } catch (rejected: EnterpriseConfigurationException) {
                failToolResult("tool_not_permitted", "Memory access was revoked: ${rejected.reason}.")
            }
            listOf(UIMessagePart.Text(payload.toString()))
        }
    )
)

private fun validateMemoryArguments(element: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonObject? {
    val params = element as? kotlinx.serialization.json.JsonObject
        ?: return invalidMemoryArguments("Arguments must be a JSON object.")
    val action = (params["action"] as? kotlinx.serialization.json.JsonPrimitive)
        ?.takeIf { it.isString }?.contentOrNull
    if (action !in setOf("create", "edit", "delete")) {
        return invalidMemoryArguments("action must be create, edit, or delete.")
    }
    if (action == "edit" || action == "delete") {
        val id = (params["id"] as? kotlinx.serialization.json.JsonPrimitive)
            ?.takeIf { !it.isString }?.intOrNull
        if (id == null || id <= 0) return invalidMemoryArguments("id must be a positive integer for $action.")
    }
    if (action == "create" || action == "edit") {
        val content = (params["content"] as? kotlinx.serialization.json.JsonPrimitive)
            ?.takeIf { it.isString }?.contentOrNull
        if (content == null) return invalidMemoryArguments("content must be a string for $action.")
    }
    return null
}

private fun invalidMemoryArguments(detail: String) = invalidToolArguments(detail)
