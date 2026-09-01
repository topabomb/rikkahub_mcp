package net.weero.measix.pilot.data.ai.tools

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.core.ToolInteractionRequirement
import me.rerere.ai.core.ToolOutputPolicy
import me.rerere.ai.core.ToolExecutionContext
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.subassistant.CatalogMode
import net.weero.measix.pilot.data.ai.subassistant.SubAssistantAccessPolicy
import net.weero.measix.pilot.data.ai.attachments.AttachmentFailureReasons
import net.weero.measix.pilot.data.ai.attachments.MAX_ASSISTANT_CALL_ATTACHMENTS
import net.weero.measix.pilot.data.ai.subassistant.AttachmentParseResult
import net.weero.measix.pilot.data.ai.subassistant.buildCatalogPrompt
import net.weero.measix.pilot.data.ai.subassistant.buildSubAssistantCallResult
import net.weero.measix.pilot.data.ai.subassistant.parseAssistantCallAttachments
import net.weero.measix.pilot.data.ai.subassistant.parseAssistantCallExtras
import net.weero.measix.pilot.data.ai.tools.local.LocalToolOption
import net.weero.measix.pilot.data.ai.tools.local.TtsToolPlaybackContext
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.getAssistantById
import net.weero.measix.pilot.data.datastore.getChatModel
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.normalizeDescription
import net.weero.measix.pilot.utils.jsonPrimitiveOrNull
import net.weero.measix.pilot.service.AssistantDeletionResult
import net.weero.measix.pilot.service.AssistantManagementService
import net.weero.measix.pilot.service.runtime.DelegationCoordinator
import kotlin.uuid.Uuid

private const val TOOL_ASSISTANT_MANAGE = "assistant_manage"
private const val TOOL_ASSISTANT_INSPECT = "assistant_inspect"
private const val TOOL_ASSISTANT_CALL = "assistant_call"
private const val INSPECT_SECTION_PROFILE = "profile"
private const val INSPECT_SECTION_TOOLS = "tools"
private const val INSPECT_SECTION_SKILLS = "skills"
private const val INSPECT_SECTION_MEMORY = "memory"
private val INSPECT_SECTIONS = setOf(
    INSPECT_SECTION_PROFILE,
    INSPECT_SECTION_TOOLS,
    INSPECT_SECTION_SKILLS,
    INSPECT_SECTION_MEMORY,
)

internal enum class AssistantManageAction { CREATE, UPDATE, DELETE }

internal data class AssistantManageArguments(
    val action: AssistantManageAction,
    val assistantId: Uuid?,
    val name: String?,
    val description: String?,
    val instructions: String?,
)

/** Pure model-input validation; current permissions and target existence remain execution-time facts. */
internal fun parseAssistantManageArguments(args: kotlinx.serialization.json.JsonElement): AssistantManageArguments? {
    val obj = args as? JsonObject ?: return null
    val stringFields = listOf("action", "assistant_id", "name", "description", "instructions")
    if (stringFields.any { it in obj && (obj[it] as? JsonPrimitive)?.isString != true }) return null
    val action = AssistantManageAction.entries.find { it.name == obj["action"]?.jsonPrimitive?.content } ?: return null
    val rawId = obj["assistant_id"]?.jsonPrimitive?.content
    val assistantId = rawId?.let { runCatching { Uuid.parse(it) }.getOrNull() }
    if (rawId != null && assistantId == null) return null
    val name = obj["name"]?.jsonPrimitive?.content?.trim()
    val description = obj["description"]?.jsonPrimitive?.content?.let(::normalizeDescription)
    val instructions = obj["instructions"]?.jsonPrimitive?.content?.trim()
    if (listOfNotNull(name, description, instructions).any { it.isEmpty() }) return null
    when (action) {
        AssistantManageAction.CREATE -> if (name == null || description == null || instructions == null) return null
        AssistantManageAction.UPDATE -> if (assistantId == null || listOfNotNull(name, description, instructions).isEmpty()) return null
        AssistantManageAction.DELETE -> if (assistantId == null) return null
    }
    return AssistantManageArguments(action, assistantId, name, description, instructions)
}

