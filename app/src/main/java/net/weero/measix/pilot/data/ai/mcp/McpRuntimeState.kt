package net.weero.measix.pilot.data.ai.mcp

import me.rerere.common.configuration.ConfigurationReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Bounded foreground acknowledgement for AppScope-owned refresh work. */
data class McpRefreshReceipt(
    val requestedServerCount: Int,
    val settledServerCount: Int,
) {
    init {
        require(requestedServerCount >= 0)
        require(settledServerCount in 0..requestedServerCount)
    }

    val continuingServerCount: Int = requestedServerCount - settledServerCount
}

/** Directory outcomes never replace connection or notification health. */
sealed interface McpCatalogRefresh {
    data object Idle : McpCatalogRefresh
    data object Refreshing : McpCatalogRefresh
    data object RejectedEmpty : McpCatalogRefresh
    data class Failed(val error: McpStatus.Error) : McpCatalogRefresh
}

sealed interface McpNotificationHealth {
    data object NotEstablished : McpNotificationHealth
    data object Connecting : McpNotificationHealth
    data object Listening : McpNotificationHealth
    data object Unsupported : McpNotificationHealth
    data class Unavailable(val error: McpStatus.Error, val retryable: Boolean) : McpNotificationHealth
}

/** Runtime projection published by the single-server lifecycle owner. */
data class McpRuntimeCapability(
    val status: McpStatus,
    val catalog: McpCatalogSnapshot?,
    val sessionCallable: Boolean,
    val catalogRefresh: McpCatalogRefresh = McpCatalogRefresh.Idle,
    val notifications: McpNotificationHealth = McpNotificationHealth.NotEstablished,
) {
    companion object {
        val EMPTY = McpRuntimeCapability(McpStatus.Idle, null, false)
    }
}

/**
 * Owns the process-local MCP server-runtime registry and its public capability projection.
 *
 * Runtime identity and projection publication are deliberately kept together: a detached runtime
 * cannot publish late work after the server was removed or replaced.
 */
internal class McpRuntimeStateStore {
    private val lock = Any()
    private val runtimes = mutableMapOf<McpRuntimeKey, McpServerRuntime>()
    private val _capabilities = MutableStateFlow<Map<McpRuntimeKey, McpRuntimeCapability>>(emptyMap())

    val capabilities: StateFlow<Map<McpRuntimeKey, McpRuntimeCapability>> = _capabilities
    val keys: Set<McpRuntimeKey> get() = synchronized(lock) { runtimes.keys.toSet() }
    val activeRuntimes: List<McpServerRuntime> get() = synchronized(lock) { runtimes.values.toList() }
    val isEmpty: Boolean get() = synchronized(lock) { runtimes.isEmpty() }

    fun find(key: McpRuntimeKey): McpServerRuntime? = synchronized(lock) { runtimes[key] }

    fun getOrCreate(
        key: McpRuntimeKey,
        create: () -> McpServerRuntime,
    ): McpServerRuntime = synchronized(lock) { runtimes.getOrPut(key, create) }

    fun isCurrent(runtime: McpServerRuntime): Boolean =
        synchronized(lock) { runtimes[runtime.key] === runtime }

    fun publish(runtime: McpServerRuntime, capability: McpRuntimeCapability) {
        synchronized(lock) {
            if (runtimes[runtime.key] !== runtime) return
            _capabilities.update { previous ->
                val own = capability.catalog?.let { incoming ->
                    val retained = previous[runtime.key]?.catalog?.takeIf {
                        it.samePublication(incoming) && it.revision > incoming.revision
                    }
                    capability.withCatalog(retained ?: incoming)
                } ?: capability
                val next = previous + (runtime.key to own)
                val shared = own.catalog?.takeIf { it.managed != null }
                if (shared == null) next else next.mapValues { (_, peer) ->
                    val known = peer.catalog
                    if (known != null && known.samePublication(shared) && known.revision < shared.revision) {
                        peer.withCatalog(shared)
                    } else peer
                }
            }
        }
    }

    private fun McpCatalogSnapshot.samePublication(other: McpCatalogSnapshot): Boolean =
        key == other.key && definitionDigest == other.definitionDigest && managed == other.managed

    // Confirmed managed directory changes reach existing peers before a later publication can
    // replace the durable head. Connection health and callability remain owned by each runtime.
    private fun McpRuntimeCapability.withCatalog(snapshot: McpCatalogSnapshot): McpRuntimeCapability = copy(
        catalog = snapshot,
        status = when (val health = status) {
            is McpStatus.Ready -> health.copy(toolCount = snapshot.tools.size, catalogRevision = snapshot.revision)
            else -> health
        },
    )

    fun remove(runtime: McpServerRuntime): Boolean = synchronized(lock) {
        if (runtimes[runtime.key] !== runtime) return@synchronized false
        runtimes.remove(runtime.key)
        _capabilities.update { it - runtime.key }
        true
    }
}

/** Keeps the source authorization gate owned until the runtime has accepted or rejected the definition. */
internal interface McpRuntimeDefinition {
    suspend fun <T> withCurrent(use: McpDefinitionUse, operation: suspend (McpConnectionDefinition?) -> T): T

    /** Recheck time-based authority after suspension while the definition gate remains held. */
    fun requireAuthority() = Unit
}
