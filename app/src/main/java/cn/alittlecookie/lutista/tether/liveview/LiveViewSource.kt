package cn.alittlecookie.lutista.tether.liveview

import cn.alittlecookie.lutista.tether.profile.PtpQuirks
import cn.alittlecookie.lutista.tether.session.CameraSession
import cn.alittlecookie.lutista.tether.session.PtpControlPort
import cn.alittlecookie.lutista.tether.session.PtpScheduler
import cn.alittlecookie.lutista.tether.session.SessionControlPort
import cn.alittlecookie.lutista.tether.transport.PtpProtocolException
import cn.alittlecookie.lutista.tether.transport.PtpResponse
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class LiveViewPhase { Stopped, Starting, Running, Switching, Stopping, Failed }
data class LiveViewState(val phase: LiveViewPhase = LiveViewPhase.Stopped,
    val format: LiveViewFormat? = null, val detail: String? = null)
data class LiveViewStats(val received: Long = 0, val published: Long = 0,
    val discarded: Long = 0, val invalid: Long = 0, val userYields: Long = 0)

/** No renderer/Bitmap dependency. Only the latest JPEG is retained; slow collectors skip frames. */
class LiveViewSource internal constructor(
    private val port: PtpControlPort,
    private val scope: CoroutineScope,
    private val quirks: PtpQuirks,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onReconnectRequired: (String) -> Unit = {},
) {
    constructor(session: CameraSession, scope: CoroutineScope, quirks: PtpQuirks,
        onReconnectRequired: (String) -> Unit = {}) :
        this(SessionControlPort(session), scope, quirks, onReconnectRequired = onReconnectRequired)

    private val lifecycle = Mutex()
    private val mutableFormats = MutableStateFlow<List<LiveViewFormat>>(emptyList())
    private val mutableState = MutableStateFlow(LiveViewState())
    private val mutableStats = MutableStateFlow(LiveViewStats())
    private val mutableFrame = MutableStateFlow<LiveViewFrame?>(null)
    val formats: StateFlow<List<LiveViewFormat>> = mutableFormats.asStateFlow()
    val state: StateFlow<LiveViewState> = mutableState.asStateFlow()
    val stats: StateFlow<LiveViewStats> = mutableStats.asStateFlow()
    val latestFrame: StateFlow<LiveViewFrame?> = mutableFrame.asStateFlow()
    val frames: Flow<LiveViewFrame> = latestFrame.filterNotNull()
    private var loop: Job? = null
    private val discard = AtomicInteger(0)
    private var cameraMayBeRunning = false

    suspend fun start(preferredIndex: Int = 0): LiveViewFormat = lifecycle.withLock {
        if (mutableState.value.phase == LiveViewPhase.Running) return@withLock requireNotNull(mutableState.value.format)
        val table = loadFormats()
        require(preferredIndex in table.indices) { "Format index is not advertised" }
        applyFormat(table[preferredIndex], LiveViewPhase.Starting)
    }

    suspend fun setFormat(index: Int): LiveViewFormat = lifecycle.withLock {
        val table = loadFormats()
        require(index in table.indices) { "Format index is not advertised" }
        if (mutableState.value.phase == LiveViewPhase.Running && mutableState.value.format == table[index]) return@withLock table[index]
        applyFormat(table[index], LiveViewPhase.Switching)
    }

    suspend fun stop() = lifecycle.withLock {
        mutableState.value = mutableState.value.copy(phase = LiveViewPhase.Stopping)
        // Cancellation may abandon a waiter, but never the admitted frame/stop transaction.
        withContext(NonCancellable) {
            cancelLoop()
            try { stopCamera() }
            finally {
                mutableFrame.value = null
                mutableState.value = LiveViewState()
            }
        }
    }

    /** Detached/fatal cleanup cancels work without touching a lost USB endpoint. */
    suspend fun abandon() = lifecycle.withLock {
        withContext(NonCancellable) { cancelLoop() }
        cameraMayBeRunning = false
        mutableFrame.value = null
        mutableState.value = LiveViewState()
    }

    /** Style changes invalidate the first three complete camera frames, without restarting USB. */
    fun discardNextFrames(count: Int = 3) { require(count >= 0); discard.set(count) }

    private suspend fun loadFormats(): List<LiveViewFormat> {
        if (mutableFormats.value.isNotEmpty()) return mutableFormats.value
        val result = port.runOp { it.liveViewFormats() }
        requireOk(result.response)
        return LiveViewCodec.formats(result.payload).also { mutableFormats.value = it }
    }

    private suspend fun applyFormat(format: LiveViewFormat, phase: LiveViewPhase): LiveViewFormat {
        mutableState.value = LiveViewState(phase, format)
        cancelLoop()
        mutableFrame.value = null
        try {
            // Stop is required even at initial start: the current-format placeholder is untrusted.
            port.runOp(PtpScheduler.Priority.ABORT) { requireOk(it.liveView(false)) }
            cameraMayBeRunning = false
            delay(maxOf(1_000L, quirks.liveViewFormatSwitchSettleMs))
            port.runOp { requireOk(it.setLiveViewFormat(format.payload())) }
            // Mark before admission: cancellation after camera acceptance must still stop it.
            cameraMayBeRunning = true
            port.runOp { requireOk(it.liveView(true)) }
            discardNextFrames()
            mutableState.value = LiveViewState(LiveViewPhase.Running, format)
            loop = scope.launch { frameLoop() }
            return format
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                if (!port.isFatal && port.isOpen) runCatching { stopCamera() }
                mutableFrame.value = null
                mutableState.value = LiveViewState(if (failure is CancellationException) LiveViewPhase.Stopped else LiveViewPhase.Failed,
                    format, failure.message)
            }
            throw failure
        }
    }

    private suspend fun cancelLoop() { loop?.cancelAndJoin(); loop = null }
    private suspend fun stopCamera() {
        if (!port.isOpen || port.isFatal) { cameraMayBeRunning = false; return }
        if (cameraMayBeRunning) port.runOp(PtpScheduler.Priority.ABORT) { requireOk(it.liveView(false)) }
        cameraMayBeRunning = false
    }

    private suspend fun frameLoop() {
        var lastValid = clock()
        try {
            while (kotlin.coroutines.coroutineContext.isActive) {
                if (port.isFatal || !port.isOpen) error("USB disconnected or fatal; reconnect required")
                if (port.pendingUserOps() > 0) {
                    // A long foreground download is intentional frame starvation, not a bad USB endpoint.
                    lastValid = clock()
                    mutableStats.value = mutableStats.value.copy(userYields = mutableStats.value.userYields + 1)
                    delay(5)
                    continue
                }
                if (clock() - lastValid > 3_000) error("超过 3 秒未收到取景帧，请重新连接")
                // Exactly one frame can be queued; USER/ABORT priority wins the next admission.
                val output = ByteArrayOutputStream()
                val response = port.runOp(PtpScheduler.Priority.LIVEVIEW) { api ->
                    api.liveViewFrame { chunk, length ->
                        if (length !in 0..chunk.size || output.size().toLong() + length > MAX_FRAME_BYTES)
                            throw PtpProtocolException("Liveview frame exceeds bounded buffer")
                        output.write(chunk, 0, length)
                    }
                }
                val frame = if (response.code == 0x2001) LiveViewCodec.frame(output.toByteArray(), clock()) else null
                if (frame == null) {
                    mutableStats.value = mutableStats.value.copy(invalid = mutableStats.value.invalid + 1)
                    delay(20)
                } else {
                    lastValid = clock()
                    val skip = discard.getAndUpdate { if (it > 0) it - 1 else 0 } > 0
                    val previous = mutableStats.value
                    mutableStats.value = previous.copy(received = previous.received + 1,
                        discarded = previous.discarded + if (skip) 1 else 0,
                        published = previous.published + if (skip) 0 else 1)
                    if (!skip) mutableFrame.value = frame
                    delay(1) // Fair to lifecycle/UI work, including scripted non-blocking cameras.
                }
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Throwable) {
            mutableFrame.value = null
            mutableState.value = mutableState.value.copy(phase = LiveViewPhase.Failed, detail = failure.message)
            onReconnectRequired(failure.message ?: "取景失败，请重新连接")
            // Real transport failures stop immediately; do not send cleanup to a broken endpoint.
        }
    }

    private fun requireOk(response: PtpResponse) {
        check(response.code == 0x2001) { "Liveview response 0x${response.code.toString(16)}" }
    }
    companion object { const val MAX_FRAME_BYTES = 4L * 1024 * 1024 }
}
