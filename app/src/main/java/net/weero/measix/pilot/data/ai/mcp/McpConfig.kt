package net.weero.measix.pilot.data.ai.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.uuid.Uuid

/** Canonical identity of fields that require replacing a live MCP transport. */
internal data class McpConnectionFingerprint(
    val transportType: String,
    val serverUrl: String,
    val clientName: String,
    val headers: List<Pair<String, String>>,
)

/** Stable identity of the remote principal to which OAuth credentials may be persisted. */
internal data class McpOAuthTrustBoundary(
    val transportType: String,
    val canonicalResource: String,
    val headers: List<Pair<String, String>>,
)

internal fun McpServerConfig.connectionFingerprint(): McpConnectionFingerprint = McpConnectionFingerprint(
    transportType = when (this) {
        is McpServerConfig.SseTransportServer -> "sse"
        is McpServerConfig.StreamableHTTPServer -> "streamable_http"
    },
    serverUrl = serverUrl,
    clientName = commonOptions.name,
    headers = resolvedConnectionHeaders(),
)

/** Stable definition identity; bearer-token rotation does not invalidate a durable catalog. */
internal fun McpServerConfig.mcpDefinitionDigest(): String = connectionFingerprint().let { fingerprint ->
    sha256(
        buildString {
            append(fingerprint.transportType).append('\u0000')
            append(fingerprint.serverUrl).append('\u0000')
            append(fingerprint.clientName).append('\u0000')
            commonOptions.headers
                .sortedWith(compareBy<Pair<String, String>> { it.first.lowercase() }.thenBy { it.second })
                .forEach { (name, value) ->
                    append(name.lowercase()).append(':').append(value).append('\u0000')
                }
        }
    )
}

internal fun McpServerConfig.resolvedConnectionHeaders(): List<Pair<String, String>> {
    val base = commonOptions.headers
    val token = commonOptions.oauth?.takeIf { it.enabled }?.accessToken
    val hasAuthorization = base.any { it.first.equals("Authorization", ignoreCase = true) }
    return if (!token.isNullOrBlank() && !hasAuthorization) {
        base + ("Authorization" to "Bearer $token")
    } else {
        base
    }
}

internal fun McpServerConfig.oauthTrustBoundary(): McpOAuthTrustBoundary = McpOAuthTrustBoundary(
    transportType = when (this) {
        is McpServerConfig.SseTransportServer -> "sse"
        is McpServerConfig.StreamableHTTPServer -> "streamable_http"
    },
    canonicalResource = McpOAuthClient.canonicalResource(serverUrl),
    headers = commonOptions.headers
        .map { (name, value) -> name.lowercase() to value }
        .sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second }),
)

@Serializable
data class McpCommonOptions(
    val enable: Boolean = true,
    val name: String = "",
    val headers: List<Pair<String, String>> = emptyList(),
    @SerialName("tools")
    val toolPolicies: List<McpToolPolicy> = emptyList(),
    val oauth: McpOAuthState? = null,
)

/**
 * OAuth 2.1 授权状态，遵循 MCP 授权规范 (2025-11-25)。
 *
 * 持久化了动态客户端注册结果、授权服务器端点以及令牌，用于对需要
 * OAuth 授权的 MCP Server 注入 `Authorization: Bearer` 请求头并支持刷新。
 */
@Serializable
data class McpOAuthState(
    val revision: Long = 0L,
    val enabled: Boolean = false,
    val clientId: String? = null,
    val clientSecret: String? = null,
    val authorizationEndpoint: String? = null,
    val tokenEndpoint: String? = null,
    val registrationEndpoint: String? = null,
    val scope: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val expiresAt: Long = 0L, // epoch millis, 0 表示未知/不过期
) {
    val isAuthorized: Boolean get() = !accessToken.isNullOrBlank()

    // 脱敏 toString，避免 client_secret / token 随 config 打印到日志
    override fun toString(): String =
        "McpOAuthState(revision=$revision, enabled=$enabled, clientId=$clientId, clientSecret=${clientSecret.masked()}, " +
            "authorizationEndpoint=$authorizationEndpoint, tokenEndpoint=$tokenEndpoint, " +
            "registrationEndpoint=$registrationEndpoint, scope=$scope, " +
            "accessToken=${accessToken.masked()}, refreshToken=${refreshToken.masked()}, expiresAt=$expiresAt)"

    private fun String?.masked(): String = when {
        this == null -> "null"
        isBlank() -> "***"
        else -> "***(${length})"
    }
}

@Serializable
data class McpToolPolicy(
    val enable: Boolean = true,
    val name: String = "",
    val needsApproval: Boolean = false
)

/**
 * MCP 配置身份的唯一规范化协议。按持久化顺序保留第一个 server id、规范化名称和工具策略，
 * 使读取投影、写入以及运行时策略解析不会对同一份损坏数据采用不同胜者。
 */
internal fun List<McpServerConfig>.normalizeMcpDefinitions(): List<McpServerConfig> {
    val seenIds = hashSetOf<Uuid>()
    val seenNames = hashSetOf<String>()
    return mapNotNull { server ->
        val normalizedName = server.commonOptions.name.trim().lowercase()
        if (!seenIds.add(server.id) || !seenNames.add(normalizedName)) return@mapNotNull null
        server.clone(
            commonOptions = server.commonOptions.copy(
                toolPolicies = server.commonOptions.toolPolicies.distinctBy { it.name },
            )
        )
    }
}

