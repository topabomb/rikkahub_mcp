package net.weero.measix.pilot.service.files

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MediaPreviewSourceTest {
    @Test fun `close releases once and permanently denies verification and reads`() = runTest {
        var closes = 0
        var verifications = 0
        var reads = 0
        val source = MediaPreviewSource("video", 8, emptyFlow<Unit>(), { verifications++ },
            close = { closes++ }, readRange = { _, count -> reads++; ByteArray(count) })
        source.verifyAccess()
        source.readRange(0, 1)
        source.close()
        source.close()
        assertTrue(runCatching { source.verifyAccess() }.isFailure)
        assertTrue(runCatching { source.readRange(0, 1) }.isFailure)
        assertEquals(1, closes)
        assertEquals(1, reads)
        assertEquals(1, verifications)
    }

    @Test fun `close during a read prevents late delivery`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val source = MediaPreviewSource("video", 8, emptyFlow<Unit>(), {}, readRange = { _, count ->
            entered.complete(Unit)
            release.await()
            ByteArray(count)
        })
        val read = async { runCatching { source.readRange(0, 8) } }
        entered.await()
        source.close()
        release.complete(Unit)
        assertEquals("workspace_media_closed", read.await().exceptionOrNull()?.message)
    }

    @Test fun `range validation uses long offsets and bounds allocations before IO`() = runTest {
        var reads = 0
        val source = MediaPreviewSource("video", MAX_MEDIA_BYTES, emptyFlow<Unit>(), {}, readRange = { position, count ->
            reads++
            assertEquals(MAX_MEDIA_BYTES - 1, position)
            assertEquals(1, count)
            byteArrayOf(42)
        })
        for ((position, count) in listOf(-1L to 1, MAX_MEDIA_BYTES to 1, 0L to 0, 0L to Int.MAX_VALUE)) {
            assertTrue(runCatching { source.readRange(position, count) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertEquals(0, reads)
        assertArrayEquals(byteArrayOf(42), source.readRange(MAX_MEDIA_BYTES - 1, 1))
        assertEquals(1, reads)
        source.close()
    }
}
