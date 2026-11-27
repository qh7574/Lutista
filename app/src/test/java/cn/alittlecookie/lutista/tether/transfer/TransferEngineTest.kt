package cn.alittlecookie.lutista.tether.transfer

import cn.alittlecookie.lutista.tether.objects.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class TransferEngineTest {
    private class MemoryStore : TransferStore {
        val rows = LinkedHashMap<Pair<String, TransferKind>, TransferRecord>()
        override suspend fun find(key: String, kind: TransferKind) = rows[key to kind]
        override suspend fun insertIfAbsent(record: TransferRecord): TransferRecord = rows.getOrPut(record.key to record.kind) { record }
        override suspend fun save(record: TransferRecord) { rows[record.key to record.kind] = record }
        override suspend fun recoverable() = rows.values.filter { it.state in setOf(TransferState.PENDING, TransferState.RUNNING, TransferState.DOWNLOADED, TransferState.SUBMITTING, TransferState.SUBMISSION_FAILED) }
    }
    private class MemoryTargetStorage : TransferTargetStorage {
        val commits = CopyOnWriteArrayList<String>()
        override suspend fun prepare(key: String, info: CameraObject, kind: TransferKind): TransferTarget = object : TransferTarget {
            override val destinationHint = "memory:$key"
            private val bytes = java.io.ByteArrayOutputStream()
            override fun write(bytes: ByteArray, length: Int) { this.bytes.write(bytes, 0, length) }
            override fun finish() { check(bytes.size() == info.size.toInt()) }
            override suspend fun commit() = DownloadedTransfer(destinationHint, destinationHint, bytes.size().toLong()).also { commits += key }
            override suspend fun abort() = Unit
            override fun close() = Unit
        }
        override suspend fun recover(record: TransferRecord) = null
    }
    private class FakeSubmitter : TetherTaskSubmitter {
        var enabled = true
        var effects = TetherEffects(1, 0)
        val submissions = CopyOnWriteArrayList<String>()
        override suspend fun automaticEnabled() = enabled
        override suspend fun resolveEffects() = effects
        override suspend fun submit(key: String, downloaded: DownloadedTransfer, effects: TetherEffects): Long {
            submissions += key; return submissions.size.toLong()
        }
    }
    private class FakeSource(private val info: CameraObject, private val payload: ByteArray) : CameraObjectSource {
        override val sessionKey = CameraSessionKey("serial", 10)
        val infos = linkedMapOf(info.handle to info)
        var failInfo = false
        override fun listTree(): Flow<ObjectPage> = flowOf(ObjectPage.Batch(infos.values.toList()), ObjectPage.Complete(infos.size, mapOf(1L to infos.size)))
        override suspend fun listChildren(parent: Long, storageId: Long?) = infos.values.toList()
        override suspend fun getObjectInfo(handle: Long): CameraObject { if (failInfo) error("camera info failed"); return infos[handle] ?: error("missing handle") }
        override suspend fun fetchThumb(handle: Long) = payload
        override suspend fun fetchRawPreview(handle: Long) = null
        override suspend fun fetchObject(handle: Long) = payload
        override suspend fun streamObject(handle: Long, sink: (ByteArray, Int) -> Unit, onProgress: (Long) -> Unit): Long {
            sink(payload, payload.size); onProgress(payload.size.toLong()); return payload.size.toLong()
        }
    }
    private fun jpeg(handle: Long = 7, size: Long = 4) = CameraObject(handle, 1, 0x3801, 0, size, "IMG.JPG", "raw", false)

    @Test fun eventObservedBeforeBaselinePersistsAutoAndIsNotMarkedBaseline() = runBlocking {
        val source = FakeSource(jpeg(), byteArrayOf(1, 2, 3, 4)); val store = MemoryStore(); val submit = FakeSubmitter()
        val engine = TransferEngine(this, store, MemoryTargetStorage(), submit)
        engine.startSession(source)
        val observed = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>(); observed += 7
        engine.onObjectAdded(source.sessionKey, 7)
        engine.completeBaseline(source.sessionKey, listOf(jpeg()), observed)
        withTimeout(2_000) { engine.awaitIdle() }
        assertNotNull(store.find(CameraObjectKeys.transferDedupeKey("serial", jpeg()), TransferKind.AUTO))
        assertNull(store.find(CameraObjectKeys.transferDedupeKey("serial", jpeg()), TransferKind.BASELINE))
        assertEquals(1, submit.submissions.size)
        engine.close()
    }

    @Test fun infoFailureIsThrownSoCallerCannotAck() = runBlocking {
        val source = FakeSource(jpeg(), byteArrayOf(1, 2, 3, 4)); source.failInfo = true
        val engine = TransferEngine(this, MemoryStore(), MemoryTargetStorage(), FakeSubmitter()); engine.startSession(source)
        try { engine.onObjectAdded(source.sessionKey, 7); fail("must throw") } catch (e: IllegalStateException) { assertEquals("camera info failed", e.message) }
        engine.close()
    }

    @Test fun wrongSizeFailsCurrentItemWithoutStoppingQueue() = runBlocking {
        val source = FakeSource(jpeg(size = 5), byteArrayOf(1, 2, 3, 4)); val store = MemoryStore(); val target = MemoryTargetStorage()
        source.infos[8L] = jpeg(handle = 8, size = 4)
        val engine = TransferEngine(this, store, target, FakeSubmitter()); engine.startSession(source)
        engine.enqueueManual(source.sessionKey, listOf(jpeg(size = 5), jpeg(handle = 8, size = 4)))
        withTimeout(2_000) { engine.awaitIdle() }
        assertEquals(1, target.commits.size)
        assertEquals(TransferState.FAILED, store.rows.values.first { it.handle == 7L }.state)
        assertEquals(TransferState.DONE, store.rows.values.first { it.handle == 8L }.state)
        engine.close()
    }
}
