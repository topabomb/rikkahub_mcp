package net.weero.measix.pilot.ui.pages.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.ai.mcp.McpManager
import net.weero.measix.pilot.data.ai.mcp.McpServerConfig

class SettingVM(
    private val settingsStore: SettingsStore,
    private val mcpManager: McpManager
) :
    ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings(init = true, providers = emptyList()))

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }

    /**
     * 导入 MCP 服务器配置，按 name 去重。
     * 不重复的直接添加；重复的收集到 conflicts，由 UI 层决定是否覆盖。
     *
     * @return 导入结果（已添加列表 + 冲突列表）
     */
    fun importMcpServers(newConfigs: List<McpServerConfig>): McpImportResult {
        val current = settings.value
        val existingByName = current.mcpServers.associateBy { it.commonOptions.name }

        val toAdd = mutableListOf<McpServerConfig>()
        val conflicts = mutableListOf<Pair<McpServerConfig, McpServerConfig>>()

        newConfigs.forEach { newConfig ->
            val existing = existingByName[newConfig.commonOptions.name]
            if (existing == null) {
                toAdd.add(newConfig)
            } else {
                conflicts.add(newConfig to existing)
            }
        }

        if (toAdd.isNotEmpty()) {
            updateSettings(current.copy(mcpServers = current.mcpServers + toAdd))
        }

        return McpImportResult(added = toAdd, conflicts = conflicts)
    }

    /**
     * 覆盖已存在的 MCP 服务器配置，保留原 id。
     * id 不变但 connectionKey 可能变化，McpManager 会自动检测并重建连接。
     */
    fun confirmOverwriteMcpServers(toOverwrite: List<McpServerConfig>) {
        val current = settings.value
        val updated = current.mcpServers.map { existing ->
            val overwrite = toOverwrite.find { it.commonOptions.name == existing.commonOptions.name }
            if (overwrite != null) {
                overwrite.clone(id = existing.id)
            } else {
                existing
            }
        }
        updateSettings(current.copy(mcpServers = updated))
    }
}

/**
 * MCP 导入结果。
 * @param added 已成功添加的新配置
 * @param conflicts 与现有配置 name 冲突的列表（新配置, 现有配置）
 */
data class McpImportResult(
    val added: List<McpServerConfig>,
    val conflicts: List<Pair<McpServerConfig, McpServerConfig>>,
)
