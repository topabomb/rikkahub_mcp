package net.weero.measix.pilot.data.files

import android.content.Context
import androidx.core.net.toUri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.ai.attachments.AttachmentRefs
import net.weero.measix.pilot.data.datastore.EffectiveSettingsSnapshot
import net.weero.measix.pilot.data.datastore.ManagedConfigurationState
import net.weero.measix.pilot.data.datastore.SettingsAccessIndex
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.db.RoomDatabaseTransactionRunner
import net.weero.measix.pilot.data.db.entity.ArtifactEntity
import net.weero.measix.pilot.data.db.entity.ArtifactOrigin
import net.weero.measix.pilot.data.db.entity.ArtifactState
import net.weero.measix.pilot.data.db.entity.ArtifactReferenceEntity
import net.weero.measix.pilot.data.db.entity.ArtifactReferenceType
import net.weero.measix.pilot.data.db.entity.ConversationEntity
import net.weero.measix.pilot.data.db.entity.MessageNodeEntity
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.Avatar
import net.weero.measix.pilot.data.imggen.TINY_PNG
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ArtifactStoreLifecycleTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var payloadStore: ArtifactPayloadStore
    private lateinit var settingsFlow: MutableStateFlow<Settings>
    private lateinit var effectiveSettings: MutableStateFlow<EffectiveSettingsSnapshot>
    private lateinit var store: ArtifactStore
    private val folders = mutableSetOf<String>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        payloadStore = ArtifactPayloadStore(context)
        settingsFlow = MutableStateFlow(Settings())
        effectiveSettings = MutableStateFlow(settingsFlow.value.toEffectiveSnapshot())
        val settingsStore = mockk<SettingsStore>()
        every { settingsStore.effectiveSettings } returns effectiveSettings
        coEvery { settingsStore.updateLocal(any()) } coAnswers {
            firstArg<(Settings) -> Settings>()(settingsFlow.value).also { updated ->
                settingsFlow.value = updated
                effectiveSettings.value = updated.toEffectiveSnapshot()
            }
        }
        store = ArtifactStore(
            payloadStore = payloadStore,
            artifactDAO = database.artifactDao(),
            artifactReferenceDAO = database.artifactReferenceDao(),
            systemMetaDAO = database.systemMetaDao(),
            conversationDAO = database.conversationDao(),
            messageNodeDAO = database.messageNodeDao(),
            settingsCoordinator = ArtifactSettingsCoordinator(settingsStore),
            transactionRunner = RoomDatabaseTransactionRunner(database),
        )
    }

    @After
    fun tearDown() {
        folders.forEach { File(context.filesDir, it).deleteRecursively() }
        File(context.filesDir, ArtifactPayloadStore.STAGING_FOLDER).deleteRecursively()
        database.close()
    }

    @Test
    fun `create publishes active metadata and payload together`() = runTest {
        val folder = folder()

        val owned = store.createFromBytes(
            bytes = byteArrayOf(1, 2, 3),
            displayName = "sample.bin",
            folder = folder,
            origin = ArtifactOrigin.USER,
        )

        val persisted = requireNotNull(database.artifactDao().getById(owned.entity.id))
        assertEquals(ArtifactState.ACTIVE.name, persisted.state)
        assertNull(persisted.payloadToken)
        assertTrue(store.file(persisted).isFile)
        assertTrue(payloadStore.listStagingTokens().isEmpty())
    }

    @Test
    fun `image preview port rejects artifact after lifecycle deletion`() = runTest {
        val owned = store.createFromBytes(
            bytes = TINY_PNG,
            displayName = "preview.png",
            mimeType = "image/png",
            origin = ArtifactOrigin.USER,
        )

        assertEquals(
            AttachmentRefs.fileToFileUrl(store.file(owned.entity)),
            store.resolveImagePreviewForArtifact(owned.localRef),
        )
        store.abandonUnpublished(owned)
        assertTrue(store.deleteUserRequested(owned.entity.id) is ArtifactDeleteResult.Completed)
        assertNull(store.resolveImagePreviewForArtifact(owned.localRef))
    }

    @Test
    fun `payload paths and staging tokens cannot escape app storage`() {
        assertTrue(runCatching { payloadStore.file("../outside.bin") }.isFailure)
        assertTrue(runCatching { payloadStore.stagingExists("../outside.part") }.isFailure)
    }

    @Test
    fun `startup rolls back a creating row whose staging payload survived`() = runTest {
        val folder = folder()
        val staged = payloadStore.stageFromBytes(folder, byteArrayOf(9), "recover.bin", null)
        val id = database.artifactDao().insert(
            entity(staged.relativePath, folder, ArtifactState.CREATING, staged.stagingToken)
        )

        store.reconcileStartup()

        assertNull(database.artifactDao().getById(id))
        assertFalse(payloadStore.finalExists(staged.relativePath))
        assertFalse(payloadStore.stagingExists(staged.stagingToken))
    }

    @Test
    fun `startup rolls back a creating row whose final payload survived`() = runTest {
        val folder = folder()
        val staged = payloadStore.stageFromBytes(folder, byteArrayOf(9), "promoted.bin", null)
        val id = database.artifactDao().insert(
            entity(staged.relativePath, folder, ArtifactState.CREATING, staged.stagingToken)
        )
        payloadStore.promote(staged)

        store.reconcileStartup()

        assertNull(database.artifactDao().getById(id))
        assertFalse(payloadStore.finalExists(staged.relativePath))
        assertFalse(payloadStore.stagingExists(staged.stagingToken))
    }

    @Test
    fun `cancellation while waiting for lifecycle ownership removes the staged payload`() = runTest {
        val folder = folder()
        val lockAcquired = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = async {
            store.withLifecycleLock {
                lockAcquired.complete(Unit)
                release.await()
            }
        }
        lockAcquired.await()
        val creator = async {
            store.createFromBytes(byteArrayOf(1), "cancelled.bin", folder = folder, origin = ArtifactOrigin.USER)
        }
        withTimeout(5_000) {
            while (payloadStore.listStagingTokens().isEmpty()) delay(10)
        }

        creator.cancelAndJoin()
        release.complete(Unit)
        holder.await()

        assertTrue(payloadStore.listStagingTokens().isEmpty())
        assertTrue(database.artifactDao().listAllStatesByFolder(folder).first().isEmpty())
    }

    @Test
    fun `startup resumes deleting and removes both payload and metadata`() = runTest {
        val folder = folder()
        val owned = store.createFromBytes(byteArrayOf(7), "delete.bin", folder = folder, origin = ArtifactOrigin.USER)
        assertEquals(
            1,
            database.artifactDao().compareAndSetState(
                owned.entity.id,
                ArtifactState.ACTIVE.name,
                ArtifactState.DELETING.name,
                2L,
            ),
        )

        store.reconcileStartup()

        assertNull(database.artifactDao().getById(owned.entity.id))
        assertFalse(store.file(owned.entity).exists())
    }

    @Test
    fun `startup never adopts an untracked upload file without a durable root`() = runTest {
        folders += FileFolders.UPLOAD
        val relativePath = "${FileFolders.UPLOAD}/unrooted.png"
        File(context.filesDir, relativePath).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 1, 2, 3))
        }

        store.reconcileStartup()

        assertNull(database.artifactDao().getByPathAndState(relativePath, ArtifactState.ACTIVE.name))
    }

    @Test
    fun `startup persists fallback when a settings root lacks artifact metadata`() = runTest {
        folders += FileFolders.UPLOAD
        val relativePath = "${FileFolders.UPLOAD}/untracked-settings-root.png"
        val file = File(context.filesDir, relativePath).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 1, 2, 3))
        }
        settingsFlow.value = Settings(
            assistants = listOf(Assistant(background = file.toUri().toString())),
        )
        effectiveSettings.value = settingsFlow.value.toEffectiveSnapshot()

        store.reconcileStartup()

        assertTrue(file.isFile)
        assertNull(settingsFlow.value.assistants.single().background)
        assertNull(database.artifactDao().getByPathAndState(relativePath, ArtifactState.ACTIVE.name))
    }

    @Test
    fun `startup persists defaults when every Settings image root lacks metadata and payload`() = runTest {
        folders += FileFolders.UPLOAD
        val missingRoot = File(context.filesDir, "${FileFolders.UPLOAD}/missing-settings-image.png").toUri().toString()
        settingsFlow.value = Settings(
            assistants = listOf(
                Assistant(
                    avatar = Avatar.Image(missingRoot),
                    background = missingRoot,
                ),
            ),
            displaySetting = Settings().displaySetting.copy(userAvatar = Avatar.Image(missingRoot)),
        )
        effectiveSettings.value = settingsFlow.value.toEffectiveSnapshot()

        store.reconcileStartup()
        store.reconcileStartup()

        val recovered = settingsFlow.value
        val assistant = recovered.assistants.single()
        assertNull(assistant.background)
        assertEquals(Avatar.Dummy, assistant.avatar)
        assertEquals(Avatar.Dummy, recovered.displaySetting.userAvatar)
        assertNull(database.artifactDao().getByPathAndState(
            "${FileFolders.UPLOAD}/missing-settings-image.png",
            ArtifactState.ACTIVE.name,
        ))
    }

    @Test
    fun `startup persists sub-assistant avatar and background fallback without metadata`() = runTest {
        folders += FileFolders.UPLOAD
        val avatar = File(context.filesDir, "${FileFolders.UPLOAD}/legacy-sub-avatar.png").apply {
            parentFile?.mkdirs()
            writeBytes(TINY_PNG)
        }
        val background = File(context.filesDir, "${FileFolders.UPLOAD}/legacy-sub-background.png").apply {
            parentFile?.mkdirs()
            writeBytes(TINY_PNG)
        }
        settingsFlow.value = Settings(
            assistants = listOf(
                Assistant(
                    avatar = Avatar.Image(avatar.toUri().toString()),
                    background = background.toUri().toString(),
                    allowAsSubAssistant = true,
                ),
            ),
        )
        effectiveSettings.value = settingsFlow.value.toEffectiveSnapshot()

        store.reconcileStartup()

        assertTrue(avatar.isFile)
        assertTrue(background.isFile)
        assertEquals(Avatar.Dummy, settingsFlow.value.assistants.single().avatar)
        assertNull(settingsFlow.value.assistants.single().background)
        assertNull(database.artifactDao().getByPathAndState("${FileFolders.UPLOAD}/legacy-sub-avatar.png", ArtifactState.ACTIVE.name))
        assertNull(database.artifactDao().getByPathAndState("${FileFolders.UPLOAD}/legacy-sub-background.png", ArtifactState.ACTIVE.name))
    }

    @Test
    fun `read port exposes only active artifacts`() = runTest {
        val folder = folder()
        val active = store.createFromBytes(byteArrayOf(1), "active.bin", folder = folder, origin = ArtifactOrigin.USER)
        val staging = payloadStore.stageFromBytes(folder, byteArrayOf(2), "creating.bin", null)
        database.artifactDao().insert(entity(staging.relativePath, folder, ArtifactState.CREATING, staging.stagingToken))
        val deleting = store.createFromBytes(byteArrayOf(3), "deleting.bin", folder = folder, origin = ArtifactOrigin.USER)
        database.artifactDao().compareAndSetState(
            deleting.entity.id,
            ArtifactState.ACTIVE.name,
            ArtifactState.DELETING.name,
            2L,
        )

        val visible = store.list(folder)

        assertEquals(listOf(active.entity.id), visible.map { it.id })
    }

    @Test
    fun `startup fails closed when a message root points to missing active payload`() = runTest {
        val folder = folder()
        val owned = store.createFromBytes(byteArrayOf(4), "rooted.bin", folder = folder, origin = ArtifactOrigin.USER)
        val conversationId = Uuid.random().toString()
        val nodeId = Uuid.random().toString()
        database.conversationDao().insert(
            ConversationEntity(
                id = conversationId,
                assistantId = Uuid.random().toString(),
                title = "rooted",
                createAt = 1,
                updateAt = 1,
                chatSuggestions = "[]",
                isPinned = false,
            )
        )
        database.messageNodeDao().insertAll(
            listOf(MessageNodeEntity(
                id = nodeId,
                conversationId = conversationId,
                nodeIndex = 0,
                messages = "[]",
                selectIndex = 0,
            ))
        )
        database.artifactReferenceDao().insertAll(
            listOf(
                ArtifactReferenceEntity(
                    artifactId = owned.entity.id,
                    nodeId = nodeId,
                    referenceType = ArtifactReferenceType.ATTACHMENT.name,
                )
            )
        )
        assertTrue(store.file(owned.entity).delete())

        val failure = runCatching { store.reconcileStartup() }.exceptionOrNull()

        assertTrue(failure is ArtifactDataIntegrityException)
        assertEquals(ArtifactState.ACTIVE.name, database.artifactDao().getById(owned.entity.id)?.state)
    }

    @Test
    fun `startup persists fallback when a settings root points to missing active payload`() = runTest {
        val folder = folder()
        val assistant = Assistant()
        settingsFlow.value = Settings(assistants = listOf(assistant))
        val owned = store.createFromBytes(byteArrayOf(5), "background.bin", folder = folder, origin = ArtifactOrigin.USER)
        store.updateSettingsReferences { current ->
            current.copy(assistants = current.assistants.map { it.copy(background = owned.uri.toString()) })
        }
        assertTrue(store.file(owned.entity).delete())

        store.reconcileStartup()

        assertNull(settingsFlow.value.assistants.single().background)
        assertNull(database.artifactDao().getById(owned.entity.id))
    }

    @Test
    fun `folder deletion resumes mixed active and creating lifecycle states`() = runTest {
        val folder = folder()
        settingsFlow.value = Settings(assistants = listOf(Assistant()))
        val active = store.createFromBytes(
            byteArrayOf(7),
            "active.bin",
            folder = folder,
            origin = ArtifactOrigin.USER,
        )
        store.updateSettingsReferences { current ->
            current.copy(assistants = current.assistants.map { it.copy(background = active.uri.toString()) })
        }
        store.updateSettingsReferences { current ->
            current.copy(assistants = current.assistants.map { it.copy(background = null) })
        }
        val staged = payloadStore.stageFromBytes(folder, byteArrayOf(8), "creating.bin", null)
        val creatingId = database.artifactDao().insert(
            entity(staged.relativePath, folder, ArtifactState.CREATING, staged.stagingToken)
        )

        val result = store.deleteUserRequestedFolder(folder)

        assertTrue(result is ArtifactDeleteResult.Completed)
        assertNull(database.artifactDao().getById(active.entity.id))
        assertNull(database.artifactDao().getById(creatingId))
        assertFalse(payloadStore.finalExists(active.entity.relativePath))
        assertFalse(payloadStore.stagingExists(staged.stagingToken))
    }

    @Test
    fun `scoped folder deletion respects cutoff and keeps newer artifacts`() = runTest {
        val folder = folder()
        val oldStaged = payloadStore.stageFromBytes(folder, byteArrayOf(1), "old.bin", null)
        payloadStore.promote(oldStaged)
        val oldId = database.artifactDao().insert(
            entity(oldStaged.relativePath, folder, ArtifactState.ACTIVE, token = null, createdAt = 1_000L)
        )
        val freshStaged = payloadStore.stageFromBytes(folder, byteArrayOf(2), "fresh.bin", null)
        payloadStore.promote(freshStaged)
        val freshId = database.artifactDao().insert(
            entity(freshStaged.relativePath, folder, ArtifactState.ACTIVE, token = null, createdAt = 5_000L)
        )

        val result = store.deleteUserRequestedFolderCreatedBefore(folder, createdBefore = 2_000L)

        assertEquals(1, result.deleted)
        assertEquals(0, result.cleanupPending)
        assertEquals(0, result.skippedInProgress)
        assertEquals(0, result.failed)
        assertNull(database.artifactDao().getById(oldId))
        assertTrue(database.artifactDao().getById(freshId) != null)
        assertFalse(payloadStore.finalExists(oldStaged.relativePath))
        assertTrue(payloadStore.finalExists(freshStaged.relativePath))
    }

    @Test
    fun `scoped folder deletion skips live ownership instead of discarding it`() = runTest {
        val folder = folder()
        val owned = store.createFromBytes(
            bytes = byteArrayOf(3),
            displayName = "in-flight.bin",
            folder = folder,
            origin = ArtifactOrigin.USER,
        )

        val result = store.deleteUserRequestedFolderCreatedBefore(folder, Long.MAX_VALUE)

        assertEquals(0, result.deleted)
        assertEquals(1, result.skippedInProgress)
        assertEquals(0, result.failed)
        assertTrue(database.artifactDao().getById(owned.entity.id) != null)
        assertTrue(store.file(owned.entity).isFile)
    }

    @Test
    fun `settings root and garbage collection are serialized`() = runTest {
        val folder = folder()
        val assistant = Assistant()
        settingsFlow.value = Settings(assistants = listOf(assistant))
        store.ensureReferenceProjection()
        val owned = store.createFromBytes(byteArrayOf(3), "avatar.bin", folder = folder, origin = ArtifactOrigin.USER)
        val lockAcquired = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = async {
            store.withLifecycleLock {
                lockAcquired.complete(Unit)
                release.await()
            }
        }
        lockAcquired.await()
        val publish = async {
            store.updateSettingsReferences { current ->
                current.copy(assistants = current.assistants.map { it.copy(background = owned.uri.toString()) })
            }
        }
        runCurrent()
        val gc = async { store.collectGarbage(0) }
        release.complete(Unit)
        holder.await()
        publish.await()

        assertTrue(gc.await().isEmpty())
        assertEquals(ArtifactState.ACTIVE.name, database.artifactDao().getById(owned.entity.id)?.state)
        assertTrue(store.file(owned.entity).isFile)
    }

    @Test
    fun `discard rejects a published artifact and succeeds after detach`() = runTest {
        val folder = folder()
        val assistant = Assistant()
        settingsFlow.value = Settings(assistants = listOf(assistant))
        val owned = store.createFromBytes(byteArrayOf(4), "root.bin", folder = folder, origin = ArtifactOrigin.USER)
        store.updateSettingsReferences { current ->
            current.copy(assistants = current.assistants.map { it.copy(background = owned.uri.toString()) })
        }

        val rejected = store.discardUnpublished(owned)
        assertTrue(rejected is ArtifactDeleteResult.Failed)

        store.updateSettingsReferences { current ->
            current.copy(assistants = current.assistants.map { it.copy(background = null) })
        }
        assertTrue(store.discardUnpublished(owned) is ArtifactDeleteResult.Failed)
        assertTrue(store.deleteUserRequested(owned.entity.id) is ArtifactDeleteResult.Completed)
        assertNull(database.artifactDao().getById(owned.entity.id))
    }

    @Test
    fun `live unpublished ownership blocks garbage collection and explicit deletion`() = runTest {
        val folder = folder()
        store.ensureReferenceProjection()
        val owned = store.createFromBytes(byteArrayOf(6), "draft.bin", folder = folder, origin = ArtifactOrigin.USER)

        assertTrue(store.collectGarbage(0).isEmpty())
        val deletion = store.deleteUserRequested(owned.entity.id)

        assertTrue(deletion is ArtifactDeleteResult.Rejected)
        assertEquals(
            ArtifactDeleteResult.RejectionReason.IN_PROGRESS,
            (deletion as ArtifactDeleteResult.Rejected).reason,
        )
        assertTrue(store.file(owned.entity).isFile)
    }

    @Test
    fun `batch publication validates every root before consuming any ownership token`() = runTest {
        val folder = folder()
        val rooted = store.createFromBytes(
            byteArrayOf(1),
            "rooted.bin",
            folder = folder,
            origin = ArtifactOrigin.USER,
        )
        val unrooted = store.createFromBytes(
            byteArrayOf(2),
            "unrooted.bin",
            folder = folder,
            origin = ArtifactOrigin.USER,
        )
        val conversationId = Uuid.random().toString()
        val nodeId = Uuid.random().toString()
        database.conversationDao().insert(
            ConversationEntity(
                id = conversationId,
                assistantId = Uuid.random().toString(),
                title = "batch-root",
                createAt = 1,
                updateAt = 1,
                chatSuggestions = "[]",
                isPinned = false,
            )
        )
        database.messageNodeDao().insertAll(
            listOf(
                MessageNodeEntity(
                    id = nodeId,
                    conversationId = conversationId,
                    nodeIndex = 0,
                    messages = "[]",
                    selectIndex = 0,
                )
            )
        )
        database.artifactReferenceDao().insertAll(
            listOf(
                ArtifactReferenceEntity(
                    artifactId = rooted.entity.id,
                    nodeId = nodeId,
                    referenceType = ArtifactReferenceType.ATTACHMENT.name,
                )
            )
        )

        val failure = runCatching {
            store.publishAllUnpublished(listOf(rooted, unrooted))
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        val rootedDelete = store.deleteUserRequested(rooted.entity.id)
        val unrootedDelete = store.deleteUserRequested(unrooted.entity.id)
        assertEquals(
            ArtifactDeleteResult.RejectionReason.IN_PROGRESS,
            (rootedDelete as ArtifactDeleteResult.Rejected).reason,
        )
        assertEquals(
            ArtifactDeleteResult.RejectionReason.IN_PROGRESS,
            (unrootedDelete as ArtifactDeleteResult.Rejected).reason,
        )
    }

    @Test
    fun `startup deleting recovery detaches settings root before payload removal`() = runTest {
        val folder = folder()
        settingsFlow.value = Settings(assistants = listOf(Assistant()))
        val owned = store.createFromBytes(byteArrayOf(8), "rooted-delete.bin", folder = folder, origin = ArtifactOrigin.USER)
        store.updateSettingsReferences { current ->
            current.copy(assistants = current.assistants.map { it.copy(background = owned.uri.toString()) })
        }
        assertEquals(
            1,
            database.artifactDao().compareAndSetState(
                owned.entity.id,
                ArtifactState.ACTIVE.name,
                ArtifactState.DELETING.name,
                3L,
            ),
        )

        store.reconcileStartup()

        assertNull(database.artifactDao().getById(owned.entity.id))
        assertNull(settingsFlow.value.assistants.single().background)
        assertFalse(store.file(owned.entity).exists())
    }

    @Test
    fun `explicit delete resumes an interrupted deleting state`() = runTest {
        val folder = folder()
        settingsFlow.value = Settings(assistants = listOf(Assistant()))
        val owned = store.createFromBytes(
            bytes = byteArrayOf(9),
            displayName = "retry-delete.bin",
            folder = folder,
            origin = ArtifactOrigin.USER,
        )
        store.updateSettingsReferences { current ->
            current.copy(assistants = current.assistants.map { it.copy(background = owned.uri.toString()) })
        }
        store.publishUnpublished(owned)
        assertEquals(
            1,
            database.artifactDao().compareAndSetState(
                owned.entity.id,
                ArtifactState.ACTIVE.name,
                ArtifactState.DELETING.name,
                4L,
            ),
        )

        val result = store.deleteUserRequested(owned.entity.id)

        assertTrue(result is ArtifactDeleteResult.Completed)
        assertNull(database.artifactDao().getById(owned.entity.id))
        assertNull(settingsFlow.value.assistants.single().background)
        assertFalse(store.file(owned.entity).exists())
    }

    @Test
    fun `concurrent explicit deletes invoke physical payload deletion exactly once`() = runTest {
        val folder = folder()
        val relativePath = "$folder/concurrent.bin"
        val artifactId = database.artifactDao().insert(
            entity(relativePath, folder, ArtifactState.ACTIVE, token = null)
        )
        val physicalDeletes = AtomicInteger()
        val countingPayloadStore = mockk<ArtifactPayloadStore>()
        every { countingPayloadStore.file(relativePath) } returns File(context.filesDir, relativePath)
        coEvery { countingPayloadStore.deleteStaging(null) } returns true
        coEvery { countingPayloadStore.deleteFinal(relativePath) } coAnswers {
            physicalDeletes.incrementAndGet()
            true
        }
        val localSettings = MutableStateFlow(Settings())
        val localEffectiveSettings = MutableStateFlow(localSettings.value.toEffectiveSnapshot())
        val settingsStore = mockk<SettingsStore>()
        every { settingsStore.effectiveSettings } returns localEffectiveSettings
        coEvery { settingsStore.updateLocal(any()) } coAnswers {
            firstArg<(Settings) -> Settings>()(localSettings.value).also { updated ->
                localSettings.value = updated
                localEffectiveSettings.value = updated.toEffectiveSnapshot()
            }
        }
        val countingStore = ArtifactStore(
            payloadStore = countingPayloadStore,
            artifactDAO = database.artifactDao(),
            artifactReferenceDAO = database.artifactReferenceDao(),
            systemMetaDAO = database.systemMetaDao(),
            conversationDAO = database.conversationDao(),
            messageNodeDAO = database.messageNodeDao(),
            settingsCoordinator = ArtifactSettingsCoordinator(settingsStore),
            transactionRunner = RoomDatabaseTransactionRunner(database),
        )

        val results = (0 until 20).map {
            async(Dispatchers.Default) { countingStore.deleteUserRequested(artifactId) }
        }.awaitAll()

        assertEquals(1, physicalDeletes.get())
        assertEquals(1, results.count { it is ArtifactDeleteResult.Completed })
        assertTrue(results.filterIsInstance<ArtifactDeleteResult.Rejected>().all {
            it.reason == ArtifactDeleteResult.RejectionReason.ALREADY_DELETED
        })
        assertNull(database.artifactDao().getById(artifactId))
    }

    @Test
    fun `corrupt backfill node does not replace projection or mark it current`() = runTest {
        val conversationId = Uuid.random().toString()
        database.conversationDao().insert(
            ConversationEntity(
                id = conversationId,
                assistantId = Uuid.random().toString(),
                title = "corrupt",
                createAt = 1,
                updateAt = 1,
                chatSuggestions = "[]",
                isPinned = false,
            )
        )
        database.messageNodeDao().upsertAll(
            listOf(
                MessageNodeEntity(
                    id = Uuid.random().toString(),
                    conversationId = conversationId,
                    nodeIndex = 0,
                    messages = "not-json",
                    selectIndex = 0,
                )
            )
        )

        val failure = runCatching { store.ensureReferenceProjection() }.exceptionOrNull()

        assertTrue(failure is ArtifactProjectionException)
        assertFalse(store.isReferenceProjectionCurrent())
    }

    private fun folder(): String = "artifact-test-${Uuid.random()}".also(folders::add)

    private fun entity(
        relativePath: String,
        folder: String,
        state: ArtifactState,
        token: String?,
        createdAt: Long = 1L,
    ) = ArtifactEntity(
        folder = folder,
        relativePath = relativePath,
        displayName = File(relativePath).name,
        mimeType = "application/octet-stream",
        sizeBytes = 1,
        createdAt = createdAt,
        updatedAt = 1,
        state = state.name,
        payloadToken = token,
        origin = ArtifactOrigin.USER.name,
    )
}

private fun Settings.toEffectiveSnapshot(): EffectiveSettingsSnapshot = EffectiveSettingsSnapshot(
    settings = this,
    access = SettingsAccessIndex(),
    revision = 0L,
    managedState = ManagedConfigurationState.ABSENT,
)
