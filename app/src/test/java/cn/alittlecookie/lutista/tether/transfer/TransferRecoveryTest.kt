package cn.alittlecookie.lutista.tether.transfer

import cn.alittlecookie.lutista.tether.objects.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/** Fault-injection tests for acknowledged events, process recovery and session isolation. */
class TransferRecoveryTest {
    private class Store : TransferStore {
        val rows = linkedMapOf<Pair<String, TransferKind>, TransferRecord>()
        override suspend fun find(key: String, kind: TransferKind) = rows[key to kind]
        override suspend fun insertIfAbsent(record: TransferRecord) = rows.getOrPut(record.key to record.kind) { record }
        override suspend fun save(record: TransferRecord) { rows[record.key to record.kind] = record }
        override suspend fun recoverable() = rows.values.filter { it.state in setOf(TransferState.PENDING,
            TransferState.RUNNING, TransferState.DOWNLOADED, TransferState.SUBMITTING, TransferState.SUBMISSION_FAILED) }
    }
    private class Source(override val sessionKey: CameraSessionKey, objects: List<CameraObject>) : CameraObjectSource {
        val infos = objects.associateBy { it.handle }.toMutableMap()
        val streamed = mutableListOf<Long>()
        var beforeStream: (suspend () -> Unit)? = null
        var chunkSize = 2
        override fun listTree(): Flow<ObjectPage> = flowOf(ObjectPage.Batch(infos.values.toList()), ObjectPage.Complete(infos.size, mapOf(1L to infos.size)))
        override suspend fun listChildren(parent: Long, storageId: Long?) = infos.values.toList()
        override suspend fun getObjectInfo(handle: Long) = infos[handle] ?: error("missing handle")
        override suspend fun fetchThumb(handle: Long) = byteArrayOf(1, 2, 3, 4)
        override suspend fun fetchRawPreview(handle: Long): ByteArray? = null
        override suspend fun fetchObject(handle: Long): ByteArray = error("whole-object buffering forbidden")
        override suspend fun streamObject(handle: Long, sink: (ByteArray, Int) -> Unit, onProgress: (Long) -> Unit): Long {
            beforeStream?.invoke()
            streamed += handle
            val chunk = ByteArray(chunkSize) { 1 }
            var count = 0L
            val size = infos.getValue(handle).size
            while (count < size) {
                val n = minOf(chunk.size.toLong(), size - count).toInt()
                sink(chunk, n); count += n; onProgress(count)
            }
            return count
        }
    }
    private class Targets : TransferTargetStorage {
        val prepared = mutableListOf<Long>()
        val recovered = mutableMapOf<String, DownloadedTransfer>()
        var abortFailure = false
        var closeFailure = false
        var writeFailureHandle: Long? = null
        var maxChunk = 0
        var aborted = 0
        override suspend fun recover(record: TransferRecord) = recovered[record.key]
        override suspend fun prepare(key: String, info: CameraObject, kind: TransferKind): TransferTarget {
            prepared += info.handle
            return object : TransferTarget {
                override val destinationHint = "/original/${info.name}"
                var count = 0L
                override fun write(bytes: ByteArray, length: Int) {
                    if (info.handle == writeFailureHandle) error("disk full")
                    maxChunk = maxOf(maxChunk, length); count += length
                }
                override fun finish() { check(count == info.size) }
                override suspend fun commit() = DownloadedTransfer(destinationHint, destinationHint, count).also { recovered[key] = it }
                override suspend fun abort() { aborted++; if (abortFailure) error("cannot remove partial") }
                override fun close() { if (closeFailure) error("close failure") }
            }
        }
    }
    private class Submit : TetherTaskSubmitter {
        var enabled = true
        var effects: TetherEffects? = TetherEffects(1, 0)
        val calls = mutableListOf<String>()
        override suspend fun automaticEnabled() = enabled
        override suspend fun resolveEffects() = effects
        override suspend fun submit(key: String, downloaded: DownloadedTransfer, effects: TetherEffects): Long {
            calls += key; return 99L
        }
    }
    private fun jpg(handle: Long = 7, name: String = "IMG.JPG", format: Long = 0x3801) =
        CameraObject(handle, 1, format, 0, 4, name, "20261127T003000", false)
    private fun key(info: CameraObject, serial: String = "serial") = CameraObjectKeys.transferDedupeKey(serial, info)
    private suspend fun idle(engine: TransferEngine) = withTimeout(2_000) { engine.awaitIdle() }

