package net.weero.measix.pilot.data.sync

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.migrateLegacySettingsJson
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.files.FileFolders
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.BackupSnapshotBarrier
import net.weero.measix.pilot.data.imggen.GeneratedMediaStore
import kotlin.uuid.Uuid

@Serializable
internal data class DurableBackupManifest(
    val version: String,
    val entries: List<DurableBackupEntry>,
)

@Serializable
internal data class DurableBackupEntry(
    val path: String,
    val size: Long,
    val sha256: String,
)

/** Database and its managed payload directories are one restore aggregate. */
data class BackupSelection(
    val includeDatabase: Boolean,
    val includeFiles: Boolean,
) {
    init {
        require(includeDatabase == includeFiles) {
            "Database and managed files form one durable aggregate and must be selected together"
        }
    }
    val includeDurableAggregate: Boolean get() = includeDatabase || includeFiles
}

/**
 * The single backup archive owner. Creation uses a SQLite snapshot rather than copying live
 * main/WAL/SHM files. Restore only validates and stages; it never mutates a running Room instance.
 */
class BackupArchiveService(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val json: Json,
    private val database: AppDatabase,
    private val artifactStore: ArtifactStore,
    private val generatedMediaStore: GeneratedMediaStore,
) {
    private val restoreMutex = Mutex()

    suspend fun prepare(selection: BackupSelection): File {
        val produced = AtomicReference<File?>()
        try {
            return withContext(Dispatchers.IO) {
                generatedMediaStore.withPersistLock {
                    artifactStore.withLifecycleLock {
                        BackupSnapshotBarrier.withLock {
                            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                            val unique = Uuid.random().toString()
                            val archive = File(context.cacheDir, "backup_${timestamp}_$unique.zip")
                            val snapshot = if (selection.includeDurableAggregate) {
                                File(context.cacheDir, "backup_database_$unique.sqlite").also(::createDatabaseSnapshot)
                            } else null
                            try {
                                val liveSettings = settingsStore.settingsFlow.first { !it.init }
                                val archiveSettings = if (selection.includeDurableAggregate) {
                                    liveSettings
                                } else {
                                    BackupSettingsPolicy.withoutLocalPayloadReferences(liveSettings)
                                }
                                val settingsBytes = json.encodeToString(archiveSettings)
                                    .toByteArray(Charsets.UTF_8)
                                val durableFiles = if (snapshot != null) buildList {
                                    add(DATABASE_ENTRY to snapshot)
                                    DURABLE_DIRECTORIES.forEach { folder ->
                                        val root = File(context.filesDir, folder)
                                        if (root.isDirectory) root.walkTopDown().filter(File::isFile).forEach { file ->
                                            add("$folder/${file.relativeTo(root).invariantSeparatorsPath}" to file)
                                        }
                                    }
                                }.sortedBy { it.first } else emptyList()
                                ZipOutputStream(FileOutputStream(archive)).use { output ->
                                    addBytes(output, SETTINGS_ENTRY, settingsBytes)
                                    if (snapshot != null) {
                                        durableFiles.forEach { (path, file) ->
                                            currentCoroutineContext().ensureActive()
                                            addFile(output, file, path)
                                        }
                                        val manifestEntries = buildList {
                                            add(DurableBackupEntry(SETTINGS_ENTRY, settingsBytes.size.toLong(), sha256(settingsBytes)))
                                            durableFiles.forEach { (path, file) ->
                                                currentCoroutineContext().ensureActive()
                                                add(DurableBackupEntry(path, file.length(), sha256(file)))
                                            }
                                        }.sortedBy(DurableBackupEntry::path)
                                        addText(output, MANIFEST_ENTRY, json.encodeToString(DurableBackupManifest(MANIFEST_VERSION, manifestEntries)))
                                    }
                                }
                                archive.also(produced::set)
                            } catch (error: Throwable) {
                                archive.delete()
                                throw error
                            } finally {
                                snapshot?.delete()
                            }
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) { produced.get()?.delete() }
            throw cancelled
        }
    }

    suspend fun stageRestore(archive: File, selection: BackupSelection) {
        withContext(Dispatchers.IO) {
            if (!restoreMutex.tryLock()) throw BackupRestoreInProgressException()
            try {
                    require(archive.isFile && archive.canRead()) { "Backup file is not readable" }
                    val staging = PendingBackupRestore.stagingDir(context)
                    val pending = PendingBackupRestore.pendingDir(context)
                    check(!pending.exists()) { "A backup restore is already pending restart" }
                    staging.deleteRecursively()
                    check(staging.mkdirs()) { "Unable to create restore staging directory" }
                    try {
                        val seen = hashSetOf<String>()
                        val archiveFiles = hashSetOf<String>()
                        var totalBytes = 0L
                        ZipInputStream(FileInputStream(archive)).use { input ->
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val entry = input.nextEntry ?: break
                                require(seen.size < MAX_ENTRIES) { "Backup archive has too many entries" }
                                val normalized = entry.name.replace('\\', '/').trimStart('/')
                                require(normalized.isNotBlank() && seen.add(normalized)) {
                                    "Invalid duplicate backup entry: $normalized"
                                }
                                require(normalized.split('/').none { it == ".." }) {
                                    "Invalid backup path: $normalized"
                                }
                                val accepted = when {
                                    normalized == SETTINGS_ENTRY -> true
                                    selection.includeDurableAggregate && normalized == MANIFEST_ENTRY -> true
                                    selection.includeDurableAggregate && normalized in LEGACY_DATABASE_ENTRIES -> true
                                    selection.includeDurableAggregate && DURABLE_DIRECTORIES.any { directory ->
                                        normalized == directory || normalized.startsWith("$directory/")
                                    } -> true
                                    else -> false
                                }
                                if (accepted && !entry.isDirectory) {
                                    archiveFiles += normalized
                                    val target = resolveInside(staging, normalized)
                                    target.parentFile?.mkdirs()
                                    FileOutputStream(target).use { output ->
                                        val copied = copyLimited(input, output, MAX_ENTRY_BYTES)
                                        totalBytes += copied
                                        require(totalBytes <= MAX_TOTAL_BYTES) { "Backup archive is too large" }
                                    }
                                }
                                input.closeEntry()
                            }
                        }
                        val restoredSettings = validateSettings(staging)
                        if (selection.includeDurableAggregate) {
                            val modern = File(staging, MANIFEST_ENTRY).isFile
                            if (modern) validateModernManifest(staging, archiveFiles)
                            normalizeAndValidateDatabase(staging)
                            if (modern) validateModernAggregate(staging) else validateLegacyAggregate(staging)
                            validateSettingsPayloadRoots(staging, restoredSettings)
                            DURABLE_DIRECTORIES.forEach { File(staging, it).mkdirs() }
                            File(staging, AGGREGATE_MARKER).writeText("1", Charsets.UTF_8)
                        }
                        currentCoroutineContext().ensureActive()
                        withContext(NonCancellable) {
                            File(staging, PREPARED_MARKER).writeText("1", Charsets.UTF_8)
                            check(staging.renameTo(pending)) { "Unable to atomically publish pending restore" }
                        }
                    } catch (error: Throwable) {
                        staging.deleteRecursively()
                        throw error
                    }
            } finally {
                restoreMutex.unlock()
            }
        }
    }

    private fun createDatabaseSnapshot(target: File) {
        target.delete()
        val escaped = target.absolutePath.replace("'", "''")
        try {
            database.openHelper.writableDatabase.execSQL("VACUUM INTO '$escaped'")
        } catch (error: Exception) {
            throw IllegalStateException(
                "This device SQLite runtime cannot create a transactionally consistent backup snapshot",
                error,
            )
        }
        validateDatabase(target)
    }

    private fun validateSettings(staging: File): Settings {
        val file = File(staging, SETTINGS_ENTRY)
        require(file.isFile) { "Backup archive is missing settings.json" }
        return json.decodeFromString<Settings>(migrateLegacySettingsJson(file.readText(Charsets.UTF_8)))
    }

    private fun validateSettingsPayloadRoots(staging: File, settings: Settings) {
        val fontPath = settings.displaySetting.chatCustomFontPath
        if (fontPath.isBlank()) return
        val normalized = fontPath.replace('\\', '/').trimStart('/')
        require(normalized.startsWith("${FileFolders.FONTS}/") && normalized.count { it == '/' } == 1) {
            "Custom font payload is outside its managed domain: $fontPath"
        }
        check(resolveInside(staging, normalized).isFile) {
            "Backup is missing declared custom font payload: $normalized"
        }
    }

    private fun normalizeAndValidateDatabase(staging: File) {
        val main = File(staging, DATABASE_ENTRY)
        require(main.isFile) { "Backup archive is missing the database snapshot" }
        val legacyWal = File(staging, LEGACY_WAL_ENTRY)
        val legacyShm = File(staging, LEGACY_SHM_ENTRY)
        if (legacyWal.exists()) {
            check(legacyWal.renameTo(File(staging, "$DATABASE_ENTRY-wal"))) { "Unable to stage legacy WAL" }
        }
        if (legacyShm.exists()) {
            check(legacyShm.renameTo(File(staging, "$DATABASE_ENTRY-shm"))) { "Unable to stage legacy SHM" }
        }
        val db = SQLiteDatabase.openDatabase(main.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
        } finally {
            db.close()
        }
        File(staging, "$DATABASE_ENTRY-wal").delete()
        File(staging, "$DATABASE_ENTRY-shm").delete()
        validateDatabase(main)
    }

    private fun validateDatabase(file: File) {
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            db.rawQuery("PRAGMA quick_check", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getString(0) == "ok") { "Backup database integrity check failed" }
            }
            val version = db.version
            require(version in 1..8) { "Unsupported backup database version: $version" }
        } finally {
            db.close()
        }
    }

    /**
     * Older archives had no manifest and omitted generated-media payloads. They are upgraded only
     * when every declared managed payload is present and no gallery row would become dangling.
     */
    private fun validateLegacyAggregate(staging: File) {
        require(DURABLE_DIRECTORIES.any { File(staging, it).exists() }) {
            "Legacy backup does not prove that its managed file aggregate was included"
        }
        val db = SQLiteDatabase.openDatabase(File(staging, DATABASE_ENTRY).absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            if (tableExists(db, "GenMediaEntity")) {
                db.rawQuery("SELECT COUNT(*) FROM GenMediaEntity", null).use { cursor ->
                    check(cursor.moveToFirst() && cursor.getLong(0) == 0L) {
                        "Legacy backup has generated-media rows but no complete images payload domain"
                    }
                }
            }
            val declaredPaths = when {
                tableExists(db, "artifact") -> queryStrings(db, "SELECT relative_path FROM artifact WHERE state = 'ACTIVE'")
                tableExists(db, "managed_files") -> queryStrings(db, "SELECT relative_path FROM managed_files")
                else -> emptyList()
            }
            declaredPaths.forEach { relativePath ->
                if (relativePath.startsWith("${FileFolders.UPLOAD}/") ||
                    relativePath.startsWith("${FileFolders.IMAGES}/")
                ) {
                    check(resolveInside(staging, relativePath).isFile) {
                        "Legacy backup is missing declared payload: $relativePath"
                    }
                }
            }
        } finally {
            db.close()
        }
    }

    private fun validateModernManifest(staging: File, archiveFiles: Set<String>) {
        val manifest = try {
            json.decodeFromString<DurableBackupManifest>(File(staging, MANIFEST_ENTRY).readText(Charsets.UTF_8))
        } catch (error: Exception) {
            throw IllegalArgumentException("Backup manifest is not valid", error)
        }
        require(manifest.version == MANIFEST_VERSION) { "Unsupported backup manifest: ${manifest.version}" }
        val declared = manifest.entries.associateBy(DurableBackupEntry::path)
        require(declared.size == manifest.entries.size) { "Backup manifest contains duplicate entries" }
        require(SETTINGS_ENTRY in declared && DATABASE_ENTRY in declared) {
            "Backup manifest is missing the durable roots"
        }
        require(archiveFiles == declared.keys + MANIFEST_ENTRY) {
            "Backup archive entries do not exactly match the manifest"
        }
        manifest.entries.forEach { entry ->
            require(entry.path != MANIFEST_ENTRY) { "Backup manifest cannot declare itself" }
            val file = resolveInside(staging, entry.path)
            require(file.isFile && file.length() == entry.size && sha256(file) == entry.sha256) {
                "Backup entry failed manifest verification: ${entry.path}"
            }
        }
    }

    /** A v2 manifest is accepted only when every durable database row has its payload. */
    private fun validateModernAggregate(staging: File) {
        val db = SQLiteDatabase.openDatabase(File(staging, DATABASE_ENTRY).absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            require(db.version == 8) { "Modern backup database version must be 8: ${db.version}" }
            require(tableExists(db, "artifact")) { "Modern backup database is missing artifact metadata" }
            require(tableExists(db, "GenMediaEntity")) { "Modern backup database is missing generated-media metadata" }
            val artifactPaths = queryStrings(db, "SELECT relative_path FROM artifact WHERE state = 'ACTIVE'")
            artifactPaths.forEach { relativePath ->
                requireManagedPayload(staging, relativePath, "artifact")
            }
            val generatedPaths = queryStrings(db, "SELECT path FROM GenMediaEntity")
            generatedPaths.forEach { relativePath ->
                require(relativePath.startsWith("${FileFolders.IMAGES}/")) {
                    "Generated-media payload is outside its managed domain: $relativePath"
                }
                requireManagedPayload(staging, relativePath, "generated-media")
            }
        } finally {
            db.close()
        }
    }

    private fun requireManagedPayload(staging: File, relativePath: String, owner: String) {
        require(
            relativePath.startsWith("${FileFolders.UPLOAD}/") ||
                relativePath.startsWith("${FileFolders.IMAGES}/")
        ) { "$owner payload is outside a managed domain: $relativePath" }
        check(resolveInside(staging, relativePath).isFile) {
            "Backup is missing declared $owner payload: $relativePath"
        }
    }

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean =
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use { it.moveToFirst() }

    private fun queryStrings(db: SQLiteDatabase, sql: String): List<String> = buildList {
        db.rawQuery(sql, null).use { cursor ->
            while (cursor.moveToNext()) add(cursor.getString(0))
        }
    }

    private fun addFile(output: ZipOutputStream, file: File, name: String) {
        output.putNextEntry(ZipEntry(name))
        FileInputStream(file).use { it.copyTo(output) }
        output.closeEntry()
    }

    private fun addText(output: ZipOutputStream, name: String, content: String) {
        addBytes(output, name, content.toByteArray(Charsets.UTF_8))
    }

    private fun addBytes(output: ZipOutputStream, name: String, content: ByteArray) {
        output.putNextEntry(ZipEntry(name))
        output.write(content)
        output.closeEntry()
    }

    private fun sha256(file: File): String = FileInputStream(file).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun resolveInside(root: File, relative: String): File {
        val target = File(root, relative).canonicalFile
        val canonicalRoot = root.canonicalFile
        require(target.path.startsWith(canonicalRoot.path + File.separator)) { "Invalid backup path: $relative" }
        return target
    }

    private fun copyLimited(input: java.io.InputStream, output: java.io.OutputStream, limit: Long): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return total
            total += count
            require(total <= limit) { "Backup entry is too large" }
            output.write(buffer, 0, count)
        }
    }

    companion object {
        internal const val SETTINGS_ENTRY = "settings.json"
        internal const val MANIFEST_ENTRY = "backup_manifest"
        internal const val MANIFEST_VERSION = "rikkahub-durable-v3"
        internal const val DATABASE_ENTRY = "measix_pilot.db"
        internal const val LEGACY_WAL_ENTRY = "measix_pilot-wal"
        internal const val LEGACY_SHM_ENTRY = "measix_pilot-shm"
        internal const val PREPARED_MARKER = ".prepared"
        internal const val AGGREGATE_MARKER = ".durable_aggregate"
        internal val LEGACY_DATABASE_ENTRIES = setOf(DATABASE_ENTRY, LEGACY_WAL_ENTRY, LEGACY_SHM_ENTRY)
        internal val DURABLE_DIRECTORIES = listOf(
            FileFolders.UPLOAD,
            FileFolders.IMAGES,
            FileFolders.SKILLS,
            FileFolders.FONTS,
        )
        private const val MAX_ENTRIES = 20_000
        private const val MAX_ENTRY_BYTES = 2L * 1024 * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 8L * 1024 * 1024 * 1024
    }
}

class BackupRestoreInProgressException : IllegalStateException("Another backup restore is already being staged")
