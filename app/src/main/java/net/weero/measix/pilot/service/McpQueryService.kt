package net.weero.measix.pilot.service

import me.rerere.common.configuration.ConfigurationReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.JsonObject
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.ai.mcp.McpRuntimeCoordinator
import net.weero.measix.pilot.data.ai.mcp.McpRuntimeCapability
import net.weero.measix.pilot.data.ai.mcp.McpServerConfig
import net.weero.measix.pilot.data.ai.mcp.McpStatus
import net.weero.measix.pilot.data.ai.mcp.mcpDefinitionDigest
import net.weero.measix.pilot.data.ai.mcp.toolPolicyByName
import net.weero.measix.pilot.data.datastore.SettingsStore

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import net.weero.measix.pilot.data.configuration.ConfigurationCategory
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.configuration.ConfigurationUnavailableReason
import net.weero.measix.pilot.data.configuration.ResolvedGatewayEnablement
import net.weero.measix.pilot.data.datastore.ExecutionConfigurationSnapshot
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionController
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.data.enterprise.RealmSelection

data class McpToolPresentation(
    val name: String,
    val description: String?,
    val inputSchema: JsonObject,
    val enabled: Boolean,
    val needsApproval: Boolean,
)

internal data class McpServerPresentation(
    val serverId: ConfigurationReference,
    val name: String,
    val enabled: Boolean,
    val definition: McpServerConfig?,
    val access: RealmAccess = RealmAccess.Personal,
    val unavailableReason: ConfigurationUnavailableReason? = null,
    val requiredEnabled: Boolean = false,
    val gatewayEnablement: ResolvedGatewayEnablement? = null,
    val status: McpStatus,
    val tools: List<McpToolPresentation>,
) {
    val scope: ConfigurationScope get() = access.scope
    val isReady: Boolean get() = tools.isNotEmpty()
    val isBusy: Boolean
        get() = !isReady && (status == McpStatus.Connecting || status == McpStatus.Discovering)
}

internal sealed interface McpCatalogReadState {
    data class Available(val servers: List<McpServerPresentation>) : McpCatalogReadState
    data object Unavailable : McpCatalogReadState
}

internal data class McpCatalogUiModel(val selection: RealmSelection, val content: McpCatalogReadState) {
    val servers: List<McpServerPresentation> get() = (content as? McpCatalogReadState.Available)?.servers.orEmpty()
}

/** Read-only join of original-realm rules, confirmed catalogs and transient connection state. */
internal class McpQueryService(
    settingsStore: SettingsStore,
    private val coordinator: McpRuntimeCoordinator,
    private val configurationQueries: ConfigurationQueryService,
    private val sessions: EnterpriseSessionController,
    scope: AppScope,
) {
    /** Shared definitions are edited independently of admission to the selected enterprise. */
    val userServers: StateFlow<List<McpServerPresentation>> = flow {
        configurationQueries.requireAccess(RealmAccess.Personal)
        emitAll(combine(settingsStore.userMcpDefinitions, coordinator.runtimeCapabilities) { definitions, capabilities ->
            definitions.map { it.toPresentation(capabilities[net.weero.measix.pilot.data.ai.mcp.McpRuntimeKey(it.id)] ?: McpRuntimeCapability.EMPTY) }
        })
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val catalog: StateFlow<McpCatalogUiModel?> = flow {
        configurationQueries.requireAccess(RealmAccess.Personal)
        emitAll(sessions.observeSelectedRealmSelection().flatMapLatest { selection ->
            if (selection == null) flowOf(null)
            else flow<McpCatalogUiModel?> {
                emit(null)
                emitAll(observe(selection.access).map { rows ->
                    try { sessions.withSelectedRealmSelection(selection) { McpCatalogUiModel(selection, rows) } }
                    catch (error: Exception) {
                        if (error is CancellationException) throw error
                        null
                    }
                })
            }
        })
    }.stateIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0, replayExpirationMillis = 0), null)

    fun observe(access: RealmAccess): Flow<McpCatalogReadState> = combine(
        configurationQueries.observe(access.scope), coordinator.runtimeCapabilities, coordinator.catalogs, sessions.state,
    ) { _, _, _, _ -> Unit }.map {
        try {
            val snapshot = configurationQueries.readExecution(access)
            McpCatalogReadState.Available(snapshot.mcpPresentations(access, coordinator.readCatalogCapabilities(access, snapshot)))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            McpCatalogReadState.Unavailable
        }
    }.distinctUntilChanged()

    fun observeUserServer(serverId: ConfigurationReference): Flow<McpServerPresentation?> = userServers
        .map { rows -> rows.firstOrNull { it.serverId == serverId } }
        .distinctUntilChanged()
}

internal fun ExecutionConfigurationSnapshot.mcpPresentations(
    access: RealmAccess,
    capabilities: Map<ConfigurationReference, McpRuntimeCapability>,
): List<McpServerPresentation> {
    check(configuration.scope == access.scope)
    return configuration.catalog.values.filter {
        it.key.category == ConfigurationCategory.MCP || it.key.category == ConfigurationCategory.GATEWAY
    }.map { resource ->
        val capability = capabilities[resource.key.reference] ?: McpRuntimeCapability.EMPTY
        val user = userSettings.mcpServers.singleOrNull { it.id == resource.key.reference }
        if (user != null) user.toPresentation(capability).copy(
            access = access, unavailableReason = resource.access.unavailableReason,
        ) else McpServerPresentation(
            serverId = resource.key.reference, name = resource.name,
            enabled = resource.gatewayEnablement?.enabled ?: (resource.access.unavailableReason != ConfigurationUnavailableReason.RESOURCE_DISABLED),
            definition = null, access = access,
            unavailableReason = resource.access.unavailableReason, requiredEnabled = resource.access.requiredEnabled,
            gatewayEnablement = resource.gatewayEnablement, status = capability.status,
            tools = capability.catalog?.tools.orEmpty().map { McpToolPresentation(it.name, it.description, it.inputSchema, true, false) },
        )
    }
}

internal fun net.weero.measix.pilot.data.ai.mcp.McpServerConfig.toPresentation(
    runtime: McpRuntimeCapability,
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
