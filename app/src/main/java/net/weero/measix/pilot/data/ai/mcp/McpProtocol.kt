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
import io.modelcontextprotocol.kotlin.sdk.types.McpException
import io.modelcontextprotocol.kotlin.sdk.types.RPCError
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.TimeoutCancellationException

/** Owns the process-shared HTTP client, but no per-server connection state. */
internal class McpProtocolClientFactory(
    createHttpClient: () -> HttpClient,
    private val createManagedHttpClient: () -> HttpClient,
    private val managedProblem: (net.weero.measix.pilot.data.enterprise.RealmAccess.Enterprise, Int, String) -> Throwable? = { _, _, _ -> null },
    private val transportOverride: ((McpServerConfig) -> AbstractTransport)? = null,
    private val clientOverride: ((McpServerConfig) -> Client)? = null,
) {
    private val httpClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED, createHttpClient)
    private val managedHttpClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED, createManagedHttpClient)

    suspend fun createTransport(definition: McpConnectionDefinition): AbstractTransport {
        currentCoroutineContext().ensureActive()
        return when (definition) {
            is McpConnectionDefinition.User -> {
                transportOverride?.let { return it(definition.config) }
                val shared = httpClient
                currentCoroutineContext().ensureActive()
                userTransport(definition.config, shared)
            }
            is McpConnectionDefinition.ManagedPlatform -> {
                val shared = managedHttpClient
                currentCoroutineContext().ensureActive()
                McpStreamableHttpTransport(url = definition.url, client = shared, managed = true,
                    requestHeaders = definition::requestHeaders,
                    managedProblem = { status, body -> managedProblem(definition.access, status, body) })
            }
        }
    }

    fun createClient(definition: McpConnectionDefinition): Client =
        (definition as? McpConnectionDefinition.User)?.let { clientOverride?.invoke(it.config) } ?: Client(
            clientInfo = Implementation(name = definition.namespace, version = "1.0"),
            options = ClientOptions(capabilities = ClientCapabilities()),
        )

    private fun userTransport(config: McpServerConfig, httpClient: HttpClient): AbstractTransport {
        val customHeaders = StringValues.build {
            config.resolvedConnectionHeaders().forEach { append(it.first, it.second) }
        }
        return when (config) {
            is McpServerConfig.SseTransportServer -> McpSseTransport(
                urlString = config.url, client = httpClient,
                requestBuilder = { headers.appendAll(customHeaders) },
            )
            is McpServerConfig.StreamableHTTPServer -> McpStreamableHttpTransport(
                url = config.url, client = httpClient,
                requestBuilder = { headers.appendAll(customHeaders) },
            )
        }
    }
}

/** Shared protocol failure classification used by lifecycle and invocation execution. */
internal object McpProtocolFailureClassifier {
    private fun causes(error: Throwable): Sequence<Throwable> = generateSequence(error) { it.cause }

    private fun retryableHttpCode(code: Int?): Boolean =
        code == 404 || code == 408 || code == 425 || code == 429 ||
            (code != null && code in 500..599)

    fun httpCode(error: Throwable): String {
        val code = causes(error).firstNotNullOfOrNull {
            when (it) {
                is StreamableHttpError -> it.code
                is McpOAuthResponseException -> it.statusCode
                else -> null
            }
        }
        return code?.takeIf { it in 100..599 }?.let { "HTTP $it" } ?: ""
    }

    fun isUnauthorized(error: Throwable): Boolean {
        val http = causes(error).firstOrNull {
            it is StreamableHttpError || it is McpOAuthResponseException
        }
        return when (http) {
            is StreamableHttpError -> http.code == 401
            is McpOAuthResponseException -> http.statusCode == 401 ||
                http.errorCode == "invalid_token" || http.errorCode == "invalid_grant"
            else -> false
        }
    }

    fun isConnectionError(error: Throwable): Boolean {
        if (net.weero.measix.pilot.data.enterprise.EnterpriseRuntimeProblemException.find(error) != null) return false
        if (isUnauthorized(error)) return false
        val http = causes(error).firstOrNull {
            it is StreamableHttpError || it is McpOAuthResponseException
        }
        if (http != null) {
            return when (http) {
                is StreamableHttpError -> retryableHttpCode(http.code)
                is McpOAuthResponseException -> http.statusCode != 404 && retryableHttpCode(http.statusCode)
                else -> false
            }
        }
        return causes(error).any {
            it is java.io.IOException || it is TimeoutCancellationException ||
                it is McpException &&
                (it.code == RPCError.ErrorCode.CONNECTION_CLOSED ||
                    it.code == RPCError.ErrorCode.REQUEST_TIMEOUT)
        }
    }

    fun isSseStreamGiveUp(error: Throwable): Boolean =
        causes(error).any { it is McpNotificationStreamExhausted }
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
