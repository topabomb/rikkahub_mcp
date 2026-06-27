package net.weero.measix.pilot.data.ai.mcp

import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import kotlin.uuid.Uuid

@Serializable
data class McpCommonOptions(
    val enable: Boolean = true,
    val name: String = "",
    val headers: List<Pair<String, String>> = emptyList(),
    val tools: List<McpTool> = emptyList()
)

@Serializable
data class McpTool(
    val enable: Boolean = true,
    val name: String = "",
    val description: String? = null,
    val inputSchema: InputSchema? = null,
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

    /**
     * 连接标识：用于判断两个配置是否需要重建连接。
     * 包含影响连接的关键字段（传输类型、URL、headers），
     * 不包含 tools（由 sync 自动更新）和 name（不影响连接）。
     * 修改这些字段会触发 McpManager 断开旧连接并建立新连接。
     */
    abstract val connectionKey: ConnectionKey

    data class ConnectionKey(
        val id: Uuid,
        val transportType: String,
        val url: String,
        val headers: List<Pair<String, String>>,
    )

    @Serializable
    @SerialName("sse")
    data class SseTransportServer(
        override val id: Uuid = Uuid.random(),
        override val commonOptions: McpCommonOptions = McpCommonOptions(),
        val url: String = "",
    ) : McpServerConfig() {
        override val connectionKey: ConnectionKey
            get() = ConnectionKey(id, "sse", url, commonOptions.headers)

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
        override val connectionKey: ConnectionKey
            get() = ConnectionKey(id, "streamable_http", url, commonOptions.headers)

        override fun clone(id: Uuid, commonOptions: McpCommonOptions): McpServerConfig {
            return copy(id = id, commonOptions = commonOptions)
        }
    }
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

private fun io.modelcontextprotocol.kotlin.sdk.types.ToolSchema.toSchema(): InputSchema {
    return InputSchema.Obj(properties = this.properties ?: JsonObject(emptyMap()), required = this.required)
}

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
