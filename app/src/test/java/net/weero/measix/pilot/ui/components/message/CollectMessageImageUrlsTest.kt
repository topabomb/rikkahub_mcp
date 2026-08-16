package net.weero.measix.pilot.ui.components.message

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectMessageImageUrlsTest {
    @Test
    fun `collects top level image urls in original order`() {
        val parts = listOf(
            UIMessagePart.Text("hi"),
            UIMessagePart.Image("file:///a.png"),
            UIMessagePart.Text("mid"),
            UIMessagePart.Image("file:///b.png"),
        )

        assertEquals(listOf("file:///a.png", "file:///b.png"), collectMessageImageUrls(parts))
    }

    @Test
    fun `filters blank and streaming loading placeholders`() {
        val parts = listOf(
            UIMessagePart.Image(""),
            UIMessagePart.Image("data:image/png;base64,"),
            UIMessagePart.Image("data:image/png;base64,   "),
            UIMessagePart.Image("data:image/png;base64,iVBOR..."),
            UIMessagePart.Image("file:///ok.png"),
        )

        assertEquals(
            listOf("data:image/png;base64,iVBOR...", "file:///ok.png"),
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
        assertFalse(isImagePartLoading("file:///x.png"))
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
            UIMessagePart.Image("file:///a.png"),
            UIMessagePart.Image(""),
            UIMessagePart.Image("file:///b.png"),
        )

        assertEquals(listOf("file:///a.png", "file:///b.png"), collectMessageImageUrls(parts))
    }

    @Test
    fun `duplicate urls are all kept`() {
        val parts = listOf(
            UIMessagePart.Image("file:///same.png"),
            UIMessagePart.Image("file:///same.png"),
        )

        assertEquals(
            listOf("file:///same.png", "file:///same.png"),
            collectMessageImageUrls(parts),
        )
    }

    private fun imageTool(
        toolCallId: String,
        vararg outputs: UIMessagePart,
    ) = UIMessagePart.Tool(
        toolCallId = toolCallId,
        toolName = "generate_image",
        input = "{}",
        output = outputs.toList(),
    )

    @Test
    fun `message images include tool output images in part order`() {
        val parts = listOf(
            UIMessagePart.Image("file:///a.png"),
            imageTool(
                "t1",
                UIMessagePart.Text("{}"),
                UIMessagePart.Image("file:///gen1.png"),
                UIMessagePart.Image("file:///gen2.png"),
            ),
            UIMessagePart.Text("done"),
            imageTool("t2", UIMessagePart.Image("file:///gen3.png")),
            UIMessagePart.Image("file:///b.png"),
        )

        assertEquals(
            listOf(
                "file:///a.png",
                "file:///gen1.png",
                "file:///gen2.png",
                "file:///gen3.png",
                "file:///b.png",
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
                UIMessagePart.Image("file:///gen.png"),
            ),
        )

        assertEquals(listOf("file:///gen.png"), collectMessageImageUrls(parts))
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
}
