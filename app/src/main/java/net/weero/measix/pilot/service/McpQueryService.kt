package net.weero.measix.pilot.service

import net.weero.measix.pilot.utils.logDiagnosticFailure

import me.rerere.common.configuration.ConfigurationReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonObject
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.ai.mcp.McpRuntimeCoordinator
import net.weero.measix.pilot.data.ai.mcp.McpRuntimeCapability
import net.weero.measix.pilot.data.ai.mcp.McpCatalogCapability
import net.weero.measix.pilot.data.ai.mcp.McpServerConfig
import net.weero.measix.pilot.data.ai.mcp.McpStatus
import net.weero.measix.pilot.data.ai.mcp.McpToolUnavailableReason
import net.weero.measix.pilot.data.ai.mcp.toolAccess
import net.weero.measix.pilot.data.enterprise.PlatformMcpDefinitionToolAccessMode
import net.weero.measix.pilot.data.ai.mcp.mcpDefinitionDigest
import net.weero.measix.pilot.data.ai.mcp.toolPolicyByName
import net.weero.measix.pilot.data.datastore.SettingsStore

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
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
    val inputSchema: JsonObject?,
    val enabled: Boolean,
    val needsApproval: Boolean,
    val unavailableReason: McpToolUnavailableReason? = null,
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
    val sessionCallable: Boolean,
    val tools: List<McpToolPresentation>,
    val allowsAllTools: Boolean? = null,
    val directoryConfirmed: Boolean = false,
    val connectionsPartiallyReady: Boolean = false,
    val connectionDiagnostic: String? = null,
) {
    val scope: ConfigurationScope get() = access.scope
    val hasCatalogTools: Boolean get() = tools.any { it.inputSchema != null }
    val isCallable: Boolean get() = enabled && unavailableReason == null && sessionCallable && tools.any { it.enabled }
    val isBusy: Boolean
        get() = !directoryConfirmed && !hasCatalogTools && (status == McpStatus.Connecting || status == McpStatus.Discovering)
}

internal sealed interface McpCatalogReadState {
    data class Available(val servers: List<McpServerPresentation>) : McpCatalogReadState
    data object Unavailable : McpCatalogReadState
    data class Failed(val error: Throwable) : McpCatalogReadState
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

    private val catalogReadRevision = MutableStateFlow(0L)

