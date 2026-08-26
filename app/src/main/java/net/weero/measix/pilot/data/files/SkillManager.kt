package net.weero.measix.pilot.data.files

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.weero.measix.pilot.data.datastore.SettingsStore

class SkillManager(
    private val context: Context,
    private val settingsStore: SettingsStore,
) {
    companion object {
        private const val TAG = "SkillManager"
        private val SKILL_TEMP_PATTERN = Regex("^\\.[0-9a-f]+\\.(?:staging|backup)\\.\\d+\\.tmp$")
        private val BUNDLE_STAGING_PATTERN = Regex("^\\.${FileFolders.SKILLS}\\.bundle\\.staging\\.\\d+\\.tmp$")
        private val BUNDLE_BACKUP_PATTERN = Regex("^\\.${FileFolders.SKILLS}\\.bundle\\.backup\\.\\d+\\.tmp$")
        private const val MAX_SKILL_TEXT_BYTES = 4 * 1024 * 1024
        private const val MAX_SKILL_FILE_BYTES = 4 * 1024 * 1024
        private const val MAX_SKILL_TREE_BYTES = 32L * 1024L * 1024L
        private const val MAX_SKILL_FILES = 512
    }

    private fun getSkillsDir(): File {
        val dir = context.filesDir.resolve(FileFolders.SKILLS)
        val rootIsSafe = recoverInterruptedBundlePublish(dir)
        if (!dir.exists() && rootIsSafe && !dir.mkdirs()) {
            Log.e(TAG, "getSkillsDir: Failed to create skills directory")
        }
        return dir
    }

    fun listSkills(): List<SkillMetadata> = BackupSnapshotBarrier.withBlockingLock {
        listSkillsUnlocked()
    }

    private fun listSkillsUnlocked(): List<SkillMetadata> {
        val skillsDir = getSkillsDir()
        recoverInterruptedSkillPublishes(skillsDir)
        return skillsDir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.mapNotNull { dir ->
                val skillFile = dir.resolve("SKILL.md")
                if (!skillFile.exists()) return@mapNotNull null
                parseSkillFile(skillFile)
            }
            ?: emptyList()
    }

    /**
     * 清理所有助手 enabledSkills 中已不存在于磁盘的技能名。
     *
     * 当用户在 App 外直接删除 /skills/ 目录下的技能时，不会走 [deleteSkill] 的清理逻辑，
     * 导致 enabledSkills 残留"幽灵"技能名，使扩展入口角标计数偏大。
     *
     * @return 现存技能列表，供调用方复用避免重复读盘
     */
    suspend fun pruneOrphanedEnabledSkills(): List<SkillMetadata> = withContext(Dispatchers.IO) {
        val skills = BackupSnapshotBarrier.withLock { listSkillsUnlocked() }
        val existing = skills.mapTo(HashSet()) { it.name }
        settingsStore.updateLocal { settings ->
            var changed = false
            val newAssistants = settings.assistants.map { assistant ->
                val pruned = assistant.enabledSkills.filterTo(LinkedHashSet()) { it in existing }
                if (pruned.size != assistant.enabledSkills.size) {
                    changed = true
                    assistant.copy(enabledSkills = pruned)
                } else {
                    assistant
                }
            }
            if (changed) settings.copy(assistants = newAssistants) else settings
        }
        skills
    }

    fun saveSkill(name: String, content: String): SkillMetadata? {
        return BackupSnapshotBarrier.withBlockingLock { saveSkillUnlocked(name, content) }
    }

    suspend fun importSkill(name: String, content: String): SkillMetadata? = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()[Job]
        BackupSnapshotBarrier.withLock {
            saveSkillUnlocked(name, content) { job?.ensureActive() }
        }
    }

    private fun saveSkillUnlocked(
        name: String,
        content: String,
        beforePublish: () -> Unit = {},
    ): SkillMetadata? {
        val existing = findPublishedSkillDir(name)
        val saved = if (existing == null) {
            saveSkillFileBytesAtomicallyUnlocked(
                name,
                mapOf("SKILL.md" to content.toByteArray()),
                beforePublish,
            )
        } else {
            saveSkillFileUnlocked(name, "SKILL.md", content, beforePublish) == SkillFileSaveResult.SUCCESS
        }
        if (!saved) return null
        val skillDir = findPublishedSkillDir(name) ?: return null
        return parseSkillFile(skillDir.resolve("SKILL.md"))
    }

    suspend fun deleteSkill(name: String): Boolean = withContext(Dispatchers.IO) {
        BackupSnapshotBarrier.withLock lock@{
        val skillsDir = getSkillsDir()
        val skillDir = findPublishedSkillDir(name) ?: return@lock false
        val backupDir = createTempSkillPath(skillsDir, name, "backup") ?: return@lock false
        if (!skillDir.renameTo(backupDir)) return@lock false
        try {
            settingsStore.updateLocal { settings ->
                settings.copy(
                    assistants = settings.assistants.map { assistant ->
                        if (assistant.enabledSkills.contains(name)) {
                            assistant.copy(enabledSkills = assistant.enabledSkills - name)
                        } else {
                            assistant
                        }
                    }
                )
            }
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                if (!backupDir.renameTo(skillDir)) {
                    error.addSuppressed(IllegalStateException("Unable to restore Skill after Settings rejection: $name"))
                }
            }
            throw error
        }
        withContext(NonCancellable) {
            backupDir.deleteRecursively()
        }
        }
    }

    fun listSkillFiles(skillName: String): List<SkillFileNode> = BackupSnapshotBarrier.withBlockingLock {
        val skillDir = findPublishedSkillDir(skillName) ?: return@withBlockingLock emptyList()
        buildSkillTree(skillDir, skillDir)
    }

    fun readSkillContent(skillName: String, relativePath: String?): SkillContentReadResult =
        BackupSnapshotBarrier.withBlockingLock {
            val skillDir = findPublishedSkillDir(skillName)
                ?: return@withBlockingLock SkillContentReadResult.NotFound
            val target = SkillPaths.resolveSkillFile(skillDir, relativePath ?: "SKILL.md")
                ?: return@withBlockingLock SkillContentReadResult.InvalidPath
            if (!target.isFile) return@withBlockingLock SkillContentReadResult.NotFound
            val content = when (val read = target.readUtf8Limited()) {
                is SkillTextRead.Success -> read.content
                SkillTextRead.InvalidEncoding -> return@withBlockingLock SkillContentReadResult.InvalidEncoding
                SkillTextRead.ResourceLimit -> return@withBlockingLock SkillContentReadResult.ResourceLimit
                SkillTextRead.ReadFailure -> return@withBlockingLock SkillContentReadResult.ReadFailure
            }
            if (relativePath.isNullOrBlank()) {
                when (val parsed = SkillFrontmatterParser.parseDocument(content)) {
                    is SkillParseResult.Success -> {
                        if (parsed.document.frontmatter.name != skillName) {
                            SkillContentReadResult.InvalidSkill
                        } else {
                            SkillContentReadResult.Success(parsed.document.body)
                        }
                    }
                    is SkillParseResult.NoFrontmatter,
                    is SkillParseResult.Error,
                    -> SkillContentReadResult.InvalidSkill
                }
            } else {
                SkillContentReadResult.Success(content)
            }
        }

    fun saveSkillFile(skillName: String, relativePath: String, content: String): SkillFileSaveResult {
        return BackupSnapshotBarrier.withBlockingLock {
            saveSkillFileUnlocked(skillName, relativePath, content)
        }
    }

    private fun saveSkillFileUnlocked(
        skillName: String,
        relativePath: String,
        content: String,
        beforePublish: () -> Unit = {},
    ): SkillFileSaveResult {
        if (content.toByteArray().size > MAX_SKILL_TEXT_BYTES) return SkillFileSaveResult.INVALID_SKILL
        val targetDir = findPublishedSkillDir(skillName) ?: return SkillFileSaveResult.NOT_FOUND
        val skillsDir = getSkillsDir()
        val stagingDir = createTempSkillDir(skillsDir, skillName, "staging")
            ?: return SkillFileSaveResult.IO_FAILURE
        try {
            if (!copySkillTree(targetDir, stagingDir)) return SkillFileSaveResult.IO_FAILURE
            val target = SkillPaths.resolveSkillFile(stagingDir, relativePath)
                ?: return SkillFileSaveResult.INVALID_PATH
            val parent = target.parentFile
            if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                return SkillFileSaveResult.IO_FAILURE
            }
            target.writeText(content)
            val validation = validateStagedSkill(stagingDir, skillName)
            if (validation != SkillFileSaveResult.SUCCESS) return validation
            beforePublish()
            return if (publishStagedSkill(targetDir, stagingDir, skillsDir, skillName)) {
                SkillFileSaveResult.SUCCESS
            } else {
                SkillFileSaveResult.IO_FAILURE
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.w(TAG, "saveSkillFile: Failed to save $skillName/$relativePath", e)
            return SkillFileSaveResult.IO_FAILURE
        } finally {
            if (stagingDir.exists()) stagingDir.deleteRecursively()
        }
    }

    fun saveSkillFileBytesAtomically(skillName: String, files: Map<String, ByteArray>): Boolean =
        BackupSnapshotBarrier.withBlockingLock { saveSkillFileBytesAtomicallyUnlocked(skillName, files) }

    suspend fun importSkillFileBytesAtomically(skillName: String, files: Map<String, ByteArray>): Boolean =
        withContext(Dispatchers.IO) {
            val job = currentCoroutineContext()[Job]
            BackupSnapshotBarrier.withLock {
                saveSkillFileBytesAtomicallyUnlocked(skillName, files) { job?.ensureActive() }
            }
        }

    suspend fun importSkillBundleAtomically(
        entries: List<SkillImportBundleEntry>,
    ): SkillBundleImportResult = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()[Job]
        BackupSnapshotBarrier.withLock {
            importSkillBundleUnlocked(entries) { job?.ensureActive() }
        }
    }

    private fun importSkillBundleUnlocked(
        entries: List<SkillImportBundleEntry>,
        beforePublish: () -> Unit,
    ): SkillBundleImportResult {
        if (entries.isEmpty()) return SkillBundleImportResult.INVALID_BUNDLE
        if (entries.map { it.name }.toSet().size != entries.size) {
            return SkillBundleImportResult.DUPLICATE_NAME
        }
        val bundleFiles = entries.asSequence().flatMap { it.files.values.asSequence() }.asIterable()
        if (!isWithinFilePayloadLimits(bundleFiles)) return SkillBundleImportResult.INVALID_BUNDLE
        val skillsDir = getSkillsDir()
        if (!skillsDir.isDirectory) return SkillBundleImportResult.IO_FAILURE
        recoverInterruptedSkillPublishes(skillsDir)
        val skillsParent = skillsDir.parentFile ?: return SkillBundleImportResult.IO_FAILURE
        val stagingRoot = createBundleTempDir(skillsParent, "staging")
            ?: return SkillBundleImportResult.IO_FAILURE
        try {
            if (!copySkillTree(skillsDir, stagingRoot)) return SkillBundleImportResult.IO_FAILURE
            for (entry in entries) {
                val existing = findPublishedSkillDirIn(stagingRoot, entry.name)
                val target = existing ?: SkillPaths.resolveSkillDir(stagingRoot, entry.name)
                    ?: return SkillBundleImportResult.INVALID_BUNDLE
                if (existing != null && !existing.deleteRecursively()) {
                    return SkillBundleImportResult.IO_FAILURE
                }
                if (target.exists() || !target.mkdirs()) return SkillBundleImportResult.IO_FAILURE
                for ((relativePath, content) in entry.files) {
                    val file = SkillPaths.resolveSkillFile(target, relativePath)
                        ?: return SkillBundleImportResult.INVALID_BUNDLE
                    val parent = file.parentFile
                    if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                        return SkillBundleImportResult.IO_FAILURE
                    }
                    file.writeBytes(content)
                }
                if (validateStagedSkill(target, entry.name) != SkillFileSaveResult.SUCCESS) {
                    return SkillBundleImportResult.INVALID_BUNDLE
                }
            }
            beforePublish()
            return if (publishStagedSkillsRoot(skillsDir, stagingRoot)) {
                SkillBundleImportResult.SUCCESS
            } else {
                SkillBundleImportResult.IO_FAILURE
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "importSkillBundle: Failed to publish bundle", error)
            return SkillBundleImportResult.IO_FAILURE
        } finally {
            if (stagingRoot.exists()) stagingRoot.deleteRecursively()
        }
    }

    private fun saveSkillFileBytesAtomicallyUnlocked(
        skillName: String,
        files: Map<String, ByteArray>,
        beforePublish: () -> Unit = {},
    ): Boolean {
        if (!isWithinFilePayloadLimits(files.values)) return false
        val skillsDir = getSkillsDir()
        val targetDir = findPublishedSkillDir(skillName)
            ?: resolveSkillTargetDir(skillName)
            ?: return false
        val stagingDir = createTempSkillDir(skillsDir, skillName, "staging") ?: return false
        try {
            for ((relativePath, content) in files) {
                val target = SkillPaths.resolveSkillFile(stagingDir, relativePath) ?: return false
                val parent = target.parentFile
                if (parent != null && !parent.isDirectory && !parent.mkdirs()) return false
                target.writeBytes(content)
            }

            if (validateStagedSkill(stagingDir, skillName) != SkillFileSaveResult.SUCCESS) return false
            beforePublish()
            return publishStagedSkill(targetDir, stagingDir, skillsDir, skillName)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.w(TAG, "saveSkillFileBytesAtomically: Failed to save $skillName", e)
            return false
        } finally {
            if (stagingDir.exists()) {
                stagingDir.deleteRecursively()
            }
        }
    }

    fun deleteSkillFile(skillName: String, relativePath: String): SkillFileDeleteResult {
        return BackupSnapshotBarrier.withBlockingLock lock@{
        if (relativePath.replace('\\', '/').equals("SKILL.md", ignoreCase = true)) {
            return@lock SkillFileDeleteResult.PROTECTED_SKILL_FILE
        }
        val targetDir = findPublishedSkillDir(skillName) ?: return@lock SkillFileDeleteResult.NOT_FOUND
        val skillsDir = getSkillsDir()
        val stagingDir = createTempSkillDir(skillsDir, skillName, "staging")
            ?: return@lock SkillFileDeleteResult.IO_FAILURE
        try {
            if (!copySkillTree(targetDir, stagingDir)) return@lock SkillFileDeleteResult.IO_FAILURE
            val target = SkillPaths.resolveSkillFile(stagingDir, relativePath)
                ?: return@lock SkillFileDeleteResult.INVALID_PATH
            if (!target.isFile) return@lock SkillFileDeleteResult.NOT_FOUND
            if (!target.delete()) return@lock SkillFileDeleteResult.IO_FAILURE
            if (validateStagedSkill(stagingDir, skillName) != SkillFileSaveResult.SUCCESS) {
                return@lock SkillFileDeleteResult.INVALID_SKILL
            }
            if (publishStagedSkill(targetDir, stagingDir, skillsDir, skillName)) {
                SkillFileDeleteResult.SUCCESS
            } else {
                SkillFileDeleteResult.IO_FAILURE
            }
        } catch (error: Exception) {
            Log.w(TAG, "deleteSkillFile: Failed to delete $skillName/$relativePath", error)
            SkillFileDeleteResult.IO_FAILURE
        } finally {
            if (stagingDir.exists()) stagingDir.deleteRecursively()
        }
        }
    }

    private fun resolveSkillTargetDir(skillName: String): File? {
        return SkillPaths.resolveSkillDir(getSkillsDir(), skillName)
    }

    private fun findPublishedSkillDir(skillName: String): File? {
        val skillsDir = getSkillsDir()
        recoverInterruptedSkillPublishes(skillsDir)
        return findPublishedSkillDirIn(skillsDir, skillName)
    }

    private fun findPublishedSkillDirIn(skillsDir: File, skillName: String): File? {
        val direct = SkillPaths.resolveSkillDir(skillsDir, skillName)
        if (direct?.isDirectory == true && parseSkillFile(direct.resolve("SKILL.md"))?.name == skillName) {
            return direct
        }
        return skillsDir.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.firstOrNull { directory -> parseSkillFile(directory.resolve("SKILL.md"))?.name == skillName }
    }

    private fun recoverInterruptedSkillPublishes(skillsDir: File) {
        if (!skillsDir.isDirectory) return
        val temporaryDirs = skillsDir.listFiles()
            ?.filter { it.isDirectory && SKILL_TEMP_PATTERN.matches(it.name) }
            .orEmpty()
        temporaryDirs.filter { ".staging." in it.name }.forEach { staging ->
            if (!staging.deleteRecursively()) Log.w(TAG, "Failed to remove stale Skill staging directory")
        }
        val backupsByName = temporaryDirs
            .filter { it.exists() && ".backup." in it.name }
            .mapNotNull { backup -> parseSkillFile(backup.resolve("SKILL.md"))?.name?.let { it to backup } }
            .groupBy({ it.first }, { it.second })
        for ((skillName, backups) in backupsByName) {
            val published = findPublishedSkillDirIn(skillsDir, skillName)
            if (published != null) {
                backups.forEach { backup ->
                    if (!backup.deleteRecursively()) Log.w(TAG, "Failed to remove stale Skill backup")
                }
                continue
            }
            val target = SkillPaths.resolveSkillDir(skillsDir, skillName)
            if (backups.size == 1 && target != null && !target.exists() && backups.single().renameTo(target)) {
                Log.i(TAG, "Recovered interrupted Skill publish for $skillName")
            } else {
                Log.e(TAG, "Ambiguous or invalid interrupted Skill publish for $skillName; keeping it hidden")
            }
        }
    }

    private fun recoverInterruptedBundlePublish(skillsDir: File): Boolean {
        val parent = skillsDir.parentFile ?: return false
        val siblings = parent.listFiles().orEmpty().filter { it.isDirectory }
        val stagingDirs = siblings.filter { BUNDLE_STAGING_PATTERN.matches(it.name) }
        val backupDirs = siblings.filter { BUNDLE_BACKUP_PATTERN.matches(it.name) }
        if (skillsDir.isDirectory) {
            (stagingDirs + backupDirs).forEach { stale ->
                if (!stale.deleteRecursively()) Log.w(TAG, "Failed to remove stale Skill bundle directory")
            }
            return true
        }
        if (backupDirs.size == 1) {
            if (!backupDirs.single().renameTo(skillsDir)) {
                Log.e(TAG, "Failed to recover interrupted Skill bundle publish")
                return false
            }
            stagingDirs.forEach { it.deleteRecursively() }
            Log.i(TAG, "Recovered interrupted Skill bundle publish")
            return true
        }
        if (backupDirs.size > 1) {
            Log.e(TAG, "Ambiguous interrupted Skill bundle publish; refusing to create an empty root")
            return false
        }
        stagingDirs.forEach { it.deleteRecursively() }
        return true
    }

    private fun createBundleTempDir(parent: File, kind: String): File? {
        repeat(100) { attempt ->
            val candidate = parent.resolve(".${FileFolders.SKILLS}.bundle.$kind.$attempt.tmp")
            if (!candidate.exists() && candidate.mkdirs()) return candidate
        }
        return null
    }

    private fun createTempSkillDir(skillsRoot: File, skillName: String, suffix: String): File? {
        val identity = skillName.hashCode().toUInt().toString(16)
        repeat(100) { attempt ->
            val candidate = skillsRoot.resolve(".$identity.$suffix.$attempt.tmp")
            if (!candidate.exists() && candidate.mkdirs()) {
                return candidate
            }
        }
        return null
    }

    private fun createTempSkillPath(skillsRoot: File, skillName: String, suffix: String): File? {
        val identity = skillName.hashCode().toUInt().toString(16)
        repeat(100) { attempt ->
            val candidate = skillsRoot.resolve(".$identity.$suffix.$attempt.tmp")
            if (!candidate.exists()) return candidate
        }
        return null
    }

    private fun buildSkillTree(root: File, directory: File): List<SkillFileNode> {
        val items = directory.listFiles()?.toList().orEmpty()
        val files = items
            .filter(File::isFile)
            .sortedWith(compareBy({ it.name != "SKILL.md" }, File::getName))
            .map { file ->
                SkillFileNode.FileNode(
                    SkillFile(
                        name = file.name,
                        relativePath = file.relativeTo(root).path.replace(File.separatorChar, '/'),
                        sizeBytes = file.length(),
                    )
                )
            }
        val directories = items
            .filter(File::isDirectory)
            .sortedBy(File::getName)
            .map { child ->
                SkillFileNode.DirNode(
                    name = child.name,
                    relativePath = child.relativeTo(root).path.replace(File.separatorChar, '/'),
                    children = buildSkillTree(root, child),
                )
            }
        return directories + files
    }

    private fun copySkillTree(source: File, destination: File): Boolean = runCatching {
        source.walkTopDown().forEach { current ->
            if (Files.isSymbolicLink(current.toPath())) return@runCatching false
            if (current == source) return@forEach
            val relativePath = current.relativeTo(source).path.replace(File.separatorChar, '/')
            val target = SkillPaths.resolveSkillFile(destination, relativePath) ?: return@runCatching false
            if (current.isDirectory) {
                if (!target.isDirectory && !target.mkdirs()) return@runCatching false
            } else if (current.isFile) {
                val parent = target.parentFile
                if (parent != null && !parent.isDirectory && !parent.mkdirs()) return@runCatching false
                current.copyTo(target, overwrite = false)
            }
        }
        true
    }.getOrElse {
        Log.w(TAG, "copySkillTree: Failed to stage ${source.name}", it)
        false
    }

    private fun validateStagedSkill(stagingDir: File, skillName: String): SkillFileSaveResult {
        val skillFile = stagingDir.resolve("SKILL.md")
        val content = (skillFile.readUtf8Limited() as? SkillTextRead.Success)?.content
            ?: return SkillFileSaveResult.INVALID_SKILL
        return when (val parsed = SkillFrontmatterParser.parseDocument(content)) {
            is SkillParseResult.Success -> {
                if (parsed.document.frontmatter.name == skillName) {
                    SkillFileSaveResult.SUCCESS
                } else {
                    SkillFileSaveResult.NAME_MISMATCH
                }
            }
            is SkillParseResult.NoFrontmatter,
            is SkillParseResult.Error,
            -> SkillFileSaveResult.INVALID_SKILL
        }
    }

    private fun publishStagedSkill(
        targetDir: File,
        stagingDir: File,
        skillsDir: File,
        skillName: String,
    ): Boolean {
        var backupDir: File? = null
        if (targetDir.exists()) {
            backupDir = createTempSkillPath(skillsDir, skillName, "backup") ?: return false
            if (!targetDir.renameTo(backupDir)) return false
        }
        if (!stagingDir.renameTo(targetDir)) {
            if (backupDir != null && !targetDir.exists() && !backupDir.renameTo(targetDir)) {
                Log.e(TAG, "publishStagedSkill: Failed to restore $skillName after publish failure")
            }
            return false
        }
        if (backupDir?.deleteRecursively() == false) {
            Log.w(TAG, "publishStagedSkill: Published $skillName but failed to remove its backup")
        }
        return true
    }

    private fun publishStagedSkillsRoot(skillsDir: File, stagingRoot: File): Boolean {
        val parent = skillsDir.parentFile ?: return false
        val backupRoot = parent.resolve(".${FileFolders.SKILLS}.bundle.backup.0.tmp")
        if (backupRoot.exists() || !skillsDir.renameTo(backupRoot)) return false
        if (!stagingRoot.renameTo(skillsDir)) {
            if (!skillsDir.exists() && !backupRoot.renameTo(skillsDir)) {
                Log.e(TAG, "Failed to restore Skill root after bundle publish failure")
            }
            return false
        }
        if (!backupRoot.deleteRecursively()) {
            Log.w(TAG, "Published Skill bundle but failed to remove its backup")
        }
        return true
    }

    private fun parseSkillFile(skillFile: File): SkillMetadata? {
        return try {
            val content = when (val read = skillFile.readUtf8Limited()) {
                is SkillTextRead.Success -> read.content
                SkillTextRead.InvalidEncoding -> {
                    Log.w(TAG, "parseSkillFile: SKILL.md is not valid UTF-8")
                    return null
                }
                SkillTextRead.ResourceLimit -> {
                    Log.w(TAG, "parseSkillFile: SKILL.md exceeds the byte limit")
                    return null
                }
                SkillTextRead.ReadFailure -> return null
            }
            when (val result = SkillFrontmatterParser.parseDocument(content)) {
                is SkillParseResult.Success -> SkillMetadata(
                    name = result.document.frontmatter.name,
                    description = result.document.frontmatter.description,
                    compatibility = result.document.frontmatter.compatibility,
                )
                is SkillParseResult.NoFrontmatter -> {
                    Log.w(TAG, "parseSkillFile: No frontmatter in ${skillFile.absolutePath}")
                    null
                }
                is SkillParseResult.Error -> {
                    Log.w(TAG, "parseSkillFile: Failed to parse ${skillFile.absolutePath}: ${result.message}")
                    null
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "parseSkillFile: Failed to read ${skillFile.absolutePath}", error)
            null
        }
    }

    private fun File.readUtf8Limited(): SkillTextRead {
        if (!isFile) return SkillTextRead.ReadFailure
        if (length() > MAX_SKILL_TEXT_BYTES) return SkillTextRead.ResourceLimit
        return try {
            val bytes = inputStream().use { input ->
                val output = ByteArrayOutputStream(minOf(length().toInt(), DEFAULT_BUFFER_SIZE))
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_SKILL_TEXT_BYTES) return SkillTextRead.ResourceLimit
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            SkillTextRead.Success(decoder.decode(ByteBuffer.wrap(bytes)).toString())
        } catch (_: CharacterCodingException) {
            SkillTextRead.InvalidEncoding
        } catch (error: Exception) {
            Log.w(TAG, "Failed to read bounded Skill text", error)
            SkillTextRead.ReadFailure
        }
    }

    private fun isWithinFilePayloadLimits(files: Iterable<ByteArray>): Boolean {
        var count = 0
        var total = 0L
        for (content in files) {
            count += 1
            if (count > MAX_SKILL_FILES) return false
            if (content.size > MAX_SKILL_FILE_BYTES) return false
            total += content.size
            if (total > MAX_SKILL_TREE_BYTES) return false
        }
        return true
    }

}

