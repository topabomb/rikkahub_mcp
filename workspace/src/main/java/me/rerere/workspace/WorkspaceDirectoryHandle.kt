package me.rerere.workspace

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID

/** A scoped directory descriptor; descendants are never resolved through mutable absolute paths. */
internal class WorkspaceDirectoryHandle private constructor(private val descriptor: ParcelFileDescriptor) : Closeable {
    constructor(root: File) : this(ParcelFileDescriptor.adoptFd(WorkspaceFileAccess.openDirectory(root.absolutePath.toByteArray())))

    private val fd get() = descriptor.fd

    fun directory(path: String): WorkspaceDirectoryHandle {
        var current = WorkspaceDirectoryHandle(ParcelFileDescriptor.dup(descriptor.fileDescriptor))
        try {
            for (name in segments(path)) {
                val next = current.child(name, metadataOnly = true, directory = true)
                    ?: throw FileNotFoundException(path)
                current.close()
                current = WorkspaceDirectoryHandle(next)
            }
            return current
        } catch (error: Throwable) { current.close(); throw error }
    }

    fun stat(path: String): WorkspaceFileEntry? {
        if (path.isEmpty()) return metadata(descriptor).entry(path)
        return parent(path) { directory, name ->
            directory.child(name, metadataOnly = true)?.use { file -> metadata(file).entry(path) }
        }
    }

    fun list(path: String): List<WorkspaceFileEntry> = directory(path).use { directory ->
        directory.names().filterNot { it.startsWith(".l2s.") }
            .mapNotNull { name -> directory.child(name, metadataOnly = true)?.use { metadata(it).entry(join(path, name)) } }
            .sortedWith(compareBy<WorkspaceFileEntry> { !it.isDirectory }.thenBy { it.name.lowercase() })
    }

    fun open(path: String, mode: Int): ParcelFileDescriptor = parent(path) { directory, name ->
        val writable = mode and ParcelFileDescriptor.MODE_WRITE_ONLY != 0
        val flags = when (mode and ParcelFileDescriptor.MODE_READ_WRITE) {
            ParcelFileDescriptor.MODE_READ_ONLY -> OsConstants.O_RDONLY
            ParcelFileDescriptor.MODE_WRITE_ONLY -> OsConstants.O_WRONLY
            ParcelFileDescriptor.MODE_READ_WRITE -> OsConstants.O_RDWR
            else -> throw IllegalArgumentException("Invalid document mode")
        } or if (mode and ParcelFileDescriptor.MODE_APPEND != 0) OsConstants.O_APPEND else 0
        val file = directory.child(name, flags) ?: throw FileNotFoundException(path)
        try {
            val metadata = metadata(file)
            check(metadata.regular && (!writable || metadata.links == 1L)) { "Document is not a writable regular file" }
            if (writable && mode and ParcelFileDescriptor.MODE_TRUNCATE != 0) Os.ftruncate(file.fileDescriptor, 0)
            file
        } catch (error: Throwable) { file.close(); throw error }
    }

    fun create(path: String, name: String, isDirectory: Boolean): String = directory(path).use { parent ->
        uniqueName(name) { candidate ->
            if (isDirectory) parent.createDirectory(candidate)?.use { true } ?: false
            else parent.child(candidate, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL)?.use { true } ?: false
        }.let { join(path, it) }
    }

    fun delete(path: String) = parent(path) { directory, name -> directory.removeTree(name, 0) }

    fun rename(path: String, name: String): String = parent(path) { directory, original ->
        validateName(name)
        directory.child(original, metadataOnly = true)?.use { check(metadata(it).supported) { "Unsupported document" } }
            ?: throw FileNotFoundException(path)
        check(WorkspaceFileAccess.renameChild(directory.fd, original.toByteArray(), directory.fd, name.toByteArray())) { "Document already exists" }
        join(path.substringBeforeLast('/', ""), name)
    }

    fun move(source: String, target: WorkspaceDirectoryHandle, targetPath: String): String = parent(source) { sourceParent, name ->
        target.directory(targetPath).use { destination ->
            sourceParent.child(name, metadataOnly = true)?.use { check(metadata(it).supported) { "Unsupported document" } }
                ?: throw FileNotFoundException(source)
            join(targetPath, uniqueName(name) {
                WorkspaceFileAccess.renameChild(sourceParent.fd, name.toByteArray(), destination.fd, it.toByteArray())
            })
        }
    }

    fun copy(source: String, target: WorkspaceDirectoryHandle, targetPath: String, staging: WorkspaceDirectoryHandle): String =
        parent(source) { sourceParent, name -> target.directory(targetPath).use { destination ->
            val temporary = COPY_PREFIX + UUID.randomUUID()
            var owned: Metadata? = null
            var published = false
            var failure: Throwable? = null
            try {
                sourceParent.copyChild(name, staging, temporary, 0) { owned = it }
                val result = uniqueName(name) {
                    WorkspaceFileAccess.renameChild(staging.fd, temporary.toByteArray(), destination.fd, it.toByteArray())
                }
                published = true
                join(targetPath, result)
            } catch (error: Throwable) { failure = error; throw error }
            finally {
                if (!published && owned != null) {
                    // Cancellation cannot leave an unpublished copy; restore the interrupt afterwards.
                    val interrupted = Thread.interrupted()
                    try {
                        staging.child(temporary, metadataOnly = true)?.use {
                            if (metadata(it).sameFile(owned!!)) staging.removeTree(temporary, 0)
                        }
                    } catch (cleanup: Throwable) { failure?.addSuppressed(cleanup) ?: throw cleanup }
                    finally { if (interrupted) Thread.currentThread().interrupt() }
                }
            }
        } }

