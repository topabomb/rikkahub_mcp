package net.weero.measix.pilot.service.portal

import java.io.File
import java.io.DataInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.attribute.BasicFileAttributes
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class PortalMediaHandle(val mediaId: String, val mimeType: String, val byteLength: Int)
internal data class PortalMediaChunk(val dataBase64: String, val nextOffset: Int, val eof: Boolean)

/** Only the native capture adapter receives this staging file; published files never leave the owner. */
internal class PortalMediaCapture internal constructor(
    internal val mediaId: String,
    val mimeType: String,
    val expiresAtMillis: Long,
    internal val file: File,
)

/** The supplied directory belongs exclusively to Portal and must be outside backup and durable media roots. */
internal class PortalMediaStore(
    private val directory: File,
    internal val nowMillis: () -> Long = System::currentTimeMillis,
    private val deleteFile: (File) -> Boolean = File::delete,
) {
    private val mutex = Mutex()
    private val sessions = mutableMapOf<String, PortalMediaSession>()
    private var recovered = false

    suspend fun recover() = locked {
        if (!recovered) {
            deleteTree(directory)
            Files.createDirectories(directory.toPath())
            recovered = true
        }
    }

    suspend fun open(documentId: String): PortalMediaSession {
        var created: PortalMediaSession? = null
        try {
            return locked {
                if (!recovered) throw PortalFailure("media_unavailable")
                sessions.values.filter { it.isAbandoned }.toList().forEach { it.closeLocked() }
                if (documentId in sessions) throw PortalFailure("media_unavailable")
                if (documentId.length !in 1..128) throw PortalFailure("invalid_request")
                val folder = File(directory, token())
                PortalMediaSession(this, documentId, folder).also {
                    created = it
                    sessions[documentId] = it
                    Files.createDirectory(folder.toPath())
                }
            }
        } catch (failure: Exception) {
            try { withContext(NonCancellable) { created?.let { session -> locked { session.abandonLocked() } } } }
            catch (cleanup: Exception) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    internal suspend fun <T> locked(operation: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { currentCoroutineContext().ensureActive(); operation() }
    }

    internal fun forget(session: PortalMediaSession) {
        check(sessions.remove(session.documentId, session))
    }

    internal fun delete(file: File) {
        if (Files.exists(file.toPath(), NOFOLLOW_LINKS) && !deleteFile(file)) {
            throw IOException("Portal temporary media deletion failed")
        }
    }

    internal suspend fun deleteTree(file: File) {
        val context = currentCoroutineContext()
        if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return
        Files.walkFileTree(file.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                context.ensureActive()
                delete(path.toFile())
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(path: Path, failure: IOException?): FileVisitResult {
                context.ensureActive()
                if (failure != null) throw failure
                delete(path.toFile())
                return FileVisitResult.CONTINUE
            }
        })
    }

    companion object {
        const val JPEG = "image/jpeg"
        const val AUDIO_MP4 = "audio/mp4"
        const val MAX_ITEM_BYTES = 10 * 1024 * 1024
        const val MAX_ITEMS = 2
        const val MAX_TOTAL_BYTES = 20 * 1024 * 1024
        const val MAX_CHUNK_BYTES = 65536
        const val TTL_MILLIS = 5 * 60 * 1000L
        private val random = SecureRandom()
        internal fun token(): String = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(32).also(random::nextBytes))
    }
}

