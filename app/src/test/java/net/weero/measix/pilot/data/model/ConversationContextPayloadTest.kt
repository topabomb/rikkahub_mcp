package net.weero.measix.pilot.data.model

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.common.configuration.ConfigurationReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationContextPayloadTest {
    private val locator = ContextMessageLocator(Uuid.random(), Uuid.random())
    private val assistant = ConfigurationReference.parse("11111111-1111-1111-1111-111111111111")

    @Test fun `source definitions and literal rendered bytes survive codec independently`() {
        val rule = PromptInjection.ModeInjection(id = assistant, name = "Rule 名称", priority = 9,
            position = InjectionPosition.AT_DEPTH, injectDepth = -1, role = MessageRole.ASSISTANT,
            content = "Raw {{char}}\r\n{% if true %} data")
        val sources = listOf(
            ConversationContextSource.Disclosure(DisclosureNamespace("__global__", assistant),
                mapOf(DisclosureSection.MEMORY to ContextAdmissionReason.EXTERNAL_STATE,
                    DisclosureSection.SUB_ASSISTANTS to ContextAdmissionReason.BASELINE_RESTORE)),
            ConversationContextSource.System(listOf(SystemContextContribution(SystemContextKind.DOMAIN,
                "Domain", "Raw {{char}}", mapOf("char" to "值")))),
            ConversationContextSource.PromptRule(rule, mapOf("char" to "值 {{literal}}")),
            ConversationContextSource.MessageTime(locator, "2026-09-26T10:00:00", null, null, "Asia/Shanghai"),
            ConversationContextSource.Attachment(locator, 2, "file \"名字\".txt", AttachmentContextInput.DOCUMENT_TEXT,
                "attachment:11111111-1111-1111-1111-111111111111"),
            ConversationContextSource.Preset(assistant, 1),
            ConversationContextSource.HistorySummary(assistant, "Summarize {{literal}}"),
        )
        val text = "rendered 值\r\n<raw>& \"quotes\" {{literal}}"
        sources.forEach { source ->
            val payload = ConversationContextPayload(source = source, body = ConversationContextBody.Inline(text))
            val encoded = ConversationContextCodec.encode(payload)
            assertEquals(payload, ConversationContextCodec.decode(encoded))
            assertEquals(text, (ConversationContextCodec.decode(encoded).body as ConversationContextBody.Inline).text)
        }
    }

    @Test fun `historical unknown disclosure metadata is not turned into a current namespace or reason`() {
        val old = """{"type":"conversation_disclosure_snapshot", "format":1,"memory":{"enabled":false,"scope":"disabled","header":["id","content"],"rows":[]},"sub_assistants":{"mode":"disabled","header":["id","name","description"],"rows":[]}}"""
        val payload = ConversationContextPayload(source = ConversationContextSource.Disclosure(null),
            body = ConversationContextBody.Inline(old))
        assertEquals(payload, ConversationContextCodec.decode(ConversationContextCodec.encode(payload)))
        val decoded = ConversationContextCodec.decode(ConversationContextCodec.encode(payload))
        val source = decoded.source as ConversationContextSource.Disclosure
        assertEquals(null, source.namespace)
        assertTrue(source.reasons.isEmpty())
        assertEquals(old, (decoded.body as ConversationContextBody.Inline).text)
    }

    @Test fun `unknown payload and renderer versions and unknown fields fail closed`() {
        val payload = ConversationContextPayload(source = ConversationContextSource.PromptRule(
            PromptInjection.ModeInjection(content = "raw"), emptyMap()), body = ConversationContextBody.Inline("rendered"))
        val encoded = ConversationContextCodec.encode(payload)
        assertThrows(IllegalArgumentException::class.java) {
            ConversationContextCodec.decode(encoded.replace("\"version\":1", "\"version\":2"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConversationContextCodec.decode(encoded.replace("\"rendererVersion\":1", "\"rendererVersion\":2"))
        }
        assertThrows(SerializationException::class.java) {
            ConversationContextCodec.decode(encoded.dropLast(1) + ",\"future\":true}")
        }
    }

    @Test fun `reference paths and opening source cannot masquerade as another body kind`() {
        val source = ConversationContextSource.HistorySummary(null, "original")
        val artifact = ConversationContextPayload(source = source, body = ConversationContextBody.Artifact(7L, "upload/input.txt"))
        assertEquals(artifact, ConversationContextCodec.decode(ConversationContextCodec.encode(artifact)))
        listOf("", "/absolute", "../escape", "upload/../escape", "upload//empty", "C:/secret", "upload\\a").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { ConversationContextBody.Artifact(7L, path) }
        }
        assertThrows(IllegalArgumentException::class.java) { ConversationContextPayload(source = source, body = ConversationContextBody.Opening) }
        assertThrows(IllegalArgumentException::class.java) {
            ConversationContextPayload(source = ConversationContextSource.Starter, body = ConversationContextBody.Inline("fake"))
        }
        val opening = ConversationContextPayload(source = ConversationContextSource.Starter, body = ConversationContextBody.Opening)
        assertEquals(opening, ConversationContextCodec.decode(ConversationContextCodec.encode(opening)))
    }

    @Test fun `time predecessor identity and timestamp must be paired`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConversationContextSource.MessageTime(locator, "2026-09-26T10:00:00", locator, null, "UTC")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConversationContextSource.MessageTime(locator, "2026-09-26T10:00:00", null, "2026-09-26T09:00:00", "UTC")
        }
        assertThrows(IllegalArgumentException::class.java) { ContextPlacement.MessagePart(locator, -1) }
        assertThrows(IllegalArgumentException::class.java) { ConversationContextSource.Preset(assistant, -1) }
    }

    @Test fun `empty turn selection survives distinctly from enabled rules and every placement roundtrips`() {
        val selection = TurnContextSelection(systemEntryId = Uuid.random(), ruleEntryIds = emptyList(),
            timeReminderEnabled = false, timeZoneId = "UTC")
        assertEquals(selection, ConversationContextCodec.decodeSelection(ConversationContextCodec.encodeSelection(selection)))
        val rule = Uuid.random()
        assertThrows(IllegalArgumentException::class.java) { selection.copy(ruleEntryIds = listOf(rule, rule)) }
        assertThrows(IllegalArgumentException::class.java) { selection.copy(version = 2) }
        listOf(ContextPlacement.System, ContextPlacement.BeforeMessage(locator), ContextPlacement.MessagePart(locator, 0),
            ContextPlacement.BeforeStep(Uuid.random()), ContextPlacement.MessageOrigin).forEach { placement ->
            assertEquals(placement, ConversationContextCodec.decodePlacement(ConversationContextCodec.encodePlacement(placement)))
        }
    }

    @Test fun `admitted change facts roundtrip and old payloads retain unknown details`() {
        val change = DisclosureSectionChange(addedIds = listOf("2"),
            updatedBefore = listOf(JsonArray(listOf(JsonPrimitive(1), JsonPrimitive("before\r\ntext")))),
            attributesBefore = mapOf("scope" to JsonPrimitive("local")))
        val source = ConversationContextSource.Disclosure(null,
            mapOf(DisclosureSection.MEMORY to ContextAdmissionReason.EXTERNAL_STATE),
            mapOf(DisclosureSection.MEMORY to change))
        val payload = ConversationContextPayload(source = source, body = ConversationContextBody.Inline("unchanged model body"))
        assertEquals(payload, ConversationContextCodec.decode(ConversationContextCodec.encode(payload)))
        val historical = """{"version":1,"source":{"type":"disclosure","namespace":null,"reasons":{"MEMORY":"EXTERNAL_STATE"}},"body":{"type":"inline","text":"old bytes"}}"""
        val decoded = ConversationContextCodec.decode(historical)
        assertEquals(null, (decoded.source as ConversationContextSource.Disclosure).changes)
        assertEquals("old bytes", (decoded.body as ConversationContextBody.Inline).text)
    }

    @Test fun `change metadata rejects duplicate identities and invalid section attribution`() {
        assertThrows(IllegalArgumentException::class.java) {
            DisclosureSectionChange(addedIds = listOf("1"),
                removedBefore = listOf(JsonArray(listOf(JsonPrimitive(1), JsonPrimitive("old")))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConversationContextSource.Disclosure(null, mapOf(DisclosureSection.MEMORY to ContextAdmissionReason.INITIAL),
                mapOf(DisclosureSection.MEMORY to DisclosureSectionChange(addedIds = listOf("1"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConversationContextSource.Disclosure(null, mapOf(DisclosureSection.MEMORY to ContextAdmissionReason.EXTERNAL_STATE),
                mapOf(DisclosureSection.MEMORY to DisclosureSectionChange(attributesBefore = mapOf("mode" to JsonPrimitive("both")))))
        }
        assertThrows(IllegalArgumentException::class.java) { DisclosureSectionChange() }
    }
}
