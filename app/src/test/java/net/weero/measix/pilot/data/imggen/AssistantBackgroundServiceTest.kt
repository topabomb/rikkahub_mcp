package net.weero.measix.pilot.data.imggen

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.ai.attachments.RemoteMediaFetchResult
import net.weero.measix.pilot.data.ai.attachments.SafeRemoteMediaFetcher
import net.weero.measix.pilot.data.db.entity.ArtifactEntity
import net.weero.measix.pilot.data.db.entity.ArtifactOrigin
import net.weero.measix.pilot.data.db.entity.ArtifactState
import net.weero.measix.pilot.data.files.ArtifactDeleteResult
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.LocalArtifactRef
import net.weero.measix.pilot.data.files.OwnedArtifact
import net.weero.measix.pilot.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantBackgroundServiceTest {
    @Test
    fun `uppercase file scheme resolves the same local path`() {
        val file = File.createTempFile("background", ".png")
        val uppercase = fileUri(file).replaceFirst("file:", "FILE:")

        assertEquals(file.canonicalFile, localFileFromUri(uppercase)?.canonicalFile)
        file.delete()
    }

    @Test
    fun `successful replacement publishes settings before reporting success`() = runTest {
        val env = Env()
        env.settingsUpdateSucceeds()

        val result = env.service.replaceGeneratedBackground(env.assistant.id, env.source, "image/png")

        assertTrue(result.updated)
        assertFalse(result.cleanupPending)
        coVerify(exactly = 0) { env.store.discardUnpublished(any()) }
        env.close()
    }

    @Test
    fun `missing assistant compensates the unpublished copy`() = runTest {
        val env = Env()
        env.settingsUpdateSucceeds()
        coEvery { env.store.discardUnpublished(env.owned) } returns ArtifactDeleteResult.Completed(env.owned.entity.id)

        val result = env.service.replaceGeneratedBackground(Uuid.random(), env.source, "image/png")

        assertEquals("assistant_not_found", result.reason)
        assertFalse(result.cleanupPending)
        coVerify(exactly = 1) { env.store.discardUnpublished(env.owned) }
        env.close()
    }

    @Test
    fun `settings failure compensates and exposes cleanup status`() = runTest {
        val env = Env()
        coEvery { env.store.updateSettingsReferences(any()) } throws IllegalStateException("datastore failed")
        coEvery { env.store.discardUnpublished(env.owned) } returns ArtifactDeleteResult.Failed(
            env.owned.entity.id,
            "payload_delete_failed",
        )

        val result = env.service.replaceGeneratedBackground(env.assistant.id, env.source, "image/png")

        assertEquals("settings_write_failed", result.reason)
        assertTrue(result.cleanupPending)
        env.close()
    }

    @Test
    fun `user selected data image is validated and copied inside the service`() = runTest {
        val env = Env()
        env.settingsUpdateSucceeds()
        coEvery {
            env.store.createFromBytes(TINY_PNG, "background.png", "image/png", any(), ArtifactOrigin.USER)
        } returns env.owned
        val encoded = java.util.Base64.getEncoder().encodeToString(TINY_PNG)

        val result = env.service.replaceUserSelectedBackground(
            env.assistant.id,
            "data:image/png;base64,$encoded",
        )

        assertTrue(result.updated)
        coVerify(exactly = 1) {
            env.store.createFromBytes(TINY_PNG, "background.png", "image/png", any(), ArtifactOrigin.USER)
        }
        env.close()
    }

    @Test
    fun `rejected remote image never enters artifact storage`() = runTest {
        val env = Env()
        coEvery { env.fetcher.fetch(any()) } returns RemoteMediaFetchResult.Failure("unsafe")

        val result = env.service.replaceUserSelectedBackground(env.assistant.id, "https://example.com/image.png")

        assertEquals("background_copy_failed", result.reason)
        coVerify(exactly = 0) { env.store.createFromBytes(any(), any(), any(), any(), any()) }
        env.close()
    }

    private class Env {
        val directory = createTempDirectory("assistant-background-v1c").toFile()
        val source = File(directory, "source.png").apply { writeBytes(TINY_PNG) }
        val assistant = Assistant(name = "A")
        val settings = Settings(assistants = listOf(assistant))
        val store = mockk<ArtifactStore>()
        val context = mockk<Context>()
        val fetcher = mockk<SafeRemoteMediaFetcher>()
        val owned = owned(directory)
        val service: AssistantBackgroundService

        init {
            every { context.applicationContext } returns context
            service = AssistantBackgroundService(store, context, fetcher)
            coEvery { store.copyFile(source, "image/png", source.name, any(), ArtifactOrigin.GENERATED) } returns owned
            coEvery { store.discardUnpublished(owned) } returns ArtifactDeleteResult.Completed(owned.entity.id)
            coEvery { store.collectGarbage(any()) } returns emptyList()
        }

        fun settingsUpdateSucceeds() {
            coEvery { store.updateSettingsReferences(any()) } coAnswers {
                firstArg<(Settings) -> Settings>()(settings)
            }
        }

        fun close() {
            directory.deleteRecursively()
        }

        companion object {
            fun owned(directory: File): OwnedArtifact {
                val file = File(directory, "upload/background.png").apply {
                    parentFile?.mkdirs()
                    writeBytes(TINY_PNG)
                }
                val entity = ArtifactEntity(
                    id = 9,
                    folder = "upload",
                    relativePath = "upload/background.png",
                    displayName = file.name,
                    mimeType = "image/png",
                    sizeBytes = file.length(),
                    createdAt = 1,
                    updatedAt = 1,
                    state = ArtifactState.ACTIVE.name,
                    origin = ArtifactOrigin.USER.name,
                )
                val uri = mockk<android.net.Uri>()
                every { uri.toString() } returns "file:///${file.absolutePath.replace('\\', '/')}"
                return OwnedArtifact(
                    entity,
                    uri,
                    LocalArtifactRef(relativePath = entity.relativePath, mimeType = entity.mimeType),
                )
            }
        }
    }
}
