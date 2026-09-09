package net.weero.measix.pilot.data.ai.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.weero.measix.pilot.data.enterprise.LocalEnterpriseMcpSurface
import net.weero.measix.pilot.data.enterprise.exampleEnterprisePackage
import org.junit.Assert.*
import org.junit.Test

class McpGatewaySurfaceTest {
    @Test fun `expected hash validates the whole ordered Tool objects without discarding extensions`() {
        val expected = exampleEnterprisePackage().configuration.gateways.single().surface
        val tools = LocalEnterpriseMcpSurface.gatewayTools
        expected.validate(tools)
        val invalid = listOf(tools.reversed(), tools.take(1), tools + tools.first(),
            listOf(McpCatalogTool(JsonObject(tools.first().definition + ("futureField" to JsonPrimitive(true))))) + tools.drop(1),
            listOf(McpCatalogTool(JsonObject(tools.first().definition - "outputSchema"))) + tools.drop(1))
        invalid.forEach { value -> assertThrows(IllegalArgumentException::class.java) { expected.validate(value) } }
        // Object property order is immaterial; array order and all field values are material.
        expected.validate(tools.map { McpCatalogTool(JsonObject(it.definition.entries.reversed().associate { it.toPair() })) })
    }

    @Test fun `JCS preserves ECMAScript number semantics rather than Kotlin JSON spelling`() {
        val template = """[{"name":"discover_tools","inputSchema":{"type":"object"},"_meta":{"number":NUMBER}},
            {"name":"invoke_tool","inputSchema":{"type":"object"}}]"""
        val surface = McpGatewaySurface(1, "sha256:c552cec6b067641e3d68ecdbb5036574fd94ff2b663772ba0ceacc4de25f416e")
        listOf("1", "1.0", "1e0").forEach { number ->
            val tools = Json.decodeFromString<List<McpCatalogTool>>(template.replace("NUMBER", number))
            surface.validate(tools)
        }
        val changed = Json.decodeFromString<List<McpCatalogTool>>(template.replace("NUMBER", "2"))
        assertThrows(IllegalArgumentException::class.java) { surface.validate(changed) }
    }
    @Test fun `JCS rejects malformed Unicode instead of hashing replacement bytes`() {
        val template = """[{"name":"discover_tools","inputSchema":{"type":"object"},"_meta":{"label":"LABEL"}},
            {"name":"invoke_tool","inputSchema":{"type":"object"}}]"""
        val replacement = McpGatewaySurface(1, "sha256:bc8f04753e6f8c0849541719fea482b480058c4a34d79d02f019a189a767c90b")
        replacement.validate(Json.decodeFromString(template.replace("LABEL", "?")))
        for (invalid in listOf(0xd800.toChar().toString(), 0xdc00.toChar().toString())) {
            val tools = Json.decodeFromString<List<McpCatalogTool>>(template.replace("LABEL", invalid))
            assertThrows(IllegalArgumentException::class.java) { replacement.validate(tools) }
        }
        val valid = McpGatewaySurface(1, "sha256:b94726a28210c5e2aa97e3f474a45579449e8667079410550c76eab650aaf9c1")
        valid.validate(Json.decodeFromString(template.replace("LABEL", "😀")))
    }

}
