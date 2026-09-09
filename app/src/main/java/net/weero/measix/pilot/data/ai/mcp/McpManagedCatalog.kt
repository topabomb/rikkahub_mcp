package net.weero.measix.pilot.data.ai.mcp

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import org.erdtman.jcs.JsonCanonicalizer

/** The published generation is independent of connection epochs and catalog discovery revisions. */
@Serializable
data class McpManagedCatalog(val generation: Long, val gatewaySurface: McpGatewaySurface? = null) {
    init { require(generation > 0) { "Invalid managed catalog generation" } }
}

/** Expected surface comes from the public managed resource, never from tools/list itself. */
@Serializable
data class McpGatewaySurface(val version: Int, val hash: String) {
    init {
        require(version == 1) { "Unsupported Gateway surface version" }
        require(hash.matches(Regex("sha256:[0-9a-f]{64}"))) { "Invalid Gateway surface hash" }
    }

    fun validate(tools: List<McpCatalogTool>) {
        require(tools.map { it.name } == listOf("discover_tools", "invoke_tool")) {
            "Gateway must expose exactly the ordered discover/invoke pair"
        }
        val bytes = try {
            val canonical = JsonCanonicalizer(JsonArray(tools.map { it.definition }).toString()).encodedString
            // JCS requires rejecting lone surrogates; String.getBytes would replace them silently.
            val encoded = Charsets.UTF_8.newEncoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .encode(java.nio.CharBuffer.wrap(canonical))
            ByteArray(encoded.remaining()).also { encoded.get(it) }
        }
        catch (_: java.io.IOException) { throw IllegalArgumentException("Invalid Gateway canonical surface") }
        val actual = "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        require(actual == hash) { "Gateway surface does not match the published hash" }
    }
}

/** Resource kind binds the required verification metadata, including when a persisted row is read. */
internal fun McpCatalogKey.validateManaged(managed: McpManagedCatalog?) {
    when (val reference = serverId) {
        is me.rerere.common.configuration.ConfigurationReference.User ->
            require(managed == null) { "Personal MCP catalog cannot carry managed metadata" }
        is me.rerere.common.configuration.ConfigurationReference.Enterprise -> {
            requireNotNull(managed) { "Enterprise MCP catalog requires its managed generation" }
            when {
                reference.id.startsWith("twg_") -> requireNotNull(managed.gatewaySurface) { "Gateway catalog requires its published surface" }
                reference.id.startsWith("mcp_") -> require(managed.gatewaySurface == null) { "Direct MCP catalog cannot carry a Gateway surface" }
                else -> throw IllegalArgumentException("Invalid enterprise MCP resource type")
            }
        }
    }
}