    fun ensureDirectory(name: String): WorkspaceDirectoryHandle = createDirectory(name) ?: directory(name)

    private fun copyChild(name: String, target: WorkspaceDirectoryHandle, targetName: String, depth: Int, created: (Metadata) -> Unit = {}) {
        checkDepth(depth)
        val source = child(name, OsConstants.O_RDONLY) ?: throw FileNotFoundException(name)
        source.use {
            val sourceMetadata = metadata(source)
            check(sourceMetadata.supported) { "Cannot copy symbolic links or special files" }
            if (sourceMetadata.directory) {
                val createdDirectory = target.createDirectory(targetName) ?: throw IOException("Copy target already exists")
                createdDirectory.use { output ->
                    created(metadata(output.descriptor))
                    WorkspaceDirectoryHandle(ParcelFileDescriptor.dup(source.fileDescriptor)).use { input ->
                        input.names().forEach { input.copyChild(it, output, it, depth + 1) }
                    }
                }
            } else {
                val output = target.child(targetName, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL)
                    ?: throw IOException("Copy target already exists")
                ParcelFileDescriptor.AutoCloseOutputStream(output).use { stream ->
                    created(metadata(output))
                    ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(source.fileDescriptor)).use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            checkInterrupted()
                            val count = input.read(buffer)
                            if (count < 0) break
                            stream.write(buffer, 0, count)
                        }
                    }
                    stream.flush()
                    Os.fsync(output.fileDescriptor)
                }
            }
        }
    }

    private fun removeTree(name: String, depth: Int) {
        checkDepth(depth)
        val original = child(name, metadataOnly = true) ?: return
        original.use {
            val initial = metadata(original)
            if (initial.directory) {
                directory(name).use { directory ->
                    check(metadata(directory.descriptor).sameFile(initial)) { "Document changed during deletion" }
                    directory.names().forEach { directory.removeTree(it, depth + 1) }
                }
            }
            child(name, metadataOnly = true)?.use { current ->
                check(metadata(current).sameFile(initial)) { "Document changed during deletion" }
                WorkspaceFileAccess.removeChild(fd, name.toByteArray(), initial.directory)
            }
        }
    }

    private fun createDirectory(name: String): WorkspaceDirectoryHandle? {
        validateName(name)
        val result = WorkspaceFileAccess.createDirectory(fd, name.toByteArray())
        return if (result < 0) null else WorkspaceDirectoryHandle(ParcelFileDescriptor.adoptFd(result))
    }

    private fun names(): List<String> = WorkspaceFileAccess.listDirectory(fd).map { bytes ->
        bytes.toString(Charsets.UTF_8).also { check(it.toByteArray().contentEquals(bytes)) { "Invalid UTF-8 document name" }; validateName(it) }
    }

    private fun child(name: String, flags: Int = 0, metadataOnly: Boolean = false, directory: Boolean = false): ParcelFileDescriptor? {
        validateName(name)
        val result = WorkspaceFileAccess.openChild(fd, name.toByteArray(), flags, metadataOnly, directory)
        return if (result < 0) null else ParcelFileDescriptor.adoptFd(result)
    }

    private inline fun <T> parent(path: String, block: (WorkspaceDirectoryHandle, String) -> T): T {
        val parts = segments(path)
        require(parts.isNotEmpty()) { "Cannot modify or open the Workspace root" }
        return directory(parts.dropLast(1).joinToString("/")).use { block(it, parts.last()) }
    }

    override fun close() = descriptor.close()

    private data class Metadata(val mode: Int, val size: Long, val modified: Long, val device: Long, val inode: Long, val links: Long) {
        val regular get() = OsConstants.S_ISREG(mode)
        val directory get() = OsConstants.S_ISDIR(mode)
        val supported get() = regular || directory
        fun sameFile(other: Metadata) = device == other.device && inode == other.inode
        fun entry(path: String) = if (!supported) null else WorkspaceFileEntry(path, path.substringAfterLast('/'), directory, if (directory) 0 else size, modified)
    }

    companion object {
        private const val COPY_PREFIX = ".workspace-copy-"
        private fun metadata(file: ParcelFileDescriptor) = WorkspaceFileAccess.statDescriptor(file.fd).let {
            Metadata(it[0].toInt(), it[1], it[2], it[3], it[4], it[5])
        }
        private fun segments(path: String): List<String> = if (path.isEmpty()) emptyList() else path.split('/').onEach(::validateName)
        private fun validateName(name: String) {
            require(name.isNotEmpty() && name != "." && name != ".." && name.none { it == '/' || it == '\u0000' }) { "Invalid document name" }
        }
        private fun join(parent: String, name: String) = if (parent.isEmpty()) name else "$parent/$name"
        private fun checkInterrupted() { if (Thread.currentThread().isInterrupted) throw InterruptedException("Document operation interrupted") }
        private fun checkDepth(depth: Int) { checkInterrupted(); check(depth < 128) { "Document tree is too deep" } }
        private inline fun uniqueName(name: String, create: (String) -> Boolean): String {
            validateName(name)
            val extension = name.substringAfterLast('.', "").takeIf { '.' in name }?.let { ".$it" }.orEmpty()
            val stem = name.removeSuffix(extension)
            for (index in 0..10_000) {
                checkInterrupted()
                val candidate = if (index == 0) name else "$stem ($index)$extension"
                if (create(candidate)) return candidate
            }
            throw IOException("Cannot allocate a document name")
        }
    }
}
