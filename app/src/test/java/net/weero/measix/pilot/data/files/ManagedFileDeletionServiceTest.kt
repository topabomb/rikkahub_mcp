package net.weero.measix.pilot.data.files

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.createTempDirectory
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.datastore.Settings
import net.weero.measix.pilot.data.datastore.SettingsStore
import net.weero.measix.pilot.data.db.entity.ManagedFileEntity
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.Avatar
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.repository.ConversationRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedFileDeletionServiceTest {
    private fun tempDir(prefix: String): File = createTempDirectory(prefix).toFile()

    private fun entity(id: Long, relativePath: String): ManagedFileEntity = ManagedFileEntity(
        id = id,
        folder = FileFolders.UPLOAD,
        relativePath = relativePath,
        displayName = relativePath.substringAfterLast('/'),
        mimeType = "image/png",
        sizeBytes = 1L,
        createdAt = 0L,
        updatedAt = 0L,
    )

    private fun fileUriOf(filesDir: File, relativePath: String): String {
        val path = File(filesDir, relativePath).absolutePath.replace('\\', '/')
        return if (path.startsWith("/")) "file://$path" else "file:///$path"
    }

    private fun service(
        filesDir: File,
        settings: MutableStateFlow<Settings>,
        entities: List<ManagedFileEntity>,
        deletedIds: MutableList<Long> = mutableListOf(),
        folderDeleted: MutableList<String> = mutableListOf(),
        update: suspend (Settings) -> Settings = { it },
        conversations: ConversationRepository = mockk {
            coEvery { getAllTopLevelConversationsSync() } returns emptyList()
            coEvery { getAllChildConversationIds() } returns emptyList()
        },
    ): ManagedFileDeletionService {
        val filesManager = mockk<FilesManager>()
        entities.forEach { e ->
            every { filesManager.getFile(e) } returns File(filesDir, e.relativePath)
        }
        coEvery { filesManager.list(FileFolders.UPLOAD) } returns entities
        coEvery {
            filesManager.deleteManagedFilePermanently(any<Long>(), any<Boolean>())
        } coAnswers {
            deletedIds.add(firstArg())
            true
        }
        coEvery { filesManager.deleteManagedFolderPermanently(FileFolders.UPLOAD) } coAnswers {
            folderDeleted.add(firstArg())
            true
        }
        val store = mockk<SettingsStore>()
        every { store.settingsFlow } returns settings
        coEvery { store.updateAtomicAndGet(any()) } coAnswers {
            val fn = invocation.args[0] as (Settings) -> Settings
            update(fn(settings.value)).also { committed ->
                settings.value = committed
            }
        }
        return ManagedFileDeletionService(filesManager, store, conversations)
    }

    @Test
    fun `explicit delete detaches assistant background before deleting the file`() = runTest {
        val filesDir = tempDir("del-bg")
        val target = entity(1, "upload/bg.png")
        File(filesDir, target.relativePath).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        val uri = fileUriOf(filesDir, target.relativePath)
        val assistant = Assistant(id = Uuid.random(), background = uri)
        val settings = MutableStateFlow(Settings(assistants = listOf(assistant)))
        val deletedIds = mutableListOf<Long>()
        val service = service(filesDir, settings, listOf(target), deletedIds = deletedIds)

        val ok = service.deletePermanently(target)

        assertTrue(ok)
        assertEquals(listOf(1L), deletedIds)
        assertNull(settings.value.assistants.single().background)
        filesDir.deleteRecursively()
    }

    @Test
    fun `explicit delete resets assistant avatar to default`() = runTest {
        val filesDir = tempDir("del-avatar")
        val target = entity(2, "upload/avatar.png")
        val uri = fileUriOf(filesDir, target.relativePath)
        val assistant = Assistant(id = Uuid.random(), avatar = Avatar.Image(uri))
        val settings = MutableStateFlow(Settings(assistants = listOf(assistant)))
        val deletedIds = mutableListOf<Long>()
        val service = service(filesDir, settings, listOf(target), deletedIds = deletedIds)

        val ok = service.deletePermanently(target)

        assertTrue(ok)
        assertEquals(listOf(2L), deletedIds)
        assertEquals(Avatar.Dummy, settings.value.assistants.single().avatar)
    }

    @Test
    fun `settings write failure keeps the file`() = runTest {
        val filesDir = tempDir("del-write-fail")
        val target = entity(3, "upload/x.png")
        val uri = fileUriOf(filesDir, target.relativePath)
        val assistant = Assistant(id = Uuid.random(), background = uri)
        val settings = MutableStateFlow(Settings(assistants = listOf(assistant)))
        val deletedIds = mutableListOf<Long>()
        val service = service(
            filesDir,
            settings,
            listOf(target),
            deletedIds = deletedIds,
            update = { error("datastore") },
        )

        val ok = service.deletePermanently(target)

        assertFalse(ok)
        assertTrue(deletedIds.isEmpty())
        assertEquals(uri, settings.value.assistants.single().background)
        filesDir.deleteRecursively()
    }

    @Test
    fun `rejected settings change keeps the file`() = runTest {
        val filesDir = tempDir("del-rejected")
        val target = entity(4, "upload/y.png")
        val uri = fileUriOf(filesDir, target.relativePath)
        val assistant = Assistant(id = Uuid.random(), background = uri)
        val initial = Settings(assistants = listOf(assistant))
        val settings = MutableStateFlow(initial)
        val deletedIds = mutableListOf<Long>()
        val service = service(
            filesDir,
            settings,
            listOf(target),
            deletedIds = deletedIds,
            update = { initial },
        )

        val ok = service.deletePermanently(target)

        assertFalse(ok)
        assertTrue(deletedIds.isEmpty())
        assertEquals(uri, settings.value.assistants.single().background)
        filesDir.deleteRecursively()
    }

    @Test
    fun `cancellation propagates without deleting the file`() = runTest {
        val filesDir = tempDir("del-cancel")
        val target = entity(5, "upload/z.png")
        val settings = MutableStateFlow(Settings(assistants = emptyList()))
        val deletedIds = mutableListOf<Long>()
        val service = service(
            filesDir,
            settings,
            listOf(target),
            deletedIds = deletedIds,
            update = { throw CancellationException("cancelled") },
        )

        val result = runCatching { service.deletePermanently(target) }

        assertTrue(result.exceptionOrNull() is CancellationException)
        assertTrue(deletedIds.isEmpty())
        filesDir.deleteRecursively()
    }

    @Test
    fun `folder delete detaches all matching references`() = runTest {
        val filesDir = tempDir("del-folder")
        val first = entity(6, "upload/a.png")
        val second = entity(7, "upload/b.png")
        val firstUri = fileUriOf(filesDir, first.relativePath)
        val secondUri = fileUriOf(filesDir, second.relativePath)
        val backgroundOwner = Assistant(id = Uuid.random(), background = firstUri)
        val avatarOwner = Assistant(id = Uuid.random(), avatar = Avatar.Image(secondUri))
        val untouched = Assistant(
            id = Uuid.random(),
            background = "https://example.com/keep.png",
            avatar = Avatar.Emoji("🙂"),
        )
        val settings = MutableStateFlow(
            Settings(assistants = listOf(backgroundOwner, avatarOwner, untouched)),
        )
        val folderDeleted = mutableListOf<String>()
        val service = service(
            filesDir,
            settings,
            listOf(first, second),
            folderDeleted = folderDeleted,
        )

        val ok = service.deleteFolderPermanently(FileFolders.UPLOAD)

        assertTrue(ok)
        assertEquals(listOf(FileFolders.UPLOAD), folderDeleted)
        assertNull(settings.value.assistants[0].background)
        assertEquals(Avatar.Dummy, settings.value.assistants[1].avatar)
        assertEquals("https://example.com/keep.png", settings.value.assistants[2].background)
        assertEquals(Avatar.Emoji("🙂"), settings.value.assistants[2].avatar)
        filesDir.deleteRecursively()
    }

    @Test
    fun `folder delete stops when settings write fails`() = runTest {
        val filesDir = tempDir("del-folder-fail")
        val target = entity(8, "upload/c.png")
        val uri = fileUriOf(filesDir, target.relativePath)
        val settings = MutableStateFlow(
            Settings(assistants = listOf(Assistant(id = Uuid.random(), background = uri))),
        )
        val folderDeleted = mutableListOf<String>()
        val service = service(
            filesDir,
            settings,
            listOf(target),
            folderDeleted = folderDeleted,
            update = { error("datastore") },
        )

        val ok = service.deleteFolderPermanently(FileFolders.UPLOAD)

        assertFalse(ok)
        assertTrue(folderDeleted.isEmpty())
        filesDir.deleteRecursively()
    }

    @Test
    fun `explicit delete removes file even when referenced by conversation history`() = runTest {
        val filesDir = tempDir("del-history")
        val target = entity(9, "upload/tool.png")
        val uri = fileUriOf(filesDir, target.relativePath)
        val conversations = mockk<ConversationRepository>()
        coEvery { conversations.getAllTopLevelConversationsSync() } returns listOf(
            Conversation(
                assistantId = Uuid.random(),
                messageNodes = listOf(
                    MessageNode.of(
                        UIMessage(
                            role = MessageRole.ASSISTANT,
                            parts = listOf(UIMessagePart.Image(url = uri)),
                        ),
                    ),
                ),
            ),
        )
        coEvery { conversations.getAllChildConversationIds() } returns emptyList()
        val settings = MutableStateFlow(Settings(assistants = emptyList()))
        val deletedIds = mutableListOf<Long>()
        val service = service(
            filesDir,
            settings,
            listOf(target),
            deletedIds = deletedIds,
            conversations = conversations,
        )

        val ok = service.deletePermanently(target)

        assertTrue(ok)
        assertEquals(listOf(9L), deletedIds)
        filesDir.deleteRecursively()
    }

    @Test
    fun `inspect reports history and assistant reference counts`() = runTest {
        val filesDir = tempDir("del-inspect")
        val target = entity(10, "upload/shared.png")
        val uri = fileUriOf(filesDir, target.relativePath)
        val conversations = mockk<ConversationRepository>()
        coEvery { conversations.getAllTopLevelConversationsSync() } returns listOf(
            Conversation(
                assistantId = Uuid.random(),
                messageNodes = listOf(
                    MessageNode.of(
                        UIMessage(
                            role = MessageRole.ASSISTANT,
                            parts = listOf(UIMessagePart.Image(url = uri)),
                        ),
                    ),
                ),
            ),
        )
        coEvery { conversations.getAllChildConversationIds() } returns emptyList()
        val settings = MutableStateFlow(
            Settings(
                assistants = listOf(
                    Assistant(id = Uuid.random(), background = uri),
                    Assistant(id = Uuid.random(), avatar = Avatar.Image(uri)),
                    Assistant(id = Uuid.random()),
                ),
            ),
        )
        val service = service(
            filesDir,
            settings,
            listOf(target),
            conversations = conversations,
        )

        val impact = service.inspect(target)

        assertTrue(impact.referencedByHistory)
        assertEquals(1, impact.assistantBackgroundCount)
        assertEquals(1, impact.assistantAvatarCount)
        filesDir.deleteRecursively()
    }
}
