// Modified from modelcontextprotocol/kotlin-sdk 0.15.0; upstream license is bundled in assets/licenses.
// Source and local changes: docs/references/mcp-architecture.md.
package net.weero.measix.pilot.data.ai.mcp

import io.modelcontextprotocol.kotlin.sdk.client.ReconnectionOptions
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpError

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.headers
import io.ktor.client.request.preparePost
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.modelcontextprotocol.kotlin.sdk.shared.TooLongFrameException
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCNotification
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCResponse
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val MCP_SESSION_ID_HEADER = "mcp-session-id"
private const val MCP_PROTOCOL_VERSION_HEADER = "mcp-protocol-version"
private const val MCP_RESUMPTION_TOKEN_HEADER = "Last-Event-ID"
private const val MCP_METHOD_HEADER = "Mcp-Method"
private const val MCP_NAME_HEADER = "Mcp-Name"
private const val MCP_BASE64_PREFIX = "=?base64?"
private const val MCP_BASE64_SUFFIX = "?="

/**
 * Default maximum size, in characters, of a single inline SSE event assembled from a POST response.
 *
 * Mirrors the stdio transport's 16 MiB frame cap: a server that streams `data:` lines without ever
 * terminating the event cannot grow the client's buffer without bound.
 */
private const val DEFAULT_MAX_INLINE_SSE_EVENT_SIZE: Int = 16 * 1024 * 1024

/**
 * Client transport implementing the MCP Streamable HTTP transport specification.
 *
 * Sends messages via HTTP POST and receives messages via HTTP GET with Server-Sent Events.
 * Supports automatic SSE reconnection with exponential backoff, stream resumption via the
 * `Last-Event-ID` header, and awaited connection shutdown.
 *
 * @param client Ktor HTTP client used for all requests
 * @param url MCP endpoint URL
 * @param reconnectionOptions reconnection backoff and retry-limit settings for the SSE stream
 * @param maxInlineSseEventSize maximum size, in characters, of a single inline SSE event parsed from a
 *      POST response; a server that exceeds it (including by never terminating an event) fails the send
 *      with [io.modelcontextprotocol.kotlin.sdk.shared.TooLongFrameException]. Defaults to 16 MiB.
 * @param requestBuilder builder applied to every outgoing HTTP request, e.g. for adding auth headers
 */
