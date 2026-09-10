package net.weero.measix.pilot.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import net.weero.measix.pilot.data.db.entity.FavoriteEntity
import net.weero.measix.pilot.data.configuration.ConfigurationScope

@Dao
interface FavoriteDAO {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(favorite: FavoriteEntity)

    @Query("SELECT * FROM favorites WHERE scope = :scope AND type = :type ORDER BY created_at DESC")
    fun listByType(scope: ConfigurationScope, type: String): Flow<List<FavoriteEntity>>

    @Query("SELECT substr(ref_key, length('node:' || :conversationId || ':') + 1) FROM favorites WHERE ref_key LIKE 'node:' || :conversationId || ':%'")
    suspend fun getFavoriteNodeIdsOfConversation(conversationId: String): List<String>

    @Query("SELECT * FROM favorites WHERE scope = :scope AND ref_key = :refKey LIMIT 1")
    suspend fun getByRefKey(scope: ConfigurationScope, refKey: String): FavoriteEntity?

    @Query("DELETE FROM favorites WHERE scope = :scope AND ref_key = :refKey")
    suspend fun deleteByRefKey(scope: ConfigurationScope, refKey: String): Int

    @Query("DELETE FROM favorites WHERE ref_key LIKE 'node:' || :conversationId || ':%'")
    suspend fun deleteNodeFavoritesOfConversation(conversationId: String): Int

    /** 删除指定 node 的 favorites（applyMutation 增量节点删除用；ref_key = "node:<conversationId>:<nodeId>"）。 */
    @Query("DELETE FROM favorites WHERE ref_key IN (:refKeys)")
    suspend fun deleteNodeFavoritesByRefKeys(refKeys: List<String>): Int

}
