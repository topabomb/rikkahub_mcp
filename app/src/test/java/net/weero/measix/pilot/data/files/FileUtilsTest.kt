package net.weero.measix.pilot.data.files

import android.content.Context
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import net.weero.measix.pilot.data.imggen.TINY_PNG
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FileUtilsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `file URI preserves its name and resolves MIME from the actual payload`() {
        val directory = Files.createTempDirectory("file-metadata").toFile()
        val file = directory.resolve("crop_output_42.png").apply { writeBytes(TINY_PNG) }
        try {
            assertEquals(file.name, FileUtils.getFileNameFromUri(context, file.toUri()))
            assertEquals("image/png", FileUtils.getFileMimeType(context, file.toUri()))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `unknown file extension falls back to signature sniffing`() {
        val directory = Files.createTempDirectory("file-signature").toFile()
        val file = directory.resolve("image.unknown").apply { writeBytes(TINY_PNG) }
        try {
            assertEquals("image/png", FileUtils.getFileMimeType(context, file.toUri()))
        } finally {
            directory.deleteRecursively()
        }
    }
}
