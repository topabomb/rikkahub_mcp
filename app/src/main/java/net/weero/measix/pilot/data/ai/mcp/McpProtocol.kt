package net.weero.measix.pilot.data.ai.mcp

import io.ktor.client.HttpClient
import io.ktor.util.StringValues
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.PaginatedRequestParams
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** Creates MCP SDK clients and transports; it owns no runtime connection state. */
internal class McpProtocolClientFactory(
    private val httpClient: HttpClient,
    private val transportOverride: ((McpServerConfig) -> AbstractTransport)? = null,
    private val clientOverride: ((McpServerConfig) -> Client)? = null,
) {
    fun createTransport(config: McpServerConfig): AbstractTransport =
        transportOverride?.invoke(config) ?: defaultTransport(config)

    fun createClient(config: McpServerConfig): Client =
        clientOverride?.invoke(config) ?: Client(
            clientInfo = Implementation(name = config.commonOptions.name, version = "1.0"),
            options = ClientOptions(capabilities = ClientCapabilities()),
        )

    private fun defaultTransport(config: McpServerConfig): AbstractTransport {
        val customHeaders = StringValues.build {
            config.resolvedConnectionHeaders().forEach { append(it.first, it.second) }
        }
        return when (config) {
            is McpServerConfig.SseTransportServer -> SseClientTransport(
                urlString = config.url,
                client = httpClient,
                requestBuilder = { headers.appendAll(customHeaders) },
            )
            is McpServerConfig.StreamableHTTPServer -> StreamableHttpClientTransport(
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

    suspend fun fetchCandidate(config: McpServerConfig, client: Client): McpCatalogCandidate {
        checkNotNull(client.serverCapabilities?.tools) { "MCP server does not declare tools capability" }
        val tools = mutableListOf<McpCatalogTool>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        var page = 0
        do {
            check(page++ < MAX_TOOL_PAGES) { "MCP tools/list exceeded $MAX_TOOL_PAGES pages" }
            val result = client.listTools(ListToolsRequest(params = cursor?.let(::PaginatedRequestParams)))
            result.tools.forEach { tool ->
                check(tool.name.isNotBlank()) { "MCP catalog contains a blank tool name" }
                check(tools.none { it.name == tool.name }) {
                    "MCP catalog contains duplicate tool '${tool.name}'"
                }
                check(tools.size < MAX_TOOL_COUNT) { "MCP catalog exceeded $MAX_TOOL_COUNT tools" }
                tools += McpCatalogTool(
                    name = tool.name,
                    description = tool.description,
                    inputSchema = Json.encodeToJsonElement(
                        io.modelcontextprotocol.kotlin.sdk.types.ToolSchema.serializer(),
                        tool.inputSchema,
                    ).jsonObject,
                )
            }
            cursor = result.nextCursor
            if (cursor != null) check(seenCursors.add(cursor)) { "MCP tools/list repeated cursor" }
        } while (cursor != null)

        return McpCatalogCandidate(
            serverId = config.id,
            definitionDigest = config.mcpDefinitionDigest(),
            tools = tools,
        )
    }
}
