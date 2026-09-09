package net.weero.measix.pilot.data.sync

import me.rerere.common.configuration.ConfigurationReference

import android.content.Context
import androidx.core.net.toUri
import net.weero.measix.pilot.data.model.Avatar
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.mockk.coEvery
import io.mockk.mockk
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.ai.mcp.McpCatalogStore
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.db.APP_DATABASE_VERSION
import net.weero.measix.pilot.data.db.createAppDatabase
import net.weero.measix.pilot.data.db.entity.ConversationModelContextEntity
import net.weero.measix.pilot.data.db.fts.MessageFtsManager
import net.weero.measix.pilot.data.db.migrations.Migration_1_2
import net.weero.measix.pilot.data.db.migrations.Migration_2_3
import net.weero.measix.pilot.data.db.migrations.Migration_3_4
import net.weero.measix.pilot.data.db.migrations.Migration_4_5
import net.weero.measix.pilot.data.db.migrations.Migration_5_6
import net.weero.measix.pilot.data.db.migrations.Migration_6_7
import net.weero.measix.pilot.data.db.migrations.Migration_7_8
import net.weero.measix.pilot.data.db.migrations.Migration_8_9
import net.weero.measix.pilot.data.db.migrations.Migration_9_10
import net.weero.measix.pilot.data.db.migrations.Migration_10_11
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.imggen.GeneratedMediaStore
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.service.ConversationDisclosureSnapshotService
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

