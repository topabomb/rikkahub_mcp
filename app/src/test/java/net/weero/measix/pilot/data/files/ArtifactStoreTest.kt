package net.weero.measix.pilot.data.files

import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
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
import net.weero.measix.pilot.data.db.dao.ArtifactDAO
import net.weero.measix.pilot.data.db.dao.ArtifactReferenceDAO
import net.weero.measix.pilot.data.db.dao.ConversationDAO
import net.weero.measix.pilot.data.db.dao.MessageNodeDAO
import net.weero.measix.pilot.data.db.dao.SystemMetaDAO
import net.weero.measix.pilot.data.db.entity.ArtifactEntity
import net.weero.measix.pilot.data.db.entity.ArtifactReferenceEntity
import net.weero.measix.pilot.data.db.entity.ArtifactReferenceType
import net.weero.measix.pilot.data.db.entity.ArtifactState
import net.weero.measix.pilot.data.db.entity.ConversationEntity
import net.weero.measix.pilot.data.db.entity.SystemMetaEntity
import net.weero.measix.pilot.data.model.Assistant
import net.weero.measix.pilot.data.model.Avatar
import net.weero.measix.pilot.utils.JsonInstant
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * ArtifactStore 权威测试（原 ManagedFileDeletionServiceTest 迁移 + 新协议）。
 * 覆盖：CAS 幂等删除、detach background/avatar、settings 失败保护、取消传播、
 * folder 删除、历史引用删除、inspect 计数、引用回填、启动恢复。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ArtifactStoreTest {
    private fun tempDir(prefix: String): File = createTempDirectory(prefix).toFile()

    private fun entity(id: Long, relativePath: String): ArtifactEntity = ArtifactEntity(
        id = id,
        folder = FileFolders.UPLOAD,
        relativePath = relativePath,
        displayName = relativePath.substringAfterLast('/'),
        mimeType = "image/png",
        sizeBytes = 1L,
        createdAt = 0L,
        updatedAt = 0L,
        state = ArtifactState.ACTIVE.name,
    )

    private fun fileUriOf(filesDir: File, relativePath: String): String {
        val path = File(filesDir, relativePath).absolutePath.replace('\\', '/')
        return if (path.startsWith("/")) "file://$path" else "file:///$path"
    }

    /** 构建 ArtifactStore：mock 全部 DAO + FilesManager + SettingsStore。 */
    private fun store(
        filesDir: File,
        settings: MutableStateFlow<Settings>,
        entities: List<ArtifactEntity>,
        deletedIds: MutableList<Long> = mutableListOf(),
        folderDeleted: MutableList<String> = mutableListOf(),
        update: suspend (Settings) -> Settings = { it },
        backfilled: Boolean = true,
    ): ArtifactStore {
        val filesManager = mockk<FilesManager>()
        entities.forEach { e ->
            every { filesManager.getFile(e) } returns File(filesDir, e.relativePath)
        }
        coEvery { filesManager.list(FileFolders.UPLOAD) } returns entities
        coEvery { filesManager.deleteManagedFilePermanently(any<Long>(), any<Boolean>()) } coAnswers {
            deletedIds.add(firstArg())
            true
        }
        coEvery { filesManager.deleteManagedFolderPermanently(FileFolders.UPLOAD) } coAnswers {
            folderDeleted.add(firstArg())
            true
        }

        val dao = mockk<ArtifactDAO>()
        coEvery { dao.getById(any()) } answers { entities.firstOrNull { it.id == firstArg<Long>() } }
        coEvery { dao.getByPath(any()) } returns null
        every { dao.listByFolder(FileFolders.UPLOAD) } returns MutableStateFlow(entities)
        coEvery { dao.compareAndSetState(any(), ArtifactState.ACTIVE.name, ArtifactState.DELETING.name, any()) } returns 1
        // 回滚方向 DELETING→ACTIVE（settings 失败时 ArtifactStore 主动回滚）
        coEvery { dao.compareAndSetState(any(), ArtifactState.DELETING.name, ArtifactState.ACTIVE.name, any()) } returns 1

        val refDAO = mockk<ArtifactReferenceDAO>()
        coEvery { refDAO.existsByArtifactId(any()) } returns false

        val metaDAO = mockk<SystemMetaDAO>()
        coEvery { metaDAO.get(ArtifactStore.BACKFILL_FLAG) } returns if (backfilled) "true" else null

        val convDAO = mockk<ConversationDAO>()
        coEvery { convDAO.getAllConversations() } returns emptyList()
        val nodeDAO = mockk<MessageNodeDAO>()

        val settingsStore = mockk<SettingsStore>()
        every { settingsStore.settingsFlow } returns settings
        coEvery { settingsStore.updateAtomicAndGet(any()) } coAnswers {
            val fn = invocation.args[0] as (Settings) -> Settings
            update(fn(settings.value)).also { committed -> settings.value = committed }
        }

        return ArtifactStore(
            filesManager = filesManager,
            artifactDAO = dao,
            artifactReferenceDAO = refDAO,
            systemMetaDAO = metaDAO,
            conversationDAO = convDAO,
            messageNodeDAO = nodeDAO,
            settingsStore = settingsStore,
        )
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
        val store = store(filesDir, settings, listOf(target), deletedIds = deletedIds)

        val result = store.deletePermanently(target)

        assertTrue(result is ArtifactDeleteResult.Completed)
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
        val store = store(filesDir, settings, listOf(target), deletedIds = deletedIds)

        val result = store.deletePermanently(target)

        assertTrue(result is ArtifactDeleteResult.Completed)
        assertEquals(listOf(2L), deletedIds)
        assertEquals(Avatar.Dummy, settings.value.assistants.single().avatar)
        filesDir.deleteRecursively()
    }

    @Test
    fun `settings write failure keeps the file and rolls back state`() = runTest {
        val filesDir = tempDir("del-write-fail")
        val target = entity(3, "upload/x.png")
        val uri = fileUriOf(filesDir, target.relativePath)
        val assistant = Assistant(id = Uuid.random(), background = uri)
        val settings = MutableStateFlow(Settings(assistants = listOf(assistant)))
        val deletedIds = mutableListOf<Long>()
        val store = store(
            filesDir,
            settings,
            listOf(target),
            deletedIds = deletedIds,
            update = { error("datastore") },
        )

        val result = store.deletePermanently(target)

        assertTrue(result is ArtifactDeleteResult.Failed)
        assertTrue(deletedIds.isEmpty())
        assertEquals(uri, settings.value.assistants.single().background)
        filesDir.deleteRecursively()
    }

    @Test
    fun `rejected settings change keeps the file and rolls back state`() = runTest {
        val filesDir = tempDir("del-rejected")
        val target = entity(4, "upload/y.png")
        val uri = fileUriOf(filesDir, target.relativePath)
        val assistant = Assistant(id = Uuid.random(), background = uri)
        val initial = Settings(assistants = listOf(assistant))
        val settings = MutableStateFlow(initial)
        val deletedIds = mutableListOf<Long>()
        val store = store(
            filesDir,
            settings,
            listOf(target),
            deletedIds = deletedIds,
            update = { initial },
        )

        val result = store.deletePermanently(target)

        assertTrue(result is ArtifactDeleteResult.Failed)
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
        val store = store(
            filesDir,
            settings,
            listOf(target),
            deletedIds = deletedIds,
            update = { throw CancellationException("cancelled") },
        )

        val result = runCatching { store.deletePermanently(target) }

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
            avatar = Avatar.Emoji("😊"),
        )
        val settings = MutableStateFlow(Settings(assistants = listOf(backgroundOwner, avatarOwner, untouched)))
        val folderDeleted = mutableListOf<String>()
        val store = store(filesDir, settings, listOf(first, second), folderDeleted = folderDeleted)

        val result = store.deleteFolderPermanently(FileFolders.UPLOAD)

        assertTrue(result is ArtifactDeleteResult.Completed)
        assertEquals(listOf(FileFolders.UPLOAD), folderDeleted)
        assertNull(settings.value.assistants[0].background)
        assertEquals(Avatar.Dummy, settings.value.assistants[1].avatar)
        assertEquals("https://example.com/keep.png", settings.value.assistants[2].background)
        assertEquals(Avatar.Emoji("😊"), settings.value.assistants[2].avatar)
        filesDir.deleteRecursively()
    }

    @Test
    fun `explicit delete removes file even when referenced by conversation history`() = runTest {
        val filesDir = tempDir("del-history")
        val target = entity(9, "upload/tool.png")
        val uri = fileUriOf(filesDir, target.relativePath)
        // inspect 只在 UI 提示；deletePermanently 本身不受历史引用影响
        val settings = MutableStateFlow(Settings(assistants = emptyList()))
        val deletedIds = mutableListOf<Long>()
        val store = store(filesDir, settings, listOf(target), deletedIds = deletedIds)

        val result = store.deletePermanently(target)

        assertTrue(result is ArtifactDeleteResult.Completed)
        assertEquals(listOf(9L), deletedIds)
        filesDir.deleteRecursively()
    }

    @Test
    fun `inspect reports assistant background and avatar reference counts`() = runTest {
        val filesDir = tempDir("del-inspect")
        val target = entity(10, "upload/shared.png")
        val uri = fileUriOf(filesDir, target.relativePath)
        val settings = MutableStateFlow(
            Settings(
                assistants = listOf(
                    Assistant(id = Uuid.random(), background = uri),
                    Assistant(id = Uuid.random(), avatar = Avatar.Image(uri)),
                    Assistant(id = Uuid.random()),
                ),
            ),
        )
        val store = store(filesDir, settings, listOf(target))

        val impact = store.inspect(target)

        assertEquals(1, impact.assistantBackgroundCount)
        assertEquals(1, impact.assistantAvatarCount)
        filesDir.deleteRecursively()
    }

    @Test
    fun `CAS rejection returns Rejected without touching settings or disk`() = runTest {
        val filesDir = tempDir("del-cas-reject")
        val target = entity(11, "upload/cas.png")
        val settings = MutableStateFlow(Settings(assistants = emptyList()))
        val deletedIds = mutableListOf<Long>()
        val filesManager = mockk<FilesManager>()
        every { filesManager.getFile(target) } returns File(filesDir, target.relativePath)
        coEvery { filesManager.deleteManagedFilePermanently(any<Long>(), any<Boolean>()) } coAnswers {
            deletedIds.add(firstArg())
            true
        }
        val dao = mockk<ArtifactDAO>()
        coEvery { dao.getById(target.id) } returns target
        // CAS 返回 0（状态已变迁）→ 应 Rejected，不应删除
        coEvery { dao.compareAndSetState(any(), ArtifactState.ACTIVE.name, ArtifactState.DELETING.name, any()) } returns 0
        val settingsStore = mockk<SettingsStore>()
        every { settingsStore.settingsFlow } returns settings
        coEvery { settingsStore.updateAtomicAndGet(any()) } returns settings.value
        val customStore = ArtifactStore(
            filesManager = filesManager,
            artifactDAO = dao,
            artifactReferenceDAO = mockk(),
            systemMetaDAO = mockk(),
            conversationDAO = mockk(),
            messageNodeDAO = mockk(),
            settingsStore = settingsStore,
        )
        val result = customStore.deletePermanently(target)
        assertTrue(result is ArtifactDeleteResult.Rejected)
        assertTrue(deletedIds.isEmpty())
        filesDir.deleteRecursively()
    }

    // ---- 引用回填 ----

    /** 构造一个含 file:// 图片 URL 的 UIMessage 列表，并序列化为 MessageNodeDAO 存储的 JSON。 */
    private fun messagesJson(vararg urls: String): String {
        val messages = urls.map { url ->
            UIMessage(
                id = Uuid.random(),
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Image(url = url)),
            )
        }
        return JsonInstant.encodeToString(messages)
    }

    private fun conversationEntity(id: String): ConversationEntity = ConversationEntity(
        id = id,
        assistantId = Uuid.random().toString(),
        title = "t",
        nodes = "[]",
        createAt = 0L,
        updateAt = 0L,
        chatSuggestions = "[]",
        isPinned = false,
    )

    /** 构建一个 backfill 专用的 ArtifactStore：mock 会话/节点/磁盘映射，捕获引用与 flag。 */
    private fun backfillStore(
        conversations: List<ConversationEntity>,
        nodeMessagesJson: Map<String, String>,              // nodeId -> messagesJson
        conversationNodeIds: Map<String, List<String>>,     // conversationId -> nodeIds
        relativePaths: Map<String, String>,                 // file URI -> relativePath
        artifactsByPath: Map<String, ArtifactEntity>,       // relativePath -> artifact
        backfilled: Boolean,
    ): Pair<ArtifactStore, CapturingSlot<List<ArtifactReferenceEntity>>> {
        val filesManager = mockk<FilesManager>()
        coEvery { filesManager.getRelativePathForUri(any()) } answers { relativePaths[firstArg<android.net.Uri>().toString()] }
        val dao = mockk<ArtifactDAO>()
        coEvery { dao.getByPath(any()) } answers { artifactsByPath[firstArg<String>()] }
        coEvery { dao.getById(any()) } returns null
        val refDAO = mockk<ArtifactReferenceDAO>()
        val refSlot = slot<List<ArtifactReferenceEntity>>()
        coEvery { refDAO.insertAll(capture(refSlot)) } returns Unit
        coEvery { refDAO.deleteByNodeIds(any()) } returns Unit
        val metaDAO = mockk<SystemMetaDAO>()
        coEvery { metaDAO.get(ArtifactStore.BACKFILL_FLAG) } returns if (backfilled) "true" else null
        coEvery { metaDAO.put(any()) } returns Unit
        val convDAO = mockk<ConversationDAO>()
        coEvery { convDAO.getAllConversations() } returns conversations
        val nodeDAO = mockk<MessageNodeDAO>()
        coEvery { nodeDAO.getNodeIdsOfConversation(any()) } answers {
            conversationNodeIds[firstArg<String>()] ?: emptyList()
        }
        coEvery { nodeDAO.getMessagesJsonById(any()) } answers { nodeMessagesJson[firstArg<String>()] }
        val settingsStore = mockk<SettingsStore>()
        every { settingsStore.settingsFlow } returns MutableStateFlow(Settings(assistants = emptyList()))
        val store = ArtifactStore(
            filesManager = filesManager,
            artifactDAO = dao,
            artifactReferenceDAO = refDAO,
            systemMetaDAO = metaDAO,
            conversationDAO = convDAO,
            messageNodeDAO = nodeDAO,
            settingsStore = settingsStore,
        )
        return store to refSlot
    }

    @Test
    fun `BF1 backfill registers references across master and child conversations`() = runTest {
        val convA = conversationEntity("a")
        val convB = conversationEntity("b")
        val artifactA = entity(1, "upload/a.png")
        val artifactB = entity(2, "upload/b.png")
        val nodeA1 = "a-1"
        val nodeA2 = "a-2"
        val nodeB1 = "b-1"
        val fileUriA = "file:///data/files/upload/a.png"
        val fileUriB = "file:///data/files/upload/b.png"
        val (store, refSlot) = backfillStore(
            conversations = listOf(convA, convB),
            nodeMessagesJson = mapOf(
                nodeA1 to messagesJson(fileUriA),
                nodeA2 to messagesJson(fileUriB),
                nodeB1 to messagesJson(fileUriA, fileUriB),
            ),
            conversationNodeIds = mapOf(
                "a" to listOf(nodeA1, nodeA2),
                "b" to listOf(nodeB1),
            ),
            relativePaths = mapOf(
                fileUriA to "upload/a.png",
                fileUriB to "upload/b.png",
            ),
            artifactsByPath = mapOf(
                "upload/a.png" to artifactA,
                "upload/b.png" to artifactB,
            ),
            backfilled = false,
        )

        store.backfillReferences()

        val rows = runCatching { refSlot.captured }.getOrNull() ?: emptyList()
        assertEquals(4, rows.size)
        assertTrue(rows.any { it.artifactId == 1L && it.nodeId == nodeA1 })
        assertTrue(rows.any { it.artifactId == 2L && it.nodeId == nodeA2 })
        assertTrue(rows.any { it.artifactId == 1L && it.nodeId == nodeB1 })
        assertTrue(rows.any { it.artifactId == 2L && it.nodeId == nodeB1 })
        assertTrue(rows.all { it.referenceType == ArtifactReferenceType.ATTACHMENT.name })
    }

    @Test
    fun `BF2 rerun backfill after completion adds zero rows`() = runTest {
        val conv = conversationEntity("a")
        val artifactA = entity(1, "upload/a.png")
        val fileUriA = "file:///data/files/upload/a.png"
        val nodeA1 = "a-1"
        val filesManager = mockk<FilesManager>()
        coEvery { filesManager.getRelativePathForUri(any()) } answers { fileUriA }
        val dao = mockk<ArtifactDAO>()
        coEvery { dao.getByPath(any()) } answers { artifactA }
        coEvery { dao.getById(any()) } returns null
        val refDAO = mockk<ArtifactReferenceDAO>()
        val allInserted = mutableListOf<List<ArtifactReferenceEntity>>()
        coEvery { refDAO.insertAll(capture(allInserted)) } returns Unit
        coEvery { refDAO.deleteByNodeIds(any()) } returns Unit
        val metaDAO = mockk<SystemMetaDAO>()
        // flag 一直未置位（模拟 backup 恢复后 system_meta 被回退），两次 backfill 都会执行提取
        coEvery { metaDAO.get(ArtifactStore.BACKFILL_FLAG) } returns null
        coEvery { metaDAO.put(any()) } returns Unit
        val convDAO = mockk<ConversationDAO>()
        coEvery { convDAO.getAllConversations() } returns listOf(conv)
        val nodeDAO = mockk<MessageNodeDAO>()
        coEvery { nodeDAO.getNodeIdsOfConversation(conv.id) } returns listOf(nodeA1)
        coEvery { nodeDAO.getMessagesJsonById(nodeA1) } returns messagesJson(fileUriA)
        val settingsStore = mockk<SettingsStore>()
        every { settingsStore.settingsFlow } returns MutableStateFlow(Settings(assistants = emptyList()))
        val store = ArtifactStore(
            filesManager, dao, refDAO, metaDAO, convDAO, nodeDAO, settingsStore,
        )

        store.backfillReferences()
        store.backfillReferences() // 再次执行（flag 未置位）→ 提取结果相同

        // 重复登记经唯一索引 IGNORE 幂等 → 产生的是同一引用集合（去重后仅 1 个 (nodeId, artifactId)）
        val distinct = allInserted.flatten().map { it.nodeId to it.artifactId }.distinct()
        assertEquals("rerun produces no new distinct reference", listOf(nodeA1 to 1L), distinct)
    }

    @Test
    fun `BF3 partial failure before flag set converges on retry`() = runTest {
        val conv = conversationEntity("a")
        val artifactA = entity(1, "upload/a.png")
        val artifactB = entity(2, "upload/b.png")
        val fileUriA = "file:///data/files/upload/a.png"
        val fileUriB = "file:///data/files/upload/b.png"
        val nodeA1 = "a-1"
        val nodeA2 = "a-2"
        // 第一次运行：nodeA2 的 getMessagesJsonById 抛 IO 异常 → flag 未置位、部分登记
        val filesManager = mockk<FilesManager>()
        coEvery { filesManager.getRelativePathForUri(any()) } answers {
            mapOf(fileUriA to "upload/a.png", fileUriB to "upload/b.png")[firstArg<android.net.Uri>().toString()]
        }
        val dao = mockk<ArtifactDAO>()
        coEvery { dao.getByPath(any()) } answers {
            mapOf("upload/a.png" to artifactA, "upload/b.png" to artifactB)[firstArg<String>()]
        }
        val refDAO = mockk<ArtifactReferenceDAO>()
        val refSlot = slot<List<ArtifactReferenceEntity>>()
        coEvery { refDAO.insertAll(capture(refSlot)) } returns Unit
        val metaDAO = mockk<SystemMetaDAO>()
        coEvery { metaDAO.get(ArtifactStore.BACKFILL_FLAG) } returns null
        coEvery { metaDAO.put(any()) } returns Unit
        val convDAO = mockk<ConversationDAO>()
        coEvery { convDAO.getAllConversations() } returns listOf(conv)
        val nodeDAO = mockk<MessageNodeDAO>()
        coEvery { nodeDAO.getNodeIdsOfConversation(conv.id) } returns listOf(nodeA1, nodeA2)
        coEvery { nodeDAO.getMessagesJsonById(nodeA1) } returns messagesJson(fileUriA)
        coEvery { nodeDAO.getMessagesJsonById(nodeA2) } throws RuntimeException("io boom")
        val settingsStore = mockk<SettingsStore>()
        every { settingsStore.settingsFlow } returns MutableStateFlow(Settings(assistants = emptyList()))
        val store = ArtifactStore(
            filesManager, dao, refDAO, metaDAO, convDAO, nodeDAO, settingsStore,
        )

        runCatching { store.backfillReferences() }

        // flag 未置位（异常中断在 put 之前）
        assertTrue("flag not set after failure", !store.isBackfilled())
        refSlot.clear()

        // 重跑（IO 恢复）→ 收敛
        coEvery { nodeDAO.getMessagesJsonById(nodeA2) } returns messagesJson(fileUriB)
        store.backfillReferences()
        val rows = refSlot.captured ?: emptyList()
        assertEquals("rerun registers both nodes", 2, rows.size)
        assertTrue(rows.any { it.nodeId == nodeA1 && it.artifactId == 1L })
        assertTrue(rows.any { it.nodeId == nodeA2 && it.artifactId == 2L })
    }

    @Test
    fun `BF4 external urls and unmatched file uris are ignored`() = runTest {
        val conv = conversationEntity("a")
        val artifactA = entity(1, "upload/a.png")
        val fileUriA = "file:///data/files/upload/a.png"
        val external = "https://example.com/x.png"
        val unmatched = "file:///data/files/upload/none.png"
        val nodeA1 = "a-1"
        val (store, refSlot) = backfillStore(
            conversations = listOf(conv),
            nodeMessagesJson = mapOf(nodeA1 to messagesJson(fileUriA, external, unmatched)),
            conversationNodeIds = mapOf("a" to listOf(nodeA1)),
            relativePaths = mapOf(fileUriA to "upload/a.png"),
            artifactsByPath = mapOf("upload/a.png" to artifactA),
            backfilled = false,
        )

        store.backfillReferences()

        // 只有匹配到 artifact 的 file URI 产生引用行；external/unmatched 被忽略
        val rows = runCatching { refSlot.captured }.getOrNull() ?: emptyList()
        assertEquals(1, rows.size)
        assertEquals(1L, rows.single().artifactId)
        assertEquals(nodeA1, rows.single().nodeId)
    }

    @Test
    fun `BF5 flag already set returns immediately`() = runTest {
        val conv = conversationEntity("a")
        val artifactA = entity(1, "upload/a.png")
        val fileUriA = "file:///data/files/upload/a.png"
        val nodeA1 = "a-1"
        val (store, refSlot) = backfillStore(
            conversations = listOf(conv),
            nodeMessagesJson = mapOf(nodeA1 to messagesJson(fileUriA)),
            conversationNodeIds = mapOf("a" to listOf(nodeA1)),
            relativePaths = mapOf(fileUriA to "upload/a.png"),
            artifactsByPath = mapOf("upload/a.png" to artifactA),
            backfilled = true,
        )
        store.backfillReferences()
        assertTrue((runCatching { refSlot.captured }.getOrNull() ?: emptyList()).isEmpty())
    }

    // ---- 启动恢复 ----

    private class ReconcileCtx(
        val store: ArtifactStore,
        val deletedIds: MutableList<Long>,
        val rowDeletedIds: MutableList<Long>,
        val insertedIds: MutableList<Long>,
        val root: File,
    )

    /** 构建 reconcile 专用 store；在真实临时目录创建实体文件，并记录磁盘删除/行删除动作。 */
    private fun reconcileStore(
        deleting: List<ArtifactEntity>,
        active: List<ArtifactEntity>,
    ): ReconcileCtx {
        val root = tempDir("rs")
        val filesManager = mockk<FilesManager>()
        (deleting + active).forEach { e ->
            File(root, e.relativePath).apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1)) }
        }
        // getFile 按 relativePath 计算返回（匹配任何 entity，避免 ArtifactEntity 等值/引用匹配差异）
        every { filesManager.getFile(any()) } answers {
            File(root, (firstArg() as ArtifactEntity).relativePath)
        }
        val deletedIds = mutableListOf<Long>()
        coEvery { filesManager.deleteManagedFilePermanently(any<Long>(), any<Boolean>()) } coAnswers {
            deletedIds.add(firstArg())
            true
        }
        coEvery { filesManager.logUntrackedUploadFiles() } returns Unit
        val dao = mockk<ArtifactDAO>()
        coEvery { dao.listByState(ArtifactState.DELETING.name) } returns deleting
        coEvery { dao.listByState(ArtifactState.ACTIVE.name) } returns active
        val rowDeletedIds = mutableListOf<Long>()
        coEvery { dao.deleteById(any()) } coAnswers { rowDeletedIds.add(firstArg()); 1 }
        // reconcile 绝不能调用 insert（"重启复活"缺陷的回归锁定）
        val insertedIds = mutableListOf<Long>()
        coEvery { dao.insert(any()) } coAnswers { insertedIds.add(firstArg<ArtifactEntity>().id); 1L }
        val refDAO = mockk<ArtifactReferenceDAO>()
        val metaDAO = mockk<SystemMetaDAO>()
        coEvery { metaDAO.get(ArtifactStore.BACKFILL_FLAG) } returns null
        val convDAO = mockk<ConversationDAO>()
        coEvery { convDAO.getAllConversations() } returns emptyList()
        val nodeDAO = mockk<MessageNodeDAO>()
        val settingsStore = mockk<SettingsStore>()
        every { settingsStore.settingsFlow } returns MutableStateFlow(Settings(assistants = emptyList()))
        val store = ArtifactStore(
            filesManager = filesManager,
            artifactDAO = dao,
            artifactReferenceDAO = refDAO,
            systemMetaDAO = metaDAO,
            conversationDAO = convDAO,
            messageNodeDAO = nodeDAO,
            settingsStore = settingsStore,
        )
        return ReconcileCtx(store, deletedIds, rowDeletedIds, insertedIds, root)
    }

    @Test
    fun `RS1 deleting state with file on disk deletes disk and row`() = runTest {
        val target = entity(1, "upload/rs1.png").copy(state = ArtifactState.DELETING.name)
        val ctx = reconcileStore(deleting = listOf(target), active = emptyList())
        ctx.store.reconcileStartup()
        // 文件在磁盘 → 续删磁盘（deleteManagedFilePermanently 内部负责删行）
        assertEquals(listOf(1L), ctx.deletedIds)
        assertTrue(ctx.rowDeletedIds.isEmpty())
        ctx.root.deleteRecursively()
    }

    @Test
    fun `RS2 deleting state with missing file deletes row only`() = runTest {
        val target = entity(2, "upload/rs2.png").copy(state = ArtifactState.DELETING.name)
        val ctx = reconcileStore(deleting = listOf(target), active = emptyList())
        // 删掉磁盘文件，模拟"文件已删、行未删"
        File(ctx.root, target.relativePath).delete()
        ctx.store.reconcileStartup()
        // 文件缺失 → 仅删行，不触发磁盘删除
        assertTrue(ctx.deletedIds.isEmpty())
        assertEquals(listOf(2L), ctx.rowDeletedIds)
        ctx.root.deleteRecursively()
    }

    @Test
    fun `RS3 active state with missing file removes the row`() = runTest {
        val target = entity(3, "upload/rs3.png")
        val ctx = reconcileStore(deleting = emptyList(), active = listOf(target))
        // 删掉磁盘文件
        File(ctx.root, target.relativePath).delete()
        ctx.store.reconcileStartup()
        // active + 磁盘缺失 → 删除行（死数据清理，不留 MISSING 中间态）
        assertTrue(ctx.deletedIds.isEmpty())
        assertEquals("missing active file row must be removed", listOf(3L), ctx.rowDeletedIds)
        ctx.root.deleteRecursively()
    }

    @Test
    fun `RS4 untracked disk file is not inserted`() = runTest {
        val ctx = reconcileStore(deleting = emptyList(), active = emptyList())
        // 关键前置：磁盘上存在一个 DB 无记录的 untracked 文件（"重启复活"缺陷的来源）
        File(ctx.root, "upload/untracked.png").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1)) }
        ctx.store.reconcileStartup()
        // 磁盘存在但无 DB 记录：仅日志，绝不补录（artifact 行数不变 → 无 insert）
        assertTrue("reconcile must never auto-insert", ctx.insertedIds.isEmpty())
        assertTrue(ctx.deletedIds.isEmpty())
        assertTrue(ctx.rowDeletedIds.isEmpty())
        ctx.root.deleteRecursively()
    }

    @Test
    fun `RS5 empty artifact table returns normally`() = runTest {
        val ctx = reconcileStore(deleting = emptyList(), active = emptyList())
        ctx.store.reconcileStartup()
        assertTrue(ctx.deletedIds.isEmpty())
        assertTrue(ctx.rowDeletedIds.isEmpty())
        ctx.root.deleteRecursively()
    }

    @Test
    fun `delete after row removed returns Rejected ALREADY_DELETED`() = runTest {
        val filesDir = tempDir("del-already")
        val target = entity(12, "upload/already.png")
        val settings = MutableStateFlow(Settings(assistants = emptyList()))
        val filesManager = mockk<FilesManager>()
        every { filesManager.getFile(target) } returns File(filesDir, target.relativePath)
        val dao = mockk<ArtifactDAO>()
        // CAS 返回 0，且 getById 返回 null（行已删）→ ALREADY_DELETED
        coEvery { dao.compareAndSetState(any(), any(), any(), any()) } returns 0
        coEvery { dao.getById(target.id) } returns null
        val settingsStore = mockk<SettingsStore>()
        every { settingsStore.settingsFlow } returns settings
        val store = ArtifactStore(
            filesManager = filesManager,
            artifactDAO = dao,
            artifactReferenceDAO = mockk(),
            systemMetaDAO = mockk(),
            conversationDAO = mockk(),
            messageNodeDAO = mockk(),
            settingsStore = settingsStore,
        )
        val result = store.deletePermanently(target)
        assertTrue(result is ArtifactDeleteResult.Rejected)
        assertEquals(ArtifactDeleteResult.RejectionReason.ALREADY_DELETED, (result as ArtifactDeleteResult.Rejected).reason)
        filesDir.deleteRecursively()
    }

    @Test
    fun `delete when state in progress returns Rejected IN_PROGRESS`() = runTest {
        val filesDir = tempDir("del-inprogress")
        val target = entity(13, "upload/inprogress.png")
        val settings = MutableStateFlow(Settings(assistants = emptyList()))
        val filesManager = mockk<FilesManager>()
        every { filesManager.getFile(target) } returns File(filesDir, target.relativePath)
        val dao = mockk<ArtifactDAO>()
        coEvery { dao.compareAndSetState(any(), any(), any(), any()) } returns 0
        // 行仍在（DELETING）→ IN_PROGRESS
        coEvery { dao.getById(target.id) } returns target.copy(state = ArtifactState.DELETING.name)
        val settingsStore = mockk<SettingsStore>()
        every { settingsStore.settingsFlow } returns settings
        val store = ArtifactStore(
            filesManager = filesManager,
            artifactDAO = dao,
            artifactReferenceDAO = mockk(),
            systemMetaDAO = mockk(),
            conversationDAO = mockk(),
            messageNodeDAO = mockk(),
            settingsStore = settingsStore,
        )
        val result = store.deletePermanently(target)
        assertTrue(result is ArtifactDeleteResult.Rejected)
        assertEquals(ArtifactDeleteResult.RejectionReason.IN_PROGRESS, (result as ArtifactDeleteResult.Rejected).reason)
        filesDir.deleteRecursively()
    }
}
