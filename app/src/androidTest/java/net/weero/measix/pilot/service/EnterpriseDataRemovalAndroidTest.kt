package net.weero.measix.pilot.service

import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.coEvery
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessage
import me.rerere.common.configuration.ConfigurationReference
import net.weero.measix.pilot.AppScope
import net.weero.measix.pilot.data.ai.mcp.*
import net.weero.measix.pilot.data.configuration.*
import net.weero.measix.pilot.data.datastore.*
import net.weero.measix.pilot.data.db.*
import net.weero.measix.pilot.data.db.entity.*
import net.weero.measix.pilot.data.db.fts.MessageFtsManager
import net.weero.measix.pilot.data.enterprise.*
import net.weero.measix.pilot.data.files.*
import net.weero.measix.pilot.data.imggen.GeneratedMediaStore
import net.weero.measix.pilot.data.model.*
import net.weero.measix.pilot.data.repository.*
import net.weero.measix.pilot.service.portal.PortalDocumentRegistry
import net.weero.measix.pilot.service.runtime.*
import net.weero.measix.pilot.utils.JsonInstant
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

/** Actual scoped Room/DataStore/file consumers; no network or active provider worker is involved. */
@RunWith(AndroidJUnit4::class)
class EnterpriseDataRemovalAndroidTest {
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var appScope: AppScope
    private lateinit var room: AppDatabase
    private lateinit var settings: SettingsStore
    private lateinit var preferences: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
    private lateinit var catalogs: McpCatalogStore
    private lateinit var artifacts: ArtifactStore
    private lateinit var media: GeneratedMediaStore
    private lateinit var sessions: EnterpriseSessionController
    private lateinit var repository: ConversationRepository
    private lateinit var registry: ConversationRuntimeRegistry
    private lateinit var commands: ConversationCommandCoordinator
    private lateinit var gate: ApplicationRecoveryGate
    private lateinit var exit: EnterpriseExitService
    private lateinit var packet: EnterprisePackage
    private var failPayloadDeletion = false

