package net.weero.measix.pilot.data.files

import java.io.File

object LocalToolPath {
    private val SAFE_FILE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")

    fun parseUploadToolPath(path: String): String? {
        val trimmed = path.trim()
        if (!trimmed.startsWith("/${FileFolders.UPLOAD}/")) return null
        if (trimmed.contains('\\') || trimmed.contains('\u0000')) return null
        if ('%' in trimmed) return null
        val rest = trimmed.removePrefix("/${FileFolders.UPLOAD}/")
        if (rest.isEmpty() || '/' in rest || rest == "." || rest == ".." || rest.contains("..")) {
            return null
        }
        if (!SAFE_FILE_NAME.matches(rest)) return null
        return rest
    }

    fun isInsideDirectory(file: File, directory: File): Boolean {
        val canonicalFile = runCatching { file.canonicalFile }.getOrNull() ?: return false
        val canonicalDir = runCatching { directory.canonicalFile }.getOrNull() ?: return false
        return canonicalFile.path.startsWith(canonicalDir.path + File.separator)
    }
}
