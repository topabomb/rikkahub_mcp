package net.weero.measix.pilot.data.datastore

import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.configuration.AssistantUsagePreferences
import net.weero.measix.pilot.data.configuration.ConfigurationCategory
import net.weero.measix.pilot.data.configuration.ResolvedConfiguration

internal fun requireAssistantUsageWriteAllowed(
    before: AssistantUsagePreferences?,
    proposed: AssistantUsagePreferences?,
    assistantId: ConfigurationReference,
    previousResolved: ResolvedConfiguration,
    resolved: ResolvedConfiguration,
) {
    fun requireSelectable(category: ConfigurationCategory, id: ConfigurationReference) {
        val access = resolved.access(category, id)
        if (!access.canSelect) throw SettingsLockedException("assistantUsage/$assistantId", access.unavailableReason!!.name)
    }
    // Reset is a preference deletion, not an implicit selection of a replacement resource.
    if (proposed == null) return
    requireSelectable(ConfigurationCategory.ASSISTANT, assistantId)
    if (proposed.chatModelId != before?.chatModelId && proposed.chatModelId?.value != null) {
        val selected = resolved.assistantModel(assistantId)
        if (!selected.isAvailable) throw SettingsLockedException(
            "assistantUsage/$assistantId/chatModelId", selected.unavailableReason?.name ?: "model_selection_required",
        )
    }
    if (proposed.mcpServers != before?.mcpServers) {
        val previous = previousResolved.assistants[assistantId]?.mcpServers.orEmpty()
        (proposed.mcpServers?.value.orEmpty() - previous).forEach { requireSelectable(ConfigurationCategory.MCP, it) }
    }
    (proposed.additionalSubAssistantIds - before?.additionalSubAssistantIds.orEmpty()).forEach { id ->
        requireSelectable(ConfigurationCategory.ASSISTANT, id)
        require(resolved.assistants[id]?.allowAsSubAssistant == true) { "assistant_not_available_for_delegation" }
    }
}
