package cn.alittlecookie.lutista.service

import cn.alittlecookie.lutista.data.database.entity.TaskEntity
import cn.alittlecookie.lutista.data.database.entity.TaskSource
import cn.alittlecookie.lutista.data.database.entity.TaskStatus
import cn.alittlecookie.lutista.data.repository.TaskRepository
import cn.alittlecookie.lutista.manager.LogManager
import cn.alittlecookie.lutista.manager.SettingsManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class TaskSubmissionManager(
    private val taskRepository: TaskRepository,
    private val imageProcessingService: ImageProcessingService,
    private val logger: LogManager,
    private val settingsManager: SettingsManager,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {
    companion object {
        private const val TAG = "TaskSubmissionManager"
        private const val MAX_RETRY_COUNT = 2
        private val RECOVERABLE_STATUSES = setOf(
            TaskStatus.PENDING.name, TaskStatus.RETRYING.name, TaskStatus.PROCESSING.name,
        )
    }

    private val taskQueue = java.util.PriorityQueue<TaskEntity>(
        compareBy<TaskEntity> { it.priority }.thenBy { it.createdAt }.thenBy { it.id }
    )
    private val mutex = Mutex()
    private val tetherSubmissionMutex = Mutex()

    val cancelFlagMap = ConcurrentHashMap<Long, AtomicBoolean>()
    private val pauseFlag = AtomicBoolean(false)
    private val isProcessing = AtomicBoolean(false)

    init {
        scope.launch {
            try {
                // A transfer may already be DONE when Android kills its processing worker.
                // Recover from the task journal independently of camera/transfer reconnection.
                tetherSubmissionMutex.withLock {
                    val interrupted = taskRepository.getAllTasks().first().filter {
                        it.source == TaskSource.TETHER.name && it.status in RECOVERABLE_STATUSES
                    }
                    for (task in interrupted) {
                        mutex.withLock {
                            if (cancelFlagMap.putIfAbsent(task.id, AtomicBoolean(false)) == null) {
                                taskQueue.add(task.copy(status = TaskStatus.PENDING.name))
                            }
                        }
                    }
                    if (interrupted.isNotEmpty()) startProcessing()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logger.e(TAG, "联机处理任务恢复失败，保留数据库记录", failure)
            }
        }
    }

    suspend fun submitTask(
        sourcePath: String,
        source: TaskSource = TaskSource.MANUAL,
        filterId: Long? = null,
        watermarkId: Long? = null,
        outputPath: String = ""
    ): Long {
        val task = TaskEntity(
            sourcePath = sourcePath,
            outputPath = outputPath,
            filterId = filterId,
            watermarkId = watermarkId,
            status = TaskStatus.PENDING.name,
            priority = when (source) {
                TaskSource.MANUAL -> 0
                TaskSource.TETHER -> 1
                TaskSource.MONITOR -> 2
            },
            source = source.name
        )

        val taskId = taskRepository.insert(task)
        val taskWithId = task.copy(id = taskId)

        mutex.withLock {
            taskQueue.add(taskWithId)
        }

        cancelFlagMap[taskId] = AtomicBoolean(false)

        logger.i(TAG, "任务已提交: $taskId, 来源: $source, 路径: $sourcePath")

        if (!isProcessing.get()) {
            startProcessing()
        }

        return taskId
    }

    /** Only decoded JPEG originals enter this bridge; RAW/MOV are download-only.
     * Both effects are explicit (0 disables fallback to a changing global selection).
     * The persisted key closes the insert-task / set-transfer-taskId crash window.
     */
    suspend fun submitTether(
        sourcePath: String,
        originalSourcePath: String,
        submissionKey: String,
        filterId: Long,
        watermarkId: Long,
        outputPath: String = ""
    ): Long = tetherSubmissionMutex.withLock {
        require(submissionKey.isNotBlank())
        require(sourcePath.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg")) {
            "TETHER processing accepts JPEG only"
        }
        require(filterId > 0 || watermarkId > 0) { "TETHER processing requires a valid effect" }
        val existing = taskRepository.getAllTasks().first().firstOrNull {
            it.source == TaskSource.TETHER.name && it.submissionKey == submissionKey
        }
        if (existing != null) {
            // A process may die after the insert but before the in-memory enqueue.
            if (existing.status in RECOVERABLE_STATUSES) {
                mutex.withLock {
                    if (!cancelFlagMap.containsKey(existing.id)) {
                        cancelFlagMap[existing.id] = AtomicBoolean(false)
                        taskQueue.add(existing.copy(status = TaskStatus.PENDING.name))
                    }
                }
                startProcessing()
            }
            return@withLock existing.id
        }
        val task = TaskEntity(
            sourcePath = sourcePath, originalSourcePath = originalSourcePath, submissionKey = submissionKey,
            outputPath = outputPath, filterId = filterId, watermarkId = watermarkId,
            priority = 1, source = TaskSource.TETHER.name
        )
        val id = taskRepository.insert(task)
        mutex.withLock {
            cancelFlagMap[id] = AtomicBoolean(false)
            taskQueue.add(task.copy(id = id))
        }
        logger.i(TAG, "任务已提交: $id, 来源: TETHER, 原始路径: $originalSourcePath")
        startProcessing()
        id
    }

    private fun startProcessing() {
        if (!isProcessing.compareAndSet(false, true)) return

        scope.launch {
            while (isProcessing.get()) {
                while (pauseFlag.get()) {
                    delay(100)
                }

                val task = mutex.withLock {
                    taskQueue.poll().also { if (it == null) isProcessing.set(false) }
                } ?: return@launch

                if (isTaskCancelled(task.id)) {
                    taskRepository.updateStatus(task.id, TaskStatus.CANCELLED.name)
                    cancelFlagMap.remove(task.id)
                    continue
                }

                var retryCount = 0
                var success = false

                while (retryCount <= MAX_RETRY_COUNT && !success) {
                    try {
                        taskRepository.updateStatus(task.id, TaskStatus.PROCESSING.name)

                        imageProcessingService.processImage(task) { progress ->
                            if (isTaskCancelled(task.id)) {
                                throw CancellationException("任务被取消")
                            }
                            scope.launch {
                                taskRepository.updateProgress(task.id, progress)
                            }
                        }

                        success = true
                        taskRepository.completeTask(task.id, TaskStatus.COMPLETED.name)
                        settingsManager.incrementProcessedCount()
                        logger.i(TAG, "任务完成: ${task.id}")
                    } catch (e: CancellationException) {
                        taskRepository.updateStatus(task.id, TaskStatus.CANCELLED.name)
                        logger.i(TAG, "任务被取消: ${task.id}")
                        break
                    } catch (e: Exception) {
                        retryCount++
                        logger.w(TAG, "任务失败 (重试 $retryCount/$MAX_RETRY_COUNT): ${task.id}", e)

                        if (retryCount <= MAX_RETRY_COUNT) {
                            taskRepository.updateStatus(task.id, TaskStatus.RETRYING.name)
                            taskRepository.updateRetryCount(task.id, retryCount)
                            delay(1000L * retryCount)
                        } else {
                            taskRepository.updateStatus(task.id, TaskStatus.FAILED.name)
                            taskRepository.updateError(task.id, e.message ?: "未知错误")
                            logger.e(TAG, "任务最终失败，已跳过: ${task.id}", e)
                        }
                    }
                }

                cancelFlagMap.remove(task.id)
            }
        }
    }

    fun cancelTask(taskId: Long) {
        cancelFlagMap[taskId]?.set(true)
        logger.i(TAG, "取消任务: $taskId")
    }

    fun pauseQueue() {
        pauseFlag.set(true)
        logger.i(TAG, "队列已暂停")
    }

    fun resumeQueue() {
        pauseFlag.set(false)
        logger.i(TAG, "队列已恢复")
    }

    private fun isTaskCancelled(taskId: Long): Boolean {
        return cancelFlagMap[taskId]?.get() ?: false
    }

    suspend fun retryTask(taskId: Long) {
        val task = taskRepository.getTaskById(taskId) ?: return

        if (task.status == TaskStatus.FAILED.name) {
            taskRepository.updateStatus(taskId, TaskStatus.PENDING.name)
            cancelFlagMap[taskId] = AtomicBoolean(false)

            mutex.withLock {
                taskQueue.add(task.copy(status = TaskStatus.PENDING.name))
            }

            if (!isProcessing.get()) {
                startProcessing()
            }
        }
    }
}
