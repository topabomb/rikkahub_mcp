package net.weero.measix.pilot.service

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.JsonObject
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.ai.mcp.McpCatalogSnapshot
import net.weero.measix.pilot.data.ai.mcp.McpRuntimeCoordinator
import net.weero.measix.pilot.data.ai.mcp.McpRuntimeCapability
import net.weero.measix.pilot.data.ai.mcp.McpServerConfig
import net.weero.measix.pilot.data.ai.mcp.McpStatus
import net.weero.measix.pilot.data.ai.mcp.mcpDefinitionDigest
import net.weero.measix.pilot.data.ai.mcp.toolPolicyByName
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.ManagedConfigurationRecordKind
import net.weero.measix.pilot.data.datastore.ManagedConfigurationState
import net.weero.measix.pilot.data.datastore.SettingsValueSource
import kotlin.uuid.Uuid

data class McpToolPresentation(
    val name: String,
    val description: String?,
    val inputSchema: JsonObject,
    val enabled: Boolean,
    val needsApproval: Boolean,
)

data class McpServerPresentation(
    val serverId: Uuid,
    val name: String,
    val enabled: Boolean,
    val definition: McpServerConfig,
    val configurationSource: McpConfigurationSource = McpConfigurationSource.LOCAL,
    val lockReason: String? = null,
    val showConfigurationSource: Boolean = false,
    val status: McpStatus,
    val tools: List<McpToolPresentation>,
) {
    /** A validated catalog is usable even while the transport is reconnecting or offline. */
    val isReady: Boolean get() = tools.isNotEmpty()
    val isBusy: Boolean
        get() = !isReady && (status == McpStatus.Connecting || status == McpStatus.Discovering)
}

enum class McpConfigurationSource { BUILT_IN, LOCAL, MANAGED }

/** Read-only join of MCP definition, durable catalog and runtime state for every UI consumer. */
class McpQueryService(
    settingsStore: SettingsStore,
    coordinator: McpRuntimeCoordinator,
    scope: AppScope,
) {
    val servers: StateFlow<List<McpServerPresentation>> = combine(
        settingsStore.effectiveSettings,
        coordinator.runtimeCapabilities,
    ) { effective, capabilities ->
        effective.settings.mcpServers.map { server ->
            server.toPresentation(
                runtime = capabilities[server.id] ?: McpRuntimeCapability(McpStatus.Idle, null),
                configurationSource = effective.access
                    .sourceOf(ManagedConfigurationRecordKind.MCP_SERVER, server.id)
                    .toMcpSource(),
                lockReason = effective.access.reasonFor(
                    "records/${ManagedConfigurationRecordKind.MCP_SERVER.settingsPath}/${server.id}"
                ),
                showConfigurationSource = effective.managedState != ManagedConfigurationState.ABSENT,
            )
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun observeServer(serverId: Uuid): Flow<McpServerPresentation?> = servers
        .map { rows -> rows.firstOrNull { it.serverId == serverId } }
        .distinctUntilChanged()
}

internal fun net.weero.measix.pilot.data.ai.mcp.McpServerConfig.toPresentation(
    runtime: McpRuntimeCapability,
    configurationSource: McpConfigurationSource = McpConfigurationSource.LOCAL,
    lockReason: String? = null,
    showConfigurationSource: Boolean = false,
): McpServerPresentation {
    val status = runtime.status
    val catalog = runtime.catalog
    val policies = commonOptions.toolPolicyByName()
    val activeCatalog = catalog?.takeIf {
        it.definitionDigest == mcpDefinitionDigest()
    }
    val presentedStatus = if (
        activeCatalog == null && (status is McpStatus.Ready || status is McpStatus.CatalogStale)
    ) {
        McpStatus.Error("MCP catalog identity is inconsistent; refresh required")
    } else {
        status
    }
    return McpServerPresentation(
        serverId = id,
        name = commonOptions.name,
        enabled = commonOptions.enable,
        definition = this,
        configurationSource = configurationSource,
        lockReason = lockReason,
        showConfigurationSource = showConfigurationSource,
        status = presentedStatus,
        tools = activeCatalog?.tools.orEmpty().map { descriptor ->
            val policy = policies[descriptor.name]
            McpToolPresentation(
                name = descriptor.name,
                description = descriptor.description,
                inputSchema = descriptor.inputSchema,
                enabled = policy?.enable ?: true,
                needsApproval = policy?.needsApproval ?: false,
            )
        },
    )
}

private fun SettingsValueSource.toMcpSource(): McpConfigurationSource = when (this) {
    SettingsValueSource.BUILT_IN -> McpConfigurationSource.BUILT_IN
    SettingsValueSource.LOCAL -> McpConfigurationSource.LOCAL
    SettingsValueSource.MANAGED -> McpConfigurationSource.MANAGED
}
