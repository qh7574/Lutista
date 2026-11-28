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
        var originalPresent = true
        val commits = CopyOnWriteArrayList<String>()
        val releases = CopyOnWriteArrayList<String>()
        val swept = CopyOnWriteArrayList<Set<String>>()
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
        override suspend fun committedOriginalExists(record: TransferRecord, targetPath: String) = originalPresent
        var materialized: String? = null
        override suspend fun materializeOriginal(record: TransferRecord) = materialized
        override suspend fun release(record: TransferRecord, downloaded: DownloadedTransfer) { releases += record.key }
        override suspend fun sweepOrphanedStaging(protectedKeys: Set<String>) { swept += protectedKeys }
    }
    private class FakeSubmitter : TetherTaskSubmitter {
        var enabled = true
        var effects: TetherEffects? = TetherEffects(1, 0)
        val submissions = CopyOnWriteArrayList<String>()
        override suspend fun automaticEnabled() = enabled
        override suspend fun resolveEffects() = effects
        var productMissing = false
        override suspend fun processingRequired(key: String) = productMissing
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
        var streams = 0
        override suspend fun streamObject(handle: Long, sink: (ByteArray, Int) -> Unit, onProgress: (Long) -> Unit): Long {
            streams += 1
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

    @Test fun cachedOriginalSkipsTheUsbTransferButStillRunsProcessing() = runBlocking {
        val source = FakeSource(jpeg(), byteArrayOf(1, 2, 3, 4))
        val store = MemoryStore()
        val target = MemoryTargetStorage()
        val submitter = FakeSubmitter()
        val engine = TransferEngine(this, store, target, submitter)
        engine.startSession(source)
        engine.enqueueManual(source.sessionKey, listOf(jpeg()))
        withTimeout(2_000) { engine.awaitIdle() }
        assertEquals(1, target.commits.size)
        assertEquals(1, submitter.submissions.size)

        // 原片仍在输入文件夹里，但没有成品：滤镜直出要求补做处理，且不得再走 USB。
        submitter.productMissing = true
        target.materialized = "/cache/tether-originals/MANUAL-key/IMG.JPG"
        engine.enqueueManual(source.sessionKey, listOf(jpeg()))
        withTimeout(2_000) { engine.awaitIdle() }

        assertEquals("已下载的原片不应再次传输", 1, target.commits.size)
        assertEquals("已下载的原片不应再次读取相机", 1, source.streams)
        assertEquals("但必须补交一次滤镜直出任务", 2, submitter.submissions.size)
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

    @Test fun committedOriginalReleasesPrivateStagingWhenNoProcessingTaskOwnsIt() = runBlocking {
        val source = FakeSource(jpeg(), byteArrayOf(1, 2, 3, 4)); val store = MemoryStore()
        val target = MemoryTargetStorage(); val submitter = FakeSubmitter().apply { effects = null }
        val engine = TransferEngine(this, store, target, submitter); engine.startSession(source)
        engine.enqueueManual(source.sessionKey, listOf(jpeg()))
        withTimeout(2_000) { engine.awaitIdle() }
        assertEquals(1, target.commits.size)
        assertEquals(target.commits.toList(), target.releases.toList())
        assertEquals(TransferState.DONE, store.rows.values.single().state)
        engine.close()
    }

    @Test fun explicitRedownloadRetriesWhenTheCommittedFileVanished() = runBlocking {
        val source = FakeSource(jpeg(), byteArrayOf(1, 2, 3, 4)); val store = MemoryStore()
        val target = MemoryTargetStorage(); val submitter = FakeSubmitter().apply { effects = null }
        val engine = TransferEngine(this, store, target, submitter); engine.startSession(source)
        engine.enqueueManual(source.sessionKey, listOf(jpeg()))
        withTimeout(2_000) { engine.awaitIdle() }
        assertEquals(1, target.commits.size)
        // 用户在文件管理器里删掉了输入文件夹里的原片：再次点下载必须真的重传。
        target.originalPresent = false
        engine.enqueueManual(source.sessionKey, listOf(jpeg()))
        withTimeout(2_000) { engine.awaitIdle() }
        assertEquals(2, target.commits.size)
        assertEquals(TransferState.DONE, store.rows.values.single().state)
        engine.close()
    }

    @Test fun manualEnqueueReportsHowManyWereSkippedAndHowManyProduceAProduct() = runBlocking {
        val source = FakeSource(jpeg(), byteArrayOf(1, 2, 3, 4)); val store = MemoryStore()
        val target = MemoryTargetStorage()
        val submitter = FakeSubmitter().apply { effects = TetherEffects(1L, 0L); productMissing = true }
        val engine = TransferEngine(this, store, target, submitter); engine.startSession(source)
        val first = engine.enqueueManual(source.sessionKey, listOf(jpeg()))
        assertEquals("首次下载要真的传输，并且会产出一张成品", ManualEnqueueSummary(0, 1), first)
        withTimeout(2_000) { engine.awaitIdle() }

        // 原片还在、成品也还在：重复点击应当整批跳过，并且不打扰用户。
        submitter.productMissing = false
        val duplicate = engine.enqueueManual(source.sessionKey, listOf(jpeg()))
        assertEquals(ManualEnqueueSummary(skipped = 1, processed = 0), duplicate)

        // 原片还在但缺成品：跳过 USB 传输，但仍要计数为「处理滤镜 1 张」。
        submitter.productMissing = true
        target.materialized = "/cache/tether-originals/MANUAL-key/IMG.JPG"
        val reprocess = engine.enqueueManual(source.sessionKey, listOf(jpeg()))
        assertEquals("已下载且缺成品：既算跳过下载，也算处理一张", ManualEnqueueSummary(skipped = 1, processed = 1), reprocess)
        withTimeout(2_000) { engine.awaitIdle() }
        assertEquals("跳过时不得再次读相机", 1, source.streams)
        engine.close()
    }

    @Test fun explicitRedownloadSkipsWhenTheCommittedFileIsStillThere() = runBlocking {
        val source = FakeSource(jpeg(), byteArrayOf(1, 2, 3, 4)); val store = MemoryStore()
        val target = MemoryTargetStorage(); val submitter = FakeSubmitter().apply { effects = null }
        val engine = TransferEngine(this, store, target, submitter); engine.startSession(source)
        engine.enqueueManual(source.sessionKey, listOf(jpeg()))
        withTimeout(2_000) { engine.awaitIdle() }
        engine.enqueueManual(source.sessionKey, listOf(jpeg()))
        withTimeout(2_000) { engine.awaitIdle() }
        assertEquals(1, target.commits.size)
        engine.close()
    }

    @Test fun stagingSweepProtectsRecoverableTransferIdentities() = runBlocking {
        val store = MemoryStore()
        store.rows["manual-key" to TransferKind.MANUAL] = TransferRecord(
            id = 1, key = "manual-key", kind = TransferKind.MANUAL, handle = 7, name = "IMG.JPG",
            size = 4, state = TransferState.DOWNLOADED, createdAt = 0,
        )
        val target = MemoryTargetStorage()
        val engine = TransferEngine(this, store, target, FakeSubmitter())
        engine.sweepOrphanedStaging()
        assertEquals(setOf("MANUAL-manual-key"), target.swept.single())
        engine.close()
    }

    @Test fun processingTaskKeepsTheReadableStagingOriginal() = runBlocking {
        val source = FakeSource(jpeg(), byteArrayOf(1, 2, 3, 4)); val store = MemoryStore()
        val target = MemoryTargetStorage(); val submitter = FakeSubmitter()
        val engine = TransferEngine(this, store, target, submitter); engine.startSession(source)
        engine.enqueueManual(source.sessionKey, listOf(jpeg()))
        withTimeout(2_000) { engine.awaitIdle() }
        assertEquals(1, submitter.submissions.size)
        assertTrue(target.releases.isEmpty())
        assertEquals(TransferState.DONE, store.rows.values.single().state)
        engine.close()
    }
}
