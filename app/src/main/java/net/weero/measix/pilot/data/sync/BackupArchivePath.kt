package net.weero.measix.pilot.data.sync

import java.io.File

/** 解析备份 entry 的相对路径，并拒绝绝对路径与逃逸目标目录的路径。 */
internal fun resolveBackupEntry(root: File, relativePath: String): File? {
    if (relativePath.isBlank()) return null
    val canonicalRoot = root.canonicalFile
    val target = File(canonicalRoot, relativePath).canonicalFile
    val rootPrefix = canonicalRoot.path + File.separator
    return target.takeIf { it.path.startsWith(rootPrefix) }
}
