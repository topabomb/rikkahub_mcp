package net.weero.measix.pilot.data.model

import kotlinx.serialization.SerializationException
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.data.enterprise.EnterpriseStarter
import net.weero.measix.pilot.data.enterprise.EnterpriseStarterInitialContext
import net.weero.measix.pilot.data.enterprise.EnterpriseStarterOpeningSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ConversationOpeningTest {

    @Test fun `stored opening retains content and provenance after retiring display metadata`() {
        val original = opening()
        val encoded = ConversationOpeningCodec.encode(original)
        val stored = encoded.replaceFirst("\"definition\":{", "\"definition\":{\"description\":\"old display only\",")
        assertEquals(original, ConversationOpeningCodec.decode(stored))
        assertEquals(encoded, ConversationOpeningCodec.encode(ConversationOpeningCodec.decode(stored)))
        assertThrows(SerializationException::class.java) {
            ConversationOpeningCodec.decode(encoded.replaceFirst("\"definition\":{", "\"definition\":{\"unknown\":true,"))
        }
    }
    private fun opening() = ConversationOpening(
        assistant = ConfigurationReference.parse("managed~dep_example~assistant_one") as ConfigurationReference.Enterprise,
        releaseId = "release-original", generation = 7, snapshotHash = "a".repeat(64),
        definition = EnterpriseStarter(id = "starter_one", assistantId = "assistant_one", title = "原开场",
            prompt = "  original prompt {{literal}}\r\n", sortOrder = 4,
            openingSnapshot = EnterpriseStarterOpeningSnapshot(1, "  system {{char}}\r\n", listOf(
                EnterpriseStarterInitialContext("b", "背景 {% raw %}"),
                EnterpriseStarterInitialContext("a", ""),
            ))),
    )

    @Test fun `opening preserves full published definition order and original whitespace`() {
        val original = opening()
        val decoded = ConversationOpeningCodec.decode(ConversationOpeningCodec.encode(original))
        assertEquals(original, decoded)
        val snapshot = requireNotNull(decoded.definition.openingSnapshot)
        assertEquals(listOf("b", "a"), snapshot.initialContexts.map { it.id })
        assertEquals("", snapshot.initialContexts.last().content)
        val explicitEmpty = original.copy(definition = original.definition.copy(
            openingSnapshot = EnterpriseStarterOpeningSnapshot(1, "", emptyList())))
        assertEquals(explicitEmpty, ConversationOpeningCodec.decode(ConversationOpeningCodec.encode(explicitEmpty)))
    }

    @Test fun `missing opening unknown formats and invalid source identity are rejected`() {
        val original = opening()
        assertThrows(IllegalArgumentException::class.java) { original.copy(format = 2) }
        assertThrows(IllegalArgumentException::class.java) { original.copy(releaseId = " ") }
        assertThrows(IllegalArgumentException::class.java) { original.copy(generation = -1) }
        assertThrows(IllegalArgumentException::class.java) { original.copy(snapshotHash = "") }
        assertThrows(IllegalArgumentException::class.java) { original.copy(definition = original.definition.copy(assistantId = "assistant_other")) }
        assertThrows(IllegalArgumentException::class.java) { original.copy(definition = original.definition.copy(openingSnapshot = null)) }
        val encoded = ConversationOpeningCodec.encode(original)
        assertThrows(IllegalArgumentException::class.java) { ConversationOpeningCodec.decode(encoded.replaceFirst("\"format\":1", "\"format\":2")) }
        assertThrows(SerializationException::class.java) { ConversationOpeningCodec.decode(encoded.dropLast(1) + ",\"future\":true}") }
    }
}
