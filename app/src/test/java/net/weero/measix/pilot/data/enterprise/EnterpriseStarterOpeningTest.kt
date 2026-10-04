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
                EnterpriseStarterInitialContext("z", "  {{user}}\n<state>literal</state>  "),
                EnterpriseStarterInitialContext("a", ""),
            ),
        )
        val encoded = json.encodeToJsonElement(EnterpriseStarterOpeningSnapshot.serializer(), opening)
        assertEquals(opening, json.decodeFromJsonElement(EnterpriseStarterOpeningSnapshot.serializer(), encoded))
        assertEquals(listOf("z", "a"), opening.initialContexts.map { it.id })
        assertEquals("  {{user}}\n<state>literal</state>  ", opening.initialContexts.first().content)
    }

    @Test
    fun `opening rejects unknown format duplicate IDs and blank identities`() {
        val block = EnterpriseStarterInitialContext("background", "literal")
        assertThrows(IllegalArgumentException::class.java) {
            EnterpriseStarterOpeningSnapshot(2, "", emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            EnterpriseStarterOpeningSnapshot(1, "", listOf(block, block.copy(content = "different")))
        }
        assertThrows(IllegalArgumentException::class.java) { block.copy(id = " \n") }
    }

    @Test
    fun `stored starter discards retired display metadata while preserving content`() {
        val raw = """{"id":"starter","assistantId":"assistant","title":"开场","prompt":" 原草稿 ","description":"说明","sortOrder":12,"enabled":false}"""
        val old = json.decodeFromString(StoredStarterSerializer, raw)
        assertNull(old.openingSnapshot)
        assertEquals(" 原草稿 ", old.prompt)
        assertFalse(json.encodeToString(EnterpriseStarter.serializer(), old).contains("description"))
        assertEquals(12, old.sortOrder)
        assertFalse(old.enabled)
        val fresh = old.copy(openingSnapshot = EnterpriseStarterOpeningSnapshot(1, "", emptyList()))
        assertNotEquals(old, fresh)
        assertEquals(fresh, json.decodeFromString<EnterpriseStarter>(json.encodeToString(EnterpriseStarter.serializer(), fresh)))
    }
}
