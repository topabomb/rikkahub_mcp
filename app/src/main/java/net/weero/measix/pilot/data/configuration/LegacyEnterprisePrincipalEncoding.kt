package net.weero.measix.pilot.data.configuration

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.weero.measix.pilot.utils.JsonInstant

/** One-time current-development migration; runtime serializers accept only the deployment-owned shape. */
internal object LegacyEnterprisePrincipalEncoding {
    fun migrateSettingsJson(encoded: String): String? {
        val original = JsonInstant.parseToJsonElement(encoded).jsonObject
        val preferences = original["preferences"] ?: return null
        return encodedMigration(original, JsonObject(original + ("preferences" to migrate(preferences, true))))
    }

    fun migrateMcpCatalogJson(encoded: String): String? {
        val original = JsonInstant.parseToJsonElement(encoded).jsonObject
        val catalogs = original["catalogs"] as? JsonArray ?: return null
        val migratedCatalogs = JsonArray(catalogs.map { element ->
            val catalog = element.jsonObject
            JsonObject(catalog.mapValues { (key, value) -> when (key) {
                "scope" -> migrate(value, false)
                "serverId" -> (value as? JsonPrimitive)?.takeIf { it.isString }
                    ?.let { migrateReference(it.content)?.let(::JsonPrimitive) } ?: value
                else -> value
            } })
        })
        return encodedMigration(original, JsonObject(original + ("catalogs" to migratedCatalogs)))
    }

    fun migrateStorageJson(encoded: String): String? {
        val original = JsonInstant.parseToJsonElement(encoded)
        return encodedMigration(original, migrate(original, false))
    }

    fun migrateReference(value: String): String? {
        val parts = value.split('~')
        if (parts.size != 5 || parts[0] != "managed" || parts[1] != "platform" ||
            !parts[2].matches(Regex("[0-9a-f]{64}")) ||
            !parts[3].matches(Regex("[A-Za-z0-9._-]{1,256}")) ||
            !parts[4].matches(Regex("[a-z][a-z0-9]*_[A-Za-z0-9._-]{1,256}"))) return null
        return "managed~${parts[3]}~${parts[4]}"
    }

    private fun encodedMigration(original: JsonElement, migrated: JsonElement): String? =
        JsonInstant.encodeToString(JsonElement.serializer(), migrated).takeIf { migrated != original }

    private fun migrate(element: JsonElement, references: Boolean): JsonElement = when (element) {
        is JsonObject -> {
            val migrated = element.mapValues { (_, value) -> migrate(value, references) }.toMutableMap()
            val source = migrated["sourceNamespace"]?.jsonPrimitive?.content
            val deployment = migrated["deploymentId"]?.jsonPrimitive?.content
            if (migrated.keys == setOf("sourceNamespace", "deploymentId") &&
                source?.matches(Regex("platform:[0-9a-f]{64}")) == true &&
                deployment?.matches(Regex("[A-Za-z0-9._-]{1,256}")) == true) {
                migrated.remove("sourceNamespace")
            }
            JsonObject(migrated)
        }
        is JsonArray -> JsonArray(element.map { migrate(it, references) })
        is JsonPrimitive -> if (references && element.isString) {
            migrateReference(element.content)?.let(::JsonPrimitive) ?: element
        } else element
    }
}