    @Before fun setup() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        root = File(base.cacheDir, "enterprise-removal-${Uuid.random()}").apply { check(mkdirs()) }
        context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
            override fun getDatabasePath(name: String) =
                (if (File(name).isAbsolute) File(name) else File(root, "databases/$name")).also { it.parentFile?.mkdirs() }
        }
        packet = context.assets.open(LocalEnterpriseSource.EXAMPLE_ASSET).use(EnterprisePackageCodec::decode)
        openOwners()
        settings.updateLocal { it.copy(assistants = listOf(Assistant(name = "Shared user definition"))) }
        artifacts.ensureReferenceProjection()
    }

    @After fun cleanup() = runBlocking {
        appScope.coroutineContext[Job]!!.cancelAndJoin()
        room.close()
        check(root.deleteRecursively())
    }

    @Test fun completeScopeRemovalPreservesOtherPrincipalsAndReleasesDraftOwnership() = runBlocking {
        val retained = seedGraph()
        val selected = requireNotNull(sessions.readPresentation().selection)
        val assistant = settings.snapshotLocal().assistants.single { it.name == "Shared user definition" }
        val draftArtifact = artifacts.createFromBytes(packet.identity.scope, "draft".toByteArray(), "draft.txt", "text/plain", origin = ArtifactOrigin.USER)
        val draftId = Uuid.random()
        val request = ConversationOpenRequest.NewDraft(draftId, selected.access, assistant.id)
        val lease = commands.openForView(request) {
            ConversationDraft(Conversation.ofId(draftId, assistant.id, newConversation = true).copy(scope = packet.identity.scope), artifacts, listOf(draftArtifact))
        }
        try {
            assertTrue(artifacts.deleteUserRequested(packet.identity.scope, draftArtifact.entity.id) is ArtifactDeleteResult.Rejected)
            assertNull(repository.getConversationHeader(draftId))
            val removal = requireNotNull(exit.captureRequest())
            exit.clearExampleData(removal, packet.identity.scope)
            assertFalse(registry.isDraft(draftId))
            assertNull(artifacts.get(draftArtifact.entity.id))
            assertRemoved(retained)
        } finally { lease.close() }
    }

    @Test fun pendingMediaReceiptSurvivesOwnerRecreationAndFinishesThroughStartupRecovery() = runBlocking {
        val retained = seedGraph()
        failPayloadDeletion = true
        val request = requireNotNull(exit.captureRequest())
        try { exit.clearExampleData(request, packet.identity.scope); fail("Expected pending payload") }
        catch (_: IllegalStateException) { }
        assertEquals(EnterpriseSessionPhase.CLOSING, (sessions.state.value as EnterpriseState.Available).manifest.phase)
        assertEquals(EnterpriseExitReason.CLEAR_EXAMPLE_DATA, sessions.pendingExit()?.reason)
        assertTrue(file("images/target.png.deleting").isFile)
        assertTrue(room.genMediaDao().listInScope(packet.identity.scope).isEmpty())
        appScope.coroutineContext[Job]!!.cancelAndJoin()
        room.close()
        failPayloadDeletion = false
        openOwners(ready = false)
        ApplicationRecoveryCoordinator(
            appScope, settings, artifacts, media, repository,
            mockk { coEvery { recoverInterruptedRuns() } returns Unit; coEvery { recoverInterruptedTurns() } returns Unit },
            lazy { mockk { coEvery { performPendingDeletionCleanupDuringRecovery() } returns Unit } }, gate,
            recoverEnterpriseConfiguration = { sessions.recover() },
            completePendingEnterpriseExit = { exit.completeDuringRecovery() },
            recoveryDispatcher = Dispatchers.IO, startImmediately = false,
        ).recoverNow()
        assertEquals(ApplicationRecoveryState.Ready, gate.state.value)
        assertFalse(file("images/target.png.deleting").exists())
        assertRemoved(retained)
    }

    private data class Retained(
        val personalId: String,
        val otherId: String,
        val other: ConfigurationScope.Enterprise,
        val document: UserSettingsDocument,
        val catalogKeys: Set<McpCatalogKey>,
        val feed: EnterpriseFeedVersion,
    )

    private suspend fun seedGraph(): Retained {
        val target = packet.identity.scope
        val otherIdentity = packet.identity.copy(userId = "other-user")
        val other = otherIdentity.scope
        sessions.enrollLocal(otherIdentity, { otherIdentity }, { packet.copy(identity = otherIdentity) })
        val otherFeed = (sessions.state.value as EnterpriseState.Available).manifest.feeds.single()
        sessions.finishExit(sessions.beginExit(requireNotNull(sessions.captureExitRequest())))
        sessions.enrollLocal(packet.identity, { packet.identity }, { packet })
        val personalId = conversation(ConfigurationScope.Personal, "personalkeep")
        val otherId = conversation(other, "otherkeep")
        val targetId = conversation(target, "targetremove")
        conversation(target, "childremove", parentId = targetId)
        val assistant = settings.snapshotLocal().assistants.single { it.name == "Shared user definition" }.id
        room.folderDao().insert(FolderEntity(Uuid.random().toString(), assistant.toString(), "empty target", createAt = 1, scope = target))
        room.memoryDao().insertMemory(MemoryEntity(assistantId = MemoryOwner.RealmShared.storageId, content = "global target", scope = target))
        room.memoryDao().insertMemory(MemoryEntity(assistantId = ConfigurationReference.User(Uuid.random()).toString(), content = "orphan assistant", scope = target))
        artifact(10, ConfigurationScope.Personal, "upload/personal.txt")
        artifact(11, other, "upload/other.txt")
        artifact(12, target, "upload/target.txt")
        artifact(13, target, "images/target-artifact.txt", state = ArtifactState.DELETING)
        artifact(14, target, "upload/creating.txt", state = ArtifactState.CREATING)
        file("images/personal.png").writeText("personal media")
        file("images/other.png").writeText("other media")
        file("images/target.png").writeText("target media")
        for ((domain, name) in listOf(ConfigurationScope.Personal to "personal", other to "other", target to "target")) {
            room.genMediaDao().insert(GenMediaEntity(scope = domain, path = "images/$name.png", modelId = "model", prompt = name, createAt = 1))
        }
        file("workspace/shared.txt").writeText("shared workspace")
        file("images/untracked.txt").writeText("unrelated untracked file")
        val before = settings.snapshotUserDocument()
        val document = before.copy(preferences = before.preferences
            .withLastConversation(ConfigurationScope.Personal, Uuid.parse(personalId))
            .withLastConversation(other, Uuid.parse(otherId))
            .withLastConversation(target, Uuid.parse(targetId))
            .withAssistantUsage(target, AssistantUsagePreferences(assistant, background = UsageValue(file("upload/personal.txt").toURI().toString()))))
        preferences.edit { it[SettingsStore.USER_SETTINGS] = JsonInstant.encodeToString(document) }
        val tool = McpCatalogTool(buildJsonObject {
            put("name", "scope_check"); put("description", "Scoped fixture")
            put("inputSchema", buildJsonObject { put("type", "object") })
        })
        val candidates = listOf(
            McpCatalogCandidate(ConfigurationScope.Personal, ConfigurationReference.User(Uuid.random()), "personal", listOf(tool)),
            McpCatalogCandidate(other, ConfigurationReference.Enterprise(other.authority, "mcp_example"), "other", listOf(tool), McpManagedCatalog(1)),
            McpCatalogCandidate(target, ConfigurationReference.Enterprise(target.authority, "mcp_example"), "target", listOf(tool), McpManagedCatalog(1)),
        )
        candidates.forEach { assertTrue(catalogs.commitCandidate(it) is McpCatalogCommitResult.Committed) }
        return Retained(personalId, otherId, other, document, candidates.filter { it.scope != target }.map { it.key }.toSet(), otherFeed)
    }

    private suspend fun assertRemoved(retained: Retained) {
        val target = packet.identity.scope
        val manifest = (sessions.state.value as EnterpriseState.Available).manifest
        assertEquals(EnterpriseSessionPhase.SIGNED_OUT, manifest.phase)
        assertNull(manifest.session)
        assertNull(manifest.lastIdentity)
        assertEquals(listOf(retained.feed), manifest.feeds)
        assertTrue(room.conversationDao().getRootIds(target).isEmpty())
        assertTrue(room.folderDao().getIdsInScope(target).isEmpty())
        assertTrue(room.artifactDao().listInScope(target).isEmpty())
        assertTrue(room.genMediaDao().listInScope(target).isEmpty())
        assertEquals(0L, scalar("SELECT COUNT(*) FROM memoryentity WHERE content IN ('targetremove','childremove','global target','orphan assistant')"))
        assertEquals(0L, scalar("SELECT COUNT(*) FROM message_fts WHERE message_fts MATCH 'targetremove OR childremove'"))
        assertEquals(2L, scalar("SELECT COUNT(*) FROM favorites"))
        assertEquals(2L, scalar("SELECT COUNT(*) FROM ConversationEntity"))
        assertEquals(2L, scalar("SELECT COUNT(*) FROM memoryentity"))
        assertEquals(listOf(retained.personalId), room.conversationDao().getRootIds(ConfigurationScope.Personal))
        assertEquals(listOf(retained.otherId), room.conversationDao().getRootIds(retained.other))
        val expected = retained.document.copy(preferences = retained.document.preferences.copy(
            scopes = retained.document.preferences.scopes.filterNot { it.scope == target }))
        assertEquals(JsonInstant.encodeToString(expected), JsonInstant.encodeToString(settings.snapshotUserDocument()))
        assertEquals(retained.catalogKeys, catalogs.catalogs.value.keys)
        for (name in listOf("upload/personal.txt", "upload/other.txt", "images/personal.png", "images/other.png", "workspace/shared.txt", "images/untracked.txt")) {
            assertTrue(name, file(name).isFile)
        }
        for (name in listOf("upload/target.txt", "images/target-artifact.txt", "upload/creating.txt", "images/target.png", "images/target.png.deleting")) {
            assertFalse(name, file(name).exists())
        }
        assertEquals("shared workspace", file("workspace/shared.txt").readText())
        assertFalse(ArtifactPayloadStore(context).stagingExists("creating.txt.part"))
    }

    private fun openOwners(ready: Boolean = true) {
        appScope = AppScope(Dispatchers.IO)
        preferences = PreferenceDataStoreFactory.create(scope = appScope, migrations = listOf(UserSettingsMigration()),
            produceFile = { File(root, "settings.preferences_pb") })
        settings = SettingsStore(context, appScope, dataStore = preferences)
        catalogs = McpCatalogStore(PreferenceDataStoreFactory.create(scope = appScope,
            produceFile = { File(root, "catalogs.preferences_pb") }), appScope, settings)
        room = createAppDatabase(context, "removal")
        artifacts = ArtifactStore(ArtifactPayloadStore(context), room.artifactDao(), room.artifactReferenceDao(), room.systemMetaDao(),
            room.conversationDao(), room.messageNodeDao(), ArtifactSettingsCoordinator(settings), RoomDatabaseTransactionRunner(room))
        media = GeneratedMediaStore(context.filesDir, GenMediaRepository(room.genMediaDao()), artifacts,
            deleteCommittedPayload = { if (failPayloadDeletion) false else it.delete() })
        sessions = EnterpriseSessionController(EnterpriseAppliedStore(File(root, "enterprise")))
        repository = ConversationRepository(room.conversationDao(), room.messageNodeDao(), room.favoriteDao(), room,
            MessageFtsManager(room), room.turnExecutionDao(), room.toolExecutionDao(), room.conversationModelContextDao(), artifacts)
        val locks = ConversationOperationLocks()
        gate = ApplicationRecoveryGate().apply { if (ready) ready() }
        registry = ConversationRuntimeRegistry(appScope, repository, locks)
        commands = ConversationCommandCoordinator(registry, repository, gate, locks)
        val conversations = ConversationApplicationService(settings, repository, FolderRepository(room.folderDao(), room.conversationDao()),
            registry, commands, gate, mockk(), mockk(relaxed = true), artifacts, mockk(),
            mockk { coEvery { captureStop(any(), any(), any()) } returns null }, JsonInstant, mockk(), mockk(), sessions, mockk())
        val sync = mockk<EnterpriseSynchronizationService> { coEvery { cancelAndAwait(any()) } returns Unit }
        exit = EnterpriseExitService(sessions, sync, conversations, gate, appScope, PortalDocumentRegistry(),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), settings,
            MemoryRepository(room.memoryDao(), RoomDatabaseTransactionRunner(room)), catalogs,
            FileManagementApplicationService(artifacts, media, gate, sessions))
    }

    private suspend fun conversation(domain: ConfigurationScope, text: String, parentId: String? = null): String {
        val id = Uuid.random()
        val node = Uuid.random()
        val message = UIMessage.user(text)
        val assistant = settings.snapshotLocal().assistants.single { it.name == "Shared user definition" }.id.toString()
        val folder = if (parentId == null) Uuid.random().toString() else ""
        if (folder.isNotEmpty()) room.folderDao().insert(FolderEntity(folder, assistant, text, createAt = 1, scope = domain))
        room.memoryDao().insertMemory(MemoryEntity(assistantId = assistant, content = text, scope = domain))
        room.conversationDao().insert(ConversationEntity(id.toString(), assistant, text, 1, 1, "[]", false,
            folderId = folder, parentConversationId = parentId, scope = domain))
        room.messageNodeDao().insertAll(listOf(MessageNodeEntity(node.toString(), id.toString(), 0, JsonInstant.encodeToString(listOf(message)), 0)))
        room.favoriteDao().upsert(FavoriteEntity(Uuid.random().toString(), "node", "node:$id:$node",
            JsonInstant.encodeToString(NodeFavoriteRef(id, node)), "", createdAt = 1, updatedAt = 1, scope = domain))
        MessageFtsManager(room).reindexNodesInTransaction(id.toString(), text, 1, listOf(MessageNode(node, listOf(message))))
        return id.toString()
    }

    private suspend fun artifact(id: Long, domain: ConfigurationScope, path: String, state: ArtifactState = ArtifactState.ACTIVE) {
        val payloads = ArtifactPayloadStore(context)
        val staged = if (state == ArtifactState.CREATING) {
            payloads.stageText(payloads.reserve(path.substringBefore('/'), File(path).name), path)
                .also { assertTrue(payloads.stagingExists(it.stagingToken)) }
        } else null
        file(path).writeText(path)
        room.artifactDao().insert(ArtifactEntity(id, path.substringBefore('/'), path, File(path).name, "text/plain", path.length.toLong(), 1, 1,
            state = state.name, payloadToken = staged?.stagingToken, scope = domain))
    }

    private fun file(path: String) = File(context.filesDir, path).also { it.parentFile?.mkdirs() }
    private fun scalar(sql: String): Long = room.openHelper.writableDatabase.query(sql).use { check(it.moveToFirst()); it.getLong(0) }
}