private sealed interface SkillTextRead {
    data class Success(val content: String) : SkillTextRead
    data object InvalidEncoding : SkillTextRead
    data object ResourceLimit : SkillTextRead
    data object ReadFailure : SkillTextRead
}

data class SkillMetadata(
    val name: String,
    val description: String,
    val compatibility: String? = null,
)

data class SkillFile(
    val name: String,
    val relativePath: String,
    val sizeBytes: Long,
)

data class SkillImportBundleEntry(
    val name: String,
    val files: Map<String, ByteArray>,
)

enum class SkillBundleImportResult {
    SUCCESS,
    DUPLICATE_NAME,
    INVALID_BUNDLE,
    IO_FAILURE,
}

sealed class SkillFileNode {
    data class FileNode(val skillFile: SkillFile) : SkillFileNode()
    data class DirNode(
        val name: String,
        val relativePath: String,
        val children: List<SkillFileNode>,
    ) : SkillFileNode()
}

enum class SkillFileSaveResult {
    SUCCESS,
    NOT_FOUND,
    INVALID_PATH,
    INVALID_SKILL,
    NAME_MISMATCH,
    IO_FAILURE,
}

enum class SkillFileDeleteResult {
    SUCCESS,
    NOT_FOUND,
    INVALID_PATH,
    PROTECTED_SKILL_FILE,
    INVALID_SKILL,
    IO_FAILURE,
}

sealed interface SkillContentReadResult {
    data class Success(val content: String) : SkillContentReadResult
    data object NotFound : SkillContentReadResult
    data object InvalidPath : SkillContentReadResult
    data object InvalidSkill : SkillContentReadResult
    data object InvalidEncoding : SkillContentReadResult
    data object ResourceLimit : SkillContentReadResult
    data object ReadFailure : SkillContentReadResult
}
