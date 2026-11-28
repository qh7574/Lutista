package cn.alittlecookie.lutista.data.repository

import cn.alittlecookie.lutista.data.database.dao.TaskDao
import cn.alittlecookie.lutista.data.database.entity.TaskEntity
import kotlinx.coroutines.flow.Flow

class TaskRepository(
    private val taskDao: TaskDao
) {
    fun getAllTasks(): Flow<List<TaskEntity>> = taskDao.getAllTasks()

    suspend fun getTaskById(id: Long): TaskEntity? = taskDao.getTaskById(id)

    fun getTasksByStatus(status: String): Flow<List<TaskEntity>> = taskDao.getTasksByStatus(status)

    suspend fun getNextPendingTask(): TaskEntity? = taskDao.getNextPendingTask()

    suspend fun getProcessingTask(): TaskEntity? = taskDao.getProcessingTask()

    suspend fun insert(task: TaskEntity): Long = taskDao.insert(task)

    suspend fun updateStatus(id: Long, status: String) = taskDao.updateStatus(id, status)

    suspend fun updateOutputPath(id: Long, outputPath: String) = taskDao.updateOutputPath(id, outputPath)

    suspend fun updateTetherSource(
        id: Long,
        sourcePath: String,
        originalSourcePath: String,
        filterId: Long,
        watermarkId: Long,
    ) = taskDao.updateTetherSource(id, sourcePath, originalSourcePath, filterId, watermarkId)

    suspend fun updateProgress(id: Long, progress: Float) = taskDao.updateProgress(id, progress)

    suspend fun updateRetryCount(id: Long, count: Int) = taskDao.updateRetryCount(id, count)

    suspend fun updateError(id: Long, message: String?) = taskDao.updateError(id, message)

    suspend fun completeTask(id: Long, status: String) = taskDao.completeTask(id, status)

    suspend fun delete(task: TaskEntity) = taskDao.delete(task)

    suspend fun deleteById(id: Long) = taskDao.deleteById(id)

    suspend fun cleanCompletedTasks() = taskDao.cleanCompletedTasks()
}
