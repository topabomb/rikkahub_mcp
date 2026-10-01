package net.weero.measix.pilot.ui.components.message

import me.rerere.ai.ui.UIMessagePart
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import net.weero.measix.pilot.service.AttachmentPreview
import net.weero.measix.pilot.service.ImageSource
import net.weero.measix.pilot.service.ImageOrigin
import kotlin.uuid.Uuid
import org.junit.Assert.*
import org.junit.Test

class CollectMessageImagesTest {
    private fun source(id: String) = ImageSource(id, ImageOrigin.UPLOAD, verifyAccess = {}, readPayload = { byteArrayOf() })

    @Test fun `album retains projected capabilities and order through tool output`() {
        val a = source("a")
        val b = source("b")
        val previews = mapOf("a" to AttachmentPreview("a", a), "b" to AttachmentPreview("b", b))
        val parts = listOf(
            UIMessagePart.Image("a"),
            UIMessagePart.Tool(localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = "call",
                toolName = "generate_image", input = "{}", output = listOf(UIMessagePart.Image("b"))),
            UIMessagePart.Image("a"),
            UIMessagePart.Image("unprojected"),
            UIMessagePart.Image("data:image/png;base64,"),
        )
        val images = collectMessageImages(parts, previews::get)
        assertEquals(listOf(a, b, a), images)
        assertSame(a, images[0])
        assertSame(b, images[1])
        assertTrue(collectMessageImages(parts).isEmpty())
    }

    @Test fun `stable reference selects the authorized image instead of the message path`() {
        val ref = AttachmentRefs.format(Uuid.random())
        val image = UIMessagePart.Image("file:///untrusted/image.png",
            metadata = buildJsonObject { put(AttachmentRefs.METADATA_KEY, ref) })
        val source = source("authorized")
        assertTrue(collectMessageImages(listOf(image)).isEmpty())
        assertSame(source, collectMessageImages(listOf(image)) { candidate ->
            AttachmentPreview("file:///safe/image.png", source).takeIf { candidate == ref }
        }.single())
    }

    @Test fun `streaming placeholders never enter the album`() {
        for (url in listOf("", "   ", "data:image/png;base64,", "data:image/svg+xml;base64,\n")) {
            assertTrue(isImagePartLoading(url))
            assertTrue(collectMessageImages(listOf(UIMessagePart.Image(url))) { AttachmentPreview(url, source(url)) }.isEmpty())
        }
        assertFalse(isImagePartLoading("data:image/png;base64,QUJD"))
        assertFalse(isImagePartLoading("https://example.test/image.png"))
    }

    @Test fun `failed stable projection does not fall back to path capability or advertise export url`() {
        val ref = AttachmentRefs.format(Uuid.random())
        val url = "file:///managed/failed.png"
        val image = UIMessagePart.Image(url, metadata = buildJsonObject { put(AttachmentRefs.METADATA_KEY, ref) })
        val failed = AttachmentPreview("", null, diagnostic = "IOException: metadata unavailable")
        val previews = mapOf(ref to failed, url to AttachmentPreview(url, source("stale-path")))

        assertSame(failed, resolveAttachmentPreview(image, previews::get))
        assertNull(resolveAttachmentImageSource(image, previews::get))
        assertTrue(collectMessageImages(listOf(image), previews::get).isEmpty())
        assertNull(resolveAttachmentMediaUrl(url, image, previews::get))
    }
}
