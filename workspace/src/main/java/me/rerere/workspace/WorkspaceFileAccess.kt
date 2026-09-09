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
    private var handle = WorkspaceFileAccess.open(
        directory.absolutePath.toByteArray(Charsets.UTF_8), path.toByteArray(Charsets.UTF_8),
        writable, create, overwrite,
    )

    fun read(maxBytes: Long): ByteArray = WorkspaceFileAccess.read(handle, maxBytes)

    fun write(bytes: ByteArray): LongArray = WorkspaceFileAccess.write(handle, bytes)

    override fun close() {
        if (handle != 0L) {
            WorkspaceFileAccess.close(handle)
            handle = 0L
        }
    }
}

@Keep
internal object WorkspaceFileAccess {
    init { System.loadLibrary("workspace") }

    external fun open(root: ByteArray, path: ByteArray, writable: Boolean, create: Boolean, overwrite: Boolean): Long
    external fun read(handle: Long, maxBytes: Long): ByteArray
    external fun write(handle: Long, bytes: ByteArray): LongArray
    external fun close(handle: Long)
    external fun openDirectory(root: ByteArray): Int
    external fun openChild(parent: Int, name: ByteArray, flags: Int, metadataOnly: Boolean, directory: Boolean): Int
    external fun statDescriptor(descriptor: Int): LongArray
    external fun listDirectory(descriptor: Int): Array<ByteArray>
    external fun createDirectory(parent: Int, name: ByteArray): Int
    external fun removeChild(parent: Int, name: ByteArray, directory: Boolean)
    external fun renameChild(source: Int, name: ByteArray, destination: Int, target: ByteArray): Boolean
}
