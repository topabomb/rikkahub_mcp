package net.weero.measix.pilot.data.configuration

import me.rerere.ai.core.ReasoningLevel
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.ai.tools.local.LocalToolOption
import net.weero.measix.pilot.data.model.Assistant
import kotlin.uuid.Uuid

enum class AssistantSearchMode { OFF, LOCAL, BUILT_IN }

/** Field commands apply to the latest definition or principal preference, never a rendered aggregate. */
sealed interface AssistantPreferenceChange {
    data class Model(val reference: ConfigurationReference?) : AssistantPreferenceChange
    data object InheritModel : AssistantPreferenceChange
    data class Reasoning(val level: ReasoningLevel) : AssistantPreferenceChange
    data class Search(val mode: AssistantSearchMode) : AssistantPreferenceChange
    data class Mcp(val reference: ConfigurationReference, val enabled: Boolean) : AssistantPreferenceChange
    data class QuickMessage(val reference: ConfigurationReference, val enabled: Boolean) : AssistantPreferenceChange
    data class PromptInjection(val reference: ConfigurationReference, val enabled: Boolean) : AssistantPreferenceChange
    data class Skill(val name: String, val enabled: Boolean) : AssistantPreferenceChange
    data class Workspace(val id: Uuid?) : AssistantPreferenceChange
    data class LocalTool(val option: LocalToolOption, val enabled: Boolean) : AssistantPreferenceChange
}

internal fun Assistant.withPreference(change: AssistantPreferenceChange): Assistant = when (change) {
    is AssistantPreferenceChange.Model -> copy(chatModelId = change.reference)
    AssistantPreferenceChange.InheritModel -> copy(chatModelId = null)
    is AssistantPreferenceChange.Reasoning -> copy(reasoningLevel = change.level)
    is AssistantPreferenceChange.Search -> copy(enableWebSearch = change.mode == AssistantSearchMode.LOCAL,
        builtInSearch = change.mode == AssistantSearchMode.BUILT_IN)
    is AssistantPreferenceChange.Mcp -> copy(mcpServers = mcpServers.toggle(change.reference, change.enabled))
    is AssistantPreferenceChange.QuickMessage -> copy(quickMessageIds = quickMessageIds.toggle(change.reference, change.enabled))
    is AssistantPreferenceChange.PromptInjection -> copy(modeInjectionIds = modeInjectionIds.toggle(change.reference, change.enabled))
    is AssistantPreferenceChange.Skill -> copy(enabledSkills = enabledSkills.toggle(change.name, change.enabled))
    is AssistantPreferenceChange.Workspace -> copy(workspaceId = change.id)
    is AssistantPreferenceChange.LocalTool -> copy(localTools = if (change.enabled) (localTools + change.option).distinct() else localTools - change.option)
}

internal fun AssistantUsagePreferences.withPreference(
    change: AssistantPreferenceChange,
    current: Assistant,
): AssistantUsagePreferences = when (change) {
    is AssistantPreferenceChange.Model -> copy(chatModelId = UsageValue(change.reference))
    AssistantPreferenceChange.InheritModel -> copy(chatModelId = null)
    is AssistantPreferenceChange.Reasoning -> copy(reasoningLevel = UsageValue(change.level))
    is AssistantPreferenceChange.Search -> copy(enableWebSearch = UsageValue(change.mode == AssistantSearchMode.LOCAL),
        builtInSearch = UsageValue(change.mode == AssistantSearchMode.BUILT_IN))
    is AssistantPreferenceChange.Mcp -> copy(mcpServers = UsageValue(current.mcpServers.toggle(change.reference, change.enabled)))
    is AssistantPreferenceChange.QuickMessage -> copy(quickMessageIds = UsageValue(current.quickMessageIds.toggle(change.reference, change.enabled)))
    is AssistantPreferenceChange.PromptInjection -> copy(modeInjectionIds = UsageValue(current.modeInjectionIds.toggle(change.reference, change.enabled)))
    is AssistantPreferenceChange.Skill -> copy(enabledSkills = UsageValue(current.enabledSkills.toggle(change.name, change.enabled)))
    is AssistantPreferenceChange.Workspace -> copy(workspaceId = UsageValue(change.id))
    is AssistantPreferenceChange.LocalTool -> copy(localTools = UsageValue(if (change.enabled) (current.localTools + change.option).distinct() else current.localTools - change.option))
}

private fun <T> Set<T>.toggle(value: T, enabled: Boolean): Set<T> = if (enabled) this + value else this - value
