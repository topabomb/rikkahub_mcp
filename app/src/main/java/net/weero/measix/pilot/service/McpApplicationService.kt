package net.weero.measix.pilot.service

import me.rerere.common.configuration.ConfigurationReference
import android.content.Context
import net.weero.measix.pilot.data.ai.mcp.McpRefreshReceipt
import net.weero.measix.pilot.data.ai.mcp.McpRuntimeCoordinator
import net.weero.measix.pilot.data.ai.mcp.McpServerConfig
import net.weero.measix.pilot.data.ai.mcp.hasSameOAuthTrustBoundary
import net.weero.measix.pilot.data.ai.mcp.toolPolicyByName
import net.weero.measix.pilot.data.datastore.SettingsStore

/** Typed UI command boundary for MCP configuration and external lifecycle operations. */
class McpApplicationService(
    private val coordinator: McpRuntimeCoordinator,
    private val settingsStore: SettingsStore,
) {
    suspend fun refreshAll(): McpRefreshReceipt = coordinator.refreshAllRegisteredServers()

    suspend fun restart(serverId: ConfigurationReference): McpRefreshReceipt = coordinator.restartServer(userReference(serverId))

    fun authorize(serverId: ConfigurationReference, context: Context) =
        coordinator.startAuthorization(userReference(serverId), context.applicationContext)

    fun cancelAuthorization(serverId: ConfigurationReference) = coordinator.cancelAuthorization(userReference(serverId))

    suspend fun clearAuthorization(serverId: ConfigurationReference) = coordinator.clearAuthorization(userReference(serverId))

    suspend fun setOAuthClientCredentials(serverId: ConfigurationReference, clientId: String, clientSecret: String?) =
        coordinator.setOAuthClientCredentials(userReference(serverId), clientId, clientSecret)

    suspend fun upsert(config: McpServerConfig) {
        userReference(config.id)
        settingsStore.updateLocal { settings ->
            requireUniqueName(settings.mcpServers, config)
            val existing = settings.mcpServers.firstOrNull { it.id == config.id }
            val saved = existing?.let { applyEditorSave(it, config) } ?: config
            settings.copy(
                mcpServers = if (existing == null) settings.mcpServers + saved else
                    settings.mcpServers.map { if (it.id == config.id) saved else it }
            )
        }
    }

    suspend fun importServers(newConfigs: List<McpServerConfig>): McpImportResult {
        newConfigs.forEach { userReference(it.id) }
        var result = McpImportResult(emptyList(), emptyList())
        settingsStore.updateLocal { local ->
            val existingByName = local.mcpServers
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
        return result
    }

    suspend fun overwriteByName(configs: List<McpServerConfig>) {
        configs.forEach { userReference(it.id) }
        val imports = configs.associateBy(::normalizedName)
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

    suspend fun delete(serverId: ConfigurationReference) {
        userReference(serverId)
        settingsStore.updateLocal { settings ->
            settings.copy(
                mcpServers = settings.mcpServers.filterNot { it.id == serverId },
                assistants = settings.assistants.map { assistant ->
                    assistant.copy(mcpServers = assistant.mcpServers - serverId)
                },
            )
        }
    }

    private fun userReference(reference: ConfigurationReference): ConfigurationReference.User =
        requireNotNull(reference as? ConfigurationReference.User) { "enterprise_mcp_definition_is_read_only" }

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
