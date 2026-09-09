package net.weero.measix.pilot.data.ai.mcp

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Original Turn owner keeps its connections and binding alive across user interaction pauses. */
internal class McpExecutionLease(private val releaseOwner: suspend () -> Unit) {
    private val closed = AtomicBoolean(false)
    private val mutex = Mutex()
    private var released = false

    fun requireOpen() { check(!closed.get()) { "mcp_execution_lease_closed" } }

    suspend fun release() {
        closed.set(true)
        withContext(NonCancellable) {
            mutex.withLock {
                if (!released) {
                    releaseOwner()
                    released = true
                }
            }
        }
    }
}
