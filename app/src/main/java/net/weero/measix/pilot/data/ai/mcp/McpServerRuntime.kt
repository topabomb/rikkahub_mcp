package net.weero.measix.pilot.data.ai.mcp

import android.content.Context
import android.util.Log
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.ToolListChangedNotification
import io.modelcontextprotocol.kotlin.sdk.types.Method.Defined.NotificationsToolsListChanged
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.datastore.SettingsStore
import java.util.concurrent.atomic.AtomicLong
import kotlin.uuid.Uuid

private const val TAG = "McpServerRuntime"

internal class McpServerRuntimePolicy(
    private val retryJitter: (upperInclusive: Long) -> Long,
) {
    val maxFastReconnectAttempts: Int = MAX_FAST_RECONNECT_ATTEMPTS
    val maxTotalReconnectAttempts: Int = MAX_TOTAL_RECONNECT_ATTEMPTS
    val connectionOperationTimeoutMs: Long = CONNECTION_OPERATION_TIMEOUT_MS
    val catalogRefreshTimeoutMs: Long = CATALOG_REFRESH_TIMEOUT_MS
    val catalogRefreshDebounceMs: Long = CATALOG_REFRESH_DEBOUNCE_MS
    val clientCloseTimeoutMs: Long = CLIENT_CLOSE_TIMEOUT_MS

    fun reconnectDelay(attempt: Int): Long {
        require(attempt > 0)
        val ceiling = if (attempt <= MAX_FAST_RECONNECT_ATTEMPTS) {
            FAST_RECONNECT_CEILINGS_MS[attempt - 1]
        } else {
            (FIRST_MAINTENANCE_RETRY_DELAY_MS *
                (1L shl (attempt - MAX_FAST_RECONNECT_ATTEMPTS - 1).coerceAtMost(10)))
                .coerceAtMost(MAX_MAINTENANCE_RETRY_DELAY_MS)
        }
        val floor = ceiling / 2L
        return floor + retryJitter(ceiling - floor).coerceIn(0L, ceiling - floor)
    }

    companion object {
        const val MAX_FAST_RECONNECT_ATTEMPTS = 3
        const val MAX_TOTAL_RECONNECT_ATTEMPTS = 8
        const val FIRST_MAINTENANCE_RETRY_DELAY_MS = 30_000L
        const val MAX_MAINTENANCE_RETRY_DELAY_MS = 5 * 60_000L
        const val CONNECTION_OPERATION_TIMEOUT_MS = 30_000L
        const val CATALOG_REFRESH_TIMEOUT_MS = 20_000L
        const val CATALOG_REFRESH_DEBOUNCE_MS = 350L
        const val CLIENT_CLOSE_TIMEOUT_MS = 5_000L

        private val FAST_RECONNECT_CEILINGS_MS = longArrayOf(2_000L, 6_000L, 15_000L)
    }
}

internal sealed interface McpToolCallPreparation {
    data class Ready(
        val client: Client,
        val serverName: String,
        val generation: Long,
    ) : McpToolCallPreparation

    data class Rejected(
        val kind: McpToolFailureKind,
        val serverName: String,
        val diagnosticMessage: String,
    ) : McpToolCallPreparation
}

internal sealed interface McpToolCallAdmission {
    data class Candidate(val config: McpServerConfig) : McpToolCallAdmission
    data class Rejected(val serverName: String, val message: String) : McpToolCallAdmission
}

