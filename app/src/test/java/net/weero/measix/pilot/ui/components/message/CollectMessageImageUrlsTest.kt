package net.weero.measix.pilot.ui.components.message

import me.rerere.ai.ui.UIMessagePart
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectMessageImageUrlsTest {
    @Test
    fun `collects top level image urls in original order`() {
        val parts = listOf(
            UIMessagePart.Text("hi"),
            UIMessagePart.Image("https://example.test/a.png"),
            UIMessagePart.Text("mid"),
            UIMessagePart.Image("https://example.test/b.png"),
        )

        assertEquals(
            listOf("https://example.test/a.png", "https://example.test/b.png"),
            collectMessageImageUrls(parts),
        )
    }

    @Test
    fun `filters blank and streaming loading placeholders`() {
        val parts = listOf(
            UIMessagePart.Image(""),
            UIMessagePart.Image("data:image/png;base64,"),
            UIMessagePart.Image("data:image/png;base64,   "),
            UIMessagePart.Image("data:image/png;base64,iVBOR..."),
            UIMessagePart.Image("https://example.test/ok.png"),
        )

        assertEquals(
            listOf("data:image/png;base64,iVBOR...", "https://example.test/ok.png"),
            collectMessageImageUrls(parts),
        )
    }

    @Test
    fun `ignores non image parts and returns empty for no images`() {
        assertTrue(collectMessageImageUrls(emptyList()).isEmpty())
        assertTrue(
            collectMessageImageUrls(
                listOf(
                    UIMessagePart.Text("t"),
                    UIMessagePart.Reasoning("r"),
                )
            ).isEmpty()
        )
    }

    @Test
    fun `loading detection covers blank, empty base64 shell and real urls`() {
        assertTrue(isImagePartLoading(""))
        assertTrue(isImagePartLoading("   "))
        assertTrue(isImagePartLoading("data:image/jpeg;base64,"))
        assertFalse(isImagePartLoading("data:image/jpeg;base64,QUJD"))
        assertFalse(isImagePartLoading("https://example.com/x.png"))
    }

    @Test
    fun `loading detection handles svg subtype and trailing newline shell`() {
        assertTrue(isImagePartLoading("data:image/svg+xml;base64,"))
        assertTrue(isImagePartLoading("data:image/png;base64,\n"))
        assertFalse(isImagePartLoading("data:image/svg+xml;base64,PHN2Zw=="))
    }

    @Test
    fun `interleaved loading placeholders keep stable ordering for survivors`() {
        val parts = listOf(
            UIMessagePart.Image("data:image/png;base64,"),
            UIMessagePart.Image("https://example.test/a.png"),
            UIMessagePart.Image(""),
            UIMessagePart.Image("https://example.test/b.png"),
        )

        assertEquals(listOf("https://example.test/a.png", "https://example.test/b.png"), collectMessageImageUrls(parts))
    }

    @Test
    fun `duplicate urls are all kept`() {
        val parts = listOf(
            UIMessagePart.Image("https://example.test/same.png"),
            UIMessagePart.Image("https://example.test/same.png"),
        )

        assertEquals(
            listOf("https://example.test/same.png", "https://example.test/same.png"),
            collectMessageImageUrls(parts),
        )
    }

    private fun imageTool(
        toolCallId: String,
        vararg outputs: UIMessagePart,
    ) = UIMessagePart.Tool(
        localCallId = Uuid.random(), stepId = Uuid.random(), providerCallId = toolCallId,
        toolName = "generate_image",
        input = "{}",
        output = outputs.toList(),
    )

    @Test
    fun `message images include tool output images in part order`() {
        val parts = listOf(
            UIMessagePart.Image("https://example.test/a.png"),
            imageTool(
                "t1",
                UIMessagePart.Text("{}"),
                UIMessagePart.Image("https://example.test/gen1.png"),
                UIMessagePart.Image("https://example.test/gen2.png"),
            ),
            UIMessagePart.Text("done"),
            imageTool("t2", UIMessagePart.Image("https://example.test/gen3.png")),
            UIMessagePart.Image("https://example.test/b.png"),
        )

        assertEquals(
            listOf(
                "https://example.test/a.png",
                "https://example.test/gen1.png",
                "https://example.test/gen2.png",
                "https://example.test/gen3.png",
                "https://example.test/b.png",
            ),
            collectMessageImageUrls(parts),
        )
    }

    @Test
    fun `message images filter loading placeholders in both positions`() {
        val parts = listOf(
            UIMessagePart.Image("data:image/png;base64,"),
            imageTool(
                "t1",
                UIMessagePart.Image(""),
                UIMessagePart.Image("https://example.test/gen.png"),
            ),
        )

        assertEquals(listOf("https://example.test/gen.png"), collectMessageImageUrls(parts))
    }

    @Test
    fun `message images ignore reasoning and text only parts`() {
        val parts = listOf(
            UIMessagePart.Reasoning("think"),
            UIMessagePart.Text("text"),
            imageTool("t1", UIMessagePart.Text("ok")),
        )

        assertTrue(collectMessageImageUrls(parts).isEmpty())
    }

    @Test
    fun `local image requires a projected stable reference`() {
        val ref = AttachmentRefs.format(Uuid.random())
        val image = UIMessagePart.Image(
            url = "file:///data/data/app/files/upload/image.png",
            metadata = buildJsonObject { put(AttachmentRefs.METADATA_KEY, ref) },
        )

        assertTrue(collectMessageImageUrls(listOf(image)).isEmpty())
        assertEquals(
            listOf("file:///safe/upload/image.png"),
            collectMessageImageUrls(listOf(image)) { candidate ->
                "file:///safe/upload/image.png".takeIf { candidate == ref }
            },
        )
    }
}
