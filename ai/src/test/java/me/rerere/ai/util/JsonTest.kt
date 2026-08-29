package me.rerere.ai.util

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.providers.CLAUDE_MESSAGES_OWNERSHIP
import me.rerere.ai.provider.providers.GEMINI_OWNERSHIP
import me.rerere.ai.provider.providers.openai.CHAT_COMPLETIONS_OWNERSHIP
import me.rerere.ai.provider.providers.openai.RESPONSES_OWNERSHIP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTest {

    private val ownership = RequestBodyOwnership(
        protocol = "test",
        reservedKeys = setOf("model", "messages", "tools"),
    )

    @Test
    fun `mergeCustomBody with empty list should return original object`() {
        val originalJson = buildJsonObject {
            put("key1", "value1")
            put("key2", 2)
        }

        val result = originalJson.mergeCustomBody(emptyList(), ownership)

        assertEquals(originalJson, result)
    }

    @Test
    fun `mergeCustomBody with simple keys should merge correctly`() {
        val originalJson = buildJsonObject {
            put("existingKey", "existingValue")
        }

        val customBodies = listOf(
            CustomBody("newKey", JsonPrimitive("newValue")),
            CustomBody("numberKey", JsonPrimitive(42))
        )

        val result = originalJson.mergeCustomBody(customBodies, ownership)

        assertEquals("existingValue", result["existingKey"]?.toString()?.trim('"'))
        assertEquals("newValue", result["newKey"]?.toString()?.trim('"'))
        assertEquals("42", result["numberKey"]?.toString())
    }

    @Test
    fun `mergeCustomBody should override existing simple keys`() {
        val originalJson = buildJsonObject {
            put("key1", "oldValue")
        }

        val customBodies = listOf(
            CustomBody("key1", JsonPrimitive("newValue"))
        )

        val result = originalJson.mergeCustomBody(customBodies, ownership)

        assertEquals("newValue", result["key1"]?.toString()?.trim('"'))
    }

    @Test
    fun `mergeCustomBody should merge nested JsonObjects`() {
        val originalJson = buildJsonObject {
            put("config", buildJsonObject {
                put("setting1", "value1")
                put("setting2", "value2")
            })
            put("simpleKey", "simpleValue")
        }

        val nestedJsonValue = buildJsonObject {
            put("setting2", "updatedValue")
            put("setting3", "newValue")
        }

        val customBodies = listOf(
            CustomBody("config", nestedJsonValue)
        )

        val result = originalJson.mergeCustomBody(customBodies, ownership)

        val config = result["config"] as JsonObject
        assertEquals("value1", config["setting1"]?.toString()?.trim('"'))
        assertEquals("updatedValue", config["setting2"]?.toString()?.trim('"'))
        assertEquals("newValue", config["setting3"]?.toString()?.trim('"'))
        assertEquals("simpleValue", result["simpleKey"]?.toString()?.trim('"'))
    }

    @Test
    fun `mergeCustomBody should handle deeply nested JsonObjects`() {
        val originalJson = buildJsonObject {
            put("level1", buildJsonObject {
                put("level2", buildJsonObject {
                    put("setting1", "original")
                })
            })
        }

        val nestedValue = buildJsonObject {
            put("level2", buildJsonObject {
                put("setting1", "updated")
                put("setting2", "new")
            })
        }

        val customBodies = listOf(
            CustomBody("level1", nestedValue)
        )

        val result = originalJson.mergeCustomBody(customBodies, ownership)

        val level1 = result["level1"] as JsonObject
        val level2 = level1["level2"] as JsonObject

        assertEquals("updated", level2["setting1"]?.toString()?.trim('"'))
        assertEquals("new", level2["setting2"]?.toString()?.trim('"'))
    }

    @Test
    fun `mergeCustomBody should ignore empty keys`() {
        val originalJson = buildJsonObject {
            put("key1", "value1")
        }

        val customBodies = listOf(
            CustomBody("", JsonPrimitive("ignored")),
            CustomBody("key2", JsonPrimitive("value2"))
        )

        val result = originalJson.mergeCustomBody(customBodies, ownership)

        assertEquals(2, result.size)
        assertEquals("value1", result["key1"]?.toString()?.trim('"'))
        assertEquals("value2", result["key2"]?.toString()?.trim('"'))
    }

    @Test
    fun `mergeCustomBody should throw for reserved keys`() {
        val originalJson = buildJsonObject {
            put("model", "original-model")
        }

        val customBodies = listOf(
            CustomBody("model", JsonPrimitive("overridden")),
        )

        val error = assertThrows(CustomBodyReservedKeyException::class.java) {
            originalJson.mergeCustomBody(customBodies, ownership)
        }
        assertEquals("test", error.protocol)
        assertEquals("custom_body_reserved_key", error.reason)
        assertTrue(error.conflictingKeys.contains("model"))
    }

    @Test
    fun `mergeCustomBody should throw for multiple reserved keys with sorted diagnostics`() {
        val originalJson = buildJsonObject {}

        val customBodies = listOf(
            CustomBody("tools", JsonPrimitive("[]")),
            CustomBody("messages", JsonPrimitive("[]")),
            CustomBody("model", JsonPrimitive("override")),
        )

        val error = assertThrows(CustomBodyReservedKeyException::class.java) {
            originalJson.mergeCustomBody(customBodies, ownership)
        }
        assertEquals(listOf("messages", "model", "tools"), error.conflictingKeys.sorted())
    }

    @Test
    fun `mergeCustomBody should allow non-reserved keys alongside reserved conflicts`() {
        val originalJson = buildJsonObject {
            put("temperature", 0.7)
        }

        // No conflict here since temperature is not reserved
        val customBodies = listOf(
            CustomBody("temperature", JsonPrimitive(0.5)),
            CustomBody("top_p", JsonPrimitive(0.9)),
        )

        val result = originalJson.mergeCustomBody(customBodies, ownership)
        assertEquals("0.5", result["temperature"]?.toString())
        assertEquals("0.9", result["top_p"]?.toString())
    }

    @Test
    fun `every text protocol top level ownership key is rejected`() {
        listOf(
            CHAT_COMPLETIONS_OWNERSHIP,
            RESPONSES_OWNERSHIP,
            CLAUDE_MESSAGES_OWNERSHIP,
            GEMINI_OWNERSHIP,
        ).forEach { protocolOwnership ->
            protocolOwnership.reservedKeys.forEach { key ->
                val error = assertThrows(CustomBodyReservedKeyException::class.java) {
                    buildJsonObject {}.mergeCustomBody(
                        listOf(CustomBody(key, JsonPrimitive("override"))),
                        protocolOwnership,
                    )
                }
                assertEquals(protocolOwnership.protocol, error.protocol)
                assertEquals(listOf(key), error.conflictingKeys)
            }
        }
    }

    @Test
    fun `custom body cannot replace builder owned object shape with scalar`() {
        val base = buildJsonObject {
            put("generationConfig", buildJsonObject {
                put("thinkingConfig", buildJsonObject { put("includeThoughts", true) })
            })
        }

        val topLevel = assertThrows(CustomBodyReservedKeyException::class.java) {
            base.mergeCustomBody(
                listOf(CustomBody("generationConfig", JsonPrimitive("invalid"))),
                ownership,
            )
        }
        assertEquals(listOf("generationConfig"), topLevel.conflictingKeys)

        val nested = assertThrows(CustomBodyReservedKeyException::class.java) {
            base.mergeCustomBody(
                listOf(CustomBody("generationConfig", buildJsonObject {
                    put("thinkingConfig", JsonPrimitive("invalid"))
                })),
                ownership,
            )
        }
        assertEquals(listOf("generationConfig.thinkingConfig"), nested.conflictingKeys)
    }

    @Test
    fun `custom body cannot replace builder owned object shape with null or array`() {
        val base = buildJsonObject {
            put("generationConfig", buildJsonObject {
                put("candidateCount", 1)
            })
        }

        listOf(JsonNull, buildJsonArray { add(1) }).forEach { replacement ->
            val error = assertThrows(CustomBodyReservedKeyException::class.java) {
                base.mergeCustomBody(
                    listOf(CustomBody("generationConfig", replacement)),
                    ownership,
                )
            }
            assertEquals(listOf("generationConfig"), error.conflictingKeys)
        }
    }

}
