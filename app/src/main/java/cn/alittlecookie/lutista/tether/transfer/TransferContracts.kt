package cn.alittlecookie.lutista.tether.transfer

import cn.alittlecookie.lutista.tether.objects.CameraObject
import cn.alittlecookie.lutista.tether.objects.CameraSessionKey
import kotlinx.coroutines.flow.StateFlow

/** AUTO is strictly PTP JPEG (0x3801); all other formats require an explicit MANUAL request. */
enum class TransferKind { AUTO, MANUAL, BASELINE }
enum class TransferState { PENDING, RUNNING, DOWNLOADED, SUBMITTING, DONE, FAILED, CANCELLED, SUBMISSION_FAILED }

/** A transfer is downloaded only once commit produced a durable original at the destination. */
internal val committedTransferStates = setOf(
    TransferState.DOWNLOADED, TransferState.SUBMITTING, TransferState.SUBMISSION_FAILED, TransferState.DONE,
)

data class TransferRecord(
    val id: Long = 0,
    val key: String,
    val kind: TransferKind,
    val handle: Long,
    val name: String,
    val size: Long,
    val targetPath: String = "",
    val state: TransferState = TransferState.PENDING,
    val bytesDone: Long = 0,
    val taskId: Long? = null,
    val error: String? = null,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
)

/** Idempotency is enforced by a UNIQUE(key,kind), never by a connection-local handle. */
interface TransferStore {
    suspend fun find(key: String, kind: TransferKind): TransferRecord?
    suspend fun insertIfAbsent(record: TransferRecord): TransferRecord
    suspend fun save(record: TransferRecord)
    suspend fun recoverable(): List<TransferRecord>
}

data class TetherEffects(val filterId: Long = 0, val watermarkId: Long = 0) {
    val valid: Boolean get() = filterId > 0 || watermarkId > 0
}

/** localPath is the unchanged, readable JPEG original used for decoding AND EXIF.
 * targetPath is the actual original path/SAF URI shown to the user. */
data class DownloadedTransfer(val targetPath: String, val localPath: String?, val bytes: Long)

interface TetherTaskSubmitter {
    suspend fun automaticEnabled(): Boolean
    /** Resolve existing, parseable global effects; invalid IDs must return null/zero. */
    suspend fun resolveEffects(): TetherEffects?
    /** Idempotent even if a process dies between task insert and transfer.taskId update. */
    suspend fun submit(key: String, downloaded: DownloadedTransfer, effects: TetherEffects): Long
}

/** An output transaction must keep partial bytes invisible to ordinary image watchers.
 * write consumes the first length bytes synchronously; no whole-object buffer is allowed.
 * commit must be recoverable by key if the process dies before the DB update. */
interface TransferTarget : AutoCloseable {
    val destinationHint: String
    fun write(bytes: ByteArray, length: Int)
    fun finish()
    suspend fun commit(): DownloadedTransfer
    /** Removes partial/uncommitted bytes only; committed originals must survive. */
    suspend fun abort()
}

interface TransferTargetStorage {
    suspend fun prepare(key: String, info: CameraObject, kind: TransferKind): TransferTarget
    /** Recover committed originals, or discard partials before a complete restart. */
    suspend fun recover(record: TransferRecord): DownloadedTransfer?

    /** Drop the private staging copy after the committed destination no longer needs it.
     * Implementations must keep [DownloadedTransfer.targetPath] intact and must be idempotent. */
    suspend fun release(record: TransferRecord, downloaded: DownloadedTransfer) = Unit

    /** Best-effort cleanup of private staging left behind by interrupted sessions.
     * [protectedKeys] hold `KIND-key` identities whose staging may still be read for crash
     * recovery or a pending submission; implementations must never delete those. */
    suspend fun sweepOrphanedStaging(protectedKeys: Set<String> = emptySet()) = Unit
}

/** One explicit selection (or one automatic event); totalCount never follows the queue size.
 * currentIndex is one-based while an item is being attempted, zero before the batch starts.
 * completedCount counts committed originals, even when optional processing later fails. */
data class TransferBatchProgress(
    val id: Long,
    val sessionKey: CameraSessionKey,
    val kind: TransferKind,
    val totalCount: Int,
    val currentIndex: Int = 0,
    val completedCount: Int = 0,
    val failedCount: Int = 0,
    val cancelledCount: Int = 0,
    val active: Boolean = true,
    /** Sum of committed/streamed bytes across the entries, for one aggregate progress bar. */
    val bytesDone: Long = 0,
    /** Sum of the selected objects' sizes, available before the first byte is sent. */
    val totalBytes: Long = 0,
)

data class TransferProgress(
    val sessionKey: CameraSessionKey? = null,
    val key: String? = null,
    val name: String = "",
    val state: TransferState? = null,
    val bytesDone: Long = 0,
    val totalBytes: Long = 0,
    val queued: Int = 0,
    val paused: Boolean = false,
    val error: String? = null,
    val handle: Long? = null,
    val kind: TransferKind? = null,
    val targetPath: String? = null,
    val localPath: String? = null,
    val batchId: Long? = null,
    val batchIndex: Int = 0,
    val batchTotal: Int = 0,
)
