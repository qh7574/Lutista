package cn.alittlecookie.lutista.tether.transfer

import cn.alittlecookie.lutista.tether.objects.CameraObject
import cn.alittlecookie.lutista.tether.objects.CameraObjectSource
import cn.alittlecookie.lutista.tether.objects.CameraSessionKey
import cn.alittlecookie.lutista.tether.objects.ObjectPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

/** The queue is deliberately blocked at observable camera/commit boundaries, without timing sleeps. */
class TransferEngineProgressTest {
    private class Store : TransferStore {
        private val rows = linkedMapOf<Pair<String, TransferKind>, TransferRecord>()
        override suspend fun find(key: String, kind: TransferKind) = rows[key to kind]
        override suspend fun insertIfAbsent(record: TransferRecord) =
            rows.getOrPut(record.key to record.kind) { record }
        override suspend fun save(record: TransferRecord) { rows[record.key to record.kind] = record }
        override suspend fun recoverable() = rows.values.filter {
            it.state in setOf(TransferState.PENDING, TransferState.RUNNING, TransferState.DOWNLOADED,
                TransferState.SUBMITTING, TransferState.SUBMISSION_FAILED)
        }
    }

    private class Source(objects: List<CameraObject>, generation: Long = 1) : CameraObjectSource {
        override val sessionKey = CameraSessionKey("batch-camera", generation)
        val infos = objects.associateByTo(linkedMapOf()) { it.handle }
        val entered = Channel<Long>(Channel.UNLIMITED)
        val blocked = mutableMapOf<Long, CompletableDeferred<Unit>>()
        val failures = mutableSetOf<Long>()
        val calls = mutableListOf<Long>()
        var onStream: (Long) -> Unit = {}
        override fun listTree(): Flow<ObjectPage> = flowOf(ObjectPage.Batch(infos.values.toList()),
            ObjectPage.Complete(infos.size, mapOf(1L to infos.size)))
        override suspend fun listChildren(parent: Long, storageId: Long?) = infos.values.toList()
        override suspend fun getObjectInfo(handle: Long) = infos.getValue(handle)
        override suspend fun fetchThumb(handle: Long) = byteArrayOf(1, 2, 3, 4)
        override suspend fun fetchRawPreview(handle: Long): ByteArray? = null
        override suspend fun fetchObject(handle: Long) = byteArrayOf(1, 2, 3, 4)
        override suspend fun streamObject(handle: Long, sink: (ByteArray, Int) -> Unit,
            onProgress: (Long) -> Unit): Long {
            calls += handle
            onStream(handle)
            entered.send(handle)
            blocked[handle]?.await()
            if (handle in failures) throw IOException("camera read rejected $handle")
            sink(byteArrayOf(1, 2, 3, 4), 4)
            onProgress(4)
            return 4
        }
    }

    private class Targets : TransferTargetStorage {
        val committed = linkedMapOf<Pair<String, TransferKind>, DownloadedTransfer>()
        var prepares = 0
        var beforeCommit: suspend (CameraObject) -> Unit = {}
        override suspend fun prepare(key: String, info: CameraObject, kind: TransferKind): TransferTarget {
            prepares++
            return object : TransferTarget {
                // A running record intentionally contains a private hint, never a visible target URI.
                override val destinationHint = "/private/staging/$key.part"
                private val bytes = ByteArrayOutputStream()
                override fun write(bytes: ByteArray, length: Int) { this.bytes.write(bytes, 0, length) }
                override fun finish() { check(bytes.size().toLong() == info.size) }
                override suspend fun commit(): DownloadedTransfer {
                    beforeCommit(info)
                    return DownloadedTransfer("content://camera-originals/${kind.name}/${info.handle}",
                        "/private/originals/${kind.name}/${info.handle}.jpg", bytes.size().toLong()).also {
                        committed[key to kind] = it
                    }
                }
                override suspend fun abort() = Unit // Committed originals survive cleanup.
                override fun close() = Unit
            }
        }
        override suspend fun recover(record: TransferRecord) = committed[record.key to record.kind]
    }

    private class Submitter : TetherTaskSubmitter {
        var effects: TetherEffects? = null
        var submitAction: suspend () -> Unit = {}
        var submits = 0
        override suspend fun automaticEnabled() = true
        override suspend fun resolveEffects() = effects
        override suspend fun submit(key: String, downloaded: DownloadedTransfer, effects: TetherEffects): Long {
            submits++
            submitAction()
            return submits.toLong()
        }
    }

    private fun jpeg(handle: Long) = CameraObject(handle, 1, 0x3801, 0, 4,
        "IMG_$handle.JPG", "capture-$handle", false)
    private suspend fun idle(engine: TransferEngine) = withTimeout(5_000) { engine.awaitIdle() }
    private suspend fun entered(source: Source) = withTimeout(5_000) { source.entered.receive() }

