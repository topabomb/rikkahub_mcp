package net.weero.measix.pilot.data.sync

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
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
import net.weero.measix.pilot.data.ai.mcp.McpCatalogCandidate
import net.weero.measix.pilot.data.ai.mcp.McpCatalogSnapshot
import net.weero.measix.pilot.data.ai.mcp.McpCatalogStore
import net.weero.measix.pilot.data.ai.mcp.McpCatalogTool
import net.weero.measix.pilot.data.ai.mcp.McpCommonOptions
import net.weero.measix.pilot.data.ai.mcp.McpServerConfig
import net.weero.measix.pilot.data.ai.mcp.initialSnapshot
import net.weero.measix.pilot.data.ai.mcp.mcpDefinitionDigest
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.db.APP_DATABASE_VERSION
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.imggen.GeneratedMediaStore
import net.weero.measix.pilot.utils.JsonInstant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
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
    private lateinit var catalogStore: McpCatalogStore
    private lateinit var work: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        work = File(System.getProperty("java.io.tmpdir"), "backup-test-${System.nanoTime()}").apply { mkdirs() }
        File(context.noBackupFilesDir, "backup_restore").deleteRecursively()
        deleteLiveRestoreComponents()
        catalogStore = mockk(relaxed = true)
        coEvery { catalogStore.snapshotForBackup(any()) } returns emptyList()
        service = BackupArchiveService(
            context = context,
            settingsStore = mockk(),
            mcpCatalogStore = catalogStore,
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
        PendingBackupRestore.restoreSettingsIfPending(context, settingsStore, catalogStore, JsonInstant)
        assertEquals(Settings().assistantId, restored?.assistantId)
        coVerify { catalogStore.restoreCatalogs(emptyList(), any()) }
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
        PendingBackupRestore.restoreSettingsIfPending(context, settingsStore, catalogStore, JsonInstant)

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

    @Test
    fun `MCP catalogs round trip as an independent backup entry`() = runTest {
        val server = McpServerConfig.StreamableHTTPServer(
            commonOptions = McpCommonOptions(name = "Remote tools"),
            url = "https://example.test/mcp",
        )
        val settings = Settings(mcpServers = listOf(server))
        val catalog = McpCatalogCandidate(
            serverId = server.id,
            definitionDigest = server.mcpDefinitionDigest(),
            tools = listOf(
                McpCatalogTool(
                    name = "measure",
                    description = "Measure a value",
                    inputSchema = buildJsonObject { put("type", "object") },
                )
            ),
        ).initialSnapshot()
        val stagedDb = File(work, "catalog.sqlite")
        createDatabase(stagedDb, "new")
        val archive = modernArchive(stagedDb, emptyMap(), settings, listOf(catalog))

        service.stageRestore(archive, BackupSelection(true, true))
        PendingBackupRestore.bootstrapBeforeDatabaseOpen(context)
        val settingsStore = mockk<SettingsStore>()
        coEvery { settingsStore.restoreLocal(any()) } returns settings

        PendingBackupRestore.restoreSettingsIfPending(context, settingsStore, catalogStore, JsonInstant)

        coVerify(exactly = 1) { catalogStore.restoreCatalogs(listOf(catalog), settings.mcpServers) }
    }

    @Test
    fun `v3 backup migrates complete legacy MCP schema into the catalog entry`() = runTest {
        val server = McpServerConfig.StreamableHTTPServer(
            commonOptions = McpCommonOptions(name = "Legacy remote tools"),
            url = "https://legacy.example/mcp",
        )
        val settingsRoot = JsonInstant.parseToJsonElement(
            JsonInstant.encodeToString(Settings(mcpServers = listOf(server)))
        ).jsonObject
        val serverRoot = settingsRoot.getValue("mcpServers").jsonArray.single().jsonObject
        val commonRoot = serverRoot.getValue("commonOptions").jsonObject
        val legacyTool = buildJsonObject {
            put("enable", false)
            put("name", "legacy_measure")
            put("description", "Legacy measure schema")
            put("inputSchema", buildJsonObject { put("type", "object") })
            put("needsApproval", true)
        }
        val legacyServer = buildJsonObject {
            serverRoot.forEach { (key, value) -> put(key, value) }
            put("commonOptions", buildJsonObject {
                commonRoot.forEach { (key, value) -> put(key, value) }
                put("tools", JsonArray(listOf(legacyTool)))
            })
        }
        val legacySettings = buildJsonObject {
            settingsRoot.forEach { (key, value) -> put(key, value) }
            put("mcpServers", JsonArray(listOf(legacyServer)))
        }
        val stagedDb = File(work, "legacy-catalog.sqlite")
        createDatabase(stagedDb, "legacy")
        val payloads = linkedMapOf(
            "settings.json" to JsonInstant.encodeToString(legacySettings).toByteArray(),
            "measix_pilot.db" to stagedDb.readBytes(),
        )
        val manifest = DurableBackupManifest(
            version = "rikkahub-durable-v3",
            entries = payloads.map { (path, bytes) ->
                DurableBackupEntry(path, bytes.size.toLong(), sha256(bytes))
            }.sortedBy(DurableBackupEntry::path),
        )
        val archive = archive(payloads + ("backup_manifest" to JsonInstant.encodeToString(manifest).toByteArray()))

        service.stageRestore(archive, BackupSelection(true, true))
        PendingBackupRestore.bootstrapBeforeDatabaseOpen(context)
        val settingsStore = mockk<SettingsStore>()
        var restoredSettings: Settings? = null
        var restoredCatalogs: List<McpCatalogSnapshot>? = null
        coEvery { settingsStore.restoreLocal(any()) } coAnswers {
            firstArg<Settings>().also { restoredSettings = it }
        }
        coEvery { catalogStore.restoreCatalogs(any(), any()) } coAnswers {
            firstArg<List<McpCatalogSnapshot>>().also { restoredCatalogs = it }
        }

        PendingBackupRestore.restoreSettingsIfPending(context, settingsStore, catalogStore, JsonInstant)

        val restoredPolicy = requireNotNull(restoredSettings).mcpServers.single().commonOptions.toolPolicies.single()
        assertFalse(restoredPolicy.enable)
        assertTrue(restoredPolicy.needsApproval)
        val restoredCatalog = requireNotNull(restoredCatalogs).single()
        assertEquals(server.id, restoredCatalog.serverId)
        assertEquals(listOf("legacy_measure"), restoredCatalog.tools.map { it.name })
        assertEquals("Legacy measure schema", restoredCatalog.tools.single().description)
    }

    private fun modernArchive(
        database: File,
        files: Map<String, ByteArray>,
        settings: Settings = Settings(),
        catalogs: List<McpCatalogSnapshot> = emptyList(),
    ): File {
        val payloads = linkedMapOf(
            "settings.json" to JsonInstant.encodeToString(settings).toByteArray(),
            "mcp_catalogs.json" to JsonInstant.encodeToString(catalogs).toByteArray(),
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

    @Test
    fun `v8 archive remains restorable without rewriting its database or filenames`() = runTest {
        val path = "upload/809278de-6677-4bc1-9249-d94c85b0930c.png"
        val source = File(work, "v8.sqlite")
        createDatabase(source, "v8", artifactPath = path, version = 8)
        val archive = modernArchive(source, mapOf(path to byteArrayOf(1, 2, 3)))

        service.stageRestore(archive, BackupSelection(true, true))
        PendingBackupRestore.bootstrapBeforeDatabaseOpen(context)

        val restored = context.getDatabasePath("measix_pilot")
        assertEquals("v8", databaseMarker(restored))
        SQLiteDatabase.openDatabase(restored.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(8, db.version)
        }
        assertArrayEquals(byteArrayOf(1, 2, 3), File(context.filesDir, path).readBytes())
    }

    @Test
    fun `unsupported archive versions fail before live mutation`() = runTest {
        val live = context.getDatabasePath("measix_pilot")
        createDatabase(live, "live")
        for (version in listOf(7, APP_DATABASE_VERSION + 1)) {
            val source = File(work, "unsupported-$version.sqlite")
            createDatabase(source, "unsupported", version = version)
            val archive = modernArchive(source, emptyMap())

            assertTrue(runCatching { service.stageRestore(archive, BackupSelection(true, true)) }.isFailure)
            assertEquals("live", databaseMarker(live))
            assertFalse(File(context.noBackupFilesDir, "backup_restore/pending").exists())
        }
    }

    private fun createDatabase(
        file: File,
        marker: String,
        artifactPath: String? = null,
        version: Int = APP_DATABASE_VERSION,
    ) {
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.version = version
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