/**
 * 构建三个 Assistant Tools 及 Catalog。
 * 捕获当前 Master Conversation 上下文（callerAssistantId、masterConversationId）。
 *
 * - [AssistantManagement] → [TOOL_ASSISTANT_MANAGE] + [TOOL_ASSISTANT_INSPECT]
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
    /** 子助手调用的唯一执行协调器。 */
    private val delegationCoordinator: DelegationCoordinator,
    /** 提供 Target Run 可注册工具名；memory_tool 由 GenerationLoop 另加，listing 时需补上。 */
    private val toolSetFactory: GenerationToolSetFactory,
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
                add(buildAssistantInspectTool(callerAssistant.id, masterConversationId))
            }
            if (enableDelegation) {
                add(buildAssistantCallTool(
                    callerAssistantId = callerAssistant.id,
                    masterConversationId = masterConversationId,
                    enableManagement = enableManagement,
                    ttsPlaybackContext = ttsPlaybackContext,
                ))
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
                        put("description", "Display name. Required and non-empty for CREATE; optional replacement for UPDATE.")
                    })
                    put("description", buildJsonObject {
                        put("type", "string")
                        put("description", "Specialty and when to call it. Required and non-empty for CREATE; optional replacement for UPDATE. Not a system prompt.")
                    })
                    put("instructions", buildJsonObject {
                        put("type", "string")
                        put("description", "System prompt for the sub-assistant: role, method, output style. Required and non-empty for CREATE; optional replacement for UPDATE. Do not invent tools or skills.")
                    })
                },
                required = listOf("action"),
            )
        },
        systemPrompt = { _, _ ->
            // management 存在时负责完整 Catalog
            val settings = settingsStore.effectiveSettings.value.settings
            val caller = settings.assistants.find { it.id == callerAssistantId } ?: return@Tool ""
            val mode = if (enableDelegation) CatalogMode.BOTH else CatalogMode.MANAGEMENT_ONLY
            buildCatalogPrompt(
                caller = caller,
                allAssistants = settings.assistants,
                mode = mode,
                json = json,
            )
        },
        validateArguments = { args ->
            if (parseAssistantManageArguments(args) == null) errorJson("invalid_arguments") else null
        },
        interactionRequirement = { args ->
            parseAssistantManageArguments(args)?.action?.let {
                if (it != AssistantManageAction.CREATE) {
                    ToolInteractionRequirement.Approval
                } else {
                    ToolInteractionRequirement.None
                }
            } ?: ToolInteractionRequirement.None
        },
        execute = { args ->
            executeAssistantManage(callerAssistantId, args)
        },
    )

    private suspend fun executeAssistantManage(
        callerAssistantId: Uuid,
        args: kotlinx.serialization.json.JsonElement,
    ): List<UIMessagePart> {
        val parameters = parseAssistantManageArguments(args) ?: return errorResult("invalid_arguments")
        val action = parameters.action

        // 执行时从最新 Settings 重新校验 caller 仍存在、AssistantManagement 仍启用
        val settings = settingsStore.effectiveSettings.value.settings
        val caller = settings.assistants.find { it.id == callerAssistantId }
            ?: return errorResult("tool_not_permitted")
        if (LocalToolOption.AssistantManagement !in caller.localTools) {
            return errorResult("tool_not_permitted")
        }

        val result = when (action) {
            AssistantManageAction.CREATE -> {
                assistantManagementService.createAssistant(
                    requireNotNull(parameters.name), requireNotNull(parameters.description),
                    requireNotNull(parameters.instructions), callerAssistantId,
                )
            }
            AssistantManageAction.UPDATE -> {
                val assistantId = requireNotNull(parameters.assistantId)
                // Target 必须在当前 Catalog 有效范围内
                val target = settings.getAssistantById(assistantId)
                if (target == null || !SubAssistantAccessPolicy.canAccess(caller, target)) {
                    return errorResult("target_not_allowed")
                }
                assistantManagementService.updateAssistant(
                    assistantId = assistantId,
                    name = parameters.name,
                    description = parameters.description,
                    instructions = parameters.instructions,
                    callerAssistantId = callerAssistantId,
                )
            }
            AssistantManageAction.DELETE -> {
                val assistantId = requireNotNull(parameters.assistantId)
                // Target 必须在当前 Catalog 有效范围内
                val target = settings.getAssistantById(assistantId)
                if (target == null || !SubAssistantAccessPolicy.canAccess(caller, target)) {
                    return errorResult("target_not_allowed")
                }
                assistantManagementService.deleteAssistant(assistantId, callerAssistantId)
            }
        }

        val resultJson = result.fold(
            onSuccess = { data ->
                when (data) {
                    is Assistant -> {
                        buildJsonObject {
                            put("action", action.name.lowercase())
                            put("id", data.id.toString())
                        }
                    }
                    is AssistantDeletionResult -> {
                        buildJsonObject {
                            put("action", "delete")
                            put("id", data.assistant.id.toString())
                            if (data.cleanupPending) {
                                put("cleanup_pending", true)
                            }
                        }
                    }
                    else -> errorResult("operation_failed")
                }
            },
            onFailure = { error ->
                if (error is CancellationException) throw error
                val reason = when (error) {
                    is NoSuchElementException -> "assistant_not_found"
                    is IllegalArgumentException -> error.message ?: "invalid_arguments"
                    else -> "operation_failed"
                }
                errorResult(reason)
            },
        )
        return listOf(UIMessagePart.Text(resultJson.toString()))
    }

    // ---- assistant_inspect ----

    private fun buildAssistantInspectTool(
        callerAssistantId: Uuid,
        masterConversationId: Uuid,
    ): Tool = Tool(
        name = TOOL_ASSISTANT_INSPECT,
        description = "Inspect a sub-assistant's configuration before updating or deleting it. " +
            "Returns profile by default; request additional sections if needed.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("assistant_id", buildJsonObject {
                        put("type", "string")
                        put("description", "Catalog id.")
                    })
                    put("sections", buildJsonObject {
                        put("type", "array")
                        put(
                            "description",
                            "Optional: profile, tools, skills, memory.",
                        )
                        put("items", buildJsonObject {
                            put("type", "string")
                            putJsonArray("enum") {
                                add(JsonPrimitive(INSPECT_SECTION_PROFILE))
                                add(JsonPrimitive(INSPECT_SECTION_TOOLS))
                                add(JsonPrimitive(INSPECT_SECTION_SKILLS))
                                add(JsonPrimitive(INSPECT_SECTION_MEMORY))
                            }
                        })
                    })
                },
                required = listOf("assistant_id"),
            )
        },
        systemPrompt = { _, _ -> "" },
        execute = { args ->
            executeAssistantInspect(args, callerAssistantId, masterConversationId)
        },
    )

    private suspend fun executeAssistantInspect(
        args: kotlinx.serialization.json.JsonElement,
        callerAssistantId: Uuid,
        masterConversationId: Uuid,
    ): List<UIMessagePart> {
        val obj = args as? JsonObject ?: return errorResult("invalid_arguments")
        val assistantIdStr = obj["assistant_id"]?.let { (it as? JsonPrimitive)?.content }
            ?: return errorResult("invalid_arguments")
        val assistantId = runCatching { Uuid.parse(assistantIdStr) }.getOrNull()
            ?: return errorResult("invalid_arguments")

        if (assistantId == callerAssistantId) {
            return errorResult("target_is_caller")
        }

        val settings = settingsStore.effectiveSettings.value.settings
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

        val sections = parseInspectSections(obj)
        val toolNames = if (INSPECT_SECTION_TOOLS in sections) {
            listTargetToolNames(target, settings, masterConversationId)
        } else {
            emptyList()
        }
        val memory = if (INSPECT_SECTION_MEMORY in sections) {
            assistantManagementService.listAssistantMemory(assistantId).getOrElse { error ->
                val reason = when (error) {
                    is NoSuchElementException -> "assistant_not_found"
                    else -> "operation_failed"
                }
                return errorResult(reason)
            }
        } else {
            null
        }
        val resultJson = buildJsonObject {
            put("id", target.id.toString())
            if (INSPECT_SECTION_PROFILE in sections) {
                put("profile", buildJsonObject {
                    put("name", target.name)
                    put("description", target.description)
                    put("instructions", target.systemPrompt)
                })
            }
            if (INSPECT_SECTION_TOOLS in sections) {
                putJsonArray("tools") {
                    toolNames.forEach { name ->
                        add(JsonPrimitive(name))
                    }
                }
            }
            if (INSPECT_SECTION_SKILLS in sections) {
                putJsonArray("skills") {
                    target.enabledSkills.sorted().forEach { name ->
                        add(JsonPrimitive(name))
                    }
                }
            }
            if (memory != null) {
                put("memory", buildJsonObject {
                    put("active", memory.delegatedMemoryScope)
                    put(
                        "header",
                        JsonArray(listOf(JsonPrimitive("id"), JsonPrimitive("content"))),
                    )
                    put(
                        "rows",
                        JsonArray(
                            memory.memories.map { item ->
                                JsonArray(listOf(JsonPrimitive(item.id), JsonPrimitive(item.content)))
                            },
                        ),
                    )
                })
            }
        }
        return listOf(UIMessagePart.Text(resultJson.toString()))
    }

    private suspend fun listTargetToolNames(
        target: Assistant,
        settings: Settings,
        masterConversationId: Uuid,
    ): List<String> {
        val built = toolSetFactory.buildTools(
            assistant = target,
            conversationId = masterConversationId,
            settings = settings,
            capabilityModel = settings.getChatModel(target),
            runMode = ToolSetRunMode.TARGET,
            mcpCapabilities = toolSetFactory.captureMcpCapabilities(target),
        ).map { it.name }
        return buildList {
            addAll(built)
            if (target.enableMemory && "memory_tool" !in built) {
                add("memory_tool")
            }
        }
    }

    private fun parseInspectSections(obj: JsonObject): Set<String> {
        val raw = obj["sections"] ?: return setOf(INSPECT_SECTION_PROFILE)
        val names = when (raw) {
            is JsonArray -> raw.mapNotNull { element ->
                element.jsonPrimitiveOrNull?.contentOrNull
            }
            is JsonPrimitive -> listOfNotNull(raw.contentOrNull)
            else -> emptyList()
        }.map { it.trim().lowercase() }.filter { it in INSPECT_SECTIONS }
        return names.toSet().ifEmpty { setOf(INSPECT_SECTION_PROFILE) }
    }

    // ---- assistant_call ----

    private fun buildAssistantCallTool(
        callerAssistantId: Uuid,
        masterConversationId: Uuid,
        enableManagement: Boolean,
        ttsPlaybackContext: TtsToolPlaybackContext? = null,
    ): Tool = Tool(
        name = TOOL_ASSISTANT_CALL,
        // 未执行、失败与无法解析的结果继续保留；成功结果再按实际交付物决定是否可归档。
        outputPolicy = ToolOutputPolicy.PRESERVE,
        successfulOutputPolicy = { output -> assistantCallSuccessfulOutputPolicy(output, json) },
        description = "Delegate a self-contained request to a catalog sub-assistant (sub-agent). " +
            "Do not prescribe how it must work.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("assistant_id", buildJsonObject {
                        put("type", "string")
                        put("description", "Catalog id.")
                    })
                    put("request", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "It cannot see this chat. Give a clear goal, the facts it needs, and any constraints. " +
                                "Say what you need back; a concise, high-value reply is usually enough.",
                        )
                    })
                    put("extras", buildJsonObject {
                        put("type", "array")
                        put(
                            "description",
                            "Extra result content for the caller model. Default none. " +
                                "Values: artifacts, tts, tool_calls. Request artifacts when you need to inspect, " +
                                "reason about, or reuse the file contents produced by the sub-assistant. " +
                                "The user can already see those files in the call card. " +
                                "A short artifact list is always included when files were produced.",
                        )
                        put("items", buildJsonObject {
                            put("type", "string")
                            putJsonArray("enum") {
                                add(JsonPrimitive("artifacts"))
                                add(JsonPrimitive("tts"))
                                add(JsonPrimitive("tool_calls"))
                            }
                        })
                    })
                    put("attachments", buildJsonObject {
                        put("type", "array")
                        put("maxItems", MAX_ASSISTANT_CALL_ATTACHMENTS)
                        put(
                            "description",
                            "Up to 4 task-related image file paths: /upload/<file>. " +
                                "Copy paths from the user's request, [Attachment path=...] markers, " +
                                "tool result file.path, or artifacts[].path. The target cannot see this chat—" +
                                "do not assume it can see a just-uploaded image.",
                        )
                        put("items", buildJsonObject {
                            put("type", "string")
                        })
                    })
                },
                required = listOf("assistant_id", "request"),
            )
        },
        systemPrompt = { _, _ ->
            // 只有 delegation 时由 assistant_call 提供 callable Catalog
            // management 同时开启时由 assistant_manage 负责，这里不重复注入
            if (enableManagement) return@Tool ""

            val settings = settingsStore.effectiveSettings.value.settings
            val caller = settings.assistants.find { it.id == callerAssistantId } ?: return@Tool ""
            buildCatalogPrompt(
                caller = caller,
                allAssistants = settings.assistants,
                mode = CatalogMode.DELEGATION_ONLY,
                json = json,
            )
        },
        contextualExecute = { args ->
            executeAssistantCall(callerAssistantId, masterConversationId, this, args, ttsPlaybackContext)
        },
        execute = { _ ->
            // assistant_call 必须由带 durable locator 与 metadata 回写能力的执行器调用。
            callUnavailable("context_required")
        },
    )

    /**
     * 普通子助手纯文本结果可在模型读取后归档；`artifacts[].path` 在 fork 时需要专用改写，
     * 因此带这类路径、混合媒体或损坏信封的结果必须继续 inline 保留。
     */
    private fun assistantCallSuccessfulOutputPolicy(
        output: List<UIMessagePart>,
        json: Json,
    ): ToolOutputPolicy {
        val text = (output.singleOrNull() as? UIMessagePart.Text)?.text
            ?: return ToolOutputPolicy.PRESERVE
        val payload = runCatching {
            json.parseToJsonElement(text) as? JsonObject
        }.getOrNull() ?: return ToolOutputPolicy.PRESERVE
        fun stringField(name: String): String? = payload[name]?.jsonPrimitiveOrNull
            ?.takeIf { it.isString }?.contentOrNull
        val status = stringField("status")
        if (status != "completed") return ToolOutputPolicy.PRESERVE
        if (stringField("assistant_name") == null || stringField("content") == null) {
            return ToolOutputPolicy.PRESERVE
        }
        // 正式 builder 只在存在有效交付 manifest 时写 artifacts；出现该 key 即不得共享归档 payload。
        if ("artifacts" in payload) return ToolOutputPolicy.PRESERVE
        return ToolOutputPolicy.ARCHIVABLE_TEXT
    }

    /**
     * assistant_call 执行入口。
     * 通过 DelegationCoordinator 实现完整 Target 执行。
     */
    private suspend fun executeAssistantCall(
        callerAssistantId: Uuid,
        masterConversationId: Uuid,
        context: ToolExecutionContext,
        args: kotlinx.serialization.json.JsonElement,
        ttsPlaybackContext: TtsToolPlaybackContext? = null,
    ): List<UIMessagePart> {
        val obj = args as? JsonObject
            ?: return callUnavailable("invalid_arguments")
        val targetIdStr = obj["assistant_id"]?.let { (it as? JsonPrimitive)?.content }
            ?: return callUnavailable("assistant_id_required")
        val targetId = runCatching { Uuid.parse(targetIdStr) }.getOrNull()
            ?: return callUnavailable("invalid_assistant_id")
        val task = obj["request"]?.let { (it as? JsonPrimitive)?.content }
            ?: return callUnavailable("request_required")
        if (task.isBlank()) return callUnavailable("request_required")
        val attachments = when (val parsed = parseAssistantCallAttachments(obj["attachments"])) {
            is AttachmentParseResult.Invalid ->
                return callUnavailable(AttachmentFailureReasons.INVALID_ATTACHMENTS)
            is AttachmentParseResult.Ok -> parsed.paths
        }

        val output = delegationCoordinator.executeCall(
            callerAssistantId = callerAssistantId,
            masterConversationId = masterConversationId,
            targetAssistantId = targetId,
            task = task,
            execContext = context,
            turnTtsContext = ttsPlaybackContext,
            extras = parseAssistantCallExtras(obj["extras"]),
            attachments = attachments,
        )
        val envelope = output.filterIsInstance<UIMessagePart.Text>().singleOrNull()?.text?.let { text ->
            runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        }
        val status = envelope?.get("status")?.jsonPrimitiveOrNull
            ?.takeIf { it.isString }?.contentOrNull
        if (status != "completed") {
            val reason = envelope?.get("reason")?.jsonPrimitiveOrNull
                ?.takeIf { it.isString }?.contentOrNull
                ?: status
                ?: "invalid_result"
            failToolResult(output, reason)
        }
        return output
    }

    private fun callUnavailable(reason: String): Nothing {
        failToolResult(
            output = listOf(
            UIMessagePart.Text(
                buildSubAssistantCallResult(
                    json = json,
                    status = "failed",
                    assistantName = "",
                    content = "",
                    reason = reason,
                ),
            ),
            ),
            reason = reason,
        )
    }

    // ---- 工具函数 ----

    /** 仅供审批前纯参数校验返回字段级 reason；执行期失败统一走 [failToolResult]。 */
    private fun errorJson(errorCode: String): JsonObject = buildJsonObject {
        put("error", errorCode)
    }

    private fun errorResult(errorCode: String): Nothing = failToolResult(errorCode)
}
