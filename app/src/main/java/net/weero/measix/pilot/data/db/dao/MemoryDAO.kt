package net.weero.measix.pilot.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import net.weero.measix.pilot.data.db.entity.MemoryEntity

/**
 * Memory 的唯一查询入口。
 *
 * 四条读取路径一律 `ORDER BY id ASC`：Memory 的插入顺序就是它的领域顺序，而
 * Disclosure Snapshot 的 canonical content 必须对同一业务数据逐字复现。
 * 未显式排序时 SQLite 不保证返回次序，同一份 Memory 可能渲染出不同 bytes 并追加伪 entry。
 * `getAllMemories*` 跨 owner 聚合，仍按全局 id 升序：分组由调用方按 assistant_id 完成，
 * 不改变行内次序。
 */
@Dao
interface MemoryDAO {
    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId ORDER BY id ASC")
    fun getMemoriesOfAssistantFlow(assistantId: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memoryentity WHERE assistant_id = :assistantId ORDER BY id ASC")
    suspend fun getMemoriesOfAssistant(assistantId: String): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity ORDER BY id ASC")
    fun getAllMemoriesFlow(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memoryentity ORDER BY id ASC")
    suspend fun getAllMemories(): List<MemoryEntity>

    @Query("SELECT * FROM memoryentity WHERE id = :id")
    suspend fun getMemoryById(id: Int): MemoryEntity?

    @Insert
    suspend fun insertMemory(memory: MemoryEntity): Long

    @Query("UPDATE memoryentity SET content = :content WHERE id = :id AND assistant_id = :assistantId")
    suspend fun updateMemoryContent(id: Int, content: String, assistantId: String): Int

    @Query("DELETE FROM memoryentity WHERE id = :id AND assistant_id = :assistantId")
    suspend fun deleteMemory(id: Int, assistantId: String): Int

    @Query("DELETE FROM memoryentity WHERE assistant_id = :assistantId")
    suspend fun deleteMemoriesOfAssistant(assistantId: String)
}
