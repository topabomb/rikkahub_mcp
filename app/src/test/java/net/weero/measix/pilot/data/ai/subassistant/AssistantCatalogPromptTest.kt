package net.weero.measix.pilot.data.ai.subassistant

import kotlinx.serialization.json.Json
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class AssistantCatalogPromptTest {

    private val json: Json = JsonInstant
    private val callerId = Uuid.random()

    private fun makeAssistant(
        id: Uuid = Uuid.random(),
        name: String = "Assistant",
        description: String = "A helpful assistant",
        allowAsSub: Boolean = true,
        isGloballyVisible: Boolean = false,
        allowedSubAssistantIds: Set<Uuid> = emptySet(),
    ) = Assistant(
        id = id,
        name = name,
        description = description,
        allowAsSubAssistant = allowAsSub,
        isSubAssistantGloballyVisible = isGloballyVisible,
        allowedSubAssistantIds = allowedSubAssistantIds,
    )

    private fun makeCaller(
        allowedIds: Set<Uuid> = emptySet(),
    ) = makeAssistant(
        id = callerId,
        name = "Caller",
        description = "Caller",
        allowAsSub = false,
        allowedSubAssistantIds = allowedIds,
    )

    // ---- buildAssistantCatalog ----

    @Test
    fun `delegation_only only includes accessible sub-assistants`() {
        val target1 = makeAssistant(name = "Accessible", description = "Can delegate")
        val target2 = makeAssistant(name = "NotAccessible", description = "Cannot delegate")
        val caller = makeCaller(allowedIds = setOf(target1.id))
        val assistants = listOf(caller, target1, target2)

        val catalog = buildAssistantCatalog(caller, assistants, CatalogMode.DELEGATION_ONLY, json)

        assertTrue(catalog.contains("Accessible"))
        assertFalse(catalog.contains("NotAccessible"))
    }

    @Test
    fun `management_only includes accessible sub-assistants only`() {
        val target1 = makeAssistant(name = "Accessible", description = "Desc1")
        val target2 = makeAssistant(name = "NotAccessible", description = "Desc2")
        val caller = makeCaller(allowedIds = setOf(target1.id))
        val assistants = listOf(caller, target1, target2)

        val catalog = buildAssistantCatalog(caller, assistants, CatalogMode.MANAGEMENT_ONLY, json)

        assertTrue(catalog.contains("Accessible"))
        assertFalse(catalog.contains("NotAccessible"))
    }

    @Test
    fun `both mode includes accessible sub-assistants only`() {
        val target1 = makeAssistant(name = "Accessible")
        val target2 = makeAssistant(name = "NotAccessible")
        val caller = makeCaller(allowedIds = setOf(target1.id))
        val assistants = listOf(caller, target1, target2)

        val catalog = buildAssistantCatalog(caller, assistants, CatalogMode.BOTH, json)

        assertTrue(catalog.contains("Accessible"))
        assertFalse(catalog.contains("NotAccessible"))
    }

    @Test
    fun `caller is always excluded`() {
        val caller = makeCaller()
        val target = makeAssistant(name = "Other", isGloballyVisible = true)
        val assistants = listOf(caller, target)
        val catalog = buildAssistantCatalog(caller, assistants, CatalogMode.MANAGEMENT_ONLY, json)

        assertFalse(catalog.contains("Caller"))
        assertTrue(catalog.contains("Other"))
    }

    @Test
    fun `globally visible targets are accessible without explicit allow`() {
        val target = makeAssistant(name = "Global", isGloballyVisible = true)
        val caller = makeCaller(allowedIds = emptySet())
        val assistants = listOf(caller, target)

        val catalog = buildAssistantCatalog(caller, assistants, CatalogMode.DELEGATION_ONLY, json)

        assertTrue(catalog.contains("Global"))
    }

    @Test
    fun `header always uses id name description without callable`() {
        val target = makeAssistant(name = "A1", isGloballyVisible = true)
        val caller = makeCaller()
        val assistants = listOf(caller, target)

        for (mode in CatalogMode.entries) {
            val catalog = buildAssistantCatalog(caller, assistants, mode, json)
            assertTrue(catalog.contains("header"))
            assertTrue(catalog.contains("\"id\""))
            assertTrue(catalog.contains("\"name\""))
            assertTrue(catalog.contains("\"description\""))
            assertFalse(catalog.contains("callable"))
        }
    }

    @Test
    fun `empty list produces empty rows`() {
        val caller = makeCaller()
        val assistants = listOf(caller)
        val catalog = buildAssistantCatalog(caller, assistants, CatalogMode.MANAGEMENT_ONLY, json)

        assertTrue(catalog.contains("header"))
        assertTrue(catalog.contains("\"rows\":[]"))
    }

    // ---- buildCatalogPrompt ----

    @Test
    fun `all modes use sub_assistant_catalog tag`() {
        val target = makeAssistant(name = "Helper", description = "Helps", isGloballyVisible = true)
        val caller = makeCaller()
        val assistants = listOf(caller, target)

        for (mode in CatalogMode.entries) {
            val prompt = buildCatalogPrompt(caller, assistants, mode, json)
            assertTrue("Mode $mode should use sub_assistant_catalog tag", prompt.contains("<sub_assistant_catalog>"))
            assertTrue("Mode $mode should close tag", prompt.contains("</sub_assistant_catalog>"))
        }
    }

    @Test
    fun `prompt includes header rows format`() {
        val target = makeAssistant(name = "Helper", description = "Helps", isGloballyVisible = true)
        val caller = makeCaller()
        val assistants = listOf(caller, target)

        val prompt = buildCatalogPrompt(caller, assistants, CatalogMode.DELEGATION_ONLY, json)

        assertTrue(prompt.contains("header"))
        assertTrue(prompt.contains("rows"))
        assertTrue(prompt.contains("untrusted data"))
    }

    @Test
    fun `empty catalog still outputs header and empty rows`() {
        val caller = makeCaller()
        val assistants = listOf(caller)
        val prompt = buildCatalogPrompt(caller, assistants, CatalogMode.DELEGATION_ONLY, json)

        // Design doc: 空列表仍输出相同 header 与空 rows，不再追加解释性文案
        assertTrue(prompt.contains("header"))
        assertTrue(prompt.contains("\"rows\":[]"))
    }

    @Test
    fun `xml-like delimiters are escaped`() {
        val target = makeAssistant(name = "Test<script>", description = "Desc with <tag>", isGloballyVisible = true)
        val caller = makeCaller()
        val assistants = listOf(caller, target)

        val prompt = buildCatalogPrompt(caller, assistants, CatalogMode.DELEGATION_ONLY, json)

        // < and > should be escaped as Unicode escapes in the JSON
        assertFalse("Should not contain raw <script>", prompt.contains("<script>"))
        assertTrue("Should contain escaped version", prompt.contains("\\u003c"))
        assertTrue("Should contain escaped version", prompt.contains("\\u003e"))
    }

    // ---- resolveCatalogMode ----

    @Test
    fun `resolveCatalogMode both when both enabled`() {
        assertEquals(CatalogMode.BOTH, resolveCatalogMode(true, true))
    }

    @Test
    fun `resolveCatalogMode management only when only management`() {
        assertEquals(CatalogMode.MANAGEMENT_ONLY, resolveCatalogMode(true, false))
    }

    @Test
    fun `resolveCatalogMode delegation only when only delegation`() {
        assertEquals(CatalogMode.DELEGATION_ONLY, resolveCatalogMode(false, true))
    }

    @Test
    fun `resolveCatalogMode null when neither enabled`() {
        assertEquals(null, resolveCatalogMode(false, false))
    }

    // ---- Order stability ----

    @Test
    fun `catalog preserves settings order`() {
        val a1 = makeAssistant(name = "Zebra", isGloballyVisible = true)
        val a2 = makeAssistant(name = "Alpha", isGloballyVisible = true)
        val a3 = makeAssistant(name = "Middle", isGloballyVisible = true)
        val caller = makeCaller()
        val assistants = listOf(a1, a2, a3, caller)

        val prompt = buildCatalogPrompt(caller, assistants, CatalogMode.DELEGATION_ONLY, json)

        val zebraIdx = prompt.indexOf("Zebra")
        val alphaIdx = prompt.indexOf("Alpha")
        val middleIdx = prompt.indexOf("Middle")

        assertTrue(zebraIdx < alphaIdx)
        assertTrue(alphaIdx < middleIdx)
    }
}
