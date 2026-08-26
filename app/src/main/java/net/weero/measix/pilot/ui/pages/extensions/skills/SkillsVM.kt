package net.weero.measix.pilot.ui.pages.extensions.skills

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.LinkedHashMap
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import net.weero.measix.pilot.data.files.FileUtils
import net.weero.measix.pilot.data.files.SkillFrontmatterParser
import net.weero.measix.pilot.data.files.SkillDocument
import net.weero.measix.pilot.data.files.SkillBundleImportResult
import net.weero.measix.pilot.data.files.SkillImportBundleEntry
import net.weero.measix.pilot.data.files.SkillManager
import net.weero.measix.pilot.data.files.SkillMetadata
import net.weero.measix.pilot.data.files.SkillParseResult
import org.json.JSONArray
import kotlin.collections.iterator

class SkillsVM internal constructor(
    private val skillManager: SkillManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val gitHubSource: SkillGitHubSource = NetworkSkillGitHubSource,
) : ViewModel() {
    private val _skills = MutableStateFlow<List<SkillMetadata>>(emptyList())
    val skills = _skills.asStateFlow()

    init {
        loadSkills()
    }

    private fun loadSkills() {
        viewModelScope.launch(ioDispatcher) {
            _skills.value = skillManager.listSkills()
        }
    }

    fun saveSkill(name: String, content: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(ioDispatcher) {
            val result = skillManager.saveSkill(name, content)
            _skills.value = skillManager.listSkills()
            withContext(Dispatchers.Main) {
                onResult(result != null)
            }
        }
    }

    fun deleteSkill(name: String) {
        viewModelScope.launch(ioDispatcher) {
            skillManager.deleteSkill(name)
            _skills.value = skillManager.listSkills()
        }
    }

    fun importSkillFromFile(context: Context, uri: Uri, onResult: (SkillImportOutcome) -> Unit) =
        viewModelScope.launch(ioDispatcher) {
            val appContext = context.applicationContext
            try {
                val fileName = FileUtils.getFileNameFromUri(appContext, uri).orEmpty()
                val bytes = runInterruptible {
                    appContext.contentResolver.openInputStream(uri)?.use {
                        it.readBytesLimited(SkillImportLimits.MAX_SOURCE_BYTES)
                    }
                } ?: throw SkillImportException(SkillImportFailure.READ_SOURCE)
                currentCoroutineContext().ensureActive()

                val importedNames = if (isZipFile(fileName, bytes)) {
                    importSkillsFromZip(bytes)
                } else {
                    importSkillMarkdown(bytes)
                }

                currentCoroutineContext().ensureActive()
                _skills.value = skillManager.listSkills()
                currentCoroutineContext().ensureActive()
                withContext(Dispatchers.Main) {
                    onResult(SkillImportOutcome.Success(importedNames.joinToString()))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: SkillImportException) {
                withContext(Dispatchers.Main) { onResult(SkillImportOutcome.Failure(error.reason)) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult(SkillImportOutcome.Failure(SkillImportFailure.UNKNOWN)) }
            }
    }

    fun importSkillFromGitHub(repoUrl: String, onResult: (SkillImportOutcome) -> Unit) =
        viewModelScope.launch(ioDispatcher) {
            try {
                val info = parseGitHubUrl(repoUrl)
                    ?: throw SkillImportException(SkillImportFailure.INVALID_GITHUB_URL)

                currentCoroutineContext().ensureActive()
                val files = gitHubSource.listFiles(info.owner, info.repo, info.branch, info.path)
                    ?: throw SkillImportException(SkillImportFailure.GITHUB_LIST_FAILED)
                if (files.size > SkillImportLimits.MAX_ENTRIES) {
                    throw SkillImportException(SkillImportFailure.RESOURCE_LIMIT)
                }
                currentCoroutineContext().ensureActive()

                val skillMdEntry = files.find { it.relativePath == "SKILL.md" }
                    ?: throw SkillImportException(SkillImportFailure.SKILL_FILE_MISSING)

                val skillMdBytes = gitHubSource.downloadBytes(skillMdEntry.downloadUrl)
                    ?: throw SkillImportException(SkillImportFailure.DOWNLOAD_FAILED)
                currentCoroutineContext().ensureActive()
                val skillMdContent = skillMdBytes.decodeUtf8Strict()
                    ?: throw SkillImportException(SkillImportFailure.INVALID_SKILL)

                val name = parseSkillDocument(skillMdContent).frontmatter.name

                val fileContents = LinkedHashMap<String, ByteArray>()
                var totalBytes = 0L
                for ((relativePath, downloadUrl) in files) {
                    currentCoroutineContext().ensureActive()
                    val contentBytes = if (downloadUrl == skillMdEntry.downloadUrl) {
                        skillMdBytes
                    } else {
                        gitHubSource.downloadBytes(downloadUrl)
                            ?: throw SkillImportException(SkillImportFailure.DOWNLOAD_FAILED)
                    }
                    currentCoroutineContext().ensureActive()
                    if (contentBytes.size > SkillImportLimits.MAX_ENTRY_BYTES) {
                        throw SkillImportException(SkillImportFailure.RESOURCE_LIMIT)
                    }
                    totalBytes += contentBytes.size
                    if (totalBytes > SkillImportLimits.MAX_TOTAL_BYTES) {
                        throw SkillImportException(SkillImportFailure.RESOURCE_LIMIT)
                    }
                    fileContents[relativePath] = contentBytes
                }

                currentCoroutineContext().ensureActive()
                val saved = skillManager.importSkillFileBytesAtomically(name, fileContents)
                if (!saved) throw SkillImportException(SkillImportFailure.SAVE_FAILED)

                currentCoroutineContext().ensureActive()
                _skills.value = skillManager.listSkills()
                currentCoroutineContext().ensureActive()
                withContext(Dispatchers.Main) { onResult(SkillImportOutcome.Success(name)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: SkillImportException) {
                withContext(Dispatchers.Main) { onResult(SkillImportOutcome.Failure(error.reason)) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult(SkillImportOutcome.Failure(SkillImportFailure.UNKNOWN)) }
            }
        }

    private suspend fun importSkillMarkdown(bytes: ByteArray): List<String> {
        if (bytes.size > SkillImportLimits.MAX_ENTRY_BYTES) {
            throw SkillImportException(SkillImportFailure.RESOURCE_LIMIT)
        }
        val content = bytes.decodeUtf8Strict()
            ?: throw SkillImportException(SkillImportFailure.INVALID_SKILL)
        val frontmatter = parseSkillDocument(content).frontmatter
        val name = frontmatter.name
        currentCoroutineContext().ensureActive()
        val saved = skillManager.importSkill(name, content)
            ?: throw SkillImportException(SkillImportFailure.SAVE_FAILED)
        currentCoroutineContext().ensureActive()
        return listOf(saved.name)
    }

    private suspend fun importSkillsFromZip(bytes: ByteArray): List<String> {
        val files = LinkedHashMap<String, ByteArray>()
        var entryCount = 0
        var totalBytes = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zipInput ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = zipInput.nextEntry ?: break
                try {
                    if (!entry.isDirectory) {
                        entryCount++
                        if (entryCount > SkillImportLimits.MAX_ENTRIES) {
                            throw SkillImportException(SkillImportFailure.RESOURCE_LIMIT)
                        }
                        if (entry.size > SkillImportLimits.MAX_ENTRY_BYTES) {
                            throw SkillImportException(SkillImportFailure.RESOURCE_LIMIT)
                        }
                        val path = normalizeZipEntryPath(entry.name)
                            ?: throw SkillImportException(SkillImportFailure.INVALID_SKILL)
                        if (path in files) throw SkillImportException(SkillImportFailure.INVALID_SKILL)
                        val remaining = SkillImportLimits.MAX_TOTAL_BYTES - totalBytes
                        if (remaining <= 0L) throw SkillImportException(SkillImportFailure.RESOURCE_LIMIT)
                        val content = zipInput.readBytesLimited(
                            minOf(SkillImportLimits.MAX_ENTRY_BYTES.toLong(), remaining).toInt(),
                        )
                        totalBytes += content.size
                        files[path] = content
                    }
                } finally {
                    zipInput.closeEntry()
                }
            }
        }

        val skillMdPaths = files.keys
            .filter { it.substringAfterLast('/').equals("SKILL.md", ignoreCase = true) }
            .sorted()
        if (skillMdPaths.isEmpty()) {
            throw SkillImportException(SkillImportFailure.SKILL_FILE_MISSING)
        }
        val skillBasePaths = skillMdPaths.map {
            it.substringBeforeLast('/', missingDelimiterValue = "")
        }

        val bundleEntries = mutableListOf<SkillImportBundleEntry>()
        for (skillMdPath in skillMdPaths) {
            val skillContent = files[skillMdPath]?.decodeUtf8Strict()
                ?: throw SkillImportException(SkillImportFailure.READ_SOURCE)
            val name = try {
                parseSkillDocument(skillContent).frontmatter.name
            } catch (error: IllegalArgumentException) {
                throw SkillImportException(SkillImportFailure.INVALID_SKILL)
            }

            val basePath = skillMdPath.substringBeforeLast('/', missingDelimiterValue = "")
            val skillFiles = LinkedHashMap<String, ByteArray>()
            for ((path, content) in files) {
                if (isInsideNestedSkill(path, basePath, skillBasePaths)) continue
                val relativePath = relativeToSkillBase(path, basePath) ?: continue
                val targetPath = if (relativePath.equals("SKILL.md", ignoreCase = true)) {
                    "SKILL.md"
                } else {
                    relativePath
                }
                skillFiles[targetPath] = content
            }

            bundleEntries += SkillImportBundleEntry(name, skillFiles)
        }
        if (bundleEntries.map { it.name }.toSet().size != bundleEntries.size) {
            throw SkillImportException(SkillImportFailure.INVALID_SKILL)
        }
        currentCoroutineContext().ensureActive()
        when (skillManager.importSkillBundleAtomically(bundleEntries)) {
            SkillBundleImportResult.SUCCESS -> Unit
            SkillBundleImportResult.DUPLICATE_NAME,
            SkillBundleImportResult.INVALID_BUNDLE,
            -> throw SkillImportException(SkillImportFailure.INVALID_SKILL)
            SkillBundleImportResult.IO_FAILURE -> throw SkillImportException(SkillImportFailure.SAVE_FAILED)
        }
        currentCoroutineContext().ensureActive()
        return bundleEntries.map { it.name }
    }

    private fun parseSkillDocument(content: String): SkillDocument =
        when (val parsed = SkillFrontmatterParser.parseDocument(content)) {
            is SkillParseResult.Success -> parsed.document
            is SkillParseResult.NoFrontmatter -> throw SkillImportException(SkillImportFailure.INVALID_SKILL)
            is SkillParseResult.Error -> throw SkillImportException(SkillImportFailure.INVALID_SKILL)
        }

    private fun isInsideNestedSkill(path: String, basePath: String, skillBasePaths: List<String>): Boolean {
        return skillBasePaths.any { otherBasePath ->
            otherBasePath != basePath &&
                isPathInsideBase(path, otherBasePath) &&
                (basePath.isBlank() || isPathInsideBase(otherBasePath, basePath))
        }
    }

    private fun isPathInsideBase(path: String, basePath: String): Boolean {
        return basePath.isBlank() || path == basePath || path.startsWith("$basePath/")
    }

    private fun relativeToSkillBase(path: String, basePath: String): String? {
        if (basePath.isBlank()) return path
        if (path == basePath) return null
        return path.removePrefix("$basePath/").takeIf { it != path }
    }

    private fun normalizeZipEntryPath(path: String): String? {
        val parts = path.replace('\\', '/')
            .trimStart('/')
            .split('/')
            .filter { it.isNotBlank() && it != "." }
        if (parts.isEmpty() || parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    private fun isZipFile(fileName: String, bytes: ByteArray): Boolean {
        return fileName.endsWith(".zip", ignoreCase = true) ||
            bytes.startsWithBytes(0x50, 0x4B, 0x03, 0x04) ||
            bytes.startsWithBytes(0x50, 0x4B, 0x05, 0x06) ||
            bytes.startsWithBytes(0x50, 0x4B, 0x07, 0x08)
    }

    private fun ByteArray.startsWithBytes(vararg values: Int): Boolean {
        if (size < values.size) return false
        return values.indices.all { index -> (this[index].toInt() and 0xFF) == values[index] }
    }

    private data class GitHubRepoInfo(
        val owner: String,
        val repo: String,
        val branch: String,
        val path: String,
    )

    private fun parseGitHubUrl(url: String): GitHubRepoInfo? {
        val trimmed = url.trim().trimEnd('/')
        // https://github.com/owner/repo
        // https://github.com/owner/repo/tree/branch
        // https://github.com/owner/repo/tree/branch/sub/path
        val regex = Regex("""https://github\.com/([^/]+)/([^/]+)(?:/tree/([^/]+)(/.*)?)?""")
        val match = regex.matchEntire(trimmed) ?: return null
        val owner = match.groupValues[1]
        val repo = match.groupValues[2]
        val branch = match.groupValues[3].ifBlank { "HEAD" }
        val subPath = match.groupValues[4].trimStart('/')
        return GitHubRepoInfo(owner, repo, branch, subPath)
    }

}

internal data class SkillGitHubFile(val relativePath: String, val downloadUrl: String)

internal interface SkillGitHubSource {
    suspend fun listFiles(owner: String, repo: String, branch: String, path: String): List<SkillGitHubFile>?
    suspend fun downloadBytes(url: String): ByteArray?
}

private object NetworkSkillGitHubSource : SkillGitHubSource {
    override suspend fun listFiles(
        owner: String,
        repo: String,
        branch: String,
        path: String,
    ): List<SkillGitHubFile>? {
        val result = mutableListOf<SkillGitHubFile>()
        return if (listFilesRecursively(owner, repo, branch, path, path, result)) result else null
    }

    override suspend fun downloadBytes(url: String): ByteArray? = runInterruptible(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            if (connection.responseCode == 200) {
                connection.inputStream.use { it.readBytesLimited(SkillImportLimits.MAX_ENTRY_BYTES) }
            }
            else null
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun listFilesRecursively(
        owner: String,
        repo: String,
        branch: String,
        dirPath: String,
        basePath: String,
        result: MutableList<SkillGitHubFile>,
    ): Boolean {
        val apiUrl = "https://api.github.com/repos/$owner/$repo/contents/$dirPath?ref=$branch"
        val json = downloadBytes(apiUrl)?.decodeUtf8Strict() ?: return false
        val array = JSONArray(json)
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val type = item.getString("type")
            val itemPath = item.getString("path")
            val relativePath = itemPath.removePrefix("$basePath/").removePrefix(basePath)
            when (type) {
                "file" -> {
                    if (result.size >= SkillImportLimits.MAX_ENTRIES) return false
                    val downloadUrl = item.optString("download_url").takeIf { it.isNotBlank() }
                        ?: return false
                    result += SkillGitHubFile(relativePath, downloadUrl)
                }
                "dir" -> if (!listFilesRecursively(owner, repo, branch, itemPath, basePath, result)) {
                    return false
                }
            }
        }
        return true
    }
}

sealed interface SkillImportOutcome {
    data class Success(val names: String) : SkillImportOutcome
    data class Failure(val reason: SkillImportFailure) : SkillImportOutcome
}

enum class SkillImportFailure {
    READ_SOURCE,
    INVALID_GITHUB_URL,
    GITHUB_LIST_FAILED,
    SKILL_FILE_MISSING,
    DOWNLOAD_FAILED,
    INVALID_SKILL,
    SAVE_FAILED,
    RESOURCE_LIMIT,
    UNKNOWN,
}

private class SkillImportException(val reason: SkillImportFailure) : IllegalArgumentException()

private object SkillImportLimits {
    const val MAX_SOURCE_BYTES = 16 * 1024 * 1024
    const val MAX_ENTRY_BYTES = 4 * 1024 * 1024
    const val MAX_TOTAL_BYTES = 32L * 1024L * 1024L
    const val MAX_ENTRIES = 512
}

private fun InputStream.readBytesLimited(limit: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(limit, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        if (total > limit) throw SkillImportException(SkillImportFailure.RESOURCE_LIMIT)
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

private fun ByteArray.decodeUtf8Strict(): String? = runCatching {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(this))
        .toString()
}.getOrNull()
