package net.weero.measix.pilot.data.configuration

import kotlinx.serialization.Serializable
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.ui.UIMessage
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.ai.tools.local.LocalToolOption
import net.weero.measix.pilot.data.enterprise.EnterpriseAssistant
import net.weero.measix.pilot.data.enterprise.EnterpriseIdentity
import net.weero.measix.pilot.data.enterprise.reference
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.AssistantRegex
import net.weero.measix.pilot.data.model.Avatar
import kotlin.uuid.Uuid

/** Missing means inherit; a present null explicitly clears a nullable setting. */
@Serializable
internal data class UsageValue<T>(val value: T)

/** Per-principal choices contain only overrides, never a second assistant definition. */
@Serializable
internal data class AssistantUsagePreferences(
    val assistantId: ConfigurationReference,
    val chatModelId: UsageValue<ConfigurationReference?>? = null,
    val avatar: UsageValue<Avatar>? = null,
    val useAssistantAvatar: UsageValue<Boolean>? = null,
    val tags: UsageValue<List<ConfigurationReference>>? = null,
    val temperature: UsageValue<Float?>? = null,
    val topP: UsageValue<Float?>? = null,
    val contextMessageLimit: UsageValue<Int>? = null,
    val streamOutput: UsageValue<Boolean>? = null,
    val enableMemory: UsageValue<Boolean>? = null,
    val useGlobalMemory: UsageValue<Boolean>? = null,
    val enableRecentChatsReference: UsageValue<Boolean>? = null,
    val messageTemplate: UsageValue<String>? = null,
    val presetMessages: UsageValue<List<UIMessage>>? = null,
    val quickMessageIds: UsageValue<Set<ConfigurationReference>>? = null,
    val regexes: UsageValue<List<AssistantRegex>>? = null,
    val reasoningLevel: UsageValue<ReasoningLevel>? = null,
    val maxTokens: UsageValue<Int?>? = null,
    val customHeaders: UsageValue<List<CustomHeader>>? = null,
    val customBodies: UsageValue<List<CustomBody>>? = null,
    val mcpServers: UsageValue<Set<ConfigurationReference>>? = null,
    val localTools: UsageValue<List<LocalToolOption>>? = null,
    val enableWebSearch: UsageValue<Boolean>? = null,
    val builtInSearch: UsageValue<Boolean?>? = null,
    val workspaceId: UsageValue<Uuid?>? = null,
    val background: UsageValue<String?>? = null,
    val backgroundOpacity: UsageValue<Float>? = null,
    val useGradientBackground: UsageValue<Boolean>? = null,
    val modeInjectionIds: UsageValue<Set<ConfigurationReference>>? = null,
    val enabledSkills: UsageValue<Set<String>>? = null,
    val enableTimeReminder: UsageValue<Boolean>? = null,
    val allowConversationSystemPrompt: UsageValue<Boolean>? = null,
    val allowConversationPromptInjection: UsageValue<Boolean>? = null,
    val additionalSubAssistantIds: Set<ConfigurationReference> = emptySet(),
) {
    init {
        if (assistantId is ConfigurationReference.Enterprise) {
            require(chatModelId == null) { "enterprise_assistant_model_is_fixed" }
            require(allowConversationSystemPrompt?.value != true) { "enterprise_assistant_prompt_is_fixed" }
        }
        require(assistantId !in additionalSubAssistantIds) { "assistant_cannot_delegate_to_itself" }
    }

    internal fun references(): List<ConfigurationReference> = buildList {
        add(assistantId)
        chatModelId?.value?.let(::add)
        addAll(tags?.value.orEmpty())
        addAll(quickMessageIds?.value.orEmpty())
        addAll(mcpServers?.value.orEmpty())
        addAll(modeInjectionIds?.value.orEmpty())
        addAll(additionalSubAssistantIds)
        addAll(regexes?.value.orEmpty().map { it.id })
        addAll(presetMessages?.value.orEmpty().mapNotNull { it.modelId })
    }
}

