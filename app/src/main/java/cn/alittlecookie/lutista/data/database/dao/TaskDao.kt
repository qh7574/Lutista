package cn.alittlecookie.lutista.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cn.alittlecookie.lutista.data.database.entity.TaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {

    @Query("SELECT * FROM processing_tasks ORDER BY priority ASC, createdAt ASC")
    fun getAllTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM processing_tasks WHERE id = :id")
    suspend fun getTaskById(id: Long): TaskEntity?

    @Query("SELECT * FROM processing_tasks WHERE status = :status ORDER BY priority ASC, createdAt ASC")
    fun getTasksByStatus(status: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM processing_tasks WHERE status = 'PENDING' ORDER BY priority ASC, createdAt ASC LIMIT 1")
    suspend fun getNextPendingTask(): TaskEntity?

    @Query("SELECT * FROM processing_tasks WHERE status = 'PROCESSING' LIMIT 1")
    suspend fun getProcessingTask(): TaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: TaskEntity): Long

    @Query("UPDATE processing_tasks SET outputPath = :outputPath WHERE id = :id")
    suspend fun updateOutputPath(id: Long, outputPath: String)

    /** TETHER 重下后暂存原件路径会变（新的一次传输），复用旧任务时必须刷新，否则处理的是已删除文件。 */
    @Query("UPDATE processing_tasks SET sourcePath = :sourcePath, originalSourcePath = :originalSourcePath, filterId = :filterId, watermarkId = :watermarkId, outputPath = '' WHERE id = :id")
    suspend fun updateTetherSource(
        id: Long,
        sourcePath: String,
        originalSourcePath: String,
        filterId: Long,
        watermarkId: Long,
    )

    @Query("UPDATE processing_tasks SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    @Query("UPDATE processing_tasks SET progress = :progress WHERE id = :id")
    suspend fun updateProgress(id: Long, progress: Float)

    @Query("UPDATE processing_tasks SET retryCount = :count WHERE id = :id")
    suspend fun updateRetryCount(id: Long, count: Int)

    @Query("UPDATE processing_tasks SET errorMessage = :message WHERE id = :id")
    suspend fun updateError(id: Long, message: String?)

    @Query("UPDATE processing_tasks SET status = :status, completedAt = :completedAt WHERE id = :id")
    suspend fun completeTask(id: Long, status: String, completedAt: Long = System.currentTimeMillis())

    @Delete
    suspend fun delete(task: TaskEntity)

    @Query("DELETE FROM processing_tasks WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM processing_tasks WHERE status IN ('COMPLETED', 'FAILED', 'CANCELLED')")
    suspend fun cleanCompletedTasks()
}
