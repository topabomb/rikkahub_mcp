// Modified from modelcontextprotocol/kotlin-sdk 0.15.0; upstream license is bundled in assets/licenses.
// Source and local changes: docs/references/mcp-architecture.md.
package net.weero.measix.pilot.data.ai.mcp

import io.modelcontextprotocol.kotlin.sdk.client.ReconnectionOptions
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpError

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.plugins.sse.ClientSSESession
import io.ktor.client.plugins.sse.SSEClientException
import io.ktor.client.plugins.sse.sseSession
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.headers
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.charsets.TooLongLineException
import io.ktor.utils.io.readUTF8Line
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractClientTransport
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
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

private sealed interface ConnectResult {
    data class Success(val session: ClientSSESession) : ConnectResult
    data object NonRetryable : ConnectResult
    data object Failed : ConnectResult
}

/**
 * Client transport implementing the MCP Streamable HTTP transport specification.
 *
 * Sends messages via HTTP POST and receives messages via HTTP GET with Server-Sent Events.
 * Supports automatic SSE reconnection with exponential backoff, stream resumption via the
 * `Last-Event-ID` header, and explicit session termination.
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
    private val reconnectionOptions: ReconnectionOptions = ReconnectionOptions(),
    private val maxInlineSseEventSize: Int = DEFAULT_MAX_INLINE_SSE_EVENT_SIZE,
    private val requestBuilder: HttpRequestBuilder.() -> Unit = {},
) : AbstractClientTransport() {

    init {
        require(maxInlineSseEventSize > 0) { "maxInlineSseEventSize must be greater than 0" }
    }

    private val catalogWire = McpCatalogWire()
    private val initializeRequest = java.util.concurrent.atomic.AtomicReference<RequestId?>()

    override val logger: KLogger = KotlinLogging.logger {}

    /** Session identifier assigned by the server after initialization, or `null` before connection. */
    private var sessionId: String? = null

    /** MCP protocol version negotiated with the server, or `null` before connection. */
    @Volatile private var protocolVersion: String? = null

    private var sseJob: Job? = null

    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    /** Result of an SSE stream collection. Reconnect when [hasPrimingEvent] is true and [receivedResponse] is false. */
    private data class SseStreamResult(
        val hasPrimingEvent: Boolean,
        val receivedResponse: Boolean,
        val lastEventId: String? = null,
        val serverRetryDelay: Duration? = null,
    )

    override suspend fun initialize() {
        logger.debug { "Client transport is starting..." }
    }

    /**
     * Sends a single message with optional resumption support
     */
    override suspend fun performSend(message: JSONRPCMessage, options: TransportSendOptions?) {
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
                val error = StreamableHttpError(response.status.value, response.readMcpBody())
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
                    val result = handleInlineSse(response, replayMessageId, options?.onResumptionToken)
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

    override suspend fun closeResources() {
        catalogWire.close()
        logger.debug { "Client transport closing." }
        sseJob?.cancelAndJoin()
        scope.cancel()
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
        sseJob = scope.launch(CoroutineName("StreamableHttpTransport.collect#${hashCode()}")) {
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

                // Connect
                val session = when (val cr = connectSse(lastEventId)) {
                    is ConnectResult.Success -> {
                        attempt = 0
                        cr.session
                    }

                    ConnectResult.NonRetryable -> return@launch

                    ConnectResult.Failed -> {
                        // Give up after maxRetries consecutive failed connection attempts
                        if (++attempt >= reconnectionOptions.maxRetries) {
                            _onError(StreamableHttpError(null, "Maximum reconnection attempts exceeded"))
                            return@launch
                        }
                        continue
                    }
                }

                // Collect
                val result = collectSse(session, replayMessageId, onResumptionToken)
                lastEventId = result.lastEventId ?: lastEventId
                serverRetryDelay = result.serverRetryDelay ?: serverRetryDelay
                if (result.receivedResponse) break
            }
        }
    }

    private suspend fun connectSse(lastEventId: String?): ConnectResult {
        logger.debug { "Client attempting to start SSE session at url: $url" }
        return try {
            val session = client.sseSession(urlString = url, showRetryEvents = true) {
                method = HttpMethod.Get
                applyCommonHeaders(this)
                accept(ContentType.Application.Json)
                lastEventId?.let { headers.append(MCP_RESUMPTION_TOKEN_HEADER, it) }
                requestBuilder()
            }
            logger.debug { "Client SSE session started successfully." }
            ConnectResult.Success(session)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SSEClientException) {
            if (isNonRetryableSseError(e)) {
                ConnectResult.NonRetryable
            } else {
                logger.debug { "SSE connection failed: ${e.message}" }
                ConnectResult.Failed
            }
        } catch (e: Exception) {
            logger.debug { "SSE connection failed: ${e.message}" }
            ConnectResult.Failed
        }
    }

    private fun getNextReconnectionDelay(attempt: Int, serverRetryDelay: Duration?): Duration {
        // Per SSE specification, the server-sent `retry` field sets the reconnection time
        // for all subsequent attempts, taking priority over exponential backoff.
        serverRetryDelay?.let { return it }
        val delay = reconnectionOptions.initialReconnectionDelay *
            reconnectionOptions.reconnectionDelayMultiplier.pow(attempt)
        return delay.coerceAtMost(reconnectionOptions.maxReconnectionDelay)
    }

    /**
     * Checks if an SSE session error is non-retryable (404, 405, JSON-only).
     * Returns `true` if non-retryable (should stop trying), `false` otherwise.
     */
    private fun isNonRetryableSseError(e: SSEClientException): Boolean {
        val responseStatus = e.response?.status
        val responseContentType = e.response?.contentType()

        return when {
            responseStatus == HttpStatusCode.NotFound || responseStatus == HttpStatusCode.MethodNotAllowed -> {
                logger.info { "Server returned ${responseStatus.value} for GET/SSE, stream disabled." }
                true
            }

            responseContentType?.match(ContentType.Application.Json) == true -> {
                logger.info { "Server returned application/json for GET/SSE, using JSON-only mode." }
                true
            }

            else -> false
        }
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

    private suspend fun collectSse(
        session: ClientSSESession,
        replayMessageId: RequestId?,
        onResumptionToken: ((String) -> Unit)?,
    ): SseStreamResult {
        var hasPrimingEvent = false
        var receivedResponse = false
        var localLastEventId: String? = null
        var localServerRetryDelay: Duration? = null
        try {
            session.incoming.collect { event ->
                event.retry?.let { localServerRetryDelay = it.milliseconds }
                event.id?.let {
                    localLastEventId = it
                    hasPrimingEvent = true
                    onResumptionToken?.invoke(it)
                }
                when (event.event) {
                    null, "message" ->
                        event.data?.takeIf { it.isNotEmpty() }?.let { json ->
                            runCatching { decodeMessage(json) }
                                .onSuccess { msg ->
                                    if (isResponseFor(msg, replayMessageId)) receivedResponse = true
                                    _onMessage(msg)
                                }
                                .onFailure(_onError)
                        }

                    "error" -> _onError(StreamableHttpError(null, event.data))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            _onError(t)
        }
        return SseStreamResult(hasPrimingEvent, receivedResponse, localLastEventId, localServerRetryDelay)
    }

    private suspend fun handleInlineSse(
        response: HttpResponse,
        replayMessageId: RequestId?,
        onResumptionToken: ((String) -> Unit)?,
    ): SseStreamResult {
        logger.trace { "Handling inline SSE from POST response" }
        val channel = response.bodyAsChannel()

        var hasPrimingEvent = false
        var receivedResponse = false
        var localLastEventId: String? = null
        var localServerRetryDelay: Duration? = null
        val sb = StringBuilder()
        var id: String? = null
        var eventName: String? = null

        suspend fun dispatch(id: String?, eventName: String?, data: String) {
            id?.let {
                localLastEventId = it
                hasPrimingEvent = true
                onResumptionToken?.invoke(it)
            }
            if (data.isBlank()) {
                return
            }
            if (eventName == null || eventName == "message") {
                runCatching { decodeMessage(data) }
                    .onSuccess { msg ->
                        if (isResponseFor(msg, replayMessageId)) receivedResponse = true
                        _onMessage(msg)
                    }
                    .onFailure {
                        _onError(it)
                        throw it
                    }
            }
            if (eventName == "error") {
                _onError(StreamableHttpError(null, data))
                return
            }
        }

        while (!channel.isClosedForRead && !receivedResponse) {
            // Bound each line so a server that streams a line without ever terminating it cannot
            // exhaust client memory; readUTF8Line returns null at the end of the stream.
            val line = try {
                channel.readUTF8Line(maxInlineSseEventSize)
            } catch (_: TooLongLineException) {
                throw TooLongFrameException(maxInlineSseEventSize.toLong() + 1, maxInlineSseEventSize)
            }
            if (line == null) break
            if (line.isEmpty()) {
                dispatch(id = id, eventName = eventName, data = sb.toString().removeSuffix("\n"))
                // reset
                id = null
                eventName = null
                sb.clear()
                continue
            }
            val separator = line.indexOf(':')
            val field = if (separator < 0) line else line.substring(0, separator)
            val value = if (separator < 0) "" else line.substring(separator + 1).removePrefix(" ")
            when (field) {
                "id" -> if ('\u0000' !in value) id = value
                "event" -> eventName = value
                "data" -> {
                    sb.append(value).append('\n')
                    if (sb.length > maxInlineSseEventSize) {
                        throw TooLongFrameException(sb.length.toLong(), maxInlineSseEventSize)
                    }
                }
                "retry" -> if (value.isNotEmpty() && value.all { it in '0'..'9' }) {
                    value.toLongOrNull()?.let { localServerRetryDelay = it.milliseconds }
                }
            }
        }
        return SseStreamResult(hasPrimingEvent, receivedResponse, localLastEventId, localServerRetryDelay)
    }
}
