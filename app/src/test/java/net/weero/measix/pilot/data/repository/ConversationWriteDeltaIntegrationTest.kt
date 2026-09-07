package net.weero.measix.pilot.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.coEvery
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlin.uuid.Uuid
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.db.dao.MessageNodeDAO
import net.weero.measix.pilot.data.db.entity.MessageNodeEntity
import net.weero.measix.pilot.data.files.ArtifactReferenceDelta
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.model.MessageNode
import net.weero.measix.pilot.service.runtime.ConversationMutation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Write-path complexity gate: [ConversationRepository.applyMutation] must hand the DAO only the
 * delta rows, each carrying its new-tree position (`node_index` is the reload ORDER BY), never a
 * full-tree rewrite and never the list index. This is the repository/DAO half of the
 * "1 changed node -> 1 node upsert" invariant; the transition-plan half lives in
 * TurnPersistenceDeltaTest. Real Room supplies the transaction; a counting DAO observes the write.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ConversationWriteDeltaIntegrationTest {

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
    fun `applyMutation writes only the delta rows with the new-tree node index`() = runTest {
        val nodeDAO = mockk<MessageNodeDAO>(relaxed = true)
        val upserts = mutableListOf<List<MessageNodeEntity>>()
        coEvery { nodeDAO.upsertAll(capture(upserts)) } just runs

        val artifactStore = mockk<ArtifactStore>()
        coEvery { artifactStore.prepareReferenceDelta(any(), any()) } returns
            ArtifactReferenceDelta(emptyList(), emptyList(), emptyList())
        coEvery { artifactStore.withLifecycleLock<Any>(any()) } coAnswers {
            firstArg<suspend () -> Any>().invoke()
        }
        coEvery { artifactStore.applyReferenceDeltaInTransaction(any()) } just runs

        val repository = ConversationRepository(
            conversationDAO = database.conversationDao(),
            messageNodeDAO = nodeDAO,
            favoriteDAO = database.favoriteDao(),
            database = database,
            messageFtsManager = mockk(relaxed = true),
            turnExecutionDAO = database.turnExecutionDao(),
            toolExecutionDAO = database.toolExecutionDao(),
            modelContextDAO = database.conversationModelContextDao(),
            artifactStore = artifactStore,
        )
        val conversationId = Uuid.random()

        // Seed a 50-node tree (positions 0..49).
        val tree = (0 until 50).map { MessageNode.of(UIMessage.user("node-$it")) }
        repository.applyMutation(
            ConversationMutation(
                conversationId = conversationId,
                headerPatch = null,
                upsertedNodes = tree,
                deletedNodeIds = emptyList(),
                updateAt = 0L,
                upsertedNodeIndices = tree.indices.toList(),
                indexForSearch = false,
            ),
        )

        // A checkpoint delta replaces only the owning Assistant at the new-tree tail (index 49).
        val active = MessageNode.of(
            UIMessage(
                id = Uuid.random(),
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("answer")),
            ),
        )
        repository.applyMutation(
            ConversationMutation(
                conversationId = conversationId,
                headerPatch = null,
                upsertedNodes = listOf(active),
                deletedNodeIds = emptyList(),
                updateAt = 1L,
                upsertedNodeIndices = listOf(49),
                indexForSearch = false,
            ),
        )

        val deltaWrite = upserts.last()
        assertEquals("the delta write must carry only the changed node", 1, deltaWrite.size)
        assertEquals("node_index must be the new-tree position, not the list index", 49, deltaWrite.single().nodeIndex)
    }
}
