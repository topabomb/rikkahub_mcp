package net.weero.measix.pilot.architecture

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.mockk
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.datastore.DEFAULT_ASSISTANT_ID
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.db.fts.MessageFtsManager
import net.weero.measix.pilot.data.files.ArtifactStore
import net.weero.measix.pilot.data.files.FilesManager
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.repository.ConversationRepository
import net.weero.measix.pilot.service.runtime.BeginTurn
import net.weero.measix.pilot.service.runtime.ConversationRuntime
import net.weero.measix.pilot.service.runtime.OptionalFolderId
import net.weero.measix.pilot.service.runtime.UpdateHeader
import net.weero.measix.pilot.service.runtime.toSnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * folder 覆盖缺陷消除契约测试。
 * streaming delta 与 `UpdateHeader(folderId)` 交错 → DB `folder_id` 终值为命令值（窄列更新，非整对象回写）。
 * 覆盖消除由 reducer 基于最新 state 应用 + applyHeaderPatch 窄列落库保证（架构不变式 1/2）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FolderOwnershipTest {

    private lateinit var database: AppDatabase
    private lateinit var repo: ConversationRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = ConversationRepository(
            conversationDAO = database.conversationDao(),
            messageNodeDAO = database.messageNodeDao(),
            favoriteDAO = database.favoriteDao(),
            database = database,
            filesManager = mockk(relaxed = true),
            messageFtsManager = mockk(relaxed = true),
            turnExecutionDAO = database.turnExecutionDao(),
            toolExecutionDAO = database.toolExecutionDao(),
            artifactStore = mockk(relaxed = true),
        )
    }

    @After
    fun teardown() {
        database.close()
    }

    private fun user(text: String): UIMessage =
        UIMessage(id = Uuid.random(), role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)))

    @Test
    fun `I3 interleaved streaming and UpdateHeader yields command folderId in db`() = runTest {
        val conversationId = Uuid.random()
        val initial = Conversation.ofId(conversationId, assistantId = DEFAULT_ASSISTANT_ID)
        repo.insertConversation(initial)

        val scope = CoroutineScope(Dispatchers.Default)
        val rt = ConversationRuntime(
            id = conversationId,
            initial = initial.toSnapshot(),
            scope = scope,
            onIdle = {},
            repository = repo,
        )
        val folder = Uuid.random()

        // 交错：先起 turn + 流式 delta，再提交 folder 命令
        rt.submit(BeginTurn(Uuid.random(), Uuid.random(), null, resume = false, onStart = true))
        rt.applyStreamingDelta(Uuid.random(), Uuid.random(), listOf(user("streamed")))
        rt.submit(UpdateHeader(folderId = OptionalFolderId.SetTo(folder)))
        rt.applyStreamingDelta(Uuid.random(), Uuid.random(), listOf(user("more")))

        // DB 终值为命令值（窄列 folder_id 更新）
        val persisted = database.conversationDao().getConversationById(conversationId.toString())
        assertEquals(folder.toString(), persisted?.folderId)
        scope.cancel()
    }
}
