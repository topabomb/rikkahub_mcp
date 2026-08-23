package net.weero.measix.pilot.data.imggen

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.createTempDirectory
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.db.entity.ArtifactOrigin
import net.weero.measix.pilot.data.files.LocalArtifactRef
import net.weero.measix.pilot.data.files.ManagedLocalArtifactStore
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.Avatar
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.data.repository.GenMediaRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantBackgroundServiceTest {
    private val assistantId = Uuid.random()

    private fun tempDir(prefix: String): File = createTempDirectory(prefix).toFile()

    private fun service(
        filesDir: File,
        settings: MutableStateFlow<Settings>,
        artifactStore: ManagedLocalArtifactStore,
        conversations: ConversationRepository = mockk {
            coEvery { getAllTopLevelConversationsSync() } returns emptyList()
            coEvery { getAllChildConversationIds() } returns emptyList()
        },
        media: GenMediaRepository = mockk {
            coEvery { getAllMediaList() } returns emptyList()
        },
        update: suspend (Settings) -> Settings = { it },
    ): AssistantBackgroundService {
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns filesDir
        val store = mockk<SettingsStore>()
        every { store.settingsFlow } returns settings
        coEvery { store.updateAtomicAndGet(any()) } coAnswers {
            val fn = invocation.args[0] as (Settings) -> Settings
            update(fn(settings.value)).also { committed ->
                settings.value = committed
            }
        }
        return AssistantBackgroundService(context, store, artifactStore, conversations, media)
    }

    @Test
    fun `settings write failure deletes the new background copy`() = runTest {
        val filesDir = tempDir("bg-write-fail")
        val source = File(filesDir, "source.png").apply { writeBytes(TINY_PNG) }
        val copy = LocalArtifactRef(relativePath = "upload/new-bg.png", mimeType = "image/png")
        File(filesDir, copy.relativePath).apply {
            parentFile?.mkdirs()
            writeBytes(TINY_PNG)
        }
        val artifactStore = mockk<ManagedLocalArtifactStore>()
        coEvery { artifactStore.copyFile(any(), any(), any(), any(), any()) } returns copy
        coEvery { artifactStore.delete(copy) } returns Unit
        val settings = MutableStateFlow(Settings(assistants = listOf(Assistant(id = assistantId))))
        val service = service(filesDir, settings, artifactStore, update = { error("datastore") })
        val result = service.replaceBackground(assistantId, source, "image/png", ArtifactOrigin.USER)
        assertFalse(result.updated)
        assertEquals("settings_write_failed", result.reason)
        coVerify { artifactStore.delete(copy) }
        filesDir.deleteRecursively()
    }

    @Test
    fun `cancellation before settings publish deletes the new copy`() = runTest {
        val filesDir = tempDir("bg-cancel")
        val source = File(filesDir, "source.png").apply { writeBytes(TINY_PNG) }
        val copy = LocalArtifactRef(relativePath = "upload/new-bg.png", mimeType = "image/png")
        val artifactStore = mockk<ManagedLocalArtifactStore>()
        coEvery { artifactStore.copyFile(any(), any(), any(), any(), any()) } returns copy
        coEvery { artifactStore.delete(copy) } returns Unit
        val settings = MutableStateFlow(Settings(assistants = listOf(Assistant(id = assistantId))))
        val service = service(filesDir, settings, artifactStore, update = {
            throw CancellationException("cancelled before commit")
        })
        val result = runCatching { service.replaceBackground(assistantId, source, "image/png", ArtifactOrigin.USER) }
        assertTrue(result.exceptionOrNull() is CancellationException)
        coVerify { artifactStore.delete(copy) }
        filesDir.deleteRecursively()
    }

    @Test
    fun `cancellation after settings publish keeps the new copy`() = runTest {
        val filesDir = tempDir("bg-cancel-after")
        val source = File(filesDir, "source.png").apply { writeBytes(TINY_PNG) }
        val copy = LocalArtifactRef(relativePath = "upload/new-bg.png", mimeType = "image/png")
        val artifactStore = mockk<ManagedLocalArtifactStore>()
        coEvery { artifactStore.copyFile(any(), any(), any(), any(), any()) } returns copy
        val settings = MutableStateFlow(Settings(assistants = listOf(Assistant(id = assistantId))))
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns filesDir
        val store = mockk<SettingsStore>()
        every { store.settingsFlow } returns settings
        coEvery { store.updateAtomicAndGet(any()) } coAnswers {
            val fn = invocation.args[0] as (Settings) -> Settings
            settings.value = fn(settings.value)
            throw CancellationException("cancelled after commit")
        }
        val conversations = mockk<ConversationRepository>()
        coEvery { conversations.getAllTopLevelConversationsSync() } returns emptyList()
        coEvery { conversations.getAllChildConversationIds() } returns emptyList()
        val media = mockk<GenMediaRepository>()
        coEvery { media.getAllMediaList() } returns emptyList()
        val service = AssistantBackgroundService(context, store, artifactStore, conversations, media)
        val result = runCatching { service.replaceBackground(assistantId, source, "image/png", ArtifactOrigin.USER) }
        assertTrue(result.exceptionOrNull() is CancellationException)
        coVerify(exactly = 0) { artifactStore.delete(copy) }
        filesDir.deleteRecursively()
    }

    @Test
    fun `missing assistant deletes the unused copy`() = runTest {
        val filesDir = tempDir("bg-missing")
        val source = File(filesDir, "source.png").apply { writeBytes(TINY_PNG) }
        val copy = LocalArtifactRef(relativePath = "upload/new-bg.png", mimeType = "image/png")
        val artifactStore = mockk<ManagedLocalArtifactStore>()
        coEvery { artifactStore.copyFile(any(), any(), any(), any(), any()) } returns copy
        coEvery { artifactStore.delete(copy) } returns Unit
        val settings = MutableStateFlow(Settings(assistants = emptyList()))
        val service = service(filesDir, settings, artifactStore)
        val result = service.replaceBackground(assistantId, source, "image/png", ArtifactOrigin.USER)
        assertFalse(result.updated)
        assertEquals("assistant_not_found", result.reason)
        coVerify { artifactStore.delete(copy) }
        filesDir.deleteRecursively()
    }

    @Test
    fun `rejected settings change deletes only the unused new copy`() = runTest {
        val filesDir = tempDir("bg-rejected")
        val source = File(filesDir, "source.png").apply { writeBytes(TINY_PNG) }
        val oldFile = File(filesDir, "upload/old-bg.png").apply {
            parentFile?.mkdirs()
            writeBytes(TINY_PNG)
        }
        val oldRef = LocalArtifactRef(relativePath = "upload/old-bg.png", mimeType = "image/png")
        val copy = LocalArtifactRef(relativePath = "upload/new-bg.png", mimeType = "image/png")
        val artifactStore = mockk<ManagedLocalArtifactStore>()
        coEvery { artifactStore.copyFile(any(), any(), any(), any(), any()) } returns copy
        coEvery { artifactStore.delete(copy) } returns Unit
        val initial = Settings(
            assistants = listOf(Assistant(id = assistantId, background = oldRef.fileUri(filesDir))),
        )
        val settings = MutableStateFlow(initial)
        val service = service(filesDir, settings, artifactStore, update = { initial })

        val result = service.replaceBackground(assistantId, source, "image/png", ArtifactOrigin.USER)

        assertFalse(result.updated)
        assertEquals("settings_write_rejected", result.reason)
        assertTrue(oldFile.exists())
        coVerify { artifactStore.delete(copy) }
        coVerify(exactly = 0) { artifactStore.delete(oldRef) }
        filesDir.deleteRecursively()
    }

    @Test
    fun `old background shared by another assistant is kept`() = runTest {
        val filesDir = tempDir("bg-shared")
        val source = File(filesDir, "source.png").apply { writeBytes(TINY_PNG) }
        val oldFile = File(filesDir, "upload/old-bg.png").apply {
            parentFile?.mkdirs()
            writeBytes(TINY_PNG)
        }
        val oldUri = LocalArtifactRef(relativePath = "upload/old-bg.png", mimeType = "image/png").fileUri(filesDir)
        val other = Assistant(id = Uuid.random(), background = oldUri, avatar = Avatar.Dummy)
        val owner = Assistant(id = assistantId, background = oldUri)
        val copy = LocalArtifactRef(relativePath = "upload/new-bg.png", mimeType = "image/png")
        val artifactStore = mockk<ManagedLocalArtifactStore>()
        coEvery { artifactStore.copyFile(any(), any(), any(), any(), any()) } returns copy
        val settings = MutableStateFlow(Settings(assistants = listOf(owner, other)))
        val service = service(filesDir, settings, artifactStore)
        val result = service.replaceBackground(assistantId, source, "image/png", ArtifactOrigin.USER)
        assertTrue(result.updated)
        assertTrue(oldFile.exists())
        coVerify(exactly = 0) { artifactStore.delete(match { it.relativePath == "upload/old-bg.png" }) }
        filesDir.deleteRecursively()
    }

    @Test
    fun `unreferenced old background is cleaned after a successful update`() = runTest {
        val filesDir = tempDir("bg-old-clean")
        val source = File(filesDir, "source.png").apply { writeBytes(TINY_PNG) }
        File(filesDir, "upload/old-bg.png").apply {
            parentFile?.mkdirs()
            writeBytes(TINY_PNG)
        }
        val oldUri = LocalArtifactRef(relativePath = "upload/old-bg.png", mimeType = "image/png").fileUri(filesDir)
        val owner = Assistant(id = assistantId, background = oldUri)
        val copy = LocalArtifactRef(relativePath = "upload/new-bg.png", mimeType = "image/png")
        val artifactStore = mockk<ManagedLocalArtifactStore>()
        coEvery { artifactStore.copyFile(any(), any(), any(), any(), any()) } returns copy
        coEvery { artifactStore.delete(any()) } returns Unit
        val settings = MutableStateFlow(Settings(assistants = listOf(owner)))
        val service = service(filesDir, settings, artifactStore)
        val result = service.replaceBackground(assistantId, source, "image/png", ArtifactOrigin.USER)
        assertTrue(result.updated)
        coVerify { artifactStore.delete(match { it.relativePath == "upload/old-bg.png" }) }
        filesDir.deleteRecursively()
    }

    @Test
    fun `old background referenced by a conversation is kept`() = runTest {
        val filesDir = tempDir("bg-conversation")
        val source = File(filesDir, "source.png").apply { writeBytes(TINY_PNG) }
        File(filesDir, "upload/old-bg.png").apply {
            parentFile?.mkdirs()
            writeBytes(TINY_PNG)
        }
        val oldUri = LocalArtifactRef(relativePath = "upload/old-bg.png", mimeType = "image/png").fileUri(filesDir)
        val owner = Assistant(id = assistantId, background = oldUri)
        val copy = LocalArtifactRef(relativePath = "upload/new-bg.png", mimeType = "image/png")
        val artifactStore = mockk<ManagedLocalArtifactStore>()
        coEvery { artifactStore.copyFile(any(), any(), any(), any(), any()) } returns copy
        val conversations = mockk<ConversationRepository>()
        coEvery { conversations.getAllTopLevelConversationsSync() } returns listOf(
            Conversation(
                assistantId = assistantId,
                messageNodes = listOf(
                    net.weero.measix.pilot.data.model.MessageNode.of(
                        me.rerere.ai.ui.UIMessage(
                            role = me.rerere.ai.core.MessageRole.ASSISTANT,
                            parts = listOf(me.rerere.ai.ui.UIMessagePart.Image(url = oldUri)),
                        )
                    )
                ),
            )
        )
        coEvery { conversations.getAllChildConversationIds() } returns emptyList()
        val settings = MutableStateFlow(Settings(assistants = listOf(owner)))
        val service = service(filesDir, settings, artifactStore, conversations)
        val result = service.replaceBackground(assistantId, source, "image/png", ArtifactOrigin.USER)
        assertTrue(result.updated)
        coVerify(exactly = 0) { artifactStore.delete(match { it.relativePath == "upload/old-bg.png" }) }
        filesDir.deleteRecursively()
    }
}
