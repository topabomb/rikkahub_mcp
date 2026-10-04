package net.weero.measix.pilot.ui.components.files

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import net.weero.measix.pilot.service.files.MAX_MEDIA_BYTES
import net.weero.measix.pilot.service.files.MediaPreviewSource
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MediaPreviewDataSourceTest {
    private fun spec(position: Long = 0, name: String = "media") = DataSpec.Builder()
        .setUri(Uri.parse("workspace://$name")).setPosition(position).build()

    @Test fun `closing an in flight read rejects late bytes and cannot corrupt a reopened session`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val source = MediaPreviewSource("video.mp4", 64, emptyFlow<Unit>(), {}, readRange = { position, count ->
            if (position == 0L) withContext(NonCancellable) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            ByteArray(count) { (position + it).toByte() }
        })
        val data = MediaPreviewDataSource(source)
        val executor = Executors.newSingleThreadExecutor()
        try {
            data.open(spec())
            val oldTarget = ByteArray(4) { -1 }
            val oldRead = executor.submit<IOException?> {
                try { data.read(oldTarget, 0, oldTarget.size); null }
                catch (error: IOException) { error }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            data.close()
            assertNull(data.getUri())
            data.open(spec(16, "new"))
            val target = ByteArray(4)
            assertEquals(4, data.read(target, 0, target.size))
            assertArrayEquals(byteArrayOf(16, 17, 18, 19), target)
            release.countDown()
            assertNotNull(oldRead.get(5, TimeUnit.SECONDS))
            assertArrayEquals(ByteArray(4) { -1 }, oldTarget)
            assertEquals(Uri.parse("workspace://new"), data.getUri())
            assertEquals(4, data.read(target, 0, target.size))
            assertArrayEquals(byteArrayOf(20, 21, 22, 23), target)
        } finally {
            release.countDown()
            data.close()
            executor.shutdownNow()
        }
    }

    @Test fun `close cancels access verification and a later open gets its own job`() {
        val entered = CountDownLatch(1)
        val shouldBlock = AtomicBoolean(true)
        val source = MediaPreviewSource("video.mp4", 8, emptyFlow<Unit>(), {
            if (shouldBlock.getAndSet(false)) { entered.countDown(); awaitCancellation() }
        }, readRange = { _, count -> ByteArray(count) { 7 } })
        val data = MediaPreviewDataSource(source)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val opening = executor.submit<IOException?> {
                try { data.open(spec()); null } catch (error: IOException) { error }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            data.close()
            assertNotNull(opening.get(5, TimeUnit.SECONDS))
            assertNull(data.getUri())
            assertEquals(8L, data.open(spec()))
            assertEquals(1, data.read(ByteArray(1), 0, 1))
        } finally { data.close(); executor.shutdownNow() }
    }

    @Test fun `revocation denies already buffered bytes without another range request`() {
        var allowed = true
        var reads = 0
        val source = MediaPreviewSource("video.mp4", 8, emptyFlow<Unit>(), {
            check(allowed) { "workspace_access_revoked" }
        }, readRange = { _, count -> reads++; ByteArray(count) { 42 } })
        val data = MediaPreviewDataSource(source)
        try {
            data.open(spec())
            assertEquals(1, data.read(ByteArray(1), 0, 1))
            allowed = false
            val target = ByteArray(1) { -1 }
            assertThrows(IOException::class.java) { data.read(target, 0, 1) }
            assertArrayEquals(byteArrayOf(-1), target)
            assertEquals(1, reads)
        } finally { data.close() }
    }

    @Test fun `four GiB tail remains long and exact end returns EOF without reading`() {
        var reads = 0
        val source = MediaPreviewSource("video.mp4", MAX_MEDIA_BYTES, emptyFlow<Unit>(), {}, readRange = { position, count ->
            reads++
            assertEquals(MAX_MEDIA_BYTES - 2, position)
            assertEquals(2, count)
            byteArrayOf(42, 43)
        })
        val data = MediaPreviewDataSource(source)
        try {
            assertEquals(2L, data.open(spec(MAX_MEDIA_BYTES - 2)))
            val target = ByteArray(4)
            assertEquals(2, data.read(target, 0, 4))
            assertArrayEquals(byteArrayOf(42, 43, 0, 0), target)
            assertEquals(C.RESULT_END_OF_INPUT, data.read(target, 0, 4))
            data.close()
            assertEquals(0L, data.open(spec(MAX_MEDIA_BYTES)))
            assertEquals(C.RESULT_END_OF_INPUT, data.read(target, 0, 4))
            assertEquals(1, reads)
        } finally { data.close() }
    }
}
