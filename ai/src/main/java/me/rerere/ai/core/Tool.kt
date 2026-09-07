package me.rerere.ai.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import kotlin.uuid.Uuid

/**
 * The stable, ordinal-free address of one Tool Call inside a durable Assistant message.
 *
 * Identity is `(assistantMessageId, stepId, localCallId)`: a call is located by its owning
 * Assistant message, the Step that requested it, and its own local call id. Positional ordinals
 * are never identity — they are only a transient streaming cursor inside the accumulator.
 */
@Serializable
data class ToolCallLocator(
    val assistantMessageId: Uuid,
    val stepId: Uuid,
    val localCallId: Uuid,
)

/**
 * 工具按受管文件路径请求 Runtime 读取附件的统一结果。
 *
 * [parts] 是解析成功的只读 parts（如 Image）；[failureReason] 非空表示解析失败，
 * 原因字符串由 Runtime 定义，工具应原样透传给模型，不做本地解释。
 */
data class ToolAttachmentResolution(
    val parts: List<UIMessagePart> = emptyList(),
    val failureReason: String? = null,
)

/**
 * A resource created while producing tool output but not yet rooted by a durable checkpoint.
 * The generation owner must either publish the resource after the checkpoint succeeds or call
 * [discard] during failure/cancellation compensation.
 */
class ToolResourceLease(
    val publish: suspend () -> Unit,
    val discard: suspend () -> Unit,
)

/**
 * A domain tool failure whose model-visible result is already projected by the tool owner.
 * Generation records a FAILED terminal fact and preserves [output] verbatim.
 */
class ToolExecutionFailure(
    val output: List<UIMessagePart>,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** A rejected input has a replay result, but must never enter approval or execution. */
class ToolArgumentsException(details: JsonObject) : IllegalArgumentException("invalid_arguments") {
    val output: List<UIMessagePart> = listOf(UIMessagePart.Text(buildJsonObject {
        details.forEach { (key, value) -> put(key, value) }
        put("error", details["error"] ?: JsonPrimitive("invalid_arguments"))
        put("type", "error")
    }.toString()))
}

private fun invalidToolArguments(detail: String): ToolArgumentsException = ToolArgumentsException(
    buildJsonObject {
        put("error", "invalid_arguments")
        put("detail", detail)
    }
)

/**
 * What kind of user interaction a tool call requires before it may run.
 *
 * Approval is permission for a side effect; UserInput collects content from the user
 * (for example `ask_user`). They share the pause/continue infrastructure but are distinct
 * semantics and must not be conflated through a boolean.
 */
sealed interface ToolInteractionRequirement {
    /** No user interaction is required. */
    data object None : ToolInteractionRequirement

    /** The user must explicitly approve or deny before execution. */
    data object Approval : ToolInteractionRequirement

    /** The tool needs user-supplied content; the answer itself is the replay result. */
    data object UserInput : ToolInteractionRequirement
}

/** Controls when a metadata patch becomes visible outside the active tool execution. */
enum class ToolMetadataDelivery {
    /** Transient progress; carries no newly created resource references. */
    STREAMING,
    /** A durable progress/approval fact, committed before publishing its projection. */
    CHECKPOINT,
    /** Metadata owned by the next tool result or child-link checkpoint; merge without publishing. */
    DEFERRED,
}

/**
 * 通用工具执行上下文，提供 metadata 回写能力。
 * 不引入 App 的 Conversation 类型，保持 ai 模块的平台无关性。
 *
 * [locator] 是本次执行的稳定身份 `(assistantMessageId, stepId, localCallId)`；
 * [providerCallId] 保留给 Provider 协议回放关联，不能作为内存更新的唯一键。
 *
 * 工具获得的是执行时的资源访问能力，不是 Agent 的完整会话状态：
 * [resolveAttachments] 按文件路径批量读取图片内容，不要求当前会话已引用文件，
 * 也不依赖 Workspace；Runtime 委托文件 owner 校验并读取。
 */
data class ToolExecutionContext(
    val locator: ToolCallLocator,
    val providerCallId: String,
    val reportMetadata: suspend (patch: JsonObject, delivery: ToolMetadataDelivery) -> Unit,
    /** 按受管文件路径读取请求级图片内容，不创建持久化副本。 */
    val resolveAttachments: suspend (paths: List<String>) -> ToolAttachmentResolution,
    /** 委派类工具在派生会话确定后回写其 id，并入本次工具执行的 durable 事实。 */
    val reportChildConversation: suspend (childConversationId: String) -> Unit,
    /** Transfers an unpublished output resource to the generation/checkpoint owner. */
    val registerUnpublishedResource: (ToolResourceLease) -> Unit,
    /** Derived from this call's committed approval decision, never from model arguments. */
    val approvedByUser: Boolean = false,
)

/**
 * Provider 唯一可见的工具 wire 事实：名称、描述、JSON Schema 与 System
 * prompt contribution 都是装配时已物化的值，不含任何可执行闭包。由 [freeze] 在 START
 * 装配时一次性生成；Provider step 与 adapter 不得再求值或回退到 [Tool]。
 */
@Serializable
data class FrozenToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject?,
    val systemPromptContribution: String,
)