internal fun resolveUserAssistantUsage(
    definition: Assistant,
    usage: AssistantUsagePreferences?,
): Assistant {
    require(definition.id is ConfigurationReference.User) { "user_assistant_definition_required" }
    return applyAssistantUsage(definition, usage)
}

internal fun resolveEnterpriseAssistantUsage(
    identity: EnterpriseIdentity,
    definition: EnterpriseAssistant,
    usage: AssistantUsagePreferences?,
): Assistant {
    val fixed = Assistant(
        id = identity.reference(definition.id),
        name = definition.name,
        description = definition.description,
        chatModelId = identity.reference(definition.modelId),
        systemPrompt = definition.systemPrompt,
        mcpServers = definition.mcpServerIds.mapTo(linkedSetOf(), identity::reference),
        allowAsSubAssistant = definition.allowAsSubAssistant,
        allowedSubAssistantIds = definition.allowedSubAssistantIds.mapTo(linkedSetOf(), identity::reference),
    )
    return applyAssistantUsage(fixed, usage).let { resolved ->
        resolved.copy(
            mcpServers = fixed.mcpServers + resolved.mcpServers,
            allowConversationSystemPrompt = false,
        )
    }
}

private fun applyAssistantUsage(definition: Assistant, usage: AssistantUsagePreferences?): Assistant {
    if (usage == null) return definition
    require(usage.assistantId == definition.id) { "assistant_usage_identity_mismatch" }
    return definition.copy(
        chatModelId = if (usage.chatModelId != null) usage.chatModelId.value else definition.chatModelId,
        avatar = if (usage.avatar != null) usage.avatar.value else definition.avatar,
        useAssistantAvatar = if (usage.useAssistantAvatar != null) usage.useAssistantAvatar.value else definition.useAssistantAvatar,
        tags = if (usage.tags != null) usage.tags.value else definition.tags,
        temperature = if (usage.temperature != null) usage.temperature.value else definition.temperature,
        topP = if (usage.topP != null) usage.topP.value else definition.topP,
        contextMessageLimit = if (usage.contextMessageLimit != null) usage.contextMessageLimit.value else definition.contextMessageLimit,
        streamOutput = if (usage.streamOutput != null) usage.streamOutput.value else definition.streamOutput,
        enableMemory = if (usage.enableMemory != null) usage.enableMemory.value else definition.enableMemory,
        useGlobalMemory = if (usage.useGlobalMemory != null) usage.useGlobalMemory.value else definition.useGlobalMemory,
        enableRecentChatsReference = if (usage.enableRecentChatsReference != null) usage.enableRecentChatsReference.value else definition.enableRecentChatsReference,
        messageTemplate = if (usage.messageTemplate != null) usage.messageTemplate.value else definition.messageTemplate,
        presetMessages = if (usage.presetMessages != null) usage.presetMessages.value else definition.presetMessages,
        quickMessageIds = if (usage.quickMessageIds != null) usage.quickMessageIds.value else definition.quickMessageIds,
        regexes = if (usage.regexes != null) usage.regexes.value else definition.regexes,
        reasoningLevel = if (usage.reasoningLevel != null) usage.reasoningLevel.value else definition.reasoningLevel,
        maxTokens = if (usage.maxTokens != null) usage.maxTokens.value else definition.maxTokens,
        customHeaders = if (usage.customHeaders != null) usage.customHeaders.value else definition.customHeaders,
        customBodies = if (usage.customBodies != null) usage.customBodies.value else definition.customBodies,
        mcpServers = if (usage.mcpServers != null) usage.mcpServers.value else definition.mcpServers,
        localTools = if (usage.localTools != null) usage.localTools.value else definition.localTools,
        enableWebSearch = if (usage.enableWebSearch != null) usage.enableWebSearch.value else definition.enableWebSearch,
        builtInSearch = if (usage.builtInSearch != null) usage.builtInSearch.value else definition.builtInSearch,
        workspaceId = if (usage.workspaceId != null) usage.workspaceId.value else definition.workspaceId,
        background = if (usage.background != null) usage.background.value else definition.background,
        backgroundOpacity = if (usage.backgroundOpacity != null) usage.backgroundOpacity.value else definition.backgroundOpacity,
        useGradientBackground = if (usage.useGradientBackground != null) usage.useGradientBackground.value else definition.useGradientBackground,
        modeInjectionIds = if (usage.modeInjectionIds != null) usage.modeInjectionIds.value else definition.modeInjectionIds,
        enabledSkills = if (usage.enabledSkills != null) usage.enabledSkills.value else definition.enabledSkills,
        enableTimeReminder = if (usage.enableTimeReminder != null) usage.enableTimeReminder.value else definition.enableTimeReminder,
        allowConversationSystemPrompt = if (usage.allowConversationSystemPrompt != null) usage.allowConversationSystemPrompt.value else definition.allowConversationSystemPrompt,
        allowConversationPromptInjection = if (usage.allowConversationPromptInjection != null) usage.allowConversationPromptInjection.value else definition.allowConversationPromptInjection,
        allowedSubAssistantIds = definition.allowedSubAssistantIds + usage.additionalSubAssistantIds,
    )
}