internal class McpStreamableHttpTransport(
    private val client: HttpClient,
    private val url: String,
    private val managed: Boolean = false,
    private val reconnectionOptions: ReconnectionOptions = ReconnectionOptions(),
    private val maxInlineSseEventSize: Int = DEFAULT_MAX_INLINE_SSE_EVENT_SIZE,
    private val requestBuilder: HttpRequestBuilder.() -> Unit = {},
) : McpClientTransport() {

    init {
        require(maxInlineSseEventSize > 0) { "maxInlineSseEventSize must be greater than 0" }
    }

    private val initializeRequest = java.util.concurrent.atomic.AtomicReference<RequestId?>()

    override val logger: KLogger = KotlinLogging.logger {}

    /** Session identifier assigned by the server after initialization, or `null` before connection. */
    private var sessionId: String? = null

    /** MCP protocol version negotiated with the server, or `null` before connection. */
    @Volatile private var protocolVersion: String? = null

    private var sseJob: Job? = null


    /** Result of an SSE stream collection. Reconnect when [hasPrimingEvent] is true and [receivedResponse] is false. */
    private data class SseStreamResult(
        val hasPrimingEvent: Boolean,
        val receivedResponse: Boolean,
        val lastEventId: String? = null,
        val serverRetryDelay: Duration? = null,
    )

    override suspend fun initializeTransport() {
        logger.debug { "Client transport is starting..." }
    }

    /**
     * Sends a single message with optional resumption support
     */
    override suspend fun sendMessage(message: JSONRPCMessage, options: TransportSendOptions?) {
        catalogWire.bind(message)
        if (message is JSONRPCRequest && message.method == "initialize") initializeRequest.set(message.id)
        logger.debug { "Client sending MCP message via POST" }

        // If we have a resumption token, reconnect the SSE stream with it
        options?.resumptionToken?.let { token ->
            startSseSession(
                resumptionToken = token,
                onResumptionToken = options.onResumptionToken,
                replayMessageId = if (message is JSONRPCRequest) message.id else null,
            )
            return
        }

        val jsonBody = McpJson.encodeToString(message)
        client.preparePost(url) {
            applyCommonHeaders(this)
            applyStandardPostHeaders(this, message)
            headers.append(HttpHeaders.Accept, "${ContentType.Application.Json}, ${ContentType.Text.EventStream}")
            contentType(ContentType.Application.Json)
            setBody(jsonBody)
            requestBuilder()
        }.execute { response ->
            response.headers[MCP_SESSION_ID_HEADER]?.let { sessionId = it }

            if (response.status == HttpStatusCode.Accepted) {
                if (message is JSONRPCNotification && message.method == "notifications/initialized") {
                    startSseSession(onResumptionToken = options?.onResumptionToken)
                }
                return@execute
            }

            if (!response.status.isSuccess()) {
                val body = response.readMcpBody()
                val error = if (managed && response.status.value == 428) McpManagedSnapshotRequired.parse(body)
                    else StreamableHttpError(response.status.value, if (managed) "Managed MCP request rejected" else body)
                _onError(error)
                throw error
            }

            when (response.contentType()?.withoutParameters()) {
                ContentType.Application.Json -> response.readMcpBody().takeIf { it.isNotEmpty() }?.let { json ->
                    runCatching { decodeMessage(json) }
                        .onSuccess { _onMessage(it) }
                        .onFailure {
                            _onError(it)
                            throw it
                        }
                }

                ContentType.Text.EventStream -> {
                    val replayMessageId = if (message is JSONRPCRequest) message.id else null
                    val result = readSseResponse(response, replayMessageId, options?.onResumptionToken)
                    if (result.hasPrimingEvent && !result.receivedResponse) {
                        startSseSession(
                            resumptionToken = result.lastEventId,
                            replayMessageId = replayMessageId,
                            onResumptionToken = options?.onResumptionToken,
                            initialServerRetryDelay = result.serverRetryDelay,
                        )
                    }
                }

                else -> {
                    val body = response.readMcpBody()
                    if (response.contentType() == null && body.isBlank()) return@execute

                    val ct = response.contentType()?.toString() ?: "<none>"
                    val error = StreamableHttpError(-1, "Unexpected content type: $ct")
                    _onError(error)
                    throw error
                }
            }
        }
    }

    private fun startSseSession(
        resumptionToken: String? = null,
        replayMessageId: RequestId? = null,
        onResumptionToken: ((String) -> Unit)? = null,
        initialServerRetryDelay: Duration? = null,
    ) {
        // Cancel-and-replace: cancel() signals the previous job, join() inside
        // the new coroutine ensures it completes before we start collecting.
        // This is intentionally non-suspend to avoid blocking performSend.
        val previousJob = sseJob
        previousJob?.cancel()
        sseJob = transportScope.launch(CoroutineName("StreamableHttpTransport.collect#${hashCode()}")) {
            previousJob?.join()
            var lastEventId = resumptionToken
            var serverRetryDelay = initialServerRetryDelay
            var attempt = 0
            var needsDelay = initialServerRetryDelay != null

            while (isActive) {
                // Delay before (re)connection: skip only for first fresh SSE connection
                if (needsDelay) {
                    delay(getNextReconnectionDelay(attempt, serverRetryDelay))
                }
                needsDelay = true

                var connected = false
                val result = try {
                    readSseSession(
                        lastEventId, replayMessageId,
                        onResumptionToken = { id -> lastEventId = id; onResumptionToken?.invoke(id) },
                        onRetry = { serverRetryDelay = it },
                        onConnected = { connected = true; attempt = 0 },
                    ) ?: return@launch
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    val retryable = (error is java.io.IOException &&
                        error !is io.modelcontextprotocol.kotlin.sdk.shared.TooLongFrameException &&
                        error !is java.nio.charset.CharacterCodingException) ||
                        (error is StreamableHttpError &&
                            (error.code == 408 || error.code == 429 || (error.code ?: 0) >= 500))
                    if (!retryable) {
                        _onError(error)
                        return@launch
                    }
                    if (!connected && ++attempt >= reconnectionOptions.maxRetries) {
                        _onError(StreamableHttpError(null, "Maximum reconnection attempts exceeded"))
                        return@launch
                    }
                    continue
                }
                attempt = 0
                lastEventId = result.lastEventId ?: lastEventId
                serverRetryDelay = result.serverRetryDelay ?: serverRetryDelay
                if (result.receivedResponse) break
            }
        }
    }

    private suspend fun readSseSession(
        lastEventId: String?,
        replayMessageId: RequestId?,
        onResumptionToken: (String) -> Unit,
        onRetry: (Duration) -> Unit,
        onConnected: () -> Unit,
    ): SseStreamResult? = client.prepareGet(url) {
        applyCommonHeaders(this)
        headers.append(HttpHeaders.Accept, "${ContentType.Application.Json}, ${ContentType.Text.EventStream}")
        lastEventId?.let { headers.append(MCP_RESUMPTION_TOKEN_HEADER, it) }
        requestBuilder()
    }.execute { response ->
        if (managed && response.status.value == 428) throw McpManagedSnapshotRequired.parse(response.readMcpBody())
        if (response.status == HttpStatusCode.NotFound || response.status == HttpStatusCode.MethodNotAllowed ||
            response.contentType()?.match(ContentType.Application.Json) == true) return@execute null
        if (!response.status.isSuccess() || response.contentType()?.match(ContentType.Text.EventStream) != true) {
            throw StreamableHttpError(response.status.value, "MCP notification stream unavailable")
        }
        onConnected()
        readSseResponse(response, replayMessageId, onResumptionToken, onRetry)
    }

    private fun getNextReconnectionDelay(attempt: Int, serverRetryDelay: Duration?): Duration {
        // Per SSE specification, the server-sent `retry` field sets the reconnection time
        // for all subsequent attempts, taking priority over exponential backoff.
        serverRetryDelay?.let { return it }
        val delay = reconnectionOptions.initialReconnectionDelay *
            reconnectionOptions.reconnectionDelayMultiplier.pow(attempt)
        return delay.coerceAtMost(reconnectionOptions.maxReconnectionDelay)
    }

    private fun decodeMessage(text: String): JSONRPCMessage = catalogWire.decode(text).also { message ->
        val expected = initializeRequest.get()
        if (message is JSONRPCResponse && expected != null && expected == message.id &&
            initializeRequest.compareAndSet(expected, null) &&
            message.result is io.modelcontextprotocol.kotlin.sdk.types.InitializeResult) {
            protocolVersion = (message.result as io.modelcontextprotocol.kotlin.sdk.types.InitializeResult).protocolVersion
        }
    }

    private fun isResponseFor(message: JSONRPCMessage, expected: RequestId?): Boolean {
        val id = when (message) {
            is JSONRPCResponse -> message.id
            is io.modelcontextprotocol.kotlin.sdk.types.JSONRPCError -> message.id
            else -> return false
        }
        return expected != null && expected == id
    }

    private fun applyCommonHeaders(builder: HttpRequestBuilder) {
        builder.headers {
            sessionId?.let { append(MCP_SESSION_ID_HEADER, it) }
            protocolVersion?.let { append(MCP_PROTOCOL_VERSION_HEADER, it) }
        }
    }

    private fun applyStandardPostHeaders(builder: HttpRequestBuilder, message: JSONRPCMessage) {
        val (method, params) = when (message) {
            is JSONRPCRequest -> message.method to message.params
            is JSONRPCNotification -> message.method to message.params
            else -> return
        }

        builder.headers {
            append(MCP_METHOD_HEADER, method)

            val paramsObject = params as? JsonObject ?: return@headers
            val mcpName = paramsObject.stringValue("name") ?: paramsObject.stringValue("uri")
            mcpName?.let { append(MCP_NAME_HEADER, it.encodeMcpHeaderValue()) }
        }
    }

    private fun JsonObject.stringValue(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    @OptIn(ExperimentalEncodingApi::class)
    private fun String.encodeMcpHeaderValue(): String {
        val containsUnsafeCharacters = any { it != '\t' && it.code !in 0x20..0x7e }
        val hasEdgeWhitespace = firstOrNull()?.isWhitespace() == true || lastOrNull()?.isWhitespace() == true
        val matchesBase64Sentinel = startsWith(MCP_BASE64_PREFIX) && endsWith(MCP_BASE64_SUFFIX)

        if (!containsUnsafeCharacters && !hasEdgeWhitespace && !matchesBase64Sentinel) return this

        return "$MCP_BASE64_PREFIX${Base64.Default.encode(encodeToByteArray())}$MCP_BASE64_SUFFIX"
    }

    private suspend fun readSseResponse(
        response: HttpResponse,
        replayMessageId: RequestId?,
        onResumptionToken: ((String) -> Unit)?,
        onRetry: (Duration) -> Unit = {},
    ): SseStreamResult {
        var hasPrimingEvent = false
        var receivedResponse = false
        var localLastEventId: String? = null
        var localServerRetryDelay: Duration? = null
        response.readMcpSseEvents(maxInlineSseEventSize) { event ->
            event.retryMillis?.let { localServerRetryDelay = it.milliseconds; onRetry(it.milliseconds) }
            event.id?.let {
                localLastEventId = it
                hasPrimingEvent = true
                onResumptionToken?.invoke(it)
            }
            if (event.data.isNotBlank()) {
                when (event.name) {
                    null, "message" -> {
                        val message = decodeMessage(event.data)
                        receivedResponse = isResponseFor(message, replayMessageId)
                        _onMessage(message)
                    }
                    "error" -> _onError(StreamableHttpError(null, "MCP SSE error"))
                }
            }
            !receivedResponse
        }
        return SseStreamResult(hasPrimingEvent, receivedResponse, localLastEventId, localServerRetryDelay)
    }
}