    @Test fun everyRecoverableStateRebindsNewHandleAndKeepsCommittedOriginal() = runBlocking {
        for (state in listOf(TransferState.RUNNING, TransferState.DOWNLOADED, TransferState.SUBMITTING, TransferState.SUBMISSION_FAILED)) {
            val old = jpg(); val fresh = jpg(handle = 99)
            val store = Store(); val targets = Targets(); val submit = Submit()
            store.insertIfAbsent(TransferRecord(key = key(old), kind = TransferKind.AUTO, handle = old.handle,
                name = old.name, size = old.size, state = state, targetPath = "/original/IMG.JPG", createdAt = 1L))
            if (state != TransferState.RUNNING) targets.recovered[key(old)] = DownloadedTransfer("content://tree/7", "/original/IMG.JPG", 4)
            val source = Source(CameraSessionKey("serial", 20), listOf(fresh))
            val engine = TransferEngine(this, store, targets, submit)
            try {
                engine.startSession(source); engine.completeBaseline(source.sessionKey, listOf(fresh)); idle(engine)
                val row = store.find(key(old), TransferKind.AUTO)!!
                assertEquals(state.toString(), TransferState.DONE, row.state)
                assertEquals(99L, row.handle); assertEquals(99L, row.taskId)
                assertEquals(1, submit.calls.size)
                assertEquals(if (state == TransferState.RUNNING) listOf(99L) else emptyList<Long>(), source.streamed)
                assertNull(store.find(key(old), TransferKind.BASELINE))
            } finally { engine.close() }
        }
    }

    @Test fun acknowledgedEarlyEventRecoversAfterEngineRecreation() = runBlocking {
        val store = Store(); val submit = Submit(); val targets = Targets()
        val first = Source(CameraSessionKey("serial", 10), listOf(jpg()))
        val engine = TransferEngine(this, store, targets, submit)
        engine.startSession(first); engine.onObjectAdded(first.sessionKey, 7)
        assertEquals(TransferState.PENDING, store.find(key(jpg()), TransferKind.AUTO)!!.state)
        engine.close()
        val second = Source(CameraSessionKey("serial", 11), listOf(jpg(77)))
        val restored = TransferEngine(this, store, targets, submit)
        try {
            restored.startSession(second); restored.completeBaseline(second.sessionKey, listOf(jpg(77))); idle(restored)
            assertEquals(listOf(77L), second.streamed); assertEquals(1, submit.calls.size)
        } finally { restored.close() }
    }

    @Test fun callbackAfterBaselineStillOwnsLiveObservedEvent() = runBlocking {
        val store = Store(); val submit = Submit(); val source = Source(CameraSessionKey("serial", 10), listOf(jpg()))
        val engine = TransferEngine(this, store, Targets(), submit)
        try {
            engine.startSession(source)
            val observed = ConcurrentHashMap.newKeySet<Long>()
            engine.completeBaseline(source.sessionKey, listOf(jpg()), observed)
            observed += 7 // Driver observes the event while the consumer callback is delayed.
            engine.onObjectAdded(source.sessionKey, 7); idle(engine)
            assertEquals(1, submit.calls.size)
        } finally { engine.close() }
    }

    @Test fun rawMovNoEffectAndDisabledNeverCreateAutomaticRows() = runBlocking {
        for (mode in 0..3) {
            val info = when (mode) { 0 -> jpg(name = "IMG.RW2", format = 0xb103); 1 -> jpg(name = "VID.MOV", format = 0x300d); else -> jpg() }
            val store = Store(); val targets = Targets(); val submit = Submit()
            if (mode == 2) submit.effects = TetherEffects()
            if (mode == 3) submit.enabled = false
            val source = Source(CameraSessionKey("serial", 10), listOf(info)); val engine = TransferEngine(this, store, targets, submit)
            try {
                engine.startSession(source); engine.onObjectAdded(source.sessionKey, info.handle)
                engine.completeBaseline(source.sessionKey, listOf(info), setOf(info.handle)); idle(engine)
                assertNull(store.find(key(info), TransferKind.AUTO)); assertTrue(targets.prepared.isEmpty()); assertTrue(submit.calls.isEmpty())
            } finally { engine.close() }
        }
    }

    @Test fun explicitRawAndMovDownloadWithoutImageTasks() = runBlocking {
        val objects = listOf(jpg(name = "IMG.RW2", format = 0xb103), jpg(8, "VID.MOV", 0x300d))
        val source = Source(CameraSessionKey("serial", 10), objects); val targets = Targets(); val submit = Submit()
        val engine = TransferEngine(this, Store(), targets, submit)
        try {
            engine.startSession(source); engine.enqueueManual(source.sessionKey, objects); idle(engine)
            assertEquals(listOf(7L, 8L), source.streamed); assertTrue(submit.calls.isEmpty()); assertEquals(2, targets.maxChunk)
        } finally { engine.close() }
    }

    @Test fun failedAbortAndCloseCannotStopFollowingItem() = runBlocking {
        val objects = listOf(jpg(), jpg(8, "NEXT.JPG")); val store = Store(); val targets = Targets()
        targets.writeFailureHandle = 7; targets.abortFailure = true; targets.closeFailure = true
        val source = Source(CameraSessionKey("serial", 10), objects); val engine = TransferEngine(this, store, targets, Submit())
        try {
            engine.startSession(source); engine.enqueueManual(source.sessionKey, objects); idle(engine)
            assertEquals(TransferState.FAILED, store.find(key(objects[0]), TransferKind.MANUAL)!!.state)
            assertTrue(store.find(key(objects[0]), TransferKind.MANUAL)!!.error!!.contains("cleanup failed"))
            assertEquals(TransferState.DONE, store.find(key(objects[1]), TransferKind.MANUAL)!!.state)
        } finally { engine.close() }
    }