    @Test fun seventySelectionsArePublishedBeforeBoundedQueueAndKeepTheirOrdinal() = runBlocking {
        val objects = (1L..70L).map(::jpeg)
        val source = Source(objects)
        val release = CompletableDeferred<Unit>()
        source.blocked[1] = release
        val targets = Targets()
        val engine = TransferEngine(this, Store(), targets, Submitter(), queueCapacity = 64)
        engine.startSession(source)
        val ordinals = mutableListOf<Int>()
        source.onStream = { handle ->
            val current = engine.progress.value
            val batch = engine.batches.value.getValue(requireNotNull(current.batchId))
            assertEquals(70, current.batchTotal)
            assertEquals(handle.toInt(), current.batchIndex)
            assertEquals(handle.toInt(), batch.currentIndex)
            assertEquals(70, batch.totalCount)
            assertEquals(handle.toInt() - 1, batch.completedCount)
            ordinals += batch.currentIndex
        }
        val producer = launch(start = CoroutineStart.UNDISPATCHED) {
            engine.enqueueManual(source.sessionKey, objects)
        }
        try {
            assertEquals(1L, entered(source))
            assertFalse("Selection exceeds consumer plus channel capacity", producer.isCompleted)
            val registered = engine.transfers.value.values.toList()
            assertEquals(70, registered.size)
            assertEquals(69, registered.count { it.state == TransferState.PENDING })
            assertTrue(registered.all { it.targetPath == null && it.localPath == null })
            val batch = engine.batches.value.values.single()
            assertEquals(70, batch.totalCount)
            assertEquals(1, batch.currentIndex)
            assertEquals(0, batch.completedCount)
            release.complete(Unit)
            withTimeout(5_000) { producer.join() }
            idle(engine)
            val done = engine.batches.value.getValue(batch.id)
            assertEquals((1..70).toList(), ordinals)
            assertEquals(70, done.currentIndex)
            assertEquals(70, done.completedCount)
            assertEquals(0, done.failedCount)
            assertFalse(done.active)
            assertEquals(70, targets.committed.size)
        } finally { release.complete(Unit); producer.cancel(); engine.close() }
    }

    @Test fun failureRetainsItsSlotAndRetryCreatesAnIndependentBatchWithoutHistory() = runBlocking {
        val objects = (1L..3L).map(::jpeg)
        val source = Source(objects)
        source.failures += 1
        val targets = Targets()
        val engine = TransferEngine(this, Store(), targets, Submitter())
        engine.startSession(source)
        try {
            engine.enqueueManual(source.sessionKey, objects)
            idle(engine)
            val first = engine.batches.value.values.single()
            assertEquals(3, first.totalCount)
            assertEquals(3, first.currentIndex)
            assertEquals(2, first.completedCount)
            assertEquals(1, first.failedCount)
            assertFalse(first.active)
            source.failures.clear()
            engine.retry(source.sessionKey, jpeg(1))
            idle(engine)
            val retry = engine.batches.value.values.last()
            assertNotEquals(first.id, retry.id)
            assertEquals(1, retry.totalCount)
            assertEquals(1, retry.currentIndex)
            assertEquals(1, retry.completedCount)
            assertEquals(0, retry.failedCount)
            assertEquals(first, engine.batches.value.getValue(first.id))
            assertEquals(listOf(1L, 2L, 3L, 1L), source.calls)

            // A later selection is a new batch, including precommitted members, never old history.
            source.infos[4] = jpeg(4)
            val release = CompletableDeferred<Unit>()
            source.blocked[4] = release
            engine.enqueueManual(source.sessionKey, listOf(jpeg(2), jpeg(4), jpeg(4)))
            assertEquals(4L, enteredAfter(source, 4))
            val latest = engine.batches.value.values.last()
            assertEquals(2, latest.totalCount)
            assertEquals(2, latest.currentIndex)
            assertEquals(1, latest.completedCount)
            assertEquals(0, latest.failedCount)
            release.complete(Unit)
            idle(engine)
            assertEquals(2, engine.batches.value.getValue(latest.id).completedCount)
            assertEquals(1, source.calls.count { it == 2L })
        } finally { engine.close() }
    }

