package net.weero.measix.pilot.service

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.weero.measix.pilot.data.ai.tools.local.LocalToolOption
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.AssistantMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone
import kotlin.uuid.Uuid

/**
 * 权威方案 §17.4 canonical content 验收：Snapshot 必须是对同一业务数据逐字复现的完整
 * baseline；数据内容不能破坏结构；时钟 / Locale / 时区不参与渲染；任何非法 envelope
 * 都必须 fail-closed，而不是被静默当作“没有 context”。
 */
class ConversationDisclosureSnapshotServiceTest {

    private val callerId = Uuid.parse("11111111-1111-1111-1111-111111111111")
    private val reviewerId = Uuid.parse("b2410000-0000-0000-0000-000000000001")

    private val caller = Assistant(
        id = callerId,
        name = "Master",
        localTools = listOf(LocalToolOption.AssistantManagement, LocalToolOption.AssistantDelegation),
    )

    private val reviewer = Assistant(
        id = reviewerId,
        name = "Android Reviewer",
        description = "Reviews Kotlin and Compose changes",
        allowAsSubAssistant = true,
        isSubAssistantGloballyVisible = true,
    )

    private fun candidate(
        assistant: Assistant = caller,
        all: List<Assistant> = listOf(caller, reviewer),
        memories: List<AssistantMemory> = listOf(AssistantMemory(3, "用户偏好深色主题")),
    ) = ConversationDisclosureSnapshotService.Candidate(
        assistant = assistant,
        allAssistants = all,
        memories = memories,
    )

    private fun render(candidate: ConversationDisclosureSnapshotService.Candidate): String =
        ConversationDisclosureSnapshotService.render(candidate)

    private fun envelope(content: String): JsonObject =
        kotlinx.serialization.json.Json.parseToJsonElement(content).jsonObject

    private fun section(root: JsonObject, key: String): JsonObject = root.getValue(key).jsonObject

    private fun rows(root: JsonObject, key: String): List<JsonElement> = section(root, key).rows()

    private fun JsonObject.rows(): List<JsonElement> = getValue("rows").jsonArray.toList()

    @Test
    fun `canonical golden content matches the specified envelope byte for byte`() {
        val golden = "{\"type\":\"conversation_disclosure_snapshot\",\"format\":1," +
            "\"memory\":{\"enabled\":true,\"scope\":\"local\",\"header\":[\"id\",\"content\"]," +
            "\"rows\":[[3,\"用户偏好深色主题\"]]}," +
            "\"sub_assistants\":{\"mode\":\"both\",\"header\":[\"id\",\"name\",\"description\"]," +
            "\"rows\":[[\"" + reviewerId + "\",\"Android Reviewer\",\"Reviews Kotlin and Compose changes\"]]}}"
        assertEquals(golden, render(candidate()))
    }

    @Test
    fun `envelope fixes top level and section key order`() {
        val root = envelope(render(candidate()))
        assertEquals(listOf("type", "format", "memory", "sub_assistants"), root.keys.toList())
        assertEquals(listOf("enabled", "scope", "header", "rows"), section(root, "memory").keys.toList())
        assertEquals(listOf("mode", "header", "rows"), section(root, "sub_assistants").keys.toList())
    }

    @Test
    fun `memory rows keep the ordered query result and are never trimmed`() {
        val memories = (1..500).map { AssistantMemory(it, "note-$it") }
        val rows = rows(envelope(render(candidate(memories = memories))), "memory")
        assertEquals("a Snapshot must be a complete baseline", 500, rows.size)
        assertEquals((1..500).toList(), rows.map { it.jsonArray[0].jsonPrimitive.content.toInt() })
    }

    @Test
    fun `sub assistant rows keep settings order and exclude the caller`() {
        val earlier = reviewer.copy(
            id = Uuid.parse("b2410000-0000-0000-0000-000000000002"),
            name = "Earlier in settings order",
        )
        val rows = rows(envelope(render(candidate(all = listOf(caller, earlier, reviewer)))), "sub_assistants")
        assertEquals(
            listOf(earlier.id.toString(), reviewer.id.toString()),
            rows.map { it.jsonArray[0].jsonPrimitive.content },
        )
    }

