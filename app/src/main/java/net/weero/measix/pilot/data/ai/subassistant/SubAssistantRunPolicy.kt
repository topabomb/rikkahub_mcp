package net.weero.measix.pilot.data.ai.subassistant

import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import net.weero.measix.pilot.data.ai.tools.local.LocalToolOption
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.getChatModel
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.DEFAULT_ASSISTANT_LOCAL_TOOLS
import kotlin.uuid.Uuid

/**
 * Target Run policy 常量
 */
object SubAssistantRunPolicy {
    /**
     * 永久过滤的 LocalToolOption：Assistant Tools 不进入 Target Run
     */
    val FORBIDDEN_LOCAL_TOOLS = setOf(
        LocalToolOption.AssistantManagement,
        LocalToolOption.AssistantDelegation,
        LocalToolOption.TextToImage,
    )

}

/** Filter Target LocalTools using [SubAssistantRunPolicy.FORBIDDEN_LOCAL_TOOLS]. */
fun filterTargetLocalTools(tools: List<LocalToolOption>): List<LocalToolOption> {
    return tools.filter { it !in SubAssistantRunPolicy.FORBIDDEN_LOCAL_TOOLS }
}

/**
 * Filter Target tools by registered name, including historical
 * `assistant_memory_list` so replay cannot re-emit the old name.
 */
fun filterTargetTools(tools: List<Tool>): List<Tool> {
    val forbiddenNames = setOf(
        "assistant_manage",
        "assistant_call",
        "assistant_inspect",
        "assistant_memory_list",
        "generate_image",
    )
    return tools.filter { it.name !in forbiddenNames }
}

/**
 * Readiness 验证结果
 */
sealed class ReadinessResult {
    data object Ready : ReadinessResult()
    data class Blocked(val reason: String) : ReadinessResult()
}

/**
 * Restrict a running Target to the tool capabilities that are present in both its start snapshot
 * and the latest persisted configuration. New capabilities never enter an active run, while
 * revocations take effect when the next model step rebuilds its tool set.
 */
fun intersectTargetToolCapabilities(snapshot: Assistant, latest: Assistant): Assistant = snapshot.copy(
    enableWebSearch = snapshot.enableWebSearch && latest.enableWebSearch,
    enableRecentChatsReference = snapshot.enableRecentChatsReference && latest.enableRecentChatsReference,
    localTools = snapshot.localTools.filter { it in latest.localTools },
    mcpServers = snapshot.mcpServers intersect latest.mcpServers,
    workspaceId = snapshot.workspaceId.takeIf { it == latest.workspaceId },
    enabledSkills = snapshot.enabledSkills intersect latest.enabledSkills,
)

enum class SubAssistantModelSource {
    TARGET_CONFIGURED,
    CALLER_FALLBACK,
}

/**
 * 一次 Target 调用的稳定模型快照。只在内存中使用，不回写 Target 配置。
 *
 * Target 显式绑定模型时严格使用该模型；未绑定时继承 caller 当前有效模型及模型执行参数。
 * Target 的身份、提示词、工具、记忆与权限配置始终保持独立。
 */
data class SubAssistantRunSpec(
    val assistant: Assistant,
    val model: Model,
    val modelSource: SubAssistantModelSource,
    val modelUnavailableReason: String,
)

sealed interface SubAssistantRunSpecResolution {
    data class Ready(val spec: SubAssistantRunSpec) : SubAssistantRunSpecResolution
    data class Blocked(val reason: String) : SubAssistantRunSpecResolution
}

fun resolveSubAssistantRunSpec(
    settings: Settings,
    caller: Assistant,
    target: Assistant,
): SubAssistantRunSpecResolution {
    if (target.chatModelId != null) {
        val targetModel = settings.getChatModel(target)
            ?: return SubAssistantRunSpecResolution.Blocked("target_model_unavailable")
        return SubAssistantRunSpecResolution.Ready(
            SubAssistantRunSpec(
                assistant = target,
                model = targetModel,
                modelSource = SubAssistantModelSource.TARGET_CONFIGURED,
                modelUnavailableReason = "target_model_unavailable",
            )
        )
    }

    val callerModel = settings.getChatModel(caller)
        ?: return SubAssistantRunSpecResolution.Blocked("caller_model_unavailable")
    val runtimeTarget = target.copy(
        chatModelId = callerModel.id,
        temperature = caller.temperature,
        topP = caller.topP,
        contextMessageLimit = caller.contextMessageLimit,
        streamOutput = caller.streamOutput,
        reasoningLevel = caller.reasoningLevel,
        maxTokens = caller.maxTokens,
        customHeaders = caller.customHeaders,
        customBodies = caller.customBodies,
    )
    return SubAssistantRunSpecResolution.Ready(
        SubAssistantRunSpec(
            assistant = runtimeTarget,
            model = callerModel,
            modelSource = SubAssistantModelSource.CALLER_FALLBACK,
            modelUnavailableReason = "caller_model_unavailable",
        )
    )
}

fun Settings.isEnabledChatModel(modelId: Uuid): Boolean = providers.asSequence()
    .filter { it.enabled }
    .flatMap { it.models.asSequence() }
    .any { it.id == modelId && it.type == ModelType.CHAT }

