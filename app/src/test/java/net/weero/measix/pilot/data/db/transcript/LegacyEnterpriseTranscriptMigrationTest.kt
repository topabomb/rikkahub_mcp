package net.weero.measix.pilot.data.db.transcript

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LegacyEnterpriseTranscriptMigrationTest {
    private val oldModel = "managed~platform~${"a".repeat(64)}~dep_example~mdl_chat"
    private val newModel = "managed~dep_example~mdl_chat"
    private val oldAssistant = "managed~platform~${"a".repeat(64)}~dep_example~asd_child"
    private val newAssistant = "managed~dep_example~asd_child"

    @Test
    fun `only identity slots change across variants and nested tool output`() {
        val tool = """{"type":"tool","input":"$oldModel","providerCallId":"$oldAssistant",
            "metadata":{"opaque":{"modelId":"$oldModel"},"sub_assistant_call":{
                "target_assistant_id":"$oldAssistant","summary":"$oldAssistant"}},
            "output":[{"type":"text","text":"$oldModel"}]}""".trimIndent()
        val raw = """[{"modelId":"$oldModel","parts":[{"type":"text","text":"$oldModel"},
            {"type":"tool","output":[$tool]}],"providerMetadata":{"modelId":"$oldModel"},
            "translation":"$oldModel","unknown":{"modelId":"$oldModel"}},
            {"modelId":"$oldModel","parts":[]},{"modelId":null,"parts":[]}]""".trimIndent()
        val before = JsonInstant.parseToJsonElement(raw).jsonArray
        val after = JsonInstant.parseToJsonElement(LegacyEnterpriseTranscriptMigration.migrateNode(raw)).jsonArray
        val first = after[0].jsonObject
        assertEquals(JsonPrimitive(newModel), first["modelId"])
        assertEquals(JsonPrimitive(newModel), after[1].jsonObject["modelId"])
        assertEquals(before[2], after[2])
        listOf("providerMetadata", "translation", "unknown").forEach { key ->
            assertEquals(before[0].jsonObject[key], first[key])
        }
        val originalParts = before[0].jsonObject.getValue("parts").jsonArray
        val parts = first.getValue("parts").jsonArray
        assertEquals(originalParts[0], parts[0])
        val nested = parts[1].jsonObject.getValue("output").jsonArray.single().jsonObject
        val originalTool = JsonInstant.parseToJsonElement(tool).jsonObject
        val expectedMetadata = JsonObject(originalTool.getValue("metadata").jsonObject.toMutableMap().apply {
            put("sub_assistant_call", JsonObject(getValue("sub_assistant_call").jsonObject.toMutableMap().apply {
                put("target_assistant_id", JsonPrimitive(newAssistant))
            }))
        })
        assertEquals(JsonObject(originalTool.toMutableMap().apply { put("metadata", expectedMetadata) }), nested)
        val migrated = LegacyEnterpriseTranscriptMigration.migrateNode(raw)
        assertEquals(migrated, LegacyEnterpriseTranscriptMigration.migrateNode(migrated))
    }

    @Test
    fun `current and personal identities preserve exact serialization including whitespace`() {
        val raw = """[ { "modelId": "$newModel", "parts": [] },
            {"modelId":"00000000-0000-0000-0000-000000000001","parts":[]}, {"parts":[]} ]"""
        assertEquals(raw, LegacyEnterpriseTranscriptMigration.migrateNode(raw))
        assertEquals("[]", LegacyEnterpriseTranscriptMigration.migrateReferenceList("[]"))
        val ids = """[ "$newAssistant", "00000000-0000-0000-0000-000000000001" ]"""
        assertEquals(ids, LegacyEnterpriseTranscriptMigration.migrateReferenceList(ids))
    }

    @Test
    fun `malformed managed identities fail instead of becoming personal or disappearing`() {
        listOf("managed~platform~bad!~dep_example~mdl_one", "managed~dep_example", "managed~dep_example~bad").forEach { id ->
            assertThrows(IllegalArgumentException::class.java) {
                LegacyEnterpriseTranscriptMigration.migrateNode("""[{"modelId":"$id","parts":[]}]""")
            }
            assertThrows(IllegalArgumentException::class.java) {
                LegacyEnterpriseTranscriptMigration.migrateReferenceList("""["$id"]""")
            }
        }
    }

    @Test
    fun `injection identity lists migrate without changing order or personal ids`() {
        val personal = "00000000-0000-0000-0000-000000000001"
        assertEquals(JsonArray(listOf(newAssistant, personal, newModel).map(::JsonPrimitive)),
            JsonInstant.parseToJsonElement(LegacyEnterpriseTranscriptMigration.migrateReferenceList(
                """["$oldAssistant","$personal","$newModel"]""")))
    }
}
