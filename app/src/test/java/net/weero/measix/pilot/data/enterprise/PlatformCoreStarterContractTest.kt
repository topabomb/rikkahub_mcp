package net.weero.measix.pilot.data.enterprise

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PlatformCoreStarterContractTest {
    @Test
    fun `Core v5 fixture preserves the compiled opening and version`() {
        val cases = requireNotNull(javaClass.getResourceAsStream("/contracts/platform/cases.json"))
            .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonArray }
        val raw = cases.single { it.jsonObject.getValue("name").jsonPrimitive.content == "v5-full" }
            .jsonObject.getValue("value").jsonObject
        val decoded = PlatformWireCodec.decode<PlatformManagedSnapshot>(raw.toString())
        assertEquals(5L, decoded.schemaVersion)
        assertTrue(decoded.starters.isNotEmpty())
        raw.getValue("starters").jsonArray.forEachIndexed { index, value ->
            val original = value.jsonObject.getValue("openingSnapshot").jsonObject
            val opening = requireNotNull(decoded.starters[index].openingSnapshot)
            assertEquals(original.getValue("systemPrompt").jsonPrimitive.content, opening.systemPrompt)
            val blocks = original.getValue("initialContexts").jsonArray.map { it.jsonObject }
            assertEquals(blocks.map { it.getValue("id").jsonPrimitive.content }, opening.initialContexts.map { it.id })
            assertEquals(blocks.map { it.getValue("content").jsonPrimitive.content }, opening.initialContexts.map { it.content })
        }
    }
}
