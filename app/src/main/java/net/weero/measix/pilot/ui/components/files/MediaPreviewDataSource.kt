package net.weero.measix.pilot.ui.components.files

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import net.weero.measix.pilot.service.files.MediaPreviewSource
import java.io.IOException

/** Loader-thread IO; close may cancel it from another thread without publishing late bytes. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class MediaPreviewDataSource(private val source: MediaPreviewSource) : BaseDataSource(true) {
    private class ReadSession(val uri: Uri, var position: Long, var remaining: Long) {
        val job = Job()
        var buffer = ByteArray(0)
        var bufferOffset = 0
        var started = false
    }
    private val lock = Any()
    private var current: ReadSession? = null

    override fun open(dataSpec: DataSpec): Long {
        if (dataSpec.position > source.length) throw IOException("workspace_media_position_out_of_range")
        val available = source.length - dataSpec.position
        val session = ReadSession(dataSpec.uri, dataSpec.position,
            if (dataSpec.length == C.LENGTH_UNSET.toLong()) available else minOf(available, dataSpec.length))
        try {
            synchronized(lock) {
                check(current == null) { "workspace_media_already_open" }
                current = session
                transferInitializing(dataSpec)
            }
            runBlocking(session.job) { source.verifyAccess() }
            return synchronized(lock) {
                requireCurrent(session)
                session.started = true
                transferStarted(dataSpec)
                requireCurrent(session)
                session.remaining
            }
        } catch (error: Exception) {
            release(session)
            throw asIOException(error)
        }
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val session = synchronized(lock) { current ?: throw IOException("workspace_media_closed") }
        try {
            runBlocking(session.job) { source.verifyAccess() }
            val range = synchronized(lock) {
                requireCurrent(session)
                if (session.remaining == 0L) return C.RESULT_END_OF_INPUT
                if (session.bufferOffset == session.buffer.size) {
                    val window = if (session.position == 0L) 256 * 1024 else 8 * 1024 * 1024
                    session.position to minOf(session.remaining, window.toLong()).toInt()
                } else null
            }
            if (range != null) {
                // Neither the state lock nor a previous session's authority spans network IO.
                val bytes = runBlocking(session.job) { source.readRange(range.first, range.second) }
                if (bytes.size != range.second) throw IOException("workspace_incomplete_media_range")
                synchronized(lock) {
                    requireCurrent(session)
                    session.buffer = bytes
                    session.bufferOffset = 0
                }
            }
            return synchronized(lock) {
                requireCurrent(session)
                val count = minOf(length, session.buffer.size - session.bufferOffset,
                    session.remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                session.buffer.copyInto(target, offset, session.bufferOffset, session.bufferOffset + count)
                session.bufferOffset += count
                session.position += count
                session.remaining -= count
                bytesTransferred(count)
                count
            }
        } catch (error: Exception) {
            throw asIOException(error)
        }
    }

    private fun requireCurrent(session: ReadSession) {
        if (current !== session || !session.job.isActive) throw IOException("workspace_media_closed")
    }

    private fun asIOException(error: Exception): IOException =
        if (error is IOException) error else IOException("workspace_media_read_failed: ${error.message}", error)

    override fun getUri(): Uri? = synchronized(lock) { current?.uri }

    override fun close() {
        val session = synchronized(lock) { current } ?: return
        release(session)
    }

    private fun release(session: ReadSession) {
        synchronized(lock) {
            if (current === session) {
                current = null
                session.buffer = ByteArray(0)
                session.bufferOffset = 0
                if (session.started) { session.started = false; transferEnded() }
            }
        }
        // Cancel only the detached session. A subsequent open owns a different job.
        session.job.cancel()
    }
}
