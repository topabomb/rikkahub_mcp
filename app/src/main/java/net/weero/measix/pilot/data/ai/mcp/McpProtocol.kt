package net.weero.measix.pilot.data.ai.mcp

import io.ktor.client.HttpClient
import io.ktor.util.StringValues
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.PaginatedRequestParams
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpError
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Owns the process-shared HTTP client, but no per-server connection state. */
internal class McpProtocolClientFactory(
    createHttpClient: () -> HttpClient,
    private val transportOverride: ((McpServerConfig) -> AbstractTransport)? = null,
    private val clientOverride: ((McpServerConfig) -> Client)? = null,
) {
    private val httpClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED, createHttpClient)

    suspend fun createTransport(config: McpServerConfig): AbstractTransport {
        currentCoroutineContext().ensureActive()
        transportOverride?.let { return it(config) }
        val sharedClient = httpClient
        currentCoroutineContext().ensureActive()
        return defaultTransport(config, sharedClient)
    }

    fun createClient(config: McpServerConfig): Client =
        clientOverride?.invoke(config) ?: Client(
            clientInfo = Implementation(name = config.commonOptions.name, version = "1.0"),
            options = ClientOptions(capabilities = ClientCapabilities()),
        )

    private fun defaultTransport(config: McpServerConfig, httpClient: HttpClient): AbstractTransport {
        val customHeaders = StringValues.build {
            config.resolvedConnectionHeaders().forEach { append(it.first, it.second) }
        }
        return when (config) {
            is McpServerConfig.SseTransportServer -> McpSseTransport(
                urlString = config.url,
                client = httpClient,
                requestBuilder = { headers.appendAll(customHeaders) },
            )
            is McpServerConfig.StreamableHTTPServer -> McpStreamableHttpTransport(
                url = config.url,
                client = httpClient,
                requestBuilder = { headers.appendAll(customHeaders) },
            )
        }
    }
}

/** Shared protocol failure classification used by lifecycle and invocation execution. */
internal object McpProtocolFailureClassifier {
    fun httpCode(error: Throwable): String {
        val httpError = generateSequence(error) { it.cause }
            .filterIsInstance<StreamableHttpError>()
            .firstOrNull()
        return httpError?.code?.let { "HTTP $it" } ?: ""
    }

    fun isUnauthorized(error: Throwable): Boolean {
        val httpError = generateSequence(error) { it.cause }
            .filterIsInstance<StreamableHttpError>()
            .firstOrNull()
        if (httpError?.code == 401) return true
        val message = generateSequence(error) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
            .lowercase()
        return message.contains("401") ||
            message.contains("unauthorized") ||
            message.contains("invalid_token") ||
            message.contains("invalid access token") ||
            message.contains("missing or invalid") ||
            message.contains("missing required authorization")
    }

    fun isConnectionError(error: Throwable): Boolean {
        if (isUnauthorized(error)) return false
        if (error is StreamableHttpError) {
            return error.code == 404 || error.code == 408 || error.code == 425 ||
                error.code == 429 || error.code in 500..599
        }
        return error is java.io.IOException ||
            error.message?.contains("connection", ignoreCase = true) == true ||
            error.message?.contains("timeout", ignoreCase = true) == true ||
            error.message?.contains("closed", ignoreCase = true) == true
    }

    fun isSseStreamGiveUp(error: Throwable): Boolean = generateSequence(error) { it.cause }
        .mapNotNull { it.message }
        .joinToString(" ")
        .contains("Maximum reconnection attempts exceeded", ignoreCase = true)
}

/** Performs complete, bounded tools/list pagination and returns an uncommitted candidate. */
internal object McpCatalogDiscovery {
    private const val MAX_TOOL_PAGES = 64
    private const val MAX_TOOL_COUNT = 4096

    suspend fun fetchCandidate(
        key: McpCatalogKey,
        definitionDigest: String,
        client: Client,
        managed: McpManagedCatalog? = null,
    ): McpCatalogCandidate {
        checkNotNull(client.serverCapabilities?.tools) { "MCP server does not declare tools capability" }
        val tools = mutableListOf<McpCatalogTool>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        var page = 0
        do {
            check(page++ < MAX_TOOL_PAGES) { "MCP tools/list exceeded $MAX_TOOL_PAGES pages" }
            val result = McpToolPageCapture.read {
                client.listTools(ListToolsRequest(params = cursor?.let(::PaginatedRequestParams)))
            }
            result.tools.forEach { tool ->
                check(tool.name.isNotBlank()) { "MCP catalog contains a blank tool name" }
                check(tools.none { it.name == tool.name }) {
                    "MCP catalog contains duplicate tool '${tool.name}'"
                }
                check(tools.size < MAX_TOOL_COUNT) { "MCP catalog exceeded $MAX_TOOL_COUNT tools" }
                tools += tool
            }
            cursor = result.nextCursor
            if (cursor != null) check(seenCursors.add(cursor)) { "MCP tools/list repeated cursor" }
        } while (cursor != null)

        return McpCatalogCandidate(
            scope = key.scope,
            serverId = key.serverId,
            definitionDigest = definitionDigest,
            tools = tools,
            managed = managed,
        )
    }
}
