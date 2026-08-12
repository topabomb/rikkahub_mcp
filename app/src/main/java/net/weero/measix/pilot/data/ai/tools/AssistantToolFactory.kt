package net.weero.measix.pilot.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolExecutionContext
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.subassistant.CatalogMode
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantAccessPolicy
import net.weero.measix.pilot.data.ai.subassistant.buildCatalogPrompt
import net.weero.measix.pilot.data.ai.tools.local.LocalToolOption
import net.weero.measix.pilot.data.ai.tools.local.TtsToolPlaybackContext
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.getAssistantById
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.service.AssistantDeletionResult
import net.weero.measix.pilot.service.AssistantManagementService
import net.weero.measix.pilot.service.SubAssistantCoordinator
import kotlin.uuid.Uuid

private const val TOOL_ASSISTANT_MANAGE = "assistant_manage"
private const val TOOL_ASSISTANT_MEMORY_LIST = "assistant_memory_list"
private const val TOOL_ASSISTANT_CALL = "assistant_call"

/**
 * 构建三个 Assistant Tools 及 Catalog。
 * 捕获当前 Master Conversation 上下文（callerAssistantId、masterConversationId）。
 *
 * - [AssistantManagement] → [TOOL_ASSISTANT_MANAGE] + [TOOL_ASSISTANT_MEMORY_LIST]
 * - [AssistantDelegation] → [TOOL_ASSISTANT_CALL]
 *
 * Catalog 通过 [Tool.systemPrompt] 动态注入：
 * - management 存在时由 [TOOL_ASSISTANT_MANAGE] 负责完整 Catalog；
 * - 只有 delegation 时由 [TOOL_ASSISTANT_CALL] 提供 callable Catalog。
 */
