package net.weero.measix.pilot.utils

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImageExportTest {
    @Test fun `cancelled sharing retains original cancellation when file cleanup fails`() = runTest {
        val root = kotlin.io.path.createTempDirectory("share-cleanup").toFile()
        val context = mockk<Context>()
        every { context.cacheDir } returns root
        every { context.packageName } returns "test.app"
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        io.mockk.mockkStatic(androidx.core.content.FileProvider::class)
        every { androidx.core.content.FileProvider.getUriForFile(context, any(), any()) } returns Uri.parse("content://test/image")
        val original = CancellationException("original share cancelled")
        try {
            var checks = 0
            var observed: Throwable? = null
            val operation = async {
                val originalJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!
                try {
                    net.weero.measix.pilot.service.MediaExportService().shareText(context, "private", "chat.md") {
                        checks++
                        if (checks == 2) {
                            val file = java.io.File(root, "temp").listFiles()!!.single()
                            check(file.delete() && file.mkdir())
                            java.io.File(file, "prevent-delete").writeText("test")
                            originalJob.cancel(original)
                            throw original
                        }
                    }
                } catch (error: Throwable) { observed = error; throw error }
            }
            runCatching { operation.await() }
            val failure = requireNotNull(observed)
            org.junit.Assert.assertTrue(failure is CancellationException)
            org.junit.Assert.assertEquals(original.message, failure.message)
            val causes = generateSequence(failure) { it.cause }.toList()
            org.junit.Assert.assertTrue(causes.any { it === original })
            org.junit.Assert.assertTrue(causes.any { cause -> cause.suppressed.any { it.message == "Unable to remove unpublished export" } })
        } finally {
            io.mockk.unmockkStatic(androidx.core.content.FileProvider::class)
            kotlinx.coroutines.Dispatchers.resetMain()
            check(root.deleteRecursively())
        }
    }

    @Test fun `compensation failure preserves rejection and cancellation`() = runTest {
        for (original in listOf(IllegalStateException("access ended"), CancellationException("cancelled"))) {
            val resolver = mockk<ContentResolver>()
            val context = mockk<Context>()
            every { context.contentResolver } returns resolver
            val uri = Uri.parse("content://media/external/images/media/42")
            every { resolver.insert(any(), any()) } returns uri
            every { resolver.openOutputStream(uri) } returns ByteArrayOutputStream()
            val cleanup = java.io.IOException("delete failed")
            every { resolver.delete(uri, null, null) } throws cleanup
            val failure = runCatching {
                context.exportImageBytes(mockk<Activity>(), byteArrayOf(1), "image/png", "image.png") { throw original }
            }.exceptionOrNull()
            assertSame(original, failure)
            assertSame(cleanup, failure!!.suppressed.single())
        }
    }
}
