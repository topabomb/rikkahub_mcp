package net.weero.measix.pilot.data.files

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.core.net.toFile
import androidx.core.net.toUri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.mediaPersistenceFailurePart
import me.rerere.common.android.Logging
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import net.weero.measix.pilot.data.db.dao.ArtifactDAO
import net.weero.measix.pilot.data.db.entity.ArtifactEntity
import net.weero.measix.pilot.data.db.entity.ArtifactOrigin
import net.weero.measix.pilot.utils.ImageExportResult
import net.weero.measix.pilot.utils.exportImage
import net.weero.measix.pilot.utils.exportImageFile
import net.weero.measix.pilot.utils.getActivity
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal const val IMAGE_SAVE_PERMISSION_REQUIRED = "permission_required"

class FilesManager(
    private val context: Context,
    private val artifactDAO: ArtifactDAO,
    private val appScope: AppScope,
) {
    companion object {
        private const val TAG = "FilesManager"
    }

    private val mutationMutex = Mutex()

    suspend fun saveManagedFromUri(
        folder: String,
        uri: Uri,
        displayName: String? = null,
        mimeType: String? = null,
        origin: ArtifactOrigin = ArtifactOrigin.USER,
    ): ArtifactEntity = mutationMutex.withLock {
        withContext(Dispatchers.IO) {
            val resolvedName = displayName ?: getFileNameFromUri(uri) ?: "file"
            val resolvedMime = mimeType ?: getFileMimeType(uri) ?: "application/octet-stream"
            val target = createTargetFile(folder, resolvedName, resolvedMime)
            commitManagedFile(target) {
                val input = context.contentResolver.openInputStream(uri)
                    ?: error("Failed to open input stream for $uri")
                input.use { stream ->
                    target.outputStream().use { output ->
                        stream.copyTo(output)
                    }
                }
                createArtifactEntity(
                    folder = folder,
                    file = target,
                    displayName = resolvedName,
                    mimeType = resolvedMime,
                    origin = origin,
                )
            }
        }
    }

    suspend fun saveManagedFromBytes(
        folder: String,
        bytes: ByteArray,
        displayName: String,
        mimeType: String = "application/octet-stream",
        origin: ArtifactOrigin = ArtifactOrigin.USER,
    ): ArtifactEntity = mutationMutex.withLock {
        withContext(Dispatchers.IO) {
            val target = createTargetFile(folder, displayName, mimeType)
            commitManagedFile(target) {
                target.writeBytes(bytes)
                createArtifactEntity(
                    folder = folder,
                    file = target,
                    displayName = displayName,
                    mimeType = mimeType,
                    origin = origin,
                )
            }
        }
    }

    suspend fun saveManagedText(
        folder: String,
        text: String,
        displayName: String = "pasted_text.txt",
        mimeType: String = "text/plain",
        origin: ArtifactOrigin = ArtifactOrigin.USER,
    ): ArtifactEntity = mutationMutex.withLock {
        withContext(Dispatchers.IO) {
            val target = createTargetFile(folder, displayName, mimeType)
            commitManagedFile(target) {
                target.writeText(text)
                createArtifactEntity(
                    folder = folder,
                    file = target,
                    displayName = displayName,
                    mimeType = mimeType,
                    origin = origin,
                )
            }
        }
    }

    fun observe(folder: String = FileFolders.UPLOAD): Flow<List<ArtifactEntity>> =
        artifactDAO.listByFolder(folder)

    suspend fun list(folder: String = FileFolders.UPLOAD): List<ArtifactEntity> =
        artifactDAO.listByFolder(folder).first()

    suspend fun get(id: Long): ArtifactEntity? = artifactDAO.getById(id)

    suspend fun getByRelativePath(relativePath: String): ArtifactEntity? = artifactDAO.getByPath(relativePath)

    fun getFile(entity: ArtifactEntity): File =
        File(context.filesDir, entity.relativePath)

    /**
     * 写入 + 登记原子化：登记失败回滚删除磁盘文件，URI 不返回。
     * "文件 + 记录"要么都在、要么都不在——DB 是唯一事实源，
     * 不存在"磁盘有文件、DB 无记录"的应用侧路径（untracked 只可能来自外部）。
     *
     * 诞生方式（origin）自动判定：源 URI 指向已登记的本地 artifact（会话 fork、
     * 子助手克隆、附件入站等结构性复制场景）→ 继承源实体 origin；否则视为用户
     * 引入新内容（[ArtifactOrigin.USER]）。
     */
    suspend fun createChatFilesByContents(uris: List<Uri>): List<Uri> = withContext(Dispatchers.IO) {
        val newUris = mutableListOf<Uri>()
        val dir = context.filesDir.resolve(FileFolders.UPLOAD)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        uris.forEach { uri ->
            runCatching {
                val sourceName = getFileNameFromUri(uri) ?: uri.lastPathSegment ?: "file"
                val sourceMime = getFileMimeType(uri)
                val fileName = buildUuidFileName(displayName = sourceName, mimeType = sourceMime)
                val file = dir.resolve(fileName)
                if (!file.exists()) {
                    file.createNewFile()
                }
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: error("Failed to open input stream for $uri")
                inputStream.use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                val guessedMime = sourceMime ?: guessMimeType(file, sourceName)
                if (!registerTrackedFile(FileFolders.UPLOAD, file, sourceName, guessedMime, inheritOriginOrDefault(uri))) {
                    error("artifact registration failed for ${file.absolutePath}")
                }
                newUris.add(file.toUri())
            }.onFailure {
                it.printStackTrace()
                Log.e(TAG, "createChatFilesByContents: Failed to save file from $uri", it)
                Logging.log(
                    TAG,
                    "createChatFilesByContents: Failed to save file from $uri: ${it.message}"
                )
            }
        }
        newUris
    }

    /** 语义同 [createChatFilesByContents]：登记失败回滚删文件，不返回失效 URI。origin 由调用方按产物链路指定。 */
    suspend fun createChatFilesByByteArrays(
        byteArrays: List<ByteArray>,
        origin: ArtifactOrigin,
    ): List<Uri> = withContext(Dispatchers.IO) {
        val newUris = mutableListOf<Uri>()
        val dir = context.filesDir.resolve(FileFolders.UPLOAD)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        byteArrays.forEach { byteArray ->
            runCatching {
                val fileName = buildUuidFileName(displayName = "image.png", mimeType = "image/png")
                val file = dir.resolve(fileName)
                if (!file.exists()) {
                    file.createNewFile()
                }
                file.outputStream().use { outputStream ->
                    outputStream.write(byteArray)
                }
                if (!registerTrackedFile(FileFolders.UPLOAD, file, "image.png", "image/png", origin)) {
                    error("artifact registration failed for ${file.absolutePath}")
                }
                newUris.add(file.toUri())
            }.onFailure {
                Log.e(TAG, "createChatFilesByByteArrays: Failed to save file", it)
            }
        }
        newUris
    }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun convertBase64ImagePartToLocalFile(message: UIMessage): UIMessage =
        withContext(Dispatchers.IO) {
            message.copy(parts = convertBase64Parts(message.parts))
        }

    private suspend fun convertBase64Parts(parts: List<UIMessagePart>): List<UIMessagePart> {
        return parts.map { part ->
            when (part) {
                is UIMessagePart.Image -> convertBase64Image(part)
                is UIMessagePart.Tool -> part.copy(output = convertBase64Parts(part.output))
                else -> part
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun convertBase64Image(part: UIMessagePart.Image): UIMessagePart {
        if (!part.url.startsWith("data:image")) return part
        return recoverMediaPersistenceFailure(
            onFailure = { error ->
                Log.w(TAG, "convertBase64ImagePartToLocalFile: replace unpersistable image", error)
                mediaPersistenceFailurePart(part)
            },
        ) {
            val sourceByteArray = Base64.decode(part.url.substringAfter("base64,").toByteArray())
            val bitmap = BitmapFactory.decodeByteArray(sourceByteArray, 0, sourceByteArray.size)
                ?: error("incomplete image data")
            val byteArray = FileUtils.compressBitmapToPng(bitmap)
            // 模型输出落盘——系统产物
            val urls = createChatFilesByByteArrays(listOf(byteArray), ArtifactOrigin.SYSTEM)
            if (urls.isEmpty()) {
                // 登记失败已回滚删文件；抛出由 recoverMediaPersistenceFailure 降级为持久化失败占位
                error("artifact registration failed for base64 image")
            }
            Log.i(
                TAG,
                "convertBase64ImagePartToLocalFile: convert base64 img to ${urls.joinToString(", ")}"
            )
            val converted = part.copy(url = urls.first().toString())
            // 落盘即盖章稳定 ref：Child 侧没有每轮 backfill，不在这里盖章的话
            // 出站提取每次都会生成新的随机 ref，Master metadata 与 Child 失去关联。
            // ensureAttachmentRef 对已有合法 ref 的 part 是恒等变换。
            AttachmentRefs.ensureAttachmentRef(converted)
        }
    }

    /** 只删除 App filesDir 内由 FilesManager 管理的 file URI。 */
    fun deleteChatFiles(uris: List<Uri>): Boolean {
        val relativePaths = mutableSetOf<String>()
        var allDeleted = true
        uris.filter { it.toString().startsWith("file:") }.forEach { uri ->
            val file = uri.toFile()
            val relativePath = getRelativePathInFilesDir(file) ?: return@forEach
            relativePaths.add(relativePath)
            if (file.exists() && !file.delete()) {
                allDeleted = false
            }
        }
        if (relativePaths.isNotEmpty()) {
            appScope.launch(Dispatchers.IO) {
                relativePaths.forEach { path ->
                    artifactDAO.deleteByPath(path)
                }
            }
        }
        return allDeleted
    }

    suspend fun countChatFiles(): Pair<Int, Long> = withContext(Dispatchers.IO) {
        val dir = context.filesDir.resolve(FileFolders.UPLOAD)
        if (!dir.exists()) {
            return@withContext Pair(0, 0)
        }
        val files = dir.listFiles() ?: return@withContext Pair(0, 0)
        val count = files.size
        val size = files.sumOf { it.length() }
        Pair(count, size)
    }

    suspend fun createChatTextFile(text: String): UIMessagePart.Document = withContext(Dispatchers.IO) {
        val dir = context.filesDir.resolve(FileFolders.UPLOAD)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val fileName = buildUuidFileName(displayName = "pasted_text.txt", mimeType = "text/plain")
        val file = dir.resolve(fileName)
        file.writeText(text)
        // 用户粘贴的文本转文件——用户引入
        if (!registerTrackedFile(FileFolders.UPLOAD, file, "pasted_text.txt", "text/plain", ArtifactOrigin.USER)) {
            error("artifact registration failed for ${file.absolutePath}")
        }
        UIMessagePart.Document(
            url = file.toUri().toString(),
            fileName = "pasted_text.txt",
            mime = "text/plain"
        )
    }

    fun getImagesDir(): File {
        val dir = context.filesDir.resolve("images")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun createImageFileFromBase64(base64Data: String, filePath: String): File {
        val data = if (base64Data.startsWith("data:image")) {
            base64Data.substringAfter("base64,")
        } else {
            base64Data
        }

        val byteArray = Base64.decode(data.toByteArray())
        val file = File(filePath)
        file.parentFile?.mkdirs()
        file.writeBytes(byteArray)
        return file
    }

    fun listImageFiles(): List<File> {
        val imagesDir = getImagesDir()
        return imagesDir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in listOf("png", "jpg", "jpeg", "webp") }
            ?.toList()
            ?: emptyList()
    }

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun saveMessageImage(activityContext: Context, image: String): String = withContext(Dispatchers.IO) {
        val activity = requireNotNull(activityContext.getActivity()) { "Activity not found" }
        val fileName = "MeasixPilot_${System.currentTimeMillis()}.png"
        when {
            image.startsWith("data:image") -> {
                val byteArray = Base64.decode(image.substringAfter("base64,").toByteArray())
                val bitmap = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
                    ?: error("Failed to decode image")
                requireExportSuccess(activityContext.exportImage(activity, bitmap, fileName))
            }

            image.startsWith("file:") -> {
                val file = image.toUri().toFile()
                requireExportSuccess(activityContext.exportImageFile(activity, file, fileName))
            }

            image.startsWith("/") -> {
                requireExportSuccess(activityContext.exportImageFile(activity, File(image), fileName))
            }

            image.startsWith("http") -> {
                val url = URL(image)
                val connection = url.openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 15_000
                    connection.instanceFollowRedirects = true
                    connection.connect()
                    check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                        "HTTP ${connection.responseCode}"
                    }
                    val bitmap = BitmapFactory.decodeStream(connection.inputStream)
                        ?: error("Failed to decode image")
                    requireExportSuccess(activityContext.exportImage(activity, bitmap, fileName))
                } finally {
                    connection.disconnect()
                }
            }

            else -> error("Invalid image format")
        }
        fileName
    }

    private fun requireExportSuccess(result: ImageExportResult) {
        when (result) {
            ImageExportResult.Success -> Unit
            ImageExportResult.PermissionRequired -> error(IMAGE_SAVE_PERMISSION_REQUIRED)
            ImageExportResult.Failed -> error("Failed to save image")
        }
    }

    /**
     * 永久删除单个托管文件（unchecked destructive delete）。
     *
     * 不做任何引用检查：调用方必须自行确认删除语义——
     * - 自动清理路径必须走 reference-aware 检查（见 ArtifactStore.collectUnreferencedArtifacts）
     * - 用户显式删除路径必须先解除可变当前引用（见 ArtifactStore.detachMutableReferences）
     */
    suspend fun deleteManagedFilePermanently(id: Long, deleteFromDisk: Boolean = true): Boolean =
        mutationMutex.withLock {
            withContext(Dispatchers.IO) {
                val entity = artifactDAO.getById(id) ?: return@withContext false
                if (deleteFromDisk) {
                    val file = getFile(entity)
                    if (file.exists() && !file.delete()) {
                        return@withContext false
                    }
                }
                artifactDAO.deleteById(id) > 0
            }
        }

    /**
     * 永久清空整个托管目录（unchecked destructive delete）。
     *
     * 与 [deleteManagedFilePermanently] 相同的约束：调用方负责引用语义。
     */
    suspend fun deleteManagedFolderPermanently(folder: String = FileFolders.UPLOAD): Boolean =
        mutationMutex.withLock {
            withContext(Dispatchers.IO) {
                val dir = File(context.filesDir, folder)
                val entries = dir.listFiles()
                if (dir.exists() && entries == null) {
                    return@withContext false
                }

                var allDeletedFromDisk = true
                entries.orEmpty().forEach { entry ->
                    if (!runCatching { entry.deleteRecursively() }.getOrDefault(false)) {
                        allDeletedFromDisk = false
                    }
                }

                if (allDeletedFromDisk) {
                    artifactDAO.deleteByFolder(folder)
                    return@withContext true
                }

                artifactDAO.listByFolder(folder).first().forEach { entity ->
                    if (!getFile(entity).exists()) {
                        artifactDAO.deleteById(entity.id)
                    }
                }
                false
            }
        }

    private suspend fun commitManagedFile(
        target: File,
        writeAndInsert: suspend () -> ArtifactEntity,
    ): ArtifactEntity {
        return try {
            writeAndInsert()
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                runCatching { if (target.exists()) target.delete() }
            }
            throw error
        }
    }

    private fun createTargetFile(folder: String, displayName: String, mimeType: String?): File {
        val dir = File(context.filesDir, folder)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, FileUtils.buildUuidFileName(displayName = displayName, mimeType = mimeType))
    }

    private fun buildUuidFileName(displayName: String?, mimeType: String?): String =
        FileUtils.buildUuidFileName(displayName, mimeType)

    private suspend fun createArtifactEntity(
        folder: String,
        file: File,
        displayName: String,
        mimeType: String,
        origin: ArtifactOrigin,
    ): ArtifactEntity {
        val now = System.currentTimeMillis()
        val entity = ArtifactEntity(
            folder = folder,
            relativePath = buildRelativePath(folder, file),
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = file.length(),
            createdAt = now,
            updatedAt = now,
            origin = origin.name,
        )
        val id = artifactDAO.insert(entity)
        return entity.copy(id = id)
    }

    /**
     * 同步登记 artifact 元数据（DB 是唯一事实源）。
     *
     * 登记失败时回滚删除磁盘文件并返回 false，保证"文件 + 记录"要么都在、
     * 要么都不在。根因修复：旧 trackManagedFile 为 fire-and-forget 异步登记、
     * 失败仅日志，会遗留"磁盘有文件、DB 无记录"的不一致数据
     * （rescan 补录与 MISSING 状态两个补丁功能的来源，均已移除）。
     */
    private suspend fun registerTrackedFile(
        folder: String,
        file: File,
        displayName: String,
        mimeType: String,
        origin: ArtifactOrigin,
    ): Boolean {
        val relativePath = buildRelativePath(folder, file)
        return try {
            val existing = artifactDAO.getByPath(relativePath)
            if (existing == null) {
                val now = System.currentTimeMillis()
                artifactDAO.insert(
                    ArtifactEntity(
                        folder = folder,
                        relativePath = relativePath,
                        displayName = displayName,
                        mimeType = mimeType,
                        sizeBytes = file.length(),
                        createdAt = now,
                        updatedAt = now,
                        origin = origin.name,
                    )
                )
            }
            true
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                runCatching { if (file.exists()) file.delete() }
            }
            Log.e(TAG, "registerTrackedFile: registration failed, file rolled back: ${file.absolutePath}", error)
            Logging.log(
                TAG,
                "registerTrackedFile: registration failed, file rolled back: ${file.absolutePath}: ${error.message}"
            )
            false
        }
    }

    /**
     * 诞生方式判定：源 URI 指向已登记的 upload 文件（结构性复制场景——会话 fork、
     * 子助手克隆、附件入站）→ 继承源实体 origin（内容来源不变）；否则为用户引入
     * 新内容 → [ArtifactOrigin.USER]。
     */
    private suspend fun inheritOriginOrDefault(uri: Uri): ArtifactOrigin {
        val relativePath = getRelativePathForUri(uri) ?: return ArtifactOrigin.USER
        if (!relativePath.startsWith("${FileFolders.UPLOAD}/")) return ArtifactOrigin.USER
        val source = artifactDAO.getByPath(relativePath) ?: return ArtifactOrigin.USER
        return runCatching { ArtifactOrigin.valueOf(source.origin) }.getOrDefault(ArtifactOrigin.USER)
    }

    private fun buildRelativePath(folder: String, file: File): String =
        FileUtils.buildRelativePath(folder, file)

    private fun getRelativePathInFilesDir(file: File): String? =
        FileUtils.getRelativePathInFilesDir(context.filesDir, file)

    /** file URI 相对 filesDir 的规范化相对路径（如 upload/a.png，正斜杠）；不在 filesDir 内返回 null。 */
    fun getRelativePathForUri(uri: Uri): String? {
        if (!uri.toString().startsWith("file:", ignoreCase = true)) return null
        val file = runCatching { uri.toFile() }.getOrNull() ?: return null
        return getRelativePathInFilesDir(file)
    }

    /** reconcileStartup 专用：扫描 upload 目录，仅日志报告磁盘存在但未登记的 untracked 文件（绝不补录）。 */
    fun logUntrackedUploadFiles() {
        val dir = File(context.filesDir, FileFolders.UPLOAD)
        if (!dir.exists()) return
        dir.listFiles()?.filter { it.isFile }?.forEach { file ->
            val relativePath = "${FileFolders.UPLOAD}/${file.name}"
            // 仅日志；ArtifactStore.reconcileStartup 保证不自动补录（重启复活缺陷回归锁定）
            Log.i(TAG, "reconcileStartup: untracked file on disk (not inserted): $relativePath")
        }
    }

    fun getFileNameFromUri(uri: Uri): String? =
        FileUtils.getFileNameFromUri(context, uri)

    fun getFileMimeType(uri: Uri): String? =
        FileUtils.getFileMimeType(context, uri)

    private fun guessMimeType(file: File, fileName: String): String =
        FileUtils.guessMimeType(file, fileName)
}