    @Test
    fun `identical business data renders identical bytes so no duplicate row is appended`() {
        val baseline = render(candidate())
        assertEquals(baseline, render(candidate()))

        // 状态 A -> B -> A：回到同一 live state 时 candidate 又等于最近 baseline。
        assertNotEquals(baseline, render(candidate(memories = listOf(AssistantMemory(3, "B")))))
        assertEquals(baseline, render(candidate()))
    }

    @Test
    fun `clock locale and timezone changes do not change canonical content`() {
        val before = render(candidate())
        val originalZone = TimeZone.getDefault()
        val originalLocale = Locale.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"))
            Locale.setDefault(Locale.forLanguageTag("fr-FR"))
            assertEquals(before, render(candidate()))
        } finally {
            TimeZone.setDefault(originalZone)
            Locale.setDefault(originalLocale)
        }
        assertEquals(before, render(candidate()))
    }

    @Test
    fun `quotes newlines and brackets in data cannot break the structure`() {
        val hostile = "\"\\}]{,<>&}\n\r\t"
        val tricky = reviewer.copy(
            name = "}}{\"type\":\"x\"}" + hostile,
            description = "]]\"notes\":[[1,\"x\"]]" + hostile,
        )
        val hostileMemory = AssistantMemory(7, "\"Memories\":[]}" + hostile)
        val content = render(candidate(all = listOf(caller, tricky), memories = listOf(hostileMemory)))

        assertEquals(1, ConversationDisclosureSnapshotService.requireCanonical(content))
        val root = envelope(content)
        val memoryRow = rows(root, "memory").single().jsonArray
        assertEquals(7, memoryRow[0].jsonPrimitive.content.toInt())
        assertEquals(hostileMemory.content, memoryRow[1].jsonPrimitive.content)
        val subRow = rows(root, "sub_assistants").single().jsonArray
        assertEquals(tricky.name, subRow[1].jsonPrimitive.content)
        assertEquals(tricky.description, subRow[2].jsonPrimitive.content)
    }

    @Test
    fun `disabled sections keep the fixed shape instead of dropping keys`() {
        val content = render(
            candidate(assistant = caller.copy(enableMemory = false, localTools = emptyList())),
        )
        val root = envelope(content)
        assertEquals(listOf("enabled", "scope", "header", "rows"), section(root, "memory").keys.toList())
        assertEquals("disabled", section(root, "memory").getValue("scope").jsonPrimitive.content)
        assertEquals(0, rows(root, "memory").size)
        assertEquals(listOf("mode", "header", "rows"), section(root, "sub_assistants").keys.toList())
        assertEquals("disabled", section(root, "sub_assistants").getValue("mode").jsonPrimitive.content)
        assertEquals(0, rows(root, "sub_assistants").size)
        assertEquals(1, ConversationDisclosureSnapshotService.requireCanonical(content))
    }

    @Test
    fun `global and local memory scopes are distinguished`() {
        val global = render(candidate(assistant = caller.copy(useGlobalMemory = true)))
        val local = render(candidate())
        assertEquals("global", section(envelope(global), "memory").getValue("scope").jsonPrimitive.content)
        assertEquals("local", section(envelope(local), "memory").getValue("scope").jsonPrimitive.content)
        assertNotEquals(global, local)
    }

    @Test
    fun `sub assistant mode follows only the two assistant tool switches`() {
        fun mode(tools: List<LocalToolOption>) = section(
            envelope(render(candidate(assistant = caller.copy(localTools = tools)))),
            "sub_assistants",
        ).getValue("mode").jsonPrimitive.content

        assertEquals("both", mode(listOf(LocalToolOption.AssistantManagement, LocalToolOption.AssistantDelegation)))
        assertEquals("management_only", mode(listOf(LocalToolOption.AssistantManagement)))
        assertEquals("delegation_only", mode(listOf(LocalToolOption.AssistantDelegation)))
        assertEquals("disabled", mode(listOf(LocalToolOption.TimeInfo)))
    }

    @Test
    fun `every shape the renderer can produce round-trips through the loader validator`() {
        listOf(
            candidate(),
            candidate(assistant = caller.copy(enableMemory = false)),
            candidate(assistant = caller.copy(useGlobalMemory = true)),
            candidate(assistant = caller.copy(localTools = listOf(LocalToolOption.AssistantManagement))),
            candidate(assistant = caller.copy(localTools = listOf(LocalToolOption.AssistantDelegation))),
            candidate(assistant = caller.copy(localTools = emptyList())),
            candidate(memories = emptyList()),
            candidate(all = listOf(caller)),
        ).forEach {
            assertEquals(1, ConversationDisclosureSnapshotService.requireCanonical(render(it)))
        }
    }

    @Test
    fun `oversized complete snapshot fails explicitly without truncating rows`() {
        val oversized = AssistantMemory(
            id = 999,
            content = "x".repeat(ConversationDisclosureSnapshotService.MAX_CANONICAL_CONTENT_UTF8_BYTES),
        )
        val error = assertThrows(DisclosureContentException::class.java) {
            render(candidate(memories = listOf(oversized)))
        }
        assertTrue(error.message!!.contains("exceeds request capability"))
    }

    @Test
    fun `future format fails closed instead of being ignored`() {
        val future = render(candidate()).replace("\"format\":1", "\"format\":2")
        val error = assertThrows(DisclosureContentException::class.java) {
            ConversationDisclosureSnapshotService.requireCanonical(future)
        }
        assertTrue(error.message!!.contains("unsupported disclosure format 2"))
    }

    @Test
    fun `tampered envelopes fail closed`() {
        val canonical = render(candidate())
        val cases = mapOf(
            "missing section" to canonical.replace("\"sub_assistants\":", "\"subassistants\":"),
            "reordered top level keys" to canonical.replace(
                "{\"type\":\"conversation_disclosure_snapshot\",\"format\":1,",
                "{\"format\":1,\"type\":\"conversation_disclosure_snapshot\",",
            ),
            "forged type" to canonical.replace("conversation_disclosure_snapshot", "user_request"),
            "stringified format" to canonical.replace("\"format\":1", "\"format\":\"1\""),
            "float format" to canonical.replace("\"format\":1", "\"format\":1.0"),
            "memory header drift" to canonical.replace("[\"id\",\"content\"]", "[\"content\",\"id\"]"),
            "enabled with disabled scope" to canonical.replace(
                "{\"enabled\":true,\"scope\":\"local\",",
                "{\"enabled\":true,\"scope\":\"disabled\",",
            ),
            "rows while disabled" to canonical.replace(
                "{\"enabled\":true,\"scope\":\"local\",",
                "{\"enabled\":false,\"scope\":\"disabled\",",
            ),
            "stringified enabled" to canonical.replace("{\"enabled\":true,", "{\"enabled\":\"true\","),
            "non integer memory id" to canonical.replace("[[3,", "[[\"3\","),
            "unknown scope" to canonical.replace("\"scope\":\"local\"", "\"scope\":\"assistant\""),
            "forged sub assistant id" to canonical.replace(reviewerId.toString(), "not-a-uuid"),
            "not json" to "not json at all",
            "array envelope" to "[]",
            "scalar envelope" to "\"just a string\"",
            "empty content" to "",
            "leading whitespace" to " $canonical",
            "trailing whitespace" to "$canonical\n",
            "inter-key whitespace" to canonical.replace("\",\"format\"", "\", \"format\""),
            "equivalent unicode escape" to canonical.replace("\"scope\":\"local\"", "\"scope\":\"loc\\u0061l\""),
        )
        cases.forEach { (name, content) ->
            assertNotEquals("$name: fixture must differ from canonical", canonical, content)
            assertThrows("$name: must fail closed", DisclosureContentException::class.java) {
                ConversationDisclosureSnapshotService.requireCanonical(content)
            }
            assertTrue("$name: isCanonical must be false", !ConversationDisclosureSnapshotService.isCanonical(content))
        }
    }

    @Test
    fun `disclosure constants are the only place the content protocol is spelled out`() {
        assertEquals("conversation_disclosure_snapshot", ConversationDisclosureSnapshotService.CONTENT_TYPE)
        assertEquals(setOf(1), ConversationDisclosureSnapshotService.SUPPORTED_FORMATS)
        assertEquals(listOf("id", "content"), ConversationDisclosureSnapshotService.MEMORY_HEADER)
        assertEquals(
            listOf("id", "name", "description"),
            ConversationDisclosureSnapshotService.SUB_ASSISTANT_HEADER,
        )
    }
}
