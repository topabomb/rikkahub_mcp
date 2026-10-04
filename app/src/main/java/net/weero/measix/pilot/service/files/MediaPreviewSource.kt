package net.weero.measix.pilot.service.files

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal const val MAX_MEDIA_BYTES = 4L * 1024 * 1024 * 1024

/** The acquiring page owns this capability until disposal, independently of player reloads. */
internal class MediaPreviewSource(
    val name: String,
    val length: Long,
    val accessChanges: kotlinx.coroutines.flow.Flow<*>,
    verifyAccess: suspend () -> Unit,
    close: () -> Unit = {},
    readRange: suspend (Long, Int) -> ByteArray,
) {
    private val closed = AtomicBoolean()
    private val accessCheck = verifyAccess
    private val rangeReader = readRange
    private val release = close
    init { require(length in 1..MAX_MEDIA_BYTES) { "workspace_media_size_limit" } }

    val verifyAccess: suspend () -> Unit = {
        requireOpen()
        accessCheck()
        requireOpen()
    }
    val readRange: suspend (Long, Int) -> ByteArray = { position, count ->
        requireOpen()
        require(position >= 0 && position < length && count in 1..8 * 1024 * 1024) {
            "workspace_media_invalid_range"
        }
        val bytes = rangeReader(position, count)
        requireOpen()
        bytes
    }
    val close: () -> Unit = { if (closed.compareAndSet(false, true)) release() }

    private suspend fun requireOpen() {
        currentCoroutineContext().ensureActive()
        check(!closed.get()) { "workspace_media_closed" }
    }
}