/** Converts expected media failures without swallowing cancellation or fatal VM errors. */
internal suspend inline fun <T> recoverMediaPersistenceFailure(
    onFailure: (Exception) -> T,
    block: suspend () -> T,
): T = try {
    block()
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    onFailure(error)
}

object FileFolders {
    const val UPLOAD = "upload"
    const val SKILLS = "skills"
    const val FONTS = "fonts"

    /** 工具超长输出落盘目录——无引用需求、不由 artifact 表管理（非受管落盘层）。 */
    const val TOOL_OUTPUTS = "tool_outputs"
}

suspend fun FilesManager.saveUploadFromUri(
    uri: Uri,
    displayName: String? = null,
    mimeType: String? = null,
    origin: ArtifactOrigin = ArtifactOrigin.USER,
): ArtifactEntity = saveManagedFromUri(
    folder = FileFolders.UPLOAD,
    uri = uri,
    displayName = displayName,
    mimeType = mimeType,
    origin = origin,
)

suspend fun FilesManager.saveUploadFromBytes(
    bytes: ByteArray,
    displayName: String,
    mimeType: String = "application/octet-stream",
    origin: ArtifactOrigin = ArtifactOrigin.USER,
): ArtifactEntity = saveManagedFromBytes(
    folder = FileFolders.UPLOAD,
    bytes = bytes,
    displayName = displayName,
    mimeType = mimeType,
    origin = origin,
)

suspend fun FilesManager.saveUploadText(
    text: String,
    displayName: String = "pasted_text.txt",
    mimeType: String = "text/plain",
    origin: ArtifactOrigin = ArtifactOrigin.USER,
): ArtifactEntity = saveManagedText(
    folder = FileFolders.UPLOAD,
    text = text,
    displayName = displayName,
    mimeType = mimeType,
    origin = origin,
)
