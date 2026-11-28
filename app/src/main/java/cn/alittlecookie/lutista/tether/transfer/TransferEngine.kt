package cn.alittlecookie.lutista.tether.transfer

import cn.alittlecookie.lutista.tether.objects.CameraObject
import cn.alittlecookie.lutista.tether.objects.CameraObjectKeys
import cn.alittlecookie.lutista.tether.objects.CameraObjectSource
import cn.alittlecookie.lutista.tether.objects.CameraSessionKey
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

private const val TRANSFER_LOG_TAG = "TetherTransfer"

/** One bounded queue / one streaming object. No camera handle survives a session change. */
class TransferEngine(
    scope: CoroutineScope,
    private val store: TransferStore,
    private val targets: TransferTargetStorage,
    private val taskSubmitter: TetherTaskSubmitter,
    private val now: () -> Long = System::currentTimeMillis,
    queueCapacity: Int = 64,
) {
    private class Session(val source: CameraObjectSource) {
        val key = source.sessionKey
        var baselineComplete = false
        val eventHandles = linkedSetOf<Long>()
        val earlyEvents = linkedMapOf<Long, CameraObject>()
        var observedEventHandles: Set<Long> = emptySet()
        val baselineWrittenKeys = linkedSetOf<String>()
    }
    private class Work(val session: Session, val info: CameraObject, val record: TransferRecord) {
        val token get() = "${session.key.id}:${record.kind}:${record.key}"
        var started = false
        var finished = false
        var cancelled = false
        var latest: TransferProgress? = null
        val entries = mutableListOf<BatchEntry>()
    }
    private class BatchEntry(val batchId: Long, val index: Int, var value: TransferProgress) {
        var started = false
        var settled = false
        var cancelled = false
    }
    private class Batch(val id: Long, val session: Session, val kind: TransferKind, val entries: List<BatchEntry>) {
        var currentIndex = 0
    }
    private val engineScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val gate = Mutex()
    private val lifecycle = Mutex()
    private val queue = Channel<Work>(queueCapacity.also { require(it > 0) })
    // Synchronous chunk callbacks and admission publish a single coherent progress snapshot here.
    private val progressLock = Any()
    private val scheduled = linkedMapOf<String, Work>()
    private val batchRecords = linkedMapOf<Long, Batch>()
    private var nextBatchId = 0L
    private val mutableBatches = MutableStateFlow<Map<Long, TransferBatchProgress>>(emptyMap())
    val batches: StateFlow<Map<Long, TransferBatchProgress>> = mutableBatches.asStateFlow()
    private val count = AtomicInteger()
    private val idle = MutableStateFlow(true)
    private val paused = MutableStateFlow(false)
    private val mutableProgress = MutableStateFlow(TransferProgress())
    val progress: StateFlow<TransferProgress> = mutableProgress.asStateFlow()
    private val mutableTransfers = MutableStateFlow<Map<String, TransferProgress>>(emptyMap())
    /** Retains each session item's latest state even when consecutive completions conflate. */
    val transfers: StateFlow<Map<String, TransferProgress>> = mutableTransfers.asStateFlow()
    @Volatile private var session: Session? = null
    @Volatile private var foreground: Job? = null

    init {
        engineScope.launch {
            for (work in queue) {
                try {
                    paused.first { !it }
                    val admitted = synchronized(progressLock) {
                        if (work.finished || session !== work.session) false else {
                            work.started = true
                            val value = requireNotNull(work.latest)
                            publishLocked(work, value)
                            true
                        }
                    }
                    if (!admitted) continue
                    supervisorScope {
                        val job = launch {
                            try { process(work) }
                            catch (_: CancellationException) { /* Session shutdown. */ }
                            catch (e: Exception) {
                                // Persistence/cleanup failure must not escape launch and kill Android.
                                publish(work, work.record.copy(state = TransferState.FAILED,
                                    error = e.message ?: e.javaClass.simpleName), 0)
                            }
                        }
                        foreground = job
                        if (session !== work.session) job.cancel()
                        job.join()
                        foreground = null
                    }
                } finally {
                    synchronized(progressLock) { finishWorkLocked(work, abandoned = session !== work.session) }
                }
            }
        }
    }

    /** SUSPEND. Call before collecting object events / enumerating the baseline. */
    suspend fun startSession(source: CameraObjectSource) = lifecycle.withLock {
        stopSessionInternal()
        gate.withLock { session = Session(source) }
        paused.value = false
        synchronized(progressLock) {
            batchRecords.clear()
            mutableBatches.value = emptyMap()
            mutableProgress.value = TransferProgress(sessionKey = source.sessionKey)
            mutableTransfers.value = emptyMap()
        }
    }

    /** Best-effort removal of private staging folders that no journal owns. */
    /** Reclaim private staging that no committed destination or pending recovery still needs. */
    suspend fun sweepOrphanedStaging() {
        val protected = runCatching {
            store.recoverable().map { "${it.kind.name}-${it.key}" }.toSet()
        }.getOrDefault(emptySet())
        targets.sweepOrphanedStaging(protected)
    }

    /** Disconnect/close. Cancels the current transaction, drains old work, keeps durable records. */
    suspend fun stopSession() = lifecycle.withLock { stopSessionInternal() }

    private suspend fun stopSessionInternal() {
        gate.withLock { session = null }
        foreground?.cancelAndJoin()
        while (true) {
            val stale = queue.tryReceive().getOrNull() ?: break
            synchronized(progressLock) { finishWorkLocked(stale, abandoned = true) }
        }
        synchronized(progressLock) {
            // Also settle registered items whose producer was blocked on the bounded channel.
            scheduled.values.toList().filter { !it.started }.forEach { finishWorkLocked(it, abandoned = true) }
        }
        // Wake a consumer holding a stale item behind the pause gate.
        paused.value = false
        mutableProgress.value = mutableProgress.value.copy(sessionKey = null, paused = false)
    }

    /** Mark the enumeration snapshot old, excluding events already seen in THIS session.
     * Recovery is rebound by stable key to freshly enumerated handles, never by stored handle.
     */
    suspend fun completeBaseline(
        sessionKey: CameraSessionKey, objects: List<CameraObject>, observedEventHandles: Set<Long> = emptySet()
    ) {
        val current = session?.takeIf { it.key == sessionKey } ?: return
        val toSchedule = mutableListOf<Pair<CameraObject, TransferKind>>()
        gate.withLock {
            if (session !== current || current.baselineComplete) return
            current.observedEventHandles = observedEventHandles // Intentionally retain the live, append-only set.
            val recovery = store.recoverable().groupBy { it.key }
            for (info in objects.filterNot { it.isDir }) {
                val key = key(current, info)
                val eventOwned = info.handle in observedEventHandles || info.handle in current.eventHandles
                val records = recovery[key].orEmpty().filter { it.kind != TransferKind.BASELINE }
                if (eventOwned) {
                    // An event observed before/around enumeration owns this identity; never let
                    // a just-written BASELINE row swallow its AUTO processing.
                    if (records.isNotEmpty()) records.forEach { toSchedule += info to it.kind }
                    else toSchedule += info to TransferKind.AUTO
                } else if (records.isNotEmpty()) {
                    records.forEach { toSchedule += info to it.kind }
                } else if (info.handle !in current.eventHandles && store.find(key, TransferKind.BASELINE) == null) {
                    store.insertIfAbsent(record(info, key, TransferKind.BASELINE).copy(state = TransferState.DONE))
                    // Only a baseline first written here may yield to a delayed live event.
                    // A prior session's durable baseline must keep rejecting duplicate events.
                    current.baselineWrittenKeys += key
                }
            }
            current.baselineComplete = true
            current.earlyEvents.values.forEach { toSchedule += it to TransferKind.AUTO }
            current.earlyEvents.clear()
        }
        for ((info, kind) in toSchedule) enqueue(current, info, kind)
    }

    /** Register the event handle BEFORE awaiting GetObjectInfo: baseline cannot swallow it. */
    suspend fun onObjectAdded(sessionKey: CameraSessionKey, handle: Long) {
        val current = session?.takeIf { it.key == sessionKey } ?: throw CancellationException("Stale camera event")
        gate.withLock {
            if (session !== current) throw CancellationException("Stale camera event")
            current.eventHandles += handle
        }
        val info = try { current.source.getObjectInfo(handle) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            mutableProgress.value = mutableProgress.value.copy(error = e.message ?: "GetObjectInfo failed")
            throw e // Caller must not acknowledge the durable camera event.
        }
        // Receiving the original is independent of the optional image-processing effects.
        val eligible = isJpeg(info) && taskSubmitter.automaticEnabled()
        val ready = gate.withLock {
            if (session !== current) throw CancellationException("Stale camera event")
            if (eligible) {
                val identity = key(current, info)
                // Persist before returning/acknowledging even while baseline enumeration is pending.
                if (store.find(identity, TransferKind.BASELINE) == null || eventOwnsBaseline(current, info, identity)) {
                    store.insertIfAbsent(record(info, identity, TransferKind.AUTO))
                }
            }
            if (!current.baselineComplete && eligible) current.earlyEvents[handle] = info
            current.baselineComplete
        }
        if (ready && eligible) enqueue(current, info, TransferKind.AUTO)
    }

    suspend fun retry(sessionKey: CameraSessionKey, info: CameraObject, kind: TransferKind = TransferKind.MANUAL) {
        require(kind == TransferKind.MANUAL) { "Automatic retries are event-driven" }
        enqueueManual(sessionKey, listOf(info))
    }

    /** Explicit manual selection is the only RAW/MOV entry. It does not bypass session checks. */
    suspend fun enqueueManual(sessionKey: CameraSessionKey, objects: List<CameraObject>) {
        val current = session?.takeIf { it.key == sessionKey } ?: return
        enqueueBatch(current, objects, TransferKind.MANUAL, forceRetry = true, includeCompleted = true)
    }

    fun stableKey(sessionKey: CameraSessionKey, info: CameraObject): String =
        CameraObjectKeys.transferDedupeKey(sessionKey.serial, info)

    suspend fun recordFor(sessionKey: CameraSessionKey, info: CameraObject, kind: TransferKind): TransferRecord? =
        store.find(stableKey(sessionKey, info), kind)

    /** Read-only lookup: unlike recovery it never deletes a partial or mutates a running transaction. */
    fun downloadedFor(sessionKey: CameraSessionKey, info: CameraObject, kind: TransferKind): DownloadedTransfer? {
        val state = mutableTransfers.value["$kind:${stableKey(sessionKey, info)}"] ?: return null
        return state.targetPath?.let { DownloadedTransfer(it, state.localPath, state.bytesDone) }
    }

    fun cancelForeground() { foreground?.cancel(CancellationException("Transfer cancelled by user")) }
    fun stopAfterCurrent() {
        paused.value = true
        mutableProgress.value = mutableProgress.value.copy(paused = true)
    }
    fun resume() {
        paused.value = false
        mutableProgress.value = mutableProgress.value.copy(paused = false)
    }
    suspend fun awaitIdle() { idle.first { it } }
    suspend fun close() { stopSession(); queue.close(); engineScope.cancel() }

    private suspend fun enqueue(current: Session, info: CameraObject, kind: TransferKind, forceRetry: Boolean = false) {
        enqueueBatch(current, listOf(info), kind, forceRetry, includeCompleted = false)
    }

    private suspend fun enqueueBatch(
        current: Session, objects: List<CameraObject>, kind: TransferKind,
        forceRetry: Boolean, includeCompleted: Boolean,
    ) {
        val work = gate.withLock {
            if (session !== current) return
            val candidates = mutableListOf<Pair<CameraObject, TransferRecord>>()
            for (info in objects.filterNot { it.isDir }.distinctBy { key(current, it) }) {
                // Keep AUTO/baseline eligibility and durable ACK semantics independent of UI batches.
                if (kind == TransferKind.AUTO && (!isJpeg(info) || !taskSubmitter.automaticEnabled())) continue
                val identity = key(current, info)
                val existing = store.find(identity, kind)
                if (kind == TransferKind.AUTO && existing == null && store.find(identity, TransferKind.BASELINE) != null &&
                    !eventOwnsBaseline(current, info, identity)) continue
                if (!includeCompleted && existing?.state == TransferState.DONE) continue
                if (existing?.state in setOf(TransferState.FAILED, TransferState.CANCELLED) && !forceRetry) continue
                val row = existing ?: store.insertIfAbsent(record(info, identity, kind))
                candidates += info to row
            }
            if (candidates.isEmpty()) return
            synchronized(progressLock) {
                if (session !== current) return
                // Repeated camera events remain idempotent and do not manufacture new batches.
                if (!includeCompleted && candidates.all { (info, row) ->
                    scheduled.containsKey("${current.key.id}:$kind:${row.key}") ||
                        mutableTransfers.value["$kind:${key(current, info)}"]?.state == TransferState.DONE
                }) return
                val batchId = ++nextBatchId
                val entries = mutableListOf<BatchEntry>()
                val toSend = mutableListOf<Work>()
                val pending = mutableTransfers.value.toMutableMap()
                for ((offset, candidate) in candidates.withIndex()) {
                    val (info, row) = candidate
                    val itemKey = "$kind:${row.key}"
                    val token = "${current.key.id}:$itemKey"
                    val running = scheduled[token]
                    val previous = pending[itemKey]
                    val alreadyDone = row.state == TransferState.DONE || previous?.state == TransferState.DONE
                    val initial = when {
                        running != null -> requireNotNull(running.latest)
                        alreadyDone && previous?.state == TransferState.DONE -> previous
                        alreadyDone -> TransferProgress(current.key, row.key, row.name, row.state,
                            row.bytesDone, row.size, count.get(), paused.value, row.error, info.handle, kind,
                            // DONE is persisted only after commit; PENDING/RUNNING targetPath can be staging.
                            row.targetPath.takeIf { it.isNotBlank() })
                        else -> TransferProgress(current.key, row.key, row.name, TransferState.PENDING,
                            0, row.size, count.get(), paused.value, null, info.handle, kind)
                    }.copy(batchId = batchId, batchIndex = offset + 1, batchTotal = candidates.size)
                    val entry = BatchEntry(batchId, offset + 1, initial)
                    entries += entry
                    if (alreadyDone && running == null) entry.settled = true
                    else {
                        val item = running ?: Work(current, info, row.copy(handle = info.handle,
                            state = if (forceRetry) TransferState.PENDING else row.state,
                            error = if (forceRetry) null else row.error)).also {
                            scheduled[token] = it
                            count.incrementAndGet()
                            idle.value = false
                            toSend += it
                        }
                        entry.started = item.started
                        item.entries += entry
                        if (item.latest == null) item.latest = initial
                    }
                    pending[itemKey] = initial
                }
                val batch = Batch(batchId, current, kind, entries).also {
                    it.currentIndex = entries.firstOrNull { entry -> entry.started }?.index ?: 0
                }
                batchRecords[batchId] = batch
                refreshBatchesLocked(setOf(batchId))
                // All selection members are visible before the first send can resume the consumer.
                mutableTransfers.value = pending
                val active = foreground?.isActive == true
                if (!active) mutableProgress.value = entries.first().value.copy(queued = count.get())
                toSend
            }
        }
        var sent = 0
        try {
            for (item in work) {
                if (session !== current) throw CancellationException("Stale camera session")
                if (!synchronized(progressLock) { item.finished }) queue.send(item)
                sent++
            }
        } catch (failure: Throwable) {
            synchronized(progressLock) {
                // send has prompt cancellation: an item may already have been picked up.
                // Only abandon items the consumer has not started; finish is idempotent.
                work.drop(sent).filter { !it.started }.forEach { finishWorkLocked(it, abandoned = true) }
            }
            throw failure
        }
    }

    private suspend fun process(work: Work) {
        var row = work.record
        var target: TransferTarget? = null
        var downloaded: DownloadedTransfer? = null
        var bytes = 0L
        try {
            ensureSession(work)
            // A crash may have happened after atomic file commit and before the DB update.
            downloaded = targets.recover(row)
            if (downloaded == null) {
                // Fresh ObjectInfo prevents overwritten/reused handles downloading a different file.
                val actual = work.session.source.getObjectInfo(work.info.handle)
                ensureSession(work)
                check(key(work.session, actual) == row.key) { "Object identity changed; refusing a reused camera handle" }
                check(actual.size >= 0) { "Invalid object size" }
                target = targets.prepare(row.key, actual, row.kind)
                row = row.copy(state = TransferState.RUNNING, bytesDone = 0, targetPath = target.destinationHint, error = null, updatedAt = now())
                store.save(row)
                publish(work, row, 0)
                val job = currentCoroutineContext()[Job]!!
                val returned = work.session.source.streamObject(actual.handle, { chunk, length ->
                    job.ensureActive()
                    ensureSession(work)
                    require(length in 0..chunk.size) { "Invalid stream chunk length" }
                    if (length.toLong() > actual.size - bytes) throw IOException("Object exceeds declared size")
                    target.write(chunk, length)
                    bytes += length
                    publish(work, row, bytes)
                })
                job.ensureActive()
                ensureSession(work)
                if (bytes != actual.size || returned != bytes) throw IOException("Object size mismatch: expected=${actual.size}, received=$bytes, reported=$returned")
                target.finish()
                downloaded = target.commit()
                if (downloaded.bytes != actual.size) throw IOException("Committed object size mismatch")
            }
            row = row.copy(state = TransferState.DOWNLOADED, targetPath = downloaded.targetPath, bytesDone = downloaded.bytes, error = null, updatedAt = now())
            store.save(row)
            publish(work, row, row.bytesDone, downloaded)
            val effects = if (isJpeg(work.info)) taskSubmitter.resolveEffects()?.takeIf { it.valid } else null
            if (effects == null && isJpeg(work.info)) {
                Timber.tag(TRANSFER_LOG_TAG).d("跳过滤镜直出：kind=%s key=%s（开关关闭或没有有效滤镜/签名）", row.kind, row.key)
            }
            if (effects != null) {
                check(!downloaded.localPath.isNullOrBlank()) { "Processing needs a readable original file" }
                row = row.copy(state = TransferState.SUBMITTING, updatedAt = now())
                store.save(row)
                // Includes the transfer kind so AUTO/MANUAL can never silently alias task identities.
                val taskId = taskSubmitter.submit("${row.kind}:${row.key}", downloaded, effects)
                Timber.tag(TRANSFER_LOG_TAG).i(
                    "提交滤镜直出任务：kind=%s key=%s taskId=%d filter=%d watermark=%d",
                    row.kind, row.key, taskId, effects.filterId, effects.watermarkId,
                )
                row = row.copy(taskId = taskId)
            }
            row = row.copy(state = TransferState.DONE, updatedAt = now())
            store.save(row)
            publish(work, row, row.bytesDone, downloaded)
            if (row.taskId == null) {
                // No image-processing task owns the readable staging copy, so the committed
                // destination is the only copy the user needs.
                val committed = requireNotNull(downloaded) { "Committed transfer missing after DONE" }
                val cleanupFailure = runCatching { targets.release(row, committed) }.exceptionOrNull()
                if (cleanupFailure != null) {
                    row = row.copy(error = listOfNotNull(row.error,
                        "Staging cleanup failed: ${cleanupFailure.message ?: cleanupFailure.javaClass.simpleName}")
                        .joinToString("; "), updatedAt = now())
                    store.save(row)
                    publish(work, row, row.bytesDone, downloaded)
                }
            }
        } catch (e: CancellationException) {
            work.cancelled = true
            withContext(NonCancellable) {
                // Record recoverability before cleanup: a failed delete must not lose the row.
                // Committed originals are retained and recoverable; only partial bytes are removed.
                row = row.copy(state = if (downloaded != null) TransferState.DOWNLOADED
                    else if (e.message?.contains("cancelled by user", ignoreCase = true) == true) TransferState.CANCELLED else TransferState.PENDING,
                    bytesDone = downloaded?.bytes ?: 0, error = e.message, updatedAt = now())
                store.save(row)
                val cleanup = cleanup(target)
                if (cleanup != null) {
                    row = row.copy(error = listOfNotNull(row.error, cleanup).joinToString("; "))
                    store.save(row)
                }
                publish(work, row, row.bytesDone, downloaded)
            }
        } catch (e: Exception) {
            withContext(NonCancellable) {
                row = row.copy(state = if (downloaded == null) TransferState.FAILED else TransferState.SUBMISSION_FAILED,
                    bytesDone = downloaded?.bytes ?: bytes, error = e.message ?: e.javaClass.simpleName, updatedAt = now())
                store.save(row)
                val cleanup = cleanup(target)
                if (cleanup != null) {
                    row = row.copy(error = listOfNotNull(row.error, cleanup).joinToString("; "))
                    store.save(row)
                }
                publish(work, row, row.bytesDone, downloaded)
            }
        } finally {
            // Close may report a delayed filesystem error after the original/task committed.
            // Preserve its successful state and task ID, but retain the diagnostic durably.
            val closeFailure = runCatching { target?.close() }.exceptionOrNull()
            if (closeFailure != null) withContext(NonCancellable) {
                row = row.copy(error = listOfNotNull(row.error,
                    "Close failed: ${closeFailure.message ?: closeFailure.javaClass.simpleName}").joinToString("; "),
                    updatedAt = now())
                // Storage failure here must remain isolated from the next queue item.
                runCatching { store.save(row) }
                publish(work, row, row.bytesDone, downloaded)
            }
        }
    }

    private suspend fun cleanup(target: TransferTarget?): String? = try {
        target?.abort()
        null
    } catch (e: Exception) { "Partial cleanup failed: ${e.message ?: e.javaClass.simpleName}" }

    private fun ensureSession(work: Work) {
        if (session !== work.session || work.session.source.sessionKey != work.session.key) {
            throw CancellationException("Stale camera session")
        }
    }
    private fun publish(work: Work, row: TransferRecord, bytes: Long, downloaded: DownloadedTransfer? = null) {
        synchronized(progressLock) {
            if (work.finished) return
            val previous = work.latest
            val value = TransferProgress(work.session.key, row.key, row.name, row.state,
                bytes, row.size, count.get(), paused.value, row.error, work.info.handle, row.kind,
                downloaded?.targetPath ?: previous?.targetPath, downloaded?.localPath ?: previous?.localPath)
            publishLocked(work, value)
        }
    }

    /** All callers hold progressLock; never expose destinationHint/row.targetPath before commit. */
    private fun publishLocked(work: Work, value: TransferProgress) {
        work.latest = value
        work.entries.forEach { entry ->
            entry.started = work.started
            entry.cancelled = work.cancelled
            entry.value = value.copy(batchId = entry.batchId, batchIndex = entry.index,
                batchTotal = batchRecords[entry.batchId]?.entries?.size ?: entry.value.batchTotal)
            if (work.started) batchRecords[entry.batchId]?.currentIndex = entry.index
        }
        refreshBatchesLocked(work.entries.mapTo(linkedSetOf()) { it.batchId })
        // A repeated manual selection can observe the same work without another camera read.
        val primary = work.entries.lastOrNull { batchRecords[it.batchId]?.kind == TransferKind.MANUAL }
            ?: work.entries.lastOrNull()
        val published = primary?.value ?: value
        mutableTransfers.value = mutableTransfers.value + ("${work.record.kind}:${work.record.key}" to published)
        mutableProgress.value = published
    }

    private fun finishWorkLocked(work: Work, abandoned: Boolean) {
        if (work.finished) return
        if (abandoned && !work.started) {
            work.cancelled = true
            publishLocked(work, requireNotNull(work.latest).copy(state = TransferState.CANCELLED,
                error = "Camera session closed before transfer started"))
        }
        work.finished = true
        work.entries.forEach { it.settled = true; it.cancelled = work.cancelled }
        refreshBatchesLocked(work.entries.mapTo(linkedSetOf()) { it.batchId })
        if (scheduled[work.token] === work) scheduled.remove(work.token)
        val remaining = count.decrementAndGet().coerceAtLeast(0)
        mutableProgress.value = mutableProgress.value.copy(queued = remaining)
        if (remaining == 0) idle.value = true
    }

    private fun refreshBatchesLocked(ids: Set<Long>) {
        val snapshots = mutableBatches.value.toMutableMap()
        for (id in ids) {
            val batch = batchRecords[id] ?: continue
            val allDone = batch.entries.all { it.settled }
            snapshots[id] = TransferBatchProgress(id, batch.session.key, batch.kind, batch.entries.size,
                currentIndex = if (allDone) batch.entries.size else batch.currentIndex,
                completedCount = batch.entries.count { it.value.state in committedTransferStates },
                failedCount = batch.entries.count { it.value.state in setOf(TransferState.FAILED, TransferState.SUBMISSION_FAILED) },
                cancelledCount = batch.entries.count { it.cancelled }, active = !allDone,
                bytesDone = batch.entries.sumOf { it.value.bytesDone.coerceAtLeast(0) },
                totalBytes = batch.entries.sumOf { it.value.totalBytes.coerceAtLeast(0) })
        }
        mutableBatches.value = snapshots
    }
    private fun eventOwnsBaseline(session: Session, info: CameraObject, key: String): Boolean =
        key in session.baselineWrittenKeys && info.handle in session.observedEventHandles

    private fun key(session: Session, info: CameraObject) = CameraObjectKeys.transferDedupeKey(session.key.serial, info)
    private fun record(info: CameraObject, key: String, kind: TransferKind) = TransferRecord(
        key = key, kind = kind, handle = info.handle, name = info.name, size = info.size, createdAt = now()
    )
    private fun isJpeg(info: CameraObject) = !info.isDir && info.format == 0x3801L &&
        info.name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg")
}