/** Only fields changed on the page become overrides; inherited values stay absent. */
internal fun AssistantUsagePreferences.withDelta(baseline: Assistant, edited: Assistant): AssistantUsagePreferences {
    require(assistantId == baseline.id && baseline.id == edited.id) { "assistant_edit_target_changed" }
    val usageOnly = baseline.copy(
        chatModelId = edited.chatModelId,
        avatar = edited.avatar,
        useAssistantAvatar = edited.useAssistantAvatar,
        tags = edited.tags,
        temperature = edited.temperature,
        topP = edited.topP,
        contextMessageLimit = edited.contextMessageLimit,
        streamOutput = edited.streamOutput,
        enableMemory = edited.enableMemory,
        useGlobalMemory = edited.useGlobalMemory,
        enableRecentChatsReference = edited.enableRecentChatsReference,
        messageTemplate = edited.messageTemplate,
        presetMessages = edited.presetMessages,
        quickMessageIds = edited.quickMessageIds,
        regexes = edited.regexes,
        reasoningLevel = edited.reasoningLevel,
        maxTokens = edited.maxTokens,
        customHeaders = edited.customHeaders,
        customBodies = edited.customBodies,
        mcpServers = edited.mcpServers,
        localTools = edited.localTools,
        enableWebSearch = edited.enableWebSearch,
        builtInSearch = edited.builtInSearch,
        workspaceId = edited.workspaceId,
        background = edited.background,
        backgroundOpacity = edited.backgroundOpacity,
        useGradientBackground = edited.useGradientBackground,
        modeInjectionIds = edited.modeInjectionIds,
        enabledSkills = edited.enabledSkills,
        enableTimeReminder = edited.enableTimeReminder,
        allowConversationSystemPrompt = edited.allowConversationSystemPrompt,
        allowConversationPromptInjection = edited.allowConversationPromptInjection,
    )
    require(usageOnly == edited) { "assistant_definition_is_not_a_usage_preference" }
    return copy(
        chatModelId = if (baseline.chatModelId != edited.chatModelId) UsageValue(edited.chatModelId) else chatModelId,
        avatar = if (baseline.avatar != edited.avatar) UsageValue(edited.avatar) else avatar,
        useAssistantAvatar = if (baseline.useAssistantAvatar != edited.useAssistantAvatar) UsageValue(edited.useAssistantAvatar) else useAssistantAvatar,
        tags = if (baseline.tags != edited.tags) UsageValue(edited.tags) else tags,
        temperature = if (baseline.temperature != edited.temperature) UsageValue(edited.temperature) else temperature,
        topP = if (baseline.topP != edited.topP) UsageValue(edited.topP) else topP,
        contextMessageLimit = if (baseline.contextMessageLimit != edited.contextMessageLimit) UsageValue(edited.contextMessageLimit) else contextMessageLimit,
        streamOutput = if (baseline.streamOutput != edited.streamOutput) UsageValue(edited.streamOutput) else streamOutput,
        enableMemory = if (baseline.enableMemory != edited.enableMemory) UsageValue(edited.enableMemory) else enableMemory,
        useGlobalMemory = if (baseline.useGlobalMemory != edited.useGlobalMemory) UsageValue(edited.useGlobalMemory) else useGlobalMemory,
        enableRecentChatsReference = if (baseline.enableRecentChatsReference != edited.enableRecentChatsReference) UsageValue(edited.enableRecentChatsReference) else enableRecentChatsReference,
        messageTemplate = if (baseline.messageTemplate != edited.messageTemplate) UsageValue(edited.messageTemplate) else messageTemplate,
        presetMessages = if (baseline.presetMessages != edited.presetMessages) UsageValue(edited.presetMessages) else presetMessages,
        quickMessageIds = if (baseline.quickMessageIds != edited.quickMessageIds) UsageValue(edited.quickMessageIds) else quickMessageIds,
        regexes = if (baseline.regexes != edited.regexes) UsageValue(edited.regexes) else regexes,
        reasoningLevel = if (baseline.reasoningLevel != edited.reasoningLevel) UsageValue(edited.reasoningLevel) else reasoningLevel,
        maxTokens = if (baseline.maxTokens != edited.maxTokens) UsageValue(edited.maxTokens) else maxTokens,
        customHeaders = if (baseline.customHeaders != edited.customHeaders) UsageValue(edited.customHeaders) else customHeaders,
        customBodies = if (baseline.customBodies != edited.customBodies) UsageValue(edited.customBodies) else customBodies,
        mcpServers = if (baseline.mcpServers != edited.mcpServers) UsageValue(edited.mcpServers) else mcpServers,
        localTools = if (baseline.localTools != edited.localTools) UsageValue(edited.localTools) else localTools,
        enableWebSearch = if (baseline.enableWebSearch != edited.enableWebSearch) UsageValue(edited.enableWebSearch) else enableWebSearch,
        builtInSearch = if (baseline.builtInSearch != edited.builtInSearch) UsageValue(edited.builtInSearch) else builtInSearch,
        workspaceId = if (baseline.workspaceId != edited.workspaceId) UsageValue(edited.workspaceId) else workspaceId,
        background = if (baseline.background != edited.background) UsageValue(edited.background) else background,
        backgroundOpacity = if (baseline.backgroundOpacity != edited.backgroundOpacity) UsageValue(edited.backgroundOpacity) else backgroundOpacity,
        useGradientBackground = if (baseline.useGradientBackground != edited.useGradientBackground) UsageValue(edited.useGradientBackground) else useGradientBackground,
        modeInjectionIds = if (baseline.modeInjectionIds != edited.modeInjectionIds) UsageValue(edited.modeInjectionIds) else modeInjectionIds,
        enabledSkills = if (baseline.enabledSkills != edited.enabledSkills) UsageValue(edited.enabledSkills) else enabledSkills,
        enableTimeReminder = if (baseline.enableTimeReminder != edited.enableTimeReminder) UsageValue(edited.enableTimeReminder) else enableTimeReminder,
        allowConversationSystemPrompt = if (baseline.allowConversationSystemPrompt != edited.allowConversationSystemPrompt) UsageValue(edited.allowConversationSystemPrompt) else allowConversationSystemPrompt,
        allowConversationPromptInjection = if (baseline.allowConversationPromptInjection != edited.allowConversationPromptInjection) UsageValue(edited.allowConversationPromptInjection) else allowConversationPromptInjection,
    )
}
