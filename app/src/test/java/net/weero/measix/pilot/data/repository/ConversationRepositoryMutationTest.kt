package net.weero.measix.pilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.db.dao.FavoriteDAO
import net.weero.measix.pilot.data.db.dao.MessageNodeDAO
import net.weero.measix.pilot.data.db.entity.ToolExecutionEntity
import net.weero.measix.pilot.data.db.entity.ToolExecutionStatus
import net.weero.measix.pilot.data.db.entity.TurnExecutionEntity
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.db.fts.MessageFtsManager
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.FilesManager
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.service.runtime.AppendUserMessage
import net.weero.measix.pilot.service.runtime.BeginTurn
import net.weero.measix.pilot.service.runtime.CommitCheckpoint
import net.weero.measix.pilot.service.runtime.ConversationHeaderPatch
import net.weero.measix.pilot.service.runtime.ConversationMutation
import net.weero.measix.pilot.service.runtime.ConversationRuntime
import net.weero.measix.pilot.service.runtime.ExecutionFacts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * ConversationRepository.applyMutation 权威测试。
 * 用 Robolectric + Room in-memory 真实 DB 验证事务行为（withTransaction 无法被 mockk stub）。
 * 覆盖：delta 持久化（零写入/命中）、级联清理、执行事实同事务、失败重试、替换语义、GC 窗口。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ConversationRepositoryMutationTest {

    private lateinit var database: AppDatabase
    private lateinit var messageNodeDAO: MessageNodeDAO
    private lateinit var favoriteDAO: FavoriteDAO
    private lateinit var ftsManager: MessageFtsManager
    private lateinit var artifactStore: ArtifactStore

    private fun user(text: String): UIMessage =
        UIMessage(id = Uuid.random(), role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)))

    private fun node(text: String): MessageNode = MessageNode.of(user(text))

    private fun mutation(
        id: Uuid,
        nodes: List<MessageNode> = emptyList(),
        deleted: List<Uuid> = emptyList(),
        indices: List<Int> = nodes.indices.toList(),
        header: ConversationHeaderPatch? = null,
        updateAt: Long = System.currentTimeMillis(),
    ) = ConversationMutation(
        conversationId = id,
        headerPatch = header,
        upsertedNodes = nodes,
        deletedNodeIds = deleted,
        updateAt = updateAt,
        upsertedNodeIndices = indices,
    )

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        messageNodeDAO = database.messageNodeDao()
        favoriteDAO = database.favoriteDao()
        ftsManager = mockk(relaxed = true)
        artifactStore = mockk(relaxed = true)
    }

    @After
    fun teardown() {
        database.close()
    }

    private fun repo(): ConversationRepository = ConversationRepository(
        conversationDAO = database.conversationDao(),
        messageNodeDAO = messageNodeDAO,
        favoriteDAO = favoriteDAO,
        database = database,
        filesManager = mockk(relaxed = true),
        messageFtsManager = ftsManager,
        turnExecutionDAO = database.turnExecutionDao(),
        toolExecutionDAO = database.toolExecutionDao(),
        artifactStore = artifactStore,
    )

    private suspend fun insertConversation(id: Uuid): Conversation {
        val conversation = Conversation.ofId(id, assistantId = Uuid.random()).copy(title = "title")
        repo().insertConversation(conversation)
        return conversation
    }

    /** 构造一个在 upsertAll 上抛异常、其余行为正常的 repo（用于 C-4 事务回滚验证）。 */
    private fun throwingNodeRepo(): Pair<ConversationRepository, MessageNodeDAO> {
        val nodeDAO = mockk<MessageNodeDAO>(relaxed = true)
        coEvery { nodeDAO.upsertAll(any()) } throws RuntimeException("forced write failure")
        val repository = ConversationRepository(
            conversationDAO = database.conversationDao(),
            messageNodeDAO = nodeDAO,
            favoriteDAO = favoriteDAO,
            database = database,
            filesManager = mockk(relaxed = true),
            messageFtsManager = ftsManager,
            turnExecutionDAO = database.turnExecutionDao(),
            toolExecutionDAO = database.toolExecutionDao(),
            artifactStore = artifactStore,
        )
        return repository to nodeDAO
    }

    @Test
    fun `C1 header-only mutation writes zero nodes`() = runTest {
        val id = Uuid.random()
        insertConversation(id)
        val repo = repo()
        val mutation = mutation(id, header = ConversationHeaderPatch(title = "new-title"))
        val written = repo.applyMutation(mutation)
        assertTrue(written)
        assertEquals(0, messageNodeDAO.getNodesOfConversation(id.toString()).size)
        val updated = database.conversationDao().getConversationById(id.toString())
        assertEquals("new-title", updated?.title)
    }

    @Test
    fun `C2 append mutation persists only the appended node`() = runTest {
        val id = Uuid.random()
        insertConversation(id)
        val repo = repo()
        val appended = node("hello")
        repo.applyMutation(mutation(id, nodes = listOf(appended), indices = listOf(0)))
        val nodes = messageNodeDAO.getNodesOfConversation(id.toString())
        assertEquals(1, nodes.size)
        assertEquals(appended.id.toString(), nodes[0].id)
        assertEquals(0, nodes[0].nodeIndex)
    }

    @Test
    fun `C2 append on non-empty tree persists new-tree node_index`() = runTest {
        val id = Uuid.random()
        val existing = listOf(node("a"), node("b"), node("c"))
        val conversation = Conversation.ofId(id, assistantId = Uuid.random()).copy(
            title = "title",
            messageNodes = existing,
        )
        val repo = repo()
        repo.insertConversation(conversation)
        val scope = CoroutineScope(Job())
        val runtime = ConversationRuntime(
            id = id,
            initial = conversation,
            scope = scope,
            onIdle = {},
            repository = repo,
        )
        val appended = user("appended-tail")
        runtime.submit(AppendUserMessage(appended))
        val rows = messageNodeDAO.getNodesOfConversation(id.toString()).sortedBy { it.nodeIndex }
        assertEquals(4, rows.size)
        assertEquals(listOf(0, 1, 2, 3), rows.map { it.nodeIndex })
        assertEquals(existing[0].id.toString(), rows[0].id)
        assertEquals(existing[1].id.toString(), rows[1].id)
        assertEquals(existing[2].id.toString(), rows[2].id)
        assertTrue(
            "appended user message is stored on the new-tree last node",
            rows[3].messages.contains("appended-tail"),
        )
        scope.cancel()
    }

    @Test
    fun `C3 delete mutation removes node row`() = runTest {
        val id = Uuid.random()
        insertConversation(id)
        val repo = repo()
        val existing = node("to-delete")
        repo.applyMutation(mutation(id, nodes = listOf(existing), indices = listOf(0)))
        assertEquals(1, messageNodeDAO.getNodesOfConversation(id.toString()).size)
        repo.applyMutation(mutation(id, deleted = listOf(existing.id)))
        assertEquals(0, messageNodeDAO.getNodesOfConversation(id.toString()).size)
    }

    @Test
    fun `C4 execution facts committed atomically with nodes and rolled back on write failure`() = runTest {
        val id = Uuid.random()
        insertConversation(id)
        val repo = repo()
        val turn = TurnExecutionEntity(
            turnId = "t1", conversationId = id.toString(), assistantMessageId = "am1",
            status = TurnExecutionStatus.RUNNING, reason = null,
            createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(),
        )
        val tool = ToolExecutionEntity(
            executionId = "e1", turnId = "t1", toolOrdinal = 0,
            status = ToolExecutionStatus.STARTED, reason = null,
            createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(),
        )
        // 同事务提交：节点 + 执行事实一起落盘
        repo.applyMutation(
            mutation(id, nodes = listOf(node("hi")), indices = listOf(0)),
            ExecutionFacts(turn = turn, toolExecution = tool),
        )
        val persistedTurn = database.turnExecutionDao().getById("t1")
        assertEquals(TurnExecutionStatus.RUNNING, persistedTurn?.status)
        val persistedTool = database.toolExecutionDao().getById("e1")
        assertEquals(ToolExecutionStatus.STARTED, persistedTool?.status)

        // 事务回滚：节点写失败 → 执行事实随之回滚（nodes 与 turn/tool_execution 均无残留行）
        val (throwingRepo, _) = throwingNodeRepo()
        val turn2 = TurnExecutionEntity(
            turnId = "t2", conversationId = id.toString(), assistantMessageId = "am2",
            status = TurnExecutionStatus.RUNNING, reason = null,
            createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(),
        )
        val thrown = runCatching {
            throwingRepo.applyMutation(
                mutation(id, nodes = listOf(node("rollback")), indices = listOf(0)),
                ExecutionFacts(turn = turn2, toolExecution = null),
            )
        }.exceptionOrNull()
        assertTrue("expected a write failure to propagate", thrown != null)
        assertNull("node write failure rolls back the turn fact", database.turnExecutionDao().getById("t2"))
    }

    @Test
    fun `C5 persist failure retries with previous delta included`() = runTest {
        // 持久化失败一次 → 再次 submit 任意命令 → 重试差异包含上次未落盘变更（失败不丢 delta）。
        // 通过 ConversationRuntime 的持久化基线（persistedState）驱动：失败期间内存前进而基线停驻，
        // 重试 diff 以基线为起点，structural sharing 不会跳过未落盘节点。
        val id = Uuid.random()
        val conversation = Conversation.ofId(id, assistantId = Uuid.random())
        repo().insertConversation(conversation)

        val persistingRepo = mockk<ConversationRepository>()
        val persistedMutations = mutableListOf<ConversationMutation>()
        var failedOnce = false
        coEvery { persistingRepo.getTurnExecution(any()) } returns null
        coEvery { persistingRepo.applyMutation(any(), any()) } answers {
            val mutation: ConversationMutation = firstArg()
            val hasText = mutation.upsertedNodes.any { n ->
                n.messages.any { m -> m.parts.any { it is UIMessagePart.Text } }
            }
            if (!failedOnce && hasText) {
                failedOnce = true
                throw RuntimeException("forced persist failure")
            }
            persistedMutations.add(mutation)
            true
        }

        val scope = CoroutineScope(Job())
        val runtime = ConversationRuntime(
            id = id,
            initial = conversation,
            scope = scope,
            onIdle = {},
            repository = persistingRepo,
        )
        val turnId = Uuid.random()
        val assistantMessageId = Uuid.random()
        runtime.submit(BeginTurn(turnId, assistantMessageId, null, resume = false, onStart = true))

        // 第一次 checkpoint（带内容）持久化失败：内存前进而 DB 停留在 BeginTurn 空槽
        val messageA = UIMessage(
            id = assistantMessageId,
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("delta-a")),
        )
        runtime.submit(
            CommitCheckpoint(turnId, assistantMessageId, listOf(messageA), TurnExecutionStatus.RUNNING, null, null)
        )
        assertTrue("failure must mark the runtime dirty", runtime.isDirty())

        // 再次 submit（checkpoint 追加 B）：重试差异必须同时包含 A（上次未落盘）与 B（本次）
        val messageB = UIMessage(
            id = Uuid.random(),
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("delta-b")),
        )
        runtime.submit(
            CommitCheckpoint(turnId, assistantMessageId, listOf(messageA, messageB), TurnExecutionStatus.RUNNING, null, null)
        )
        assertFalse("successful retry clears the dirty flag", runtime.isDirty())

        val retried = persistedMutations.last()
        val retriedMessageIds = retried.upsertedNodes
            .flatMap { node -> node.messages.map { it.id } }
            .toSet()
        assertTrue(
            "retry must include the previously unpersisted delta A",
            assistantMessageId in retriedMessageIds,
        )
        assertTrue("retry must include the new delta B", messageB.id in retriedMessageIds)
        scope.cancel()
    }

    @Test
    fun `C6 node no longer referencing artifact replaces its reference via syncReferences`() = runTest {
        val id = Uuid.random()
        insertConversation(id)
        val repo = repo()
        // 节点原先引用 artifact X（file:// URI），checkpoint 后消息不再包含该 URI
        val oldNode = MessageNode(
            id = Uuid.random(),
            messages = listOf(
                UIMessage(
                    id = Uuid.random(),
                    role = MessageRole.USER,
                    parts = listOf(UIMessagePart.Image(url = "file:///data/files/upload/x.png")),
                ),
            ),
            selectIndex = 0,
        )
        val newNode = oldNode.copy(
            messages = listOf(UIMessage(id = Uuid.random(), role = MessageRole.USER, parts = listOf(UIMessagePart.Text("no file")))),
        )
        repo.applyMutation(mutation(id, nodes = listOf(oldNode), indices = listOf(0)))
        // 替换语义：同一节点 upsert 后消息不再含 X → syncReferences 以"删旧+插新"替换（非纯 INSERT）
        repo.applyMutation(mutation(id, nodes = listOf(newNode), indices = listOf(0)))
        coVerify(exactly = 2) { artifactStore.syncReferences(id, any(), any()) }
        // 触发替换路径：syncReferences 收到的是替换后的节点（不再含 X）
        coVerify {
            artifactStore.syncReferences(id, listOf(newNode), emptyList())
        }
    }

    @Test
    fun `C7 insertConversationTree registers references for all forked conversations`() = runTest {
        val masterId = Uuid.random()
        val childId = Uuid.random()
        val master = Conversation.ofId(masterId, assistantId = Uuid.random())
            .copy(title = "master", messageNodes = listOf(node("master-msg")))
        val child = Conversation.ofId(childId, assistantId = Uuid.random())
            .copy(title = "child", parentConversationId = masterId, messageNodes = listOf(node("child-msg")))
        repo().insertConversationTree(master, listOf(child))
        // fork 入口无悬挂：master 与每个 child 的节点引用都登记
        coVerify { artifactStore.syncReferences(masterId, master.messageNodes, emptyList()) }
        coVerify { artifactStore.syncReferences(childId, child.messageNodes, emptyList()) }
        // 节点确实落库
        assertEquals(1, database.messageNodeDao().getNodesOfConversation(masterId.toString()).size)
        assertEquals(1, database.messageNodeDao().getNodesOfConversation(childId.toString()).size)
    }

    @Test
    fun `C8 collectUnreferencedArtifacts delegates to ArtifactStore`() = runTest {
        coEvery { artifactStore.collectUnreferencedArtifacts(any()) } returns emptyList()
        val repo = repo()
        val collected = repo.collectUnreferencedArtifacts()
        assertEquals(emptyList<Any>(), collected)
        coVerify { artifactStore.collectUnreferencedArtifacts(any()) }
    }
}
