package net.weero.measix.pilot.data.files

import android.content.Context
import android.net.Uri
import androidx.core.net.toFile
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * 托管 artifact 的纯磁盘层。它不知道数据库、引用或删除策略，只负责 staging、
 * 同文件系统发布、查询与物理删除。生命周期裁决全部由 [ArtifactStore] 完成。
 */
class ArtifactPayloadStore(private val context: Context) {
    data class StagedPayload(
        val folder: String,
        val relativePath: String,
        val stagingToken: String,
        val sizeBytes: Long,
    )

    suspend fun stageFromUri(
        folder: String,
        uri: Uri,
        displayName: String,
        mimeType: String?,
        maxBytes: Long? = null,
    ): StagedPayload = stageOnIo {
        stage(folder, displayName, mimeType) { staging ->
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
    }

    suspend fun stageFromBytes(
        folder: String,
        bytes: ByteArray,
        displayName: String,
        mimeType: String?,
    ): StagedPayload = stageOnIo {
        stage(folder, displayName, mimeType) { it.writeBytes(bytes) }
    }

    suspend fun stageText(
        folder: String,
        text: String,
        displayName: String,
        mimeType: String?,
    ): StagedPayload = stageOnIo {
        stage(folder, displayName, mimeType) { it.writeText(text) }
    }

    /**
     * Owns the staging file until the completed descriptor is delivered back to ArtifactStore.
     * This closes the prompt-cancellation gap at the IO dispatcher return boundary.
     */
    private suspend fun stageOnIo(block: () -> StagedPayload): StagedPayload {
        val completed = AtomicReference<StagedPayload?>()
        return try {
            withContext(Dispatchers.IO) { block().also(completed::set) }
        } catch (error: Throwable) {
            completed.get()?.let { staged ->
                withContext(NonCancellable + Dispatchers.IO) {
                    if (!deleteIfPresent(stagingFile(staged.stagingToken))) {
                        error.addSuppressed(
                            IllegalStateException(
                                "Failed to remove undelivered artifact staging payload: ${staged.stagingToken}"
                            )
                        )
                    }
                }
            }
            throw error
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

    fun stagingExists(stagingToken: String): Boolean = stagingFile(stagingToken).isFile

    fun finalExists(relativePath: String): Boolean = file(relativePath).isFile

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

    private fun stage(
        folder: String,
        displayName: String,
        mimeType: String?,
        writer: (File) -> Unit,
    ): StagedPayload {
        val finalName = FileUtils.buildUuidFileName(displayName, mimeType)
        val relativePath = FileUtils.buildRelativePath(folder, File(finalName))
        val token = "$finalName.part"
        val staging = stagingFile(token)
        staging.parentFile?.mkdirs()
        check(staging.createNewFile()) { "Artifact staging collision: $token" }
        try {
            writer(staging)
            return StagedPayload(folder, relativePath, token, staging.length())
        } catch (error: Throwable) {
            if (staging.exists() && !staging.delete()) {
                error.addSuppressed(
                    IllegalStateException("Failed to remove incomplete artifact staging payload: $token")
                )
            }
            throw error
        }
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
