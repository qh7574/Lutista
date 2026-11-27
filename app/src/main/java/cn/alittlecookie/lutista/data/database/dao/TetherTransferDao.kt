package cn.alittlecookie.lutista.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import cn.alittlecookie.lutista.data.database.entity.TetherTransferEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TetherTransferDao {
    @Query("SELECT * FROM tether_transfers WHERE dedupeKey=:dedupeKey AND kind=:kind LIMIT 1")
    suspend fun byKey(dedupeKey: String, kind: String): TetherTransferEntity?

    /** -1 means the persistent dedupe key was already present; do not overwrite its history. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: TetherTransferEntity): Long

    @Update
    suspend fun update(entity: TetherTransferEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TetherTransferEntity): Long

    @Query("UPDATE tether_transfers SET state=:state, bytesDone=:bytesDone, error=:error, updatedAt=:updatedAt WHERE id=:id")
    suspend fun updateState(id: Long, state: String, bytesDone: Long, error: String?, updatedAt: Long)

    @Query("UPDATE tether_transfers SET bytesDone=:bytesDone, updatedAt=:updatedAt WHERE id=:id")
    suspend fun updateProgress(id: Long, bytesDone: Long, updatedAt: Long)

    @Query("UPDATE tether_transfers SET targetPath=:targetPath, updatedAt=:updatedAt WHERE id=:id")
    suspend fun setTarget(id: Long, targetPath: String, updatedAt: Long)

    @Query("UPDATE tether_transfers SET taskId=:taskId, updatedAt=:updatedAt WHERE id=:id")
    suspend fun setTaskId(id: Long, taskId: Long?, updatedAt: Long)

    @Query("SELECT * FROM tether_transfers ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<TetherTransferEntity>>

    @Query("SELECT * FROM tether_transfers WHERE state IN ('PENDING','RUNNING','DOWNLOADED','SUBMITTING','SUBMISSION_FAILED') ORDER BY createdAt ASC, id ASC")
    suspend fun pending(): List<TetherTransferEntity>

    @Query("UPDATE tether_transfers SET state='PENDING', bytesDone=0, error=NULL, updatedAt=:updatedAt WHERE state='RUNNING'")
    suspend fun resetRunning(updatedAt: Long)
}
