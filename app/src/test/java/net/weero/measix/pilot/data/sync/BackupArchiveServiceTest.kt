package net.weero.measix.pilot.data.sync

import me.rerere.common.configuration.ConfigurationReference

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import net.weero.measix.pilot.data.db.createAppDatabase
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipFile
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
import net.weero.measix.pilot.data.db.entity.ConversationEntity
import net.weero.measix.pilot.data.db.entity.ConversationModelContextEntity
import net.weero.measix.pilot.data.db.entity.MessageNodeEntity
import net.weero.measix.pilot.service.ConversationDisclosureSnapshotService
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
    @Test
    fun `legacy backup without assistant search field preserves model search on restore`() = runTest {
        val model = me.rerere.ai.provider.Model(tools = setOf(me.rerere.ai.provider.BuiltInTools.Search))
        val provider = me.rerere.ai.provider.ProviderSetting.Google(models = listOf(model))
        val providers = JsonInstant.encodeToString(listOf<me.rerere.ai.provider.ProviderSetting>(provider))
        val legacy = """{"assistants":[{"id":"00000000-0000-0000-0000-000000000001","name":"Legacy","chatModelId":"${model.id}"}],"providers":$providers}"""
        service.stageRestore(archive(mapOf("settings.json" to legacy.toByteArray())), BackupSelection(false, false))
        PendingBackupRestore.bootstrapBeforeDatabaseOpen(context)
        var restored: Settings? = null
        val settingsStore = mockk<SettingsStore>()
        coEvery { settingsStore.restoreLocal(any()) } coAnswers { firstArg<Settings>().also { restored = it } }
        PendingBackupRestore.restoreSettingsIfPending(context, settingsStore, catalogStore, JsonInstant)
        assertNull(restored!!.assistants.single().builtInSearch)
        assertEquals(model, restored!!.providers.single().models.single())
        PendingBackupRestore.complete(context)
    }

    private lateinit var context: Context
    private lateinit var service: BackupArchiveService
    private lateinit var catalogStore: McpCatalogStore
    private lateinit var work: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // JVM exercises Room and restore semantics; native FTS loading is covered on device.
        mockkStatic("net.weero.measix.pilot.data.db.AppDatabaseFactoryKt")
        every { createAppDatabase(any(), any()) } answers {
            Room.databaseBuilder(firstArg<Context>(), AppDatabase::class.java, secondArg<String>())
                .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE).build()
        }
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
        unmockkStatic("net.weero.measix.pilot.data.db.AppDatabaseFactoryKt")
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
    fun `managed tool output artifact is restored with its database root`() = runTest {
        val path = "tool_outputs/result.txt"
        val stagedDb = File(work, "tool-output.sqlite")
        createDatabase(stagedDb, "tool-output", artifactPath = path)
        val archive = modernArchive(stagedDb, mapOf(path to "full result".toByteArray()))

        service.stageRestore(archive, BackupSelection(true, true))
        PendingBackupRestore.bootstrapBeforeDatabaseOpen(context)

        assertEquals("tool-output", databaseMarker(context.getDatabasePath("measix_pilot")))
        assertEquals("full result", File(context.filesDir, path).readText())
        PendingBackupRestore.complete(context)
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
    fun `production settings-only backup excludes database files and disclosure state`() = runTest {
        val settingsStore = mockk<SettingsStore>()
        val artifactStore = mockk<ArtifactStore>()
        val generatedMediaStore = mockk<GeneratedMediaStore>()
        coEvery { settingsStore.snapshotLocal() } returns Settings(
            chatModelId = ConfigurationReference.parse("00000000-0000-0000-0000-000000000201"),
            fastModelId = ConfigurationReference.parse("00000000-0000-0000-0000-000000000202"),
            imageGenerationModelId = ConfigurationReference.parse("00000000-0000-0000-0000-000000000203"),
            compressModelId = ConfigurationReference.parse("00000000-0000-0000-0000-000000000204"),
        )
        coEvery { artifactStore.withLifecycleLock<Any>(any()) } coAnswers {
            firstArg<suspend () -> Any>().invoke()
        }
        coEvery { generatedMediaStore.withPersistLock<Any>(any()) } coAnswers {
            firstArg<suspend () -> Any>().invoke()
        }
        val localService = BackupArchiveService(
            context, settingsStore, catalogStore, JsonInstant, mockk(), artifactStore, generatedMediaStore,
        )

        val archive = localService.prepare(BackupSelection(false, false))
        ZipFile(archive).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            assertEquals(listOf("settings.json", "mcp_catalogs.json"), names)
            val settingsJson = zip.getInputStream(zip.getEntry("settings.json")).bufferedReader().readText()
            assertFalse(settingsJson.contains("modelContext", ignoreCase = true))
            assertFalse(settingsJson.contains("disclosure", ignoreCase = true))
        }
        archive.delete()
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
        val swapPoints = listOf("measix_pilot.db") + BackupArchiveService.DURABLE_DIRECTORIES
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
    fun `valid Room database reopens through DAO after durable v5 round trip`() = runTest {
        val sourceName = "v10-context-${System.nanoTime()}"
        val conversationId = "00000000-0000-0000-0000-000000000010"
        val anchorNodeId = "00000000-0000-0000-0000-000000000011"
        val ownerNodeId = "00000000-0000-0000-0000-000000000012"
        val anchorMessageId = "00000000-0000-0000-0000-000000000013"
        val ownerMessageId = "00000000-0000-0000-0000-000000000014"
        val content = ConversationDisclosureSnapshotService.render(
            ConversationDisclosureSnapshotService.Candidate(
                assistant = Settings().assistants.first(),
                allAssistants = Settings().assistants,
                memories = emptyList(),
            ),
        )
        val room = Room.databaseBuilder(context, AppDatabase::class.java, sourceName)
            .allowMainThreadQueries()
            .build()
        room.conversationDao().insert(
            ConversationEntity(
                id = conversationId,
                assistantId = Settings().assistants.first().id.toString(),
                title = "backup context",
                createAt = 1,
                updateAt = 1,
                chatSuggestions = "[]",
                isPinned = false,
            ),
        )
        val anchorMessage = me.rerere.ai.ui.UIMessage.user("request").copy(
            id = kotlin.uuid.Uuid.parse(anchorMessageId),
        )
        val ownerMessage = me.rerere.ai.ui.UIMessage(
            id = kotlin.uuid.Uuid.parse(ownerMessageId),
            role = me.rerere.ai.core.MessageRole.ASSISTANT,
            parts = listOf(
                me.rerere.ai.ui.UIMessagePart.Step(kotlin.uuid.Uuid.random(), 0,
                    kotlin.time.Instant.fromEpochMilliseconds(1), outcome = me.rerere.ai.ui.StepOutcome.Final),
                me.rerere.ai.ui.UIMessagePart.Text("answer"),
            ),
        )
        room.messageNodeDao().insertAll(
            listOf(
                MessageNodeEntity(anchorNodeId, conversationId, 0, JsonInstant.encodeToString(listOf(anchorMessage)), 0),
                MessageNodeEntity(ownerNodeId, conversationId, 1, JsonInstant.encodeToString(listOf(ownerMessage)), 0),
            ),
        )
        room.conversationModelContextDao().insertOnce(
            listOf(
                ConversationModelContextEntity(
                    ownerMessageId = ownerMessageId,
                    ownerNodeId = ownerNodeId,
                    anchorNodeId = anchorNodeId,
                    anchorMessageId = anchorMessageId,
                    content = content,
                ),
            ),
        )
        room.close()
        val source = context.getDatabasePath(sourceName)
        val archive = modernArchive(source, emptyMap())
        ZipFile(archive).use { zip ->
            val manifest = JsonInstant.decodeFromString<DurableBackupManifest>(
                zip.getInputStream(zip.getEntry("backup_manifest")).bufferedReader().readText(),
            )
            assertEquals("rikkahub-durable-v5", manifest.version)
            assertTrue(manifest.entries.any { it.path == "measix_pilot.db" })
            assertFalse(manifest.entries.any { it.path.contains("disclosure") || it.path.contains("model_context") })
        }

        service.stageRestore(archive, BackupSelection(true, true))
        PendingBackupRestore.bootstrapBeforeDatabaseOpen(context)
        val restored = Room.databaseBuilder(context, AppDatabase::class.java, "measix_pilot")
            .allowMainThreadQueries()
            .build()
        val entries = restored.conversationModelContextDao().getEntriesOfConversation(conversationId)
        assertEquals(listOf(ownerMessageId), entries.map { it.ownerMessageId })
        assertEquals(content, entries.single().content)
        restored.close()
        context.deleteDatabase(sourceName)
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
        val room = Room.databaseBuilder(context, AppDatabase::class.java, file.absolutePath)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
            .allowMainThreadQueries().build()
        try { room.openHelper.writableDatabase } finally { room.close() }
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.version = version
        db.execSQL("CREATE TABLE marker(value TEXT NOT NULL)")
        db.execSQL("INSERT INTO marker VALUES(?)", arrayOf(marker))
        artifactPath?.let { insertArtifact(db, it) }
        db.close()
    }

    private fun insertArtifact(db: SQLiteDatabase, path: String) {
        db.execSQL("INSERT INTO artifact(folder, relative_path, display_name, mime_type, size_bytes, created_at, updated_at, state, origin) " +
            "VALUES('upload', ?, 'fixture', 'text/plain', 1, 1, 1, 'ACTIVE', 'USER')", arrayOf(path))
    }

    @Test
    fun `current schema marker cannot publish invalid schema or transcript over live data`() = runTest {
        val live = context.getDatabasePath("measix_pilot")
        createDatabase(live, "live")
        for (fault in listOf("missing_unique_index", "missing_column", "invalid_transcript")) {
            val source = File(work, "$fault.sqlite")
            createDatabase(source, "invalid")
            SQLiteDatabase.openDatabase(source.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                when (fault) {
                    "missing_unique_index" -> db.execSQL("DROP INDEX index_tool_execution_turn_id_local_call_id")
                    "missing_column" -> db.execSQL("ALTER TABLE ConversationEntity RENAME COLUMN title TO damaged_title")
                    else -> {
                        db.execSQL("INSERT INTO ConversationEntity(id, assistant_id, title, create_at, update_at, suggestions, " +
                            "is_pinned, custom_system_prompt, mode_injection_ids, workspace_cwd, tags, folder_id, parent_conversation_id) " +
                            "VALUES('c','a','t',1,1,'[]',0,'','[]','','','',NULL)")
                        db.execSQL("INSERT INTO message_node(id, conversation_id, node_index, messages, select_index, transcript_schema) " +
                            "VALUES('n','c',0,?,0,3)", arrayOf("""[{"role":"assistant","parts":[{"type":"text","text":"no step"}]}]"""))
                    }
                }
            }
            val failure = runCatching {
                service.stageRestore(modernArchive(source, emptyMap()), BackupSelection(true, true))
            }.exceptionOrNull()
            assertTrue("$fault must fail staging", failure != null)
            if (fault != "invalid_transcript") {
                assertTrue("$fault must reach Room schema validation: $failure",
                    failure?.message.orEmpty().contains("invalid schema"))
            }
            assertEquals("live", databaseMarker(live))
            assertFalse(PendingBackupRestore.pendingDir(context).exists())
            assertFalse(PendingBackupRestore.stagingDir(context).exists())
        }
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