    @Test fun cancelForegroundCleansPartialAndQueueContinues() = runBlocking {
        val objects = listOf(jpg(), jpg(8, "NEXT.JPG")); val store = Store(); val targets = Targets()
        val entered = CompletableDeferred<Unit>(); val source = Source(CameraSessionKey("serial", 10), objects)
        var once = true
        source.beforeStream = { if (once) { once = false; entered.complete(Unit); awaitCancellation() } }
        val engine = TransferEngine(this, store, targets, Submit())
        try {
            engine.startSession(source); engine.enqueueManual(source.sessionKey, objects)
            withTimeout(2_000) { entered.await() }; engine.cancelForeground(); idle(engine)
            assertEquals(TransferState.CANCELLED, store.find(key(objects[0]), TransferKind.MANUAL)!!.state)
            assertEquals(TransferState.DONE, store.find(key(objects[1]), TransferKind.MANUAL)!!.state); assertEquals(1, targets.aborted)
        } finally { engine.close() }
    }

    @Test fun staleCallbackCannotAckOrUsePreviousSessionHandle() = runBlocking {
        val source = Source(CameraSessionKey("serial", 10), listOf(jpg())); val next = Source(CameraSessionKey("serial", 11), listOf(jpg(99)))
        val store = Store(); val engine = TransferEngine(this, store, Targets(), Submit())
        try {
            engine.startSession(source); engine.startSession(next)
            try { engine.onObjectAdded(source.sessionKey, 7); fail("stale callback must throw") } catch (_: CancellationException) { }
            assertTrue(source.streamed.isEmpty()); assertTrue(next.streamed.isEmpty()); assertTrue(store.rows.isEmpty())
        } finally { engine.close() }
    }

    @Test fun successfulStateSurvivesCloseErrorAndNextItemStillRuns() = runBlocking {
        val objects = listOf(jpg(), jpg(8, "NEXT.JPG"))
        val store = Store(); val targets = Targets().apply { closeFailure = true }
        val submit = Submit(); val source = Source(CameraSessionKey("serial", 10), objects)
        val engine = TransferEngine(this, store, targets, submit)
        try {
            engine.startSession(source)
            engine.onObjectAdded(source.sessionKey, 7)
            engine.onObjectAdded(source.sessionKey, 8)
            engine.completeBaseline(source.sessionKey, objects, setOf(7L, 8L))
            idle(engine)
            val first = store.find(key(objects[0]), TransferKind.AUTO)!!
            val second = store.find(key(objects[1]), TransferKind.AUTO)!!
            assertEquals(TransferState.DONE, first.state)
            assertEquals(99L, first.taskId)
            assertTrue(first.error!!.contains("Close failed"))
            assertEquals(TransferState.DONE, second.state)
            assertEquals(99L, second.taskId)
            assertEquals(2, submit.calls.size)
        } finally { engine.close() }
    }

    @Test fun delayedDuplicateAfterReconnectCannotClaimPreviousSessionBaseline() = runBlocking {
        val store = Store(); val targets = Targets(); val submit = Submit()
        val first = Source(CameraSessionKey("serial", 10), listOf(jpg()))
        val engine = TransferEngine(this, store, targets, submit)
        try {
            engine.startSession(first); engine.completeBaseline(first.sessionKey, listOf(jpg()))
            val secondInfo = jpg(99)
            val second = Source(CameraSessionKey("serial", 11), listOf(secondInfo))
            engine.startSession(second)
            val observed = ConcurrentHashMap.newKeySet<Long>()
            engine.completeBaseline(second.sessionKey, listOf(secondInfo), observed)
            observed += 99L
            engine.onObjectAdded(second.sessionKey, 99); idle(engine)
            assertNull(store.find(key(secondInfo), TransferKind.AUTO))
            assertNotNull(store.find(key(secondInfo), TransferKind.BASELINE))
            assertTrue(submit.calls.isEmpty()); assertTrue(second.streamed.isEmpty())
        } finally { engine.close() }
    }

    @Test fun baselineDeduplicatesReconnectButDifferentSerialRemainsIndependent() = runBlocking {
        val store = Store(); val submit = Submit(); val targets = Targets()
        val source = Source(CameraSessionKey("serial", 10), listOf(jpg())); val engine = TransferEngine(this, store, targets, submit)
        try {
            engine.startSession(source); engine.completeBaseline(source.sessionKey, listOf(jpg()))
            engine.onObjectAdded(source.sessionKey, 7); idle(engine); assertTrue(submit.calls.isEmpty())
            val second = Source(CameraSessionKey("other", 10), listOf(jpg()))
            engine.startSession(second); engine.onObjectAdded(second.sessionKey, 7)
            engine.completeBaseline(second.sessionKey, listOf(jpg()), setOf(7)); idle(engine)
            assertEquals(1, submit.calls.size)
        } finally { engine.close() }
    }
}
