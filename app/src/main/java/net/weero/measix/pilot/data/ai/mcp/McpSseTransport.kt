// Modified from modelcontextprotocol/kotlin-sdk 0.15.0; upstream license is bundled in assets/licenses.
// Source and local changes: docs/references/mcp-architecture.md.
package net.weero.measix.pilot.data.ai.mcp


import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.preparePost
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.append
import io.ktor.http.isSuccess
import io.ktor.http.contentType
import io.ktor.http.protocolWithAuthority
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Client transport for SSE: this will connect to a server using Server-Sent Events for receiving
 * messages and make separate POST requests for sending messages.
 */
internal class McpSseTransport(
    private val client: HttpClient,
    private val urlString: String,
    private val requestBuilder: HttpRequestBuilder.() -> Unit = {},
) : McpClientTransport() {


    override val logger: KLogger = KotlinLogging.logger {}

    private val endpoint = CompletableDeferred<String>()

    private var job: Job? = null
    private lateinit var origin: String
    private lateinit var baseUrl: String

    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    override suspend fun initializeTransport() {
        job = transportScope.launch(CoroutineName("SseMcpClientTransport.connect#${hashCode()}"), start = CoroutineStart.ATOMIC) {
            try {
                client.prepareGet(urlString) {
                    headers.append(HttpHeaders.Accept, ContentType.Text.EventStream.toString())
                    requestBuilder()
                }.execute { response ->
                    check(response.status.isSuccess()) { "MCP SSE connection failed: HTTP ${response.status.value}" }
                    check(response.contentType()?.match(ContentType.Text.EventStream) == true) { "Invalid MCP SSE content type" }
                    val url = response.call.request.url
                    origin = url.protocolWithAuthority
                    val path = url.encodedPath
                    baseUrl = origin + if (path.endsWith("/")) path.removeSuffix("/") else path.substringBeforeLast('/', "")
                    response.readMcpSseEvents { event ->
                        ensureActive()
                        when (event.name) {
                            "error" -> error("MCP SSE error")
                            "open" -> Unit
                            "endpoint" -> handleEndpoint(event.data)
                            else -> if (event.data.isNotBlank()) _onMessage(catalogWire.decode(event.data))
                        }
                        true
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _onError(error)
                endpoint.completeExceptionally(error)
            } finally {
                endpoint.completeExceptionally(java.io.IOException("MCP SSE connection closed before endpoint"))
                signalShutdown()
                invokeOnCloseCallback()
            }
        }
        endpoint.await()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun sendMessage(message: JSONRPCMessage, options: TransportSendOptions?) {
        catalogWire.bind(message)
        check(job?.isActive == true) { "McpSseTransport is closed!" }
        check(endpoint.isCompleted) { "Not connected!" }

        client.preparePost(endpoint.getCompleted()) {
            requestBuilder()
            headers.append(HttpHeaders.ContentType, ContentType.Application.Json)
            setBody(McpJson.encodeToString(message))
        }.execute { response ->
            if (!response.status.isSuccess()) {
                val text = response.readMcpBody()
                error("Error POSTing to endpoint (HTTP ${response.status}): $text")
            }
        }

        logger.debug { "Client successfully sent message via SSE $endpoint" }
    }

    /**
     * Resolves and completes [endpoint] based on [eventData].
     * Uses full URLs as-is, treats absolute paths as origin-relative,
     * and relative paths as relative to [baseUrl].
     */
    private fun handleEndpoint(eventData: String) {
        try {
            val endpointUrl = if (eventData.startsWith("http://") || eventData.startsWith("https://")) {
                eventData
            } else if (eventData.startsWith("/")) {
                origin + eventData
            } else {
                "$baseUrl/$eventData"
            }
            endpoint.complete(endpointUrl)
            logger.debug { "Client connected to endpoint: $endpointUrl" }
        } catch (e: Throwable) {
            _onError(e)
            endpoint.completeExceptionally(e)
            throw e
        }
    }

}