class AssistantToolFactory(
    private val settingsStore: SettingsStore,
    private val assistantManagementService: AssistantManagementService,
    private val json: Json,
    /**
     * 子助手执行协调器。Phase D 注入；为 null 时 assistant_call 返回 unavailable。
     */
    private val subAssistantCoordinator: SubAssistantCoordinator? = null,
) {
    /**
     * 按 caller Assistant 的 LocalTool 配置构建工具。
     */
    fun buildTools(
        callerAssistant: Assistant,
        masterConversationId: Uuid,
        ttsPlaybackContext: TtsToolPlaybackContext? = null,
    ): List<Tool> {
        val enableManagement = LocalToolOption.AssistantManagement in callerAssistant.localTools
        val enableDelegation = LocalToolOption.AssistantDelegation in callerAssistant.localTools

        if (!enableManagement && !enableDelegation) return emptyList()

        return buildList {
            if (enableManagement) {
                add(buildAssistantManageTool(callerAssistant.id, enableDelegation))
                add(buildAssistantMemoryListTool(callerAssistant.id))
            }
            if (enableDelegation) {
                add(buildAssistantCallTool(callerAssistant.id, masterConversationId, enableManagement, ttsPlaybackContext))
            }
        }
    }

    // ---- assistant_manage ----

    private fun buildAssistantManageTool(
        callerAssistantId: Uuid,
        enableDelegation: Boolean,
    ): Tool = Tool(
        name = TOOL_ASSISTANT_MANAGE,
        description = "Create, update, or delete a sub-assistant (sub-agent). New ones join your allowed list.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("action", buildJsonObject {
                        put("type", "string")
                        putJsonArray("enum") {
                            add(JsonPrimitive("CREATE"))
                            add(JsonPrimitive("UPDATE"))
                            add(JsonPrimitive("DELETE"))
                        }
                        put("description", "CREATE, UPDATE, or DELETE.")
                    })
                    put("assistant_id", buildJsonObject {
                        put("type", "string")
                        put("description", "Required for UPDATE and DELETE.")
                    })
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Display name.")
                    })
                    put("description", buildJsonObject {
                        put("type", "string")
                        put("description", "Specialty and when to call it. Not a system prompt.")
                    })
                    put("instructions", buildJsonObject {
                        put("type", "string")
                        put("description", "System prompt for the sub-assistant: role, method, output style. Do not invent tools or skills.")
                    })
                },
                required = listOf("action"),
            )
        },
        systemPrompt = { _, _ ->
            // management 存在时负责完整 Catalog
            val settings = settingsStore.settingsFlow.value
            val caller = settings.assistants.find { it.id == callerAssistantId } ?: return@Tool ""
            val mode = if (enableDelegation) CatalogMode.BOTH else CatalogMode.MANAGEMENT_ONLY
            buildCatalogPrompt(
                caller = caller,
                allAssistants = settings.assistants,
                mode = mode,
                json = json,
            )
        },
        needsApproval = { true },
        execute = { args ->
            executeAssistantManage(callerAssistantId, args)
        },
    )

    private suspend fun executeAssistantManage(
        callerAssistantId: Uuid,
        args: kotlinx.serialization.json.JsonElement,
    ): List<UIMessagePart> {
        val obj = args as? JsonObject ?: return errorResult("invalid_arguments")
        val action = obj["action"]?.let { (it as? JsonPrimitive)?.content } ?: return errorResult("invalid_arguments")

        // 执行时从最新 Settings 重新校验 caller 仍存在、AssistantManagement 仍启用
        val settings = settingsStore.settingsFlow.value
        val caller = settings.assistants.find { it.id == callerAssistantId }
            ?: return errorResult("tool_not_permitted")
        if (LocalToolOption.AssistantManagement !in caller.localTools) {
            return errorResult("tool_not_permitted")
        }

        val result = when (action) {
            "CREATE" -> {
                val name = obj["name"]?.let { (it as? JsonPrimitive)?.content } ?: ""
                val description = obj["description"]?.let { (it as? JsonPrimitive)?.content } ?: ""
                val instructions = obj["instructions"]?.let { (it as? JsonPrimitive)?.content } ?: ""
                assistantManagementService.createAssistant(name, description, instructions, callerAssistantId)
            }
            "UPDATE" -> {
                val assistantIdStr = obj["assistant_id"]?.let { (it as? JsonPrimitive)?.content }
                    ?: return errorResult("invalid_arguments")
                val assistantId = runCatching { Uuid.parse(assistantIdStr) }.getOrNull()
                    ?: return errorResult("invalid_arguments")
                // Target 必须在当前 Catalog 有效范围内
                val target = settings.getAssistantById(assistantId)
                if (target == null || !SubAssistantAccessPolicy.canAccess(caller, target)) {
                    return errorResult("target_not_allowed")
                }
                assistantManagementService.updateAssistant(
                    assistantId = assistantId,
                    name = obj["name"]?.let { (it as? JsonPrimitive)?.content },
                    description = obj["description"]?.let { (it as? JsonPrimitive)?.content },
                    instructions = obj["instructions"]?.let { (it as? JsonPrimitive)?.content },
                    callerAssistantId = callerAssistantId,
                )
            }
            "DELETE" -> {
                val assistantIdStr = obj["assistant_id"]?.let { (it as? JsonPrimitive)?.content }
                    ?: return errorResult("invalid_arguments")
                val assistantId = runCatching { Uuid.parse(assistantIdStr) }.getOrNull()
                    ?: return errorResult("invalid_arguments")
                // Target 必须在当前 Catalog 有效范围内
                val target = settings.getAssistantById(assistantId)
                if (target == null || !SubAssistantAccessPolicy.canAccess(caller, target)) {
                    return errorResult("target_not_allowed")
                }
                assistantManagementService.deleteAssistant(assistantId, callerAssistantId)
            }
            else -> Result.failure(IllegalArgumentException("invalid_arguments"))
        }

        val resultJson = result.fold(
            onSuccess = { data ->
                when (data) {
                    is Assistant -> {
                        // 紧凑结果：不回显 instructions，不返回 callable
                        buildJsonObject {
                            put("action", action.lowercase())
                            put("assistant", buildJsonObject {
                                put("id", data.id.toString())
                                put("name", data.name)
                                put("description", data.description)
                            })
                        }
                    }
                    is AssistantDeletionResult -> {
                        buildJsonObject {
                            put("action", "delete")
                            put("assistant", buildJsonObject {
                                put("id", data.assistant.id.toString())
                                put("name", data.assistant.name)
                            })
                            if (data.cleanupPending) {
                                put("cleanup_pending", true)
                            }
                        }
                    }
                    else -> {
                        buildJsonObject {
                            put("error", "operation_failed")
                        }
                    }
                }
            },
            onFailure = { error ->
                val reason = when (error) {
                    is NoSuchElementException -> "assistant_not_found"
                    is IllegalArgumentException -> error.message ?: "invalid_arguments"
                    else -> "operation_failed"
                }
                buildJsonObject {
                    put("error", reason)
                }
            },
        )
        return listOf(UIMessagePart.Text(resultJson.toString()))
    }

    // ---- assistant_memory_list ----

    private fun buildAssistantMemoryListTool(
        callerAssistantId: Uuid,
    ): Tool = Tool(
        name = TOOL_ASSISTANT_MEMORY_LIST,
        description = "List the local memories of a sub-assistant in the catalog. This is read-only; only the target can change them through its own memory tools. Global memory is never returned.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("assistant_id", buildJsonObject {
                        put("type", "string")
                        put("description", "Catalog id.")
                    })
                },
                required = listOf("assistant_id"),
            )
        },
        systemPrompt = { _, _ -> "" },
        needsApproval = { false },
        execute = { args ->
            executeAssistantMemoryList(args, callerAssistantId)
        },
    )

    private suspend fun executeAssistantMemoryList(
        args: kotlinx.serialization.json.JsonElement,
        callerAssistantId: Uuid,
    ): List<UIMessagePart> {
        val obj = args as? JsonObject ?: return errorResult("invalid_arguments")
        val assistantIdStr = obj["assistant_id"]?.let { (it as? JsonPrimitive)?.content }
            ?: return errorResult("invalid_arguments")
        val assistantId = runCatching { Uuid.parse(assistantIdStr) }.getOrNull()
            ?: return errorResult("invalid_arguments")

        // 拒绝 caller 查看自身记忆
        if (assistantId == callerAssistantId) {
            return errorResult("target_is_caller")
        }

        // 执行时从最新 Settings 重新校验 caller 仍存在、AssistantManagement 仍启用、Target 在有效范围
        val settings = settingsStore.settingsFlow.value
        val caller = settings.assistants.find { it.id == callerAssistantId }
            ?: return errorResult("tool_not_permitted")
        if (LocalToolOption.AssistantManagement !in caller.localTools) {
            return errorResult("tool_not_permitted")
        }
        val target = settings.getAssistantById(assistantId)
            ?: return errorResult("assistant_not_found")
        if (!SubAssistantAccessPolicy.canAccess(caller, target)) {
            return errorResult("target_not_allowed")
        }

        val result = assistantManagementService.listAssistantMemory(assistantId)
        val resultJson = result.fold(
            onSuccess = { data ->
                buildJsonObject {
                    put("assistant", buildJsonObject {
                        put("id", data.assistantId)
                        put("name", data.assistantName)
                    })
                    put("active_memory", data.delegatedMemoryScope)
                    // header + rows 紧凑格式
                    put("header", JsonArray(listOf(
                        JsonPrimitive("id"),
                        JsonPrimitive("content")
                    )))
                    put("rows", JsonArray(data.memories.map { item ->
                        JsonArray(listOf(
                            JsonPrimitive(item.id),
                            JsonPrimitive(item.content)
                        ))
                    }))
                }
            },
            onFailure = { error ->
                val reason = when (error) {
                    is NoSuchElementException -> "assistant_not_found"
                    else -> "operation_failed"
                }
                buildJsonObject {
                    put("error", reason)
                }
            },
        )
        return listOf(UIMessagePart.Text(resultJson.toString()))
    }

    // ---- assistant_call ----

    private fun buildAssistantCallTool(
        callerAssistantId: Uuid,
        masterConversationId: Uuid,
        enableManagement: Boolean,
        ttsPlaybackContext: TtsToolPlaybackContext? = null,
    ): Tool = Tool(
        name = TOOL_ASSISTANT_CALL,
        description = "Delegate a self-contained request to a catalog sub-assistant (sub-agent). Do not prescribe how it must work.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("assistant_id", buildJsonObject {
                        put("type", "string")
                        put("description", "Catalog id.")
                    })
                    put("request", buildJsonObject {
                        put("type", "string")
                        put("description", "It cannot see this chat. Include facts, constraints, and the expected deliverable.")
                    })
                },
                required = listOf("assistant_id", "request"),
            )
        },
        systemPrompt = { _, _ ->
            // 只有 delegation 时由 assistant_call 提供 callable Catalog
            // management 同时开启时由 assistant_manage 负责，这里不重复注入
            if (enableManagement) return@Tool ""

            val settings = settingsStore.settingsFlow.value
            val caller = settings.assistants.find { it.id == callerAssistantId } ?: return@Tool ""
            buildCatalogPrompt(
                caller = caller,
                allAssistants = settings.assistants,
                mode = CatalogMode.DELEGATION_ONLY,
                json = json,
            )
        },
        needsApproval = { false },
        contextualExecute = { args ->
            executeAssistantCall(callerAssistantId, masterConversationId, this, args, ttsPlaybackContext)
        },
        execute = { _ ->
            // Fallback: 缺少真实 locator/reportMetadata 时不能启动 Child
            listOf(UIMessagePart.Text(buildJsonObject {
                put("status", "unavailable")
                put("reason", "context_required")
            }.toString()))
        },
    )

    /**
     * assistant_call 执行入口。
     * 通过 SubAssistantCoordinator 实现完整 Target 执行。
     */
    private suspend fun executeAssistantCall(
        callerAssistantId: Uuid,
        masterConversationId: Uuid,
        context: ToolExecutionContext,
        args: kotlinx.serialization.json.JsonElement,
        ttsPlaybackContext: TtsToolPlaybackContext? = null,
    ): List<UIMessagePart> {
        val coordinator = subAssistantCoordinator
            ?: return listOf(UIMessagePart.Text(buildJsonObject {
                put("status", "failed")
                put("reason", "runtime_error")
            }.toString()))

        val obj = args as? JsonObject
            ?: return errorResult("invalid_arguments")
        val targetIdStr = obj["assistant_id"]?.let { (it as? JsonPrimitive)?.content }
            ?: return errorResult("assistant_id_required")
        val targetId = runCatching { Uuid.parse(targetIdStr) }.getOrNull()
            ?: return errorResult("invalid_assistant_id")
        val task = obj["request"]?.let { (it as? JsonPrimitive)?.content }
            ?: obj["task"]?.let { (it as? JsonPrimitive)?.content } // backward compat
            ?: return errorResult("request_required")
        if (task.isBlank()) return errorResult("request_required")

        return coordinator.executeCall(
            callerAssistantId = callerAssistantId,
            masterConversationId = masterConversationId,
            targetAssistantId = targetId,
            task = task,
            execContext = context,
            turnTtsContext = ttsPlaybackContext,
        )
    }

    // ---- 工具函数 ----

    private fun errorResult(errorCode: String): List<UIMessagePart> {
        val json = buildJsonObject {
            put("error", errorCode)
        }
        return listOf(UIMessagePart.Text(json.toString()))
    }
}
