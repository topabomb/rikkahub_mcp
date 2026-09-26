package net.weero.measix.pilot.data.enterprise

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class EnterpriseStarterOpeningTest {
    private val json = EnterpriseConfigurationCodec.json

    @Test
    fun `opening preserves explicit empty system and ordered literal backgrounds`() {
        val opening = EnterpriseStarterOpeningSnapshot(
            format = 1,
            systemPrompt = "",
            initialContexts = listOf(
                EnterpriseStarterInitialContext("z", "背景二", "  {{user}}\n<state>literal</state>  "),
                EnterpriseStarterInitialContext("a", "背景一", ""),
            ),
        )
        val encoded = json.encodeToJsonElement(EnterpriseStarterOpeningSnapshot.serializer(), opening)
        assertEquals(opening, json.decodeFromJsonElement(EnterpriseStarterOpeningSnapshot.serializer(), encoded))
        assertEquals(listOf("z", "a"), opening.initialContexts.map { it.id })
        assertEquals("  {{user}}\n<state>literal</state>  ", opening.initialContexts.first().content)
    }

    @Test
    fun `opening rejects unknown format duplicate IDs and blank identities`() {
        val block = EnterpriseStarterInitialContext("background", "背景", "literal")
        assertThrows(IllegalArgumentException::class.java) {
            EnterpriseStarterOpeningSnapshot(2, "", emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            EnterpriseStarterOpeningSnapshot(1, "", listOf(block, block.copy(content = "different")))
        }
        assertThrows(IllegalArgumentException::class.java) { block.copy(id = " \n") }
        assertThrows(IllegalArgumentException::class.java) { block.copy(title = "\t") }
    }

    @Test
    fun `historical starter missing opening stays absent and preserves its full definition`() {
        val raw = """{"id":"starter","assistantId":"assistant","title":"开场","prompt":" 原草稿 ","description":"说明","sortOrder":12,"enabled":false}"""
        val old = json.decodeFromString<EnterpriseStarter>(raw)
        assertNull(old.openingSnapshot)
        assertEquals(" 原草稿 ", old.prompt)
        assertEquals("说明", old.description)
        assertEquals(12, old.sortOrder)
        assertFalse(old.enabled)
        val fresh = old.copy(openingSnapshot = EnterpriseStarterOpeningSnapshot(1, "", emptyList()))
        assertNotEquals(old, fresh)
        assertEquals(fresh, json.decodeFromString<EnterpriseStarter>(json.encodeToString(EnterpriseStarter.serializer(), fresh)))
    }
}
