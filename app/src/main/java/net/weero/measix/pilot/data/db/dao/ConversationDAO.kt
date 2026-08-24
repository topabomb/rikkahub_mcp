package net.weero.measix.pilot.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import net.weero.measix.pilot.data.db.entity.ConversationEntity

@Dao
interface ConversationDAO {
    @Query("SELECT * FROM conversationentity WHERE parent_conversation_id IS NULL AND assistant_id = :assistantId ORDER BY is_pinned DESC, update_at DESC")
    fun getConversationsOfAssistant(assistantId: String): Flow<List<ConversationEntity>>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, folder_id as folderId FROM conversationentity WHERE parent_conversation_id IS NULL AND assistant_id = :assistantId AND folder_id = '' ORDER BY is_pinned DESC, update_at DESC")
    fun getUnfiledConversationsOfAssistantPaging(assistantId: String): PagingSource<Int, LightConversationEntity>

    @Query("SELECT id, assistant_id as assistantId, title, is_pinned as isPinned, create_at as createAt, update_at as updateAt, folder_id as folderId FROM conversationentity WHERE parent_conversation_id IS NULL AND folder_id = :folderId ORDER BY is_pinned DESC, update_at DESC")
    fun getConversationsOfFolderPaging(folderId: String): PagingSource<Int, LightConversationEntity>

    @Query("SELECT * FROM conversationentity WHERE parent_conversation_id IS NULL AND assistant_id = :assistantId ORDER BY is_pinned DESC, update_at DESC LIMIT :limit")
    suspend fun getRecentConversationsOfAssistant(assistantId: String, limit: Int): List<ConversationEntity>

    @Query("SELECT id FROM conversationentity WHERE parent_conversation_id IS NULL")
    suspend fun getAllIds(): List<String>

    @Query("SELECT id FROM conversationentity WHERE parent_conversation_id IS NULL AND folder_id = :folderId")
    suspend fun getIdsByFolder(folderId: String): List<String>

    @Query("SELECT * FROM conversationentity WHERE id = :id")
    suspend fun getConversationById(id: String): ConversationEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM conversationentity WHERE id = :id)")
    suspend fun existsById(id: String): Boolean

    @Insert
    suspend fun insert(conversation: ConversationEntity)

    @Query("UPDATE conversationentity SET update_at = :updateAt WHERE id = :id")
    suspend fun updateTimestamp(id: String, updateAt: Long): Int

    @Query("DELETE FROM conversationentity WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM conversationentity WHERE parent_conversation_id IS NULL AND is_pinned = 1 ORDER BY update_at DESC")
    fun getPinnedConversations(): Flow<List<ConversationEntity>>

    @Query("UPDATE conversationentity SET is_pinned = :isPinned WHERE id = :id")
    suspend fun updatePinStatus(id: String, isPinned: Boolean)

    @Query("UPDATE conversationentity SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: String, title: String)

    @Query("UPDATE conversationentity SET suggestions = :chatSuggestions WHERE id = :id")
    suspend fun updateChatSuggestions(id: String, chatSuggestions: String)

    @Query("UPDATE conversationentity SET folder_id = :folderId WHERE id = :id")
    suspend fun updateFolderId(id: String, folderId: String)

    @Query("UPDATE conversationentity SET assistant_id = :assistantId WHERE id = :id")
    suspend fun updateAssistantId(id: String, assistantId: String)

    @Query("UPDATE conversationentity SET custom_system_prompt = :prompt WHERE id = :id")
    suspend fun updateCustomSystemPrompt(id: String, prompt: String)

    @Query("UPDATE conversationentity SET mode_injection_ids = :ids WHERE id = :id")
    suspend fun updateModeInjectionIds(id: String, ids: String)

    @Query("UPDATE conversationentity SET workspace_cwd = :cwd WHERE id = :id")
    suspend fun updateWorkspaceCwd(id: String, cwd: String)

    @Query("SELECT COUNT(*) FROM conversationentity WHERE parent_conversation_id IS NULL")
    suspend fun countAll(): Int

    // ---- Child Conversation 查询 ----

    @Query("SELECT * FROM conversationentity WHERE parent_conversation_id = :parentConversationId")
    suspend fun getChildConversations(parentConversationId: String): List<ConversationEntity>

    @Query("SELECT id FROM conversationentity WHERE parent_conversation_id = :parentConversationId")
    suspend fun getChildConversationIds(parentConversationId: String): List<String>

    @Query("SELECT * FROM conversationentity ORDER BY update_at DESC")
    suspend fun getAllConversations(): List<ConversationEntity>
}

/**
 * 轻量级的会话查询结果，不包含 nodes 和 suggestions 字段
 */
data class LightConversationEntity(
    val id: String,
    val assistantId: String,
    val title: String,
    val isPinned: Boolean,
    val createAt: Long,
    val updateAt: Long,
    val folderId: String,
)
