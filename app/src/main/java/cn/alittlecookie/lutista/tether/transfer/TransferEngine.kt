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
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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
    private data class Work(val session: Session, val info: CameraObject, val record: TransferRecord) {
        val token get() = "${session.key.id}:${record.kind}:${record.key}"
    }
    private val engineScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val gate = Mutex()
    private val lifecycle = Mutex()
    private val queue = Channel<Work>(queueCapacity.also { require(it > 0) })
    private val scheduled = ConcurrentHashMap.newKeySet<String>()
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
                    if (session !== work.session) continue
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
                    scheduled.remove(work.token)
                    val remaining = count.decrementAndGet()
                    mutableProgress.value = mutableProgress.value.copy(queued = remaining.coerceAtLeast(0))
                    if (remaining == 0) idle.value = true
                }
            }
        }
    }

    /** SUSPEND. Call before collecting object events / enumerating the baseline. */
    suspend fun startSession(source: CameraObjectSource) = lifecycle.withLock {
        stopSessionInternal()
        gate.withLock { session = Session(source) }
        paused.value = false
        mutableProgress.value = TransferProgress(sessionKey = source.sessionKey)
        mutableTransfers.value = emptyMap()
    }

    /** Disconnect/close. Cancels the current transaction, drains old work, keeps durable records. */
    suspend fun stopSession() = lifecycle.withLock { stopSessionInternal() }

    private suspend fun stopSessionInternal() {
        gate.withLock { session = null }
        foreground?.cancelAndJoin()
        while (true) {
            val stale = queue.tryReceive().getOrNull() ?: break
            scheduled.remove(stale.token)
            if (count.decrementAndGet() == 0) idle.value = true
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
        val eligible = isJpeg(info) && taskSubmitter.automaticEnabled() && taskSubmitter.resolveEffects()?.valid == true
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
        enqueue(session?.takeIf { it.key == sessionKey } ?: return, info, kind, forceRetry = true)
    }

    /** Explicit manual selection is the only RAW/MOV entry. It does not bypass session checks. */
    suspend fun enqueueManual(sessionKey: CameraSessionKey, objects: List<CameraObject>) {
        val current = session?.takeIf { it.key == sessionKey } ?: return
        for (info in objects) enqueue(current, info, TransferKind.MANUAL, forceRetry = true)
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
        if (session !== current || info.isDir) return
        // Check before creating ANY AUTO record. Extension alone is not sufficient.
        if (kind == TransferKind.AUTO && (!isJpeg(info) || !taskSubmitter.automaticEnabled() ||
                    taskSubmitter.resolveEffects()?.valid != true)) return
        val key = key(current, info)
        val existing = store.find(key, kind)
        if (kind == TransferKind.AUTO && existing == null && store.find(key, TransferKind.BASELINE) != null &&
            !eventOwnsBaseline(current, info, key)) return
        if (existing?.state == TransferState.DONE) return
        // Recovery includes committed-but-not-submitted states. A fresh session rebinds
        // the durable identity to its new handle; only explicit selection retries terminal failures.
        if (existing?.state in setOf(TransferState.FAILED, TransferState.CANCELLED) && !forceRetry) return
        val row = existing ?: store.insertIfAbsent(record(info, key, kind))
        val work = Work(current, info, row.copy(handle = info.handle,
            state = if (forceRetry) TransferState.PENDING else row.state, error = if (forceRetry) null else row.error))
        if (!scheduled.add(work.token)) return
        count.incrementAndGet()
        idle.value = false
        mutableProgress.value = mutableProgress.value.copy(queued = count.get())
        try { queue.send(work) }
        catch (e: Throwable) {
            scheduled.remove(work.token)
            if (count.decrementAndGet() == 0) idle.value = true
            throw e
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
            publish(work, row, row.bytesDone)
            val effects = if (isJpeg(work.info)) taskSubmitter.resolveEffects()?.takeIf { it.valid } else null
            if (effects != null) {
                check(!downloaded.localPath.isNullOrBlank()) { "Processing needs a readable original file" }
                row = row.copy(state = TransferState.SUBMITTING, updatedAt = now())
                store.save(row)
                // Includes the transfer kind so AUTO/MANUAL can never silently alias task identities.
                val taskId = taskSubmitter.submit("${row.kind}:${row.key}", downloaded, effects)
                row = row.copy(taskId = taskId)
            }
            row = row.copy(state = TransferState.DONE, updatedAt = now())
            store.save(row)
            publish(work, row, row.bytesDone, downloaded)
        } catch (e: CancellationException) {
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
                publish(work, row, row.bytesDone)
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
                publish(work, row, row.bytesDone)
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
        val previous = mutableTransfers.value["${row.kind}:${row.key}"]
        val value = TransferProgress(work.session.key, row.key, row.name, row.state,
            bytes, row.size, count.get(), paused.value, row.error, work.info.handle, row.kind,
            downloaded?.targetPath ?: previous?.targetPath, downloaded?.localPath ?: previous?.localPath)
        mutableProgress.value = value
        mutableTransfers.value = mutableTransfers.value + ("${row.kind}:${row.key}" to value)
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