@RunWith(AndroidJUnit4::class)
class BackupRestoreMigrationIntegrationTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val sourceName = "restore-source-v9"
    private val conversationId = Uuid.parse("00000000-0000-0000-0000-000000000301")
    private val assistantId = ConfigurationReference.parse("00000000-0000-0000-0000-000000000302")
    private val anchorNodeId = Uuid.parse("00000000-0000-0000-0000-000000000303")
    private val ownerNodeId = Uuid.parse("00000000-0000-0000-0000-000000000304")
    private val anchorMessageId = Uuid.parse("00000000-0000-0000-0000-000000000305")
    private val ownerMessageId = Uuid.parse("00000000-0000-0000-0000-000000000306")
    private lateinit var archive: File

    @Before
    fun setUp() {
        PendingBackupRestore.pendingDir(context).parentFile?.deleteRecursively()
        context.deleteDatabase("measix_pilot")
        context.deleteDatabase(sourceName)
        archive = File(context.cacheDir, "restore-v9-${System.nanoTime()}.zip")
    }

    @After
    fun tearDown() {
        archive.delete()
        PendingBackupRestore.pendingDir(context).parentFile?.deleteRecursively()
        context.deleteDatabase("measix_pilot")
        context.deleteDatabase(sourceName)
    }

    @Test
    fun durableV4Db8RestoreUpgradesStagingToV11AndLoadsThroughRepository() = assertLegacyRestorePreservesMessages(8)

    @Test
    fun durableV4Db9RestoreUpgradesStagingToV11AndLoadsThroughRepository() = assertLegacyRestorePreservesMessages(9)

    private fun assertLegacyRestorePreservesMessages(sourceVersion: Int) = runBlocking {
        val anchor = UIMessage.user("preserved request").copy(id = anchorMessageId)
        val owner = UIMessage(
            id = ownerMessageId,
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("preserved answer")),
        )
        migrationHelper.createDatabase(sourceName, sourceVersion).use { db ->
            db.execSQL(
                "INSERT INTO ConversationEntity(id, assistant_id, title, create_at, update_at, suggestions, " +
                    "is_pinned, custom_system_prompt, mode_injection_ids, workspace_cwd, tags, folder_id, " +
                    "parent_conversation_id) VALUES (?,?,?,?,?,'[]',0,'','[]','','','',NULL)",
                arrayOf<Any?>(conversationId.toString(), assistantId.toString(), "restored", 1L, 1L),
            )
            db.execSQL(
                "INSERT INTO message_node(id, conversation_id, node_index, messages, select_index) VALUES (?,?,?,?,0)",
                arrayOf<Any?>(anchorNodeId.toString(), conversationId.toString(), 0, JsonInstant.encodeToString(listOf(anchor))),
            )
            db.execSQL(
                "INSERT INTO message_node(id, conversation_id, node_index, messages, select_index) VALUES (?,?,?,?,0)",
                arrayOf<Any?>(ownerNodeId.toString(), conversationId.toString(), 1, JsonInstant.encodeToString(listOf(owner))),
            )
        }
        createDurableArchive(context.getDatabasePath(sourceName), archive)
        val catalogStore = mockk<McpCatalogStore>(relaxed = true)
        coEvery { catalogStore.snapshotForBackup(any()) } returns emptyList()
        val service = BackupArchiveService(
            context = context,
            settingsStore = mockk<SettingsStore>(relaxed = true),
            mcpCatalogStore = catalogStore,
            json = JsonInstant,
            database = mockk(),
            artifactStore = mockk(),
            generatedMediaStore = mockk<GeneratedMediaStore>(),
        )

        service.stageRestore(archive, BackupSelection(true, true))
        // The pre-v11 aggregate is carried to the current schema inside staging/pending —
        // before any swap — by the same Room chain, and its transcripts are already V3.
        val pendingDb = File(PendingBackupRestore.pendingDir(context), "measix_pilot.db")
        SQLiteDatabase.openDatabase(pendingDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { staged ->
            assertEquals(APP_DATABASE_VERSION, staged.version)
            staged.rawQuery("SELECT transcript_schema FROM message_node", null).use { c ->
                while (c.moveToNext()) assertEquals(3, c.getInt(0))
            }
        }
        PendingBackupRestore.applyBeforeDatabaseOpen(context, readSettings = { net.weero.measix.pilot.data.datastore.UserSettingsDocument.empty() })
        val database = createAppDatabase(context, "measix_pilot")
        try {
            assertEquals(APP_DATABASE_VERSION, database.openHelper.readableDatabase.version)
            assertTrue(database.conversationModelContextDao().getEntriesOfConversation(conversationId.toString()).isEmpty())
            val artifactStore = mockk<ArtifactStore>(relaxed = true)
            val repository = ConversationRepository(
                conversationDAO = database.conversationDao(),
                messageNodeDAO = database.messageNodeDao(),
                favoriteDAO = database.favoriteDao(),
                database = database,
                messageFtsManager = mockk<MessageFtsManager>(relaxed = true),
                turnExecutionDAO = database.turnExecutionDao(),
                toolExecutionDAO = database.toolExecutionDao(),
                modelContextDAO = database.conversationModelContextDao(),
                artifactStore = artifactStore,
            )
            val restored = repository.getConversationSnapshotById(conversationId)
            assertNotNull(restored)
            assertEquals(listOf("preserved request", "preserved answer"), restored!!.currentMessages().map {
                // v11 transcript carries a leading Step part on the assistant message; the assertion
                // targets the preserved Text content, so select the Text part rather than the sole part.
                it.parts.filterIsInstance<UIMessagePart.Text>().single().text
            })
            val content = ConversationDisclosureSnapshotService.render(
                ConversationDisclosureSnapshotService.Candidate(
                    assistant = Settings().assistants.first(),
                    allAssistants = Settings().assistants,
                    memories = emptyList(),
                ),
            )
            database.conversationModelContextDao().insertOnce(
                listOf(
                    ConversationModelContextEntity(
                        ownerNodeId = ownerNodeId.toString(),
                        ownerMessageId = ownerMessageId.toString(),
                        anchorNodeId = anchorNodeId.toString(),
                        anchorMessageId = anchorMessageId.toString(),
                        content = content,
                    ),
                ),
            )
            assertEquals(content, repository.getConversationSnapshotById(conversationId)!!
                .modelContextEntries.single().content)
        } finally {
            database.close()
        }
    }

    /**
     * v4 + db10 round-trip 保留 context rows 的设备级证据：备份一个已含 context 行的 v10
     * 数据库，经生产 stageRestore/bootstrap 换回，再用生产 migration 链打开，canonical bytes 必须逐字保留。
     */
    @Test
    fun durableV4Db10RestorePreservesCanonicalContextRows() = runBlocking {
        val content = ConversationDisclosureSnapshotService.render(
            ConversationDisclosureSnapshotService.Candidate(
                assistant = Settings().assistants.first(),
                allAssistants = Settings().assistants,
                memories = listOf(
                    net.weero.measix.pilot.data.model.AssistantMemory(
                        id = 1,
                        content = "sentinel memory",
                    ),
                ),
            ),
        )
        val sourceV10 = "restore-source-v10"
        context.deleteDatabase(sourceV10)
        migrationHelper.createDatabase(sourceV10, 9).use { }
        val migrated = migrationHelper.runMigrationsAndValidate(
            sourceV10, 10, true,
            Migration_1_2, Migration_2_3, Migration_3_4, Migration_4_5, Migration_5_6,
            Migration_6_7, Migration_7_8, Migration_8_9, Migration_9_10,
        )
        val anchor = UIMessage.user("request with context").copy(id = anchorMessageId)
        val owner = UIMessage(
            id = ownerMessageId,
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("answer with context")),
        )
        migrated.execSQL(
            "INSERT INTO ConversationEntity(id, assistant_id, title, create_at, update_at, suggestions, " +
                "is_pinned, custom_system_prompt, mode_injection_ids, workspace_cwd, tags, folder_id, " +
                "parent_conversation_id) VALUES (?,?,?,?,?,'[]',0,'','[]','','','',NULL)",
            arrayOf<Any?>(conversationId.toString(), assistantId.toString(), "with context", 1L, 1L),
        )
        migrated.execSQL(
            "INSERT INTO message_node(id, conversation_id, node_index, messages, select_index) VALUES (?,?,?,?,0)",
            arrayOf<Any?>(anchorNodeId.toString(), conversationId.toString(), 0, JsonInstant.encodeToString(listOf(anchor))),
        )
        migrated.execSQL(
            "INSERT INTO message_node(id, conversation_id, node_index, messages, select_index) VALUES (?,?,?,?,0)",
            arrayOf<Any?>(ownerNodeId.toString(), conversationId.toString(), 1, JsonInstant.encodeToString(listOf(owner))),
        )
        migrated.execSQL(
            "INSERT INTO conversation_model_context(owner_message_id, owner_node_id, anchor_node_id, " +
                "anchor_message_id, content) VALUES (?,?,?,?,?)",
            arrayOf<Any?>(
                ownerMessageId.toString(), ownerNodeId.toString(), anchorNodeId.toString(),
                anchorMessageId.toString(), content,
            ),
        )
        migrated.close()

        val v10Archive = File(context.cacheDir, "restore-v10-${System.nanoTime()}.zip")
        try {
            createDurableArchive(context.getDatabasePath(sourceV10), v10Archive)
            val catalogStore = mockk<McpCatalogStore>(relaxed = true)
            coEvery { catalogStore.snapshotForBackup(any()) } returns emptyList()
            BackupArchiveService(
                context = context,
                settingsStore = mockk<SettingsStore>(relaxed = true),
                mcpCatalogStore = catalogStore,
                json = JsonInstant,
                database = mockk(),
                artifactStore = mockk(),
                generatedMediaStore = mockk<GeneratedMediaStore>(),
            ).stageRestore(v10Archive, BackupSelection(true, true))
            // The v10 aggregate is upgraded to v11 in staging before the swap; the disclosure
            // context rows are durable content and must survive the transcript conversion byte-for-byte.
            val pendingDb = File(PendingBackupRestore.pendingDir(context), "measix_pilot.db")
            SQLiteDatabase.openDatabase(pendingDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { staged ->
                assertEquals(APP_DATABASE_VERSION, staged.version)
                staged.rawQuery("SELECT transcript_schema FROM message_node", null).use { c ->
                    while (c.moveToNext()) assertEquals(3, c.getInt(0))
                }
            }
            PendingBackupRestore.applyBeforeDatabaseOpen(context, readSettings = { net.weero.measix.pilot.data.datastore.UserSettingsDocument.empty() })

            val database = createAppDatabase(context, "measix_pilot")
            try {
                assertEquals(APP_DATABASE_VERSION, database.openHelper.readableDatabase.version)
                val repository = ConversationRepository(
                    conversationDAO = database.conversationDao(),
                    messageNodeDAO = database.messageNodeDao(),
                    favoriteDAO = database.favoriteDao(),
                    database = database,
                    messageFtsManager = mockk<MessageFtsManager>(relaxed = true),
                    turnExecutionDAO = database.turnExecutionDao(),
                    toolExecutionDAO = database.toolExecutionDao(),
                    modelContextDAO = database.conversationModelContextDao(),
                    artifactStore = mockk<ArtifactStore>(relaxed = true),
                )
                val loaded = repository.getConversationSnapshotById(conversationId)
                assertNotNull(loaded)
                assertEquals(listOf(content), loaded!!.modelContextEntries.map { it.content })
                assertEquals(
                    content,
                    database.conversationModelContextDao()
                        .findByOwner(ownerNodeId.toString(), ownerMessageId.toString())
                        ?.content,
                )
            } finally {
                database.close()
            }
        } finally {
            v10Archive.delete()
            context.deleteDatabase(sourceV10)
        }
    }

    @Test
    fun publishedV5ArchivePreservesMigratedArtifactColumnsAndReservedIds() = assertPublishedV5Restore("archive")

    @Test
    fun preparedV19RestoreSurvivesApplicationUpgrade() = assertPublishedV5Restore("prepared")

    @Test
    fun interruptedV19RestoreRollsBackBeforeMigratingItsOriginalInput() = assertPublishedV5Restore("interrupted")

    private fun assertPublishedV5Restore(entry: String) = runBlocking {
        migrationHelper.createDatabase(sourceName, 5).use { db ->
            db.execSQL("INSERT INTO managed_files(id,folder,relative_path,display_name,mime_type,size_bytes,created_at,updated_at) VALUES(9,'upload','upload/legacy.txt','legacy','text/plain',6,1,1)")
            db.execSQL("INSERT INTO managed_files(id,folder,relative_path,display_name,mime_type,size_bytes,created_at,updated_at) VALUES(123,'upload','upload/deleted.txt','deleted','text/plain',1,1,1)")
            db.execSQL("DELETE FROM managed_files WHERE id=123")
        }
        migrationHelper.runMigrationsAndValidate(sourceName, 11, true,
            Migration_5_6, Migration_6_7, Migration_7_8, Migration_8_9, Migration_9_10, Migration_10_11).use { db ->
            val columns = buildList {
                db.query("PRAGMA table_info(artifact)").use { while (it.moveToNext()) add(it.getString(1)) }
            }
            assertTrue(columns.indexOf("origin") < columns.indexOf("payload_token"))
        }
        val missing = File(context.filesDir, "upload/missing-legacy-asset.png").toUri().toString()
        val oldSettings = Settings().let { settings -> settings.copy(assistants = listOf(settings.assistants.first().copy(
            avatar = Avatar.Image(missing), background = missing,
            presetMessages = listOf(UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Image(missing)))),
        ))) }
        createDurableArchive(context.getDatabasePath(sourceName), archive, "rikkahub-durable-v5",
            mapOf("upload/legacy.txt" to "legacy".encodeToByteArray()), oldSettings)
        val pending = PendingBackupRestore.pendingDir(context)
        if (entry == "archive") {
            BackupArchiveService(context, mockk(), mockk(), JsonInstant, mockk(), mockk(), mockk())
                .stageRestore(archive, BackupSelection(true, true))
        } else {
            pending.mkdirs()
            ZipFile(archive).use { zip ->
                zip.entries().asSequence().forEach { item ->
                    val file = File(pending, item.name).also { it.parentFile?.mkdirs() }
                    zip.getInputStream(item).use { input -> file.outputStream().use(input::copyTo) }
                }
            }
            File(pending, BackupArchiveService.PREPARED_MARKER).writeText("1")
            File(pending, BackupArchiveService.AGGREGATE_MARKER).writeText("1")
        }
        val original = File(pending, BackupArchiveService.DATABASE_ENTRY).readBytes()
        val originalSettings = File(pending, BackupArchiveService.SETTINGS_ENTRY).readBytes()
        if (entry == "interrupted") {
            migrationHelper.createDatabase("measix_pilot", 11).close()
            val live = context.getDatabasePath("measix_pilot")
            val rollback = File(pending.parentFile, "rollback").apply { mkdirs() }
            assertTrue(live.renameTo(File(rollback, live.name)))
            assertTrue(File(pending, BackupArchiveService.DATABASE_ENTRY).renameTo(live))
            File(pending, ".apply_started").writeText("1")
        }
        PendingBackupRestore.applyBeforeDatabaseOpen(context,
            readSettings = { net.weero.measix.pilot.data.datastore.UserSettingsDocument.empty() })
        org.junit.Assert.assertArrayEquals(original, File(pending, BackupArchiveService.DATABASE_ENTRY).readBytes())
        org.junit.Assert.assertArrayEquals(originalSettings, File(pending, BackupArchiveService.SETTINGS_ENTRY).readBytes())
        var installedSettings: Settings? = null
        val settingsOwner = mockk<ArtifactStore>()
        coEvery { settingsOwner.restoreSettingsReferences(any()) } answers { firstArg<Settings>().also { installedSettings = it } }
        PendingBackupRestore.restoreSettingsIfPending(context, settingsOwner, mockk(relaxed = true), JsonInstant)
        val installedAssistant = requireNotNull(installedSettings).assistants.first()
        assertEquals(Avatar.Dummy, installedAssistant.avatar)
        assertEquals(null, installedAssistant.background)
        assertTrue(installedAssistant.presetMessages.isEmpty())
        val restored = createAppDatabase(context, "measix_pilot")
        try {
            val artifact = requireNotNull(restored.artifactDao().getById(9))
            assertEquals("USER", artifact.origin)
            assertEquals(null, artifact.payloadToken)
            assertEquals(net.weero.measix.pilot.data.configuration.ConfigurationScope.Personal, artifact.scope)
            restored.openHelper.writableDatabase.query("SELECT seq FROM sqlite_sequence WHERE name='artifact'").use {
                assertTrue(it.moveToFirst() && it.getLong(0) >= 123)
            }
            assertEquals("legacy", File(context.filesDir, "upload/legacy.txt").readText())
        } finally { restored.close() }
        if (entry == "interrupted") {
            val failure = runCatching { PendingBackupRestore.complete(context) { error("interrupted cleanup") } }.exceptionOrNull()
            assertEquals("interrupted cleanup", failure?.message)
            assertTrue(!pending.exists())
            PendingBackupRestore.applyBeforeDatabaseOpen(context, readSettings = { error("Retired restore must not read settings") })
            PendingBackupRestore.restoreSettingsIfPending(context, mockk(), mockk(), JsonInstant)
            PendingBackupRestore.complete(context)
            assertTrue(!File(pending.parentFile, "completed").exists())
            assertTrue(!File(pending.parentFile, "publication").exists())
        }
    }

    private fun createDurableArchive(database: File, target: File, version: String = "rikkahub-durable-v4", payloads: Map<String, ByteArray> = emptyMap(), settingsOverride: Settings? = null) {
        val settings = JsonInstant.encodeToString(settingsOverride ?: Settings(
            chatModelId = ConfigurationReference.parse("00000000-0000-0000-0000-000000000311"),
            fastModelId = ConfigurationReference.parse("00000000-0000-0000-0000-000000000312"),
            imageGenerationModelId = ConfigurationReference.parse("00000000-0000-0000-0000-000000000313"),
            compressModelId = ConfigurationReference.parse("00000000-0000-0000-0000-000000000314"),
        )).encodeToByteArray()
        val catalogs = "[]".encodeToByteArray()
        val dbBytes = database.readBytes()
        val entries = listOf(
            DurableBackupEntry("settings.json", settings.size.toLong(), sha256(settings)),
            DurableBackupEntry("mcp_catalogs.json", catalogs.size.toLong(), sha256(catalogs)),
            DurableBackupEntry("measix_pilot.db", dbBytes.size.toLong(), sha256(dbBytes)),
        ).plus(payloads.map { (path, bytes) -> DurableBackupEntry(path, bytes.size.toLong(), sha256(bytes)) }).sortedBy { it.path }
        val manifest = JsonInstant.encodeToString(DurableBackupManifest(version, entries)).encodeToByteArray()
        ZipOutputStream(FileOutputStream(target)).use { output ->
            listOf(
                "settings.json" to settings,
                "mcp_catalogs.json" to catalogs,
                "measix_pilot.db" to dbBytes,
                "backup_manifest" to manifest,
            ).plus(payloads.toList()).forEach { (name, bytes) ->
                output.putNextEntry(ZipEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
