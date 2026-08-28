package net.weero.measix.pilot.service

import android.content.Context
import net.weero.measix.pilot.data.ai.mcp.McpRefreshReceipt
import net.weero.measix.pilot.data.ai.mcp.McpRuntimeCoordinator
import net.weero.measix.pilot.data.ai.mcp.McpServerConfig
import net.weero.measix.pilot.data.ai.mcp.hasSameOAuthTrustBoundary
import net.weero.measix.pilot.data.ai.mcp.toolPolicyByName
import net.weero.measix.pilot.data.datastore.SettingsStore
import kotlin.uuid.Uuid

/** Typed UI command boundary for MCP configuration and external lifecycle operations. */
class McpApplicationService(
    private val coordinator: McpRuntimeCoordinator,
    private val settingsStore: SettingsStore,
) {
    suspend fun refreshAll(): McpRefreshReceipt = coordinator.refreshAllRegisteredServers()

    suspend fun restart(serverId: Uuid): McpRefreshReceipt {
        val config = requireNotNull(currentConfig(serverId)) { "MCP server not found" }
        return coordinator.restartServer(config.id)
    }

    fun authorize(serverId: Uuid, context: Context) {
        val config = requireNotNull(currentConfig(serverId)) { "MCP server not found" }
        coordinator.startAuthorization(config, context.applicationContext)
    }

    fun cancelAuthorization(serverId: Uuid) {
        val config = currentConfig(serverId) ?: return
        coordinator.cancelAuthorization(config)
    }

    suspend fun clearAuthorization(serverId: Uuid) {
        val config = currentConfig(serverId) ?: return
        coordinator.clearAuthorization(config)
    }

    suspend fun setOAuthClientCredentials(serverId: Uuid, clientId: String, clientSecret: String?) {
        val config = requireNotNull(currentConfig(serverId)) { "MCP server not found" }
        coordinator.setOAuthClientCredentials(config, clientId, clientSecret)
    }

    suspend fun upsert(config: McpServerConfig) {
        val effectiveOthers = settingsStore.effectiveSettings.value.settings.mcpServers
            .filterNot { it.id == config.id }
        coordinator.withConfigurationMutation {
            settingsStore.updateLocal { settings ->
                requireUniqueName(effectiveOthers + settings.mcpServers, config)
                val existing = settings.mcpServers.firstOrNull { it.id == config.id }
                val saved = existing?.let { applyEditorSave(it, config) } ?: config
                settings.copy(
                    mcpServers = if (existing == null) settings.mcpServers + saved else
                        settings.mcpServers.map { if (it.id == config.id) saved else it }
                )
            }
        }
    }

    suspend fun importServers(newConfigs: List<McpServerConfig>): McpImportResult {
        var result = McpImportResult(emptyList(), emptyList())
        val managed = settingsStore.effectiveSettings.value.settings.mcpServers
        coordinator.withConfigurationMutation {
            settingsStore.updateLocal { local ->
                val existingByName = (managed + local.mcpServers)
                    .associateBy { it.commonOptions.name.trim().lowercase() }
                    .toMutableMap()
                val added = mutableListOf<McpServerConfig>()
                val conflicts = mutableListOf<Pair<McpServerConfig, McpServerConfig>>()
                newConfigs.forEach { candidate ->
                    val key = normalizedName(candidate)
                    val existing = existingByName[key]
                    if (existing == null) {
                        added += candidate
                        existingByName[key] = candidate
                    } else {
                        conflicts += candidate to existing
                    }
                }
                result = McpImportResult(added, conflicts)
                local.copy(mcpServers = local.mcpServers + added)
            }
        }
        return result
    }

    suspend fun overwriteByName(configs: List<McpServerConfig>) {
        val imports = configs.associateBy(::normalizedName)
        coordinator.withConfigurationMutation {
            settingsStore.updateLocal { settings ->
                settings.copy(
                    mcpServers = settings.mcpServers.map { existing ->
                        imports[normalizedName(existing)]?.let { imported ->
                            applyEditorSave(existing, imported.clone(id = existing.id))
                        } ?: existing
                    }
                )
            }
        }
    }

    suspend fun delete(serverId: Uuid) {
        coordinator.withConfigurationMutation {
            settingsStore.updateLocal { settings ->
                settings.copy(
                    mcpServers = settings.mcpServers.filterNot { it.id == serverId },
                    assistants = settings.assistants.map { assistant ->
                        assistant.copy(mcpServers = assistant.mcpServers - serverId)
                    },
                )
            }
        }
    }

    private fun currentConfig(serverId: Uuid): McpServerConfig? =
        settingsStore.effectiveSettings.value.settings.mcpServers.firstOrNull { it.id == serverId }

    private fun requireUniqueName(existing: List<McpServerConfig>, candidate: McpServerConfig) {
        require(existing.none { it.id != candidate.id && normalizedName(it) == normalizedName(candidate) }) {
            "MCP server name must be unique"
        }
    }

    private fun applyEditorSave(latest: McpServerConfig, edited: McpServerConfig): McpServerConfig {
        val editedPolicies = edited.commonOptions.toolPolicyByName()
        val policies = latest.commonOptions.toolPolicies.map { current ->
            editedPolicies[current.name]?.let { changed ->
                current.copy(enable = changed.enable, needsApproval = changed.needsApproval)
            } ?: current
        } + edited.commonOptions.toolPolicies.filter { changed ->
            latest.commonOptions.toolPolicies.none { it.name == changed.name }
        }
        return edited.clone(
            commonOptions = edited.commonOptions.copy(
                toolPolicies = policies,
                oauth = latest.commonOptions.oauth.takeIf { latest.hasSameOAuthTrustBoundary(edited) },
            )
        )
    }

    private fun normalizedName(config: McpServerConfig): String =
        config.commonOptions.name.trim().lowercase()
}

data class McpImportResult(
    val added: List<McpServerConfig>,
    val conflicts: List<Pair<McpServerConfig, McpServerConfig>>,
)
