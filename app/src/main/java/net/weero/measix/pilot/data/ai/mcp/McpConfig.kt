package net.weero.measix.pilot.data.ai.mcp

import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.uuid.Uuid

@Serializable
data class McpCommonOptions(
    val enable: Boolean = true,
    val name: String = "",
    val headers: List<Pair<String, String>> = emptyList(),
    val tools: List<McpTool> = emptyList(),
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
        "McpOAuthState(enabled=$enabled, clientId=$clientId, clientSecret=${clientSecret.masked()}, " +
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
data class McpTool(
    val enable: Boolean = true,
    val name: String = "",
    val description: String? = null,
    val inputSchema: JsonObject? = null,
    val needsApproval: Boolean = false
)

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

/**
 * 将服务器端返回的工具列表与本地缓存的工具列表合并：
 * - 新工具添加（默认 enable=true）
 * - 已有工具更新 description/inputSchema，保留 enable/needsApproval
 * - 服务器已删除的工具从列表移除
 */
fun mergeTools(
    serverTools: List<Tool>,
    localTools: List<McpTool>,
): List<McpTool> {
    val result = mutableListOf<McpTool>()
    val localByName = localTools.associateBy { it.name }

    serverTools.forEach { serverTool ->
        val existing = localByName[serverTool.name]
        if (existing == null) {
            result.add(
                McpTool(
                    name = serverTool.name,
                    description = serverTool.description,
                    enable = true,
                    inputSchema = serverTool.inputSchema.toSchema(),
                )
            )
        } else {
            result.add(
                existing.copy(
                    description = serverTool.description,
                    inputSchema = serverTool.inputSchema.toSchema(),
                )
            )
        }
    }

    return result
}

private fun io.modelcontextprotocol.kotlin.sdk.types.ToolSchema.toSchema(): JsonObject =
    Json.encodeToJsonElement(
        io.modelcontextprotocol.kotlin.sdk.types.ToolSchema.serializer(),
        this,
    ).jsonObject

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
