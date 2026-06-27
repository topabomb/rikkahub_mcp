package net.weero.measix.pilot.data.ai.mcp

import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import net.weero.measix.pilot.utils.checkDifferent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class McpConfigTest {

    // ==================== connectionKey 测试 ====================

    @Test
    fun `connectionKey should be equal when only tools differ`() {
        val id = Uuid.random()
        val config1 = McpServerConfig.StreamableHTTPServer(
            id = id,
            url = "https://example.com/mcp",
            commonOptions = McpCommonOptions(tools = listOf(McpTool(name = "tool_a")))
        )
        val config2 = McpServerConfig.StreamableHTTPServer(
            id = id,
            url = "https://example.com/mcp",
            commonOptions = McpCommonOptions(tools = listOf(McpTool(name = "tool_b")))
        )
        assertEquals(config1.connectionKey, config2.connectionKey)
    }

    @Test
    fun `connectionKey should differ when url changes`() {
        val id = Uuid.random()
        val config1 = McpServerConfig.StreamableHTTPServer(id = id, url = "https://old.com/mcp")
        val config2 = McpServerConfig.StreamableHTTPServer(id = id, url = "https://new.com/mcp")
        assert(config1.connectionKey != config2.connectionKey)
    }

    @Test
    fun `connectionKey should differ when headers change`() {
        val id = Uuid.random()
        val config1 = McpServerConfig.StreamableHTTPServer(
            id = id,
            url = "https://example.com/mcp",
            commonOptions = McpCommonOptions(headers = listOf("Authorization" to "Bearer old"))
        )
        val config2 = McpServerConfig.StreamableHTTPServer(
            id = id,
            url = "https://example.com/mcp",
            commonOptions = McpCommonOptions(headers = listOf("Authorization" to "Bearer new"))
        )
        assert(config1.connectionKey != config2.connectionKey)
    }

    @Test
    fun `connectionKey should differ when transport type changes`() {
        val id = Uuid.random()
        val config1 = McpServerConfig.StreamableHTTPServer(id = id, url = "https://example.com/mcp")
        val config2 = McpServerConfig.SseTransportServer(id = id, url = "https://example.com/mcp")
        assert(config1.connectionKey != config2.connectionKey)
    }

    @Test
    fun `connectionKey should be equal when name changes`() {
        val id = Uuid.random()
        val config1 = McpServerConfig.StreamableHTTPServer(
            id = id, url = "https://example.com/mcp",
            commonOptions = McpCommonOptions(name = "old_name")
        )
        val config2 = McpServerConfig.StreamableHTTPServer(
            id = id, url = "https://example.com/mcp",
            commonOptions = McpCommonOptions(name = "new_name")
        )
        assertEquals(config1.connectionKey, config2.connectionKey)
    }

    // ==================== checkDifferent + connectionKey 集成测试 ====================

    @Test
    fun `checkDifferent should detect url change as remove plus add`() {
        val id = Uuid.random()
        val oldConfig = McpServerConfig.StreamableHTTPServer(id = id, url = "https://old.com/mcp")
        val newConfig = McpServerConfig.StreamableHTTPServer(id = id, url = "https://new.com/mcp")

        val (toAdd, toRemove) = listOf(oldConfig).checkDifferent(
            other = listOf(newConfig),
            eq = { a, b -> a.connectionKey == b.connectionKey }
        )

        assertEquals(1, toAdd.size)
        assertEquals(1, toRemove.size)
        assertEquals(newConfig, toAdd[0])
        assertEquals(oldConfig, toRemove[0])
    }

    @Test
    fun `checkDifferent should not detect tools-only change`() {
        val id = Uuid.random()
        val oldConfig = McpServerConfig.StreamableHTTPServer(
            id = id, url = "https://example.com/mcp",
            commonOptions = McpCommonOptions(tools = listOf(McpTool(name = "tool_a")))
        )
        val newConfig = McpServerConfig.StreamableHTTPServer(
            id = id, url = "https://example.com/mcp",
            commonOptions = McpCommonOptions(tools = listOf(McpTool(name = "tool_b")))
        )

        val (toAdd, toRemove) = listOf(oldConfig).checkDifferent(
            other = listOf(newConfig),
            eq = { a, b -> a.connectionKey == b.connectionKey }
        )

        assertTrue(toAdd.isEmpty())
        assertTrue(toRemove.isEmpty())
    }

    @Test
    fun `checkDifferent should detect new config`() {
        val existing = McpServerConfig.StreamableHTTPServer(url = "https://a.com/mcp")
        val added = McpServerConfig.StreamableHTTPServer(url = "https://b.com/mcp")

        val (toAdd, toRemove) = listOf(existing).checkDifferent(
            other = listOf(existing, added),
            eq = { a, b -> a.connectionKey == b.connectionKey }
        )

        assertEquals(1, toAdd.size)
        assertTrue(toRemove.isEmpty())
    }

    @Test
    fun `checkDifferent should detect removed config`() {
        val remaining = McpServerConfig.StreamableHTTPServer(url = "https://a.com/mcp")
        val removed = McpServerConfig.StreamableHTTPServer(url = "https://b.com/mcp")

        val (toAdd, toRemove) = listOf(remaining, removed).checkDifferent(
            other = listOf(remaining),
            eq = { a, b -> a.connectionKey == b.connectionKey }
        )

        assertTrue(toAdd.isEmpty())
        assertEquals(1, toRemove.size)
    }

    // ==================== mergeTools 测试 ====================

    private fun makeServerTool(name: String, description: String = ""): Tool {
        return Tool(
            name = name,
            description = description,
            inputSchema = ToolSchema(
                properties = JsonObject(emptyMap()),
                required = null,
            )
        )
    }

    @Test
    fun `mergeTools should add new tools from server`() {
        val serverTools = listOf(makeServerTool("tool_a"), makeServerTool("tool_b"))
        val localTools = emptyList<McpTool>()

        val result = mergeTools(serverTools, localTools)

        assertEquals(2, result.size)
        assertEquals("tool_a", result[0].name)
        assertEquals("tool_b", result[1].name)
        assertTrue(result.all { it.enable })
    }

    @Test
    fun `mergeTools should preserve enable and needsApproval for existing tools`() {
        val serverTools = listOf(makeServerTool("tool_a", "updated desc"))
        val localTools = listOf(
            McpTool(name = "tool_a", enable = false, needsApproval = true, description = "old desc")
        )

        val result = mergeTools(serverTools, localTools)

        assertEquals(1, result.size)
        assertEquals("tool_a", result[0].name)
        assertEquals(false, result[0].enable)
        assertEquals(true, result[0].needsApproval)
        assertEquals("updated desc", result[0].description)
    }

    @Test
    fun `mergeTools should remove tools not present on server`() {
        val serverTools = listOf(makeServerTool("tool_a"))
        val localTools = listOf(
            McpTool(name = "tool_a"),
            McpTool(name = "tool_b"),
        )

        val result = mergeTools(serverTools, localTools)

        assertEquals(1, result.size)
        assertEquals("tool_a", result[0].name)
    }

    @Test
    fun `mergeTools should return empty for empty server tools`() {
        val serverTools = emptyList<Tool>()
        val localTools = listOf(McpTool(name = "tool_a"), McpTool(name = "tool_b"))

        val result = mergeTools(serverTools, localTools)

        assertTrue(result.isEmpty())
    }

    // ==================== parseMcpServersFromJson 测试（原有 mcpServers 格式） ====================

    @Test
    fun `parse should parse streamable http config`() {
        val json = """
        {
            "mcpServers": {
                "my_server": {
                    "type": "streamable_http",
                    "url": "https://example.com/mcp"
                }
            }
        }
        """.trimIndent()

        val result = parseMcpServersFromJson(json)

        assertEquals(1, result.servers.size)
        val config = result.servers[0]
        assertTrue(config is McpServerConfig.StreamableHTTPServer)
        assertEquals("my_server", config.commonOptions.name)
        assertEquals("https://example.com/mcp", (config as McpServerConfig.StreamableHTTPServer).url)
        assertTrue(result.unsupportedNames.isEmpty())
    }

    @Test
    fun `parse should parse sse config`() {
        val json = """
        {
            "mcpServers": {
                "sse_server": {
                    "type": "sse",
                    "url": "https://example.com/sse"
                }
            }
        }
        """.trimIndent()

        val result = parseMcpServersFromJson(json)

        assertEquals(1, result.servers.size)
        assertTrue(result.servers[0] is McpServerConfig.SseTransportServer)
    }

    @Test
    fun `parse should default to streamable http when type omitted`() {
        val json = """
        {
            "mcpServers": {
                "no_type": {
                    "url": "https://example.com/mcp"
                }
            }
        }
        """.trimIndent()

        val result = parseMcpServersFromJson(json)

        assertEquals(1, result.servers.size)
        assertTrue(result.servers[0] is McpServerConfig.StreamableHTTPServer)
    }

    @Test
    fun `parse should parse headers`() {
        val json = """
        {
            "mcpServers": {
                "auth_server": {
                    "url": "https://example.com/mcp",
                    "headers": {
                        "Authorization": "Bearer token123",
                        "X-Custom": "value"
                    }
                }
            }
        }
        """.trimIndent()

        val result = parseMcpServersFromJson(json)

        assertEquals(1, result.servers.size)
        val headers = result.servers[0].commonOptions.headers
        assertEquals(2, headers.size)
        assertTrue(headers.contains("Authorization" to "Bearer token123"))
        assertTrue(headers.contains("X-Custom" to "value"))
    }

    @Test
    fun `parse should skip entries without url`() {
        val json = """
        {
            "mcpServers": {
                "no_url": {
                    "type": "streamable_http"
                },
                "has_url": {
                    "url": "https://example.com/mcp"
                }
            }
        }
        """.trimIndent()

        val result = parseMcpServersFromJson(json)

        assertEquals(1, result.servers.size)
        assertEquals("has_url", result.servers[0].commonOptions.name)
    }

    @Test
    fun `parse should handle multiple servers`() {
        val json = """
        {
            "mcpServers": {
                "server1": { "url": "https://a.com/mcp" },
                "server2": { "type": "sse", "url": "https://b.com/sse" }
            }
        }
        """.trimIndent()

        val result = parseMcpServersFromJson(json)

        assertEquals(2, result.servers.size)
    }

    @Test
    fun `parse should return empty for missing mcpServers key without type fields`() {
        val json = """{ "other": {} }"""

        val result = parseMcpServersFromJson(json)

        assertTrue(result.servers.isEmpty())
        assertTrue(result.unsupportedNames.isEmpty())
    }

    @Test
    fun `parse should return empty for empty mcpServers`() {
        val json = """{ "mcpServers": {} }"""

        val result = parseMcpServersFromJson(json)

        assertTrue(result.servers.isEmpty())
    }

    // ==================== parseMcpServersFromJson 测试（OpenCode 格式） ====================

    @Test
    fun `parse should handle opencode remote format as streamable http`() {
        val json = """
        {
            "mrl-D256": {
                "type": "remote",
                "url": "https://mrl-tunnel.weero.net/D233C24D568E4756/agent/mcp",
                "oauth": false,
                "headers": {
                    "Authorization": "Bearer 176025"
                }
            }
        }
        """.trimIndent()

        val result = parseMcpServersFromJson(json)

        assertEquals(1, result.servers.size)
        val config = result.servers[0]
        assertTrue(config is McpServerConfig.StreamableHTTPServer)
        assertEquals("mrl-D256", config.commonOptions.name)
        assertEquals("https://mrl-tunnel.weero.net/D233C24D568E4756/agent/mcp", (config as McpServerConfig.StreamableHTTPServer).url)
        assertEquals(1, config.commonOptions.headers.size)
        assertEquals("Authorization" to "Bearer 176025", config.commonOptions.headers[0])
        assertTrue(result.unsupportedNames.isEmpty())
    }

    @Test
    fun `parse should collect local type as unsupported`() {
        val json = """
        {
            "my-local": {
                "type": "local",
                "command": ["npx", "some-mcp-server"]
            },
            "my-remote": {
                "type": "remote",
                "url": "https://example.com/mcp"
            }
        }
        """.trimIndent()

        val result = parseMcpServersFromJson(json)

        assertEquals(1, result.servers.size)
        assertEquals("my-remote", result.servers[0].commonOptions.name)
        assertEquals(1, result.unsupportedNames.size)
        assertEquals("my-local", result.unsupportedNames[0])
    }

    @Test
    fun `parse should ignore oauth field in opencode format`() {
        val json = """
        {
            "oauth-server": {
                "type": "remote",
                "url": "https://example.com/mcp",
                "oauth": {
                    "clientId": "test-client-id",
                    "scope": "tools:read"
                }
            }
        }
        """.trimIndent()

        val result = parseMcpServersFromJson(json)

        assertEquals(1, result.servers.size)
        assertTrue(result.servers[0] is McpServerConfig.StreamableHTTPServer)
    }

    @Test
    fun `parse should handle opencode format without headers`() {
        val json = """
        {
            "no-headers": {
                "type": "remote",
                "url": "https://example.com/mcp"
            }
        }
        """.trimIndent()

        val result = parseMcpServersFromJson(json)

        assertEquals(1, result.servers.size)
        assertTrue(result.servers[0].commonOptions.headers.isEmpty())
    }

    @Test
    fun `parse should handle mixed opencode and mcpServers format`() {
        val json = """
        {
            "mcpServers": {
                "server1": { "type": "streamable_http", "url": "https://a.com/mcp" }
            },
            "server2": {
                "type": "remote",
                "url": "https://b.com/mcp"
            }
        }
        """.trimIndent()

        val result = parseMcpServersFromJson(json)

        assertEquals(1, result.servers.size)
        assertEquals("server1", result.servers[0].commonOptions.name)
    }

    // ==================== encodeForShare 测试 ====================

    @Test
    fun `encodeForShare should produce parseable streamable http config`() {
        val original = McpServerConfig.StreamableHTTPServer(
            commonOptions = McpCommonOptions(name = "my-server"),
            url = "https://example.com/mcp"
        )

        val json = original.encodeForShare()
        val result = parseMcpServersFromJson(json)

        assertEquals(1, result.servers.size)
        val parsed = result.servers[0]
        assertTrue(parsed is McpServerConfig.StreamableHTTPServer)
        assertEquals("my-server", parsed.commonOptions.name)
        assertEquals("https://example.com/mcp", (parsed as McpServerConfig.StreamableHTTPServer).url)
        assertTrue(parsed.commonOptions.headers.isEmpty())
    }

    @Test
    fun `encodeForShare should produce parseable sse config`() {
        val original = McpServerConfig.SseTransportServer(
            commonOptions = McpCommonOptions(name = "sse-server"),
            url = "https://example.com/sse"
        )

        val json = original.encodeForShare()
        val result = parseMcpServersFromJson(json)

        assertEquals(1, result.servers.size)
        val parsed = result.servers[0]
        assertTrue(parsed is McpServerConfig.SseTransportServer)
        assertEquals("sse-server", parsed.commonOptions.name)
        assertEquals("https://example.com/sse", (parsed as McpServerConfig.SseTransportServer).url)
    }

    @Test
    fun `encodeForShare should include headers when present`() {
        val original = McpServerConfig.StreamableHTTPServer(
            commonOptions = McpCommonOptions(
                name = "auth-server",
                headers = listOf("Authorization" to "Bearer token123")
            ),
            url = "https://example.com/mcp"
        )

        val json = original.encodeForShare()
        val result = parseMcpServersFromJson(json)

        val parsed = result.servers[0]
        assertEquals(1, parsed.commonOptions.headers.size)
        assertEquals("Authorization" to "Bearer token123", parsed.commonOptions.headers[0])
    }

    @Test
    fun `encodeForShare should exclude headers when empty`() {
        val original = McpServerConfig.StreamableHTTPServer(
            commonOptions = McpCommonOptions(name = "no-headers"),
            url = "https://example.com/mcp"
        )

        val json = original.encodeForShare()
        val root = Json.parseToJsonElement(json).jsonObject
        val serverObj = root["no-headers"]!!.jsonObject
        assertTrue(!serverObj.containsKey("headers"))
    }

    @Test
    fun `encodeForShare should use default name when blank`() {
        val original = McpServerConfig.StreamableHTTPServer(
            commonOptions = McpCommonOptions(name = ""),
            url = "https://example.com/mcp"
        )

        val json = original.encodeForShare()
        assertTrue(json.contains("mcp_server"))
    }

    @Test
    fun `encodeForShare should exclude id and tools`() {
        val original = McpServerConfig.StreamableHTTPServer(
            id = Uuid.parse("12345678-1234-1234-1234-123456789abc"),
            commonOptions = McpCommonOptions(
                name = "server-with-tools",
                tools = listOf(McpTool(name = "tool_a"))
            ),
            url = "https://example.com/mcp"
        )

        val json = original.encodeForShare()
        val root = Json.parseToJsonElement(json).jsonObject
        val serverObj = root["server-with-tools"]!!.jsonObject
        // id should not be in the JSON
        assertTrue(!serverObj.containsKey("id"))
        // tools should not be in the JSON
        assertTrue(!serverObj.containsKey("tools"))
        // enable should not be in the JSON
        assertTrue(!serverObj.containsKey("enable"))
    }
}
