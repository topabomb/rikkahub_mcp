package net.weero.measix.pilot.data.ai.mcp

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
import io.ktor.client.plugins.sse.SSE
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
import kotlin.uuid.Uuid
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
 * 每个 server 只有一个 [McpServerRuntime]。本类只汇聚配置、前台、网络和用户命令，
 * 单服务器连接、发现、恢复和调用准入全部由对应 runtime 串行化。
 */
class McpRuntimeCoordinator(
    private val settingsStore: SettingsStore,
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

    private val ktorClient = HttpClient(OkHttp) {
        engine {
            preconfigured = okHttpClient
        }
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
            })
        }
        install(SSE)
    }
    private val protocolClientFactory = McpProtocolClientFactory(
        httpClient = ktorClient,
        transportOverride = transportOverride,
        clientOverride = clientOverride,
    )
    private val toolCallExecutor = McpToolCallExecutor(artifactStore)

    private val runtimeState = McpRuntimeStateStore()
    /** Linearizes local definition/policy commits against irrevocable tool-call commitment. */
    private val configurationInvocationCommitMutex = Mutex()
    /** Bounds connect/discovery and catalog refresh work; tool calls never pass through this gate. */
    private val lifecycleOperationSemaphore = Semaphore(MAX_PARALLEL_LIFECYCLE_OPERATIONS)
    val runtimeCapabilities: StateFlow<Map<Uuid, McpRuntimeCapability>> = runtimeState.capabilities
    private val runtimePolicy = McpServerRuntimePolicy(retryJitter)

    private fun logMcp(serverName: String, message: String) {
        Log.i(TAG, "[$serverName] $message")
        Logging.log("MCP", "[$serverName] $message")
    }

    init {
        val initialDesired = settingsStore.effectiveSettings.value.settings.mcpServers
            .map(::desiredConnection)
            .associateBy(McpDesiredConnection::serverId)
        // 链 1: 有效配置变化 → reconcile（create/update/remove 由 server runtime 重读当前配置）
        appScope.launch {
            var previous = initialDesired
            var bootstrapped = false
            settingsStore.effectiveSettings
                .map { snapshot ->
                    snapshot.settings.mcpServers.map(::desiredConnection)
                }
                .distinctUntilChanged()
                .collect { desired ->
                    val configs = settingsStore.effectiveSettings.value.settings.mcpServers
                    val current = desired.associateBy(McpDesiredConnection::serverId)
                    val removedDefinitionIds = previous.keys - current.keys
                    if (!bootstrapped) {
                        configs.filter { current[it.id]?.enabled == true }
                            .forEach { config -> runtime(config.id).bootstrap(config) }
                        bootstrapped = true
                    }
                    runtimeState.serverIds.filter { current[it]?.enabled != true }
                        .forEach { id -> runtime(id).deactivateIfDisabledOrRemoved() }
                    // Catalog retention follows definition existence, not runtime existence. A
                    // disabled definition has no runtime, so deletion must be derived from the
                    // Settings delta or its durable catalog could become orphaned.
                    removedDefinitionIds.forEach { id -> catalogStore.remove(id) }
                    configs.filter { current[it.id]?.enabled == true }
                        .filter { config -> previous[config.id] != current[config.id] }
                        .forEach { config -> runtime(config.id).reconcile(refreshTools = false) }
                    previous = current
                }
        }

        // Durable LKG is independently owned by McpCatalogStore. Restore it into the runtime's
        // single runtime capability without waiting for a transport connection.
        appScope.launch {
            catalogStore.catalogs.collect { catalogs ->
                val definitions = settingsStore.effectiveSettings.value.settings.mcpServers
                    .associateBy(McpServerConfig::id)
                catalogs.forEach { (serverId, catalog) ->
                    definitions[serverId]
                        ?.takeIf { it.commonOptions.enable && it.commonOptions.name.isNotBlank() }
                        ?.let { definition -> runtime(serverId).hydrateCatalog(definition, catalog) }
                }
            }
        }

        // 链 2: 前台恢复只恢复已激活 runtime；durable enabled 不等于移动端常驻连接。
        foregroundObserver.onForegroundStarted {
            if (!runtimeState.isEmpty) {
                appScope.launch { recoverActivatedConnections(refreshTools = false) }
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

    fun captureTurnCapabilities(assistant: Assistant): TurnMcpCapabilitySnapshot =
        captureTurnCapabilities(assistant, emptySet())

    private fun captureTurnCapabilities(
        assistant: Assistant,
        timedOutServerIds: Set<Uuid>,
    ): TurnMcpCapabilitySnapshot {
        val settings = settingsStore.effectiveSettings.value.settings
        val selected = settings.mcpServers
            .filter { it.commonOptions.enable && it.id in assistant.mcpServers }
        val runtimeViews = runtimeCapabilities.value
        val activeCatalogs = selected.mapNotNull { server ->
            val view = runtimeViews[server.id] ?: McpRuntimeCapability.EMPTY
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
            val runtime = runtimeViews[server.id] ?: McpRuntimeCapability.EMPTY
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
        val settings = settingsStore.effectiveSettings.value.settings
        val selected = settings.mcpServers.filter {
            it.id in assistant.mcpServers && it.commonOptions.enable && it.commonOptions.name.isNotBlank()
        }
        if (selected.isEmpty()) return TurnMcpCapabilitySnapshot.EMPTY
        val selectedIds = selected.mapTo(hashSetOf()) { it.id }
        val missingCatalogIds = selected.filterTo(linkedSetOf()) { config ->
            runtimeCapabilities.value[config.id]?.catalog
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
            runtimeCapabilities.value[id]?.catalog == null
        }
        return captureTurnCapabilities(assistant, timedOut)
    }

    suspend fun callTool(
        serverId: Uuid,
        toolName: String,
        expectedDefinitionDigest: String,
        expectedNeedsApproval: Boolean,
        args: JsonObject,
        onArtifactCreated: (OwnedArtifact) -> Unit,
    ): List<UIMessagePart> {
        val serverRuntime = runtimeState.find(serverId) ?: run {
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
            withTimeout(OAUTH_IO_TIMEOUT_MS) { oauthCoordinator.ensureFreshToken(admission.config) }
        } catch (timeout: TimeoutCancellationException) {
            logMcp(admission.config.commonOptions.name, "Tool '$toolName' rejected before commitment: OAuth refresh timeout")
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
        val preparation = configurationInvocationCommitMutex.withLock {
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
        )
        return when (val outcome = toolCallExecutor.execute(lease, toolName, args, onArtifactCreated)) {
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
        val snapshot = settingsStore.effectiveSettings.value
        val desired = snapshot.settings.mcpServers.filter {
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
        runtimeState.serverIds.filter { it !in desiredIds }.forEach { id ->
            launch { runtime(id).deactivateIfDisabledOrRemoved() }
        }
        desired.forEach { config ->
            launch { runtime(config.id).reconcile(refreshTools) }
        }
    }

    private fun runtime(serverId: Uuid): McpServerRuntime =
        runtimeState.getOrCreate(serverId) {
            McpServerRuntime(
                serverId = serverId,
                settingsStore = settingsStore,
                catalogStore = catalogStore,
                appScope = appScope,
                networkMonitor = networkMonitor,
                stateStore = runtimeState,
                protocolClientFactory = protocolClientFactory,
                oauthCoordinator = oauthCoordinator,
                lifecycleOperationSemaphore = lifecycleOperationSemaphore,
                ioDispatcher = ioDispatcher,
                foregroundState = foregroundState,
                policy = runtimePolicy,
                logger = ::logMcp,
            )
        }

    private fun desiredConnection(config: McpServerConfig) = McpDesiredConnection(
        serverId = config.id,
        enabled = config.commonOptions.enable && config.commonOptions.name.isNotBlank(),
        fingerprint = config.connectionFingerprint(),
    )

    suspend fun restartServer(serverId: Uuid): McpRefreshReceipt {
        val target = runtime(serverId)
        target.reconcile(refreshTools = true, forceReconnect = true)
        return awaitUserOperationReceipt(listOf(serverId))
    }

    /**
     * User interaction waits for accepted runtime work for a bounded time. The operations remain
     * owned by AppScope, so ending the foreground receipt never cancels connection or discovery.
     */
    private suspend fun awaitUserOperationReceipt(
        serverIds: List<Uuid>,
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

    /**
     * Typed application commands use this boundary for definition/policy commits. It creates one
     * total order with irrevocable invocation commitment without holding a runtime mutex across
     * network I/O. The SDK does not expose the exact network-byte send boundary; after commitment,
     * failures are therefore conservatively classified as an unknown remote outcome.
     */
    internal suspend fun withConfigurationMutation(block: suspend () -> Unit) =
        configurationInvocationCommitMutex.withLock { block() }

    fun startAuthorization(config: McpServerConfig, context: Context) {
        runtime(config.id).startAuthorization(context.applicationContext)
    }

    fun cancelAuthorization(config: McpServerConfig) {
        runtime(config.id).cancelAuthorization()
    }

    suspend fun clearAuthorization(config: McpServerConfig) {
        withConfigurationMutation {
            oauthCoordinator.clearAuthorization(config.id)
            runtime(config.id).revokeAuthorization()
        }
    }

    private suspend fun recoverActivatedConnections(refreshTools: Boolean) = coroutineScope {
        runtimeState.activeRuntimes.filter { it.isActivated() }
            .map { serverRuntime -> async { serverRuntime.reconcile(refreshTools) } }
            .forEach { it.await() }
    }

    suspend fun setOAuthClientCredentials(config: McpServerConfig, clientId: String, clientSecret: String?) {
        oauthCoordinator.setClientCredentials(config.id, clientId, clientSecret)
    }

}

/** Settings 观察链只比较连接定义，工具策略变化不会触发所有 server 的网络同步。 */
private data class McpDesiredConnection(
    val serverId: Uuid,
    val enabled: Boolean,
    val fingerprint: McpConnectionFingerprint,
)
