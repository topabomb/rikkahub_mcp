package net.weero.measix.pilot.data.ai.mcp

import net.weero.measix.pilot.data.enterprise.ManagedSnapshotRequired

import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import net.weero.measix.pilot.data.configuration.ConfigurationCategory
import net.weero.measix.pilot.data.configuration.ConfigurationKey
import net.weero.measix.pilot.data.configuration.ResolvedConfiguration
import net.weero.measix.pilot.data.enterprise.EnterpriseBindingLease
import net.weero.measix.pilot.data.enterprise.EnterpriseSessionPhase
import net.weero.measix.pilot.data.enterprise.EnterpriseState
import net.weero.measix.pilot.data.enterprise.RealmAccess
import net.weero.measix.pilot.service.CapturedModelConfiguration
import net.weero.measix.pilot.service.runtime.ConversationRuntime
import kotlin.uuid.Uuid
import me.rerere.common.configuration.ConfigurationReference
import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.ktor.client.HttpClient
import me.rerere.common.android.Logging
import net.weero.measix.pilot.data.ai.RequestLoggingInterceptor
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.OwnedArtifact
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.random.Random

private const val TAG = "McpRuntimeCoordinator"

/** Android 前台信号 adapter，使 [McpRuntimeCoordinator] 的生命周期订阅可在 JVM 测试中替换。 */
fun interface ForegroundObserver {
    fun onForegroundStarted(action: () -> Unit)
}

private val ProcessLifecycleForegroundObserver = ForegroundObserver { action ->
    ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            ProcessForegroundState.value = true
            action()
        }

        override fun onStop(owner: LifecycleOwner) {
            ProcessForegroundState.value = false
        }
    })
}

private val ProcessForegroundState = MutableStateFlow(true)

/**
 * MCP 服务器连接管理器。
 *
 * 每个 [McpRuntimeKey] 只有一个 [McpServerRuntime]。本类只汇聚配置、前台、网络和用户命令，
 * 单服务器连接、发现、恢复和调用准入全部由对应 runtime 串行化。
 */
