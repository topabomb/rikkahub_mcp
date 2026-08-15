package net.weero.measix.pilot.ui.pages.setting

import net.weero.measix.pilot.data.ai.mcp.McpServerConfig

/**
 * 把 MCP 编辑表单应用到最新记录。
 * 连接字段以来自表单的值为准；工具 schema 与 OAuth 令牌保留最新记录，只覆盖用户改过的 enable/needsApproval。
 */
internal fun applyMcpEditorSave(
    latest: McpServerConfig,
    edited: McpServerConfig,
): McpServerConfig {
    if (latest.id != edited.id) return latest
    val editedFlags = edited.commonOptions.tools.associateBy { it.name }
    val mergedTools = latest.commonOptions.tools.map { latestTool ->
        val editedTool = editedFlags[latestTool.name]
        if (editedTool == null) {
            latestTool
        } else {
            latestTool.copy(
                enable = editedTool.enable,
                needsApproval = editedTool.needsApproval,
            )
        }
    }
    return edited.clone(
        commonOptions = edited.commonOptions.copy(
            tools = mergedTools,
            oauth = latest.commonOptions.oauth,
        ),
    )
}