    @Test fun userCancellationDoesNotShrinkTheBatchOrCancelTheFollowingItems() = runBlocking {
        val source = Source((1L..3L).map(::jpeg))
        source.blocked[1] = CompletableDeferred()
        val engine = TransferEngine(this, Store(), Targets(), Submitter())
        engine.startSession(source)
        try {
            engine.enqueueManual(source.sessionKey, source.infos.values.toList())
            assertEquals(1L, entered(source))
            engine.cancelForeground()
            idle(engine)
            val batch = engine.batches.value.values.single()
            assertEquals(3, batch.totalCount)
            assertEquals(3, batch.currentIndex)
            assertEquals(2, batch.completedCount)
            assertEquals(1, batch.cancelledCount)
            assertEquals(0, batch.failedCount)
            assertFalse(batch.active)
            assertNull(engine.downloadedFor(source.sessionKey, jpeg(1), TransferKind.MANUAL))
            source.blocked.remove(1)
            engine.retry(source.sessionKey, jpeg(1))
            idle(engine)
            val retry = engine.batches.value.values.last()
            assertEquals(1, retry.totalCount)
            assertEquals(1, retry.completedCount)
            assertEquals(0, retry.cancelledCount)
            assertEquals(batch, engine.batches.value.getValue(batch.id))
        } finally { engine.close() }
    }

    @Test fun committedSafUriCountsEvenWhenEffectsFailButPrivateStagingNeverDoes() = runBlocking {
        val source = Source(listOf(jpeg(1)))
        val targets = Targets()
        val commitEntered = CompletableDeferred<Unit>()
        val commitRelease = CompletableDeferred<Unit>()
        targets.beforeCommit = { commitEntered.complete(Unit); commitRelease.await() }
        val submitter = Submitter().apply {
            effects = TetherEffects(1, 0)
            submitAction = { throw IOException("effects submission failed") }
        }
        val engine = TransferEngine(this, Store(), targets, submitter)
        engine.startSession(source)
        try {
            engine.enqueueManual(source.sessionKey, listOf(jpeg(1)))
            withTimeout(5_000) { commitEntered.await() }
            assertEquals(4L, engine.progress.value.bytesDone)
            assertNull(engine.progress.value.targetPath)
            assertNull(engine.progress.value.localPath)
            assertEquals(0, engine.batches.value.values.single().completedCount)
            commitRelease.complete(Unit)
            idle(engine)
            val originalBatch = engine.batches.value.values.single()
            val progress = engine.progress.value
            assertEquals(TransferState.SUBMISSION_FAILED, progress.state)
            assertEquals("content://camera-originals/MANUAL/1", progress.targetPath)
            assertEquals("/private/originals/MANUAL/1.jpg", progress.localPath)
            assertNotEquals(progress.targetPath, progress.localPath)
            assertEquals(1, originalBatch.completedCount)
            assertEquals(1, originalBatch.failedCount)
            assertEquals(progress.targetPath,
                engine.downloadedFor(source.sessionKey, jpeg(1), TransferKind.MANUAL)?.targetPath)
            submitter.submitAction = {}
            engine.retry(source.sessionKey, jpeg(1))
            idle(engine)
            assertEquals(TransferState.DONE, engine.progress.value.state)
            assertEquals(1, engine.batches.value.values.last().completedCount)
            assertEquals(originalBatch, engine.batches.value.getValue(originalBatch.id))
            assertEquals(1, source.calls.size) // Recovery reuses the committed original.
            assertEquals(1, targets.prepares)
        } finally { commitRelease.complete(Unit); engine.close() }
    }

    @Test fun cancellationAfterCommitRetainsTargetAndCountsOriginalSeparatelyFromProcessing() = runBlocking {
        val source = Source(listOf(jpeg(1)))
        val submitting = CompletableDeferred<Unit>()
        val submitter = Submitter().apply {
            effects = TetherEffects(1, 0)
            submitAction = { submitting.complete(Unit); CompletableDeferred<Unit>().await() }
        }
        val engine = TransferEngine(this, Store(), Targets(), submitter)
        engine.startSession(source)
        try {
            engine.enqueueManual(source.sessionKey, listOf(jpeg(1)))
            withTimeout(5_000) { submitting.await() }
            assertEquals(1, engine.batches.value.values.single().completedCount)
            assertTrue(engine.batches.value.values.single().active)
            assertEquals("content://camera-originals/MANUAL/1", engine.progress.value.targetPath)
            engine.cancelForeground()
            idle(engine)
            val batch = engine.batches.value.values.single()
            assertEquals(1, batch.completedCount)
            assertEquals(1, batch.cancelledCount)
            assertFalse(batch.active)
            assertEquals(TransferState.DOWNLOADED, engine.progress.value.state)
            assertEquals("content://camera-originals/MANUAL/1", engine.progress.value.targetPath)
            assertEquals("/private/originals/MANUAL/1.jpg", engine.progress.value.localPath)
        } finally { engine.close() }
    }

