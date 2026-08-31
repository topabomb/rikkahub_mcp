package me.rerere.workspace

import androidx.annotation.Keep
import java.io.Closeable
import java.io.File

/** The open directory/file descriptors, not a re-resolved path, own a file operation. */
internal class RootfsFileHandle(
    directory: File,
    path: String,
    writable: Boolean,
    create: Boolean,
    overwrite: Boolean,
) : Closeable {
    private var handle = RootfsFileAccess.open(
        directory.absolutePath.toByteArray(Charsets.UTF_8), path.toByteArray(Charsets.UTF_8),
        writable, create, overwrite,
    )

    fun read(maxBytes: Long): ByteArray = RootfsFileAccess.read(handle, maxBytes)

    fun write(bytes: ByteArray): LongArray = RootfsFileAccess.write(handle, bytes)

    override fun close() {
        if (handle != 0L) {
            RootfsFileAccess.close(handle)
            handle = 0L
        }
    }
}

@Keep
internal object RootfsFileAccess {
    init { System.loadLibrary("workspace") }

    external fun open(root: ByteArray, path: ByteArray, writable: Boolean, create: Boolean, overwrite: Boolean): Long
    external fun read(handle: Long, maxBytes: Long): ByteArray
    external fun write(handle: Long, bytes: ByteArray): LongArray
    external fun close(handle: Long)
}
