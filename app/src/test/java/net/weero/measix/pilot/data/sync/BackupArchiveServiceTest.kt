package net.weero.measix.pilot.data.sync

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.mockk
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.ChatFontFamily
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.imggen.GeneratedMediaStore
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupArchiveServiceTest {
    private lateinit var context: Context
    private lateinit var service: BackupArchiveService
    private lateinit var work: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        work = File(System.getProperty("java.io.tmpdir"), "backup-test-${System.nanoTime()}").apply { mkdirs() }
        File(context.noBackupFilesDir, "backup_restore").deleteRecursively()
        deleteLiveRestoreComponents()
        service = BackupArchiveService(
            context = context,
            settingsStore = mockk(),
            json = JsonInstant,
            database = mockk<AppDatabase>(),
            artifactStore = mockk<ArtifactStore>(),
            generatedMediaStore = mockk<GeneratedMediaStore>(),
        )
    }

    @After
    fun tearDown() {
        work.deleteRecursively()
        File(context.noBackupFilesDir, "backup_restore").deleteRecursively()
        deleteLiveRestoreComponents()
    }

    @Test
    fun `durable archive stages then swaps database files and settings across restart phases`() = runTest {
        val liveDb = context.getDatabasePath("measix_pilot")
        createDatabase(liveDb, "old")
        val oldWal = File(liveDb.parentFile, "measix_pilot-wal").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val liveUpload = File(context.filesDir, "upload").apply { mkdirs() }
        File(liveUpload, "old.txt").writeText("old")
        val stagedDb = File(work, "new.sqlite")
        createDatabase(stagedDb, "new")
        val archive = modernArchive(stagedDb, mapOf("upload/new.txt" to "new".toByteArray()))

        service.stageRestore(archive, BackupSelection(true, true))
        assertEquals("old", databaseMarker(liveDb))

        PendingBackupRestore.bootstrapBeforeDatabaseOpen(context)

        assertEquals("new", databaseMarker(liveDb))
        assertEquals("new", File(context.filesDir, "upload/new.txt").readText())
        assertFalse(File(context.filesDir, "upload/old.txt").exists())
        assertFalse(oldWal.exists())

        var restored: Settings? = null
        val settingsStore = mockk<SettingsStore>()
        coEvery { settingsStore.restoreLocal(any()) } coAnswers { firstArg<Settings>().also { restored = it } }
        PendingBackupRestore.restoreSettingsIfPending(context, settingsStore, JsonInstant)
        assertEquals(Settings().assistantId, restored?.assistantId)
        PendingBackupRestore.complete(context)
        assertFalse(File(context.noBackupFilesDir, "backup_restore/pending").exists())
        assertFalse(File(context.noBackupFilesDir, "backup_restore/rollback").exists())
    }

    @Test
    fun `database and managed files cannot be selected independently`() {
        assertTrue(runCatching { BackupSelection(true, false) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { BackupSelection(false, true) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `legacy aggregate with missing declared payload is rejected before live mutation`() = runTest {
        val legacyDb = File(work, "legacy.sqlite")
        val db = SQLiteDatabase.openOrCreateDatabase(legacyDb, null)
        db.version = 8
        db.execSQL("CREATE TABLE artifact(relative_path TEXT NOT NULL, state TEXT NOT NULL)")
        db.execSQL("INSERT INTO artifact VALUES('upload/missing.bin', 'ACTIVE')")
        db.close()
        val archive = archive(
            mapOf(
                "settings.json" to JsonInstant.encodeToString(Settings()).toByteArray(),
                "measix_pilot.db" to legacyDb.readBytes(),
            )
        )
        val live = File(context.filesDir, "upload/live.txt").apply {
            parentFile?.mkdirs()
            writeText("keep")
        }

        val failure = runCatching { service.stageRestore(archive, BackupSelection(true, true)) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException || failure is IllegalStateException)
        assertEquals("keep", live.readText())
        assertFalse(File(context.noBackupFilesDir, "backup_restore/pending").exists())
    }

    @Test
    fun `modern manifest with a missing declared artifact payload is rejected`() = runTest {
        val stagedDb = File(work, "missing-modern.sqlite")
        createDatabase(stagedDb, "new", artifactPath = "upload/missing.bin")
        val archive = modernArchive(stagedDb, emptyMap())

        val failure = runCatching { service.stageRestore(archive, BackupSelection(true, true)) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException || failure is IllegalStateException)
        assertFalse(File(context.noBackupFilesDir, "backup_restore/pending").exists())
    }

    @Test
    fun `aggregate with a missing declared custom font is rejected`() = runTest {
        val stagedDb = File(work, "missing-font.sqlite")
        createDatabase(stagedDb, "new")
        val settings = Settings(
            displaySetting = Settings().displaySetting.copy(
                chatFontFamily = ChatFontFamily.CUSTOM,
                chatCustomFontPath = "fonts/missing.ttf",
                chatCustomFontName = "missing.ttf",
            )
        )
        val archive = modernArchive(stagedDb, emptyMap(), settings)

        val failure = runCatching { service.stageRestore(archive, BackupSelection(true, true)) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException || failure is IllegalStateException)
        assertFalse(File(context.noBackupFilesDir, "backup_restore/pending").exists())
    }

    @Test
    fun `settings-only restore strips legacy local payload references before commit`() = runTest {
        val settings = Settings(
            assistants = Settings().assistants.mapIndexed { index, assistant ->
                if (index == 0) assistant.copy(background = "file:///files/upload/background.png") else assistant
            },
            displaySetting = Settings().displaySetting.copy(
                chatFontFamily = ChatFontFamily.CUSTOM,
                chatCustomFontPath = "fonts/custom.ttf",
                chatCustomFontName = "custom.ttf",
            )
        )
        val archive = archive(
            mapOf("settings.json" to JsonInstant.encodeToString(settings).toByteArray())
        )

        service.stageRestore(archive, BackupSelection(false, false))
        PendingBackupRestore.bootstrapBeforeDatabaseOpen(context)
        var restored: Settings? = null
        val settingsStore = mockk<SettingsStore>()
        coEvery { settingsStore.restoreLocal(any()) } coAnswers { firstArg<Settings>().also { restored = it } }
        PendingBackupRestore.restoreSettingsIfPending(context, settingsStore, JsonInstant)

        assertNull(restored?.assistants?.first()?.background)
        assertEquals(ChatFontFamily.DEFAULT, restored?.displaySetting?.chatFontFamily)
        assertEquals("", restored?.displaySetting?.chatCustomFontPath)
        PendingBackupRestore.complete(context)
    }

    @Test
    fun `swap faults rollback and retry`() = runTest {
        val swapPoints = listOf("measix_pilot.db", "upload", "images", "skills", "fonts")
        val stagedDb = File(work, "new-template.sqlite")
        createDatabase(stagedDb, "new")
        swapPoints.forEach { faultPoint ->
            File(context.noBackupFilesDir, "backup_restore").deleteRecursively()
            deleteLiveRestoreComponents()
            val liveDb = context.getDatabasePath("measix_pilot")
            createDatabase(liveDb, "old")
            val oldDatabase = liveDb.readBytes()
            val walBytes = byteArrayOf(7, 8, 9, 10)
            val oldWal = File(liveDb.parentFile, "measix_pilot-wal").apply { writeBytes(walBytes) }
            BackupArchiveService.DURABLE_DIRECTORIES.forEach { folder ->
                File(context.filesDir, "$folder/old.txt").apply {
                    parentFile?.mkdirs()
                    writeText("old-$folder")
                }
            }
            val files = BackupArchiveService.DURABLE_DIRECTORIES.associate { folder ->
                "$folder/new.txt" to "new-$folder".toByteArray()
            }
            service.stageRestore(modernArchive(stagedDb, files), BackupSelection(true, true))

            val failure = runCatching {
                PendingBackupRestore.applyBeforeDatabaseOpen(context) { swapped ->
                    if (swapped == faultPoint) error("injected-$faultPoint")
                }
            }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertTrue(liveDb.readBytes().contentEquals(oldDatabase))
            assertTrue(oldWal.readBytes().contentEquals(walBytes))
            BackupArchiveService.DURABLE_DIRECTORIES.forEach { folder ->
                assertEquals("old-$folder", File(context.filesDir, "$folder/old.txt").readText())
            }

            PendingBackupRestore.bootstrapBeforeDatabaseOpen(context)
            assertEquals("new", databaseMarker(liveDb))
            PendingBackupRestore.complete(context)
        }
    }

    @Test
    fun `zip slip entry is rejected without publishing pending restore`() = runTest {
        val archive = archive(
            linkedMapOf(
                "settings.json" to JsonInstant.encodeToString(Settings()).toByteArray(),
                "../outside" to byteArrayOf(1),
            )
        )

        val failure = runCatching { service.stageRestore(archive, BackupSelection(false, false)) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertFalse(File(context.noBackupFilesDir, "backup_restore/pending").exists())
    }

    private fun modernArchive(
        database: File,
        files: Map<String, ByteArray>,
        settings: Settings = Settings(),
    ): File {
        val payloads = linkedMapOf(
            "settings.json" to JsonInstant.encodeToString(settings).toByteArray(),
            "measix_pilot.db" to database.readBytes(),
        ).apply { putAll(files) }
        val manifest = DurableBackupManifest(
            version = BackupArchiveService.MANIFEST_VERSION,
            entries = payloads.map { (path, bytes) ->
                DurableBackupEntry(path, bytes.size.toLong(), sha256(bytes))
            }.sortedBy(DurableBackupEntry::path),
        )
        return archive(payloads + ("backup_manifest" to JsonInstant.encodeToString(manifest).toByteArray()))
    }

    private fun archive(entries: Map<String, ByteArray>): File {
        val file = File(work, "archive-${System.nanoTime()}.zip")
        ZipOutputStream(FileOutputStream(file)).use { output ->
            entries.forEach { (name, bytes) ->
                output.putNextEntry(ZipEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
        }
        return file
    }

    private fun createDatabase(file: File, marker: String, artifactPath: String? = null) {
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.version = 8
        db.execSQL("CREATE TABLE marker(value TEXT NOT NULL)")
        db.execSQL("INSERT INTO marker VALUES(?)", arrayOf(marker))
        db.execSQL("CREATE TABLE artifact(relative_path TEXT NOT NULL, state TEXT NOT NULL)")
        db.execSQL("CREATE TABLE GenMediaEntity(path TEXT NOT NULL)")
        artifactPath?.let { db.execSQL("INSERT INTO artifact VALUES(?, 'ACTIVE')", arrayOf(it)) }
        db.close()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun deleteLiveRestoreComponents() {
        val database = context.getDatabasePath("measix_pilot")
        database.delete()
        File(database.parentFile, "measix_pilot-wal").delete()
        File(database.parentFile, "measix_pilot-shm").delete()
        BackupArchiveService.DURABLE_DIRECTORIES.forEach { folder ->
            File(context.filesDir, folder).deleteRecursively()
        }
    }

    private fun databaseMarker(file: File): String {
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        return try {
            db.rawQuery("SELECT value FROM marker", null).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getString(0)
            }
        } finally {
            db.close()
        }
    }
}
