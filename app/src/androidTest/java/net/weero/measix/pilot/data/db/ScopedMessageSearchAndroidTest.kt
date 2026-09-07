package net.weero.measix.pilot.data.db

import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.configuration.EnterpriseAuthority
import net.weero.measix.pilot.data.configuration.ConfigurationScope
import net.weero.measix.pilot.data.db.entity.ConversationEntity
import net.weero.measix.pilot.data.db.fts.MessageFtsManager
import net.weero.measix.pilot.data.db.fts.MessageSearchSort
import net.weero.measix.pilot.data.model.MessageNode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.uuid.Uuid

/** Exercises the application's Requery, simple tokenizer and Jieba queries on the device. */
@RunWith(AndroidJUnit4::class)
class ScopedMessageSearchAndroidTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "scoped-search-${Uuid.random()}.db"
    private lateinit var database: AppDatabase
    private lateinit var search: MessageFtsManager

    @Before fun setUp() {
        database = createAppDatabase(context, name)
        search = MessageFtsManager(database)
    }

    @After fun tearDown() {
        database.close()
        context.deleteDatabase(name)
    }

    @Test fun nativeSearchFiltersCompleteScopeAndChildrenBeforeLimitForEverySort() = runBlocking {
        val scopes = listOf(
            ConfigurationScope.Personal,
            ConfigurationScope.Enterprise(EnterpriseAuthority("local:example", "deployment"), "alice"),
            ConfigurationScope.Enterprise(EnterpriseAuthority("local:example", "deployment"), "bob"),
            ConfigurationScope.Enterprise(EnterpriseAuthority("platform:example", "deployment"), "alice"),
            ConfigurationScope.Enterprise(EnterpriseAuthority("local:example", "other"), "alice"),
        )
        val assistant = Uuid.random().toString()
        val otherAssistant = Uuid.random().toString()
        val roots = scopes.map { scope ->
            val root = insert(scope, assistant)
            insert(scope, assistant, root)
            root
        }
        // Foreign hits outnumber the global cap, including both newer and older timestamps.
        repeat(60) { insert(scopes[2], assistant) }
        val other = insert(scopes[1], otherAssistant)
        for (sort in MessageSearchSort.entries) {
            for (index in listOf(0, 1, 3, 4)) {
                val expected = if (index == 1) setOf(roots[index], other) else setOf(roots[index])
                assertEquals(expected, search.search(scopes[index], "boundary", sort).map { it.conversationId }.toSet())
                assertEquals(setOf(roots[index]), search.search(scopes[index], "boundary", sort, assistant).map { it.conversationId }.toSet())
            }
        }
        assertEquals(setOf(roots[0]), search.search(scopes[0], "企业", MessageSearchSort.RELEVANCE).map { it.conversationId }.toSet())
    }

    private suspend fun insert(scope: ConfigurationScope, assistant: String, parent: String? = null): String {
        val id = Uuid.random().toString()
        val row = ConversationEntity(id = id, assistantId = assistant, title = "Search owner",
            createAt = 0, updateAt = 0, chatSuggestions = "[]", isPinned = false,
            parentConversationId = parent, scope = scope)
        val nodes = listOf(MessageNode(messages = listOf(UIMessage(role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("boundary 企业查询隔离"))))))
        database.withTransaction {
            database.conversationDao().insert(row)
            search.reindexNodesInTransaction(id, row.title, row.updateAt, nodes)
        }
        return id
    }
}
