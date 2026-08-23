package net.weero.measix.pilot.data.db.fts

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import net.weero.measix.pilot.data.db.AppDatabase
import net.weero.measix.pilot.data.model.Conversation
import net.weero.measix.pilot.data.model.MessageNode
import java.time.Instant

data class MessageSearchResult(
    val nodeId: String,
    val messageId: String,
    val conversationId: String,
    val title: String,
    val updateAt: Instant,
    val snippet: String,
)

enum class MessageSearchSort(val orderBy: String) {
    RELEVANCE("rank, update_at DESC"),
    NEWEST_FIRST("update_at DESC, rank"),
    OLDEST_FIRST("update_at ASC, rank"),
}

private const val TAG = "MessageFtsManager"

class MessageFtsManager(private val database: AppDatabase) {

    private val db get() = database.openHelper.writableDatabase

    suspend fun indexConversation(conversation: Conversation) = withContext(Dispatchers.IO) {
        val conversationId = conversation.id.toString()
        db.execSQL("DELETE FROM message_fts WHERE conversation_id = ?", arrayOf(conversationId))
        conversation.messageNodes.forEach { node ->
            node.messages.forEach { message ->
                val text = message.extractFtsText()
                if (text.isNotBlank()) {
                    db.execSQL(
                        "INSERT INTO message_fts(text, node_id, message_id, conversation_id, title, update_at) VALUES (?, ?, ?, ?, ?, ?)",
                        arrayOf(
                            text,
                            node.id.toString(),
                            message.id.toString(),
                            conversationId,
                            conversation.title,
                            conversation.updateAt.toEpochMilli().toString(),
                        )
                    )
                }
            }
        }
    }

    suspend fun deleteConversation(conversationId: String) = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM message_fts WHERE conversation_id = ?", arrayOf(conversationId))
    }

    /**
     * 节点级增量：删旧 FTS 行 + 插入当前内容行。
     * message_id 前缀 "<convId>:nodeId:" 对齐现有 schema。
     */
    suspend fun reindexNodes(
        conversationId: String,
        title: String,
        updateAt: Long,
        nodes: List<MessageNode>,
    ) = withContext(Dispatchers.IO) {
        nodes.forEach { node ->
            // 删旧行（按 conversation_id + node_id）
            db.execSQL(
                "DELETE FROM message_fts WHERE conversation_id = ? AND node_id = ?",
                arrayOf(conversationId, node.id.toString()),
            )
            node.messages.forEach { message ->
                val text = message.extractFtsText()
                if (text.isNotBlank()) {
                    db.execSQL(
                        "INSERT INTO message_fts(text, node_id, message_id, conversation_id, title, update_at) VALUES (?, ?, ?, ?, ?, ?)",
                        arrayOf(
                            text,
                            node.id.toString(),
                            message.id.toString(),
                            conversationId,
                            title,
                            updateAt.toString(),
                        )
                    )
                }
            }
        }
    }

    /** 节点删除的索引清理。 */
    suspend fun deleteNodesIndex(conversationId: String, nodeIds: List<kotlin.uuid.Uuid>) = withContext(Dispatchers.IO) {
        nodeIds.forEach { nodeId ->
            db.execSQL(
                "DELETE FROM message_fts WHERE conversation_id = ? AND node_id = ?",
                arrayOf(conversationId, nodeId.toString()),
            )
        }
    }

    /** Updates the denormalized conversation title without rewriting message rows. */
    suspend fun updateConversationTitle(conversationId: String, title: String) = withContext(Dispatchers.IO) {
        runCatching {
            db.execSQL(
                "UPDATE message_fts SET title = ? WHERE conversation_id = ?",
                arrayOf(title, conversationId),
            )
        }.onFailure { error ->
            Log.w(TAG, "updateConversationTitle failed for $conversationId", error)
        }
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        db.execSQL("DELETE FROM message_fts")
    }

    suspend fun search(
        keyword: String,
        sort: MessageSearchSort = MessageSearchSort.RELEVANCE,
        assistantId: String? = null,
    ): List<MessageSearchResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<MessageSearchResult>()
        val scopeClause = if (assistantId == null) {
            ""
        } else {
            """
            AND conversation_id IN (
                SELECT id FROM ConversationEntity
                WHERE assistant_id = ? AND parent_conversation_id IS NULL
            )
            """.trimIndent()
        }
        val cursor = db.query(
            """
            SELECT node_id, message_id, conversation_id, title, update_at,
                   simple_snippet(message_fts, 0, '[', ']', '...', 30) AS snippet
            FROM message_fts
            WHERE text MATCH jieba_query(?)
            $scopeClause
            ORDER BY ${sort.orderBy}
            LIMIT 50
            """.trimIndent(),
            if (assistantId == null) arrayOf(keyword) else arrayOf(keyword, assistantId)
        )
        Log.i(TAG, "search: $keyword")
        cursor.use {
            while (it.moveToNext()) {
                results.add(
                    MessageSearchResult(
                        nodeId = it.getString(0),
                        messageId = it.getString(1),
                        conversationId = it.getString(2),
                        title = it.getString(3),
                        updateAt = Instant.ofEpochMilli(it.getLong(4)),
                        snippet = it.getString(5),
                    )
                )
            }
        }
        results
    }
}

private fun UIMessage.extractFtsText(): String =
    parts.filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .take(10_000)
