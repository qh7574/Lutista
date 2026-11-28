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
    /**
     * 判断成品是否真的还在。数据库里 COMPLETED + outputPath 只代表「当时写成功过」，
     * 用户删文件、换输出文件夹之后这条记录会撒谎（真机 logs_1795851337338：P1028082 明明
     * 没有成品，任务却是 COMPLETED，重新下载时被直接跳过）。
     */
    private val productProbe: (String) -> Boolean = { path -> path.isNotBlank() && java.io.File(path).isFile },
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
    /** 已进入 taskQueue、尚未被 worker 取走的任务 id（防止重复排队生成两份成品）。 */
    private val queuedTaskIds = mutableSetOf<Long>()
    private val isProcessing = AtomicBoolean(false)

    /**
     * 同一任务只允许在队列里存在一份（否则会生成两份成品）。调用方必须持有 [mutex]。
     * 取消标记为 true 表示上一轮已被取消，可以重新排队。
     */
    private fun enqueueLocked(task: TaskEntity): Boolean {
        if (queuedTaskIds.contains(task.id)) return false
        val existingFlag = cancelFlagMap[task.id]
        if (existingFlag != null && !existingFlag.get()) return false
        cancelFlagMap[task.id] = AtomicBoolean(false)
        queuedTaskIds += task.id
        taskQueue.add(task.copy(status = TaskStatus.PENDING.name))
        return true
    }

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
                        mutex.withLock { enqueueLocked(task) }
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
            enqueueLocked(taskWithId)
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
            // 真机 logs_1795847176992：历史任务（早于成品落点持久化）状态是 COMPLETED 但
            // outputPath 为空，滤镜直出打开后重新下载同一张图会命中这里直接返回旧 id，
            // 既不处理也不校验成品 —— 用户看到的就是「开了滤镜直出却没有成品」。
            // 因此：可恢复状态照旧重新排队；COMPLETED 但没有成品落点记录的旧任务也重跑一次，
            // 并在重跑时刷新暂存原件路径（上一次传输的 staging 可能已被清理）。
            val productMissing = productAbsent(existing)
            if (existing.status in RECOVERABLE_STATUSES || productMissing) {
                if (existing.sourcePath != sourcePath || existing.originalSourcePath != originalSourcePath ||
                    existing.filterId != filterId || existing.watermarkId != watermarkId
                ) {
                    taskRepository.updateTetherSource(
                        existing.id, sourcePath, originalSourcePath, filterId, watermarkId,
                    )
                }
                mutex.withLock {
                    // 同一个任务既不能重复排队（会生成两份成品），也不能因为「上一轮的取消标记
                    // 还没清掉」而永远不再重排（真机曾出现只提交不处理）。
                    enqueueLocked(
                        existing.copy(
                            sourcePath = sourcePath,
                            originalSourcePath = originalSourcePath,
                            filterId = filterId,
                            watermarkId = watermarkId,
                            outputPath = "",
                        )
                    )
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
        mutex.withLock { enqueueLocked(task.copy(id = id)) }
        logger.i(TAG, "任务已提交: $id, 来源: TETHER, 原始路径: $originalSourcePath")
        startProcessing()
        id
    }

    /**
     * 「这张原片已经有成品了吗」：滤镜直出打开时，若原片已在本地，引擎会跳过 USB 传输，
     * 但仍要为它补做一次处理（用户要求）。没有任务记录、任务未完成、或没有成品落点
     * （早于落点持久化的历史任务）都算「需要处理」。
     */
    suspend fun tetherProductMissing(submissionKey: String): Boolean {
        val existing = taskRepository.getAllTasks().first().firstOrNull {
            it.source == TaskSource.TETHER.name && it.submissionKey == submissionKey
        } ?: return true
        return existing.status != TaskStatus.COMPLETED.name || productAbsent(existing)
    }

    /** 成品缺失：没有落点记录，或者记录的文件已经不在了。 */
    private fun productAbsent(task: TaskEntity): Boolean {
        if (task.status != TaskStatus.COMPLETED.name) return true
        val path = task.outputPath
        if (path.isBlank()) return true
        return !runCatching { productProbe(path) }.getOrDefault(false)
    }

    private fun startProcessing() {
        if (!isProcessing.compareAndSet(false, true)) return

        scope.launch {
            while (isProcessing.get()) {
                while (pauseFlag.get()) {
                    delay(100)
                }

                val task = mutex.withLock {
                    taskQueue.poll().also { polled ->
                        if (polled != null) queuedTaskIds -= polled.id
                        if (polled == null) isProcessing.set(false)
                    }
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

                        val outputPath = imageProcessingService.processImage(task) { progress ->
                            if (isTaskCancelled(task.id)) {
                                throw CancellationException("任务被取消")
                            }
                            scope.launch {
                                taskRepository.updateProgress(task.id, progress)
                            }
                        }

                        success = true
                        if (outputPath.isNotBlank()) taskRepository.updateOutputPath(task.id, outputPath)
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
