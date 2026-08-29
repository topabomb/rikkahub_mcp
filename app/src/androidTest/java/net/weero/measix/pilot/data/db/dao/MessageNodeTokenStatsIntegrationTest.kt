package net.weero.measix.pilot.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import kotlinx.coroutines.runBlocking
import net.weero.measix.pilot.data.db.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class MessageNodeTokenStatsIntegrationTest {
    private lateinit var database: AppDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        val sqlite = database.openHelper.writableDatabase
        sqlite.execSQL(
            "INSERT INTO conversationentity (id, assistant_id, title, create_at, update_at) " +
                "VALUES ('master', 'assistant', '', 0, 0)"
        )
        sqlite.execSQL(
            "INSERT INTO conversationentity " +
                "(id, assistant_id, title, create_at, update_at, parent_conversation_id) " +
                "VALUES ('child', 'assistant', '', 0, 0, 'master')"
        )
        sqlite.execSQL(
            "INSERT INTO message_node (id, conversation_id, node_index, messages, select_index) " +
                "VALUES ('master-node', 'master', 0, ?, 0)",
            arrayOf(
                """[
                  {"role":"assistant","usage":{"promptTokens":10,"completionTokens":2,"cachedTokens":5}},
                  {"role":"assistant","usage":{"promptTokens":20,"completionTokens":3,"cachedTokens":10,"coreCompleteness":"COMPLETE","cacheReadCompleteness":"COMPLETE"}},
                  {"role":"assistant","usage":{"promptTokens":7,"completionTokens":1,"coreCompleteness":"PARTIAL","cacheReadCompleteness":"NONE"}},
                  {"role":"assistant"},
                  {"role":"user"}
                ]""".trimIndent()
            )
        )
        sqlite.execSQL(
            "INSERT INTO message_node (id, conversation_id, node_index, messages, select_index) " +
                "VALUES ('child-node', 'child', 0, ?, 0)",
            arrayOf(
                """[
                  {"role":"assistant","usage":{"promptTokens":30,"completionTokens":4,"cachedTokens":20,"coreCompleteness":"COMPLETE","cacheReadCompleteness":"COMPLETE"}}
                ]""".trimIndent()
            )
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun tokenStatsExecuteLegacyCompletePartialAndMissingUsageSemantics() = runBlocking {
        val stats = database.messageNodeDao().getTokenStats()

        assertEquals(5, stats.totalMessages)
        assertEquals(67L, stats.inputTokens)
        assertEquals(10L, stats.outputTokens)
        assertEquals(35L, stats.cacheReadInputTokens)
        assertEquals(3, stats.coreNonExactMessages)
        assertEquals(3, stats.cacheReadNonExactMessages)
    }
}
