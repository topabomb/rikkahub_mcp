package net.weero.measix.pilot.data.db.transcript

import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.configuration.LegacyEnterprisePrincipalEncoding
import net.weero.measix.pilot.utils.JsonInstant

/** Only persisted identity slots are rewritten; prompt, tool IO and provider payloads are opaque. */
internal object LegacyEnterpriseTranscriptMigration {
    fun migrateNode(encoded: String): String {
        val original = JsonInstant.parseToJsonElement(encoded).jsonArray
        val migrated = JsonArray(original.map { migrateMessage(it.jsonObject) })
        return if (migrated == original) encoded else migrated.toString()
    }

    fun migrateMessage(message: JsonObject): JsonObject = JsonObject(message.toMutableMap().apply {
        message["modelId"]?.takeUnless { it == JsonNull }?.let { put("modelId", migrateReference(it)) }
        (message["parts"] as? JsonArray)?.let { put("parts", migrateParts(it)) }
    })

    private fun migrateParts(parts: JsonArray): JsonArray = JsonArray(parts.map { part ->
        if (part !is JsonObject || part["type"] != JsonPrimitive("tool")) return@map part
        JsonObject(part.toMutableMap().apply {
            (part["metadata"] as? JsonObject)?.let { metadata ->
                (metadata["sub_assistant_call"] as? JsonObject)?.let { call ->
                    call["target_assistant_id"]?.let { target ->
                        put("metadata", JsonObject(metadata.toMutableMap().apply {
                            put("sub_assistant_call", JsonObject(call.toMutableMap().apply {
                                put("target_assistant_id", migrateReference(target))
                            }))
                        }))
                    }
                }
            }
            (part["output"] as? JsonArray)?.let { put("output", migrateParts(it)) }
        })
    })

    fun migrateReferenceList(encoded: String): String {
        val original = JsonInstant.parseToJsonElement(encoded).jsonArray
        val migrated = JsonArray(original.map(::migrateReference))
        return if (migrated == original) encoded else migrated.toString()
    }

    private fun migrateReference(element: JsonElement): JsonPrimitive {
        require(element is JsonPrimitive && element.isString) { "invalid_persisted_configuration_reference" }
        val value = element.content
        if (!value.startsWith("managed~")) return element
        val migrated = LegacyEnterprisePrincipalEncoding.migrateReference(value) ?: value
        ConfigurationReference.parse(migrated)
        return if (migrated == value) element else JsonPrimitive(migrated)
    }
}

/** Runs in the Room upgrade transaction, before any current typed transcript decoder. */
internal fun migrateEnterpriseTranscriptReferences(db: SupportSQLiteDatabase) {
    db.query("SELECT id FROM message_node").use { rows ->
        while (rows.moveToNext()) {
            val id = rows.getString(0)
            val original = readTranscriptPayload(db, id)
            val migrated = LegacyEnterpriseTranscriptMigration.migrateNode(original)
            if (migrated != original) {
                db.execSQL("UPDATE message_node SET messages = ? WHERE id = ?", arrayOf(migrated, id))
            }
        }
    }
    db.query("SELECT id, mode_injection_ids FROM ConversationEntity").use { rows ->
        while (rows.moveToNext()) {
            val original = rows.getString(1)
            val migrated = LegacyEnterpriseTranscriptMigration.migrateReferenceList(original)
            if (migrated != original) {
                db.execSQL("UPDATE ConversationEntity SET mode_injection_ids = ? WHERE id = ?",
                    arrayOf(migrated, rows.getString(0)))
            }
        }
    }
}