internal class McpServerRuntime(
    val serverId: Uuid,
    private val settingsStore: SettingsStore,
    private val catalogStore: McpCatalogStore,
    private val appScope: AppScope,
    private val networkMonitor: NetworkMonitor,
    private val stateStore: McpRuntimeStateStore,
    private val protocolClientFactory: McpProtocolClientFactory,
    private val oauthCoordinator: McpOAuthCoordinator,
    private val lifecycleOperationSemaphore: Semaphore,
    private val ioDispatcher: CoroutineDispatcher,
    private val foregroundState: StateFlow<Boolean>,
    private val policy: McpServerRuntimePolicy,
    private val logger: (String, String) -> Unit,
) {
    val mutex = Mutex()
    var client: Client? = null
        private set
    var fingerprint: McpConnectionFingerprint? = null
        private set
    private var reconnectJob: Job? = null
    private var connectionJob: Job? = null
    private var connectionRequestFingerprint: McpConnectionFingerprint? = null
    private var catalogRefreshJob: Job? = null
    private var catalogRefreshPending = false
    private var catalogRefreshPreviousStatus: McpStatus? = null
    private var catalogRefreshPreviousCatalog: McpCatalogSnapshot? = null
    private var authorizationJob: Job? = null
    private var authorizationPreviousStatus: McpStatus? = null
    private var authorizationPreviousCatalog: McpCatalogSnapshot? = null
    private var authorizationOperation = 0L
    @Volatile
    private var activated = false
    private var reconnectAttempt = 0
    private val generation = AtomicLong(0)

    fun currentGeneration(): Long = generation.get()

    private fun capability(): McpRuntimeCapability =
        stateStore.capabilities.value[serverId] ?: McpRuntimeCapability.EMPTY

    private val status: McpStatus get() = capability().status
    private val activeCatalog: McpCatalogSnapshot? get() = capability().catalog

    private fun desiredEnabledConfig(): McpServerConfig? =
        settingsStore.effectiveSettings.value.settings.mcpServers
            .find { it.id == serverId && it.commonOptions.enable && it.commonOptions.name.isNotBlank() }

    fun isActivated(): Boolean = activated

    suspend fun bootstrap(config: McpServerConfig) = withContext(ioDispatcher) {
        mutex.withLock {
            hydrateCatalogLocked(config, catalogStore.catalogs.value[serverId])
            if (activeCatalog == null && status == McpStatus.Idle) {
                setStatusLocked(McpStatus.Idle, null)
            }
        }
    }

    suspend fun reconcile(
        refreshTools: Boolean,
        forceReconnect: Boolean = false,
    ) = withContext(ioDispatcher) {
        mutex.withLock {
            activated = true
            if (!stateStore.isCurrent(this@McpServerRuntime)) return@withLock
            val config = desiredEnabledConfig()
            if (config == null) {
                teardownLocked()
                return@withLock
            }
            hydrateCatalogLocked(config, catalogStore.catalogs.value[serverId])
            // 授权流程进行中不被配置同步打断；需要授权的 server 只有连接参数变化时才重连
            val desiredFingerprint = config.connectionFingerprint()
            if (!forceReconnect && status == McpStatus.Authorizing) return@withLock
            if (!forceReconnect && status == McpStatus.NeedsAuthorization && fingerprint == desiredFingerprint) {
                return@withLock
            }
            // A non-forced lifecycle trigger joins the AppScope-owned connection/discovery
            // already accepted by this runtime. Only an explicit single-server restart replaces it.
            if (
                !forceReconnect &&
                connectionJob?.isActive == true &&
                connectionRequestFingerprint == desiredFingerprint
            ) {
                return@withLock
            }
            val live = client?.takeIf {
                it.transport != null && (status is McpStatus.Ready || status is McpStatus.CatalogStale)
            }
            if (!forceReconnect && live != null && fingerprint == desiredFingerprint) {
                if (refreshTools) requestCatalogRefreshLocked()
                return@withLock
            }
            startConnectionLocked(config, retryAfterFailure = true)
        }
    }

    fun requestReconcile(refreshTools: Boolean, forceReconnect: Boolean = false) {
        appScope.launch { reconcile(refreshTools, forceReconnect) }
    }

    suspend fun deactivateIfDisabledOrRemoved() = withContext(ioDispatcher) {
        mutex.withLock {
            if (!stateStore.isCurrent(this@McpServerRuntime)) return@withLock false
            if (desiredEnabledConfig() == null) {
                teardownLocked()
                true
            } else {
                false
            }
        }
    }

    suspend fun hydrateCatalog(config: McpServerConfig, catalog: McpCatalogSnapshot) =
        withContext(ioDispatcher) {
            mutex.withLock { hydrateCatalogLocked(config, catalog) }
        }

    private fun hydrateCatalogLocked(config: McpServerConfig, catalog: McpCatalogSnapshot?) {
        if (catalog == null || catalog.definitionDigest != config.mcpDefinitionDigest()) return
        val current = activeCatalog
        if (current != null && current.revision >= catalog.revision) return
        val restoredStatus = when (val health = status) {
            McpStatus.Idle -> McpStatus.CatalogStale(
                catalog.tools.size,
                catalog.revision,
                "restored last-known-good catalog; session is not connected",
            )
            else -> health
        }
        setStatusLocked(restoredStatus, catalog)
    }

    suspend fun revokeAuthorization() = withContext(ioDispatcher) {
        mutex.withLock {
            generation.incrementAndGet()
            cancelAllJobsLocked()
            connectionJob?.cancel()
            connectionJob = null
            connectionRequestFingerprint = null
            val detachedClient = client
            client = null
            fingerprint = null
            detachedClient?.let { stale -> appScope.launch(ioDispatcher) { closeClient(stale) } }
            setStatusLocked(McpStatus.NeedsAuthorization)
        }
    }

    /** Waits for the operation accepted before this call; coalesced follow-up refreshes drain too. */
    suspend fun awaitCurrentOperations() {
        while (true) {
            val operation = mutex.withLock {
                connectionJob?.takeIf { it.isActive }
                    ?: catalogRefreshJob?.takeIf { it.isActive }
            } ?: return
            operation.join()
        }
    }

    private fun teardownLocked() {
        val name = settingsStore.effectiveSettings.value.settings.mcpServers
            .find { it.id == serverId }?.commonOptions?.name ?: serverId.toString()
        generation.incrementAndGet()
        cancelAllJobsLocked()
        connectionJob?.cancel()
        connectionJob = null
        connectionRequestFingerprint = null
        val detachedClient = client
        client = null
        fingerprint = null
        reconnectAttempt = 0
        activated = false
        stateStore.remove(this@McpServerRuntime)
        detachedClient?.let { stale -> appScope.launch(ioDispatcher) { closeClient(stale) } }
        logger(name, "Disconnected (removed)")
    }

    /** 锁内只交接 lease；connect、发现和目录落盘均由 AppScope operation 在锁外完成。 */
    fun startConnectionLocked(
        config: McpServerConfig,
        retryAfterFailure: Boolean,
        cancelReconnect: Boolean = true,
    ) {
        if (cancelReconnect) reconnectAttempt = 0
        val assignedGeneration = generation.incrementAndGet()
        if (cancelReconnect) {
            reconnectJob?.cancel()
        }
        reconnectJob = null
        catalogRefreshJob?.cancel()
        catalogRefreshJob = null
        catalogRefreshPending = false
        catalogRefreshPreviousStatus = null
        catalogRefreshPreviousCatalog = null
        connectionJob?.cancel()
        connectionRequestFingerprint = config.connectionFingerprint()
        val detachedClient = client
        client = null
        fingerprint = null
        val retainedCatalog = activeCatalog?.takeIf {
            it.definitionDigest == config.mcpDefinitionDigest()
        }
        if (retainedCatalog == null) setStatusLocked(McpStatus.Idle, null)
        connectionJob = appScope.launch(ioDispatcher) {
            runConnectionOperation(config, assignedGeneration, retryAfterFailure, detachedClient)
        }
    }

    private suspend fun runConnectionOperation(
        requestedConfig: McpServerConfig,
        assignedGeneration: Long,
        retryAfterFailure: Boolean,
        detachedClient: Client?,
    ) {
        var newClient: Client? = null
        try {
            lifecycleOperationSemaphore.withPermit {
                withTimeout(policy.connectionOperationTimeoutMs) {
                    detachedClient?.let { closeClient(it) }
                    val config = oauthCoordinator.ensureFreshToken(requestedConfig)
                    if (!matchesDesiredDefinition(assignedGeneration, config)) {
                        requestReconcile(refreshTools = false)
                        return@withTimeout
                    }
                    val connecting = mutex.withLock {
                        if (!matchesDesiredDefinitionLocked(assignedGeneration, config)) return@withLock false
                        setStatusLocked(McpStatus.Connecting)
                        true
                    }
                    if (!connecting) return@withTimeout
                    val transport = protocolClientFactory.createTransport(config)
                    val createdClient = protocolClientFactory.createClient(config)
                    newClient = createdClient
                    setupNotificationHandlers(createdClient, config, assignedGeneration)
                    transport.onClose {
                        appScope.launch { onTransportClosed(assignedGeneration, createdClient) }
                    }
                    transport.onError { error ->
                        appScope.launch { onTransportError(assignedGeneration, createdClient, error) }
                    }
                    val admitted = mutex.withLock {
                        if (!matchesDesiredDefinitionLocked(assignedGeneration, config)) return@withLock false
                        client = createdClient
                        fingerprint = config.connectionFingerprint()
                        true
                    }
                    if (!admitted) return@withTimeout
                    createdClient.connect(transport)
                    val discovering = mutex.withLock {
                        if (!matchesClientLeaseLocked(assignedGeneration, createdClient, config)) {
                            return@withLock false
                        }
                        setStatusLocked(McpStatus.Discovering)
                        true
                    }
                    if (!discovering) return@withTimeout
                    val candidate = McpCatalogDiscovery.fetchCandidate(config, createdClient)
                    if (!matchesClientLease(assignedGeneration, createdClient, config)) return@withTimeout
                    val catalogResult = catalogStore.commitCandidate(candidate)
                    val activated = mutex.withLock {
                        if (!matchesClientLeaseLocked(assignedGeneration, createdClient, config)) return@withLock false
                        publishCatalogResultLocked(catalogResult)
                        reconnectAttempt = 0
                        logger(
                            config.commonOptions.name,
                            "Discovery completed (${status.catalogCountForLog()} tools available)",
                        )
                        if (catalogRefreshPending) {
                            catalogRefreshPending = false
                            requestCatalogRefreshLocked()
                        }
                        true
                    }
                    if (!activated) rollbackStaleCommit(catalogResult)
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            handleConnectionFailure(requestedConfig, assignedGeneration, retryAfterFailure, newClient, timeout)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            handleConnectionFailure(requestedConfig, assignedGeneration, retryAfterFailure, newClient, error)
        } finally {
            newClient?.let { candidate ->
                val owned = mutex.withLock { client === candidate && generation.get() == assignedGeneration }
                if (!owned) closeClient(candidate)
            }
            mutex.withLock {
                if (connectionJob === kotlinx.coroutines.currentCoroutineContext()[Job]) {
                    connectionJob = null
                    connectionRequestFingerprint = null
                }
            }
        }
    }

    private suspend fun handleConnectionFailure(
        requestedConfig: McpServerConfig,
        assignedGeneration: Long,
        retryAfterFailure: Boolean,
        failedClient: Client?,
        error: Throwable,
    ) {
        val config = desiredEnabledConfig() ?: requestedConfig
        val authorizationRequired = needsAuthorization(config, error)
        mutex.withLock {
            if (generation.get() != assignedGeneration) return@withLock
            if (client === failedClient) {
                client = null
                fingerprint = null
            }
            if (authorizationRequired) {
                setStatusLocked(McpStatus.NeedsAuthorization)
                cancelRecoveryJobsLocked()
            } else if (retryAfterFailure && McpProtocolFailureClassifier.isConnectionError(error)) {
                scheduleReconnectLocked(assignedGeneration)
            } else {
                setStatusLocked(McpStatus.Error.from(error))
            }
        }
        logger(config.commonOptions.name, "Connection failed: ${error.message ?: error::class.simpleName}")
    }

    private fun needsAuthorization(config: McpServerConfig, error: Throwable): Boolean {
        if (!McpProtocolFailureClassifier.isUnauthorized(error)) return false
        // A user-supplied Authorization header is manual authentication; an invalid header is a
        // configuration failure and must not start the OAuth lifecycle.
        return config.commonOptions.headers.none {
            it.first.equals("Authorization", ignoreCase = true)
        }
    }

    private suspend fun rollbackStaleCommit(result: McpCatalogCommitResult) {
        if (result !is McpCatalogCommitResult.Committed) return
        catalogStore.rollbackCommitted(result.snapshot, result.previous, result.headToken)
    }

    private suspend fun matchesDesiredDefinition(
        assignedGeneration: Long,
        config: McpServerConfig,
    ): Boolean = mutex.withLock { matchesDesiredDefinitionLocked(assignedGeneration, config) }

    private fun matchesDesiredDefinitionLocked(
        assignedGeneration: Long,
        config: McpServerConfig,
    ): Boolean = stateStore.isCurrent(this@McpServerRuntime) &&
        generation.get() == assignedGeneration &&
        desiredEnabledConfig()?.connectionFingerprint() == config.connectionFingerprint()

    private suspend fun matchesClientLease(
        assignedGeneration: Long,
        expectedClient: Client,
        config: McpServerConfig,
    ): Boolean = mutex.withLock { matchesClientLeaseLocked(assignedGeneration, expectedClient, config) }

    private fun matchesClientLeaseLocked(
        assignedGeneration: Long,
        expectedClient: Client,
        config: McpServerConfig,
    ): Boolean = matchesDesiredDefinitionLocked(assignedGeneration, config) && client === expectedClient

    /** Runtime serializes admission against definition removal and client hand-off. */
    suspend fun admitInvocation(
        toolName: String,
        expectedDefinitionDigest: String,
        expectedNeedsApproval: Boolean,
    ): McpToolCallAdmission = mutex.withLock {
        val rejection = invocationRejection(
            toolName,
            expectedDefinitionDigest,
            expectedNeedsApproval,
        )
        if (rejection != null) {
            return@withLock McpToolCallAdmission.Rejected(
                desiredEnabledConfig()?.commonOptions?.name ?: serverId.toString(),
                rejection,
            )
        }
        McpToolCallAdmission.Candidate(requireNotNull(desiredEnabledConfig()))
    }

    /** Revalidates every admission condition after credential refresh performed outside this owner. */
    suspend fun completeInvocationAdmission(
        freshConfig: McpServerConfig,
        toolName: String,
        expectedDefinitionDigest: String,
        expectedNeedsApproval: Boolean,
    ): McpToolCallPreparation = mutex.withLock {
        val rejection = invocationRejection(
            toolName,
            expectedDefinitionDigest,
            expectedNeedsApproval,
        )
        val serverName = desiredEnabledConfig()?.commonOptions?.name ?: serverId.toString()
        if (rejection != null) {
            return@withLock McpToolCallPreparation.Rejected(
                kind = McpToolFailureKind.TOOL_UNAVAILABLE,
                serverName = serverName,
                diagnosticMessage = rejection,
            )
        }
        val currentConfig = requireNotNull(desiredEnabledConfig())
        if (currentConfig.connectionFingerprint() != freshConfig.connectionFingerprint()) {
            logger(currentConfig.commonOptions.name, "Connection credentials changed during callTool; scheduling reconnect")
            startConnectionLocked(currentConfig, retryAfterFailure = true)
            return@withLock McpToolCallPreparation.Rejected(
                kind = McpToolFailureKind.SERVER_UNAVAILABLE,
                serverName = currentConfig.commonOptions.name,
                diagnosticMessage = "Session credentials changed; recovery started",
            )
        }
        if (status == McpStatus.NeedsAuthorization) {
            return@withLock McpToolCallPreparation.Rejected(
                kind = McpToolFailureKind.AUTHORIZATION_REQUIRED,
                serverName = currentConfig.commonOptions.name,
                diagnosticMessage = "MCP authorization is required",
            )
        }
        val liveClient = client ?: run {
            if (connectionJob?.isActive != true) {
                if (networkMonitor.isOnline.value) {
                    startConnectionLocked(currentConfig, retryAfterFailure = true)
                } else {
                    scheduleReconnectLocked(currentGeneration())
                }
            }
            return@withLock McpToolCallPreparation.Rejected(
                kind = McpToolFailureKind.SERVER_UNAVAILABLE,
                serverName = currentConfig.commonOptions.name,
                diagnosticMessage = "MCP session is unavailable; recovery is in progress",
            )
        }
        if (fingerprint != currentConfig.connectionFingerprint()) {
            startConnectionLocked(currentConfig, retryAfterFailure = true)
            return@withLock McpToolCallPreparation.Rejected(
                kind = McpToolFailureKind.SERVER_UNAVAILABLE,
                serverName = currentConfig.commonOptions.name,
                diagnosticMessage = "Session credentials changed; recovery started",
            )
        }
        if (liveClient.transport == null) {
            setStatusLocked(McpStatus.Reconnecting(1, policy.maxFastReconnectAttempts))
            scheduleReconnectLocked(currentGeneration())
            return@withLock McpToolCallPreparation.Rejected(
                kind = McpToolFailureKind.SERVER_UNAVAILABLE,
                serverName = currentConfig.commonOptions.name,
                diagnosticMessage = "MCP transport is disconnected; recovery is in progress",
            )
        }
        if (liveClient.serverCapabilities?.tools == null) {
            return@withLock McpToolCallPreparation.Rejected(
                kind = McpToolFailureKind.PROTOCOL_INCOMPATIBLE,
                serverName = currentConfig.commonOptions.name,
                diagnosticMessage = "MCP server does not declare tools capability",
            )
        }
        McpToolCallPreparation.Ready(
            client = liveClient,
            serverName = currentConfig.commonOptions.name,
            generation = currentGeneration(),
        )
    }

    suspend fun recordInvocationFailure(
        lease: McpInvocationLease,
        kind: McpInvocationFailureKind,
    ) = mutex.withLock {
        if (currentGeneration() != lease.generation || client !== lease.client) return@withLock
        when (kind) {
            McpInvocationFailureKind.AUTHORIZATION -> setStatusLocked(McpStatus.NeedsAuthorization)
            McpInvocationFailureKind.CONNECTION -> {
                setStatusLocked(McpStatus.Reconnecting(1, policy.maxFastReconnectAttempts))
                scheduleReconnectLocked(lease.generation)
            }
            else -> Unit
        }
    }

    private fun invocationRejection(
        toolName: String,
        expectedDefinitionDigest: String,
        expectedNeedsApproval: Boolean,
    ): String? {
        val currentConfig = desiredEnabledConfig()
            ?: return "TOOL_REVOKED: MCP tool is no longer available"
        val policy = currentConfig.commonOptions.toolPolicyByName()[toolName]
        val currentNeedsApproval = policy?.needsApproval ?: false
        if (
            currentConfig.mcpDefinitionDigest() != expectedDefinitionDigest ||
            policy?.enable == false ||
            (!expectedNeedsApproval && currentNeedsApproval)
        ) {
            return "TOOL_REVOKED: MCP tool is no longer available"
        }
        return null
    }

    private fun publishCatalogResultLocked(catalogResult: McpCatalogCommitResult) {
        when (catalogResult) {
            is McpCatalogCommitResult.Committed -> {
                setStatusLocked(
                    McpStatus.Ready(catalogResult.snapshot.tools.size, catalogResult.snapshot.revision),
                    catalogResult.snapshot,
                )
            }
            is McpCatalogCommitResult.Unchanged -> {
                setStatusLocked(
                    McpStatus.Ready(catalogResult.snapshot.tools.size, catalogResult.snapshot.revision),
                    catalogResult.snapshot,
                )
            }
            is McpCatalogCommitResult.RejectedEmpty -> {
                setStatusLocked(
                    catalogResult.lastKnownGood?.let { catalog ->
                        McpStatus.CatalogStale(
                        lastKnownGoodCount = catalog.tools.size,
                        catalogRevision = catalog.revision,
                        message = "server returned an empty tools catalog",
                    )
                    } ?: McpStatus.CatalogRejectedEmpty,
                    catalogResult.lastKnownGood,
                )
            }
        }
    }

    private suspend fun closeClient(target: Client) {
        try {
            withTimeout(policy.clientCloseTimeoutMs) { target.close() }
        } catch (_: TimeoutCancellationException) {
            logger(getServerName(), "Client close timed out")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            logger(getServerName(), "Failed to close client: ${error.message}")
        }
    }

    private suspend fun onTransportClosed(
        capturedGeneration: Long,
        capturedClient: Client,
    ) = withContext(ioDispatcher) {
        mutex.withLock {
            if (generation.get() != capturedGeneration) return@withLock
            if (client !== capturedClient) return@withLock
            if (activeCatalog == null) return@withLock
            scheduleReconnectLocked(capturedGeneration)
        }
    }

    private suspend fun onTransportError(
        capturedGeneration: Long,
        capturedClient: Client,
        error: Throwable,
    ) {
        if (McpProtocolFailureClassifier.isSseStreamGiveUp(error)) {
            mutex.withLock {
                if (generation.get() != capturedGeneration || client !== capturedClient) return@withLock
                activeCatalog?.let { catalog ->
                    setStatusLocked(
                        McpStatus.CatalogStale(
                            catalog.tools.size,
                            catalog.revision,
                            "notification stream is unavailable; command transport remains usable",
                        ),
                        catalog,
                    )
                }
            }
            return
        }
        onTransportClosed(capturedGeneration, capturedClient)
    }

    private fun requestCatalogRefreshLocked() {
        if (
            connectionJob?.isActive == true &&
            (status == McpStatus.Connecting || status == McpStatus.Discovering)
        ) {
            catalogRefreshPending = true
            return
        }
        if (catalogRefreshJob?.isActive == true) {
            catalogRefreshPending = true
            return
        }
        val assignedGeneration = generation.get()
        catalogRefreshPreviousStatus = status
        catalogRefreshPreviousCatalog = activeCatalog
        if (activeCatalog == null) setStatusLocked(McpStatus.Discovering)
        catalogRefreshJob = appScope.launch(ioDispatcher) {
            drainCatalogRefreshes(assignedGeneration, getServerName())
        }
    }

    /**
     * A single per-generation recovery loop: fast equal-jitter exponential retry followed by
     * foreground-only maintenance probes capped at five minutes. Offline/background waiting is
     * event driven and does not consume attempts or poll the radio.
     */
    private fun scheduleReconnectLocked(capturedGeneration: Long) {
        if (generation.get() != capturedGeneration) return
        if (reconnectJob?.isActive == true) return
        val attempt = reconnectAttempt + 1
        if (attempt > policy.maxTotalReconnectAttempts) {
            setStatusLocked(
                McpStatus.Error(
                    "Automatic maintenance recovery paused; retry on the next call, network change, foreground entry, or manual refresh"
                )
            )
            reconnectJob = null
            return
        }
        reconnectAttempt = attempt
        val delayMs = policy.reconnectDelay(attempt)
        val maintenance = attempt > policy.maxFastReconnectAttempts
        setStatusLocked(
            if (networkMonitor.isOnline.value) {
                McpStatus.RetryScheduled(attempt, policy.maxFastReconnectAttempts, delayMs, maintenance)
            } else {
                McpStatus.WaitingNetwork
            }
        )
        reconnectJob = appScope.launch {
            try {
                networkMonitor.isOnline.first { it }
                foregroundState.first { it }
                mutex.withLock {
                    if (generation.get() != capturedGeneration) return@launch
                    setStatusLocked(
                        McpStatus.RetryScheduled(
                            attempt,
                            policy.maxFastReconnectAttempts,
                            delayMs,
                            maintenance,
                        )
                    )
                }
                delay(delayMs)
                networkMonitor.isOnline.first { it }
                foregroundState.first { it }
                mutex.withLock {
                    if (generation.get() != capturedGeneration) return@launch
                    val config = desiredEnabledConfig() ?: run {
                        teardownLocked()
                        return@launch
                    }
                    setStatusLocked(
                        McpStatus.Reconnecting(attempt, policy.maxFastReconnectAttempts, maintenance)
                    )
                    reconnectJob = null
                    startConnectionLocked(
                        config = config,
                        retryAfterFailure = true,
                        cancelReconnect = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger(getServerName(), "Reconnect failed: ${e.message}")
            }
        }
    }

    private fun setStatusLocked(
        newStatus: McpStatus,
        catalog: McpCatalogSnapshot? = activeCatalog,
    ) {
        if (!stateStore.isCurrent(this@McpServerRuntime)) return
        // 只发布本 runtime 的键，避免全表重建把其他 server 的更新回退。
        stateStore.publish(this@McpServerRuntime, McpRuntimeCapability(newStatus, catalog))
    }

    private fun cancelRecoveryJobsLocked() {
        reconnectJob?.cancel()
        reconnectJob = null
        catalogRefreshJob?.cancel()
        catalogRefreshJob = null
        catalogRefreshPending = false
        catalogRefreshPreviousStatus = null
        catalogRefreshPreviousCatalog = null
    }

    private fun cancelAllJobsLocked() {
        cancelRecoveryJobsLocked()
        authorizationJob?.cancel()
        authorizationJob = null
    }

    private fun setupNotificationHandlers(client: Client, config: McpServerConfig, assignedGeneration: Long) {
        val configName = config.commonOptions.name
        client.setNotificationHandler<ToolListChangedNotification>(
            NotificationsToolsListChanged
        ) {
            logger(configName, "Received tools/list_changed notification")
            appScope.launch {
                mutex.withLock {
                    if (generation.get() != assignedGeneration || this@McpServerRuntime.client !== client) {
                        return@withLock
                    }
                    requestCatalogRefreshLocked()
                }
            }
            CompletableDeferred(Unit)
        }
    }

    private suspend fun drainCatalogRefreshes(assignedGeneration: Long, configName: String) {
        try {
            while (true) {
                delay(policy.catalogRefreshDebounceMs)
                val lease = mutex.withLock {
                    if (generation.get() != assignedGeneration) return
                    val current = desiredEnabledConfig() ?: return
                    val live = client ?: return
                    if (live.transport == null) {
                        scheduleReconnectLocked(assignedGeneration)
                        return
                    }
                    val previousStatus = catalogRefreshPreviousStatus ?: status
                    val previousCatalog = catalogRefreshPreviousCatalog
                    catalogRefreshPreviousStatus = null
                    catalogRefreshPreviousCatalog = null
                    catalogRefreshPending = false
                    McpCatalogRefreshLease(current, live, previousStatus, previousCatalog)
                }
                try {
                    lifecycleOperationSemaphore.withPermit {
                        withTimeout(policy.catalogRefreshTimeoutMs) {
                            if (!matchesClientLease(assignedGeneration, lease.client, lease.config)) {
                                return@withTimeout
                            }
                            val candidate = McpCatalogDiscovery.fetchCandidate(lease.config, lease.client)
                            if (!matchesClientLease(assignedGeneration, lease.client, lease.config)) {
                                return@withTimeout
                            }
                            val result = catalogStore.commitCandidate(candidate)
                            val activated = mutex.withLock {
                                if (!matchesClientLeaseLocked(assignedGeneration, lease.client, lease.config)) {
                                    return@withLock false
                                }
                                publishCatalogResultLocked(result)
                                true
                            }
                            if (!activated) rollbackStaleCommit(result)
                        }
                    }
                } catch (timeout: TimeoutCancellationException) {
                    mutex.withLock {
                        if (generation.get() != assignedGeneration || client !== lease.client) return@withLock
                        val lastGood = lease.previousCatalog
                        setStatusLocked(
                            lastGood?.let { catalog ->
                                McpStatus.CatalogStale(
                                    catalog.tools.size,
                                    catalog.revision,
                                    "catalog refresh timed out",
                                )
                            } ?: McpStatus.Error("MCP catalog refresh timed out"),
                            lastGood,
                        )
                    }
                } catch (cancelled: CancellationException) {
                    mutex.withLock {
                        if (generation.get() == assignedGeneration && client === lease.client) {
                            setStatusLocked(lease.previousStatus, lease.previousCatalog)
                        }
                    }
                    throw cancelled
                } catch (error: Throwable) {
                    mutex.withLock {
                        if (generation.get() != assignedGeneration || client !== lease.client) return@withLock
                        when {
                            McpProtocolFailureClassifier.isUnauthorized(error) ->
                                setStatusLocked(McpStatus.NeedsAuthorization)
                            McpProtocolFailureClassifier.isConnectionError(error) ->
                                scheduleReconnectLocked(assignedGeneration)
                            else -> {
                                val lastGood = lease.previousCatalog
                                setStatusLocked(
                                    lastGood?.let { catalog ->
                                        McpStatus.CatalogStale(catalog.tools.size, catalog.revision, error.message)
                                    } ?: McpStatus.Error.from(error, "catalog discovery failed"),
                                    lastGood,
                                )
                            }
                        }
                    }
                    logger(configName, "Catalog refresh failed: ${error.message}")
                }
                val repeat = mutex.withLock {
                    if (generation.get() != assignedGeneration || !catalogRefreshPending) {
                        catalogRefreshJob = null
                        false
                    } else {
                        catalogRefreshPending = false
                        catalogRefreshPreviousStatus = status
                        catalogRefreshPreviousCatalog = activeCatalog
                        if (activeCatalog == null) setStatusLocked(McpStatus.Discovering)
                        true
                    }
                }
                if (!repeat) return
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to sync tools after list_changed for $configName", error)
            logger(configName, "catalog refresh after list_changed failed: ${error.message}")
        } finally {
            mutex.withLock {
                if (catalogRefreshJob === kotlinx.coroutines.currentCoroutineContext()[Job]) {
                    catalogRefreshJob = null
                    catalogRefreshPending = false
                    catalogRefreshPreviousStatus = null
                    catalogRefreshPreviousCatalog = null
                }
            }
        }
    }

    fun startAuthorization(context: Context) {
        appScope.launch {
            val replacement = mutex.withLock {
                val previousJob = authorizationJob
                authorizationJob = null
                val operation = ++authorizationOperation
                val previousStatus = authorizationPreviousStatus ?: status
                val previousCatalog = authorizationPreviousCatalog ?: activeCatalog
                authorizationPreviousStatus = previousStatus
                authorizationPreviousCatalog = previousCatalog
                previousJob?.cancel()
                McpAuthorizationReplacement(previousJob, operation, previousStatus, previousCatalog)
            }
            replacement.job?.cancelAndJoin()
            if (mutex.withLock { authorizationOperation != replacement.operation }) return@launch

            try {
                // A replacement is a new credential lease. Seal any intermediate/token write made
                // by the completed predecessor before the next flow captures its starting revision.
                oauthCoordinator.touchState(serverId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                logger(getServerName(), "OAuth replacement could not be sealed: ${error.message}")
                mutex.withLock {
                    if (authorizationOperation == replacement.operation) {
                        setStatusLocked(
                            McpStatus.Error.from(error, "OAuth authorization could not be started"),
                            replacement.previousCatalog,
                        )
                        authorizationPreviousStatus = null
                        authorizationPreviousCatalog = null
                    }
                }
                return@launch
            }

            mutex.withLock {
                if (authorizationOperation != replacement.operation) return@withLock
                val current = desiredEnabledConfig() ?: return@withLock
                setStatusLocked(McpStatus.Authorizing)
                authorizationJob = appScope.launch {
                    try {
                        oauthCoordinator.authorize(current, context)
                        reconcile(refreshTools = true, forceReconnect = true)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        logger(current.commonOptions.name, "OAuth authorization failed: ${error.message}")
                        mutex.withLock {
                            if (authorizationOperation == replacement.operation) {
                                setStatusLocked(McpStatus.NeedsAuthorization)
                            }
                        }
                        // 授权期间被跳过的配置变化（如 URL 修改）在此收敛
                        appScope.launch { reconcile(refreshTools = true) }
                    } finally {
                        mutex.withLock {
                            if (
                                authorizationOperation == replacement.operation &&
                                authorizationJob === kotlinx.coroutines.currentCoroutineContext()[Job]
                            ) {
                                authorizationJob = null
                                authorizationPreviousStatus = null
                                authorizationPreviousCatalog = null
                            }
                        }
                    }
                }
            }
        }
    }

    fun cancelAuthorization() {
        appScope.launch {
            val cancellation = mutex.withLock {
                val job = authorizationJob
                authorizationJob = null
                val operation = ++authorizationOperation
                val previousStatus = authorizationPreviousStatus
                val previousCatalog = authorizationPreviousCatalog
                job?.cancel()
                McpAuthorizationCancellation(job, operation, previousStatus, previousCatalog)
            }
            cancellation.job?.cancelAndJoin()
            var persistenceFailure: Throwable? = null
            try {
                oauthCoordinator.touchState(serverId)
            } catch (error: Throwable) {
                persistenceFailure = error
                logger(getServerName(), "OAuth cancellation persistence failed: ${error.message}")
            } finally {
                mutex.withLock {
                    if (authorizationOperation == cancellation.operation) {
                        if (persistenceFailure == null) {
                            setStatusLocked(
                                cancellation.previousStatus ?: McpStatus.NeedsAuthorization,
                                cancellation.previousCatalog,
                            )
                        } else {
                            setStatusLocked(
                                McpStatus.Error.from(
                                    requireNotNull(persistenceFailure),
                                    "OAuth cancellation could not be persisted",
                                ),
                                cancellation.previousCatalog,
                            )
                        }
                        authorizationPreviousStatus = null
                        authorizationPreviousCatalog = null
                    }
                }
            }
        }
    }

    private fun getServerName(): String {
        return settingsStore.effectiveSettings.value.settings.mcpServers
            .find { it.id == serverId }?.commonOptions?.name ?: serverId.toString()
    }
}

private data class McpCatalogRefreshLease(
    val config: McpServerConfig,
    val client: Client,
    val previousStatus: McpStatus,
    val previousCatalog: McpCatalogSnapshot?,
)

private data class McpAuthorizationCancellation(
    val job: Job?,
    val operation: Long,
    val previousStatus: McpStatus?,
    val previousCatalog: McpCatalogSnapshot?,
)

private data class McpAuthorizationReplacement(
    val job: Job?,
    val operation: Long,
    val previousStatus: McpStatus,
    val previousCatalog: McpCatalogSnapshot?,
)

private fun McpStatus.catalogCountForLog(): Int = when (this) {
    is McpStatus.Ready -> toolCount
    is McpStatus.CatalogStale -> lastKnownGoodCount
    else -> 0
}
