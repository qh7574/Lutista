package cn.alittlecookie.lutista.tether.session

import android.hardware.usb.UsbDevice
import android.os.SystemClock
import cn.alittlecookie.lutista.tether.debug.TetherDiagLogger
import cn.alittlecookie.lutista.tether.driver.CloseReason
import cn.alittlecookie.lutista.tether.driver.ConnectOutcome
import cn.alittlecookie.lutista.tether.driver.UsbTarget
import cn.alittlecookie.lutista.tether.probe.ProbeBudgetExceededException
import cn.alittlecookie.lutista.tether.profile.CameraCapabilities
import cn.alittlecookie.lutista.tether.protocol.DeviceInfoParser
import cn.alittlecookie.lutista.tether.protocol.PtpConstants
import cn.alittlecookie.lutista.tether.transport.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One lifecycle owns the factory handle, scheduler, and all subsequent USB I/O. */
class CameraSession(
    private val factory: TransportFactory,
    val scheduler: PtpScheduler,
    private val logger: TetherDiagLogger,
    private val scope: CoroutineScope,
    private val discover: suspend () -> UsbTarget? = { null },
    private val requestPermission: suspend (UsbTarget) -> Boolean = { it.permissionGranted },
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    private val eventDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val machine = TetherStateMachine()
    val state = machine.state
    private val mutableCaps = MutableStateFlow(CameraCapabilities.empty())
    val caps = mutableCaps.asStateFlow()
    private val mutableDeviceCaps = MutableStateFlow<PtpDeviceCaps?>(null)
    /** Exactly the L1 open return, including its original preflightFailure. */
    val deviceCaps = mutableDeviceCaps.asStateFlow()
    private val mutableStorageIds = MutableStateFlow<List<Long>>(emptyList())
    val storageIds = mutableStorageIds.asStateFlow()
    val eventPump = EventPump { logger.log("EVENT", "deliveryBackpressure=" + it) }
    val events = eventPump.events
    private val lifecycle = Mutex()
    private val admissionLock = Any()
    private val connecting = AtomicBoolean(false)
    private val connectAborted = AtomicBoolean(false)
    private val fatalCause = AtomicReference<Throwable?>(null)
    private val generation = AtomicLong()
    private val closing = AtomicBoolean(false)
    @Volatile private var eventDelivery: Deferred<Unit>? = null
    private val deferredEvents = ArrayDeque<PtpEvent>()
    @Volatile private var connectJob: Job? = null
    @Volatile private var transport: Transport? = null
    @Volatile private var diagnostics: PtpDiagnostics? = null
    @Volatile private var api: PtpSessionApiImpl? = null
    private var targetKey: String? = null
    private var cameraIdentity: String? = null
    private var probeJob: Job? = null
    private var pollJob: Job? = null
    private var capabilityWork: suspend (CameraSession, PtpDeviceCaps, Boolean) -> Unit = { _, _, _ -> }
    val transportFatal: Boolean? get() = diagnostics?.isFatal
    val transportOpen: Boolean? get() = diagnostics?.isOpen
    val isFatal: Boolean get() = diagnostics?.isFatal == true || fatalCause.get() != null
    val isOpen: Boolean get() = diagnostics?.isOpen == true
    val discardedEventContainers: Int get() = diagnostics?.discardedEventContainers ?: 0

    fun setCapabilityWork(work: suspend (CameraSession, PtpDeviceCaps, Boolean) -> Unit) { capabilityWork = work }
    fun publishCapabilities(value: CameraCapabilities) { mutableCaps.value = value }
    private var connectedInfo: PtpDeviceCaps? = null

    suspend fun connect(): ConnectOutcome = connectInternal(null)
    suspend fun connect(target: UsbTarget): ConnectOutcome = connectInternal(target)
    @Deprecated("use connect(target)")
    suspend fun connect(device: UsbDevice, permissionGranted: Boolean): ConnectOutcome = connect(UsbTarget(device, permissionGranted))

    private suspend fun connectInternal(requested: UsbTarget?): ConnectOutcome {
        if (!connecting.compareAndSet(false, true)) return ConnectOutcome.AlreadyConnecting
        connectAborted.set(false)
        connectJob = currentCoroutineContext()[Job]
        try {
            return lifecycle.withLock {
                if (isOpen && state.value.phase == TetherPhase.Connected &&
                    (requested == null || requested.cacheKey == targetKey)) return@withLock ConnectOutcome.Connected
                closeLocked(CloseReason.RECONNECT, preserveState = false)
                val activeGeneration = synchronized(admissionLock) {
                    val next = generation.incrementAndGet()
                    closing.set(false)
                    fatalCause.set(null)
                    next
                }
                mutableCaps.value = CameraCapabilities.empty()
                mutableDeviceCaps.value = null
                mutableStorageIds.value = emptyList()
                machine.move(TetherState(TetherPhase.Detecting, message = "正在检测相机…"))
                val found = requested ?: withTimeoutOrNull(3_000) { discover() }
                if (found == null || !factory.supports(found)) {
                    machine.move(TetherState(TetherPhase.NotAttached, message = "未检测到相机，请连接并选择 PC(Tether)"))
                    return@withLock ConnectOutcome.NotAttached
                }
                if (!found.permissionGranted) {
                    machine.move(TetherState(TetherPhase.PermissionNeeded, message = "等待 USB 授权"))
                    val granted = withTimeoutOrNull(8_000) { requestPermission(found) } == true
                    if (!granted) {
                        machine.move(TetherState(TetherPhase.NotAttached, message = "USB 授权未完成，可重试"))
                        return@withLock ConnectOutcome.PermissionDenied
                    }
                }
                machine.move(TetherState(TetherPhase.Connecting, message = "正在连接…"))
                val started = nowMs()
                var deadline = started + CONNECT_BUDGET_MS
                val target = found.copy(permissionGranted = true)
                targetKey = target.cacheKey
                // 传输层是否判定「相机一个字节都没回」：决定失败时给用户哪条处置建议。
                // 完全沉默只有关相机电源能救（拔数据线无效，见 CAMERA_SILENT_HINT）。
                var openSilent = false
                try {
                    scheduler.start()
                    val opened = scheduler.run {
                        checkConnecting(deadline)
                        // 传输层自愈（清 halt → 精确排空 → 类请求 Cancel/DeviceReset → 重试预检）
                        // 是有界的，但它**不属于**「PTP 会话建立」的 5 s 预算：真机
                        // logs_1795791570292 的 22:57:56 那一次，复位在 455 ms 内成功、
                        // 重新预检 4 ms 就 OK，结果 open() 返回后立刻被 5 s 预算判死
                        // （日志 `connect Connected connectionMs=2027` 与
                        // `Connection exceeded the fixed 5 second budget` 交替出现）。
                        //
                        // 只按传输层**自报**的自愈耗时补预算（`Transport.openRecoveryMs`）：
                        // 干净连接与 fake 传输层都报 0，预算分毫不动，201E 恢复、factory 超预算
                        // 两条既有契约保持原样；真机上确实做过自愈的那一次才拿回额度。
                        val link = factory.create(target) ?: throw PtpException("USB connection could not be opened")
                        openSilent = link.cameraSilent
                        val recoveryMs = link.openRecoveryMs
                        if (recoveryMs > 0) {
                            val granted = recoveryMs.coerceAtMost(OPEN_RECOVERY_ALLOWANCE_MS)
                            deadline += granted
                            logger.log(
                                "connect",
                                "openRecoveryMs=" + recoveryMs + "; budgetExtendedMs=" + granted,
                            )
                        }
                        // Publish ownership before checking cancellation: close reads these fields on this worker.
                        transport = link
                        checkConnecting(deadline)
                        val truth = when (link) {
                            is PtpTransportAdapter -> link.diagnostics
                            is PtpDiagnostics -> link
                            else -> throw IllegalStateException("Factory transport must expose PtpDiagnostics")
                        }
                        diagnostics = truth
                        val preflight = checkNotNull(truth.deviceCaps) { "open() did not provide device caps" }
                        mutableDeviceCaps.value = preflight
                        logger.deviceCaps(preflight)
                        val commands = PtpSessionApiImpl(link)
                        api = commands
                        var response = commands.openSession(timeoutMs = remaining(deadline, 1_000))
                        checkConnecting(deadline)
                        if (response.code == 0x201E && preflight.preflightFailure == null && !truth.isFatal) {
                            val closeResponse = commands.closeSession(remaining(deadline, 1_000))
                            checkConnecting(deadline)
                            requireOk(closeResponse, "CloseSession")
                            response = commands.openSession(timeoutMs = remaining(deadline, 1_000))
                            checkConnecting(deadline)
                        }
                        requireOk(response, "OpenSession")
                        val warmed = commands.getDeviceInfo(remaining(deadline, 1_000))
                        checkConnecting(deadline)
                        requireOk(warmed.response, "GetDeviceInfo")
                        // Warm info is a separate decision snapshot; it cannot erase the L1 preflight result.
                        val parsed = DeviceInfoParser.parse(warmed.payload).copy(
                            bcdUsb = preflight.bcdUsb, bulkMaxPacketSize = preflight.bulkMaxPacketSize,
                            usbVendorId = preflight.usbVendorId, usbProductId = preflight.usbProductId,
                            preflightFailure = preflight.preflightFailure,
                        )
                        if (parsed.vendorExtensionId != PANASONIC_VENDOR_EXTENSION) return@run parsed
                        val identity = listOf(parsed.usbVendorId, parsed.usbProductId, parsed.model,
                            parsed.serialNumber.ifBlank { target.cacheKey }).joinToString(":")
                        if (cameraIdentity != null && cameraIdentity != identity) eventPump.clearProcessed()
                        cameraIdentity = identity
                        eventPump.resetSession()
                        repeat(3) { drainNow(link, truth, deadline); checkConnecting(deadline) }
                        val storage = commands.getStorageIds(remaining(deadline, 1_000))
                        checkConnecting(deadline)
                        requireOk(storage.response, "GetStorageIDs")
                        mutableStorageIds.value = parseStorageIds(storage.payload)
                        parsed
                    }
                    checkConnecting(deadline)
                    if (opened.vendorExtensionId != PANASONIC_VENDOR_EXTENSION) {
                        closeLocked(CloseReason.RECONNECT, preserveState = true)
                        machine.move(TetherState(TetherPhase.WrongMode, opened.model, "请在相机上选择 PC(Tether)，然后重试"))
                        return@withLock ConnectOutcome.WrongMode
                    }
                    connectedInfo = opened
                    machine.move(TetherState(TetherPhase.Connected, opened.model, "已连接 · " + opened.model, nowMs() - started))
                    logger.log("connect", "Connected connectionMs=" + (nowMs() - started))
                    scheduler.setEventDrain {
                        checkAdmission(activeGeneration)
                        transport?.let { t -> diagnostics?.let { d -> drainNow(t, d) } }
                    }
                    pollJob = scope.launch {
                        while (isActive && state.value.phase == TetherPhase.Connected) {
                            delay(200)
                            try { scheduler.run(PtpScheduler.Priority.PROBE) {
                                checkAdmission(activeGeneration)
                                transport?.let { t -> diagnostics?.let { d -> drainNow(t, d) } }
                            } }
                            catch (cancel: CancellationException) { throw cancel }
                            catch (failure: Throwable) { if (state.value.phase == TetherPhase.Connected) latchFatal(failure, activeGeneration); break }
                        }
                    }
                    // Connected precedes every cache read, probe and asynchronous save.
                    startCapabilityWork(opened, false)
                    ConnectOutcome.Connected
                } catch (cancel: CancellationException) {
                    connectAborted.set(true)
                    cleanupAfterFailure(cancel, CloseReason.USER, false)
                    throw cancel
                } catch (failure: Throwable) {
                    if (diagnostics?.isFatal == true) fatalCause.compareAndSet(null, failure)
                    logger.log(
                        "connect",
                        "failure=" + failure.javaClass.simpleName + " message=" + failure.message +
                            " fatal=" + isFatal + " cameraSilent=" + openSilent,
                    )
                    cleanupAfterFailure(failure, CloseReason.FATAL, true)
                    val hint = if (openSilent) CAMERA_SILENT_HINT else CONNECT_FAILURE_HINT
                    machine.move(TetherState(TetherPhase.Fatal, message = hint + failure.message))
                    ConnectOutcome.Failed(failure)
                }
            }
        } finally { connectJob = null; connecting.set(false) }
    }

    suspend fun <T> runOp(priority: PtpScheduler.Priority = PtpScheduler.Priority.USER, block: suspend (PtpSessionApi) -> T): T =
        runOpInternal(priority, null, block)

    private suspend fun <T> runOpInternal(
        priority: PtpScheduler.Priority, queueBudgetMs: Long?, block: suspend (PtpSessionApi) -> T,
    ): T {
        val requestGeneration = generation.get()
        fatalCause.get()?.let { throw it }
        check(state.value.phase == TetherPhase.Connected) { "Camera session is not connected" }
        val work: suspend () -> T = {
            checkAdmission(requestGeneration)
            fatalCause.get()?.let { throw it }
            check(!isFatal) { "USB transport is fatal; replug required" }
            try {
                val value = block(checkNotNull(api))
                check(!isFatal) { "USB transport became fatal; replug required" }
                value
            } catch (failure: Throwable) {
                // Only this worker observes the real USB failure. A cancelled waiter
                // must never replace it while the physical request is still running.
                if (diagnostics?.isFatal == true) latchFatal(failure, requestGeneration)
                throw failure
            }
        }
        return if (queueBudgetMs == null) scheduler.run(priority, work)
            else scheduler.runWithQueueBudget(priority, queueBudgetMs, work)
    }

    /** Narrow diagnostic access is queued; no raw open/claim handle is exposed. */
    suspend fun <T> runDiagnostic(block: suspend (Transport, PtpDiagnostics) -> T): T {
        val requestGeneration = generation.get()
        fatalCause.get()?.let { throw it }
        check(state.value.phase == TetherPhase.Connected) { "Camera session is not connected" }
        return scheduler.run {
            checkAdmission(requestGeneration)
            try {
                val value = block(checkNotNull(transport), checkNotNull(diagnostics))
                if (diagnostics?.isFatal == true) {
                    latchFatal(fatalCause.get() ?: PtpException("Diagnostic operation observed fatal; replug required"), requestGeneration, retainActiveResult = true)
                }
                value
            } catch (failure: Throwable) {
                if (diagnostics?.isFatal == true) latchFatal(failure, requestGeneration)
                throw failure
            }
        }
    }

    /** Probe budget includes queue waiting. No USB is issued after that budget is exhausted. */
    suspend fun readDiagnostic(op: PtpOp, timeoutMs: Int): Pair<PtpResponse, ByteArray> {
        val deadline = nowMs() + timeoutMs.coerceIn(1, 1_500)
        try {
            return runOpInternal(PtpScheduler.Priority.PROBE, (deadline - nowMs()).coerceAtLeast(0)) {
            require(op.code in PROBE_OPS) { "Side-effecting operation is forbidden in capability probes" }
            if (op.code == PtpConstants.Op.GET_OBJECT_HANDLES) {
                check(it.warmed) { "GetObjectHandles must follow warm-up" }
                require(op.params.size == 3 && op.params[1] == 0L && op.params[0] in 1L..PtpConstants.ALL_STORAGE_IDS)
            }
            val budget = deadline - nowMs()
            if (budget <= 0) throw ProbeBudgetExceededException("probe budget exhausted while queued; no USB issued")
            val output = ByteArrayOutputStream()
            val response = checkNotNull(transport).transaction(op, expectedData = true, dataSink = { bytes, count ->
                if (output.size().toLong() + count > PtpSessionApiImpl.MAX_DIAGNOSTIC_BYTES) {
                    throw PtpProtocolException("Probe payload exceeds bounded buffer")
                }
                output.write(bytes, 0, count)
            }, timeoutMs = budget.coerceAtMost(1_500).toInt())
            response to output.toByteArray()
            }
        } catch (_: PtpScheduler.QueueBudgetExceededException) {
            throw ProbeBudgetExceededException("probe budget exhausted while queued; no USB issued")
        }
    }

    suspend fun drainDiagnosticEvents(rounds: Int = 3) {
        runOp {
            repeat(rounds.coerceIn(1, 3)) { drainNow(checkNotNull(transport), checkNotNull(diagnostics)) }
        }
    }

    suspend fun reprobe() {
        probeJob?.cancelAndJoin()
        connectedInfo?.takeIf { state.value.phase == TetherPhase.Connected }?.let { startCapabilityWork(it, true) }
    }
    private fun startCapabilityWork(caps: PtpDeviceCaps, force: Boolean) {
        val probeGeneration = generation.get()
        probeJob = scope.launch {
            try { capabilityWork(this@CameraSession, caps, force) }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Throwable) {
                logger.log("probe", "failure=" + failure.javaClass.simpleName + " fatal=" + isFatal)
                if (isFatal) latchFatal(failure, probeGeneration)
            }
        }
    }
    private fun latchFatal(failure: Throwable, failedGeneration: Long, retainActiveResult: Boolean = false) {
        synchronized(admissionLock) {
            if (generation.get() != failedGeneration || closing.get() || !fatalCause.compareAndSet(null, failure)) return
            // Failure continuations may resume inline; publish Fatal before notifying them.
            if (state.value.phase != TetherPhase.Fatal) machine.move(TetherState(TetherPhase.Fatal, message = "USB 已中断，请把相机电源关掉再打开（或取出电池重装）后重新连接"))
            scheduler.abortAll(failure, retainActiveResult)
        }
        scope.launch {
            lifecycle.withLock {
                if (generation.get() == failedGeneration && fatalCause.get() === failure) {
                    closeLocked(CloseReason.FATAL, preserveState = true)
                }
            }
        }
    }
    suspend fun close(reason: CloseReason = CloseReason.USER): Unit = withContext(NonCancellable) {
        connectAborted.set(true)
        synchronized(admissionLock) { closing.set(true) }
        eventDelivery?.cancel()
        val caller = currentCoroutineContext()[Job]
        connectJob?.takeIf { it !== caller }?.cancel(CancellationException("Camera connection closed"))
        probeJob?.cancel(); probeJob = null
        pollJob?.cancel(); pollJob = null
        lifecycle.withLock { closeLocked(reason, preserveState = false) }
    }
    private suspend fun cleanupAfterFailure(primary: Throwable, reason: CloseReason, preserveState: Boolean) {
        withContext(NonCancellable) {
            try { closeLocked(reason, preserveState) }
            catch (cleanup: Throwable) { if (cleanup !== primary) primary.addSuppressed(cleanup) }
        }
    }
    private suspend fun closeLocked(reason: CloseReason, preserveState: Boolean): Unit = withContext(NonCancellable) {
        val started = nowMs()
        synchronized(admissionLock) { closing.set(true) }
        eventDelivery?.cancelAndJoin()
        eventDelivery = null
        probeJob?.cancel(); probeJob = null
        pollJob?.cancel(); pollJob = null
        scheduler.setEventDrain(null)
        var released = transport == null
        try {
            scheduler.close {
                // Read ownership after in-flight open/USB returns, not before waiting for it.
                val link = transport
                val truth = diagnostics
                val commands = api
                try {
                    if (link != null && truth?.isOpen == true && !truth.isFatal && commands != null) {
                        repeat(3) {
                            val batch = link.drainEvents(2) + truth.collectPendingEvents()
                            logger.log("EVENT", "closeDrain count=" + batch.size + "; consumerDelivery=false; discarded=" + truth.discardedEventContainers)
                        }
                        try { commands.closeSession((5_000L - (nowMs() - started)).coerceIn(1, 1_000).toInt()) }
                        catch (failure: Throwable) { logger.log("close", "CloseSession=" + failure.message) }
                    }
                } finally {
                    deferredEvents.clear()
                    try { link?.close(1_000) }
                    finally { released = link == null || truth?.isOpen == false }
                }
            }
        } finally {
            transport = null; diagnostics = null; api = null; connectedInfo = null
            logger.log("close", "reason=$reason elapsedMs=" + (nowMs() - started) + " handlesReleased=" + released)
            if (!preserveState) {
                synchronized(admissionLock) { fatalCause.set(null) }
                val next = if (reason == CloseReason.DETACHED) TetherState(TetherPhase.Detached, message = "USB 已断开，可重新连接") else TetherState()
                machine.move(next, lifecycleReset = true)
            }
        }
    }
    private suspend fun drainNow(link: Transport, truth: PtpDiagnostics, flowDeadline: Long? = null) {
        // Keep at most one decoded batch awaiting consumers. While it is blocked,
        // leave further interrupt bytes in the transport/device for a later round.
        // User operations and their USB ownership never wait on a Flow collector.
        if (flowDeadline == null && eventDelivery?.isCompleted == false) {
            val pending = truth.collectPendingEvents()
            if (deferredEvents.size + pending.size > EVENT_BACKLOG_LIMIT) {
                val failure = PtpException("Event delivery backlog exceeds bounded buffer")
                logger.log("EVENT", "backlogOverflow=true; waiting=" + deferredEvents.size + "; pending=" + pending.size + "; consumerDeliveryIncomplete=true")
                latchFatal(failure, generation.get())
                throw failure
            }
            deferredEvents.addAll(pending)
            logger.log("EVENT", "drainDeferred=true; backlog=" + deferredEvents.size + "; deliveryBackpressure=true; consumerDeliveryIncomplete=true")
            return
        }
        val batch = deferredEvents.toList() + link.drainEvents(2) + truth.collectPendingEvents()
        deferredEvents.clear()
        val discarded = truth.discardedEventContainers
        if (flowDeadline == null) {
            if (batch.isEmpty()) {
                eventPump.accept(emptyList(), discarded)
                return
            }
            val delivery = scope.async(eventDispatcher, start = CoroutineStart.LAZY) {
                eventPump.accept(batch, discarded)
            }
            eventDelivery = delivery
            delivery.invokeOnCompletion { failure ->
                if (failure != null) logger.log("EVENT", "deliveryStopped count=" + batch.size + "; consumerDeliveryIncomplete=true; cause=" + failure.javaClass.simpleName)
            }
            if (closing.get()) delivery.cancel() else delivery.start()
            return
        }
        // Mandatory handshake delivery alone shares the fixed connection deadline.
        val delivery = CoroutineScope(currentCoroutineContext()).async(start = CoroutineStart.LAZY) {
            eventPump.accept(batch, discarded)
        }
        eventDelivery = delivery
        try {
            if (closing.get()) delivery.cancel() else delivery.start()
            val budget = flowDeadline - nowMs()
            if (budget <= 0) throw IllegalStateException("Connection exceeded the fixed 5 second budget")
            withTimeout(budget) { delivery.await() }
        } catch (_: TimeoutCancellationException) {
            delivery.cancel()
            logger.log("EVENT", "deliveryDeadline count=" + batch.size + "; consumerDeliveryIncomplete=true")
            throw IllegalStateException("Connection exceeded the fixed 5 second budget")
        } catch (cancelled: CancellationException) {
            logger.log("EVENT", "deliveryCancelled count=" + batch.size + "; closing=" + closing.get() + "; consumerDeliveryIncomplete=true")
            throw cancelled
        } finally {
            if (!delivery.isCompleted) delivery.cancel()
            if (eventDelivery === delivery) eventDelivery = null
        }
    }
    private fun checkAdmission(expectedGeneration: Long) {
        synchronized(admissionLock) {
            check(generation.get() == expectedGeneration && !closing.get()) {
                "Camera session request is no longer admitted"
            }
        }
    }

    private fun checkConnecting(deadline: Long) {
        if (connectAborted.get() || connectJob?.isActive == false) throw CancellationException("Camera connection closed")
        check(nowMs() <= deadline) { "Connection exceeded the fixed 5 second budget" }
    }
    private fun remaining(deadline: Long, maxMs: Int): Int {
        checkConnecting(deadline)
        val value = deadline - nowMs()
        check(value > 0) { "Connection exceeded the fixed 5 second budget" }
        return value.coerceAtMost(maxMs.toLong()).toInt()
    }
    private fun requireOk(response: PtpResponse, operation: String) {
        if (response.code != PtpConstants.Rc.OK) throw PtpException(operation + " rc=0x" + response.code.toString(16))
    }
    private fun parseStorageIds(payload: ByteArray): List<Long> {
        if (payload.size < 4) return emptyList()
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val count = buffer.int.toLong() and 0xFFFF_FFFFL
        if (count > (payload.size - 4) / 4 || count > 65_536) return emptyList()
        return List(count.toInt()) { buffer.int.toLong() and 0xFFFF_FFFFL }.filter { it in 1L until PtpConstants.ALL_STORAGE_IDS }
    }
    companion object {
        const val CONNECT_BUDGET_MS = 5_000L

        /**
         * 「`transport.open()` 花掉的时间」最多能从连接预算里补回多少毫秒。
         *
         * 取值依据 = `PtpUsbTransport.resyncChannel` 自己声明的上界（排空 128×150 ms 但总预算
         * 1.5 s、控制传输 6×150 ms、DeviceReset ≤2×150 ms、响应 2×150 ms，实测复原在 0.5 s 量级）。
         * 超过这个额度的耗时说明 open() 本身在异常状态里打转，仍按 5 s 预算判死。
         */
        const val OPEN_RECOVERY_ALLOWANCE_MS = 2_100L

        /**
         * 真机七轮日志（2026-11-27/28）结论：相机的 USB 连接状态只有在**断电**时才复位 ——
         * 拔数据线（相机仍由电池供电）不一定够；完全沉默时更是必须关电源再打开。
         * 所以提示先让用户关电源，再提换线/换口。
         */
        const val CONNECT_FAILURE_HINT =
            "连接失败。请先把相机电源关掉再打开（或取出电池重装），等待 2 秒后重新连接——" +
                "只拔数据线往往清不掉相机内部的连接状态。若仍失败，请确认相机处于 PC(Tether) 模式："

        /**
         * 相机对每条命令**一个字节都没有回**时的处置建议（传输层 `cameraSilent`）。
         *
         * 真机 `logs_1795824713270`（2026-11-28 08:10:54–08:11:49）6 次尝试全部零回包，而
         * `CLEAR_FEATURE(ENDPOINT_HALT)`、`SET_INTERFACE`、类请求 `DeviceReset(0x66)`、
         * PTP `DeviceReset(0x1003)` 全部“成功”；拔插数据线之后依旧零回包。这种状态只能靠
         * 关相机电源再打开清除，所以文案必须与 `CONNECT_FAILURE_HINT` 分开，避免用户白拔线。
         */
        const val CAMERA_SILENT_HINT =
            "相机没有响应：每条命令都没有任何回包（连设备信息都读不到），这通常是相机内部的" +
                "连接状态卡住了。请把相机电源关掉再打开（或取出电池重装），然后再连接——" +
                "只拔数据线不会清除这个状态。若仍无响应，请换一根数据线或换一个 USB 口，并确认相机在 PC(Tether) 模式："

        private const val EVENT_BACKLOG_LIMIT = 256
        private const val PANASONIC_VENDOR_EXTENSION = 0x1CL
        private val PROBE_OPS = setOf(0x9414, 0x9402, 0x9108, 0x1007)
    }
}