/** Capture writers must be stopped and joined before publish, discard, or close. Sweep never touches writers. */
internal class PortalMediaSession internal constructor(
    private val store: PortalMediaStore,
    internal val documentId: String,
    private val directory: File,
) {
    private class Entry(val capture: PortalMediaCapture, val frozen: File) {
        var handle: PortalMediaHandle? = null
        var revoked = false
        var deleted = false
    }
    // Deleted entries recognize this session's repeated releases without a global handle index.
    private val entries = mutableMapOf<String, Entry>()
    private var closing = false
    private var closed = false
    internal var isAbandoned = false
        private set

    suspend fun reserve(mimeType: String): PortalMediaCapture {
        var created: PortalMediaCapture? = null
        try {
            return store.locked {
                requireOpen()
                if (mimeType != PortalMediaStore.JPEG && mimeType != PortalMediaStore.AUDIO_MP4) {
                    throw PortalFailure("invalid_request")
                }
                // Failed compensation owns a stopped writer even when its capture was never handed out.
                entries.values.filter { it.revoked && !it.deleted }.forEach(::remove)
                sweepPublished()
                val occupied = entries.values.filterNot { it.deleted }
                val reservedBytes = occupied.sumOf { it.handle?.byteLength ?: PortalMediaStore.MAX_ITEM_BYTES }
                if (occupied.size >= PortalMediaStore.MAX_ITEMS ||
                    reservedBytes + PortalMediaStore.MAX_ITEM_BYTES > PortalMediaStore.MAX_TOTAL_BYTES) {
                    throw PortalFailure("resource_limit")
                }
                val id = PortalMediaStore.token()
                PortalMediaCapture(id, mimeType, Math.addExact(store.nowMillis(), PortalMediaStore.TTL_MILLIS),
                    File(directory, "$id.capture")).also { capture ->
                    created = capture
                    entries[id] = Entry(capture, File(directory, "$id.media"))
                    Files.createFile(capture.file.toPath())
                }
            }
        } catch (failure: Exception) {
            compensate(failure) { created?.let { discard(it) } }
            throw failure
        }
    }

    suspend fun publish(capture: PortalMediaCapture): PortalMediaHandle {
        var owned = false
        try {
            return store.locked {
                val entry = entry(capture)
                // Duplicate publish must not revoke a handle already handed to the caller.
                if (entry.handle != null || entry.deleted || entry.revoked) throw PortalFailure("media_unavailable")
                owned = true
                requireOpen()
                if (store.nowMillis() >= capture.expiresAtMillis) throw PortalFailure("media_unavailable")
                if (!Files.isRegularFile(capture.file.toPath(), NOFOLLOW_LINKS)) throw PortalFailure("media_unavailable")
                val length = Files.size(capture.file.toPath())
                if (length > PortalMediaStore.MAX_ITEM_BYTES) throw PortalFailure("resource_limit")
                if (length == 0L) throw PortalFailure("media_unavailable")
                if (!validMedia(capture.file, capture.mimeType)) throw PortalFailure("media_unavailable")
                currentCoroutineContext().ensureActive()
                Files.move(capture.file.toPath(), entry.frozen.toPath(), ATOMIC_MOVE)
                if (store.nowMillis() >= capture.expiresAtMillis) throw PortalFailure("media_unavailable")
                PortalMediaHandle(capture.mediaId, capture.mimeType, length.toInt()).also { entry.handle = it }
            }
        } catch (failure: Exception) {
            if (owned) compensate(failure) { discard(capture) }
            throw failure
        }
    }

    suspend fun discard(capture: PortalMediaCapture) = store.locked {
        remove(entry(capture))
    }

    suspend fun read(mediaId: String, offset: Int, maxBytes: Int): PortalMediaChunk = store.locked {
        requireOpen()
        val entry = entries[mediaId] ?: throw PortalFailure("media_unavailable")
        val handle = entry.handle ?: throw PortalFailure("media_unavailable")
        if (store.nowMillis() >= entry.capture.expiresAtMillis) entry.revoked = true
        if (entry.revoked || entry.deleted) throw PortalFailure("media_unavailable")
        if (offset !in 0..handle.byteLength || maxBytes !in 1..PortalMediaStore.MAX_CHUNK_BYTES) {
            throw PortalFailure("invalid_request")
        }
        if (!Files.isRegularFile(entry.frozen.toPath(), NOFOLLOW_LINKS) || entry.frozen.length() != handle.byteLength.toLong()) {
            entry.revoked = true
            throw PortalFailure("media_unavailable")
        }
        val bytes = ByteArray(minOf(maxBytes, handle.byteLength - offset))
        RandomAccessFile(entry.frozen, "r").use { file -> file.seek(offset.toLong()); file.readFully(bytes) }
        if (store.nowMillis() >= entry.capture.expiresAtMillis) {
            entry.revoked = true
            throw PortalFailure("media_unavailable")
        }
        val nextOffset = offset + bytes.size
        PortalMediaChunk(Base64.getEncoder().encodeToString(bytes), nextOffset, nextOffset == handle.byteLength)
    }

    suspend fun release(mediaId: String) = store.locked {
        val entry = entries[mediaId]?.takeIf { it.handle != null } ?: throw PortalFailure("media_unavailable")
        remove(entry)
    }

    suspend fun sweep() = store.locked { sweepPublished() }

    suspend fun close() = store.locked { closeLocked() }

    internal suspend fun abandonLocked() {
        isAbandoned = true
        closeLocked()
    }

    internal suspend fun closeLocked() {
        if (!closed) {
            closing = true
            entries.values.forEach { it.revoked = true }
            store.deleteTree(directory)
            entries.values.forEach { it.deleted = true }
            store.forget(this)
            closed = true
        }
    }

    private fun requireOpen() {
        if (closing || closed) throw PortalFailure("media_unavailable")
    }

    private fun entry(capture: PortalMediaCapture): Entry = entries[capture.mediaId]
        ?.takeIf { it.capture === capture } ?: throw PortalFailure("media_unavailable")

    private fun remove(entry: Entry) {
        if (entry.deleted) return
        entry.revoked = true
        store.delete(entry.capture.file)
        store.delete(entry.frozen)
        entry.deleted = true
    }

    private fun sweepPublished() {
        entries.values.filter { !it.deleted && it.handle != null &&
            (it.revoked || store.nowMillis() >= it.capture.expiresAtMillis) }.forEach(::remove)
    }

    private suspend fun compensate(failure: Exception, cleanup: suspend () -> Unit) {
        try { withContext(NonCancellable) { cleanup() } }
        catch (error: Exception) { if (error !== failure) failure.addSuppressed(error) }
    }
}

