package net.weero.measix.pilot.data.ai.mcp

import io.github.oshai.kotlinlogging.KotlinLogging
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class McpClientTransportTest {
    @Test fun `cancelled shutdown keeps original IO awaitable after SDK enters terminal state`() = runBlocking {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val cleaning = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            try {
                val callbacks = AtomicInteger()
                val transport = TestTransport(onSend = {
                    entered.complete(Unit)
                    try { awaitCancellation() }
                    finally { withContext(NonCancellable) { cleaning.complete(Unit); release.await() } }
                })
                transport.onClose { callbacks.incrementAndGet() }
                transport.start()
                val send = async { transport.send(JSONRPCRequest(method = "tools/list"), null) }
                entered.await()
                val closing = async(start = CoroutineStart.UNDISPATCHED) { transport.close() }
                cleaning.await()
                assertFalse(closing.isCompleted)
                closing.cancelAndJoin()
                assertFalse(send.isCompleted)
                val retry = async(start = CoroutineStart.UNDISPATCHED) { transport.close() }
                assertFalse(retry.isCompleted)
                release.complete(Unit)
                retry.await()
                send.join()
                assertTrue(send.isCancelled)
                assertEquals(1, callbacks.get())
                transport.close()
            } finally { release.complete(Unit) }
        }
    }

    @Test fun `shutdown during initialization waits for the accepted IO cleanup`() = runBlocking {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val cleaning = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            try {
                val transport = TestTransport(onInitialize = {
                    entered.complete(Unit)
                    try { awaitCancellation() }
                    finally { withContext(NonCancellable) { cleaning.complete(Unit); release.await() } }
                })
                val start = async { transport.start() }
                entered.await()
                val close = async(start = CoroutineStart.UNDISPATCHED) { transport.close() }
                cleaning.await()
                assertFalse(close.isCompleted)
                release.complete(Unit)
                close.await()
                start.join()
                assertTrue(start.isCancelled)
                transport.close()
            } finally { release.complete(Unit) }
        }
    }

    @Test fun `caller cancellation waits for transport work and shutdown blocks future work`() = runBlocking {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val cleaning = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            try {
                val sends = AtomicInteger()
                val transport = TestTransport(onSend = {
                    sends.incrementAndGet()
                    entered.complete(Unit)
                    try { awaitCancellation() }
                    finally { withContext(NonCancellable) { cleaning.complete(Unit); release.await() } }
                })
                transport.start()
                val send = async { transport.send(JSONRPCRequest(method = "tools/list"), null) }
                entered.await()
                send.cancel()
                cleaning.await()
                assertFalse(send.isCompleted)
                release.complete(Unit)
                send.join()
                transport.close()
                assertTrue(runCatching { transport.send(JSONRPCRequest(method = "tools/list"), null) }.isFailure)
                assertEquals(1, sends.get())
            } finally { release.complete(Unit) }
        }
    }

    private class TestTransport(
        private val onInitialize: suspend () -> Unit = {},
        private val onSend: suspend () -> Unit = {},
    ) : McpClientTransport() {
        override val logger = KotlinLogging.logger {}
        override suspend fun initializeTransport() = onInitialize()
        override suspend fun sendMessage(message: JSONRPCMessage, options: TransportSendOptions?) = onSend()
    }
}
