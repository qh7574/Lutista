package cn.alittlecookie.lutista.service

import cn.alittlecookie.lutista.data.database.entity.TaskEntity
import cn.alittlecookie.lutista.data.database.entity.TaskSource
import cn.alittlecookie.lutista.data.database.entity.TaskStatus
import cn.alittlecookie.lutista.data.repository.TaskRepository
import cn.alittlecookie.lutista.manager.LogManager
import cn.alittlecookie.lutista.manager.SettingsManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the real queue owner; no camera, Android context, or processing implementation is used. */
class TaskSubmissionManagerTest {
    @Test
    fun `startup processes persisted pending processing and retrying tether tasks without resubmission`() =
        withFixture(
            listOf(
                task(1, TaskStatus.PENDING),
                task(2, TaskStatus.PROCESSING),
                task(3, TaskStatus.RETRYING),
            )
        ) {
            start()
            awaitIdle()

            val recovered = List(3) { processed.receive() }
            assertEquals(initial.map { it.copy(status = TaskStatus.PENDING.name) }, recovered)
            assertTrue(processed.tryReceive().isFailure)
            assertEquals(List(3) { TaskStatus.COMPLETED.name }, journal.value.map { it.status })
            initial.forEach { persisted ->
                coVerify(exactly = 1) { processor.processImage(match { it.id == persisted.id }, any()) }
                coVerify(exactly = 1) {
                    repository.completeTask(persisted.id, TaskStatus.COMPLETED.name)
                }
            }
            coVerify(exactly = 3) { settings.incrementProcessedCount() }
            coVerify(exactly = 0) { repository.insert(any()) }
        }

    @Test
    fun `startup leaves failed cancelled and completed tether tasks untouched`() =
        withFixture(
            listOf(
                task(1, TaskStatus.FAILED),
                task(2, TaskStatus.CANCELLED),
                task(3, TaskStatus.COMPLETED),
            )
        ) {
            start()
            awaitIdle()

            assertEquals(initial, journal.value)
            assertTrue(processed.tryReceive().isFailure)
            verifyNoProcessing()
        }

    @Test
    fun `startup never recovers manual or monitor tasks regardless of status`() =
        withFixture(
            listOf(TaskSource.MANUAL, TaskSource.MONITOR).flatMapIndexed { sourceIndex, source ->
                TaskStatus.entries.mapIndexed { statusIndex, status ->
                    task((sourceIndex * TaskStatus.entries.size + statusIndex + 1).toLong(), status, source)
                }
            }
        ) {
            start()
            awaitIdle()

            assertEquals(initial, journal.value)
            assertTrue(processed.tryReceive().isFailure)
            verifyNoProcessing()
        }

    @Test
    fun `startup holding journal admission and concurrent submitTether process the existing key once`() =
        withFixture(listOf(task(7, TaskStatus.PROCESSING))) {
            journalGate = CompletableDeferred()
            processingGate = CompletableDeferred()
            val manager = start()
            withTimeout(TIMEOUT_MS) { journalReads.receive() }

            val submission = scope.async(start = CoroutineStart.UNDISPATCHED) {
                submitExisting(manager)
            }
            assertFalse(submission.isCompleted)
            journalGate!!.complete(Unit)

            assertEquals(7L, withTimeout(TIMEOUT_MS) { submission.await() })
            assertEquals(7L, withTimeout(TIMEOUT_MS) { processed.receive().id })
            processingGate!!.complete(Unit)
            awaitIdle()

            verifySingleExistingTask()
        }

    @Test
    fun `submitTether holding journal admission and concurrent startup process the existing key once`() =
        withFixture(listOf(task(7, TaskStatus.RETRYING))) {
            journalGate = CompletableDeferred()
            processingGate = CompletableDeferred()
            val manager = start()
            // The constructor's launch is queued on this runBlocking event loop; UNDISPATCHED
            // acquires submission admission before startup runs, then suspends at the journal gate.
            val submission = scope.async(start = CoroutineStart.UNDISPATCHED) {
                submitExisting(manager)
            }
            withTimeout(TIMEOUT_MS) { journalReads.receive() }
            yield() // Give startup a turn to contend for the same submission mutex.
            assertFalse(submission.isCompleted)
            journalGate!!.complete(Unit)

            assertEquals(7L, withTimeout(TIMEOUT_MS) { submission.await() })
            assertEquals(7L, withTimeout(TIMEOUT_MS) { processed.receive().id })
            processingGate!!.complete(Unit)
            awaitIdle()

            verifySingleExistingTask()
        }

