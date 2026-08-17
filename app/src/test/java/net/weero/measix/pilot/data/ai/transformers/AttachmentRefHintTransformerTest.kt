package net.weero.measix.pilot.data.ai.transformers

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class AttachmentRefHintTransformerTest {
    @Test
    fun `hint is inserted before stamped images including tool output`() = runTest {
        val ref = AttachmentRefs.format(Uuid.parse("11111111-1111-1111-1111-111111111111"))
        val image = UIMessagePart.Image(
            url = "file:///tmp/shot.png",
            metadata = buildJsonObject { put(AttachmentRefs.METADATA_KEY, ref) },
        )
        val tool = UIMessagePart.Tool(
            toolCallId = "t1",
            toolName = "generate_image",
            input = "{}",
            output = listOf(image),
        )
        val original = listOf(
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(tool)),
        )

        val transformed = insertAttachmentRefHintsInMessages(original) { "screenshot.png" }

        val nested = (transformed.single().parts.single() as UIMessagePart.Tool).output
        assertEquals(2, nested.size)
        assertEquals(
            attachmentHintText(ref, "screenshot.png"),
            (nested[0] as UIMessagePart.Text).text,
        )
        assertEquals(image, nested[1])
        assertEquals(original[0].parts, listOf(tool))
    }

    @Test
    fun `unstamped images get no hint`() = runTest {
        val image = UIMessagePart.Image(url = "file:///tmp/a.png")
        val messages = listOf(UIMessage(role = MessageRole.USER, parts = listOf(image)))
        val transformed = insertAttachmentRefHintsInMessages(messages) { "a.png" }
        assertEquals(1, transformed.single().parts.size)
        assertTrue(transformed.single().parts.single() is UIMessagePart.Image)
    }
}
