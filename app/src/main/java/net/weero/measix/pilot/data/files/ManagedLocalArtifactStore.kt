package net.weero.measix.pilot.data.files

import android.content.Context
import androidx.core.net.toUri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.weero.measix.pilot.data.db.entity.ArtifactEntity
import net.weero.measix.pilot.data.db.entity.ArtifactOrigin

/**
 * 文件实体的写入门面：把字节/文件复制进 upload 目录、登记 artifact 元数据，
 * 返回跨重启稳定的 [LocalArtifactRef] 句柄。
 *
 * 职责边界：本类只做"写入 + 登记"，不负责生命周期（删除协议、引用投影、GC、
 * 启动收敛由 [ArtifactStore] 协调——构造依赖 FilesManager，反向注入成环，
 * 故两类分离）。
 */
class ManagedLocalArtifactStore(
    private val context: Context,
    private val filesManager: FilesManager,
) {
    suspend fun copyBytes(
        bytes: ByteArray,
        mimeType: String,
        displayName: String,
        folder: String = FileFolders.UPLOAD,
        origin: ArtifactOrigin,
    ): LocalArtifactRef = withContext(Dispatchers.IO) {
        val entity = filesManager.saveManagedFromBytes(
            folder = folder,
            bytes = bytes,
            displayName = displayName,
            mimeType = mimeType,
            origin = origin,
        )
        refFromEntity(entity)
    }

    suspend fun copyFile(
        source: File,
        mimeType: String,
        displayName: String,
        folder: String = FileFolders.UPLOAD,
        origin: ArtifactOrigin,
    ): LocalArtifactRef = withContext(Dispatchers.IO) {
        val entity = filesManager.saveManagedFromUri(
            folder = folder,
            uri = source.toUri(),
            displayName = displayName,
            mimeType = mimeType,
            origin = origin,
        )
        refFromEntity(entity)
    }

    /**
     * 结构性复制（fork/克隆时 tool output 的 copy-on-write）：副本的诞生方式
     * 继承源实体——upload 源查表继承；images/ 源为相册 canonical，视为生成派生。
     */
    suspend fun copyFilePreservingOrigin(
        source: File,
        mimeType: String,
        displayName: String,
        folder: String = FileFolders.UPLOAD,
    ): LocalArtifactRef = withContext(Dispatchers.IO) {
        copyFile(
            source = source,
            mimeType = mimeType,
            displayName = displayName,
            folder = folder,
            origin = inheritedOriginOf(source),
        )
    }

    private suspend fun inheritedOriginOf(source: File): ArtifactOrigin {
        val relative = FileUtils.getRelativePathInFilesDir(context.filesDir, source)
            ?: return ArtifactOrigin.GENERATED
        if (!relative.startsWith("${FileFolders.UPLOAD}/")) return ArtifactOrigin.GENERATED
        val entity = filesManager.getByRelativePath(relative) ?: return ArtifactOrigin.GENERATED
        return runCatching { ArtifactOrigin.valueOf(entity.origin) }.getOrDefault(ArtifactOrigin.GENERATED)
    }

    suspend fun resolveToolPath(path: String): File? = withContext(Dispatchers.IO) {
        val fileName = LocalToolPath.parseUploadToolPath(path) ?: return@withContext null
        val relativePath = "${FileFolders.UPLOAD}/$fileName"
        val entity = filesManager.getByRelativePath(relativePath) ?: return@withContext null
        val file = filesManager.getFile(entity)
        val uploadDir = File(context.filesDir, FileFolders.UPLOAD)
        if (!file.isFile) return@withContext null
        if (!LocalToolPath.isInsideDirectory(file, uploadDir)) return@withContext null
        file
    }

    suspend fun delete(ref: LocalArtifactRef) {
        withContext(Dispatchers.IO) {
            val entity = filesManager.getByRelativePath(ref.relativePath)
            if (entity != null) {
                filesManager.deleteManagedFilePermanently(entity.id, deleteFromDisk = true)
            } else {
                runCatching { ref.file(context.filesDir).delete() }
            }
        }
    }

    fun materialize(ref: LocalArtifactRef): LocalArtifactRef? {
        if (ref.version != LocalArtifactRef.CURRENT_VERSION) return null
        val file = ref.file(context.filesDir)
        if (!file.isFile) return null
        val uploadDir = File(context.filesDir, FileFolders.UPLOAD)
        if (!LocalToolPath.isInsideDirectory(file, uploadDir) &&
            !LocalToolPath.isInsideDirectory(file, File(context.filesDir, "images"))
        ) {
            return null
        }
        return ref
    }

    private fun refFromEntity(entity: ArtifactEntity): LocalArtifactRef {
        val canonical = filesManager.getFile(entity)
        val relative = FileUtils.getRelativePathInFilesDir(context.filesDir, canonical)
            ?: entity.relativePath.replace(File.separatorChar, '/')
        return LocalArtifactRef(
            relativePath = relative,
            mimeType = entity.mimeType,
        )
    }
}
