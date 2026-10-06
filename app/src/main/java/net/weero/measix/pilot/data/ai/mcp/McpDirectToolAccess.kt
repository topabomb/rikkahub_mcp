package net.weero.measix.pilot.data.ai.mcp

import net.weero.measix.pilot.data.enterprise.EnterpriseMcpResource
import net.weero.measix.pilot.data.enterprise.PlatformAssistantMcpBinding
import net.weero.measix.pilot.data.enterprise.PlatformAssistantMcpBindingToolSelection
import net.weero.measix.pilot.data.enterprise.PlatformMcpDefinitionToolAccessMode
import net.weero.measix.pilot.data.enterprise.PlatformMcpToolGrantApprovalPolicy

/** Pure projection of published rules onto the complete confirmed remote Tool object. */
enum class McpToolUnavailableReason {
    NOT_ALLOWED, NOT_SELECTED, MISSING, CONTRACT_CHANGED, DIRECTORY_UNAVAILABLE,
}

internal data class McpToolAccess(
    val name: String,
    val tool: McpCatalogTool?,
    val needsApproval: Boolean,
    val contractHash: String?,
    val unavailableReason: McpToolUnavailableReason? = null,
) {
    val enabled: Boolean get() = tool != null && unavailableReason == null
}

internal fun EnterpriseMcpResource.toolAccess(
    tools: List<McpCatalogTool>,
    binding: PlatformAssistantMcpBinding? = null,
): List<McpToolAccess> {
    val discovered = tools.associateBy { it.name }
    val grants = allowedTools.associateBy { it.name }
    val names = discovered.keys + grants.keys + binding?.toolNames.orEmpty()
    return names.map { name ->
        val tool = discovered[name]
        val grant = grants[name]
        val reason = when {
            binding?.toolSelection == PlatformAssistantMcpBindingToolSelection.ALLOWLIST && name !in binding.toolNames ->
                McpToolUnavailableReason.NOT_SELECTED
            toolAccessMode == PlatformMcpDefinitionToolAccessMode.ALLOWLIST && grant == null -> McpToolUnavailableReason.NOT_ALLOWED
            tool == null -> McpToolUnavailableReason.MISSING
            toolAccessMode == PlatformMcpDefinitionToolAccessMode.ALLOWLIST && grant != null && !tool.matchesContractHash(grant.contractHash) ->
                McpToolUnavailableReason.CONTRACT_CHANGED
            else -> null
        }
        McpToolAccess(name, tool,
            grant?.approvalPolicy == PlatformMcpToolGrantApprovalPolicy.REQUIRE_CONFIRMATION,
            grant?.contractHash, reason)
    }
}

/** A definition outside JCS cannot prove a grant; unrelated tools and ALL remain usable. */
private fun McpCatalogTool.matchesContractHash(expected: String): Boolean = try {
    contractHash() == expected
} catch (_: IllegalArgumentException) {
    false
}

internal fun McpConnectionDefinition.toolAccess(tools: List<McpCatalogTool>): List<McpToolAccess> =
    if (this is McpConnectionDefinition.ManagedPlatform) toolAccess.toolAccess(tools, assistantBinding)
    else tools.map { tool ->
        val policy = toolPolicy(tool.name)
        McpToolAccess(tool.name, tool, policy?.needsApproval == true, null,
            if (policy?.enable == false) McpToolUnavailableReason.NOT_ALLOWED else null)
    }
