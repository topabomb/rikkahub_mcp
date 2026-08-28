package net.weero.measix.pilot.data.ai.mcp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlin.uuid.Uuid

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
    private val runtimes = mutableMapOf<Uuid, McpServerRuntime>()
    private val _capabilities = MutableStateFlow<Map<Uuid, McpRuntimeCapability>>(emptyMap())

    val capabilities: StateFlow<Map<Uuid, McpRuntimeCapability>> = _capabilities
    val serverIds: Set<Uuid> get() = synchronized(lock) { runtimes.keys.toSet() }
    val activeRuntimes: List<McpServerRuntime> get() = synchronized(lock) { runtimes.values.toList() }
    val isEmpty: Boolean get() = synchronized(lock) { runtimes.isEmpty() }

    fun find(serverId: Uuid): McpServerRuntime? = synchronized(lock) { runtimes[serverId] }

    fun getOrCreate(
        serverId: Uuid,
        create: () -> McpServerRuntime,
    ): McpServerRuntime = synchronized(lock) { runtimes.getOrPut(serverId, create) }

    fun isCurrent(runtime: McpServerRuntime): Boolean =
        synchronized(lock) { runtimes[runtime.serverId] === runtime }

    fun publish(runtime: McpServerRuntime, capability: McpRuntimeCapability) {
        synchronized(lock) {
            if (runtimes[runtime.serverId] !== runtime) return
            _capabilities.update { it + (runtime.serverId to capability) }
        }
    }

    fun remove(runtime: McpServerRuntime): Boolean = synchronized(lock) {
        if (runtimes[runtime.serverId] !== runtime) return@synchronized false
        runtimes.remove(runtime.serverId)
        _capabilities.update { it - runtime.serverId }
        true
    }
}
