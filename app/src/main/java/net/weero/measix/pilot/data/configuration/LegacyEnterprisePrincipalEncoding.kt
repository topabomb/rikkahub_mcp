package net.weero.measix.pilot.data.configuration

import java.util.Base64
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import me.rerere.common.configuration.EnterpriseAuthority
import net.weero.measix.pilot.utils.JsonInstant

/** One-time current-development migration; runtime serializers accept only the deployment-owned shape. */
internal object LegacyEnterprisePrincipalEncoding {
    fun migrateSettingsJson(encoded: String): String? {
        val original = JsonInstant.parseToJsonElement(encoded).jsonObject
        val preferences = original["preferences"] ?: return null
        val migratedPreferences = migrate(preferences, true).jsonObject
        val scopes = migratedPreferences["scopes"] as? JsonArray
        val mergedPreferences = if (scopes == null) migratedPreferences else JsonObject(
            migratedPreferences + ("scopes" to mergeScopedPreferences(scopes)),
        )
        return encodedMigration(original, JsonObject(original + ("preferences" to mergedPreferences)))
    }

    fun migrateMcpCatalogJson(encoded: String): String? {
        val original = JsonInstant.parseToJsonElement(encoded).jsonObject
        val catalogs = original["catalogs"] as? JsonArray ?: return null
        val migratedCatalogs = catalogs.map { element ->
            val catalog = element.jsonObject
            JsonObject(catalog.mapValues { (key, value) -> when (key) {
                "scope" -> migrate(value, false)
                "serverId" -> (value as? JsonPrimitive)?.takeIf { it.isString }
                    ?.let { migrateReference(it.content)?.let(::JsonPrimitive) } ?: value
                else -> value
            } })
        }
        return encodedMigration(original, JsonObject(original + ("catalogs" to mergeCatalogs(migratedCatalogs))))
    }

    fun migrateStorageJson(encoded: String): String? {
        val original = JsonInstant.parseToJsonElement(encoded)
        return encodedMigration(original, migrate(original, false))
    }

    fun migrateReference(value: String): String? {
        val parts = value.split('~')
        if (parts.size != 5 || parts[0] != "managed" || parts[1] != "platform" ||
            !parts[2].matches(Regex("[A-Za-z0-9._-]{1,128}")) ||
            !parts[3].matches(Regex("[A-Za-z0-9._-]{1,256}")) ||
            !parts[4].matches(Regex("[a-z][a-z0-9]*_[A-Za-z0-9._-]{1,256}"))) return null
        return "managed~${parts[3]}~${parts[4]}"
    }

    fun migrateScopeStorageKey(value: String): String? {
        val parts = value.split('~')
        if (parts.size != 4 || parts[0] != "enterprise" ||
            !parts[1].matches(Regex("platform:[A-Za-z0-9._-]{1,128}")) ||
            !parts[2].matches(Regex("[A-Za-z0-9._-]{1,256}"))) return null
        val bytes = try { Base64.getUrlDecoder().decode(parts[3]) } catch (_: IllegalArgumentException) { return null }
        val userId = bytes.toString(Charsets.UTF_8)
        if (userId.toByteArray(Charsets.UTF_8).contentEquals(bytes).not() ||
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) != parts[3]) return null
        return try {
            ConfigurationScope.Enterprise(EnterpriseAuthority(parts[2]), userId).storageKey()
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun encodedMigration(original: JsonElement, migrated: JsonElement): String? =
        JsonInstant.encodeToString(JsonElement.serializer(), migrated).takeIf { migrated != original }

    private fun migrate(element: JsonElement, references: Boolean): JsonElement = when (element) {
        is JsonObject -> {
            val migrated = element.mapValues { (_, value) -> migrate(value, references) }.toMutableMap()
            val source = migrated["sourceNamespace"]?.jsonPrimitive?.content
            val deployment = migrated["deploymentId"]?.jsonPrimitive?.content
            if (migrated.keys == setOf("sourceNamespace", "deploymentId") &&
                source?.matches(Regex("platform:[A-Za-z0-9._-]{1,128}")) == true &&
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

    /** Later serialized scope entries win scalar conflicts; per-resource preferences retain both histories. */
    private fun mergeScopedPreferences(scopes: JsonArray): JsonArray {
        val merged = linkedMapOf<String, JsonObject>()
        scopes.forEach { element ->
            val current = element.jsonObject
            val key = current.getValue("scope").toString()
            merged[key] = merged[key]?.let { previous ->
                val fields = previous.toMutableMap().apply { putAll(current) }
                listOf("assistantUsage" to "assistantId", "gateways" to "gateway").forEach { (field, identity) ->
                    val values = linkedMapOf<String, JsonElement>()
                    (previous[field] as? JsonArray).orEmpty().forEach { value ->
                        values[value.jsonObject.getValue(identity).toString()] = value
                    }
                    (current[field] as? JsonArray).orEmpty().forEach { value ->
                        values[value.jsonObject.getValue(identity).toString()] = value
                    }
                    fields[field] = JsonArray(values.values.toList())
                }
                JsonObject(fields)
            } ?: current
        }
        return JsonArray(merged.values.toList())
    }

    /** Catalogs are rebuildable caches; keep the newest managed generation/revision for a merged owner. */
    private fun mergeCatalogs(catalogs: List<JsonObject>): JsonArray {
        val merged = linkedMapOf<String, JsonObject>()
        fun rank(catalog: JsonObject): Pair<Long, Long> {
            val generation = (catalog["managed"] as? JsonObject)?.get("generation")?.jsonPrimitive?.longOrNull ?: -1L
            val revision = catalog["revision"]?.jsonPrimitive?.longOrNull ?: -1L
            return generation to revision
        }
        catalogs.forEach { catalog ->
            val key = catalog.getValue("scope").toString() + "\u0000" + catalog.getValue("serverId").toString()
            val previous = merged[key]
            if (previous == null || rank(catalog).let { it.first > rank(previous).first ||
                    it.first == rank(previous).first && it.second >= rank(previous).second }) {
                merged[key] = catalog
            }
        }
        return JsonArray(merged.values.toList())
    }
}
