package net.weero.measix.pilot.service.portal

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PortalMediaStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private var now = 1_900_000_000_000L

    @Test
    fun `startup recovery deletes only its temporary root and can retry a failed deletion`() = runBlocking<Unit> {
        val root = temporary.newFolder()
        val old = File(root, "old-document/leftover.media").apply { parentFile.mkdirs(); writeBytes(portalTestJpeg()) }
        val outside = temporary.newFile().apply { writeText("keep") }
        var failDelete = true
        val store = PortalMediaStore(root, { now }) { file -> if (failDelete && file == old) false else file.delete() }
        rejects("media_unavailable") { store.open("document") }
        fails<IOException> { store.recover() }
        assertTrue(old.exists())
        rejects("media_unavailable") { store.open("document") }
        failDelete = false
        store.recover()
        assertTrue(root.listFiles()!!.isEmpty())
        assertEquals("keep", outside.readText())
        val session = store.open("document")
        try {
            val capture = session.reserve(PortalMediaStore.JPEG)
            store.recover()
            assertTrue("Repeated successful recovery must not delete active captures", capture.file.exists())
        } finally { session.close() }
    }

    @Test
    fun `sessions are exclusive and media and capture ownership never crosses documents`() = runBlocking<Unit> {
        val store = store()
        val first = store.open("first")
        val second = store.open("second")
        try {
            rejects("media_unavailable") { store.open("first") }
            val capture = first.reserve(PortalMediaStore.JPEG).apply { file.writeBytes(portalTestJpeg()) }
            rejects("media_unavailable") { second.publish(capture) }
            rejects("media_unavailable") { second.discard(capture) }
            assertTrue(capture.file.exists())
            val handle = first.publish(capture)
            assertTrue(handle.mediaId.matches(Regex("[A-Za-z0-9_-]{43}")))
            rejects("media_unavailable") { second.read(handle.mediaId, 0, 1) }
            rejects("media_unavailable") { second.release(handle.mediaId) }
            rejects("media_unavailable") { first.release("unknown") }
            rejects("media_unavailable") { first.publish(capture) }
            assertTrue(first.read(handle.mediaId, 0, 1).dataBase64.isNotEmpty())
            first.release(handle.mediaId)
            first.release(handle.mediaId)
            first.discard(capture)
            rejects("media_unavailable") { first.read(handle.mediaId, 0, 1) }
        } finally { first.close(); second.close() }
    }

    @Test
    fun `publication freezes actual JPEG and MP4 files and chunk offsets obey the published length`() = runBlocking<Unit> {
        val store = store()
        val session = store.open("document")
        try {
            for ((mime, bytes) in listOf(PortalMediaStore.JPEG to portalTestJpeg(), PortalMediaStore.AUDIO_MP4 to portalTestAudio())) {
                val capture = session.reserve(mime).apply { file.writeBytes(bytes) }
                val handle = session.publish(capture)
                assertEquals(mime, handle.mimeType)
                assertEquals(bytes.size, handle.byteLength)
                assertFalse(capture.file.exists())
                // Retaining the staging path does not grant access to the frozen published file.
                capture.file.writeText("a later write to the old path")
                val collected = ByteArrayOutputStream()
                var offset = 0
                do {
                    val chunk = session.read(handle.mediaId, offset, 73)
                    val data = Base64.getDecoder().decode(chunk.dataBase64)
                    assertEquals(offset + data.size, chunk.nextOffset)
                    assertEquals(chunk.nextOffset == bytes.size, chunk.eof)
                    collected.write(data)
                    offset = chunk.nextOffset
                } while (!chunk.eof)
                assertArrayEquals(bytes, collected.toByteArray())
                assertEquals(PortalMediaChunk("", bytes.size, true), session.read(handle.mediaId, bytes.size, 1))
                rejects("invalid_request") { session.read(handle.mediaId, -1, 1) }
                rejects("invalid_request") { session.read(handle.mediaId, bytes.size + 1, 1) }
                rejects("invalid_request") { session.read(handle.mediaId, 0, 0) }
                rejects("invalid_request") { session.read(handle.mediaId, 0, PortalMediaStore.MAX_CHUNK_BYTES + 1) }
                session.release(handle.mediaId)
                assertFalse(capture.file.exists())
            }
        } finally { session.close() }
    }

    @Test
    fun `parallel reservations count in flight captures against both available slots`() = runBlocking<Unit> {
        val session = store().open("document")
        try {
            val results = List(3) {
                async { try { session.reserve(PortalMediaStore.JPEG) } catch (error: PortalFailure) { error } }
            }.awaitAll()
            val captures = results.filterIsInstance<PortalMediaCapture>()
            assertEquals(2, captures.size)
            assertEquals("resource_limit", results.filterIsInstance<PortalFailure>().single().code)
            assertNotEquals(captures[0].mediaId, captures[1].mediaId)
            session.discard(captures.first())
            session.discard(captures.first())
            session.reserve(PortalMediaStore.AUDIO_MP4)
            rejects("resource_limit") { session.reserve(PortalMediaStore.JPEG) }
        } finally { session.close() }
    }

    @Test
    fun `two ten MiB media items fit and oversize publication is discarded`() = runBlocking<Unit> {
        val session = store().open("document")
        try {
            val oversized = session.reserve(PortalMediaStore.JPEG)
            oversized.file.outputStream().use { it.write(ByteArray(PortalMediaStore.MAX_ITEM_BYTES + 1)) }
            rejects("resource_limit") { session.publish(oversized) }
            assertFalse(oversized.file.exists())
            val bytes = paddedJpeg(PortalMediaStore.MAX_ITEM_BYTES)
            val handles = List(2) {
                session.publish(session.reserve(PortalMediaStore.JPEG).apply { file.writeBytes(bytes) })
            }
            assertEquals(PortalMediaStore.MAX_TOTAL_BYTES, handles.sumOf { it.byteLength })
            rejects("resource_limit") { session.reserve(PortalMediaStore.JPEG) }
        } finally { session.close() }
    }

    @Test
    fun `invalid or mismatched containers fail publication without retaining a reservation`() = runBlocking<Unit> {
        val session = store().open("document")
        val videoHandler = portalTestAudio().apply {
            val position = toString(Charsets.ISO_8859_1).indexOf("soun")
            check(position >= 0)
            "vide".toByteArray().copyInto(this, position)
        }
        try {
            rejects("invalid_request") { session.reserve("image/png") }
            for ((mime, bytes) in listOf(
                PortalMediaStore.JPEG to byteArrayOf(),
                PortalMediaStore.JPEG to "%PDF-1.7".toByteArray(),
                PortalMediaStore.JPEG to portalTestJpeg().dropLast(2).toByteArray(),
                PortalMediaStore.AUDIO_MP4 to portalTestJpeg(),
                PortalMediaStore.AUDIO_MP4 to portalTestAudio().copyOf(12),
                PortalMediaStore.AUDIO_MP4 to videoHandler,
            )) {
                val capture = session.reserve(mime).apply { file.writeBytes(bytes) }
                rejects("media_unavailable") { session.publish(capture) }
                assertFalse(capture.file.exists())
            }
            session.reserve(PortalMediaStore.JPEG)
            session.reserve(PortalMediaStore.AUDIO_MP4)
        } finally { session.close() }
    }

    @Test
    fun `expiry starts at reservation and sweep never removes a still writing capture`() = runBlocking<Unit> {
        val session = store().open("document")
        try {
            val photo = session.reserve(PortalMediaStore.JPEG).apply { file.writeBytes(portalTestJpeg()) }
            val writing = session.reserve(PortalMediaStore.AUDIO_MP4)
            now += 20_000
            val handle = session.publish(photo)
            now = photo.expiresAtMillis - 1
            assertFalse(session.read(handle.mediaId, 0, 1).eof)
            now++
            rejects("media_unavailable") { session.read(handle.mediaId, 0, 1) }
            now--
            rejects("media_unavailable") { session.read(handle.mediaId, 0, 1) }
            now++
            session.sweep()
            assertTrue(writing.file.exists())
            session.release(handle.mediaId)
            session.reserve(PortalMediaStore.JPEG)
            rejects("resource_limit") { session.reserve(PortalMediaStore.JPEG) }
            // Only the caller's stopped writer can now hand this expired capture back.
            writing.file.writeBytes(portalTestAudio())
            rejects("media_unavailable") { session.publish(writing) }
            assertFalse(writing.file.exists())
            session.reserve(PortalMediaStore.JPEG)
        } finally { session.close() }
    }

    @Test
    fun `failed release revokes reads while retaining cleanup ownership and quota until retry`() = runBlocking<Unit> {
        val root = temporary.newFolder()
        var failDelete = true
        val store = PortalMediaStore(root, { now }) { file ->
            if (failDelete && file.extension == "media") false else file.delete()
        }
        store.recover()
        val session = store.open("document")
        try {
            val handle = session.publish(session.reserve(PortalMediaStore.JPEG).apply { file.writeBytes(portalTestJpeg()) })
            session.reserve(PortalMediaStore.AUDIO_MP4)
            fails<IOException> { session.release(handle.mediaId) }
            rejects("media_unavailable") { session.read(handle.mediaId, 0, 1) }
            fails<IOException> { session.reserve(PortalMediaStore.JPEG) }
            assertEquals(1, root.walkTopDown().count { it.extension == "media" })
            failDelete = false
            session.release(handle.mediaId)
            session.release(handle.mediaId)
            session.reserve(PortalMediaStore.JPEG)
            rejects("resource_limit") { session.reserve(PortalMediaStore.JPEG) }
        } finally { failDelete = false; session.close() }
    }

    @Test
    fun `failed close blocks new session ownership and can finish on retry`() = runBlocking<Unit> {
        val root = temporary.newFolder()
        var failDelete = false
        val store = PortalMediaStore(root, { now }) { file ->
            if (failDelete && file.isDirectory && file != root) false else file.delete()
        }
        store.recover()
        val session = store.open("document")
        val capture = session.reserve(PortalMediaStore.JPEG).apply { file.writeBytes(portalTestJpeg()) }
        val handle = session.publish(capture)
        try {
            failDelete = true
            fails<IOException> { session.close() }
            rejects("media_unavailable") { session.read(handle.mediaId, 0, 1) }
            rejects("media_unavailable") { session.reserve(PortalMediaStore.JPEG) }
            rejects("media_unavailable") { store.open("document") }
            failDelete = false
            session.close()
            session.close()
            assertTrue(root.listFiles()!!.isEmpty())
            val replacement = store.open("document")
            try { rejects("media_unavailable") { replacement.release(handle.mediaId) } }
            finally { replacement.close() }
        } finally { failDelete = false; session.close() }
    }

    @Test
    fun `dispatcher return cancellation compensates unreceived sessions captures and publications`() = runBlocking<Unit> {
        val root = temporary.newFolder()
        val store = PortalMediaStore(root, { now }).also { it.recover() }
        cancelAtReturn { store.open("document") }
        assertTrue(root.listFiles()!!.isEmpty())
        val session = store.open("document")
        try {
            cancelAtReturn { session.reserve(PortalMediaStore.JPEG) }
            assertFalse(root.walkTopDown().any { it.isFile })
            val capture = session.reserve(PortalMediaStore.JPEG).apply { file.writeBytes(portalTestJpeg()) }
            cancelAtReturn { session.publish(capture) }
            assertFalse(root.walkTopDown().any { it.isFile })
            session.reserve(PortalMediaStore.JPEG)
            session.reserve(PortalMediaStore.AUDIO_MP4)
        } finally { session.close() }
    }

    @Test
    fun `cancellation during deletion leaves a closed admission that the original owner can retry`() = runBlocking<Unit> {
        val root = temporary.newFolder()
        val cancelDeletion = AtomicReference<Job?>()
        val store = PortalMediaStore(root, { now }) { file ->
            if (file.extension == "media") cancelDeletion.getAndSet(null)?.cancel()
            file.delete()
        }
        store.recover()
        val session = store.open("document")
        val handle = session.publish(session.reserve(PortalMediaStore.JPEG).apply { file.writeBytes(portalTestJpeg()) })
        try {
            val closing = launch(start = CoroutineStart.LAZY) { session.close() }
            cancelDeletion.set(closing)
            closing.start()
            closing.join()
            assertTrue(closing.isCancelled)
            rejects("media_unavailable") { session.read(handle.mediaId, 0, 1) }
            rejects("media_unavailable") { store.open("document") }
            session.close()
            assertTrue(root.listFiles()!!.isEmpty())
        } finally { cancelDeletion.set(null); session.close() }
    }

    @Test
    fun `unreceived session and capture cleanup failures remain retryable without losing cancellation`() = runBlocking<Unit> {
        val root = temporary.newFolder()
        var failDirectories = false
        var failCaptures = false
        val store = PortalMediaStore(root, { now }) { file ->
            if ((failDirectories && file.isDirectory && file != root) ||
                (failCaptures && file.extension == "capture")) false else file.delete()
        }
        store.recover()
        failDirectories = true
        val cancelledOpen = cancelAtReturn { store.open("document") }
        assertTrue(cancelledOpen is CancellationException)
        assertTrue(cancelledOpen.suppressed.any { it is IOException || it.cause is IOException })
        assertEquals(1, root.listFiles()!!.size)
        failDirectories = false
        val session = store.open("document")
        try {
            failCaptures = true
            val cancelledReserve = cancelAtReturn { session.reserve(PortalMediaStore.JPEG) }
            assertTrue(cancelledReserve is CancellationException)
            assertTrue(cancelledReserve.suppressed.any { it is IOException || it.cause is IOException })
            assertEquals(1, root.walkTopDown().count { it.extension == "capture" })
            fails<IOException> { session.reserve(PortalMediaStore.JPEG) }
            failCaptures = false
            session.reserve(PortalMediaStore.JPEG)
            session.reserve(PortalMediaStore.AUDIO_MP4)
            assertEquals(2, root.walkTopDown().count { it.extension == "capture" })
        } finally { failDirectories = false; failCaptures = false; session.close() }
    }

    @Test
    fun `large compatible brand table is scanned without collecting file sized objects`() = runBlocking<Unit> {
        val store = store()
        val session = store.open("document")
        try {
            val original = portalTestAudio()
            val added = 8 * 1024 * 1024
            val firstBoxLength = java.nio.ByteBuffer.wrap(original).int
            val capture = session.reserve(PortalMediaStore.AUDIO_MP4)
            DataOutputStream(capture.file.outputStream().buffered()).use { output ->
                output.writeInt(firstBoxLength + added)
                output.write(original, 4, firstBoxLength - 4)
                val unknownBrands = ByteArray(4096)
                repeat(added / unknownBrands.size) { output.write(unknownBrands) }
                output.write(original, firstBoxLength, original.size - firstBoxLength)
            }
            val handle = session.publish(capture)
            assertEquals(original.size + added, handle.byteLength)
        } finally { session.close() }
    }

    private suspend fun store(): PortalMediaStore = PortalMediaStore(temporary.newFolder(), { now }).also { it.recover() }

    private suspend fun rejects(code: String, operation: suspend () -> Unit) {
        assertEquals(code, fails<PortalFailure>(operation).code)
    }

    private suspend inline fun <reified T : Exception> fails(operation: suspend () -> Unit): T {
        val failure = try { operation(); null } catch (error: Exception) { error }
        assertTrue("Expected ${T::class.java.simpleName}, received $failure", failure is T)
        return failure as T
    }

    private suspend fun cancelAtReturn(operation: suspend () -> Unit) = coroutineScope {
        val returning = CompletableDeferred<Runnable>()
        val intercept = AtomicBoolean(true)
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (intercept.get()) check(returning.complete(block)) else Dispatchers.Default.dispatch(context, block)
            }
        }
        var delivered = false
        var observedFailure: Exception? = null
        val pending = launch(dispatcher, start = CoroutineStart.UNDISPATCHED) {
            try { operation(); delivered = true }
            catch (failure: Exception) { observedFailure = failure; throw failure }
        }
        try {
            val resume = withTimeout(5_000) { returning.await() }
            intercept.set(false)
            pending.cancel()
            resume.run()
            pending.join()
            assertTrue(pending.isCancelled)
            assertFalse(delivered)
            requireNotNull(observedFailure)
        } finally { intercept.set(false); pending.cancelAndJoin() }
    }

    private fun paddedJpeg(size: Int): ByteArray {
        val original = portalTestJpeg()
        val output = ByteArrayOutputStream(size)
        DataOutputStream(output).use { target ->
            target.write(original, 0, 2)
            var remaining = size - original.size
            while (remaining > 0) {
                var segment = minOf(65537, remaining)
                if (remaining - segment in 1..3) segment -= 4
                check(segment >= 4)
                target.writeShort(0xfffe)
                target.writeShort(segment - 2)
                target.write(ByteArray(segment - 4))
                remaining -= segment
            }
            target.write(original, 2, original.size - 2)
        }
        return output.toByteArray().also { check(it.size == size) }
    }

}
