package net.weero.measix.pilot.ui.components.richtext

import net.weero.measix.pilot.service.ImageSource
import net.weero.measix.pilot.service.ImageOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomableAsyncImageAlbumTest {
    private fun source(id: String) = ImageSource(id, ImageOrigin.UPLOAD, verifyAccess = {}, readPayload = { byteArrayOf() })

    @Test fun `album preserves original capabilities and uses clicked index`() {
        val a = source("a")
        val b = source("b")
        val album = listOf(a, b)
        val (images, index) = resolveViewerImages(album, b)
        assertSame(album, images)
        assertSame(b, images[index])
        assertEquals(1, index)
        assertTrue(resolveViewerImages(album, null).first.isEmpty())
    }

    @Test fun `image absent from album keeps its own capability`() {
        val a = source("a")
        for (album in listOf(emptyList(), listOf(source("b")))) {
            val (images, index) = resolveViewerImages(album, a)
            assertSame(a, images.single())
            assertEquals(0, index)
        }
    }
}
