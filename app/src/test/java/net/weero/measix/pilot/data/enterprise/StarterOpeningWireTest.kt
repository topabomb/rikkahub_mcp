package net.weero.measix.pilot.data.enterprise

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class StarterOpeningWireTest {
    private val source = """{"starterId":"str_12345678-1234-4234-8234-123456789012","assistantDefinitionId":"asd_12345678-1234-4234-8234-123456789012","title":"任务","prompt":"草稿","sortOrder":0,"enabled":true}"""
    private fun raw(opening: JsonElement) = JsonObject(
        Json.parseToJsonElement(source).jsonObject + ("openingSnapshot" to opening),
    ).toString()

    @Test
    fun `shared starter DTO preserves absent v4 opening and rejects explicit null`() {
        assertNull(PlatformWireCodec.decode<PlatformAssistantStarterDefinition>(source).openingSnapshot)
        assertThrows(IllegalArgumentException::class.java) {
            PlatformWireCodec.decode<PlatformAssistantStarterDefinition>(raw(JsonNull))
        }
    }

    @Test
    fun `opening accepts ordered literal fields and explicit empty values`() {
        val opening = starterOpeningMock()
        val wire = PlatformWireCodec.decode<PlatformAssistantStarterDefinition>(raw(opening))
        assertEquals(opening, PlatformWireCodec.json.encodeToJsonElement(wire).jsonObject.getValue("openingSnapshot"))
        val extended = JsonObject(opening + ("extra" to JsonPrimitive(true)) +
            ("initialContexts" to JsonArray(opening.getValue("initialContexts").jsonArray.map {
                JsonObject(it.jsonObject + ("title" to JsonPrimitive("\t")))
            })))
        assertEquals(wire, PlatformWireCodec.decode<PlatformAssistantStarterDefinition>(raw(extended)))
        val empty = buildJsonObject {
            put("format", 1)
            put("systemPrompt", "")
            put("initialContexts", JsonArray(emptyList()))
        }
        val emptyWire = PlatformWireCodec.decode<PlatformAssistantStarterDefinition>(raw(empty))
        assertEquals(empty, PlatformWireCodec.json.encodeToJsonElement(emptyWire).jsonObject.getValue("openingSnapshot"))
    }

    @Test
    fun `opening rejects absent fields wrong types null unknown versions and duplicate IDs`() {
        val opening = starterOpeningMock()
        val blocks = opening.getValue("initialContexts").jsonArray
        val invalid = listOf(
            JsonObject(opening - "systemPrompt"),
            JsonObject(opening - "initialContexts"),
            JsonObject(opening + ("format" to JsonPrimitive(2))),
            JsonObject(opening + ("format" to JsonPrimitive("1"))),
            JsonObject(opening + ("systemPrompt" to JsonNull)),
            JsonObject(opening + ("initialContexts" to JsonNull)),
            JsonObject(opening + ("initialContexts" to JsonArray(listOf(blocks.first(), blocks.first())))),
            JsonObject(opening + ("initialContexts" to JsonArray(listOf(JsonObject(blocks.first().jsonObject - "content"))))),
            JsonObject(opening + ("initialContexts" to JsonArray(listOf(JsonObject(blocks.first().jsonObject + ("id" to JsonPrimitive(" \n"))))))),
        )
        invalid.forEach { value ->
            assertThrows(value.toString(), IllegalArgumentException::class.java) {
                PlatformWireCodec.decode<PlatformAssistantStarterDefinition>(raw(value))
            }
        }
    }

    @Test
    fun `opening shares the complete network payload byte limit`() {
        val oversized = buildJsonObject {
            put("format", 1)
            put("systemPrompt", "中".repeat(EnterpriseConfigurationCodec.MAX_BYTES / 3))
            put("initialContexts", JsonArray(emptyList()))
        }
        val error = assertThrows(IllegalStateException::class.java) {
            PlatformWireCodec.decode<PlatformAssistantStarterDefinition>(raw(oversized))
        }
        assertEquals("frame exceeds byte limit", error.message)
    }
}
