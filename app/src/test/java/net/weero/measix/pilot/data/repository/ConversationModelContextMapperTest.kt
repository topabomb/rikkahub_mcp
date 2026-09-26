package net.weero.measix.pilot.data.repository

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import net.weero.measix.pilot.data.db.entity.ConversationModelContextEntity
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.model.ConversationContextBody
import net.weero.measix.pilot.data.model.ConversationContextCodec
import net.weero.measix.pilot.data.model.contextEntryIdentity
import net.weero.measix.pilot.service.runtime.disclosurePayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationModelContextMapperTest {
    private val owner = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
    private val anchor = UIMessage.user("request")
    private val ownerNode = MessageNode.of(owner)
    private val anchorNode = MessageNode.of(anchor)

    private fun row(
        ownerNodeId: Uuid = ownerNode.id,
        ownerMessageId: Uuid = owner.id,
        anchorNodeId: Uuid = anchorNode.id,
        anchorMessageId: Uuid = anchor.id,
        content: String = "canonical",
        occurrence: Int = 0,
        stepId: Uuid? = null,
    ) = ConversationModelContextEntity(
        id = contextEntryIdentity(ownerNodeId, ownerMessageId, occurrence).toString(),
        ownerMessageId = ownerMessageId.toString(),
        ownerNodeId = ownerNodeId.toString(),
        anchorNodeId = anchorNodeId.toString(),
        anchorMessageId = anchorMessageId.toString(),
        occurrence = occurrence,
        stepId = stepId?.toString(),
        sourceKind = "disclosure",
        payload = ConversationContextCodec.encode(disclosurePayload(content)),
    )

    @Test
    fun `valid owner and anchor locators map exactly`() {
        val mapped = mapModelContextEntries(
            rows = listOf(row()),
            nodes = listOf(anchorNode, ownerNode),
            conversationId = "conversation",
            validateContent = {},
        ).single()

        assertEquals(ownerNode.id, mapped.ownerNodeId)
        assertEquals(owner.id, mapped.ownerMessageId)
        assertEquals(anchorNode.id, mapped.anchorNodeId)
        assertEquals(anchor.id, mapped.anchorMessageId)
    }

    @Test
    fun `missing node or message membership fails closed`() {
        assertThrows(ConversationModelContextIntegrityException::class.java) {
            mapModelContextEntries(listOf(row(ownerNodeId = Uuid.random())), listOf(anchorNode, ownerNode), "conversation", {})
        }
        assertThrows(ConversationModelContextIntegrityException::class.java) {
            mapModelContextEntries(listOf(row(ownerMessageId = Uuid.random())), listOf(anchorNode, ownerNode), "conversation", {})
        }
    }

    @Test
    fun `wrong owner or anchor role fails closed`() {
        val userOwner = UIMessage.user("not assistant")
        val userOwnerNode = MessageNode.of(userOwner)
        assertThrows(ConversationModelContextIntegrityException::class.java) {
            mapModelContextEntries(
                listOf(row(ownerNodeId = userOwnerNode.id, ownerMessageId = userOwner.id)),
                listOf(anchorNode, userOwnerNode),
                "conversation",
                {},
            )
        }
        val assistantAnchor = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
        val assistantAnchorNode = MessageNode.of(assistantAnchor)
        assertThrows(ConversationModelContextIntegrityException::class.java) {
            mapModelContextEntries(
                listOf(row(anchorNodeId = assistantAnchorNode.id, anchorMessageId = assistantAnchor.id)),
                listOf(assistantAnchorNode, ownerNode),
                "conversation",
                {},
            )
        }
    }

    @Test
    fun `load mapper accepts a supported envelope that is not renderer-canonical bytes`() {
        val spaced = """{"type":"conversation_disclosure_snapshot", "format":2,"memory":{"enabled":false,"scope":"disabled","header":["id","content"],"rows":[]},"sub_assistants":{"mode":"disabled","header":["id","name","description"],"rows":[]},"enterprise_memory_seeds":{"header":["id","content"],"rows":[]}}"""
        val mapped = mapModelContextEntries(
            rows = listOf(row(content = spaced)),
            nodes = listOf(anchorNode, ownerNode),
            conversationId = "conversation",
        ).single()
        assertEquals(spaced, (mapped.payload.body as ConversationContextBody.Inline).text)
    }

    @Test
    fun `invalid envelope and duplicate message identity fail closed`() {
        assertThrows(Exception::class.java) {
            mapModelContextEntries(
                rows = listOf(row(content = "{\"type\":\"unknown\",\"format\":1}")),
                nodes = listOf(anchorNode, ownerNode),
                conversationId = "conversation",
            )
        }
        listOf(
            MessageNode.of(owner.copy()),
            MessageNode.of(anchor.copy()),
        ).forEach { duplicateNode ->
            assertThrows(ConversationModelContextIntegrityException::class.java) {
                mapModelContextEntries(
                    rows = listOf(row()),
                    nodes = listOf(anchorNode, ownerNode, duplicateNode),
                    conversationId = "conversation",
                    validateContent = {},
                )
            }
        }
    }
    @Test
    fun `several occurrences can share a variant but duplicate identities and occurrences cannot`() {
        val rows = listOf(row(content = "first"), row(content = "second", occurrence = 1))
        val mapped = mapModelContextEntries(rows, listOf(anchorNode, ownerNode), "conversation", {})
        assertEquals(listOf(0, 1), mapped.map { it.occurrence })
        assertEquals(2, mapped.map { it.id }.distinct().size)
        assertThrows(ConversationModelContextIntegrityException::class.java) {
            mapModelContextEntries(listOf(rows[0], rows[0]), listOf(anchorNode, ownerNode), "conversation", {})
        }
        assertThrows(ConversationModelContextIntegrityException::class.java) {
            mapModelContextEntries(listOf(rows[0], rows[0].copy(id = Uuid.random().toString())),
                listOf(anchorNode, ownerNode), "conversation", {})
        }
    }

    @Test
    fun `source discriminator and step membership must match persisted payload and transcript`() {
        assertThrows(ConversationModelContextIntegrityException::class.java) {
            mapModelContextEntries(listOf(row().copy(sourceKind = "system")), listOf(anchorNode, ownerNode), "conversation", {})
        }
        assertThrows(ConversationModelContextIntegrityException::class.java) {
            mapModelContextEntries(listOf(row(stepId = Uuid.random())), listOf(anchorNode, ownerNode), "conversation", {})
        }
    }

}
