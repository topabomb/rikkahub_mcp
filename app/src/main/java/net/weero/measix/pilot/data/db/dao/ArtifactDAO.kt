package net.weero.measix.pilot.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import net.weero.measix.pilot.data.db.entity.ArtifactEntity

@Dao
interface ArtifactDAO {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(file: ArtifactEntity): Long

    @Query("SELECT * FROM artifact WHERE id = :id")
    suspend fun getById(id: Long): ArtifactEntity?

    @Query("SELECT * FROM artifact WHERE relative_path = :relativePath AND state = :state")
    suspend fun getByPathAndState(relativePath: String, state: String): ArtifactEntity?

    @Query("SELECT id FROM artifact WHERE id IN (:ids) AND state = :state")
    suspend fun getIdsByState(ids: List<Long>, state: String): List<Long>

    @Query("SELECT * FROM artifact WHERE folder = :folder AND state = 'ACTIVE' ORDER BY created_at DESC")
    fun listActiveByFolder(folder: String): Flow<List<ArtifactEntity>>

    /** Lifecycle/recovery-only query; UI read ports must use listActiveByFolder. */
    @Query("SELECT * FROM artifact WHERE folder = :folder ORDER BY created_at DESC")
    fun listAllStatesByFolder(folder: String): Flow<List<ArtifactEntity>>

    @Query("DELETE FROM artifact WHERE id = :id")
    suspend fun deleteById(id: Long): Int


    /** CAS 幂等屏障：返回受影响行数（1 = 获得执行权；0 = 状态已变迁）。 */
    @Query("UPDATE artifact SET state = :state, updated_at = :now WHERE id = :artifactId AND state = :expectedState")
    suspend fun compareAndSetState(artifactId: Long, expectedState: String, state: String, now: Long): Int

    @Query(
        "UPDATE artifact SET state = 'ACTIVE', payload_token = NULL, updated_at = :now " +
            "WHERE id = :artifactId AND state = 'CREATING' AND payload_token = :payloadToken"
    )
    suspend fun activateCreated(artifactId: Long, payloadToken: String, now: Long): Int

    /** reconcileStartup 专用（冷启动一次）。 */
    @Query("SELECT * FROM artifact WHERE state = :state")
    suspend fun listByState(state: String): List<ArtifactEntity>

    @Query("SELECT * FROM artifact WHERE state = :state AND created_at <= :createdBefore")
    suspend fun listByStateCreatedBefore(state: String, createdBefore: Long): List<ArtifactEntity>
}
