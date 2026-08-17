package net.weero.measix.pilot.service

import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import net.weero.measix.pilot.data.files.FilesManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class SubAssistantChildPartsTest {
    @Test
    fun `child user parts are request text plus images`() {
        val image = UIMessagePart.Image(url = "file:///tmp/a.png")
        val parts = buildChildUserParts("do the work", listOf(image))
        assertEquals(2, parts.size)
        assertEquals("do the work", (parts[0] as UIMessagePart.Text).text)
        assertEquals(image, parts[1])
    }

    @Test
    fun `clone copy keeps attachment ref on non-file parts`() {
        val ref = AttachmentRefs.format(Uuid.random())
        val image = UIMessagePart.Image(
            url = "https://example.com/a.png",
            metadata = buildJsonObject {
                put(AttachmentRefs.METADATA_KEY, ref)
                put("thoughtSignature", "sig")
            },
        )
        val filesManager = mockk<FilesManager>(relaxed = true)
        val copied = copyPartForChildClone(image, filesManager) as UIMessagePart.Image
        assertEquals(ref, AttachmentRefs.getRef(copied))
        assertEquals("sig", (copied.metadata!!["thoughtSignature"] as JsonPrimitive).content)
        assertEquals(image.url, copied.url)
    }

    @Test
    fun `clone copy of tool output preserves nested ref`() {
        val ref = AttachmentRefs.format(Uuid.random())
        val tool = UIMessagePart.Tool(
            toolCallId = "t",
            toolName = "generate_image",
            input = "{}",
            output = listOf(
                UIMessagePart.Image(
                    url = "https://example.com/a.png",
                    metadata = buildJsonObject { put(AttachmentRefs.METADATA_KEY, ref) },
                ),
            ),
        )
        val copied = copyPartForChildClone(tool, mockk(relaxed = true)) as UIMessagePart.Tool
        assertEquals(ref, AttachmentRefs.getRef(copied.output.single()))
        assertTrue(copied.output.single() is UIMessagePart.Image)
    }
}
