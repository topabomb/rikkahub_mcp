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
import kotlinx.coroutines.launch
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
    @Test fun `startup cleanup cannot remove files exported after the old directory was detached`() {
        val cache = kotlin.io.path.createTempDirectory("retired-exports").toFile()
        try {
            val old = java.io.File(cache, "temp").apply { mkdirs() }
            java.io.File(old, "old.pdf").writeText("old")
            val orphan = java.io.File(cache, "retired-temp-previous").apply { mkdirs() }
            java.io.File(orphan, "interrupted.pdf").writeText("old")
            val oldPreview = java.io.File(cache, "webview_content").apply { mkdirs() }
            java.io.File(oldPreview, "old-html").writeText("private old preview")
            val retired = net.weero.measix.pilot.retireApplicationTempFiles(cache)
            val current = java.io.File(cache, "temp").apply { mkdirs() }
            val exported = java.io.File(current, "new.pdf").apply { writeText("new export") }
            retired.forEach { check(it.deleteRecursively()) }
            org.junit.Assert.assertEquals("new export", exported.readText())
            org.junit.Assert.assertFalse(orphan.exists())
            org.junit.Assert.assertFalse(oldPreview.exists())
        } finally { check(cache.deleteRecursively()) }
    }

    @Test fun `document service compensates missing requests cancelled reception and expired writes`() = runTest {
        val context = mockk<Context>()
        val resolver = mockk<ContentResolver>()
        every { context.contentResolver } returns resolver
        val uri = Uri.parse("content://documents/document/new")
        val files = mockk<net.weero.measix.pilot.service.FileManagementApplicationService>()
        val source = net.weero.measix.pilot.service.RenderedContentSource.Static
        val request = net.weero.measix.pilot.service.TextDocumentExport(source, "original 中文", "original.txt", "text/plain")
        val output = ByteArrayOutputStream()
        every { resolver.openOutputStream(uri, "wt") } returns output
        io.mockk.coEvery { files.withContentAccess<Unit>(source, any()) } coAnswers { secondArg<suspend () -> Unit>()() }
        var expired = false
        io.mockk.coEvery { files.requireContentAccess(source) } coAnswers { check(!expired) { "expired" } }
        var deleted = 0
        io.mockk.mockkStatic(android.provider.DocumentsContract::class)
        every { android.provider.DocumentsContract.deleteDocument(resolver, uri) } answers { deleted++; true }
        val exports = net.weero.measix.pilot.service.MediaExportService(files)
        try {
            exports.saveTextDocument(context, uri, request)
            org.junit.Assert.assertEquals(request.text, output.toString("UTF-8"))
            org.junit.Assert.assertEquals(0, deleted)
            org.junit.Assert.assertTrue(runCatching { exports.saveTextDocument(context, uri, null) }.isFailure)
            org.junit.Assert.assertEquals(1, deleted)
            expired = true
            org.junit.Assert.assertTrue(runCatching { exports.saveTextDocument(context, uri, request) }.isFailure)
            org.junit.Assert.assertEquals(2, deleted)
            val cancelled = launch(start = kotlinx.coroutines.CoroutineStart.ATOMIC) {
                exports.saveTextDocument(context, uri, request)
            }
            cancelled.cancel()
            cancelled.join()
            org.junit.Assert.assertEquals(3, deleted)
        } finally { io.mockk.unmockkStatic(android.provider.DocumentsContract::class) }
    }

    @Test fun `fixed renderer upload URLs use the attachment owner rather than browser navigation`() = runTest {
        val context = mockk<Context>()
        val files = mockk<net.weero.measix.pilot.service.FileManagementApplicationService>()
        val source = net.weero.measix.pilot.service.RenderedContentSource.UserConfiguration
        io.mockk.coEvery { files.resolveContentAttachment(source, "/upload/known.pdf") } returns null
        val exports = net.weero.measix.pilot.service.MediaExportService(files)
        org.junit.Assert.assertTrue(runCatching { exports.openContentLink(context, source, "https://measix.local/upload/known.pdf") }.isFailure)
        io.mockk.coVerify(exactly = 1) { files.resolveContentAttachment(source, "/upload/known.pdf") }
        io.mockk.verify(exactly = 0) { context.startActivity(any()) }
    }

    @Test fun `attachment handoff exports only a copy and compensates every unaccepted outcome`() = runTest {
        val root = kotlin.io.path.createTempDirectory("attachment-export").toFile()
        val context = mockk<Context>()
        every { context.cacheDir } returns root
        every { context.packageName } returns "test.app"
        val files = mockk<net.weero.measix.pilot.service.FileManagementApplicationService>()
        val view = net.weero.measix.pilot.service.ConversationViewLease(kotlin.uuid.Uuid.random(),
            net.weero.measix.pilot.data.enterprise.RealmAccess.Personal, 0) {}
        val preview = net.weero.measix.pilot.service.AttachmentPreview("file:///original/private.pdf", null,
            net.weero.measix.pilot.service.AttachmentPreview.FileTarget(net.weero.measix.pilot.service.RenderedContentSource.Conversation(view), 42, "private.pdf"))
        val bytes = ByteArray(150_000) { (it % 127).toByte() }
        var mode = "success"
        io.mockk.coEvery { files.copyAttachmentTo(preview, any()) } coAnswers {
            secondArg<java.io.OutputStream>().write(bytes)
            if (mode == "copy_failed") throw java.io.IOException("copy failed")
            "application/pdf"
        }
        io.mockk.coEvery { files.withAttachmentAccess<Unit>(preview, any()) } coAnswers {
            if (mode == "revoked") throw IllegalStateException("page revoked")
            if (mode == "cancelled") throw CancellationException("page cancelled")
            secondArg<() -> Unit>()()
        }
        var delivered: android.content.Intent? = null
        every { context.startActivity(any()) } answers {
            if (mode == "no_handler") throw android.content.ActivityNotFoundException()
            delivered = firstArg()
        }
        kotlinx.coroutines.Dispatchers.setMain(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        io.mockk.mockkStatic(androidx.core.content.FileProvider::class)
        var candidate: java.io.File? = null
        every { androidx.core.content.FileProvider.getUriForFile(context, any(), any()) } answers {
            candidate = thirdArg()
            Uri.parse("content://test/export-copy")
        }
        try {
            val exporter = net.weero.measix.pilot.service.MediaExportService(files)
            for (failure in listOf("copy_failed", "revoked", "cancelled", "no_handler")) {
                mode = failure
                val result = runCatching { exporter.openAttachment(context, preview) }
                org.junit.Assert.assertTrue(failure, result.isFailure)
                org.junit.Assert.assertNull(delivered)
                org.junit.Assert.assertTrue(java.io.File(root, "temp").listFiles().orEmpty().isEmpty())
                if (failure == "cancelled") org.junit.Assert.assertTrue(result.exceptionOrNull() is CancellationException)
            }
            mode = "success"
            exporter.openAttachment(context, preview)
            org.junit.Assert.assertEquals(android.content.Intent.ACTION_VIEW, delivered?.action)
            org.junit.Assert.assertEquals("application/pdf", delivered?.type)
            org.junit.Assert.assertEquals(Uri.parse("content://test/export-copy"), delivered?.data)
            org.junit.Assert.assertTrue(delivered!!.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            org.junit.Assert.assertArrayEquals(bytes, requireNotNull(candidate).readBytes())
        } finally {
            io.mockk.unmockkStatic(androidx.core.content.FileProvider::class)
            kotlinx.coroutines.Dispatchers.resetMain()
            check(root.deleteRecursively())
        }
    }

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
                    net.weero.measix.pilot.service.MediaExportService(io.mockk.mockk()).shareText(context, "private", "chat.md") {
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
