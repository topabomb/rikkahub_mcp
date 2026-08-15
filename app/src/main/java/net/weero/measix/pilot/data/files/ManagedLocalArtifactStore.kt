package net.weero.measix.pilot.data.files

import android.content.Context
import androidx.core.net.toUri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.weero.measix.pilot.data.db.entity.ManagedFileEntity

class ManagedLocalArtifactStore(
    private val context: Context,
    private val filesManager: FilesManager,
) {
    suspend fun copyBytes(
        bytes: ByteArray,
        mimeType: String,
        displayName: String,
        folder: String = FileFolders.UPLOAD,
    ): LocalArtifactRef = withContext(Dispatchers.IO) {
        val entity = filesManager.saveManagedFromBytes(
            folder = folder,
            bytes = bytes,
            displayName = displayName,
            mimeType = mimeType,
        )
        refFromEntity(entity)
    }

    suspend fun copyFile(
        source: File,
        mimeType: String,
        displayName: String,
        folder: String = FileFolders.UPLOAD,
    ): LocalArtifactRef = withContext(Dispatchers.IO) {
        val entity = filesManager.saveManagedFromUri(
            folder = folder,
            uri = source.toUri(),
            displayName = displayName,
            mimeType = mimeType,
        )
        refFromEntity(entity)
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
                filesManager.delete(entity.id, deleteFromDisk = true)
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

    private fun refFromEntity(entity: ManagedFileEntity): LocalArtifactRef {
        val canonical = filesManager.getFile(entity)
        val relative = FileUtils.getRelativePathInFilesDir(context.filesDir, canonical)
            ?: entity.relativePath.replace(File.separatorChar, '/')
        return LocalArtifactRef(
            relativePath = relative,
            mimeType = entity.mimeType,
        )
    }
}