/**
 * Lease 已获取但 Child 尚未写入时的同步重验。
 *
 * 这里仍返回 preflight 语义，便于模型知道应该修正工具权限、Target ID、访问授权或模型配置；
 * 真正开始运行后则由 [resolveActiveRunStopReason] 返回 stopped 语义。
 */
fun resolvePreWriteBlockReason(
    settings: Settings,
    callerAssistantId: Uuid,
    targetAssistantId: Uuid,
    runSpec: SubAssistantRunSpec,
): String? {
    val caller = settings.assistants.find { it.id == callerAssistantId }
        ?: return "tool_not_permitted"
    if (LocalToolOption.AssistantDelegation !in caller.localTools) {
        return "tool_not_permitted"
    }

    val target = settings.assistants.find { it.id == targetAssistantId }
        ?: return "assistant_not_found"
    if (!target.allowAsSubAssistant || !SubAssistantAccessPolicy.canAccess(caller, target)) {
        return "target_not_allowed"
    }
    if (!settings.isEnabledChatModel(runSpec.model.id)) {
        return runSpec.modelUnavailableReason
    }
    return null
}

/** 当前运行必须立即停止的配置变化；null 表示 RunSpec 仍可继续。 */
fun resolveActiveRunStopReason(
    settings: Settings,
    callerAssistantId: Uuid,
    targetAssistantId: Uuid,
    runSpec: SubAssistantRunSpec,
): String? {
    val target = settings.assistants.find { it.id == targetAssistantId }
        ?: return "target_removed"
    if (!target.allowAsSubAssistant) return "target_disabled"

    val caller = settings.assistants.find { it.id == callerAssistantId }
        ?: return "target_access_revoked"
    if (LocalToolOption.AssistantDelegation !in caller.localTools) {
        return "target_access_revoked"
    }
    if (!SubAssistantAccessPolicy.canAccess(caller, target)) {
        return "target_access_revoked"
    }
    if (!settings.isEnabledChatModel(runSpec.model.id)) {
        return runSpec.modelUnavailableReason
    }
    return null
}

/**
 * 验证调用前置条件（preflight）。
 * 按稳定顺序验证调用前置条件，阻断 reason 保持稳定且可操作。
 */
fun validateReadiness(
    targetAssistant: Assistant?,
    callerAssistantId: kotlin.uuid.Uuid,
    callerAllowedSubAssistantIds: Set<kotlin.uuid.Uuid>,
    callerHasDelegation: Boolean,
    settingsChatModel: me.rerere.ai.provider.Model?,
    isActiveRun: Boolean,
    modelUnavailableReason: String = "target_model_unavailable",
): ReadinessResult {
    if (!callerHasDelegation) {
        return ReadinessResult.Blocked("tool_not_permitted")
    }
    if (targetAssistant == null) {
        return ReadinessResult.Blocked("assistant_not_found")
    }
    if (targetAssistant.id == callerAssistantId) {
        return ReadinessResult.Blocked("target_not_allowed")
    }
    if (!targetAssistant.allowAsSubAssistant) {
        return ReadinessResult.Blocked("target_not_allowed")
    }
    // 有效访问公式：显式允许 || 全局可见
    val hasAccess = targetAssistant.id in callerAllowedSubAssistantIds ||
        targetAssistant.isSubAssistantGloballyVisible
    if (!hasAccess) {
        return ReadinessResult.Blocked("target_not_allowed")
    }
    if (settingsChatModel == null) {
        return ReadinessResult.Blocked(modelUnavailableReason)
    }
    if (isActiveRun) {
        return ReadinessResult.Blocked("target_busy")
    }
    return ReadinessResult.Ready
}

/**
 * 构建工具创建的子助手模板。
 *
 * Local Tools 与 UI 新建的默认 Assistant 保持一致；Target Run 仍会在运行时过滤
 * assistant_manage、assistant_call、assistant_inspect 和历史名 assistant_memory_list；
 * ask_user 由 Coordinator 传导到主聊天。
 */
fun buildToolCreatedAssistant(
    name: String,
    description: String,
    systemPrompt: String,
    id: kotlin.uuid.Uuid = kotlin.uuid.Uuid.random(),
): Assistant = Assistant(
    id = id,
    name = name,
    description = description,
    systemPrompt = systemPrompt,
    // 显式模板：默认 Local Tools 与普通 Assistant 共用同一常量，其余扩展能力保持关闭。
    allowAsSubAssistant = true,
    isSubAssistantGloballyVisible = false,
    allowedSubAssistantIds = emptySet(),
    chatModelId = null,
    enableMemory = true,
    useGlobalMemory = false,
    localTools = DEFAULT_ASSISTANT_LOCAL_TOOLS,
    enableWebSearch = false,
    enableRecentChatsReference = false,
    mcpServers = emptySet(),
    workspaceId = null,
    enabledSkills = emptySet(),
    modeInjectionIds = emptySet(),
    quickMessageIds = emptySet(),
    presetMessages = emptyList(),
    regexes = emptyList(),
    customHeaders = emptyList(),
    customBodies = emptyList(),
    messageTemplate = "{{ message }}",
    enableTimeReminder = false,
    allowConversationSystemPrompt = false,
    allowConversationPromptInjection = false,
)
