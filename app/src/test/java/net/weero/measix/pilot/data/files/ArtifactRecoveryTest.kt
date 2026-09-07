package net.weero.measix.pilot.data.files

import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.db.RoomDatabaseTransactionRunner
import net.weero.measix.pilot.data.db.entity.ArtifactOrigin
import net.weero.measix.pilot.data.db.entity.ArtifactReferenceEntity
import net.weero.measix.pilot.data.db.entity.ArtifactReferenceType
import net.weero.measix.pilot.data.db.entity.ArtifactState
import net.weero.measix.pilot.data.db.entity.ConversationEntity
import net.weero.measix.pilot.data.db.entity.MessageNodeEntity
import net.weero.measix.pilot.data.imggen.TINY_PNG
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.Avatar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
internal class ArtifactRecoveryTest : ArtifactStoreLifecycleTestBase() {
    @Test
    fun `startup rolls back a creating row whose staging payload survived`() = runTest {
        val folder = folder()
        val staged = stageBytes(folder, byteArrayOf(9), "recover.bin")
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
        val staged = stageBytes(folder, byteArrayOf(9), "promoted.bin")
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
            fileNameCandidates = { List(4) { "000000" } },
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
}