class McpRuntimeCoordinator internal constructor(
    private val settingsStore: SettingsStore,
    private val sessions: net.weero.measix.pilot.data.enterprise.EnterpriseSessionController,
    private val synchronization: net.weero.measix.pilot.service.EnterpriseSynchronizationService,
    private val localMcp: net.weero.measix.pilot.data.enterprise.LocalEnterpriseMcpService,
    private val catalogStore: McpCatalogStore,
    private val appScope: AppScope,
    private val artifactStore: ArtifactStore,
    private val networkMonitor: NetworkMonitor,
    private val foregroundObserver: ForegroundObserver = ProcessLifecycleForegroundObserver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val transportOverride: ((McpServerConfig) -> AbstractTransport)? = null,
    private val clientOverride: ((McpServerConfig) -> Client)? = null,
    oauthClientOverride: McpOAuthClient? = null,
    oauthCallbackKeepAlive: OAuthCallbackKeepAlive,
    private val foregroundState: StateFlow<Boolean> = ProcessForegroundState,
    private val retryJitter: (Long) -> Long = { upperInclusive ->
        if (upperInclusive <= 0L) 0L else Random.nextLong(upperInclusive + 1L)
    },
) {
    companion object {
        const val MAX_PARALLEL_LIFECYCLE_OPERATIONS = 4
        const val TURN_CAPABILITY_PREPARE_TIMEOUT_MS = 20_000L
        const val USER_OPERATION_RECEIPT_TIMEOUT_MS = 20_000L
        const val OAUTH_IO_TIMEOUT_MS = 15_000L
    }

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(120, TimeUnit.SECONDS)
        .followSslRedirects(true)
        .followRedirects(true)
        .addNetworkInterceptor(RequestLoggingInterceptor())
        .build()

    private val oauthClient = oauthClientOverride ?: McpOAuthClient(okHttpClient)
    private val oauthCoordinator = McpOAuthCoordinator(
        settingsStore = settingsStore,
        appScope = appScope,
        oauthClient = oauthClient,
        oauthCallbackKeepAlive = oauthCallbackKeepAlive,
        ioDispatcher = ioDispatcher,
        logger = ::logMcp,
    )

    private val protocolClientFactory = McpProtocolClientFactory(
        createHttpClient = {
            HttpClient(OkHttp) {
                engine {
                    preconfigured = okHttpClient
                }
                install(ContentNegotiation) {
                    json(Json {
                        prettyPrint = true
                        isLenient = true
                    })
                }
            }
        },
        createManagedHttpClient = {
            HttpClient(OkHttp) {
                followRedirects = false
                engine {
                    preconfigured = okHttpClient.newBuilder()
                        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                        .addInterceptor { chain ->
                            chain.proceed(chain.request().newBuilder()
                                .tag(me.rerere.common.http.PrivateRequest::class.java, me.rerere.common.http.PrivateRequest).build())
                        }.build()
                }
            }
        },
        createLocalHttpClient = localMcp::createClient,
        transportOverride = transportOverride,
        clientOverride = clientOverride,
    )
    private val toolCallExecutor = McpToolCallExecutor(artifactStore)

    private val runtimeState = McpRuntimeStateStore()
    /** Bounds connect/discovery and catalog refresh work; tool calls never pass through this gate. */
    private val lifecycleOperationSemaphore = Semaphore(MAX_PARALLEL_LIFECYCLE_OPERATIONS)
    val runtimeCapabilities: StateFlow<Map<McpRuntimeKey, McpRuntimeCapability>> = runtimeState.capabilities
    private val runtimePolicy = McpServerRuntimePolicy(retryJitter)

    private fun logMcp(serverName: String, message: String) {
        Log.i(TAG, "[$serverName] $message")
        Logging.log("MCP", "[$serverName] $message")
    }

    init {
        appScope.launch {
            var previous: Map<ConfigurationReference, McpDesiredConnection>? = null
            settingsStore.userMcpDefinitions.collect { configs ->
                val current = configs.map(::desiredConnection).associateBy(McpDesiredConnection::serverId)
                if (previous == null) {
                    configs.filter { current[it.id]?.enabled == true }
                        .forEach { config -> runtime(config.id).bootstrap() }
                }
                runtimeState.keys.filter { it.access == null }.map { it.serverId }.filter { current[it]?.enabled != true }
                    .forEach { id -> runtime(id).deactivateIfDisabledOrRemoved() }
                // Disabled definitions may have no runtime; durable retention follows the definition.
                (previous.orEmpty().keys - current.keys).forEach { id ->
                    settingsStore.withUserMcpDefinitions { latest ->
                        if (latest.none { it.id == id }) {
                            catalogStore.remove(McpCatalogKey(net.weero.measix.pilot.data.configuration.ConfigurationScope.Personal, id))
                        }
                    }
                }
                configs.filter { current[it.id]?.enabled == true }
                    .filter { config -> previous != null && previous?.get(config.id) != current[config.id] }
                    .forEach { config -> runtime(config.id).reconcile(refreshTools = false) }
                previous = current
            }
        }

        // Durable LKG is independently owned by McpCatalogStore. Restore it into the runtime's
        // single runtime capability without waiting for a transport connection.
        appScope.launch {
            catalogStore.catalogs.collect { catalogs ->
                val definitions = settingsStore.userMcpDefinitions.first()
                    .associateBy(McpServerConfig::id)
                catalogs.filterKeys { it.scope == net.weero.measix.pilot.data.configuration.ConfigurationScope.Personal }
                    .forEach { (key, catalog) ->
                    val serverId = key.serverId
                    definitions[serverId]
                        ?.takeIf { it.commonOptions.enable && it.commonOptions.name.isNotBlank() }
                        ?.let { runtime(serverId).hydrateCatalog(catalog) }
                }
            }
        }

        // 链 2: 前台恢复只恢复已激活 runtime；durable enabled 不等于移动端常驻连接。
        appScope.launch {
            foregroundObserver.onForegroundStarted {
                if (!runtimeState.isEmpty) {
                    appScope.launch { recoverActivatedConnections(refreshTools = false) }
                }
            }
        }

        // 链 3: validated default network 恢复只唤醒已激活 runtime。
        appScope.launch {
            networkMonitor.isOnline
                .drop(1)
                .filter { it }
                .collect { recoverActivatedConnections(refreshTools = false) }
        }
    }

    internal val catalogs: StateFlow<Map<McpCatalogKey, McpCatalogSnapshot>> get() = catalogStore.catalogs

    /** Passive readers share catalog validation; they neither connect nor retain an execution lease. */
    internal suspend fun readCatalogCapabilities(
        access: RealmAccess,
        snapshot: net.weero.measix.pilot.data.datastore.ExecutionConfigurationSnapshot,
    ): Map<ConfigurationReference, McpRuntimeCapability> {
        check(snapshot.configuration.scope == access.scope)
        catalogStore.awaitReady()
        return when (access) {
            RealmAccess.Personal -> projectCatalogCapabilities(access, snapshot, emptyMap())
            is RealmAccess.Enterprise -> sessions.readBindings(access) { version, bindings ->
                check(version.generation == snapshot.configuration.enterpriseConfiguration?.generation) {
                    "enterprise_configuration_changed_during_inspection"
                }
                projectCatalogCapabilities(access, snapshot, bindings.associateBy { it.resourceId })
            }
        }
    }

    private fun projectCatalogCapabilities(
        access: RealmAccess,
        snapshot: net.weero.measix.pilot.data.datastore.ExecutionConfigurationSnapshot,
        bindings: Map<String, net.weero.measix.pilot.data.enterprise.EnterpriseRuntimeBinding>,
    ): Map<ConfigurationReference, McpRuntimeCapability> {
        val configuration = snapshot.configuration
        return configuration.catalog.values.filter {
            it.key.category == ConfigurationCategory.MCP || it.key.category == ConfigurationCategory.GATEWAY
        }.associate { resource ->
            val id = resource.key.reference
            val user = snapshot.userSettings.mcpServers.find { it.id == id }
            val gateway = configuration.enterpriseConfiguration?.gateways?.find { it.id == (id as? ConfigurationReference.Enterprise)?.id }
            val key = McpCatalogKey(if (id is ConfigurationReference.User) net.weero.measix.pilot.data.configuration.ConfigurationScope.Personal else configuration.scope, id)
            val catalog = catalogs.value[key]?.takeIf {
                when (id) {
                    is ConfigurationReference.User -> user != null && it.definitionDigest == user.mcpDefinitionDigest()
                    is ConfigurationReference.Enterprise -> bindings[id.id]?.let { binding ->
                        it.managed == McpManagedCatalog(requireNotNull(configuration.enterpriseConfiguration).generation, gateway?.surface) &&
                            it.definitionDigest == managedMcpDefinitionDigest(id, resource.name, binding, configuration.enterpriseConfiguration.generation)
                    } == true
                }
            }
            val connections = runtimeCapabilities.value.filter { (key, value) ->
                key.serverId == id && key.access == (access as? RealmAccess.Enterprise) &&
                    (value.catalog == null || value.catalog.definitionDigest == catalog?.definitionDigest)
            }.values
            // A resource may serve several interactions; no individual connection represents them all.
            id to McpRuntimeCapability(connections.singleOrNull()?.status ?: McpStatus.Idle, catalog)
        }
    }

    internal suspend fun inspectCapabilities(
        access: RealmAccess,
        snapshot: net.weero.measix.pilot.data.datastore.ExecutionConfigurationSnapshot,
        assistant: Assistant,
    ): TurnMcpCapabilitySnapshot = inspectCatalogCapabilities(snapshot, assistant, readCatalogCapabilities(access, snapshot))

    private fun inspectCatalogCapabilities(
        snapshot: net.weero.measix.pilot.data.datastore.ExecutionConfigurationSnapshot,
        assistant: Assistant,
        capabilities: Map<ConfigurationReference, McpRuntimeCapability>,
    ): TurnMcpCapabilitySnapshot {
        val configuration = snapshot.configuration
        check(configuration.assistants[assistant.id] == assistant &&
            configuration.access(ConfigurationCategory.ASSISTANT, assistant.id).canExecute)
        val selected = assistant.mcpServers.filter {
            configuration.access(ConfigurationCategory.MCP, it).canExecute
        } + configuration.catalog.values.filter {
            it.key.category == ConfigurationCategory.GATEWAY && it.access.canExecute
        }.map { it.key.reference }
        val tools = mutableListOf<McpAvailableTool>()
        val outcomes = selected.distinct().map { id ->
            val user = snapshot.userSettings.mcpServers.find { it.id == id }
            val gateway = configuration.enterpriseConfiguration?.gateways?.find { it.id == (id as? ConfigurationReference.Enterprise)?.id }
            val category = if (gateway != null) ConfigurationCategory.GATEWAY else ConfigurationCategory.MCP
            val name = configuration.catalog.getValue(net.weero.measix.pilot.data.configuration.ConfigurationKey(category, id)).name
            val catalog = capabilities[id]?.catalog
            val policies = user?.commonOptions?.toolPolicyByName().orEmpty()
            val enabled = catalog?.tools.orEmpty().filter { policies[it.name]?.enable != false }
            enabled.forEach { tool ->
                tools += McpAvailableTool(
                    serverId = id, serverName = name,
                    namespace = if (id is ConfigurationReference.Enterprise) managedMcpNamespace(id) else name,
                    catalogRevision = requireNotNull(catalog).revision, definitionDigest = catalog.definitionDigest,
                    catalogDigest = catalog.catalogDigest, name = tool.name, description = tool.description,
                    inputSchema = tool.inputSchema, needsApproval = policies[tool.name]?.needsApproval ?: false,
                )
            }
            McpServerCapabilityOutcome(id, name,
                if (catalog != null) McpServerCapabilityState.READY else McpServerCapabilityState.UNAVAILABLE, enabled.size)
        }
        return TurnMcpCapabilitySnapshot(tools, outcomes)
    }

    suspend fun captureTurnCapabilities(assistant: Assistant): TurnMcpCapabilitySnapshot =
        captureTurnCapabilities(assistant, emptySet())

    private suspend fun captureTurnCapabilities(
        assistant: Assistant,
        timedOutServerIds: Set<ConfigurationReference>,
    ): TurnMcpCapabilitySnapshot {
        val selected = settingsStore.userMcpDefinitions.first()
            .filter { it.commonOptions.enable && it.id in assistant.mcpServers }
        val runtimeViews = runtimeCapabilities.value
        val activeCatalogs = selected.mapNotNull { server ->
            val view = runtimeViews[McpRuntimeKey(server.id)] ?: McpRuntimeCapability.EMPTY
            val catalog = view.catalog
                ?.takeIf { it.definitionDigest == server.mcpDefinitionDigest() }
                ?: return@mapNotNull null
            server.id to catalog
        }.toMap()
        val tools = selected.flatMap { server ->
            val catalog = activeCatalogs[server.id] ?: return@flatMap emptyList()
            val policies = server.commonOptions.toolPolicyByName()
            catalog.tools.mapNotNull { tool ->
                val policy = policies[tool.name]
                if (policy?.enable == false) return@mapNotNull null
                McpAvailableTool(
                    serverId = server.id,
                    serverName = server.commonOptions.name,
                    catalogRevision = catalog.revision,
                    definitionDigest = catalog.definitionDigest,
                    catalogDigest = catalog.catalogDigest,
                    name = tool.name,
                    description = tool.description,
                    inputSchema = tool.inputSchema,
                    needsApproval = policy?.needsApproval ?: false,
                )
            }
        }
        val toolsByServer = tools.groupBy { it.serverId }
        val outcomes = selected.map { server ->
            val runtime = runtimeViews[McpRuntimeKey(server.id)] ?: McpRuntimeCapability.EMPTY
            val status = runtime.status
            val state = when {
                server.id in activeCatalogs -> McpServerCapabilityState.READY
                server.id in timedOutServerIds -> McpServerCapabilityState.TIMEOUT
                status is McpStatus.NeedsAuthorization -> McpServerCapabilityState.AUTHORIZATION_REQUIRED
                status is McpStatus.CatalogRejectedEmpty -> McpServerCapabilityState.EMPTY_CATALOG
                else -> McpServerCapabilityState.UNAVAILABLE
            }
            McpServerCapabilityOutcome(
                serverId = server.id,
                serverName = server.commonOptions.name,
                state = state,
                toolCount = toolsByServer[server.id].orEmpty().size,
            )
        }
        return TurnMcpCapabilitySnapshot(tools, outcomes)
    }

    /**
     * Run-start preflight for the Assistant's selected MCP servers only. Selected servers connect
     * concurrently and the whole preparation has one mobile-safe deadline; unrelated configured
     * servers never delay this turn. The returned catalog is then immutable for the run.
     */
    suspend fun prepareTurnCapabilities(assistant: Assistant): TurnMcpCapabilitySnapshot {
        val selected = settingsStore.userMcpDefinitions.first().filter {
            it.id in assistant.mcpServers && it.commonOptions.enable && it.commonOptions.name.isNotBlank()
        }
        if (selected.isEmpty()) return TurnMcpCapabilitySnapshot.EMPTY
        val selectedIds = selected.mapTo(hashSetOf()) { it.id }
        val missingCatalogIds = selected.filterTo(linkedSetOf()) { config ->
            runtimeCapabilities.value[McpRuntimeKey(config.id)]?.catalog
                ?.definitionDigest != config.mcpDefinitionDigest()
        }.mapTo(hashSetOf()) { it.id }
        selected.forEach { config -> runtime(config.id).reconcile(refreshTools = false) }
        val settled = missingCatalogIds.isEmpty() || withTimeoutOrNull(TURN_CAPABILITY_PREPARE_TIMEOUT_MS) {
            coroutineScope {
                selected.filter { it.id in missingCatalogIds }
                    .map { config -> async { runtime(config.id).awaitCurrentOperations() } }
                    .forEach { it.await() }
            }
            true
        } ?: false
        val timedOut = if (settled) emptySet() else selectedIds.filterTo(hashSetOf()) { id ->
            runtimeCapabilities.value[McpRuntimeKey(id)]?.catalog == null
        }
        return captureTurnCapabilities(assistant, timedOut)
    }

    /** The accepting Turn owns the lease before binding capture, discovery, or any other suspension. */
    internal suspend fun prepareTurnCapabilities(
        access: RealmAccess,
        captured: CapturedModelConfiguration,
        owner: ConversationRuntime,
        turnId: Uuid,
        worker: Job,
        stopInteraction: suspend () -> Unit,
    ): TurnMcpCapabilitySnapshot {
        check(captured.configuration.scope == access.scope && owner.durable.header.scope == access.scope)
        if (access == RealmAccess.Personal) return prepareTurnCapabilities(captured.assistant)
        access as RealmAccess.Enterprise
        var bindings: EnterpriseBindingLease? = null
        val owned = mutableListOf<McpServerRuntime>()
        val lease = McpExecutionLease {
            owned.forEach { it.closeAndAwait() }
            bindings?.release()
        }
        owner.bindMcpExecution(turnId, worker, captured.assistant.id, lease)
        worker.ensureActive()
        bindings = sessions.captureBindings(access)
        val original = requireNotNull(bindings)
        check(original.version == captured.model.enterpriseVersion) { "enterprise_configuration_changed_during_capture" }
        val interactionId = "int_$turnId"
        captured.assistant.mcpServers.forEach { reference ->
            val permission = captured.configuration.access(ConfigurationCategory.MCP, reference)
            check(permission.canExecute) { "mcp_reference_unavailable:$reference:${permission.unavailableReason}" }
        }
        val definitions = connectionDefinitions(access, captured.configuration, captured.userSettings, captured.assistant, original, interactionId)
        val targets = definitions.map { definition ->
            val key = McpRuntimeKey(definition.id, access, interactionId)
            val source = object : McpRuntimeDefinition {
                override fun requireAuthority() = sessions.requirePublishedRealmAccess(access)

                override suspend fun <T> withCurrent(use: McpDefinitionUse, operation: suspend (McpConnectionDefinition?) -> T): T =
                    sessions.withAppliedConfiguration(access) { state ->
                        check(state.manifest.phase == EnterpriseSessionPhase.READY) { "enterprise_session_not_ready" }
                        settingsStore.withExecutionConfiguration(access.scope, state) { latest ->
                            requireAuthority()
                            lease.requireOpen()
                            val configuration = latest.configuration
                            val assistant = configuration.assistants[captured.assistant.id]
                            val gateway = definition.managed?.gatewaySurface != null
                            val allowed = assistant != null && configuration.access(ConfigurationCategory.ASSISTANT, assistant.id).canExecute &&
                                if (gateway) configuration.enterpriseConfiguration?.gateways?.any { it.id == (definition.id as ConfigurationReference.Enterprise).id } == true
                                else definition.id in assistant.mcpServers && configuration.access(ConfigurationCategory.MCP, definition.id).canExecute
                            val current = when {
                                !allowed -> null
                                use == McpDefinitionUse.CATALOG_PUBLICATION && state.manifest.applied != original.version -> null
                                definition is McpConnectionDefinition.Managed -> {
                                    original.binding(definition.id.id)
                                    definition
                                }
                                else -> latest.userSettings.mcpServers.find { it.id == definition.id }
                                    ?.let { McpConnectionDefinition.User(it) }
                            }
                            operation(current)
                        }
                    }
            }
            source.withCurrent(McpDefinitionUse.EXECUTION) { current ->
                worker.ensureActive()
                check(current != null) { "mcp_configuration_changed_during_capture" }
                runtime(key, source) {
                    owner.requestCancel(turnId, "managed_snapshot_required")
                    appScope.launch {
                        try {
                            stopInteraction()
                            synchronization.synchronize(access)
                        }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { logMcp(definition.name, "Enterprise generation barrier cleanup or synchronization failed") }
                    }
                }.also(owned::add)
            }
        }
        definitions.zip(targets).forEach { (definition, runtime) ->
            catalogStore.catalogs.value[definition.catalogKey]?.let { runtime.hydrateCatalog(it) }
            runtime.reconcile(refreshTools = false)
        }
        val settled = withTimeoutOrNull(TURN_CAPABILITY_PREPARE_TIMEOUT_MS) {
            coroutineScope { targets.map { async { it.awaitCurrentOperations() } }.forEach { it.await() } }
            true
        } == true
        val result = captureCapabilities(definitions, access, interactionId, timedOut = !settled)
        val requiredGateways = captured.configuration.catalog.values.filter {
            it.key.category == ConfigurationCategory.GATEWAY && it.access.requiredEnabled
        }.mapTo(hashSetOf()) { it.key.reference }
        check(result.serverOutcomes.none { it.serverId in requiredGateways && it.state != McpServerCapabilityState.READY }) {
            "required_gateway_unavailable"
        }
        return result
    }

    private fun connectionDefinitions(
        access: RealmAccess.Enterprise,
        configuration: ResolvedConfiguration,
        settings: net.weero.measix.pilot.data.datastore.Settings,
        assistant: Assistant,
        bindings: EnterpriseBindingLease,
        interactionId: String,
    ): List<McpConnectionDefinition> = buildList {
        val enterprise = requireNotNull(configuration.enterpriseConfiguration)
        assistant.mcpServers.forEach { id ->
            when (id) {
                is ConfigurationReference.User -> settings.mcpServers.find { it.id == id }?.let { add(McpConnectionDefinition.User(it)) }
                is ConfigurationReference.Enterprise -> enterprise.mcpServers.find { it.id == id.id }?.let { definition ->
                    add(McpConnectionDefinition.Managed(access, id, definition.name, bindings.binding(id.id), bindings.version, interactionId, null))
                }
            }
        }
        configuration.catalog.values.filter { it.key.category == ConfigurationCategory.GATEWAY && it.access.canExecute }.forEach { item ->
            val id = item.key.reference as ConfigurationReference.Enterprise
            val gateway = enterprise.gateways.single { it.id == id.id }
            add(McpConnectionDefinition.Managed(access, id, gateway.name, bindings.binding(id.id), bindings.version, interactionId, gateway.surface))
        }
    }

    private fun captureCapabilities(
        definitions: List<McpConnectionDefinition>,
        access: RealmAccess.Enterprise,
        interactionId: String,
        timedOut: Boolean,
    ): TurnMcpCapabilitySnapshot {
        val tools = mutableListOf<McpAvailableTool>()
        val outcomes = definitions.map { definition ->
            val capability = runtimeCapabilities.value[McpRuntimeKey(definition.id, access, interactionId)] ?: McpRuntimeCapability.EMPTY
            val catalog = capability.catalog?.takeIf { it.definitionDigest == definition.mcpDefinitionDigest() }
            val selected = catalog?.tools.orEmpty().filter { definition.toolPolicy(it.name)?.enable != false }
            selected.forEach { tool ->
                tools += McpAvailableTool(
                    serverId = definition.id, serverName = definition.name, namespace = definition.namespace, interactionId = interactionId,
                    catalogRevision = requireNotNull(catalog).revision, definitionDigest = catalog.definitionDigest, catalogDigest = catalog.catalogDigest,
                    name = tool.name, description = tool.description, inputSchema = tool.inputSchema,
                    needsApproval = definition.toolPolicy(tool.name)?.needsApproval ?: false,
                )
            }
            McpServerCapabilityOutcome(definition.id, definition.name, when {
                catalog != null -> McpServerCapabilityState.READY
                timedOut -> McpServerCapabilityState.TIMEOUT
                capability.status is McpStatus.NeedsAuthorization -> McpServerCapabilityState.AUTHORIZATION_REQUIRED
                capability.status is McpStatus.CatalogRejectedEmpty -> McpServerCapabilityState.EMPTY_CATALOG
                else -> McpServerCapabilityState.UNAVAILABLE
            }, selected.size)
        }
        return TurnMcpCapabilitySnapshot(tools, outcomes)
    }

    /** Exit seals Session admission first, so this sweep cannot race an accepted new connection. */
    internal suspend fun closeRealm(access: RealmAccess.Enterprise) = coroutineScope {
        runtimeState.activeRuntimes.filter { it.key.access == access }
            .map { async { it.closeAndAwait() } }.forEach { it.await() }
    }

    suspend fun callTool(
        realmAccess: net.weero.measix.pilot.data.enterprise.RealmAccess,
        serverId: ConfigurationReference,
        interactionId: String? = null,
        toolName: String,
        expectedDefinitionDigest: String,
        expectedNeedsApproval: Boolean,
        args: JsonObject,
        onResolvedTool: suspend (JsonObject) -> Unit = {},
        onArtifactCreated: (OwnedArtifact) -> Unit,
    ): List<UIMessagePart> {
        val serverRuntime = runtimeState.find(McpRuntimeKey(serverId, realmAccess as? net.weero.measix.pilot.data.enterprise.RealmAccess.Enterprise, interactionId)) ?: run {
            logMcp(serverId.toString(), "Tool '$toolName' rejected before commitment: runtime absent")
            throw McpToolFailureProjector.project(McpToolFailureKind.TOOL_UNAVAILABLE)
        }
        val admission = serverRuntime.admitInvocation(
            toolName = toolName,
            expectedDefinitionDigest = expectedDefinitionDigest,
            expectedNeedsApproval = expectedNeedsApproval,
        )
        if (admission is McpToolCallAdmission.Rejected) {
            logMcp(admission.serverName, "Tool '$toolName' rejected before commitment: ${admission.message}")
            throw McpToolFailureProjector.project(McpToolFailureKind.TOOL_UNAVAILABLE)
        }
        admission as McpToolCallAdmission.Candidate

        // OAuth refresh may perform network and Settings I/O. It must never hold the runtime gate.
        val freshConfig = try {
            withTimeout(OAUTH_IO_TIMEOUT_MS) { serverRuntime.refreshCredentials(admission.config) }
        } catch (timeout: TimeoutCancellationException) {
            logMcp(admission.config.name, "Tool '$toolName' rejected before commitment: OAuth refresh timeout")
            throw McpToolFailureProjector.project(
                kind = McpToolFailureKind.SERVER_UNAVAILABLE,
                cause = timeout,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.e(TAG, "OAuth refresh failed before MCP tool commitment", error)
            val kind = if (McpProtocolFailureClassifier.isUnauthorized(error)) {
                McpToolFailureKind.AUTHORIZATION_REQUIRED
            } else {
                McpToolFailureKind.SERVER_UNAVAILABLE
            }
            throw McpToolFailureProjector.project(
                kind = kind,
                cause = error,
            )
        }
        val preparation = run {
            val preparation = serverRuntime.completeInvocationAdmission(
                freshConfig = freshConfig,
                toolName = toolName,
                expectedDefinitionDigest = expectedDefinitionDigest,
                expectedNeedsApproval = expectedNeedsApproval,
            )
            if (preparation is McpToolCallPreparation.Rejected) {
                logMcp(
                    preparation.serverName,
                    "Tool '$toolName' rejected before commitment: ${preparation.diagnosticMessage}",
                )
                throw McpToolFailureProjector.project(preparation.kind)
            }
            preparation as McpToolCallPreparation.Ready
            // This is the irrevocable local commitment point. Network-byte emission is not
            // observable at this layer, so any later failure is conservatively post-commit and
            // unknown. A configuration command that wins this gate rejects above; one that follows
            // affects only subsequent invocations and may close this call's transport.
            preparation
        }
        val lease = McpInvocationLease(
            client = preparation.client,
            serverName = preparation.serverName,
            generation = preparation.generation,
            managed = admission.config is McpConnectionDefinition.Managed,
        )
        val outcome = try {
            toolCallExecutor.execute(realmAccess.scope, lease, toolName, args, onArtifactCreated) { metadata ->
            if (admission.config.managed?.gatewaySurface != null) onResolvedTool(metadata)
        }
        } catch (barrier: ManagedSnapshotRequired) {
            serverRuntime.acceptManagedBarrier(preparation.generation, barrier)
            throw barrier
        }
        return when (outcome) {
            is McpInvocationOutcome.Succeeded -> outcome.content.also {
                logMcp(preparation.serverName, "Tool '$toolName' succeeded")
            }
            is McpInvocationOutcome.Failed -> {
                when (outcome.kind) {
                    McpInvocationFailureKind.AUTHORIZATION,
                    McpInvocationFailureKind.CONNECTION,
                    -> serverRuntime.recordInvocationFailure(lease, outcome.kind)
                    else -> Unit
                }
                val httpCode = outcome.failure.cause?.let(McpProtocolFailureClassifier::httpCode).orEmpty()
                logMcp(
                    preparation.serverName,
                    "Tool '$toolName' failed after commitment: ${outcome.kind}" +
                        if (httpCode.isEmpty()) "" else " ($httpCode)",
                )
                outcome.failure.cause?.let { Log.e(TAG, "MCP tool call failed after commitment", it) }
                throw outcome.failure
            }
        }
    }

    /** 用户/生命周期触发的唯一同步入口：只调用同一个 reconcile，不建立第二条路径。 */
    suspend fun refreshAllRegisteredServers(): McpRefreshReceipt = withContext(ioDispatcher) {
        val desired = settingsStore.userMcpDefinitions.first().filter {
            it.commonOptions.enable && it.commonOptions.name.isNotBlank()
        }
        reconcile(desired, refreshTools = true)
        awaitUserOperationReceipt(desired.map { it.id })
    }

    private suspend fun reconcile(
        desiredConfigs: List<McpServerConfig>,
        refreshTools: Boolean,
    ) = coroutineScope {
        val desired = desiredConfigs.filter { it.commonOptions.enable && it.commonOptions.name.isNotBlank() }
        val desiredIds = desired.map { it.id }.toSet()
        // remove 分支不信任快照顺序：runtime 在锁内重读当前配置后才拆除，旧的 reconcile
        // 无法拆掉新 revision 刚建立的连接。
        runtimeState.keys.filter { it.access == null }.map { it.serverId }.filter { it !in desiredIds }.forEach { id ->
            launch { runtime(id).deactivateIfDisabledOrRemoved() }
        }
        desired.forEach { config ->
            launch { runtime(config.id).reconcile(refreshTools) }
        }
    }

    private fun runtime(serverId: ConfigurationReference): McpServerRuntime = runtime(
        McpRuntimeKey(serverId), object : McpRuntimeDefinition {
            override suspend fun <T> withCurrent(use: McpDefinitionUse, operation: suspend (McpConnectionDefinition?) -> T): T =
                settingsStore.withUserMcpDefinitions { definitions ->
                    operation(definitions.find { it.id == serverId }?.let { McpConnectionDefinition.User(it) })
                }
        },
    )

    private fun runtime(
        key: McpRuntimeKey,
        source: McpRuntimeDefinition,
        onManagedSnapshotRequired: (ManagedSnapshotRequired) -> Unit = {},
    ): McpServerRuntime =
        runtimeState.getOrCreate(key) {
            McpServerRuntime(
                key = key, definition = source,
                catalogStore = catalogStore, appScope = appScope, networkMonitor = networkMonitor,
                stateStore = runtimeState, protocolClientFactory = protocolClientFactory,
                oauthCoordinator = oauthCoordinator, lifecycleOperationSemaphore = lifecycleOperationSemaphore,
                ioDispatcher = ioDispatcher, foregroundState = foregroundState,
                policy = runtimePolicy, logger = ::logMcp,
                onManagedSnapshotRequired = onManagedSnapshotRequired,
                onClosed = { closed ->
                    if (closed.access == null) appScope.launch {
                        val enabled = settingsStore.userMcpDefinitions.first()
                            .any { it.id == closed.serverId && it.commonOptions.enable && it.commonOptions.name.isNotBlank() }
                        if (enabled) runtime(closed.serverId).reconcile(refreshTools = false)
                    }
                },
            )
        }

    private fun desiredConnection(config: McpServerConfig) = McpDesiredConnection(
        serverId = config.id,
        enabled = config.commonOptions.enable && config.commonOptions.name.isNotBlank(),
        fingerprint = config.connectionFingerprint(),
    )

    suspend fun restartServer(serverId: ConfigurationReference): McpRefreshReceipt {
        val target = runtime(serverId)
        target.reconcile(refreshTools = true, forceReconnect = true)
        return awaitUserOperationReceipt(listOf(serverId))
    }

    /**
     * User interaction waits for accepted runtime work for a bounded time. The operations remain
     * owned by AppScope, so ending the foreground receipt never cancels connection or discovery.
     */
    private suspend fun awaitUserOperationReceipt(
        serverIds: List<ConfigurationReference>,
    ): McpRefreshReceipt = coroutineScope {
        val distinctIds = serverIds.distinct()
        val waiters = distinctIds.map { serverId ->
            async { runtime(serverId).awaitCurrentOperations() }
        }
        val allSettled = withTimeoutOrNull(USER_OPERATION_RECEIPT_TIMEOUT_MS) {
            waiters.forEach { it.await() }
            true
        } == true
        val settledCount = if (allSettled) waiters.size else waiters.count { it.isCompleted }
        waiters.filterNot { it.isCompleted }.forEach { it.cancel() }
        McpRefreshReceipt(
            requestedServerCount = waiters.size,
            settledServerCount = settledCount,
        )
    }

    fun startAuthorization(serverId: ConfigurationReference.User, context: Context) {
        runtime(serverId).startAuthorization(context.applicationContext)
    }

    fun cancelAuthorization(serverId: ConfigurationReference.User) {
        runtime(serverId).cancelAuthorization()
    }

    suspend fun clearAuthorization(serverId: ConfigurationReference.User) {
        oauthCoordinator.clearAuthorization(serverId)
        runtime(serverId).revokeAuthorization()
    }

    private suspend fun recoverActivatedConnections(refreshTools: Boolean) = coroutineScope {
        runtimeState.activeRuntimes.filter { it.isActivated() }
            .map { serverRuntime -> async { serverRuntime.reconcile(refreshTools) } }
            .forEach { it.await() }
    }

    suspend fun setOAuthClientCredentials(serverId: ConfigurationReference.User, clientId: String, clientSecret: String?) {
        oauthCoordinator.setClientCredentials(serverId, clientId, clientSecret)
    }

}

/** Settings 观察链只比较连接定义，工具策略变化不会触发所有 server 的网络同步。 */
private data class McpDesiredConnection(
    val serverId: ConfigurationReference,
    val enabled: Boolean,
    val fingerprint: McpConnectionFingerprint,
)
