package net.weero.measix.pilot.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import net.weero.measix.pilot.data.db.entity.ArtifactEntity

@Dao
interface ArtifactDAO {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(file: ArtifactEntity): Long

    @Update
    suspend fun update(file: ArtifactEntity)

    @Query("SELECT * FROM artifact WHERE id = :id")
    suspend fun getById(id: Long): ArtifactEntity?

    @Query("SELECT * FROM artifact WHERE relative_path = :relativePath")
    suspend fun getByPath(relativePath: String): ArtifactEntity?

    @Query("SELECT * FROM artifact WHERE folder = :folder ORDER BY created_at DESC")
    fun listByFolder(folder: String): Flow<List<ArtifactEntity>>

    @Query("DELETE FROM artifact WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM artifact WHERE relative_path = :relativePath")
    suspend fun deleteByPath(relativePath: String): Int

    @Query("DELETE FROM artifact WHERE folder = :folder")
    suspend fun deleteByFolder(folder: String): Int

    /** CAS 幂等屏障：返回受影响行数（1 = 获得执行权；0 = 状态已变迁）。 */
    @Query("UPDATE artifact SET state = :state, updated_at = :now WHERE id = :artifactId AND state = :expectedState")
    suspend fun compareAndSetState(artifactId: Long, expectedState: String, state: String, now: Long): Int

    /** reconcileStartup 专用（冷启动一次）。 */
    @Query("SELECT * FROM artifact WHERE state = :state")
    suspend fun listByState(state: String): List<ArtifactEntity>

    @Query("SELECT * FROM artifact WHERE state = :state AND created_at <= :createdBefore")
    suspend fun listByStateCreatedBefore(state: String, createdBefore: Long): List<ArtifactEntity>
}
