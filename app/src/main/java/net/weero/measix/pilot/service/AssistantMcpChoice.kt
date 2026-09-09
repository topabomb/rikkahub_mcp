package net.weero.measix.pilot.service

import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.ai.mcp.McpStatus
import net.weero.measix.pilot.data.configuration.ConfigurationCategory
import net.weero.measix.pilot.data.configuration.ConfigurationUnavailableReason
import net.weero.measix.pilot.data.model.Assistant

internal data class AssistantMcpChoice(
    val serverId: ConfigurationReference,
    val name: String,
    val selected: Boolean,
    val canToggle: Boolean,
    val unavailableReason: ConfigurationUnavailableReason?,
    val status: McpStatus,
    val tools: List<McpToolPresentation>,
) {
    val isReady: Boolean get() = unavailableReason == null && tools.isNotEmpty()
    val isBusy: Boolean get() = !isReady && (status == McpStatus.Connecting || status == McpStatus.Discovering)
}

internal fun ConversationConfigurationUiModel.mcpChoices(runtime: List<McpServerPresentation>): List<AssistantMcpChoice> {
    val selected = assistant?.mcpServers.orEmpty()
    val definitions = resources.filter { it.key.category == ConfigurationCategory.MCP }.associateBy { it.key.reference }
    return (definitions.keys + selected).map { id ->
        val definition = definitions[id]
        val reason = definition?.access?.unavailableReason
            ?: if (definition == null) ConfigurationUnavailableReason.REFERENCE_MISSING else null
        val status = runtime.singleOrNull { it.serverId == id && it.access == target.conversation.selection.access }
        AssistantMcpChoice(id, definition?.name ?: id.toString(), id in selected,
            assistant != null && id !in fixedMcpBindings && (id in selected || reason == null), reason,
            status?.status ?: McpStatus.Idle, status?.tools.orEmpty())
    }
}

internal fun List<McpServerPresentation>.assistantChoices(assistant: Assistant): List<AssistantMcpChoice> = map { server ->
    AssistantMcpChoice(server.serverId, server.name, server.serverId in assistant.mcpServers,
        server.enabled || server.serverId in assistant.mcpServers,
        if (server.enabled) null else ConfigurationUnavailableReason.RESOURCE_DISABLED, server.status, server.tools)
}
