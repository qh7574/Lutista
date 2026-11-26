package cn.alittlecookie.lutista.tether.session

import cn.alittlecookie.lutista.BuildConfig
import cn.alittlecookie.lutista.tether.debug.CapabilityOverride
import cn.alittlecookie.lutista.tether.debug.TetherDebugConfig
import cn.alittlecookie.lutista.tether.debug.TetherDiagLogger
import cn.alittlecookie.lutista.tether.debug.TetherDiagnostics
import cn.alittlecookie.lutista.tether.driver.*
import cn.alittlecookie.lutista.tether.profile.CameraCapabilities
import cn.alittlecookie.lutista.tether.protocol.PtpConstants
import cn.alittlecookie.lutista.tether.transport.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Activity/ViewModel lifetime. Disposing releases USB before stopping its coroutine scope. */
class TetherRuntime(
    val hub: UsbHub,
    private val drivers: CameraDriverFactory,
    val config: TetherDebugConfig,
    val logger: TetherDiagLogger,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val driverLock = Mutex()
    private val connecting = AtomicBoolean(false)
    private val disposed = AtomicBoolean(false)
    private var autoConnectRequested = false
    private var activeTarget: UsbTarget? = null
    private var active: PtpUsbDriver? = null
    private val bindings = mutableListOf<Job>()
    private val mutableState = MutableStateFlow(TetherState())
    val state = mutableState.asStateFlow()
    private val mutableCaps = MutableStateFlow(CameraCapabilities.empty())
    val caps = mutableCaps.asStateFlow()
    private val mutableDeviceCaps = MutableStateFlow<PtpDeviceCaps?>(null)
    val deviceCaps = mutableDeviceCaps.asStateFlow()
    private val mutableTransportFatal = MutableStateFlow<Boolean?>(null)
    val transportFatal = mutableTransportFatal.asStateFlow()
    private val mutableTransportOpen = MutableStateFlow<Boolean?>(null)
    val transportOpen = mutableTransportOpen.asStateFlow()
    private val mutableStats = MutableStateFlow(EventPump.Stats())
    val stats = mutableStats.asStateFlow()
    private val mutableMessage = MutableStateFlow("")
    val message = mutableMessage.asStateFlow()
    private val mutableLogs = MutableStateFlow<List<String>>(emptyList())
    val logLines = mutableLogs.asStateFlow()
    val configState = config.state.stateIn(scope, SharingStarted.Eagerly, TetherDebugConfig.Snapshot())
    val violationCount: Int get() = UsbIoThread.violationCount()
    private val diagnostics = TetherDiagnostics(config, logger)

    init {
        scope.launch { config.state.collect { logger.enabled = it.enabled } }
        scope.launch {
            while (isActive) { mutableLogs.value = logger.snapshot(); delay(250) }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            hub.events.collect { event ->
                when (event) {
                    is UsbHub.Event.Detached -> if (activeTarget?.device?.deviceName == event.device.deviceName) {
                        disconnect(CloseReason.DETACHED)
                    }
                    is UsbHub.Event.Attached -> if (autoConnectRequested) launch { connect() }
                    is UsbHub.Event.PermissionResult -> logger.log("permission", "granted=" + event.granted)
                }
            }
        }
    }

    suspend fun connect(): ConnectOutcome {
        check(!disposed.get()) { "Tether runtime is disposed" }
        if (!connecting.compareAndSet(false, true)) return ConnectOutcome.AlreadyConnecting
        autoConnectRequested = true
        try {
            diagnostics.syncConfig()
            val device = hub.enumerate().firstOrNull()
            if (device == null) {
                mutableState.value = TetherState(TetherPhase.NotAttached, message = "未检测到相机，请连接并选择 PC(Tether)")
                mutableMessage.value = mutableState.value.message
                return ConnectOutcome.NotAttached
            }
            val target = UsbTarget(device, hub.hasPermission(device))
            val driver = driverLock.withLock {
                if (active == null || activeTarget?.cacheKey != target.cacheKey) {
                    active?.disconnect(CloseReason.RECONNECT)
                    bindings.forEach { it.cancel() }; bindings.clear()
                    active = drivers.create(target, scope) as? PtpUsbDriver
                    activeTarget = target
                    active?.let { bind(it) }
                }
                active
            } ?: return ConnectOutcome.NotAttached
            // USB addresses may be reused after detach, while Android revokes permission.
            // Keep this driver's history but pass the freshly enumerated permission snapshot.
            return driver.connect(target).also { mutableMessage.value = it.toString() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            mutableMessage.value = failure.javaClass.simpleName + ": " + failure.message
            logger.log("runtime", "connectFailure=" + mutableMessage.value)
            return ConnectOutcome.Failed(failure)
        } finally { connecting.set(false) }
    }

    private fun bind(driver: PtpUsbDriver) {
        bindings += scope.launch(start = CoroutineStart.UNDISPATCHED) { driver.session.state.collect { mutableState.value = it } }
        bindings += scope.launch(start = CoroutineStart.UNDISPATCHED) { driver.capabilities.collect { mutableCaps.value = it } }
        bindings += scope.launch(start = CoroutineStart.UNDISPATCHED) { driver.session.deviceCaps.collect { mutableDeviceCaps.value = it } }
        bindings += scope.launch {
            while (isActive && active === driver) {
                mutableTransportFatal.value = driver.session.transportFatal
                mutableTransportOpen.value = driver.session.transportOpen
                delay(100)
            }
        }
        bindings += scope.launch(start = CoroutineStart.UNDISPATCHED) { driver.session.eventPump.stats.collect { mutableStats.value = it } }
        bindings += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            driver.session.events.collect { added ->
                logger.log("EVENT", "consumer=diagnostic_observer; handle=" + added.handle)
                driver.session.eventPump.markProcessed(added)
            }
        }
    }

    suspend fun disconnect(reason: CloseReason = CloseReason.USER) {
        closeConnection(reason)
        mutableMessage.value = "USB 会话已关闭，原因：" + reason
    }

    private suspend fun closeConnection(reason: CloseReason) {
        if (reason != CloseReason.DETACHED) autoConnectRequested = false
        driverLock.withLock { active?.disconnect(reason) }
    }

    suspend fun reprobe() {
        active?.ensureCapabilities(force = true)
        mutableMessage.value = "已请求重新探测；真实结果与耗时由能力面板更新"
    }

    /** Explicit read-only evidence sequence. It closes the session in every outcome. */
    suspend fun runEvidence() {
        if (!BuildConfig.DEBUG || !diagnostics.syncConfig().enabled) { mutableMessage.value = "诊断日志未启用"; return }
        if (state.value.phase != TetherPhase.Connected && connect() != ConnectOutcome.Connected) return
        val session = active?.session ?: return
        try {
            session.runOp { api ->
                checked(api.getDeviceInfo(1_000).response, "GetDeviceInfo")
                checked(api.getStorageIds(1_000).response, "GetStorageIDs")
                if (config.snapshot().capabilityOverride != CapabilityOverride.UnknownPtp) {
                    api.liveViewFormats(300)
                    api.getProperty(PtpConstants.Prop.MODE_POS, 200)
                    api.listPropertyDescriptor(PtpConstants.Prop.ISO_CONTAINER, 300)
                }
            }
            session.drainDiagnosticEvents(3)
            mutableMessage.value = "只读序列已执行；请按实际日志核验，尚未签收真机门禁"
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Throwable) { mutableMessage.value = failure.javaClass.simpleName + ": " + failure.message }
        // Closing USB must not replace the evidence result with the manual-close message.
        finally { withContext(NonCancellable) { closeConnection(CloseReason.USER) } }
    }

    suspend fun runFault(name: String) {
        if (!BuildConfig.DEBUG) return
        try {
            val config = diagnostics.syncConfig()
            if (!config.enabled) { mutableMessage.value = "Disabled：诊断未启用"; return }
            val result = when (name) {
                "smallRx" -> diagnostics.smallRxStatus()
                "concurrent" -> checkNotNull(active).session.runDiagnostic { t, d -> diagnostics.runConcurrentFixture(t, d) }
                "timeout" -> checkNotNull(active).session.runDiagnostic { t, d -> diagnostics.forceTimeout(t, d) }
                "claim" -> {
                    if (!config.faultClaimFail) { mutableMessage.value = "Disabled：claim 实验开关未启用"; return }
                    val device = activeTarget?.device ?: hub.enumerate().firstOrNull()
                    if (device == null) { mutableMessage.value = "Blocked：没有相机"; return }
                    disconnect(CloseReason.USER)
                    val closedSession = active?.session
                    val guard = object : PtpDiagnostics {
                        override val isOpen: Boolean get() = closedSession?.isOpen == true
                        override val isFatal: Boolean get() = closedSession?.isFatal == true
                        override val deviceCaps: PtpDeviceCaps? get() = closedSession?.deviceCaps?.value
                        override val discardedEventContainers: Int get() = closedSession?.discardedEventContainers ?: 0
                        override fun collectPendingEvents(): List<PtpEvent> = emptyList()
                    }
                    val worker = PtpScheduler()
                    worker.start()
                    try {
                        worker.run {
                            val connection = hub.openConnection(device) ?: return@run TetherDiagnostics.FaultResult(
                                "claim_fail", TetherDiagnostics.FaultStatus.Blocked, "permission/device unavailable; no claim attempted")
                            try { diagnostics.runClaimFailureFixture(device, connection, guard) }
                            finally { connection.close() }
                        }
                    } finally { worker.close() }
                }
                else -> throw IllegalArgumentException("Unknown diagnostic action")
            }
            mutableMessage.value = result.status.name + ": " + result.detail +
                "; exception=" + result.exceptionType + "; message=" + result.exceptionMessage
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Throwable) { mutableMessage.value = "Blocked：" + failure.javaClass.simpleName + ": " + failure.message }
    }

    /** Only a manual, separately enabled debug action may invoke this same-value DATA OUT check. */
    suspend fun writeBackSameValue() {
        val settings = diagnostics.syncConfig()
        if (!BuildConfig.DEBUG || !settings.enabled || !settings.allowValueWrite ||
            settings.capabilityOverride == CapabilityOverride.UnknownPtp) {
            mutableMessage.value = "Disabled：需单独启用同值写开关并使用已识别的 PTP profile"
            return
        }
        val session = active?.session ?: return
        try {
            session.runOp { api ->
                val property = PtpConstants.Prop.ISO_VALUE
                val original = api.getProperty(property, 500)
                checked(original.response, "read original")
                check(original.payload.size == 12) { "Expected the verified 12-byte ISO value payload" }
                val data = ByteBuffer.wrap(original.payload).order(ByteOrder.LITTLE_ENDIAN)
                check((data.int.toLong() and 0xFFFF_FFFFL) == property && data.int == 4) { "Unexpected ISO property framing" }
                val value = data.int.toLong() and 0xFFFF_FFFFL
                val latest = config.snapshot()
                check(latest.enabled && latest.allowValueWrite && latest.capabilityOverride != CapabilityOverride.UnknownPtp) { "Write authorization switch was disabled" }
                checked(api.setProperty(property, original.payload.copyOf(), 500), "same value write")
                val readback = api.getProperty(property, 500)
                checked(readback.response, "readback")
                check(original.payload.contentEquals(readback.payload)) { "Same-value readback differs" }
                logger.log("VERIFY_WRITE", "original=" + value + "; written=" + value + "; readback=" + value + "; source=real_transactions")
                mutableMessage.value = "同值写回读一致：" + value + "；真机门禁仍需核对完整 L2 日志"
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Throwable) { mutableMessage.value = failure.javaClass.simpleName + ": " + failure.message }
    }

    fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        autoConnectRequested = false
        scope.launch {
            try { disconnect(CloseReason.ACTIVITY_DESTROYED) }
            finally { try { hub.close() } finally { scope.cancel() } }
        }
    }

    private fun checked(response: PtpResponse, operation: String) {
        check(response.code == PtpConstants.Rc.OK) { operation + " rc=0x" + response.code.toString(16) }
    }
}
