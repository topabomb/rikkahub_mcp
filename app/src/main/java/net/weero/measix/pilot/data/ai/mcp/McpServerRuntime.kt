package net.weero.measix.pilot.data.ai.mcp

import me.rerere.common.configuration.ConfigurationReference
import android.content.Context
import android.util.Log
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.ToolListChangedNotification
import io.modelcontextprotocol.kotlin.sdk.types.Method.Defined.NotificationsToolsListChanged
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
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
import java.util.concurrent.atomic.AtomicLong

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
    data class Candidate(val config: McpConnectionDefinition) : McpToolCallAdmission
    data class Rejected(val serverName: String, val message: String) : McpToolCallAdmission
}

internal class McpServerRuntime(
    val key: McpRuntimeKey,
    private val definition: McpRuntimeDefinition,
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
    private val onClosed: (McpRuntimeKey) -> Unit,
    private val onManagedSnapshotRequired: (McpManagedSnapshotRequired) -> Unit,
) {
    val serverId: ConfigurationReference get() = key.serverId
    val mutex = Mutex()
    private val runtimeJob = SupervisorJob(appScope.coroutineContext[Job])
    private val runtimeScope = CoroutineScope(appScope.coroutineContext + runtimeJob)
    // Serializes connection replacement and all access to retained transport resources; never the UI/state mutex.
    private val connectionOperationMutex = Mutex()
    private val connections = linkedSetOf<McpConnectionResources>()
    private var activeConnection: McpConnectionResources? = null
    val client: Client? get() = activeConnection?.client
    private var closing = false
    private var closeOperation: Deferred<Unit>? = null
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
    private var barrierReported = false
    private var reconnectAttempt = 0
    private val generation = AtomicLong(0)

    fun currentGeneration(): Long = generation.get()

    private fun capability(): McpRuntimeCapability =
        stateStore.capabilities.value[key] ?: McpRuntimeCapability.EMPTY

    private val status: McpStatus get() = capability().status
    private val activeCatalog: McpCatalogSnapshot? get() = capability().catalog

    @Volatile
    private var displayName = serverId.toString()

    /** Configuration admission precedes the connection mutex; callbacks only inspect/hand off runtime state. */
    private suspend fun <T> withDefinition(block: (McpConnectionDefinition?) -> T): T =
        definition.withCurrent(McpDefinitionUse.EXECUTION) { current ->
            mutex.withLock {
                current?.let { displayName = it.name }
                block(current?.takeIf { it.enabled && !barrierReported })
            }
        }

    fun isActivated(): Boolean = activated

    suspend fun bootstrap() = withContext(ioDispatcher) {
        withDefinition { config ->
            if (config == null) return@withDefinition
            hydrateCatalogLocked(config, catalogStore.catalogs.value[config.catalogKey])
            if (activeCatalog == null && status == McpStatus.Idle) {
                setStatusLocked(McpStatus.Idle, null)
            }
        }
    }

    suspend fun reconcile(
        refreshTools: Boolean,
        forceReconnect: Boolean = false,
    ) = withContext(ioDispatcher) {
        withDefinition { config ->
            if (!stateStore.isCurrent(this@McpServerRuntime)) return@withDefinition
            if (closing) {
                beginCloseLocked()
                return@withDefinition
            }
            activated = true
            if (config == null) {
                teardownLocked()
                return@withDefinition
            }
            hydrateCatalogLocked(config, catalogStore.catalogs.value[config.catalogKey])
            // 授权流程进行中不被配置同步打断；需要授权的 server 只有连接参数变化时才重连
            val desiredFingerprint = config.connectionFingerprint()
            if (!forceReconnect && status == McpStatus.Authorizing) return@withDefinition
            if (!forceReconnect && status == McpStatus.NeedsAuthorization && fingerprint == desiredFingerprint) {
                return@withDefinition
            }
            // A non-forced lifecycle trigger joins the AppScope-owned connection/discovery
            // already accepted by this runtime. Only an explicit single-server restart replaces it.
            if (
                !forceReconnect &&
                connectionJob?.isActive == true &&
                connectionRequestFingerprint == desiredFingerprint
            ) {
                return@withDefinition
            }
            val live = client?.takeIf {
                it.transport != null && (status is McpStatus.Ready || status is McpStatus.CatalogStale)
            }
            if (!forceReconnect && live != null && fingerprint == desiredFingerprint) {
                if (refreshTools) requestCatalogRefreshLocked()
                return@withDefinition
            }
            startConnectionLocked(config, retryAfterFailure = true)
        }
    }

    fun requestReconcile(refreshTools: Boolean, forceReconnect: Boolean = false) {
        runtimeScope.launch { reconcile(refreshTools, forceReconnect) }
    }

    suspend fun deactivateIfDisabledOrRemoved() = withContext(ioDispatcher) {
        withDefinition { config ->
            if (!stateStore.isCurrent(this@McpServerRuntime)) return@withDefinition false
            if (config == null) {
                teardownLocked()
                true
            } else false
        }
    }

    suspend fun hydrateCatalog(catalog: McpCatalogSnapshot) = withContext(ioDispatcher) {
        withDefinition { config -> if (config != null) hydrateCatalogLocked(config, catalog) }
    }

    private fun hydrateCatalogLocked(config: McpConnectionDefinition, catalog: McpCatalogSnapshot?) {
        if (closing || catalog == null || catalog.definitionDigest != config.mcpDefinitionDigest()) return
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
            if (closing) return@withLock
            val revokedGeneration = generation.incrementAndGet()
            cancelAllJobsLocked()
            connectionJob?.cancel()
            connectionJob = null
            connectionRequestFingerprint = null
            activeConnection = null
            fingerprint = null
            runtimeScope.launch(ioDispatcher) {
                try {
                    connectionOperationMutex.withLock { closeConnectionsBefore(revokedGeneration) }
                } catch (timeout: TimeoutCancellationException) {
                    mutex.withLock {
                        if (generation.get() == revokedGeneration) setStatusLocked(McpStatus.Error("MCP resource cleanup timed out"))
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    mutex.withLock {
                        if (generation.get() == revokedGeneration) setStatusLocked(failureStatus(error))
                    }
                }
            }
            setStatusLocked(McpStatus.NeedsAuthorization)
        }
    }

    /** Waits for the operation accepted before this call; coalesced follow-up refreshes drain too. */
    suspend fun awaitCurrentOperations() {
        while (true) {
            val cleanup = mutex.withLock { closeOperation }
            if (cleanup != null) {
                cleanup.await()
                return
            }
            val operation = mutex.withLock {
                connectionJob?.takeIf { it.isActive }
                    ?: catalogRefreshJob?.takeIf { it.isActive }
            } ?: return
            operation.join()
        }
    }

    suspend fun closeAndAwait() {
        val close = mutex.withLock { teardownLocked(); requireNotNull(closeOperation) }
        close.await()
    }

    private fun teardownLocked() {
        if (!closing) {
            closing = true
            generation.incrementAndGet()
            activated = false
            setStatusLocked(McpStatus.Idle)
            activeConnection = null
            fingerprint = null
            runtimeJob.cancel()
        }
        beginCloseLocked()
    }

    private fun beginCloseLocked() {
        if (closeOperation?.let { !it.isCompleted || !it.isCancelled } == true) return
        // Cleanup belongs to AppScope: the sealed runtime must never await its own child here.
        closeOperation = appScope.async(ioDispatcher) {
            try {
                withTimeout(policy.clientCloseTimeoutMs) { runtimeJob.join() }
                connectionOperationMutex.withLock { closeConnectionsBefore(Long.MAX_VALUE) }
                mutex.withLock { stateStore.remove(this@McpServerRuntime) }
                logger(getServerName(), "Disconnected (resources closed)")
                onClosed(key)
            } catch (timeout: TimeoutCancellationException) {
                mutex.withLock { setStatusLocked(McpStatus.Error("MCP resource cleanup timed out; retry required")) }
                throw timeout
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutex.withLock { setStatusLocked(failureStatus(error, "MCP resource cleanup failed")) }
                throw error
            }
        }
    }

    /** 锁内只交接 lease；connect、发现和目录落盘均由 AppScope operation 在锁外完成。 */
    fun startConnectionLocked(
        config: McpConnectionDefinition,
        retryAfterFailure: Boolean,
        cancelReconnect: Boolean = true,
    ) {
        if (closing) return
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
        activeConnection = null
        fingerprint = null
        val retainedCatalog = activeCatalog?.takeIf {
            it.definitionDigest == config.mcpDefinitionDigest()
        }
        if (retainedCatalog == null) setStatusLocked(McpStatus.Idle, null)
        connectionJob = runtimeScope.launch(ioDispatcher) {
            connectionOperationMutex.withLock {
                runConnectionOperation(config, assignedGeneration, retryAfterFailure)
            }
        }
    }

    private suspend fun runConnectionOperation(
        requestedConfig: McpConnectionDefinition,
        assignedGeneration: Long,
        retryAfterFailure: Boolean,
    ) {
        var resources: McpConnectionResources? = null
        var newClient: Client? = null
        var catalogAccepted = false
        try {
            lifecycleOperationSemaphore.withPermit {
                withTimeout(policy.connectionOperationTimeoutMs) {
                    closeConnectionsBefore(assignedGeneration)
                    val config = refreshCredentials(requestedConfig)
                    if (!matchesDesiredDefinition(assignedGeneration, config)) {
                        requestReconcile(refreshTools = false)
                        return@withTimeout
                    }
                    val connecting = withDefinition { current ->
                        if (!matchesDesiredDefinitionLocked(assignedGeneration, config, current)) return@withDefinition false
                        setStatusLocked(McpStatus.Connecting)
                        true
                    }
                    if (!connecting) return@withTimeout
                    val transport = protocolClientFactory.createTransport(config)
                    val ownedResources = McpConnectionResources(assignedGeneration, transport)
                    connections.add(ownedResources)
                    resources = ownedResources
                    val createdClient = protocolClientFactory.createClient(config)
                    newClient = createdClient
                    ownedResources.client = createdClient
                    setupNotificationHandlers(createdClient, config, assignedGeneration)
                    transport.onClose {
                        runtimeScope.launch { onTransportClosed(assignedGeneration, createdClient) }
                    }
                    transport.onError { error ->
                        runtimeScope.launch { onTransportError(assignedGeneration, createdClient, error) }
                    }
                    val admitted = withDefinition { current ->
                        if (!matchesDesiredDefinitionLocked(assignedGeneration, config, current)) return@withDefinition false
                        activeConnection = ownedResources
                        fingerprint = config.connectionFingerprint()
                        true
                    }
                    if (!admitted) return@withTimeout
                    createdClient.connect(transport)
                    val discovering = withDefinition { current ->
                        if (!matchesClientLeaseLocked(assignedGeneration, createdClient, config, current)) {
                            return@withDefinition false
                        }
                        setStatusLocked(McpStatus.Discovering)
                        true
                    }
                    if (!discovering) return@withTimeout
                    val candidate = McpCatalogDiscovery.fetchCandidate(
                        config.catalogKey,
                        config.mcpDefinitionDigest(), createdClient, config.managed,
                    )
                    if (!matchesClientLease(assignedGeneration, createdClient, config)) return@withTimeout
                    val published = commitAndActivateCatalog(candidate) { current, catalogResult ->
                        if (!matchesClientLeaseLocked(assignedGeneration, createdClient, config, current)) return@commitAndActivateCatalog false
                        publishCatalogResultLocked(catalogResult)
                        catalogAccepted = true
                        reconnectAttempt = 0
                        logger(
                            config.name,
                            "Discovery completed (${status.catalogCountForLog()} tools available)",
                        )
                        if (catalogRefreshPending) {
                            catalogRefreshPending = false
                            requestCatalogRefreshLocked()
                        }
                        true
                    }
                    if (!published && key.access != null) {
                        catalogAccepted = retainConfirmedCatalog(config, assignedGeneration, createdClient)
                    }
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            if (!catalogAccepted) handleConnectionFailure(requestedConfig, assignedGeneration, retryAfterFailure, newClient, timeout)
        } catch (cancelled: CancellationException) {
            // Transport-owned cancellation can end initialization without cancelling this runtime worker.
            if (currentCoroutineContext().isActive) {
                handleConnectionFailure(requestedConfig, assignedGeneration, retryAfterFailure, newClient,
                    java.io.IOException("MCP connection interrupted", cancelled))
            }
            throw cancelled
        } catch (error: Throwable) {
            handleConnectionFailure(requestedConfig, assignedGeneration, retryAfterFailure, newClient, error)
        } finally {
            val operation = currentCoroutineContext()[Job]
            withContext(NonCancellable) {
                try {
                    resources?.let { candidate ->
                        val owned = mutex.withLock {
                            activeConnection === candidate && generation.get() == assignedGeneration
                        }
                        if (!owned) closeConnection(candidate)
                    }
                } catch (error: Exception) {
                    // The resource stays registered; a later reconnect/close retries the same owner.
                    mutex.withLock {
                        if (generation.get() == assignedGeneration) setStatusLocked(failureStatus(error))
                    }
                    logger(getServerName(), "Connection cleanup incomplete: ${failureDetail(error)}")
                } finally {
                    mutex.withLock {
                        if (connectionJob === operation) {
                            connectionJob = null
                            connectionRequestFingerprint = null
                        }
                    }
                }
            }
        }
    }

    private suspend fun handleConnectionFailure(
        requestedConfig: McpConnectionDefinition,
        assignedGeneration: Long,
        retryAfterFailure: Boolean,
        failedClient: Client?,
        error: Throwable,
    ) {
        McpManagedSnapshotRequired.find(error)?.let {
            acceptManagedBarrier(assignedGeneration, it)
            return
        }
        val authorizationRequired = needsAuthorization(requestedConfig, error)
        mutex.withLock {
            if (generation.get() != assignedGeneration) return@withLock
            if (client === failedClient) {
                activeConnection = null
                fingerprint = null
            }
            if (authorizationRequired) {
                setStatusLocked(McpStatus.NeedsAuthorization)
                cancelRecoveryJobsLocked()
            } else if (retryAfterFailure && McpProtocolFailureClassifier.isConnectionError(error)) {
                scheduleReconnectLocked(assignedGeneration)
            } else {
                setStatusLocked(failureStatus(error))
            }
        }
        logger(getServerName(), "Connection failed: ${failureDetail(error)}")
    }

    internal suspend fun refreshCredentials(config: McpConnectionDefinition): McpConnectionDefinition = when (config) {
        is McpConnectionDefinition.User -> McpConnectionDefinition.User(oauthCoordinator.ensureFreshToken(config.config))
        is McpConnectionDefinition.Managed -> config
    }

    private fun needsAuthorization(config: McpConnectionDefinition, error: Throwable): Boolean =
        config is McpConnectionDefinition.User && McpProtocolFailureClassifier.isUnauthorized(error) &&
            config.config.commonOptions.headers.none { it.first.equals("Authorization", ignoreCase = true) }

    private suspend fun rollbackStaleCommit(result: McpCatalogCommitResult) {
        if (result !is McpCatalogCommitResult.Committed) return
        catalogStore.rollbackCommitted(result.snapshot, result.previous, result.headToken)
    }

    /** A durable receipt must be accepted or compensated before cancellation can discard it. */
    private suspend fun commitAndActivateCatalog(
        candidate: McpCatalogCandidate,
        activateLocked: (McpConnectionDefinition?, McpCatalogCommitResult) -> Boolean,
    ): Boolean {
        catalogStore.awaitReady()
        var accepted = false
        val caller = currentCoroutineContext()
        caller.ensureActive()
        withContext(NonCancellable) {
            definition.withCurrent(McpDefinitionUse.CATALOG_PUBLICATION) { current ->
                if (!caller.isActive || current?.mcpDefinitionDigest() != candidate.definitionDigest || current.catalogKey != candidate.key || current.managed != candidate.managed) return@withCurrent
                val result = catalogStore.commitCandidate(candidate)
                accepted = try {
                    mutex.withLock { caller.isActive && activateLocked(current, result) }
                } catch (error: Throwable) {
                    try { rollbackStaleCommit(result) }
                    catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                    throw error
                }
                if (!accepted) rollbackStaleCommit(result)
            }
        }
        caller.ensureActive()
        return accepted
    }

    /** A newer publication cannot replace the original interaction's already-confirmed schema. */
    private suspend fun retainConfirmedCatalog(config: McpConnectionDefinition, epoch: Long, expectedClient: Client): Boolean =
        withDefinition { current ->
            if (!matchesClientLeaseLocked(epoch, expectedClient, config, current)) return@withDefinition false
            val retained = activeCatalog?.takeIf { it.definitionDigest == config.mcpDefinitionDigest() }
                ?: error("mcp_catalog_configuration_superseded")
            setStatusLocked(McpStatus.Ready(retained.tools.size, retained.revision), retained)
            true
        }

    private suspend fun matchesDesiredDefinition(
        assignedGeneration: Long,
        config: McpConnectionDefinition,
    ): Boolean = withDefinition { current -> matchesDesiredDefinitionLocked(assignedGeneration, config, current) }

    private fun matchesDesiredDefinitionLocked(
        assignedGeneration: Long,
        config: McpConnectionDefinition,
        current: McpConnectionDefinition?,
    ): Boolean = !closing && stateStore.isCurrent(this@McpServerRuntime) &&
        generation.get() == assignedGeneration &&
        current?.connectionFingerprint() == config.connectionFingerprint()

    private suspend fun matchesClientLease(
        assignedGeneration: Long,
        expectedClient: Client,
        config: McpConnectionDefinition,
    ): Boolean = withDefinition { current -> matchesClientLeaseLocked(assignedGeneration, expectedClient, config, current) }

    private fun matchesClientLeaseLocked(
        assignedGeneration: Long,
        expectedClient: Client,
        config: McpConnectionDefinition,
        current: McpConnectionDefinition?,
    ): Boolean = matchesDesiredDefinitionLocked(assignedGeneration, config, current) && client === expectedClient

    /** Runtime serializes admission against definition removal and client hand-off. */
    suspend fun admitInvocation(
        toolName: String,
        expectedDefinitionDigest: String,
        expectedNeedsApproval: Boolean,
    ): McpToolCallAdmission = withDefinition { current ->
        val rejection = invocationRejection(
            current,
            toolName,
            expectedDefinitionDigest,
            expectedNeedsApproval,
        )
        if (rejection != null) {
            return@withDefinition McpToolCallAdmission.Rejected(
                current?.name ?: serverId.toString(),
                rejection,
            )
        }
        McpToolCallAdmission.Candidate(requireNotNull(current))
    }

    /** Revalidates every admission condition after credential refresh performed outside this owner. */
    suspend fun completeInvocationAdmission(
        freshConfig: McpConnectionDefinition,
        toolName: String,
        expectedDefinitionDigest: String,
        expectedNeedsApproval: Boolean,
    ): McpToolCallPreparation = withDefinition { current ->
        val rejection = invocationRejection(
            current,
            toolName,
            expectedDefinitionDigest,
            expectedNeedsApproval,
        )
        val serverName = current?.name ?: serverId.toString()
        if (rejection != null) {
            return@withDefinition McpToolCallPreparation.Rejected(
                kind = McpToolFailureKind.TOOL_UNAVAILABLE,
                serverName = serverName,
                diagnosticMessage = rejection,
            )
        }
        val currentConfig = requireNotNull(current)
        if (currentConfig.connectionFingerprint() != freshConfig.connectionFingerprint()) {
            logger(currentConfig.name, "Connection credentials changed during callTool; scheduling reconnect")
            startConnectionLocked(currentConfig, retryAfterFailure = true)
            return@withDefinition McpToolCallPreparation.Rejected(
                kind = McpToolFailureKind.SERVER_UNAVAILABLE,
                serverName = currentConfig.name,
                diagnosticMessage = "Session credentials changed; recovery started",
            )
        }
        if (status == McpStatus.NeedsAuthorization) {
            return@withDefinition McpToolCallPreparation.Rejected(
                kind = McpToolFailureKind.AUTHORIZATION_REQUIRED,
                serverName = currentConfig.name,
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
            return@withDefinition McpToolCallPreparation.Rejected(
                kind = McpToolFailureKind.SERVER_UNAVAILABLE,
                serverName = currentConfig.name,
                diagnosticMessage = "MCP session is unavailable; recovery is in progress",
            )
        }
        if (fingerprint != currentConfig.connectionFingerprint()) {
            startConnectionLocked(currentConfig, retryAfterFailure = true)
            return@withDefinition McpToolCallPreparation.Rejected(
                kind = McpToolFailureKind.SERVER_UNAVAILABLE,
                serverName = currentConfig.name,
                diagnosticMessage = "Session credentials changed; recovery started",
            )
        }
        if (liveClient.transport == null) {
            setStatusLocked(McpStatus.Reconnecting(1, policy.maxFastReconnectAttempts))
            scheduleReconnectLocked(currentGeneration())
            return@withDefinition McpToolCallPreparation.Rejected(
                kind = McpToolFailureKind.SERVER_UNAVAILABLE,
                serverName = currentConfig.name,
                diagnosticMessage = "MCP transport is disconnected; recovery is in progress",
            )
        }
        if (liveClient.serverCapabilities?.tools == null) {
            return@withDefinition McpToolCallPreparation.Rejected(
                kind = McpToolFailureKind.PROTOCOL_INCOMPATIBLE,
                serverName = currentConfig.name,
                diagnosticMessage = "MCP server does not declare tools capability",
            )
        }
        McpToolCallPreparation.Ready(
            client = liveClient,
            serverName = currentConfig.name,
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
        currentConfig: McpConnectionDefinition?,
        toolName: String,
        expectedDefinitionDigest: String,
        expectedNeedsApproval: Boolean,
    ): String? {
        if (closing || !stateStore.isCurrent(this)) return "TOOL_REVOKED: MCP runtime is closing"
        if (currentConfig == null) return "TOOL_REVOKED: MCP tool is no longer available"
        val policy = currentConfig.toolPolicy(toolName)
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
            is McpCatalogCommitResult.RejectedGeneration -> {
                setStatusLocked(McpStatus.Error("MCP catalog generation is stale; synchronize enterprise configuration"))
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

    private suspend fun closeConnectionsBefore(beforeGeneration: Long) {
        val retired = connections.filter { it.generation < beforeGeneration }
        retired.forEach { closeConnection(it) }
    }

    private suspend fun closeConnection(target: McpConnectionResources) {
        withTimeout(policy.clientCloseTimeoutMs) {
            // SDK may detach Client.transport during its callback, so retain the original transport.
            try { target.client?.close() }
            finally { target.transport.close() }
        }
        connections.remove(target)
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
        McpManagedSnapshotRequired.find(error)?.let {
            acceptManagedBarrier(capturedGeneration, it)
            return
        }

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

    suspend fun acceptManagedBarrier(epoch: Long, barrier: McpManagedSnapshotRequired) {
        val accepted = mutex.withLock {
            if (key.access == null || generation.get() != epoch || closing || barrierReported) return@withLock false
            barrierReported = true
            activeConnection = null
            fingerprint = null
            cancelRecoveryJobsLocked()
            setStatusLocked(McpStatus.Error("managed_snapshot_required"))
            true
        }
        if (accepted) onManagedSnapshotRequired(barrier)
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
        catalogRefreshJob = runtimeScope.launch(ioDispatcher) {
            drainCatalogRefreshes(assignedGeneration, getServerName())
        }
    }

    /**
     * A single per-generation recovery loop: fast equal-jitter exponential retry followed by
     * foreground-only maintenance probes capped at five minutes. Offline/background waiting is
     * event driven and does not consume attempts or poll the radio.
     */
    private fun scheduleReconnectLocked(capturedGeneration: Long) {
        if (closing || barrierReported || generation.get() != capturedGeneration) return
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
        reconnectJob = runtimeScope.launch {
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
                withDefinition { config ->
                    if (generation.get() != capturedGeneration) return@withDefinition
                    if (config == null) {
                        teardownLocked()
                        return@withDefinition
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

    private fun setupNotificationHandlers(client: Client, config: McpConnectionDefinition, assignedGeneration: Long) {
        if (config.managed?.gatewaySurface != null) return
        val configName = config.name
        client.setNotificationHandler<ToolListChangedNotification>(
            NotificationsToolsListChanged
        ) {
            logger(configName, "Received tools/list_changed notification")
            runtimeScope.launch {
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
                val lease = withDefinition { current ->
                    if (generation.get() != assignedGeneration || current == null) return@withDefinition null
                    val live = client ?: return@withDefinition null
                    if (live.transport == null) {
                        scheduleReconnectLocked(assignedGeneration)
                        return@withDefinition null
                    }
                    val previousStatus = catalogRefreshPreviousStatus ?: status
                    val previousCatalog = catalogRefreshPreviousCatalog
                    catalogRefreshPreviousStatus = null
                    catalogRefreshPreviousCatalog = null
                    catalogRefreshPending = false
                    McpCatalogRefreshLease(current, live, previousStatus, previousCatalog)
                } ?: return
                var catalogAccepted = false
                try {
                    lifecycleOperationSemaphore.withPermit {
                        withTimeout(policy.catalogRefreshTimeoutMs) {
                            if (!matchesClientLease(assignedGeneration, lease.client, lease.config)) {
                                return@withTimeout
                            }
                            val candidate = McpCatalogDiscovery.fetchCandidate(
                                lease.config.catalogKey,
                                lease.config.mcpDefinitionDigest(), lease.client, lease.config.managed,
                            )
                            if (!matchesClientLease(assignedGeneration, lease.client, lease.config)) {
                                return@withTimeout
                            }
                            val published = commitAndActivateCatalog(candidate) { current, result ->
                                if (!matchesClientLeaseLocked(assignedGeneration, lease.client, lease.config, current)) {
                                    return@commitAndActivateCatalog false
                                }
                                publishCatalogResultLocked(result)
                                catalogAccepted = true
                                true
                            }
                            if (!published && key.access != null) {
                                catalogAccepted = retainConfirmedCatalog(lease.config, assignedGeneration, lease.client)
                            }
                        }
                    }
                } catch (timeout: TimeoutCancellationException) {
                    mutex.withLock {
                        if (catalogAccepted || generation.get() != assignedGeneration || client !== lease.client) return@withLock
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
                        if (!catalogAccepted && generation.get() == assignedGeneration && client === lease.client) {
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
                                        McpStatus.CatalogStale(catalog.tools.size, catalog.revision, failureDetail(error))
                                    } ?: failureStatus(error, "catalog discovery failed"),
                                    lastGood,
                                )
                            }
                        }
                    }
                    logger(configName, "Catalog refresh failed: ${failureDetail(error)}")
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
            if (serverId is ConfigurationReference.User) Log.e(TAG, "Failed to sync tools after list_changed for $configName", error)
            logger(configName, "catalog refresh after list_changed failed: ${failureDetail(error)}")
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
        runtimeScope.launch {
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
                logger(getServerName(), "OAuth replacement could not be sealed: ${failureDetail(error)}")
                mutex.withLock {
                    if (authorizationOperation == replacement.operation) {
                        setStatusLocked(
                            failureStatus(error, "OAuth authorization could not be started"),
                            replacement.previousCatalog,
                        )
                        authorizationPreviousStatus = null
                        authorizationPreviousCatalog = null
                    }
                }
                return@launch
            }

            withDefinition { current ->
                if (authorizationOperation != replacement.operation || current !is McpConnectionDefinition.User) return@withDefinition
                setStatusLocked(McpStatus.Authorizing)
                authorizationJob = runtimeScope.launch {
                    try {
                        oauthCoordinator.authorize(current.config, context)
                        reconcile(refreshTools = true, forceReconnect = true)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        logger(current.name, "OAuth authorization failed: ${failureDetail(error)}")
                        mutex.withLock {
                            if (authorizationOperation == replacement.operation) {
                                setStatusLocked(McpStatus.NeedsAuthorization)
                            }
                        }
                        // 授权期间被跳过的配置变化（如 URL 修改）在此收敛
                        runtimeScope.launch { reconcile(refreshTools = true) }
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
        runtimeScope.launch {
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
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                persistenceFailure = error
                logger(getServerName(), "OAuth cancellation persistence failed: ${failureDetail(error)}")
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
                                failureStatus(
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

    private fun failureDetail(error: Throwable): String? =
        if (serverId is ConfigurationReference.Enterprise) "Managed MCP operation failed (${error::class.simpleName})" else error.message

    private fun failureStatus(error: Throwable, fallback: String? = null): McpStatus.Error =
        if (serverId is ConfigurationReference.Enterprise) McpStatus.Error(failureDetail(error)) else McpStatus.Error.from(error, fallback)

    private fun getServerName(): String = displayName
}

private data class McpCatalogRefreshLease(
    val config: McpConnectionDefinition,
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

/** Retained by its runtime until both SDK and original transport cleanup have completed. */
private class McpConnectionResources(
    val generation: Long,
    val transport: AbstractTransport,
) {
    var client: Client? = null
}
