package net.weero.measix.pilot.data.ai.mcp

import io.modelcontextprotocol.kotlin.sdk.shared.AbstractClientTransport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Owns transport IO until its actual completion, independently of the SDK's terminal state. */
internal abstract class McpClientTransport : AbstractClientTransport() {
    private val transportJob = SupervisorJob()
    protected val transportScope = CoroutineScope(transportJob + Dispatchers.Default)
    protected val catalogWire = McpCatalogWire()
    private val closeMutex = Mutex()

    final override suspend fun initialize() = ownedOperation { initializeTransport() }

    final override suspend fun performSend(message: JSONRPCMessage, options: TransportSendOptions?) =
        ownedOperation { sendMessage(message, options) }

    protected abstract suspend fun initializeTransport()
    protected abstract suspend fun sendMessage(message: JSONRPCMessage, options: TransportSendOptions?)

    /** A receiver can signal closure, but cannot await the scope that contains itself. */
    protected fun signalShutdown() {
        catalogWire.close()
        transportJob.cancel()
    }

    final override suspend fun closeResources() {
        signalShutdown()
        transportJob.join()
    }

    final override suspend fun close() = closeMutex.withLock {
        // SDK close may swallow cleanup failure or become a no-op after ShutdownFailed.
        // The original IO owner remains awaitable across both cases, including close before start.
        try { super.close() }
        finally { closeResources() }
    }

    private suspend fun <T> ownedOperation(operation: suspend () -> T): T {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        transportJob.ensureActive()
        // Preserve request-local capture/credentials while parenting IO to this transport.
        val task = transportScope.async(caller.minusKey(Job)) { operation() }
        try { return task.await() }
        finally { withContext(NonCancellable) { task.cancelAndJoin() } }
    }
}
