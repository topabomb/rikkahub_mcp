package net.weero.measix.pilot.architecture

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.db.dao.ConversationDAO
import net.weero.measix.pilot.data.db.dao.FavoriteDAO
import net.weero.measix.pilot.data.db.dao.MessageNodeDAO
import net.weero.measix.pilot.data.db.dao.ToolExecutionDAO
import net.weero.measix.pilot.data.db.dao.TurnExecutionDAO
import net.weero.measix.pilot.data.db.entity.MessageNodeEntity
import net.weero.measix.pilot.data.db.entity.TurnExecutionEntity
import net.weero.measix.pilot.data.db.entity.TurnExecutionStatus
import net.weero.measix.pilot.data.db.fts.MessageFtsManager
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.service.runtime.ConversationMutation
import net.weero.measix.pilot.service.runtime.ExecutionFacts
import net.weero.measix.pilot.service.runtime.TurnExecutionOperation
import net.weero.measix.pilot.data.files.ArtifactReferenceDelta
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 检查点写放大契约测试（CheckpointWriteAmplification）。
 *
 * 断言：500 历史节点 + 50 次 CommitCheckpoint（每次只动 active node），
 * 累计 upsert 行数不随历史增长——即每 checkpoint 只写 O(changed) 而非 O(N)。
 *
 * 用计数 MessageNodeDAO（mockk relaxed + capture）精确记录 `upsertAll` 每次入参长度：
 *  - 首次全量写入 500 历史节点
 *  - 后续 50 次 checkpoint 每次只 upsert 1 行（active 节点）
 *  - 累计 upsert = 500 + 50，而非 500 + 50×501
 * 事务用真实 Room in-memory（`database.withTransaction` 可正常执行）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CheckpointWriteAmplificationTest {

    private lateinit var database: AppDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun `checkpoint upserts stay constant independent of history`() = runTest {
        val nodeDAO = mockk<MessageNodeDAO>(relaxed = true)
        val upsertCalls = mutableListOf<List<MessageNodeEntity>>()
        coEvery { nodeDAO.upsertAll(capture(upsertCalls)) } just runs

        val artifactStore = mockk<ArtifactStore>()
        coEvery { artifactStore.prepareReferenceDelta(any(), any()) } returns
            ArtifactReferenceDelta(emptyList(), emptyList(), emptyList())
        coEvery { artifactStore.withLifecycleLock<Any>(any()) } coAnswers {
            firstArg<suspend () -> Any>().invoke()
        }
        coEvery { artifactStore.applyReferenceDeltaInTransaction(any()) } just runs

        val repo = ConversationRepository(
            conversationDAO = database.conversationDao(),
            messageNodeDAO = nodeDAO, // 计数代理
            favoriteDAO = database.favoriteDao(),
            database = database, // 真实 Room（withTransaction 正常）
            messageFtsManager = mockk<MessageFtsManager>(relaxed = true),
            turnExecutionDAO = database.turnExecutionDao(),
            toolExecutionDAO = database.toolExecutionDao(),
            modelContextDAO = database.conversationModelContextDao(),
            artifactStore = artifactStore,
        )
        val conversationId = Uuid.random()

        // 首次全量写入 500 历史节点
        val historical = (0 until 500).map { i ->
            MessageNode.of(
                UIMessage(id = Uuid.random(), role = MessageRole.USER, parts = listOf(UIMessagePart.Text("hist-$i")))
            )
        }
        repo.applyMutation(
            ConversationMutation(
                conversationId,
                null,
                historical,
                emptyList(),
                0L,
                historical.indices.toList(),
            )
        )
        assertEquals("initial full write = 500", 500, upsertCalls.sumOf { it.size })

        // 50 次 checkpoint，每次只 upsert active node（新树下标 500，不随历史写成 0）
        val assistantId = Uuid.random()
        val activeNode = MessageNode.of(
            UIMessage(id = assistantId, role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("active")))
        )
        (0 until 50).forEach { i ->
            repo.applyMutation(
                ConversationMutation(
                    conversationId,
                    null,
                    listOf(activeNode),
                    emptyList(),
                    i.toLong(),
                    listOf(500),
                )
            )
        }

        // 累计 upsert = 500 + 50×1 = 550（每 checkpoint 只写 1 行，不随历史增长）
        val totalUpserts = upsertCalls.sumOf { it.size }
        assertEquals("accumulated upserts = initial + 50×1", 550, totalUpserts)
        assertTrue("every checkpoint upserts exactly 1 node", upsertCalls.drop(1).all { it.size == 1 })
        assertTrue(
            "checkpoint node_index is the new-tree position, not 0",
            upsertCalls.drop(1).all { it.single().nodeIndex == 500 },
        )
        coVerify(exactly = 51) { nodeDAO.upsertAll(any()) }
    }

    @Test
    fun `start turn fact failure rolls back its assistant slot`() = runTest {
        val conversationId = Uuid.random()
        val assistantMessageId = Uuid.random()
        database.conversationDao().insert(
            ConversationRepository(
                conversationDAO = database.conversationDao(),
                messageNodeDAO = database.messageNodeDao(),
                favoriteDAO = database.favoriteDao(),
                database = database,
                messageFtsManager = mockk(relaxed = true),
                turnExecutionDAO = database.turnExecutionDao(),
                toolExecutionDAO = database.toolExecutionDao(),
                modelContextDAO = database.conversationModelContextDao(),
                artifactStore = mockk(relaxed = true),
            ).conversationToConversationEntity(Conversation.ofId(conversationId)),
        )
        val artifactStore = mockk<ArtifactStore>()
        coEvery { artifactStore.prepareReferenceDelta(any(), any()) } returns
            ArtifactReferenceDelta(emptyList(), emptyList(), emptyList())
        coEvery { artifactStore.withLifecycleLock<Any>(any()) } coAnswers {
            firstArg<suspend () -> Any>().invoke()
        }
        coEvery { artifactStore.applyReferenceDeltaInTransaction(any()) } just runs
        val failingTurns = mockk<TurnExecutionDAO>()
        coEvery { failingTurns.insert(any()) } throws IllegalStateException("turn fact insert failed")
        val repository = ConversationRepository(
            conversationDAO = database.conversationDao(),
            messageNodeDAO = database.messageNodeDao(),
            favoriteDAO = database.favoriteDao(),
            database = database,
            messageFtsManager = mockk(relaxed = true),
            turnExecutionDAO = failingTurns,
            toolExecutionDAO = database.toolExecutionDao(),
            modelContextDAO = database.conversationModelContextDao(),
            artifactStore = artifactStore,
        )
        val assistantNode = MessageNode.of(
            UIMessage(
                id = assistantMessageId,
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("pending")),
            ),
        )
        val facts = ExecutionFacts(
            turn = TurnExecutionEntity(
                turnId = Uuid.random().toString(),
                conversationId = conversationId.toString(),
                assistantMessageId = assistantMessageId.toString(),
                status = TurnExecutionStatus.RUNNING,
                reason = null,
                createdAt = 1L,
                updatedAt = 1L,
            ),
            toolExecution = null,
            turnOperation = TurnExecutionOperation.START,
        )

        val failure = runCatching {
            repository.applyMutation(
                mutation = ConversationMutation(
                    conversationId = conversationId,
                    headerPatch = null,
                    upsertedNodes = listOf(assistantNode),
                    deletedNodeIds = emptyList(),
                    updateAt = 1L,
                    upsertedNodeIndices = listOf(0),
                ),
                executionFacts = facts,
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(database.messageNodeDao().getNodesOfConversation(conversationId.toString()).isEmpty())
    }
}
