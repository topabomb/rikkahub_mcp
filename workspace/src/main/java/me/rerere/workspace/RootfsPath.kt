package me.rerere.workspace

/** Canonical guest spelling shared by approval and the Rootfs file owner. No filesystem IO. */
@ConsistentCopyVisibility
data class RootfsPath private constructor(val value: String) {
    val requiresWriteApproval: Boolean
        get() = listOf("/workspace", "/tmp").none { value == it || value.startsWith("$it/") }

    companion object {
        fun parse(raw: String): RootfsPath {
            val path = raw.trim()
            require(path.startsWith('/')) { "path must be an absolute path inside Rootfs" }
            require('\u0000' !in path && '\\' !in path) { "path contains an invalid character" }
            val segments = ArrayList<String>()
            path.split('/').forEach { segment ->
                when (segment) {
                    "", "." -> Unit
                    ".." -> {
                        require(segments.isNotEmpty()) { "path escapes Rootfs" }
                        segments.removeAt(segments.lastIndex)
                    }
                    else -> segments += segment
                }
            }
            return RootfsPath("/" + segments.joinToString("/"))
        }
    }
}
