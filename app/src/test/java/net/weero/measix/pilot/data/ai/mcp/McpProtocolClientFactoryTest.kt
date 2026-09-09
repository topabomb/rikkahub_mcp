package net.weero.measix.pilot.data.ai.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class McpProtocolClientFactoryTest {
    @Test fun `constructing factory and using overrides never initializes HTTP`() = runTest {
        val transport = mockk<AbstractTransport>()
        val client = mockk<Client>()
        val factory = McpProtocolClientFactory(createManagedHttpClient = { error("unexpected managed connection") }, createLocalHttpClient = { error("unexpected local connection") },
            createHttpClient = { error("HTTP must remain uninitialized") },
            transportOverride = { transport },
            clientOverride = { client },
        )
        val config = McpServerConfig.SseTransportServer(url = "https://example.invalid/mcp")
        assertSame(client, factory.createClient(McpConnectionDefinition.User(config)))
        assertSame(transport, factory.createTransport(McpConnectionDefinition.User(config)))
    }

    @Test fun `parallel real transport creation shares one HTTP client without connecting`() = runTest {
        val created = AtomicInteger()
        lateinit var http: HttpClient
        val factory = McpProtocolClientFactory(createManagedHttpClient = { error("unexpected managed connection") }, createLocalHttpClient = { error("unexpected local connection") }, createHttpClient = {
            created.incrementAndGet()
            HttpClient(OkHttp) { install(SSE) }.also { http = it }
        })
        assertEquals(0, created.get())
        try {
            val transports = (0 until 4).map { index ->
                async(Dispatchers.Default) {
                    val config = if (index % 2 == 0) McpServerConfig.SseTransportServer(url = "https://example.invalid/mcp")
                        else McpServerConfig.StreamableHTTPServer(url = "https://example.invalid/mcp")
                    factory.createTransport(McpConnectionDefinition.User(config))
                }
            }.awaitAll()
            assertEquals(1, created.get())
            assertEquals(4, transports.toSet().size)
            transports.forEach { it.close() }
        } finally { http.close() }
    }

    @Test fun `cancellation during initialization does not hand off a transport or discard the shared client`() = runTest {
        val http = HttpClient(OkHttp) { install(SSE) }
        val created = AtomicInteger()
        lateinit var cancelled: Deferred<AbstractTransport>
        val handedOff = AtomicBoolean(false)
        val factory = McpProtocolClientFactory(createManagedHttpClient = { error("unexpected managed connection") }, createLocalHttpClient = { error("unexpected local connection") }, createHttpClient = {
            created.incrementAndGet()
            cancelled.cancel()
            http
        })
        val config = McpServerConfig.SseTransportServer(url = "https://example.invalid/mcp")
        try {
            cancelled = async(Dispatchers.Default, start = CoroutineStart.LAZY) {
                factory.createTransport(McpConnectionDefinition.User(config)).also { handedOff.set(true) }
            }
            cancelled.start()
            try { cancelled.await(); fail("Cancelled initialization cannot return a transport") }
            catch (_: CancellationException) { }
            cancelled.join()
            assertFalse(handedOff.get())
            val later = factory.createTransport(McpConnectionDefinition.User(config))
            assertEquals(1, created.get())
            later.close()
        } finally { http.close() }
    }
}