/** Validate the capture container before freezing it; codec decoding remains the native adapter's concern. */
private fun validMedia(file: File, mimeType: String): Boolean = try {
    if (mimeType == PortalMediaStore.JPEG) DataInputStream(file.inputStream().buffered()).use(::validJpeg)
    else RandomAccessFile(file, "r").use(::validAudioMp4)
} catch (_: IOException) { false }

private fun validJpeg(input: DataInputStream): Boolean {
    if (input.readUnsignedShort() != 0xffd8) return false
    var frame = false
    var scan = false
    var entropy = false
    while (true) {
        var value = input.readUnsignedByte()
        if (entropy) {
            while (value != 0xff) value = input.readUnsignedByte()
        } else if (value != 0xff) return false
        do { value = input.readUnsignedByte() } while (value == 0xff)
        if (entropy && (value == 0 || value in 0xd0..0xd7)) continue
        entropy = false
        if (value == 0xd9) return frame && scan && input.read() == -1
        if (value == 0xd8 || value == 0 || value in 0xd0..0xd7) return false
        if (value == 1) continue
        val length = input.readUnsignedShort()
        if (length < 2) return false
        var remaining = length - 2
        if (value in 0xc0..0xcf && value !in setOf(0xc4, 0xc8, 0xcc)) {
            if (length < 11) return false
            input.readUnsignedByte()
            val height = input.readUnsignedShort()
            val width = input.readUnsignedShort()
            val components = input.readUnsignedByte()
            remaining -= 6
            if (height == 0 || width == 0 || components !in 1..4 || length != 8 + components * 3) return false
            frame = true
        }
        if (value == 0xda) {
            val components = input.readUnsignedByte()
            remaining--
            if (!frame || components !in 1..4 || length != 6 + components * 2) return false
            scan = true
            entropy = true
        }
        if (remaining < 0 || input.skipBytes(remaining) != remaining) return false
    }
}

private data class MediaBox(val type: String, val start: Long, val end: Long)

private fun mediaBoxes(input: RandomAccessFile, start: Long, end: Long): Sequence<MediaBox> = sequence {
    var position = start
    while (position < end) {
        if (end - position < 8) throw IOException("Invalid media box")
        input.seek(position)
        var size = input.readInt().toLong() and 0xffffffffL
        val type = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
        if (size == 1L) size = input.readLong()
        if (size == 0L) size = end - position
        val payload = input.filePointer
        if (size < payload - position || size > end - position) throw IOException("Invalid media box length")
        yield(MediaBox(type, payload, position + size))
        position += size
    }
}

private fun validAudioMp4(input: RandomAccessFile): Boolean {
    var format = false
    var payload = false
    var audioTracks = 0
    for (box in mediaBoxes(input, 0, input.length())) when (box.type) {
        "ftyp" -> {
            if (format || box.end - box.start < 8 || (box.end - box.start) % 4 != 0L) return false
            input.seek(box.start)
            val brands = ByteArray(4096)
            var position = 0L
            while (input.filePointer < box.end) {
                val count = minOf(brands.size.toLong(), box.end - input.filePointer).toInt()
                input.readFully(brands, 0, count)
                for (offset in 0 until count step 4) {
                    // ftyp's second word is the minor version, not a compatible brand.
                    if (position + offset == 4L) continue
                    val brand = ((brands[offset].toInt() and 255) shl 24) or
                        ((brands[offset + 1].toInt() and 255) shl 16) or
                        ((brands[offset + 2].toInt() and 255) shl 8) or (brands[offset + 3].toInt() and 255)
                    when (brand) {
                        0x4d344120, 0x69736f6d, 0x69736f32, 0x6d703431, 0x6d703432 -> format = true
                    }
                }
                position += count
            }
            if (!format) return false
        }
        "mdat" -> payload = payload || box.end > box.start
        "moov" -> for (track in mediaBoxes(input, box.start, box.end).filter { it.type == "trak" }) {
            var hasHandler = false
            for (handler in mediaBoxes(input, track.start, track.end).filter { it.type == "mdia" }
                .flatMap { mediaBoxes(input, it.start, it.end) }.filter { it.type == "hdlr" }) {
                if (hasHandler || handler.end - handler.start < 12) return false
                input.seek(handler.start + 8)
                if (input.readInt() != 0x736f756e) return false
                hasHandler = true
            }
            if (!hasHandler) return false
            audioTracks++
        }
    }
    return format && payload && audioTracks > 0
}
