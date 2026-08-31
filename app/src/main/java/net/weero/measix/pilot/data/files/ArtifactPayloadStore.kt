package net.weero.measix.pilot.data.files

import android.content.Context
import android.net.Uri
import androidx.core.net.toFile
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream

internal class ArtifactPayloadTooLargeException : java.io.IOException("Artifact payload exceeds the size limit")

/**
 * 托管 artifact 的纯磁盘层。它不知道数据库、引用或删除策略，只负责 staging、
 * 同文件系统发布、查询与物理删除。生命周期裁决全部由 [ArtifactStore] 完成。
 */
class ArtifactPayloadStore(private val context: Context) {
    class ReservedPayload internal constructor(
        val folder: String,
        val relativePath: String,
        val stagingToken: String,
    )

    data class StagedPayload(
        val folder: String,
        val relativePath: String,
        val stagingToken: String,
        val sizeBytes: Long,
    )

    suspend fun stageFromUri(
        reserved: ReservedPayload,
        uri: Uri,
        maxBytes: Long? = null,
    ): StagedPayload = stageOnIo(reserved) { staging ->
        val input = context.contentResolver.openInputStream(uri)
            ?: error("Failed to open input stream for $uri")
        input.use { source ->
            staging.outputStream().use { destination ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var written = 0L
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    written += read
                    require(maxBytes == null || written <= maxBytes) {
                        "Artifact payload exceeds the size limit"
                    }
                    destination.write(buffer, 0, read)
                }
            }
        }
    }

    suspend fun stageFromBytes(
        reserved: ReservedPayload,
        bytes: ByteArray,
    ): StagedPayload = stageOnIo(reserved) { it.writeBytes(bytes) }

    suspend fun stageText(
        reserved: ReservedPayload,
        text: String,
    ): StagedPayload = stageOnIo(reserved) { it.writeText(text) }

    /** Atomically occupies a candidate; metadata and naming decisions stay in ArtifactStore. */
    suspend fun reserve(folder: String, fileName: String): ReservedPayload {
        val acquired = AtomicReference<ReservedPayload?>()
        return try {
            withContext(Dispatchers.IO) {
                require(fileName.isNotBlank() && File(fileName).name == fileName && '/' !in fileName && '\\' !in fileName)
                val relativePath = FileUtils.buildRelativePath(folder, File(fileName))
                val token = "$fileName.part"
                val staging = stagingFile(token)
                staging.parentFile?.mkdirs()
                check(!file(relativePath).exists()) { "Artifact target already exists: $relativePath" }
                check(staging.createNewFile()) { "Artifact staging collision: $token" }
                ReservedPayload(folder, relativePath, token).also(acquired::set)
            }
        } catch (error: Throwable) {
            acquired.get()?.let { cleanupReservation(it, error) }
            throw error
        }
    }

    /**
     * Owns the staging file until the completed descriptor is delivered back to ArtifactStore.
     * This closes the prompt-cancellation gap at the IO dispatcher return boundary.
     */
    private suspend fun stageOnIo(reserved: ReservedPayload, writer: (File) -> Unit): StagedPayload {
        return try {
            withContext(Dispatchers.IO) {
                val staging = stagingFile(reserved.stagingToken)
                check(staging.isFile) { "Artifact staging reservation missing: ${reserved.stagingToken}" }
                writer(staging)
                StagedPayload(reserved.folder, reserved.relativePath, reserved.stagingToken, staging.length())
            }
        } catch (error: Throwable) {
            cleanupReservation(reserved, error)
            throw error
        }
    }

    private suspend fun cleanupReservation(reserved: ReservedPayload, primary: Throwable) {
        try {
            withContext(NonCancellable + Dispatchers.IO) {
                check(deleteIfPresent(stagingFile(reserved.stagingToken))) {
                    "Failed to remove artifact staging payload: ${reserved.stagingToken}"
                }
            }
        } catch (cleanup: Throwable) {
            primary.addSuppressed(cleanup)
        }
    }

    /** staging 与最终目录同属 filesDir；rename 成功即不会暴露半写 payload。 */
    suspend fun promote(staged: StagedPayload): File = withContext(Dispatchers.IO) {
        promote(staged.relativePath, staged.stagingToken)
    }

    suspend fun promote(relativePath: String, stagingToken: String): File = withContext(Dispatchers.IO) {
        val staging = stagingFile(stagingToken)
        val target = file(relativePath)
        target.parentFile?.mkdirs()
        check(!target.exists()) { "Artifact target already exists: $relativePath" }
        check(staging.isFile) { "Artifact staging payload missing: $stagingToken" }
        check(staging.renameTo(target)) { "Failed to atomically publish artifact payload: $relativePath" }
        target
    }

    fun file(relativePath: String): File {
        require(relativePath.isNotBlank()) { "Artifact payload path is blank" }
        val root = context.filesDir.canonicalFile
        val target = File(root, relativePath.replace('\\', '/')).canonicalFile
        require(target.path.startsWith(root.path + File.separator)) {
            "Artifact payload escapes the managed files directory: $relativePath"
        }
        return target
    }

    fun relativePathForUri(uri: Uri): String? {
        if (!uri.toString().startsWith("file:", ignoreCase = true)) return null
        val source = runCatching { uri.toFile() }.getOrNull() ?: return null
        return relativePathForFile(source)
    }

    fun relativePathForFile(file: File): String? =
        FileUtils.getRelativePathInFilesDir(context.filesDir, file)

    fun displayName(uri: Uri): String? = FileUtils.getFileNameFromUri(context, uri)

    fun mimeType(uri: Uri): String? = FileUtils.getFileMimeType(context, uri)

    /** Bounded, cancellable payload read; lifetime authorization belongs to ArtifactStore. */
    suspend fun readBytes(relativePath: String, maxBytes: Long): ByteArray = withContext(Dispatchers.IO) {
        val source = file(relativePath)
        if (source.length() > maxBytes) throw ArtifactPayloadTooLargeException()
        source.inputStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size().toLong() + count > maxBytes) throw ArtifactPayloadTooLargeException()
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    fun stagingExists(stagingToken: String): Boolean = stagingFile(stagingToken).isFile

    fun finalExists(relativePath: String): Boolean = file(relativePath).isFile

    suspend fun pathOccupied(folder: String, fileName: String): Boolean = withContext(Dispatchers.IO) {
        file("$folder/$fileName").exists() || stagingFile("$fileName.part").exists()
    }

    suspend fun deleteStaging(stagingToken: String?): Boolean = withContext(Dispatchers.IO) {
        stagingToken == null || deleteIfPresent(stagingFile(stagingToken))
    }

    suspend fun deleteFinal(relativePath: String): Boolean = withContext(Dispatchers.IO) {
        deleteIfPresent(file(relativePath))
    }

    fun listFinalFiles(folder: String): List<File> =
        file(folder).listFiles()?.filter(File::isFile).orEmpty()

    fun listStagingTokens(): List<String> =
        file(STAGING_FOLDER).listFiles()?.filter(File::isFile)?.map(File::getName).orEmpty()

    fun deleteEmptyFolder(folder: String): Boolean {
        val directory = file(folder)
        if (!directory.exists()) return true
        val entries = directory.listFiles() ?: return false
        return entries.isEmpty() && directory.delete()
    }

    private fun stagingFile(token: String): File {
        require(token.isNotBlank() && File(token).name == token && '/' !in token && '\\' !in token) {
            "Invalid artifact staging token: $token"
        }
        return file("$STAGING_FOLDER/$token")
    }

    private fun deleteIfPresent(file: File): Boolean = !file.exists() || file.delete()

    companion object {
        const val STAGING_FOLDER = ".artifact-staging"
    }
}
