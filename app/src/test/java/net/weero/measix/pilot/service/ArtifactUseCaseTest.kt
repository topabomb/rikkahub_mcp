package net.weero.measix.pilot.service

import android.net.Uri
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.db.entity.ArtifactEntity
import net.weero.measix.pilot.data.db.entity.ArtifactOrigin
import net.weero.measix.pilot.data.db.entity.ArtifactState
import net.weero.measix.pilot.data.files.ArtifactDeleteResult
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.LocalArtifactRef
import net.weero.measix.pilot.data.files.OwnedArtifact
import net.weero.measix.pilot.data.imggen.TINY_PNG
import net.weero.measix.pilot.data.imggen.GeneratedMediaStore
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArtifactUseCaseTest {
    @Test
    fun `closing a draft releases its pin without starting a database deletion`() = runTest {
        val store = mockk<ArtifactStore>()
        val source = mockk<Uri>()
        val ownedUri = mockk<Uri>()
        every { source.toString() } returns "content://source/image"
        every { ownedUri.toString() } returns "file:///files/upload/image.png"
        val entity = ArtifactEntity(
            id = 8,
            folder = "upload",
            relativePath = "upload/image.png",
            displayName = "image.png",
            mimeType = "image/png",
            sizeBytes = 1,
            createdAt = 1,
            updatedAt = 1,
            state = ArtifactState.ACTIVE.name,
            origin = ArtifactOrigin.USER.name,
        )
        val owned = OwnedArtifact(
            entity = entity,
            uri = ownedUri,
            localRef = LocalArtifactRef(relativePath = entity.relativePath, mimeType = entity.mimeType),
        )
        coEvery { store.createFromUri(source) } returns owned
        every { store.abandonUnpublished(owned) } just Runs
        val scope = ArtifactUseCase(
            store,
            ApplicationRecoveryGate().apply { ready() },
        ).openDraftScope()

        val imported = scope.importUrisOrThrow(listOf(source)).single()
        assertEquals(ownedUri, imported.uri)
        assertEquals("image.png", imported.displayName)
        assertEquals("image/png", imported.mimeType)
        scope.close()

        verify(exactly = 1) { store.abandonUnpublished(owned) }
        coVerify(exactly = 0) { store.discardUnpublished(any()) }
    }

    @Test
    fun `durable publish receipt remains valid after the editor scope closes`() = runTest {
        val store = mockk<ArtifactStore>()
        val source = mockk<Uri>()
        val ownedUri = mockk<Uri>()
        every { source.toString() } returns "content://source/image"
        every { ownedUri.toString() } returns "file:///files/upload/image.png"
        val entity = ArtifactEntity(
            id = 9,
            folder = "upload",
            relativePath = "upload/image.png",
            displayName = "image.png",
            mimeType = "image/png",
            sizeBytes = 1,
            createdAt = 1,
            updatedAt = 1,
            state = ArtifactState.ACTIVE.name,
            origin = ArtifactOrigin.USER.name,
        )
        val owned = OwnedArtifact(
            entity = entity,
            uri = ownedUri,
            localRef = LocalArtifactRef(relativePath = entity.relativePath, mimeType = entity.mimeType),
        )
        coEvery { store.createFromUri(source) } returns owned
        every { store.abandonUnpublished(owned) } just Runs
        val scope = ArtifactUseCase(
            store,
            ApplicationRecoveryGate().apply { ready() },
        ).openDraftScope()

        scope.importUrisOrThrow(listOf(source))
        scope.close()
        scope.publishCommittedReferences(listOf(UIMessagePart.Image(url = ownedUri.toString())))

        coVerify(exactly = 0) { store.publishAllUnpublished(any()) }
        verify(exactly = 1) { store.abandonUnpublished(owned) }
    }

    @Test
    fun `settings import discards a new artifact when the transform does not root it`() = runTest {
        val store = mockk<ArtifactStore>()
        val source = mockk<Uri>()
        val ownedUri = mockk<Uri>()
        every { ownedUri.toString() } returns "file:///files/upload/avatar.png"
        val entity = ArtifactEntity(
            id = 7,
            folder = "upload",
            relativePath = "upload/avatar.png",
            displayName = "avatar.png",
            mimeType = "image/png",
            sizeBytes = 1,
            createdAt = 1,
            updatedAt = 1,
            state = ArtifactState.ACTIVE.name,
            origin = ArtifactOrigin.USER.name,
        )
        val owned = OwnedArtifact(
            entity = entity,
            uri = ownedUri,
            localRef = LocalArtifactRef(relativePath = entity.relativePath, mimeType = entity.mimeType),
        )
        val settings = Settings.dummy()
        val payload = kotlin.io.path.createTempFile(suffix = ".png").toFile().apply { writeBytes(TINY_PNG) }
        coEvery {
            store.createFromUri(source, maxBytes = GeneratedMediaStore.MAX_IMAGE_BYTES.toLong())
        } returns owned
        every { store.file(entity) } returns payload
        coEvery { store.updateSettingsReferences(any()) } answers {
            firstArg<(Settings) -> Settings>().invoke(settings)
        }
        coEvery { store.publishUnpublished(owned) } throws IllegalStateException("artifact has no durable root")
        coEvery { store.discardUnpublished(owned) } returns ArtifactDeleteResult.Completed(entity.id)
        val useCase = ArtifactUseCase(
            store,
            ApplicationRecoveryGate().apply { ready() },
        )

        var failure: IllegalStateException? = null
        try {
            useCase.importSettingsImage(source) { current, _ -> current }
        } catch (error: IllegalStateException) {
            failure = error
        }

        assertNotNull(failure)
        coVerify(exactly = 1) { store.discardUnpublished(owned) }
        payload.delete()
    }

    @Test
    fun `invalid settings image is discarded before a Settings root can be written`() = runTest {
        val store = mockk<ArtifactStore>()
        val source = mockk<Uri>()
        val ownedUri = mockk<Uri>()
        every { source.toString() } returns "content://source/not-image"
        every { ownedUri.toString() } returns "file:///files/upload/not-image.png"
        val entity = ArtifactEntity(
            id = 12,
            folder = "upload",
            relativePath = "upload/not-image.png",
            displayName = "not-image.png",
            mimeType = "image/png",
            sizeBytes = 12,
            createdAt = 1,
            updatedAt = 1,
            state = ArtifactState.ACTIVE.name,
            origin = ArtifactOrigin.USER.name,
        )
        val owned = OwnedArtifact(
            entity = entity,
            uri = ownedUri,
            localRef = LocalArtifactRef(relativePath = entity.relativePath, mimeType = entity.mimeType),
        )
        val payload = kotlin.io.path.createTempFile(suffix = ".png").toFile().apply {
            writeText("not an image")
        }
        coEvery {
            store.createFromUri(source, maxBytes = GeneratedMediaStore.MAX_IMAGE_BYTES.toLong())
        } returns owned
        every { store.file(entity) } returns payload
        coEvery { store.discardUnpublished(owned) } returns ArtifactDeleteResult.Completed(entity.id)
        val useCase = ArtifactUseCase(store, ApplicationRecoveryGate().apply { ready() })

        val failure = runCatching {
            useCase.importSettingsImage(source) { current, _ -> current }
        }.exceptionOrNull()

        assertNotNull(failure)
        coVerify(exactly = 0) { store.updateSettingsReferences(any()) }
        coVerify(exactly = 0) { store.publishUnpublished(any()) }
        coVerify(exactly = 1) { store.discardUnpublished(owned) }
        payload.delete()
    }
}
