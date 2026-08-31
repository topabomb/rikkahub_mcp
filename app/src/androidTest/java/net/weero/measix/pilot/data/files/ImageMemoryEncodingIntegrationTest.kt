package net.weero.measix.pilot.data.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.EncodedImage
import me.rerere.ai.util.encodeBase64
import me.rerere.ai.util.encodeImageBytes
import me.rerere.ai.util.encodeNativeImage
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageMemoryEncodingIntegrationTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        root = Files.createTempDirectory(context.cacheDir.toPath(), "image-memory-encode-").toFile()
    }

    @After
    fun tearDown() {
        check(root.deleteRecursively())
    }

    @Test
    fun memoryAndFileEncodePngWithSameJpegPolicy() = runBlocking {
        val bytes = bitmapBytes(40, 20, Bitmap.CompressFormat.PNG)
        val file = File(root, "source.png").apply { writeBytes(bytes) }
        val encoded = encodeImageBytes(bytes)

        assertEquals(UIMessagePart.Image(android.net.Uri.fromFile(file).toString()).encodeNativeImage(), encoded)
        assertEquals("image/jpeg", encoded.mimeType)
        assertTrue(encoded.base64.startsWith("data:image/jpeg;base64,"))
        assertDimensions(encoded, 40, 20)
        assertEquals(listOf("source.png"), root.list()!!.toList())
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun memoryAndFileApplyAllExifOrientationsIdentically() = runBlocking {
        val original = bitmapBytes(40, 20, Bitmap.CompressFormat.JPEG)
        for (orientation in 1..8) {
            val file = File(root, "orientation-$orientation.jpg").apply { writeBytes(original) }
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
            val encoded = encodeImageBytes(file.readBytes())

            assertEquals(UIMessagePart.Image(android.net.Uri.fromFile(file).toString()).encodeNativeImage(), encoded)
            val swapsAxes = orientation in 5..8
            assertDimensions(encoded, if (swapsAxes) 20 else 40, if (swapsAxes) 40 else 20)
        }
    }

    @Test
    fun gifFramesRemainByteIdenticalForMemoryAndFile() = runBlocking {
        val gif = animatedGif()
        val file = File(root, "animation.gif").apply { writeBytes(gif) }
        val encoded = encodeImageBytes(gif, withPrefix = false)

        assertEquals("image/gif", encoded.mimeType)
        assertArrayEquals(gif, java.util.Base64.getDecoder().decode(encoded.base64))
        assertEquals(UIMessagePart.Image(android.net.Uri.fromFile(file).toString()).encodeNativeImage(withPrefix = false), encoded)
    }

    @Test
    fun memoryAndFileUseSameDimensionLimit() = runBlocking {
        val bytes = bitmapBytes(10_002, 2, Bitmap.CompressFormat.PNG)
        val file = File(root, "wide.png").apply { writeBytes(bytes) }
        val encoded = encodeImageBytes(bytes)

        assertEquals(UIMessagePart.Image(android.net.Uri.fromFile(file).toString()).encodeNativeImage(), encoded)
        val bitmap = decode(encoded)
        try {
            assertTrue(bitmap.width <= 10_000)
            assertTrue(bitmap.width.toLong() * bitmap.height <= 16_000_000L)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun webpIsConvertedToJpegByBothEntrypoints() = runBlocking {
        val bytes = bitmapBytes(40, 20, Bitmap.CompressFormat.WEBP_LOSSLESS)
        val file = File(root, "source.webp").apply { writeBytes(bytes) }
        val encoded = encodeImageBytes(bytes)

        assertEquals(UIMessagePart.Image(android.net.Uri.fromFile(file).toString()).encodeNativeImage(), encoded)
        assertEquals("image/jpeg", encoded.mimeType)
        assertDimensions(encoded, 40, 20)
    }

    @Test
    fun undecodableHeicIsRejectedRatherThanForwardedAsRawPayload() = runBlocking {
        val bytes = byteArrayOf(0, 0, 0, 24) + "ftypheic".toByteArray(Charsets.US_ASCII) + ByteArray(12)
        val file = File(root, "broken.heic").apply { writeBytes(bytes) }
        try {
            encodeImageBytes(bytes)
            throw AssertionError("expected HEIC decode failure")
        } catch (_: IllegalArgumentException) {
            // A recognized container without decodable pixels is never usable input.
        }
        assertTrue(UIMessagePart.Image(android.net.Uri.fromFile(file).toString()).encodeBase64().isFailure)
    }

    @Test
    fun cancelledEncodingDoesNotReturnUsableImage() = runBlocking {
        val bytes = bitmapBytes(40, 20, Bitmap.CompressFormat.PNG)
        val operation = async(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel(CancellationException("cancel before encoding"))
            encodeImageBytes(bytes)
        }
        try {
            operation.await()
            throw AssertionError("expected cancellation")
        } catch (_: CancellationException) {
            assertTrue(operation.isCancelled)
        }
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    private fun bitmapBytes(width: Int, height: Int, format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.RED)
            for (y in 0 until height) {
                for (x in 0 until width / 2) bitmap.setPixel(x, y, Color.BLUE)
            }
            return ByteArrayOutputStream().use { output ->
                check(bitmap.compress(format, 95, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertDimensions(encoded: EncodedImage, width: Int, height: Int) {
        val bitmap = decode(encoded)
        try {
            assertEquals(width, bitmap.width)
            assertEquals(height, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    private fun decode(encoded: EncodedImage): Bitmap {
        val bytes = java.util.Base64.getDecoder().decode(encoded.base64.substringAfter("base64,", encoded.base64))
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }

    private fun animatedGif(): ByteArray {
        val header = byteArrayOf(
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 1, 0, 1, 0, 0x80.toByte(), 0, 0,
            0, 0, 0, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
        )
        val frame = byteArrayOf(
            0x21, 0xf9.toByte(), 4, 0, 10, 0, 0, 0,
            0x2c, 0, 0, 0, 0, 1, 0, 1, 0, 0,
            2, 2, 0x44, 1, 0,
        )
        return header + frame + frame + byteArrayOf(0x3b)
    }
}
