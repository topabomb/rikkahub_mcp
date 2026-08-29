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
        assertEquals(ImageInfoSource.Inline, classifyImageSource("data:image/png;base64,QUJD"))
        assertEquals(ImageInfoSource.Network, classifyImageSource("https://example.com/a.png"))
        assertEquals(ImageInfoSource.Network, classifyImageSource("http://example.com/a.png"))
    }

    @Test
    fun `classifies app directories for file urls and bare paths`() {
        val filesDir = temp.newFolder()
        val generated = File(filesDir, "images/x.png").absolutePath
        val upload = File(filesDir, "upload/y.png").absolutePath
        val uploadUrl = "file://$upload"
        val isGenerated: (File) -> Boolean = { it.absolutePath == generated }

        assertEquals(
            ImageInfoSource.Generated,
            classifyImageSource("file://$generated", isManagedGeneratedFile = isGenerated),
        )
        assertEquals(
            ImageInfoSource.Upload,
            classifyImageSource(uploadUrl, isManagedUploadUrl = { it == uploadUrl }),
        )
        assertEquals(
            ImageInfoSource.Generated,
            classifyImageSource(generated, isManagedGeneratedFile = isGenerated),
        )
        assertEquals(ImageInfoSource.Local, classifyImageSource("file:///sdcard/Pictures/z.png"))
        assertEquals(ImageInfoSource.Local, classifyImageSource("/sdcard/Pictures/z.png"))
    }

    @Test
    fun `sibling directory with shared prefix is not mistaken for app directory`() {
        val filesDir = temp.newFolder()
        val sibling = File(filesDir, "images2/x.png").absolutePath

        val generatedDir = File(filesDir, "images").absoluteFile
        assertEquals(
            ImageInfoSource.Local,
            classifyImageSource("file://$sibling", isManagedGeneratedFile = { file ->
                file.absolutePath.startsWith(generatedDir.path + File.separator)
            }),
        )
    }

    @Test
    fun `uppercase file scheme uses the same local path`() {
        val filesDir = temp.newFolder()
        val generated = File(filesDir, "images/x.png").absoluteFile
        val uppercase = "FILE://" + generated.absolutePath.replace('\\', '/')

        assertEquals(
            ImageInfoSource.Generated,
            classifyImageSource(uppercase, isManagedGeneratedFile = { it.absoluteFile == generated }),
        )
    }
}
