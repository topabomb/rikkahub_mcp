package net.weero.measix.pilot.ui.components.ui

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ClassifyImageSourceTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `classifies inline and network by scheme`() {
        val filesDir = temp.newFolder()
        assertEquals(ImageInfoSource.Inline, classifyImageSource("data:image/png;base64,QUJD", filesDir))
        assertEquals(ImageInfoSource.Network, classifyImageSource("https://example.com/a.png", filesDir))
        assertEquals(ImageInfoSource.Network, classifyImageSource("http://example.com/a.png", filesDir))
    }

    @Test
    fun `classifies app directories for file urls and bare paths`() {
        val filesDir = temp.newFolder()
        val generated = File(filesDir, "images/x.png").absolutePath
        val upload = File(filesDir, "upload/y.png").absolutePath

        assertEquals(ImageInfoSource.Generated, classifyImageSource("file://$generated", filesDir))
        assertEquals(ImageInfoSource.Upload, classifyImageSource("file://$upload", filesDir))
        assertEquals(ImageInfoSource.Generated, classifyImageSource(generated, filesDir))
        assertEquals(ImageInfoSource.Local, classifyImageSource("file:///sdcard/Pictures/z.png", filesDir))
        assertEquals(ImageInfoSource.Local, classifyImageSource("/sdcard/Pictures/z.png", filesDir))
    }

    @Test
    fun `sibling directory with shared prefix is not mistaken for app directory`() {
        val filesDir = temp.newFolder()
        val sibling = File(filesDir, "images2/x.png").absolutePath

        assertEquals(ImageInfoSource.Local, classifyImageSource("file://$sibling", filesDir))
    }
}
