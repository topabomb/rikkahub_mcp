package net.weero.measix.pilot.ui.components.richtext

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomableAsyncImageAlbumTest {
    @Test
    fun `blank model does not open a viewer`() {
        val album = listOf("file:///a.png", "file:///b.png")
        val (images, index) = resolveViewerImages(album, "  ")
        assertTrue(images.isEmpty())
        assertEquals(0, index)
    }

    @Test
    fun `hit uses the album and the clicked index`() {
        val album = listOf("file:///a.png", "file:///b.png", "file:///c.png")
        val (images, index) = resolveViewerImages(album, "file:///b.png")
        assertEquals(album, images)
        assertEquals(1, index)
    }

    @Test
    fun `url missing from a non-empty album falls back to single image`() {
        val album = listOf("file:///a.png", "file:///b.png")
        val (images, index) = resolveViewerImages(album, "data:image/png;base64,")
        assertEquals(listOf("data:image/png;base64,"), images)
        assertEquals(0, index)
    }

    @Test
    fun `empty album opens the clicked image alone`() {
        val (images, index) = resolveViewerImages(emptyList(), "file:///only.png")
        assertEquals(listOf("file:///only.png"), images)
        assertEquals(0, index)
    }
}
