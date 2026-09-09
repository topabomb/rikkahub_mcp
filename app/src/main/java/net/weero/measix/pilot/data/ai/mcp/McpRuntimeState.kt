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

/** Runtime projection published by the single-server lifecycle owner. */
data class McpRuntimeCapability(
    val status: McpStatus,
    val catalog: McpCatalogSnapshot?,
) {
    companion object {
        val EMPTY = McpRuntimeCapability(McpStatus.Idle, null)
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
            _capabilities.update { it + (runtime.key to capability) }
        }
    }

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
}
