package net.weero.measix.pilot.data.ai.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import net.weero.measix.pilot.utils.JsonInstant

@Serializable
internal data class McpLegacyCatalogMigrationPayload(
    val candidates: List<McpCatalogCandidate>,
)

internal data class McpLegacySettingsMigration(
    val normalizedServersJson: String,
    val payload: McpLegacyCatalogMigrationPayload,
)

internal data class McpLegacySettingsDocumentMigration(
    val normalizedSettingsJson: String,
    val payload: McpLegacyCatalogMigrationPayload,
)

/**
 * Converts the former Settings-owned remote schemas into a one-shot catalog staging payload.
 * The returned Settings JSON contains only current definition and policy fields; no runtime path
 * reads legacy schemas after this migration.
 */
internal fun migrateLegacyMcpServersJson(encoded: String): McpLegacySettingsMigration? {
    val elements = runCatching { JsonInstant.parseToJsonElement(encoded) as? JsonArray }
        .getOrNull() ?: return null
    val records = elements.mapNotNull { element ->
        runCatching {
            JsonInstant.decodeFromJsonElement<McpServerConfig>(element) to element
        }.getOrNull()
    }
    if (records.size != elements.size) return null

    var foundLegacySchema = false
    val seenIds = hashSetOf<kotlin.uuid.Uuid>()
    val seenNames = hashSetOf<String>()
    val normalizedServers = mutableListOf<McpServerConfig>()
    val candidates = mutableListOf<McpCatalogCandidate>()
    records.forEach { (decoded, element) ->
        val normalizedName = decoded.commonOptions.name.trim().lowercase()
        if (!seenIds.add(decoded.id) || !seenNames.add(normalizedName)) return@forEach
        val normalized = decoded.clone(
            commonOptions = decoded.commonOptions.copy(
                toolPolicies = decoded.commonOptions.toolPolicies.distinctBy { it.name },
            )
        )
        normalizedServers += normalized

        val tools = element.legacyToolsArray() ?: return@forEach
        if (tools.any(JsonElement::containsLegacySchema)) foundLegacySchema = true
        tools.toLegacyCatalogTools()?.takeIf { it.isNotEmpty() }?.let { catalogTools ->
            candidates += McpCatalogCandidate(
                serverId = normalized.id,
                definitionDigest = normalized.mcpDefinitionDigest(),
                tools = catalogTools,
            )
        }
    }
    if (!foundLegacySchema) return null
    return McpLegacySettingsMigration(
        normalizedServersJson = JsonInstant.encodeToString(normalizedServers),
        payload = McpLegacyCatalogMigrationPayload(candidates),
    )
}

internal fun migrateLegacyMcpSettingsDocument(encoded: String): McpLegacySettingsDocumentMigration? {
    val root = runCatching { JsonInstant.parseToJsonElement(encoded) as? JsonObject }
        .getOrNull() ?: return null
    val servers = root["mcpServers"] as? JsonArray ?: return null
    val migration = migrateLegacyMcpServersJson(JsonInstant.encodeToString(servers)) ?: return null
    val normalizedServers = JsonInstant.parseToJsonElement(migration.normalizedServersJson)
    val normalizedRoot = buildJsonObject {
        root.forEach { (name, value) -> put(name, value) }
        put("mcpServers", normalizedServers)
    }
    return McpLegacySettingsDocumentMigration(
        normalizedSettingsJson = JsonInstant.encodeToString(normalizedRoot),
        payload = migration.payload,
    )
}

private fun JsonElement.legacyToolsArray(): JsonArray? =
    jsonObject["commonOptions"]?.jsonObject?.get("tools") as? JsonArray

private fun JsonElement.containsLegacySchema(): Boolean =
    (this as? JsonObject)?.let { "description" in it || "inputSchema" in it } == true

private fun JsonArray.toLegacyCatalogTools(): List<McpCatalogTool>? {
    val tools = map { element ->
        val tool = element as? JsonObject ?: return null
        if (!element.containsLegacySchema()) return null
        val name = (tool["name"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
            ?: return null
        val inputSchema = tool["inputSchema"] as? JsonObject ?: return null
        val description = (tool["description"] as? JsonPrimitive)?.contentOrNull
        McpCatalogTool(name = name, description = description, inputSchema = inputSchema)
    }
    return tools.takeIf { values -> values.map { it.name }.toSet().size == values.size }
}
