package net.weero.measix.pilot.data.sync

import android.content.Context
import android.content.ContextWrapper
import androidx.core.net.toUri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.configuration.EnterpriseAuthority
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.ai.mcp.McpCatalogStore
import net.weero.measix.pilot.data.configuration.AssistantUsagePreferences
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.configuration.UsageValue
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.datastore.UserSettingsMigration
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.db.RoomDatabaseTransactionRunner
import net.weero.measix.pilot.data.db.createAppDatabase
import net.weero.measix.pilot.data.db.entity.ArtifactEntity
import net.weero.measix.pilot.data.db.entity.ConversationEntity
import net.weero.measix.pilot.data.db.entity.GenMediaEntity
import net.weero.measix.pilot.data.db.entity.MessageNodeEntity
import net.weero.measix.pilot.data.db.fts.MessageFtsManager
import net.weero.measix.pilot.data.files.ArtifactDeleteResult
import net.weero.measix.pilot.data.files.ArtifactPayloadStore
import net.weero.measix.pilot.data.files.ArtifactSettingsCoordinator
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.imggen.GeneratedMediaStore
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.repository.GenMediaRepository
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

/** Production Room/FTS, file owners, archive creation and cold restore; no platform backend. */
@RunWith(AndroidJUnit4::class)
class PersonalBackupGraphAndroidTest {
    private val enterprise = ConfigurationScope.Enterprise(EnterpriseAuthority("local:backup", "deployment"), "user")
    private val personal = ConfigurationScope.Personal
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var scope: AppScope
    private lateinit var settings: SettingsStore
    private lateinit var preferences: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
    private lateinit var catalogs: McpCatalogStore
    private lateinit var room: AppDatabase
    private lateinit var artifacts: ArtifactStore
    private lateinit var media: GeneratedMediaStore
    private lateinit var archiveService: BackupArchiveService