    val catalog: StateFlow<McpCatalogUiModel?> = flow {
        configurationQueries.requireAccess(RealmAccess.Personal)
        emitAll(combine(sessions.observeSelectedRealmSelection(), catalogReadRevision) { selection, _ -> selection }.flatMapLatest { selection ->
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

    /** Restart only this query; a stale settings page cannot retry under a replacement selection. */
    suspend fun retryCatalog(selection: RealmSelection) {
        sessions.withSelectedRealmSelection(selection) { catalogReadRevision.update { it + 1 } }
    }

    fun observe(access: RealmAccess): Flow<McpCatalogReadState> = combine(
        configurationQueries.observe(access.scope), coordinator.runtimeCapabilities, coordinator.catalogs, sessions.state,
    ) { _, _, _, _ -> Unit }.map {
        try {
            val snapshot = configurationQueries.readExecution(access)
            McpCatalogReadState.Available(snapshot.mcpPresentations(access, coordinator.readCatalogCapabilities(access, snapshot)))
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            mcpReadFailure(error)
        }
    }.catch { emit(mcpReadFailure(it)) }.distinctUntilChanged()

    private fun mcpReadFailure(error: Throwable): McpCatalogReadState {
        if (error is CancellationException) throw error
        if (error is net.weero.measix.pilot.data.enterprise.EnterpriseConfigurationException) return McpCatalogReadState.Unavailable
        logDiagnosticFailure("McpQueryService", "MCP catalog read failed", error)
        return McpCatalogReadState.Failed(error)
    }

    fun observeUserServer(serverId: ConfigurationReference): Flow<McpServerPresentation?> = userServers
        .map { rows -> rows.firstOrNull { it.serverId == serverId } }
        .distinctUntilChanged()
}

internal fun ExecutionConfigurationSnapshot.mcpPresentations(
    access: RealmAccess,
    capabilities: Map<ConfigurationReference, McpCatalogCapability>,
): List<McpServerPresentation> {
    check(configuration.scope == access.scope)
    return configuration.catalog.values.filter {
        it.key.category == ConfigurationCategory.MCP || it.key.category == ConfigurationCategory.GATEWAY
    }.map { resource ->
        val resourceCapability = capabilities[resource.key.reference]
        val connections = resourceCapability?.connections.orEmpty()
        val callable = connections.filter { it.sessionCallable }
        // Display readiness of this resource, never transfer one interaction's admission to another.
        val representative = (callable.ifEmpty { connections }).minWithOrNull(
            compareBy<McpRuntimeCapability> { connectionStatusPriority(it) }
                .thenBy { (it.status as? McpStatus.RetryScheduled)?.retryInMs ?: 0L }
                .thenBy { it.status.toString() },
        )
        val capability = McpRuntimeCapability(representative?.status ?: McpStatus.Idle,
            resourceCapability?.catalog, callable.isNotEmpty())
        val partiallyReady = callable.isNotEmpty() && callable.size < connections.size
        val diagnostic = connections.mapNotNull { (it.status as? McpStatus.Error)?.let { error -> error.detail ?: error.message } }
            .distinct().sorted().takeIf { it.isNotEmpty() }?.joinToString("\n\n")
        val managed = configuration.enterpriseConfiguration?.mcpServers?.find {
            it.id == (resource.key.reference as? ConfigurationReference.Enterprise)?.id
        }
        val user = userSettings.mcpServers.singleOrNull { it.id == resource.key.reference }
        if (user != null) user.toPresentation(capability).copy(
            access = access, unavailableReason = resource.access.unavailableReason,
            connectionsPartiallyReady = partiallyReady, connectionDiagnostic = diagnostic,
        ) else McpServerPresentation(
            serverId = resource.key.reference, name = resource.name,
            enabled = resource.gatewayEnablement?.enabled ?: (resource.access.unavailableReason != ConfigurationUnavailableReason.RESOURCE_DISABLED),
            definition = null, access = access,
            unavailableReason = resource.access.unavailableReason, requiredEnabled = resource.access.requiredEnabled,
            gatewayEnablement = resource.gatewayEnablement, status = capability.status,
            sessionCallable = capability.sessionCallable,
            tools = managed?.toolAccess(capability.catalog?.tools.orEmpty())
                ?.filter { it.unavailableReason != McpToolUnavailableReason.NOT_ALLOWED }
                ?.map { McpToolPresentation(it.name, it.tool?.description, it.tool?.inputSchema,
                    it.enabled, it.needsApproval,
                    if (capability.catalog == null) McpToolUnavailableReason.DIRECTORY_UNAVAILABLE else it.unavailableReason) }
                ?: capability.catalog?.tools.orEmpty().map { McpToolPresentation(it.name, it.description, it.inputSchema, true, false) },
            allowsAllTools = managed?.let { it.toolAccessMode == PlatformMcpDefinitionToolAccessMode.ALL },
            directoryConfirmed = capability.catalog != null,
            connectionsPartiallyReady = partiallyReady, connectionDiagnostic = diagnostic,
        )
    }
}

private fun connectionStatusPriority(connection: McpRuntimeCapability): Int = when (connection.status) {
    is McpStatus.Ready -> if (connection.sessionCallable) 0 else 8
    is McpStatus.CatalogStale -> if (connection.sessionCallable) 1 else 7
    McpStatus.NeedsAuthorization -> 2
    is McpStatus.Error, McpStatus.CatalogRejectedEmpty -> 3
    McpStatus.Authorizing -> 4
    McpStatus.Connecting, McpStatus.Discovering, is McpStatus.Reconnecting -> 5
    is McpStatus.RetryScheduled -> 6
    McpStatus.WaitingNetwork -> 7
    McpStatus.Idle -> 8
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
        sessionCallable = runtime.sessionCallable && activeCatalog != null,
        directoryConfirmed = activeCatalog != null,
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
