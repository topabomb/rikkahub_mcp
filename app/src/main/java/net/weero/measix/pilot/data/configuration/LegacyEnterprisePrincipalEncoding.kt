package net.weero.measix.pilot.data.configuration

import java.util.Base64
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import net.weero.measix.pilot.data.db.transcript.LegacyEnterpriseTranscriptMigration
import me.rerere.common.configuration.EnterpriseAuthority
import me.rerere.common.configuration.RetiredLocalEnterpriseIdentity
import net.weero.measix.pilot.utils.JsonInstant

/** Retired identity encoding for explicit migrations and historical-envelope validation only. */
internal object LegacyEnterprisePrincipalEncoding {
    fun migrateSettingsJson(encoded: String): String? {
        val original = JsonInstant.parseToJsonElement(encoded).jsonObject
        val preferences = original["preferences"] ?: return null
        val migratedPreferences = JsonObject(preferences.jsonObject.mapValues { (field, value) ->
            if (field == "scopes") JsonArray((value as JsonArray).map { migrateScopedPreferences(it.jsonObject) }) else value
        })
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
                "scope" -> migrateScope(value.jsonObject)
                "serverId" -> (value as? JsonPrimitive)?.takeIf { it.isString }
                    ?.let { migrateReference(it.content)?.let(::JsonPrimitive) } ?: value
                else -> value
            } })
        }
        return encodedMigration(original, JsonObject(original + ("catalogs" to mergeCatalogs(migratedCatalogs))))
    }

    fun migrateStorageJson(encoded: String): String? {
        val original = JsonInstant.parseToJsonElement(encoded).jsonObject
        val migrated = JsonObject(original.mapValues { (field, value) -> when (field) {
            "selectedScope" -> migrateScope(value.jsonObject)
            "identity", "lastIdentity" -> if (value is JsonObject) migrateIdentity(value) else value
            "session" -> if (value is JsonObject) JsonObject(value.mapValues { (key, child) ->
                if (key == "identity") migrateIdentity(child.jsonObject) else child
            }) else value
            else -> value
        } })
        return encodedMigration(original, migrated)
    }

    fun migrateReference(value: String): String? {
        val parts = value.split('~')
        if (parts.size != 5 || parts[0] != "managed" || parts[1] !in setOf("platform", "local") ||
            !parts[2].matches(Regex("[A-Za-z0-9._-]{1,128}")) ||
            !parts[3].matches(Regex("[A-Za-z0-9._-]{1,256}")) ||
            !parts[4].matches(Regex("[a-z][a-z0-9]*_[A-Za-z0-9._-]{1,256}"))) return null
        val deployment = migrateDeployment("${parts[1]}:${parts[2]}", parts[3]) ?: return null
        return "managed~$deployment~${parts[4]}"
    }

    fun migrateScopeStorageKey(value: String): String? {
        val parts = value.split('~')
        if (parts.size != 4 || parts[0] != "enterprise" ||
            !parts[1].matches(Regex("(platform|local):[A-Za-z0-9._-]{1,128}")) ||
            !parts[2].matches(Regex("[A-Za-z0-9._-]{1,256}"))) return null
        val bytes = try { Base64.getUrlDecoder().decode(parts[3]) } catch (_: IllegalArgumentException) { return null }
        val userId = bytes.toString(Charsets.UTF_8)
        if (userId.toByteArray(Charsets.UTF_8).contentEquals(bytes).not() ||
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) != parts[3]) return null
        return try {
            ConfigurationScope.Enterprise(EnterpriseAuthority(requireNotNull(migrateDeployment(parts[1], parts[2]))), userId).storageKey()
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun encodedMigration(original: JsonElement, migrated: JsonElement): String? =
        JsonInstant.encodeToString(JsonElement.serializer(), migrated).takeIf { migrated != original }

    private fun migrateDeployment(source: String, deployment: String): String? {
        if (!deployment.matches(Regex("[A-Za-z0-9._-]{1,256}"))) return null
        return when {
            source.matches(Regex("platform:[A-Za-z0-9._-]{1,128}")) -> deployment
            source.matches(Regex("local:[A-Za-z0-9._-]{1,128}")) -> RetiredLocalEnterpriseIdentity.encode(source, deployment)
            else -> null
        }
    }

    private fun migrateAuthority(authority: JsonObject): JsonObject {
        if ("sourceNamespace" !in authority) return authority
        val source = requireNotNull((authority["sourceNamespace"] as? JsonPrimitive)?.takeIf { it.isString }?.content) {
            "invalid_legacy_enterprise_authority"
        }
        val deployment = requireNotNull((authority["deploymentId"] as? JsonPrimitive)?.takeIf { it.isString }?.content) {
            "invalid_legacy_enterprise_authority"
        }
        val migrated = requireNotNull(migrateDeployment(source, deployment)) { "invalid_legacy_enterprise_authority" }
        return JsonObject(authority.filterKeys { it != "sourceNamespace" } + ("deploymentId" to JsonPrimitive(migrated)))
    }

    private fun migrateScope(scope: JsonObject): JsonObject =
        if (scope["type"] == JsonPrimitive("enterprise")) migrateIdentity(scope) else scope

    private fun migrateIdentity(identity: JsonObject): JsonObject = JsonObject(identity.mapValues { (field, value) ->
        if (field == "authority") migrateAuthority(value.jsonObject) else value
    })

    private fun reference(element: JsonElement): JsonElement =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.let {
            migrateReference(it.content)?.let(::JsonPrimitive)
        } ?: element

    private fun references(element: JsonElement): JsonElement = JsonArray((element as JsonArray).map(::reference))

    private fun migrateScopedPreferences(scoped: JsonObject): JsonObject = JsonObject(scoped.mapValues { (field, value) -> when (field) {
        "scope" -> migrateScope(value.jsonObject)
        "selections" -> JsonObject(value.jsonObject.mapValues { (slot, selected) -> when (slot) {
            "favoriteModels" -> references(selected)
            "chatModelId", "fastModelId", "titleModelId", "imageGenerationModelId", "suggestionModelId",
            "attachmentInspectionModelId", "compressModelId", "assistantId", "selectedSearchServiceId",
            "selectedTTSProviderId", "selectedASRProviderId" -> reference(selected)
            else -> selected
        } })
        "assistantUsage" -> JsonArray((value as JsonArray).map { migrateUsage(it.jsonObject) })
        "gateways" -> JsonArray((value as JsonArray).map { gateway -> JsonObject(gateway.jsonObject.mapValues { (key, child) ->
            if (key == "gateway") reference(child) else child
        }) })
        else -> value
    } })

    private fun migrateUsage(usage: JsonObject): JsonObject = JsonObject(usage.mapValues { (field, value) -> when (field) {
        "assistantId" -> reference(value)
        "additionalSubAssistantIds" -> references(value)
        "chatModelId", "tags", "quickMessageIds", "mcpServers", "modeInjectionIds", "regexes", "presetMessages" -> {
            if (value !is JsonObject) value else JsonObject(value.mapValues { (key, child) ->
                if (key != "value") child else when (field) {
                    "chatModelId" -> reference(child)
                    "regexes" -> JsonArray((child as JsonArray).map { regex -> JsonObject(regex.jsonObject.mapValues { (key, item) ->
                        if (key == "id") reference(item) else item
                    }) })
                    "presetMessages" -> JsonArray((child as JsonArray).map { LegacyEnterpriseTranscriptMigration.migrateMessage(it.jsonObject) })
                    else -> references(child)
                }
            })
        }
        else -> value
    } })

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
