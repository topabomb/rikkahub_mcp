package net.weero.measix.pilot.data.ai.mcp

import kotlinx.serialization.json.*
import net.weero.measix.pilot.data.enterprise.*
import org.junit.Assert.*
import org.junit.Test

class McpDirectToolAccessTest {
    private val read = McpCatalogTool(Json.parseToJsonElement(
        """{"name":"read","inputSchema":{"type":"object"},"annotations":{"readOnlyHint":false},"_meta":{"version":1}}"""
    ).jsonObject)
    private val server = exampleEnterprisePackage().configuration.mcpServers.first()
    private fun grant(tool: McpCatalogTool, approval: PlatformMcpToolGrantApprovalPolicy = PlatformMcpToolGrantApprovalPolicy.AUTO) =
        PlatformMcpToolGrant(tool.name, tool.contractHash(), approval)

    @Test fun `shared Core vectors verify full Tool JCS including Unicode and ECMAScript numbers`() {
        val vectors = requireNotNull(javaClass.getResourceAsStream("/contracts/platform/mcp-tool-contract-vectors.json"))
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonArray }
        vectors.forEach { value ->
            val vector = value.jsonObject
            val tool = McpCatalogTool(Json.parseToJsonElement(vector.getValue("rawDefinition").jsonPrimitive.content).jsonObject)
            assertEquals(vector.getValue("name").jsonPrimitive.content,
                vector.getValue("contractHash").jsonPrimitive.content, tool.contractHash())
        }
        assertNotEquals(read.contractHash(), McpCatalogTool(JsonObject(read.definition - "_meta")).contractHash())
    }

    @Test fun `ALL exposes dynamic tools without implicit approval or contract locking`() {
        val added = McpCatalogTool("added", inputSchema = buildJsonObject { put("type", "object") })
        val permissions = server.toolAccess(listOf(read, added))
        assertEquals(listOf("read", "added"), permissions.map { it.name })
        assertTrue(permissions.all { it.enabled && !it.needsApproval && it.contractHash == null })
        val changed = McpCatalogTool(JsonObject(read.definition + ("description" to JsonPrimitive("changed"))))
        assertTrue(server.toolAccess(listOf(changed)).single().enabled)
    }

    @Test fun `ALLOWLIST intersects full contract and assistant names and preserves explicit approval`() {
        val write = McpCatalogTool("write", inputSchema = buildJsonObject { put("type", "object") })
        val missing = McpCatalogTool("missing", inputSchema = buildJsonObject { put("type", "object") })
        val restricted = server.copy(toolAccessMode = PlatformMcpDefinitionToolAccessMode.ALLOWLIST,
            allowedTools = listOf(grant(read), grant(write, PlatformMcpToolGrantApprovalPolicy.REQUIRE_CONFIRMATION), grant(missing)))
        val permissions = restricted.toolAccess(listOf(read, write)).associateBy { it.name }
        assertTrue(permissions.getValue("read").enabled)
        assertFalse(permissions.getValue("read").needsApproval)
        assertTrue(permissions.getValue("write").needsApproval)
        assertEquals(McpToolUnavailableReason.MISSING, permissions.getValue("missing").unavailableReason)
        val binding = PlatformAssistantMcpBinding(server.id, PlatformAssistantMcpBindingToolSelection.ALLOWLIST, listOf("read"))
        assertEquals(listOf("read"), restricted.toolAccess(listOf(read, write), binding).filter { it.enabled }.map { it.name })
        val changed = McpCatalogTool(JsonObject(read.definition + ("description" to JsonPrimitive("changed"))))
        assertEquals(McpToolUnavailableReason.CONTRACT_CHANGED, restricted.toolAccess(listOf(changed)).first().unavailableReason)
        assertEquals(McpToolUnavailableReason.NOT_ALLOWED,
            restricted.toolAccess(listOf(McpCatalogTool("unapproved", inputSchema = read.inputSchema))).first().unavailableReason)
        val unhashable = McpCatalogTool(JsonObject(read.definition +
            ("_meta" to Json.parseToJsonElement("""{"value":1e400}"""))))
        val isolated = restricted.toolAccess(listOf(unhashable, write)).associateBy { it.name }
        assertEquals(McpToolUnavailableReason.CONTRACT_CHANGED, isolated.getValue("read").unavailableReason)
        assertTrue(isolated.getValue("write").enabled)
        assertTrue("ALL does not require a reviewed contract", server.toolAccess(listOf(unhashable)).single().enabled)
    }

    @Test fun `published modes require closed valid sets and assistant cannot exceed server ceiling`() {
        val packet = exampleEnterprisePackage()
        val definition = packet.configuration.assistants.first()
        fun validate(resource: EnterpriseMcpResource, binding: PlatformAssistantMcpBinding) {
            EnterpriseConfigurationCodec.validateConfiguration(packet.identity, packet.configuration.copy(
                mcpServers = packet.configuration.mcpServers.map { if (it.id == resource.id) resource else it },
                assistants = packet.configuration.assistants.map { if (it.id == definition.id) it.copy(mcpBindings = listOf(binding)) else it },
            ))
        }
        val all = PlatformAssistantMcpBinding(server.id, PlatformAssistantMcpBindingToolSelection.ALL, emptyList())
        assertThrows(EnterpriseConfigurationException::class.java) { validate(server.copy(allowedTools = listOf(grant(read))), all) }
        assertThrows(EnterpriseConfigurationException::class.java) {
            validate(server.copy(toolAccessMode = PlatformMcpDefinitionToolAccessMode.ALLOWLIST), all)
        }
        val restricted = server.copy(toolAccessMode = PlatformMcpDefinitionToolAccessMode.ALLOWLIST, allowedTools = listOf(grant(read)))
        assertThrows(EnterpriseConfigurationException::class.java) {
            validate(restricted, all.copy(toolSelection = PlatformAssistantMcpBindingToolSelection.ALLOWLIST, toolNames = listOf("unapproved")))
        }
        assertThrows(EnterpriseConfigurationException::class.java) { validate(restricted, all.copy(toolNames = listOf("read"))) }
    }
}
