package cn.alittlecookie.lutista.tether.session

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
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
    private val connectionLease: TetherConnectionLease = TetherConnectionLease.global,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val driverLock = Mutex()
    private val connectionLifecycle = Mutex()
    private val connecting = AtomicBoolean(false)
    private val disposed = AtomicBoolean(false)
    private var autoConnectRequested = false
    private var activeTarget: UsbTarget? = null
    private var active: PtpUsbDriver? = null
    private val mutableDriver = MutableStateFlow<PtpUsbDriver?>(null)
    /** A process-owned repository observes this; UI never creates a second USB session. */
    val driver = mutableDriver.asStateFlow()
    private var objectEventConsumer: (suspend (PtpUsbDriver, EventPump.ObjectAdded) -> Unit)? = null
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
    private val mutableConnectionNotice = MutableStateFlow<TetherConnectionNotice?>(null)
    val connectionNotice = mutableConnectionNotice.asStateFlow()

    fun dismissConnectionNotice() { mutableConnectionNotice.value = null }
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
                    is UsbHub.Event.Attached -> if (autoConnectRequested && objectEventConsumer == null) launch { connect() }
                    is UsbHub.Event.PermissionResult -> logger.log("permission", "granted=" + event.granted)
                }
            }
        }
    }

    /** Register before connect so OpenSession-drained events cannot be mistaken for diagnostics. */
    fun setObjectEventConsumer(consumer: suspend (PtpUsbDriver, EventPump.ObjectAdded) -> Unit) {
        check(!disposed.get()) { "Tether runtime is disposed" }
        objectEventConsumer = consumer
        autoConnectRequested = false
    }

    suspend fun connect(): ConnectOutcome {
        check(!disposed.get()) { "Tether runtime is disposed" }
        if (!connecting.compareAndSet(false, true)) return ConnectOutcome.AlreadyConnecting
        try {
            return connectionLifecycle.withLock { connectExclusively() }
        } finally { connecting.set(false) }
    }

    private suspend fun connectExclusively(): ConnectOutcome {
        autoConnectRequested = objectEventConsumer == null
        var acquiredLease = false
        var keepLease = false
        try {
            acquiredLease = connectionLease.tryAcquire(this)
            check(acquiredLease) { "另一个联机或诊断会话正在使用 USB，请先关闭该会话" }
            diagnostics.syncConfig()
            mutableConnectionNotice.value = null
            val devices = hub.enumerate()
            val device = devices.firstOrNull { !it.isVerifiedLumixMtpMode() && it.hasTetherBulkInterface() }
            if (device == null) {
                val notice = if (devices.any { it.isVerifiedLumixMtpMode() })
                    TetherConnectionNotice.MtpUsbMode else TetherConnectionNotice.NoUsableUsb
                return rejectConnection(notice, describeDevices(devices))
            }
            val target = UsbTarget(device, hub.hasPermission(device))
            val driver = driverLock.withLock {
                if (active == null || activeTarget?.cacheKey != target.cacheKey) {
                    active?.disconnect(CloseReason.RECONNECT)
                    bindings.forEach { it.cancel() }; bindings.clear()
                    active = drivers.create(target, scope) as? PtpUsbDriver
                    mutableDriver.value = active
                    activeTarget = target
                    active?.let { bind(it) }
                }
                active
            } ?: return rejectConnection(TetherConnectionNotice.NoUsableUsb)
            // USB addresses may be reused after detach, while Android revokes permission.
            // Keep this driver's history but pass the freshly enumerated permission snapshot.
            return driver.connect(target).also { result ->
                keepLease = result == ConnectOutcome.Connected
                if (result == ConnectOutcome.WrongMode) {
                    mutableConnectionNotice.value = TetherConnectionNotice.WrongPtpMode
                    mutableMessage.value = TetherConnectionNotice.WrongPtpMode.message
                } else { mutableMessage.value = result.toString() }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            mutableMessage.value = failure.javaClass.simpleName + ": " + failure.message
            logger.log("runtime", "connectFailure=" + mutableMessage.value)
            return ConnectOutcome.Failed(failure)
        } finally {
            if (acquiredLease && !keepLease) withContext(NonCancellable) {
                try {
                    driverLock.withLock {
                        if (active?.session?.transportOpen == true) active?.disconnect(CloseReason.FATAL)
                    }
                } finally { connectionLease.release(this@TetherRuntime) }
            }
        }
    }

    /**
     * 把枚举结果压成一行描述符摘要（VID/PID/类/接口/端点）。
     *
     * 真机反馈里只看到 `source=usb_descriptor_no_usable_target` 时无法判断到底是「相机没枚举到」、
     * 「相机回到 MTP 模式（PID 0x2372）」还是「接口/端点没对上」—— 这三种的处置完全不同，
     * 所以把描述符原样记进日志。没有设备时记 `none`。
     *
     * 诊断永远不能影响连接：描述符读取本身可能抛异常（外设异常、测试替身不完整），
     * 因此每一层都 runCatching，失败时退化成「能读到什么就记什么」。
     */
    private fun describeDevices(devices: List<UsbDevice>): String {
        if (devices.isEmpty()) return "none"
        return devices.joinToString(" | ") { device -> describeDevice(device) }
    }

    private fun describeDevice(device: UsbDevice): String {
        val head = "vid=" + hex(device.vendorId, 4) + " pid=" + hex(device.productId, 4) +
            " cls=" + runCatching { device.deviceClass }.getOrDefault(-1) +
            " mtp=" + runCatching { device.isVerifiedLumixMtpMode() }.getOrDefault(false) +
            " tether=" + runCatching { device.hasTetherBulkInterface() }.getOrDefault(false)
        // 只读 interface 0：PTP 传输层只支持接口 0，碰其它接口既无意义也可能扰乱设备状态
        // （TetherRuntimeTest 有「不得读取 interface 1」的契约用例）。
        val interfaces = runCatching {
            val count = device.interfaceCount
            "count=" + count + (if (count > 0) " if0=" + describeInterface(device, 0) else "")
        }.getOrElse { failure -> "unavailable(" + failure.javaClass.simpleName + ")" }
        return head + " intf[" + interfaces + "]"
    }

    private fun describeInterface(device: UsbDevice, index: Int): String = runCatching {
        val intf = device.getInterface(index)
        val endpoints = (0 until intf.endpointCount).joinToString("+") { ep -> describeEndpoint(intf, ep) }
        "if$index(" + intf.interfaceClass + "/" + intf.interfaceSubclass + "/" + intf.interfaceProtocol +
            ":" + endpoints + ")"
    }.getOrElse { failure -> "if$index(unavailable:" + failure.javaClass.simpleName + ")" }

    private fun describeEndpoint(intf: UsbInterface, index: Int): String = runCatching {
        val endpoint = intf.getEndpoint(index)
        hex(endpoint.address, 2) +
            (if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) "b" else "i") +
            (if (endpoint.direction == UsbConstants.USB_DIR_IN) "in" else "out") +
            "@" + endpoint.maxPacketSize
    }.getOrElse { failure -> "#" + index + "(unavailable:" + failure.javaClass.simpleName + ")" }

    private fun hex(value: Int, width: Int): String = "0x" + value.toString(16).padStart(width, '0')

    private suspend fun rejectConnection(notice: TetherConnectionNotice, descriptors: String = ""): ConnectOutcome {
        // A prior connection may still be winding down after a USB broadcast. Always release it.
        driverLock.withLock { active?.disconnect(CloseReason.RECONNECT) }
        mutableConnectionNotice.value = notice
        mutableState.value = TetherState(TetherPhase.NotAttached, message = notice.message)
        mutableMessage.value = notice.message
        logger.log(
            "connect",
            "blockedBeforeOpen=true; notice=" + notice.name + "; source=" + notice.source +
                "; devices=" + descriptors,
        )
        return ConnectOutcome.NotAttached
    }

    private fun bind(driver: PtpUsbDriver) {
        bindings += scope.launch(start = CoroutineStart.UNDISPATCHED) { driver.session.state.collect { mutableState.value = it } }
        bindings += scope.launch(start = CoroutineStart.UNDISPATCHED) { driver.capabilities.collect { mutableCaps.value = it } }
        bindings += scope.launch(start = CoroutineStart.UNDISPATCHED) { driver.session.deviceCaps.collect { mutableDeviceCaps.value = it } }
        bindings += scope.launch {
            while (isActive && active === driver) {
                mutableTransportFatal.value = driver.session.transportFatal
                mutableTransportOpen.value = driver.session.transportOpen
                if (!connecting.get() && driver.session.transportOpen == false &&
                    driver.session.state.value.phase in setOf(TetherPhase.Fatal, TetherPhase.Detached, TetherPhase.Idle)) {
                    connectionLease.release(this@TetherRuntime)
                }
                delay(100)
            }
        }
        bindings += scope.launch(start = CoroutineStart.UNDISPATCHED) { driver.session.eventPump.stats.collect { mutableStats.value = it } }
        bindings += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            driver.session.events.collect { added ->
                val consumer = objectEventConsumer
                if (consumer != null) {
                    // The formal consumer acknowledges only after durable registration/handling.
                    consumer(driver, added)
                } else {
                    logger.log("EVENT", "consumer=diagnostic_observer; handle=" + added.handle)
                    driver.session.eventPump.markProcessed(added)
                }
            }
        }
    }

    suspend fun disconnect(reason: CloseReason = CloseReason.USER) {
        closeConnection(reason)
        mutableMessage.value = "USB 会话已关闭，原因：" + reason
    }

    private suspend fun closeConnection(reason: CloseReason) = withContext(NonCancellable) {
        connectionLifecycle.withLock {
            if (reason != CloseReason.DETACHED || objectEventConsumer != null) autoConnectRequested = false
            try { driverLock.withLock { active?.disconnect(reason) } }
            finally { connectionLease.release(this@TetherRuntime) }
        }
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
                    connectionLifecycle.withLock {
                        check(connectionLease.tryAcquire(this@TetherRuntime)) {
                            "另一个联机或诊断会话正在使用 USB，请先关闭该会话"
                        }
                        val worker = PtpScheduler()
                        try {
                            worker.start()
                            worker.run {
                                val connection = hub.openConnection(device) ?: return@run TetherDiagnostics.FaultResult(
                                    "claim_fail", TetherDiagnostics.FaultStatus.Blocked, "permission/device unavailable; no claim attempted")
                                try { diagnostics.runClaimFailureFixture(device, connection, guard) }
                                finally { connection.close() }
                            }
                        } finally {
                            try { worker.close() } finally { connectionLease.release(this@TetherRuntime) }
                        }
                    }
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