    @Test fun automaticEventsHaveTheirOwnBatchAndCannotChangeAManualSelectionTotal() = runBlocking {
        val source = Source((1L..3L).map(::jpeg))
        val release = CompletableDeferred<Unit>()
        source.blocked[1] = release
        val engine = TransferEngine(this, Store(), Targets(), Submitter())
        engine.startSession(source)
        engine.completeBaseline(source.sessionKey, listOf(jpeg(1), jpeg(2)))
        try {
            engine.enqueueManual(source.sessionKey, listOf(jpeg(1), jpeg(2)))
            assertEquals(1L, entered(source))
            val manual = engine.batches.value.values.single()
            engine.onObjectAdded(source.sessionKey, 3)
            val auto = engine.batches.value.values.last()
            assertEquals(TransferKind.AUTO, auto.kind)
            assertEquals(1, auto.totalCount)
            assertEquals(manual, engine.batches.value.getValue(manual.id))
            engine.onObjectAdded(source.sessionKey, 3)
            assertEquals(2, engine.batches.value.size) // Duplicate events do not add batches/work.
            engine.enqueueManual(source.sessionKey, listOf(jpeg(3)))
            assertEquals(3, engine.batches.value.size)
            assertEquals(2, engine.batches.value.getValue(manual.id).totalCount)
            release.complete(Unit)
            idle(engine)
            assertEquals(2, engine.batches.value.getValue(manual.id).completedCount)
            assertEquals(1, engine.batches.value.getValue(auto.id).completedCount)
            assertEquals(2, source.calls.count { it == 3L }) // AUTO and explicit MANUAL stay distinct.
        } finally { release.complete(Unit); engine.close() }
    }

    @Test fun repeatedSelectionObservesScheduledWorkWithoutRedownloadingIt() = runBlocking {
        val source = Source(listOf(jpeg(1), jpeg(2)))
        val release = CompletableDeferred<Unit>()
        source.blocked[1] = release
        val engine = TransferEngine(this, Store(), Targets(), Submitter())
        engine.startSession(source)
        try {
            engine.enqueueManual(source.sessionKey, source.infos.values.toList())
            assertEquals(1L, entered(source))
            val firstId = engine.batches.value.keys.single()
            engine.enqueueManual(source.sessionKey, source.infos.values.toList())
            val secondId = engine.batches.value.keys.last()
            assertNotEquals(firstId, secondId)
            assertEquals(2, engine.transfers.value.size)
            assertEquals(2, engine.batches.value.getValue(secondId).totalCount)
            release.complete(Unit)
            idle(engine)
            assertEquals(listOf(1L, 2L), source.calls)
            for (id in listOf(firstId, secondId)) {
                val batch = engine.batches.value.getValue(id)
                assertEquals(2, batch.totalCount)
                assertEquals(2, batch.completedCount)
                assertFalse(batch.active)
            }
            engine.enqueueManual(source.sessionKey, listOf(jpeg(2)))
            val precommitted = engine.batches.value.values.last()
            assertEquals(1, precommitted.totalCount)
            assertEquals(1, precommitted.completedCount)
            assertFalse(precommitted.active)
            assertEquals(2, source.calls.size)
        } finally { release.complete(Unit); engine.close() }
    }

    @Test fun disconnectSettlesUnsentSelectionsAndANewSessionCannotInheritTheirBatch() = runBlocking {
        val source = Source((1L..70L).map(::jpeg))
        source.blocked[1] = CompletableDeferred()
        val engine = TransferEngine(this, Store(), Targets(), Submitter(), queueCapacity = 64)
        engine.startSession(source)
        val producer = launch(start = CoroutineStart.UNDISPATCHED) {
            engine.enqueueManual(source.sessionKey, source.infos.values.toList())
        }
        try {
            assertEquals(1L, entered(source))
            engine.stopSession()
            withTimeout(5_000) { producer.join() }
            idle(engine)
            val stopped = engine.batches.value.values.single()
            assertEquals(70, stopped.totalCount)
            assertEquals(0, stopped.completedCount)
            assertEquals(70, stopped.cancelledCount)
            assertFalse(stopped.active)
            assertEquals(0, engine.progress.value.queued)
            val next = Source(listOf(jpeg(1)), generation = 2)
            engine.startSession(next)
            assertTrue(engine.batches.value.isEmpty())
            assertTrue(engine.transfers.value.isEmpty())
            engine.enqueueManual(next.sessionKey, listOf(jpeg(1)))
            idle(engine)
            val fresh = engine.batches.value.values.single()
            assertEquals(next.sessionKey, fresh.sessionKey)
            assertEquals(1, fresh.totalCount)
            assertEquals(1, fresh.completedCount)
            assertNotEquals(stopped.id, fresh.id)
        } finally { producer.cancel(); engine.close() }
    }

    private suspend fun enteredAfter(source: Source, expected: Long): Long = withTimeout(5_000) {
        var value: Long
        do { value = source.entered.receive() } while (value != expected)
        value
    }
}
