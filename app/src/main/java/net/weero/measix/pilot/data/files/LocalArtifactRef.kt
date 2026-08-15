package net.weero.measix.pilot.data.files

import kotlinx.serialization.Serializable
import java.io.File

/**
 * Internal authority for a managed local artifact.
 *
 * [relativePath] is the only persisted identity. [file], [fileUri] and [toolPath] are derived
 * views for different boundaries and must not all be exposed to the model.
 */
@Serializable
data class LocalArtifactRef(
    val version: Int = CURRENT_VERSION,
    val relativePath: String,
    val mimeType: String,
) {
    fun file(filesDir: File): File = File(filesDir, relativePath.replace('/', File.separatorChar))

    fun fileUri(filesDir: File): String {
        val path = file(filesDir).absolutePath.replace('\\', '/')
        return if (path.startsWith("/")) "file://$path" else "file:///$path"
    }

    fun toolPath(): String? {
        val name = relativePath.substringAfterLast('/')
        if (relativePath.substringBefore('/') != FileFolders.UPLOAD || name.isBlank()) return null
        return "/${FileFolders.UPLOAD}/$name"
    }

    companion object {
        const val CURRENT_VERSION = 1
    }
}