    @Before
    fun setUp() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        root = File(base.cacheDir, "personal-backup-${Uuid.random()}").apply { mkdirs() }
        context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
            override fun getDatabasePath(name: String) =
                (if (File(name).isAbsolute) File(name) else File(root, "databases/$name")).also { it.parentFile?.mkdirs() }
        }
        scope = AppScope(Dispatchers.IO)
        preferences = PreferenceDataStoreFactory.create(scope = scope, migrations = listOf(UserSettingsMigration()),
            produceFile = { File(root, "settings.preferences_pb") })
        settings = SettingsStore(context, scope, dataStore = preferences)
        catalogs = McpCatalogStore(PreferenceDataStoreFactory.create(scope = scope,
            produceFile = { File(root, "catalogs.preferences_pb") }), scope, settings)
        settings.updateLocal { it.copy(assistants = listOf(net.weero.measix.pilot.data.model.Assistant(name = "Backup user"))) }
        openOwners()
    }

    @After
    fun tearDown() = runBlocking {
        room.close()
        scope.coroutineContext[Job]!!.cancelAndJoin()
        root.deleteRecursively()
        Unit
    }

    @Test
    fun exportExcludesEnterpriseAndRestoreKeepsLatestGraphAcrossRetry() = runBlocking {
        val userId = conversation(personal, "personal-before")
        conversation(enterprise, "enterprise-secret-before")
        artifact(1, personal, "upload/personal.txt", "personal")
        artifact(20, enterprise, "upload/enterprise.txt", "enterprise-secret-file")
        room.openHelper.writableDatabase.execSQL("CREATE TABLE private_unknown(secret TEXT)")
        room.openHelper.writableDatabase.execSQL("INSERT INTO private_unknown VALUES ('enterprise-secret-unknown')")
        file("upload/unindexed-private.txt").writeText("enterprise-secret-orphan")
        val archive = archiveService.prepare(BackupSelection(true, true))
        val archiveDb = File(context.cacheDir, "export.sqlite")
        ZipFile(archive).use { zip ->
            val entries = zip.entries().asSequence().map { it.name }.toSet()
            assertTrue("upload/personal.txt" in entries)
            assertFalse("upload/enterprise.txt" in entries)
            assertFalse("upload/unindexed-private.txt" in entries)
            archiveDb.writeBytes(zip.getInputStream(zip.getEntry(BackupArchiveService.DATABASE_ENTRY)).readBytes())
        }
        assertFalse(archiveDb.readBytes().toString(Charsets.UTF_8).contains("enterprise-secret"))
        val exported = createAppDatabase(context, archiveDb.absolutePath)
        try {
            listOf("ConversationEntity", "MemoryEntity", "conversation_folder", "favorites").forEach {
                assertEquals(it, 1L, scalar(exported, "SELECT COUNT(*) FROM $it"))
            }
            assertEquals(1L, scalar(exported, "SELECT COUNT(*) FROM message_fts WHERE message_fts MATCH 'personal'"))
            assertEquals(0L, scalar(exported, "SELECT COUNT(*) FROM sqlite_master WHERE name='private_unknown'"))
        } finally { exported.close() }
        archiveService.stageRestore(archive, BackupSelection(true, true))
        val pending = File(PendingBackupRestore.pendingDir(context), BackupArchiveService.DATABASE_ENTRY)
        val stagedBytes = pending.readBytes()
        room.openHelper.writableDatabase.execSQL("UPDATE ConversationEntity SET title='personal-late' WHERE id=?", arrayOf(userId))
        val lateId = conversation(enterprise, "enterprise-late")
        artifact(700, personal, "upload/shared-late.txt", "retained-shared")
        val before = settings.snapshotUserDocument()
        val withOverride = before.copy(preferences = before.preferences.withAssistantUsage(enterprise,
            AssistantUsagePreferences(before.configuration.assistants.first().id,
                background = UsageValue(file("upload/shared-late.txt").toUri().toString()))))
        preferences.edit { it[SettingsStore.USER_SETTINGS] = JsonInstant.encodeToString(withOverride) }
        closeWithNativeWalCrashImage(lateId)
        val failure = runCatching {
            PendingBackupRestore.applyBeforeDatabaseOpen(context, readSettings = settings::snapshotUserDocument) {
                if (it == "upload") error("injected publication interruption")
            }
        }.exceptionOrNull()
        assertEquals("injected publication interruption", failure?.message)
        assertArrayEquals(stagedBytes, pending.readBytes())
        openOwners()
        assertEquals("personal-late", title(userId))
        room.openHelper.writableDatabase.execSQL("UPDATE ConversationEntity SET title='enterprise-after-retry' WHERE id=?", arrayOf(lateId))
        room.close()
        PendingBackupRestore.applyBeforeDatabaseOpen(context, readSettings = settings::snapshotUserDocument)
        openOwners()
        PendingBackupRestore.restoreSettingsIfPending(context, artifacts, catalogs, JsonInstant)
        assertEquals("personal-before", title(userId))
        assertEquals("enterprise-after-retry", title(lateId))
        assertEquals("enterprise-secret-file", file("upload/enterprise.txt").readText())
        assertEquals("retained-shared", file("upload/shared-late.txt").readText())
        assertFalse(file("upload/unindexed-private.txt").exists())
        assertEquals(withOverride.preferences.assistantUsage(enterprise, before.configuration.assistants.first().id),
            settings.snapshotUserDocument().preferences.assistantUsage(enterprise, before.configuration.assistants.first().id))
        assertTrue(scalar(room, "SELECT seq FROM sqlite_sequence WHERE name='artifact'") >= 700)
        listOf("ConversationEntity", "MemoryEntity", "conversation_folder", "favorites", "message_fts").forEach {
            assertEquals(it, 3L, scalar(room, "SELECT COUNT(*) FROM $it"))
        }
        PendingBackupRestore.complete(context)
    }

    @Test
    fun deletedChatAttachmentsDoNotPreventPersonalOrEnterpriseRestore() = runBlocking {
        listOf(personal, enterprise).forEachIndexed { index, realm ->
            val path = "upload/deleted-$index.png"
            artifact(index + 1L, realm, path, "image")
            conversation(realm, "retained transcript", file(path).toUri().toString())
            assertTrue(artifacts.deleteUserRequested(realm, index + 1L) is ArtifactDeleteResult.Completed)
        }
        assertEquals(0L, scalar(room, "SELECT COUNT(*) FROM artifact"))
        val archive = archiveService.prepare(BackupSelection(true, true))
        archiveService.stageRestore(archive, BackupSelection(true, true))
        room.close()
        PendingBackupRestore.applyBeforeDatabaseOpen(context, readSettings = settings::snapshotUserDocument)
        openOwners()
        artifacts.ensureReferenceProjection()
        assertEquals(2L, scalar(room, "SELECT COUNT(*) FROM message_node"))
        assertEquals(0L, scalar(room, "SELECT COUNT(*) FROM artifact_reference"))
    }

    @Test
    fun retainedEnterpriseDeletionReceiptsReachTheirOriginalOwner() = runBlocking {
        conversation(personal, "personal")
        val archive = archiveService.prepare(BackupSelection(true, true))
        artifact(30, enterprise, "upload/deleting.txt", "pending delete")
        room.openHelper.writableDatabase.execSQL("UPDATE artifact SET state='DELETING' WHERE id=30")
        artifact(31, enterprise, "upload/creating.txt", "interrupted creation")
        val staging = ArtifactPayloadStore.STAGING_FOLDER + "/creating-token"
        assertTrue(file("upload/creating.txt").renameTo(file(staging)))
        room.openHelper.writableDatabase.execSQL("UPDATE artifact SET state='CREATING',payload_token='creating-token' WHERE id=31")
        artifact(32, enterprise, "upload/creation-cleanup.txt", "already removed")
        assertTrue(file("upload/creation-cleanup.txt").delete())
        room.openHelper.writableDatabase.execSQL("UPDATE artifact SET state='CREATING',payload_token='removed-token' WHERE id=32")
        val before = settings.snapshotUserDocument()
        val current = before.copy(preferences = before.preferences.withAssistantUsage(enterprise,
            AssistantUsagePreferences(before.configuration.assistants.first().id,
                background = UsageValue(file("upload/deleting.txt").toUri().toString()))))
        preferences.edit { it[SettingsStore.USER_SETTINGS] = JsonInstant.encodeToString(current) }
        room.genMediaDao().insert(GenMediaEntity(40, "images/media.png", "model", "prompt", 1, scope = enterprise))
        file("images/media.png.deleting").writeText("retained media receipt")
        file("images/removed.png.deleting").writeText("rowless receipt")
        archiveService.stageRestore(archive, BackupSelection(true, true))
        room.close()
        PendingBackupRestore.applyBeforeDatabaseOpen(context, readSettings = settings::snapshotUserDocument)
        openOwners()
        assertEquals("retained media receipt", file("images/media.png.deleting").readText())
        assertEquals("rowless receipt", file("images/removed.png.deleting").readText())
        assertEquals("interrupted creation", file(staging).readText())
        assertEquals(1L, scalar(room, "SELECT COUNT(*) FROM artifact WHERE state='DELETING'"))
        PendingBackupRestore.restoreSettingsIfPending(context, artifacts, catalogs, JsonInstant)
        artifacts.reconcileStartup()
        assertEquals(0L, scalar(room, "SELECT COUNT(*) FROM artifact"))
        assertNull(settings.snapshotUserDocument().preferences.assistantUsage(enterprise,
            before.configuration.assistants.first().id)?.background?.value)
        assertFalse(file("upload/deleting.txt").exists())
        assertFalse(file(staging).exists())
        media.reconcile()
        assertEquals("retained media receipt", file("images/media.png").readText())
        assertFalse(file("images/media.png.deleting").exists())
        assertFalse(file("images/removed.png.deleting").exists())
    }

    @Test
    fun equalBytesCannotMergeIndependentMediaOwners() = assertCollision("images/shared.png", false)

    @Test
    fun invalidLiveMediaPathsKeepPendingAndLiveDataUntilRetry() = runBlocking {
        val id = conversation(personal, "archived")
        val archive = archiveService.prepare(BackupSelection(true, true))
        archiveService.stageRestore(archive, BackupSelection(true, true))
        val pending = File(PendingBackupRestore.pendingDir(context), BackupArchiveService.DATABASE_ENTRY)
        val staged = pending.readBytes()
        room.openHelper.writableDatabase.execSQL("UPDATE ConversationEntity SET title='live' WHERE id=?", arrayOf(id))
        val images = file("images")
        assertFalse(images.exists())
        images.writeText("unreadable directory sentinel")
        room.close()
        try {
            PendingBackupRestore.applyBeforeDatabaseOpen(context, readSettings = settings::snapshotUserDocument)
            fail("invalid media directory accepted")
        } catch (error: IllegalStateException) {
            assertEquals("Live media recovery directory is not a directory", error.message)
        }
        assertArrayEquals(staged, pending.readBytes())
        assertEquals("unreadable directory sentinel", images.readText())
        openOwners()
        assertEquals("live", title(id))
        room.close()
        assertTrue(images.delete())
        for (suffix in listOf(GeneratedMediaStore.DELETING_SUFFIX, GeneratedMediaStore.PENDING_SUFFIX)) {
            val receipt = file("images/broken.png$suffix").apply { assertTrue(mkdir()) }
            val original = File(receipt, "original").apply { writeText("unresolved owner data") }
            try {
                PendingBackupRestore.applyBeforeDatabaseOpen(context, readSettings = settings::snapshotUserDocument)
                fail("invalid recovery receipt accepted")
            } catch (error: IllegalStateException) {
                assertEquals("Live media recovery receipt is not a file", error.message)
            }
            assertArrayEquals(staged, pending.readBytes())
            assertEquals("unresolved owner data", original.readText())
            openOwners()
            assertEquals("live", title(id))
            room.close()
            assertTrue(original.delete())
            assertTrue(receipt.delete())
        }
        PendingBackupRestore.applyBeforeDatabaseOpen(context, readSettings = settings::snapshotUserDocument)
        openOwners()
        assertEquals("archived", title(id))
    }

    @Test
    fun equalBytesCannotMergeMediaAndArtifactOwners() = assertCollision("images/shared.png", true)

    @Test
    fun nonCanonicalPathCannotAliasRetainedPayload() = assertCollision("images/./shared.png", true)

    private fun assertCollision(enterprisePath: String, asArtifact: Boolean) = runBlocking {
        room.genMediaDao().insert(GenMediaEntity(1, "images/shared.png", "model", "personal", 1))
        file("images/shared.png").writeText("same bytes")
        val archive = archiveService.prepare(BackupSelection(true, true))
        room.openHelper.writableDatabase.execSQL("DELETE FROM GenMediaEntity")
        if (asArtifact) artifact(2, enterprise, enterprisePath, "same bytes")
        else room.genMediaDao().insert(GenMediaEntity(2, enterprisePath, "model", "enterprise", 1, scope = enterprise))
        archiveService.stageRestore(archive, BackupSelection(true, true))
        room.close()
        val failure = runCatching {
            PendingBackupRestore.applyBeforeDatabaseOpen(context, readSettings = settings::snapshotUserDocument)
        }.exceptionOrNull()
        assertNotNull(failure)
        openOwners()
        assertEquals(1L, scalar(room, if (asArtifact) "SELECT COUNT(*) FROM artifact" else "SELECT COUNT(*) FROM GenMediaEntity WHERE id=2"))
        assertEquals("same bytes", file("images/shared.png").readText())
        assertFalse(File(PendingBackupRestore.pendingDir(context), ".apply_started").exists())
    }

    /** Preserve a real native WAL crash image; this does not simulate an Android process kill. */
    private fun closeWithNativeWalCrashImage(walOnlyConversation: String) {
        val live = context.getDatabasePath("measix_pilot")
        val wal = File(live.path + "-wal")
        assertTrue("Fixture must contain an uncheckpointed WAL", wal.isFile && wal.length() > 32)
        val mainImage = File(context.cacheDir, "crash-main.sqlite")
        val walImage = File(context.cacheDir, "crash-wal")
        live.copyTo(mainImage)
        wal.copyTo(walImage)
        android.database.sqlite.SQLiteDatabase.openDatabase(mainImage.absolutePath, null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { mainOnly ->
            val hasConversations = mainOnly.rawQuery("SELECT 1 FROM sqlite_master WHERE name='ConversationEntity'", null).use { it.moveToFirst() }
            if (hasConversations) mainOnly.rawQuery("SELECT COUNT(*) FROM ConversationEntity WHERE id=?",
                arrayOf(walOnlyConversation)).use { assertTrue(it.moveToFirst()); assertEquals(0L, it.getLong(0)) }
        }
        room.close()
        mainImage.copyTo(live, overwrite = true)
        listOf("-wal", "-shm").forEach { suffix ->
            val sidecar = File(live.path + suffix)
            check(!sidecar.exists() || sidecar.delete())
        }
        walImage.copyTo(File(live.path + "-wal"))
    }

    private fun openOwners() {
        room = createAppDatabase(context, "measix_pilot")
        artifacts = ArtifactStore(ArtifactPayloadStore(context), room.artifactDao(), room.artifactReferenceDao(),
            room.systemMetaDao(), room.conversationDao(), room.messageNodeDao(), ArtifactSettingsCoordinator(settings),
            RoomDatabaseTransactionRunner(room))
        media = GeneratedMediaStore(context.filesDir, GenMediaRepository(room.genMediaDao()), artifacts)
        archiveService = BackupArchiveService(context, settings, catalogs, JsonInstant, room, artifacts, media)
    }

    private suspend fun conversation(realm: ConfigurationScope, text: String, image: String? = null): String {
        val id = Uuid.random()
        val nodeId = Uuid.random()
        val message = UIMessage.user(text).let { if (image == null) it else it.copy(parts = it.parts + UIMessagePart.Image(image)) }
        val assistantId = settings.snapshotLocal().assistants.first().id.toString()
        val folderId = Uuid.random().toString()
        room.folderDao().insert(net.weero.measix.pilot.data.db.entity.FolderEntity(folderId, assistantId, text, createAt = 1, scope = realm))
        room.memoryDao().insertMemory(net.weero.measix.pilot.data.db.entity.MemoryEntity(assistantId = assistantId, content = text, scope = realm))
        room.conversationDao().insert(ConversationEntity(id.toString(), assistantId,
            text, 1, 1, "[]", false, folderId = folderId, scope = realm))
        room.messageNodeDao().insertAll(listOf(MessageNodeEntity(nodeId.toString(), id.toString(), 0,
            JsonInstant.encodeToString(listOf(message)), 0)))
        room.favoriteDao().upsert(net.weero.measix.pilot.data.db.entity.FavoriteEntity(
            id = Uuid.random().toString(), type = "node", refKey = "node:$id:$nodeId",
            refJson = JsonInstant.encodeToString(net.weero.measix.pilot.data.model.NodeFavoriteRef(id, nodeId)),
            snapshotJson = "", createdAt = 1, updatedAt = 1, scope = realm))
        MessageFtsManager(room).reindexNodesInTransaction(id.toString(), text, 1, listOf(MessageNode(nodeId, listOf(message))))
        return id.toString()
    }

    private suspend fun artifact(id: Long, realm: ConfigurationScope, path: String, content: String) {
        file(path).writeText(content)
        room.artifactDao().insert(ArtifactEntity(id, path.substringBefore('/'), path, File(path).name,
            "application/octet-stream", content.length.toLong(), 1, 1, scope = realm))
    }

    private fun file(path: String) = File(context.filesDir, path).also { it.parentFile?.mkdirs() }
    private fun scalar(db: AppDatabase, sql: String): Long = db.openHelper.writableDatabase.query(sql).use {
        check(it.moveToFirst()); it.getLong(0)
    }
    private fun title(id: String): String = room.openHelper.writableDatabase.query(
        "SELECT title FROM ConversationEntity WHERE id=?", arrayOf(id)).use { check(it.moveToFirst()); it.getString(0) }
}