    private class Fixture(val initial: List<TaskEntity>, val scope: CoroutineScope, val job: Job) {
        val repository = mockk<TaskRepository>(relaxed = true)
        val processor = mockk<ImageProcessingService>(relaxed = true)
        val settings = mockk<SettingsManager>(relaxed = true)
        private val logger = mockk<LogManager>(relaxed = true)
        val journal = MutableStateFlow(initial)
        val journalReads = Channel<Unit>(Channel.UNLIMITED)
        val processed = Channel<TaskEntity>(Channel.UNLIMITED)
        var journalGate: CompletableDeferred<Unit>? = null
        var processingGate: CompletableDeferred<Unit>? = null

        init {
            every { repository.getAllTasks() } returns flow {
                journalReads.send(Unit)
                journalGate?.await()
                emit(journal.value)
            }
            coEvery { repository.updateStatus(any(), any()) } coAnswers {
                val id = firstArg<Long>()
                val status = secondArg<String>()
                journal.value = journal.value.map { if (it.id == id) it.copy(status = status) else it }
                Unit
            }
            coEvery { repository.completeTask(any(), any()) } coAnswers {
                val id = firstArg<Long>()
                val status = secondArg<String>()
                journal.value = journal.value.map { if (it.id == id) it.copy(status = status) else it }
                Unit
            }
            coEvery { processor.processImage(any(), any()) } coAnswers {
                processed.send(firstArg<TaskEntity>())
                processingGate?.await()
                outputPath
            }
        }

        /** processImage 现在返回成品落点；测试里用一个固定值代表「确实产出了成品」。 */
        var outputPath: String = "/storage/emulated/0/Pictures/Lutista/OUT.jpg"

        fun start() = TaskSubmissionManager(repository, processor, logger, settings, scope)

        suspend fun submitExisting(manager: TaskSubmissionManager): Long {
            val task = initial.single()
            return manager.submitTether(
                sourcePath = task.sourcePath,
                originalSourcePath = requireNotNull(task.originalSourcePath),
                submissionKey = requireNotNull(task.submissionKey),
                filterId = requireNotNull(task.filterId),
                watermarkId = requireNotNull(task.watermarkId),
                outputPath = task.outputPath,
            )
        }

        suspend fun awaitIdle() = withTimeout(TIMEOUT_MS) {
            // Startup can launch a separate queue worker, so join every generation of children.
            while (true) {
                val children = job.children.toList()
                if (children.isEmpty()) break
                children.joinAll()
            }
        }

        fun verifyNoProcessing() {
            coVerify(exactly = 0) { processor.processImage(any(), any()) }
            coVerify(exactly = 0) { repository.updateStatus(any(), any()) }
            coVerify(exactly = 0) { repository.completeTask(any(), any()) }
            coVerify(exactly = 0) { settings.incrementProcessedCount() }
            coVerify(exactly = 0) { repository.insert(any()) }
        }

        fun verifySingleExistingTask() {
            assertTrue(processed.tryReceive().isFailure)
            assertEquals(listOf(7L), journal.value.map { it.id })
            assertEquals(TaskStatus.COMPLETED.name, journal.value.single().status)
            coVerify(exactly = 1) { processor.processImage(match { it.id == 7L }, any()) }
            coVerify(exactly = 1) { repository.completeTask(7L, TaskStatus.COMPLETED.name) }
            coVerify(exactly = 1) { settings.incrementProcessedCount() }
            coVerify(exactly = 0) { repository.insert(any()) }
        }
    }

    private fun withFixture(initial: List<TaskEntity>, body: suspend Fixture.() -> Unit) = runBlocking {
        val job = Job()
        val fixture = Fixture(initial, CoroutineScope(coroutineContext + job), job)
        try {
            withTimeout(TIMEOUT_MS) { fixture.body() }
        } finally {
            job.cancelAndJoin()
            fixture.journalReads.close()
            fixture.processed.close()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L

        fun task(id: Long, status: TaskStatus, source: TaskSource = TaskSource.TETHER) = TaskEntity(
            id = id,
            sourcePath = "/originals/$id.jpg",
            originalSourcePath = "content://originals/$id.jpg",
            outputPath = "content://processed/$id.jpg",
            filterId = 11L,
            watermarkId = 0L,
            status = status.name,
            source = source.name,
            createdAt = id,
            submissionKey = "camera-session/object/$id",
        )
    }
}
