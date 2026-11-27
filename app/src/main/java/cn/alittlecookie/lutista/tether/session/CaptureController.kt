package cn.alittlecookie.lutista.tether.session

import cn.alittlecookie.lutista.tether.config.CameraProperty
import cn.alittlecookie.lutista.tether.config.PropertyCodec
import cn.alittlecookie.lutista.tether.driver.CameraStatusSource
import cn.alittlecookie.lutista.tether.profile.CameraProfile
import cn.alittlecookie.lutista.tether.profile.Feature
import cn.alittlecookie.lutista.tether.protocol.Availability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class CaptureUi(val busy: Boolean = false, val recording: Boolean = false,
    val lastError: String? = null)
enum class CaptureAction { Photo, MovieStarted, MovieStopped }
sealed interface PressResult {
    data class Accepted(val action: CaptureAction) : PressResult
    data class Ignored(val reason: String) : PressResult
    data class Rejected(val responseCode: Int) : PressResult
}

/** One accepted press = one 0x9404[0x03000011]; recording belongs to this controller. */
class CaptureController internal constructor(
    private val port: PtpControlPort,
    private val modePos: StateFlow<Long?>,
    private val profile: CameraProfile,
    private val scope: CoroutineScope,
    objectEvents: Flow<Unit>,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val captureAllowed: (video: Boolean) -> Boolean = { true },
    private val confirmMode: (suspend (PtpSessionApi) -> Long?)? = null,
    private val onModeObserved: (Long) -> Unit = {},
) {
    constructor(session: CameraSession, status: CameraStatusSource, profile: CameraProfile, scope: CoroutineScope,
        onModeObserved: (Long) -> Unit = {}) :
        this(SessionControlPort(session), status.modePos, profile, scope,
            session.events.filter { session.eventPump.isCurrent(it) }.map { Unit },
            captureAllowed = { video -> session.caps.value[if (video) Feature.CaptureMovie else Feature.CapturePhoto].availability != Availability.Unsupported },
            confirmMode = { api ->
                val data = api.getProperty(CameraProperty.MODE)
                check(data.response.code == 0x2001) { "无法确认相机模式" }
                PropertyCodec.decodeValue(data.payload, CameraProperty.MODE)
            }, onModeObserved = onModeObserved)

    private val lock = Any()
    private val stopping = Mutex()
    private val mutableUi = MutableStateFlow(CaptureUi())
    val ui: StateFlow<CaptureUi> = mutableUi.asStateFlow()
    private var inFlight = false
    private var admissionClosed = false
    private var stopInProgress = false
    private var disposed = false
    internal val exitRequested: Boolean get() = synchronized(lock) { admissionClosed }
    private var pressFinished: CompletableDeferred<Unit>? = null
    private var lastPress: Long? = null
    private var completionEpoch = 0L
    private var fallback: Job? = null
    private val eventJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        objectEvents.collect {
            synchronized(lock) {
                if (mutableUi.value.busy && !inFlight) {
                    completionEpoch++
                    fallback?.cancel()
                    mutableUi.value = mutableUi.value.copy(busy = false)
                }
            }
        }
    }

    /** Explicitly reopen admission when a new liveview visit starts, after exit cleanup completed. */
    fun resumePresses(): Boolean = synchronized(lock) {
        if (disposed || stopInProgress || inFlight || mutableUi.value.busy || port.isFatal || !port.isOpen) false
        else { admissionClosed = false; true }
    }

    suspend fun press(): PressResult {
        val caller = currentCoroutineContext()[Job]
        val previousPress: Long?
        val ownedRecording: Boolean
        val admittedAt: Long
        val completion = CompletableDeferred<Unit>()
        synchronized(lock) {
            if (admissionClosed || disposed) return PressResult.Ignored("快门入口已关闭")
            val cached = modePos.value
            if (cached == null && confirmMode == null) return PressResult.Ignored("相机模式未知")
            val cachedVideo = mutableUi.value.recording || cached == profile.quirks.videoModePos
            if ((!profile.capturePhotoSupported && !profile.captureMovieSupported) ||
                (!captureAllowed(false) && !captureAllowed(true)) || (confirmMode == null && !supported(cachedVideo)))
                return PressResult.Ignored("相机未提供当前快门操作")
            if (inFlight || mutableUi.value.busy) return PressResult.Ignored("正在处理上次快门")
            admittedAt = clock()
            val videoDebounce = mutableUi.value.recording || (confirmMode == null && cachedVideo)
            if (lastPress?.let { admittedAt - it < if (videoDebounce) 1_500L else 800L } == true)
                return PressResult.Ignored("防止连续触发")
            previousPress = lastPress
            lastPress = admittedAt
            ownedRecording = mutableUi.value.recording
            inFlight = true
            pressFinished = completion
            mutableUi.value = mutableUi.value.copy(busy = true, lastError = null)
        }
        var started = false
        try {
            return port.runOp { api ->
                try {
                    // A scheduler waiter may observe cancellation after this queued block is
                    // picked. Linearize capture admission against the original caller Job here.
                    // Once admitted, never consult it again: the actual toggle must drain safely.
                    caller?.ensureActive()
                    synchronized(lock) { started = true }
                    // Check MODE and trigger in one admitted transaction: no other USB operation
                    // or stale two-second UI poll can change which local dual state is recorded.
                    val mode = currentMode(api)
                    val video = mode == profile.quirks.videoModePos
                    val reason = when {
                        mode == null -> "相机模式未知"
                        ownedRecording && !video -> MODE_CHANGED
                        !supported(video) -> "相机未提供当前快门操作"
                        previousPress != null && admittedAt - previousPress < if (video) 1_500L else 800L -> "防止连续触发"
                        else -> null
                    }
                    if (reason != null) synchronized(lock) {
                        lastPress = previousPress
                        mutableUi.value = mutableUi.value.copy(busy = false, lastError = reason)
                        PressResult.Ignored(reason)
                    } else {
                        val starting = !ownedRecording
                        check(!port.isFatal && port.isOpen) { "USB 已断开，无法触发快门" }
                        synchronized(lock) { lastPress = clock() }
                        val response = if (video) api.movieRec(starting) else api.initiateCapture(true)
                        synchronized(lock) {
                            if (response.code != 0x2001) rejected(response.code)
                            else accepted(video, starting)
                        }
                    }
                } catch (failure: Throwable) {
                    synchronized(lock) { mutableUi.value = mutableUi.value.copy(busy = false, lastError = failure.message) }
                    throw failure
                } finally { synchronized(lock) { inFlight = false; completion.complete(Unit) } }
            }
        } catch (cancel: CancellationException) {
            // Cancellation removes queued presses, but never compensates an admitted toggle.
            withContext(NonCancellable) { runCatching { port.runOp(PtpScheduler.Priority.ABORT) {} } }
            synchronized(lock) {
                if (!started) {
                    inFlight = false; lastPress = previousPress
                    mutableUi.value = mutableUi.value.copy(busy = false, lastError = null)
                }
                completion.complete(Unit)
            }
            throw cancel
        } catch (failure: Throwable) {
            synchronized(lock) {
                inFlight = false; completion.complete(Unit)
                mutableUi.value = mutableUi.value.copy(busy = false, lastError = failure.message)
            }
            throw failure
        }
    }

    /**
     * Exit barrier: close new-press admission before waiting for an admitted start. Repeated calls
     * issue no extra toggle after success. A failed stop preserves recording for an explicit retry.
     * Lost/fatal endpoints are never sent a compensating command.
     */
    suspend fun stopOwnedRecording(): PressResult? {
        synchronized(lock) { admissionClosed = true }
        return stopping.withLock {
            withContext(NonCancellable) {
                val pending = synchronized(lock) { stopInProgress = true; pressFinished }
                try {
                    pending?.await()
                    if (port.isFatal || !port.isOpen || synchronized(lock) { disposed || !mutableUi.value.recording }) return@withContext null
                    val remaining = synchronized(lock) { lastPress?.let { maxOf(0L, it + 1_500 - clock()) } ?: 0L }
                    if (remaining > 0) delay(remaining)
                    if (port.isFatal || !port.isOpen) return@withContext null
                    synchronized(lock) { inFlight = true; mutableUi.value = mutableUi.value.copy(busy = true, lastError = null) }
                    try {
                        port.runOp { api ->
                            val mode = currentMode(api)
                            if (mode != profile.quirks.videoModePos || !supported(true)) synchronized(lock) {
                                mutableUi.value = mutableUi.value.copy(busy = false, lastError = MODE_CHANGED)
                                PressResult.Ignored(MODE_CHANGED)
                            } else {
                                check(!port.isFatal && port.isOpen) { "USB 已断开，无法停止录像" }
                                synchronized(lock) { lastPress = clock() }
                                val response = api.movieRec(false)
                                synchronized(lock) { if (response.code == 0x2001) accepted(true, false) else rejected(response.code) }
                            }
                        }
                    } catch (failure: Throwable) {
                        synchronized(lock) { mutableUi.value = mutableUi.value.copy(busy = false, lastError = failure.message) }
                        throw failure
                    } finally { synchronized(lock) { inFlight = false } }
                } finally { synchronized(lock) { stopInProgress = false } }
            }
        }
    }

    /** Repository exit helper: failures keep recording and throw so navigation/disable can abort. */
    suspend fun finishRecording(): Unit = withContext(NonCancellable) {
        when (val result = stopOwnedRecording()) {
            null, is PressResult.Accepted -> Unit
            is PressResult.Ignored -> error(result.reason)
            is PressResult.Rejected -> error("停止录像被拒绝 0x${result.responseCode.toString(16)}")
        }
        if (mutableUi.value.busy && !mutableUi.value.recording) {
            withTimeoutOrNull(2_000) { ui.first { !it.busy } }
            synchronized(lock) { if (!mutableUi.value.recording) mutableUi.value = mutableUi.value.copy(busy = false) }
        }
    }

    private suspend fun currentMode(api: PtpSessionApi): Long? {
        val mode = if (confirmMode != null) confirmMode.invoke(api) else modePos.value
        if (mode != null) onModeObserved(mode)
        return mode
    }
    private fun supported(video: Boolean) = captureAllowed(video) &&
        if (video) profile.captureMovieSupported && profile.quirks.captureMovieIsToggleInVideoMode else profile.capturePhotoSupported
    private fun rejected(code: Int): PressResult {
        mutableUi.value = mutableUi.value.copy(busy = false, lastError = "快门被拒绝 0x${code.toString(16)}")
        return PressResult.Rejected(code)
    }
    private fun accepted(video: Boolean, starting: Boolean): PressResult {
        if (video) mutableUi.value = mutableUi.value.copy(recording = starting, busy = !starting)
        if (!video || !starting) scheduleCompletion(if (video) 2_000 else 1_500)
        return PressResult.Accepted(if (!video) CaptureAction.Photo else if (starting) CaptureAction.MovieStarted else CaptureAction.MovieStopped)
    }
    private fun scheduleCompletion(timeoutMs: Long) {
        val epoch = ++completionEpoch
        fallback?.cancel()
        fallback = scope.launch {
            delay(timeoutMs)
            synchronized(lock) { if (completionEpoch == epoch) mutableUi.value = mutableUi.value.copy(busy = false) }
        }
    }
    /** Fatal/detached disposal only: never guesses recording state with another USB toggle. */
    fun close() {
        synchronized(lock) { admissionClosed = true; disposed = true }
        eventJob.cancel(); fallback?.cancel()
    }
    private companion object { const val MODE_CHANGED = "相机模式已改变，请切回录像模式后停止录制" }
}
