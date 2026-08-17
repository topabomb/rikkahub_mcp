package net.weero.measix.pilot.data.ai.attachments

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class AttachmentRefsTest {
    @Test
    fun `ensureAttachmentRef is idempotent and keeps existing id`() {
        val image = UIMessagePart.Image(url = "file:///tmp/a.png")
        val stamped = AttachmentRefs.ensureAttachmentRef(image)
        val again = AttachmentRefs.ensureAttachmentRef(stamped)
        assertSame(stamped, again)
        assertNotNull(AttachmentRefs.getRef(stamped))
        assertEquals(AttachmentRefs.getRef(stamped), AttachmentRefs.getRef(again))
    }

    @Test
    fun `ensureAttachmentRef merges metadata without dropping other keys`() {
        val image = UIMessagePart.Image(
            url = "file:///tmp/a.png",
            metadata = buildJsonObject {
                put("thoughtSignature", "keep-me")
                put("artifact", "also-keep")
            },
        )
        val stamped = AttachmentRefs.ensureAttachmentRef(image) as UIMessagePart.Image
        val metadata = stamped.metadata!!
        assertEquals("keep-me", metadata["thoughtSignature"]?.let { (it as JsonPrimitive).content })
        assertEquals("also-keep", metadata["artifact"]?.let { (it as JsonPrimitive).content })
        assertTrue(AttachmentRefs.getRef(stamped)!!.startsWith(AttachmentRefs.PREFIX))
    }

    @Test
    fun `ensureAttachmentRef rebuilds malformed existing refs`() {
        val image = UIMessagePart.Image(
            url = "file:///tmp/a.png",
            metadata = buildJsonObject {
                put("attachment_ref", "not-a-valid-handle")
            },
        )
        val stamped = AttachmentRefs.ensureAttachmentRef(image) as UIMessagePart.Image
        val ref = AttachmentRefs.getRef(stamped)!!
        assertTrue(AttachmentRefs.parse(ref) != null)
        assertNotEquals("not-a-valid-handle", ref)
        // 重建后的合法 ref 再跑一遍是幂等的
        assertSame(stamped, AttachmentRefs.ensureAttachmentRef(stamped))
    }

    @Test
    fun `text parts are not stamped`() {
        val text = UIMessagePart.Text("hello")
        assertSame(text, AttachmentRefs.ensureAttachmentRef(text))
    }

    @Test
    fun `backfill stamps tool output images and writes conversation`() {
        val image = UIMessagePart.Image(url = "file:///tmp/tool.png")
        val tool = UIMessagePart.Tool(
            toolCallId = "c1",
            toolName = "generate_image",
            input = "{}",
            output = listOf(image),
            metadata = JsonObject(mapOf("thoughtSignature" to JsonPrimitive("sig"))),
        )
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool))
        val conversation = Conversation.ofId(
            id = Uuid.random(),
            assistantId = Uuid.random(),
            messages = listOf(net.weero.measix.pilot.data.model.MessageNode.of(message)),
        )
        val backfilled = AttachmentRefs.backfillConversation(conversation)
        assertNotEquals(conversation, backfilled)
        val stampedTool = backfilled.currentMessages.single().parts.single() as UIMessagePart.Tool
        assertEquals("sig", (stampedTool.metadata!!["thoughtSignature"] as JsonPrimitive).content)
        val stampedImage = stampedTool.output.single() as UIMessagePart.Image
        assertNotNull(AttachmentRefs.getRef(stampedImage))
        assertSame(backfilled, AttachmentRefs.backfillConversation(backfilled))
    }
}