internal fun McpCommonOptions.toolPolicyByName(): Map<String, McpToolPolicy> =
    toolPolicies.distinctBy { it.name }.associateBy { it.name }

@Serializable
sealed class McpServerConfig {
    abstract val id: Uuid
    abstract val commonOptions: McpCommonOptions

    abstract fun clone(
        id: Uuid = this.id,
        commonOptions: McpCommonOptions = this.commonOptions
    ): McpServerConfig

    @Serializable
    @SerialName("sse")
    data class SseTransportServer(
        override val id: Uuid = Uuid.random(),
        override val commonOptions: McpCommonOptions = McpCommonOptions(),
        val url: String = "",
    ) : McpServerConfig() {
        override fun clone(id: Uuid, commonOptions: McpCommonOptions): McpServerConfig {
            return copy(id = id, commonOptions = commonOptions)
        }
    }

    @Serializable
    @SerialName("streamable_http")
    data class StreamableHTTPServer(
        override val id: Uuid = Uuid.random(),
        override val commonOptions: McpCommonOptions = McpCommonOptions(),
        val url: String = "",
    ) : McpServerConfig() {
        override fun clone(id: Uuid, commonOptions: McpCommonOptions): McpServerConfig {
            return copy(id = id, commonOptions = commonOptions)
        }
    }
}

/** MCP Server 的连接地址（作为 OAuth 的 canonical resource 标识）。 */
val McpServerConfig.serverUrl: String
    get() = when (this) {
        is McpServerConfig.SseTransportServer -> url
        is McpServerConfig.StreamableHTTPServer -> url
    }

/** OAuth secrets may survive an editor/import merge only inside the same transport trust boundary. */
internal fun McpServerConfig.hasSameOAuthTrustBoundary(other: McpServerConfig): Boolean =
    oauthTrustBoundary() == other.oauthTrustBoundary()

/**
 * JSON 解析结果。
 * @param servers 成功解析的可导入服务器列表
 * @param unsupportedNames 因 type=local 等原因无法导入的服务器名称列表
 */
data class McpParseResult(
    val servers: List<McpServerConfig>,
    val unsupportedNames: List<String>,
)

/**
 * 从 JSON 解析 MCP 服务器配置，支持两种格式：
 *
 * 1. 项目原有格式（mcpServers 包裹）：
 *    { "mcpServers": { "name": { "type": "streamable_http|sse", "url": "...", "headers": {...} } } }
 *
 * 2. OpenCode 格式（外层 key 为服务器名，type=remote）：
 *    { "my-server": { "type": "remote", "url": "...", "headers": {...}, "oauth": false } }
 *
 * OpenCode 的 type=remote 默认转为 StreamableHTTPServer（MCP 当前标准传输）。
 * type=local 的条目无法导入（不支持本地进程），收集到 unsupportedNames。
 * 缺少 url 的条目会被跳过。
 */
fun parseMcpServersFromJson(json: String): McpParseResult {
    val root = Json.parseToJsonElement(json).jsonObject
    val serverEntries = if (root["mcpServers"] != null) {
        root["mcpServers"]!!.jsonObject
    } else {
        root
    }

    val servers = mutableListOf<McpServerConfig>()
    val unsupported = mutableListOf<String>()

    serverEntries.entries.forEach { (name, element) ->
        val obj = element.jsonObject
        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: "streamable_http"
        val url = obj["url"]?.jsonPrimitive?.contentOrNull

        when (type) {
            "local" -> {
                unsupported.add(name)
                return@forEach
            }
            else -> {
                if (url.isNullOrBlank()) return@forEach
                val headers = obj["headers"]?.jsonObject?.entries?.map { (k, v) ->
                    k to (v.jsonPrimitive.contentOrNull ?: "")
                } ?: emptyList()
                val commonOptions = McpCommonOptions(name = name, headers = headers)
                val config = when (type) {
                    "sse" -> McpServerConfig.SseTransportServer(commonOptions = commonOptions, url = url)
                    else -> McpServerConfig.StreamableHTTPServer(commonOptions = commonOptions, url = url)
                }
                servers.add(config)
            }
        }
    }

    return McpParseResult(servers = servers, unsupportedNames = unsupported)
}

/**
 * 将单个 MCP 服务器配置编码为可分享的 JSON 字符串。
 * 格式与 parseMcpServersFromJson 兼容（OpenCode 风格，外层 key 为服务器名）。
 * 不含 id/tools/enable（导入时自动生成/获取）。
 */
fun McpServerConfig.encodeForShare(): String {
    val type = when (this) {
        is McpServerConfig.SseTransportServer -> "sse"
        is McpServerConfig.StreamableHTTPServer -> "streamable_http"
    }
    val url = when (this) {
        is McpServerConfig.SseTransportServer -> this.url
        is McpServerConfig.StreamableHTTPServer -> this.url
    }
    val serverObj = buildJsonObject {
        put("type", type)
        put("url", url)
        if (commonOptions.headers.isNotEmpty()) {
            put("headers", buildJsonObject {
                commonOptions.headers.forEach { (k, v) -> put(k, v) }
            })
        }
    }
    val root = buildJsonObject {
        put(commonOptions.name.ifBlank { "mcp_server" }, serverObj)
    }
    return Json.encodeToString(JsonObject.serializer(), root)
}
