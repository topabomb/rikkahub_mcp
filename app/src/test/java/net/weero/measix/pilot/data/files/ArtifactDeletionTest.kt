package net.weero.measix.pilot.data.files

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.db.entity.ArtifactOrigin
import net.weero.measix.pilot.data.db.entity.ArtifactState
import net.weero.measix.pilot.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
internal class ArtifactDeletionTest : ArtifactStoreLifecycleTestBase() {
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
        val staged = stageBytes(folder, byteArrayOf(8), "creating.bin")
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
        val oldStaged = stageBytes(folder, byteArrayOf(1), "old.bin")
        payloadStore.promote(oldStaged)
        val oldId = database.artifactDao().insert(
            entity(oldStaged.relativePath, folder, ArtifactState.ACTIVE, token = null, createdAt = 1_000L)
        )
        val freshStaged = stageBytes(folder, byteArrayOf(2), "fresh.bin")
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
}