/**
 * 物化 wire definition：这是 `parameters()` 的唯一求值点（定义、排序与 Schema
 * 在 Turn 内稳定，动态内容不得击穿缓存前缀）。执行闭包不经过这里。
 */
fun Tool.freeze(): FrozenToolDefinition {
    val evaluatedParameters = parameters()
    // Detach from a caller-owned mutable backing map so START bytes cannot change in place.
    val frozenParameters = evaluatedParameters?.let {
        Json.parseToJsonElement(it.toString()).jsonObject
    }
    return FrozenToolDefinition(
        name = name,
        description = description,
        parameters = frozenParameters,
        systemPromptContribution = systemPromptContribution,
    )
}

@Serializable
data class Tool(
    val name: String,
    val description: String,
    val parameters: () -> JsonObject? = { null },
    /**
     * 该工具对 System prompt 的固定贡献。装配（= Turn START）时求值为字符串；
     * 不得按 step、日期、请求消息或 live Settings 重算。
     */
    val systemPromptContribution: String = "",
    /**
     * Pure function over already-validated arguments. It must not read Settings, databases,
     * files, network or request Android permissions; resource and permission re-validation
     * belongs to the execution owner after approval.
     */
    val interactionRequirement: (JsonObject) -> ToolInteractionRequirement = {
        ToolInteractionRequirement.None
    },
    /** Pure input validation. No resource access, authorization changes or side effects. */
    @Transient
    val validateArguments: (JsonElement) -> JsonObject? = { null },
    val outputPolicy: ToolOutputPolicy = ToolOutputPolicy.ARCHIVABLE_TEXT,
    /**
     * 成功结果落入 Runtime metadata 前的纯策略解析；默认沿用静态策略。
     * 仅用于结果内容决定可恢复性的工具，不得访问 IO 或外部状态。
     */
    @Transient
    val successfulOutputPolicy: (List<UIMessagePart>) -> ToolOutputPolicy = { outputPolicy },
    val execute: suspend (JsonElement) -> List<UIMessagePart>,
    /**
     * 带 receiver 的 contextual execute，可回写 metadata 和使用请求级资源能力。
     * 未声明时由 [execute] 处理普通无上下文工具。
     */
    @Transient
    val contextualExecute: (suspend ToolExecutionContext.(JsonElement) -> List<UIMessagePart>)? = null,
) {
    /** Same parser for approval and execution; remote schema semantics remain with the server. */
    fun parseArguments(input: String, json: Json): JsonObject {
        // Providers may represent a valid empty argument object as an empty streaming buffer.
        val parsed = try {
            json.parseToJsonElement(input.ifBlank { "{}" })
        } catch (_: SerializationException) {
            throw invalidToolArguments("Arguments must be valid JSON.")
        }
        val arguments = parsed as? JsonObject
            ?: throw invalidToolArguments("Arguments must be a JSON object.")
        validateArguments(arguments)?.let { rejection ->
            throw ToolArgumentsException(rejection)
        }
        return arguments
    }

    /**
     * 统一执行入口：上下文工具使用 [contextualExecute]，普通工具使用 [execute]。
     */
    suspend fun executeWithContext(
        context: ToolExecutionContext,
        args: JsonElement,
    ): List<UIMessagePart> {
        return if (contextualExecute != null) {
            context.contextualExecute(args)
        } else {
            execute(args)
        }
    }
}

/**
 * Convenience builders for tool input JSON Schema documents.
 *
 * [Tool.parameters] deliberately exposes [JsonObject] rather than a closed Kotlin model: JSON
 * Schema is extensible and MCP tools may use keywords such as `$defs`, `$ref`, or future dialect
 * additions that the client must preserve losslessly.
 */
object InputSchema {
    @Suppress("FunctionName")
    fun Obj(
        properties: JsonObject,
        required: List<String>? = null,
    ): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", properties)
        required?.let { names ->
            put("required", JsonArray(names.map(::JsonPrimitive)))
        }
    }
}

@Serializable
enum class ToolOutputPolicy {
    /**
     * The historical pure-text result may be replaced by a recoverable archive reference
     * after a successful Provider step has actually read it. This is deliberately not
     * "truncate immediately when the tool returns".
     */
    ARCHIVABLE_TEXT,

    /**
     * The historical pure-text result is derived from other durable state and may be folded
     * after successful Provider consumption. Folding never creates another payload copy; the
     * original call arguments remain available if the model needs to run the lookup again.
     */
    REGENERABLE_TEXT,

    /** Always keep the complete Provider replay output inline. */
    PRESERVE,
}
