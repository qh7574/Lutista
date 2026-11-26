package cn.alittlecookie.lutista.tether.driver

import android.hardware.usb.UsbDevice
import cn.alittlecookie.lutista.tether.Fixtures
import cn.alittlecookie.lutista.tether.debug.TetherDebugConfig
import cn.alittlecookie.lutista.tether.debug.TetherDiagLogger
import cn.alittlecookie.lutista.tether.profile.CameraCapabilities
import cn.alittlecookie.lutista.tether.profile.CameraProfileRegistry
import cn.alittlecookie.lutista.tether.protocol.DeviceInfoParser
import cn.alittlecookie.lutista.tether.protocol.PtpConstants
import cn.alittlecookie.lutista.tether.protocol.PtpConstants.Op
import cn.alittlecookie.lutista.tether.protocol.PtpConstants.PanasonicOp
import cn.alittlecookie.lutista.tether.protocol.PtpConstants.Prop
import cn.alittlecookie.lutista.tether.protocol.PtpConstants.Rc
import cn.alittlecookie.lutista.tether.session.CameraSession
import cn.alittlecookie.lutista.tether.session.PtpScheduler
import cn.alittlecookie.lutista.tether.store.CapabilityCache
import cn.alittlecookie.lutista.tether.store.CapabilityCacheCodec
import cn.alittlecookie.lutista.tether.store.CapabilityCacheDocument
import cn.alittlecookie.lutista.tether.store.CapabilityCacheEntry
import cn.alittlecookie.lutista.tether.transport.PtpDeviceCaps
import cn.alittlecookie.lutista.tether.transport.PtpDiagnostics
import cn.alittlecookie.lutista.tether.transport.PtpEvent
import cn.alittlecookie.lutista.tether.transport.PtpOp
import cn.alittlecookie.lutista.tether.transport.PtpResponse
import cn.alittlecookie.lutista.tether.transport.Transport
import cn.alittlecookie.lutista.tether.transport.TransportFactory
import cn.alittlecookie.lutista.tether.transport.UsbIoThread
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

internal fun driverTarget(permissionGranted: Boolean = true): UsbTarget {
    val device = mockk<UsbDevice>()
    every { device.deviceName } returns "/scripted/driver"
    every { device.vendorId } returns 0x1234
    every { device.productId } returns 0x5678
    return UsbTarget(device, permissionGranted)
}

/** A codec seam keeps these integration tests independent of Android's stub org.json. */
internal class DriverCache {
    private val documents = mutableMapOf<String, CapabilityCacheDocument>()
    private val nextToken = AtomicInteger()
    val codec = object : CapabilityCacheCodec {
        override fun decode(json: String): CapabilityCacheDocument = synchronized(documents) {
            checkNotNull(documents[json])
        }
        override fun encode(document: CapabilityCacheDocument): String = synchronized(documents) {
            val token = "driver-document-" + nextToken.incrementAndGet()
            documents[token] = document
            token
        }
    }
    @Volatile var raw = ""
    val reads = AtomicInteger()
    val writes = CopyOnWriteArrayList<String>()
    val saved = CompletableDeferred<Unit>()
    var beforeRead: suspend () -> Unit = {}
    var beforeWrite: suspend (String) -> Unit = {}
    val cache = CapabilityCache(
        read = { reads.incrementAndGet(); beforeRead(); raw },
        write = { value ->
            beforeWrite(value)
            raw = value
            writes += value
            if (value.isNotBlank()) saved.complete(Unit)
        },
        codec = codec,
    )
    fun seed(entry: CapabilityCacheEntry) {
        raw = codec.encode(CapabilityCacheDocument(CapabilityCache.PROBE_SCHEMA, listOf(entry)))
    }
}

/** Implements only the wire-facing contract; all lifecycle/probe scheduling is real. */
internal class DriverTransport : Transport, PtpDiagnostics {
    val deviceInfo = Fixtures.hex("device_info")
    val warmCaps = DeviceInfoParser.parse(deviceInfo).copy(
        bcdUsb = 0x0210, bulkMaxPacketSize = 512, usbVendorId = 0x04DA, usbProductId = 0x2382,
    )
    override val deviceCaps: PtpDeviceCaps = warmCaps.copy(
        model = "stale-preflight-model", serialNumber = "stale-preflight-serial", deviceVersion = "stale-preflight-firmware",
    )
    @Volatile override var isOpen = true
    @Volatile override var isFatal = false
    override val discardedEventContainers: Int = 0
    val ops = CopyOnWriteArrayList<PtpOp>()
    val threads = CopyOnWriteArrayList<String>()
    val storageId = 0x00010001L
    var modePos = 5L
    var rejectWildcard = true
    var onRead: suspend (PtpOp) -> Pair<PtpResponse, ByteArray>? = { null }
    override fun collectPendingEvents(): List<PtpEvent> = emptyList()
    override suspend fun transaction(
        op: PtpOp,
        expectedData: Boolean,
        dataOut: ByteArray?,
        dataSink: ((ByteArray, Int) -> Unit)?,
        timeoutMs: Int,
    ): PtpResponse {
        UsbIoThread.requireIoThread("DriverTransport.transaction")
        check(timeoutMs > 0)
        check(dataOut == null) { "Capability probes must not write data" }
        ops += op
        threads += Thread.currentThread().name
        val reply = onRead(op) ?: defaultReply(op)
        if (reply.second.isNotEmpty()) dataSink?.invoke(reply.second, reply.second.size)
        return reply.first
    }
    private fun defaultReply(op: PtpOp): Pair<PtpResponse, ByteArray> {
        if (op.code == Op.GET_OBJECT_HANDLES && rejectWildcard && op.params[0] == PtpConstants.ALL_STORAGE_IDS) {
            return PtpResponse(Rc.PARAMETER_NOT_SUPPORTED) to byteArrayOf()
        }
        val payload = when (op.code) {
            Op.OPEN_SESSION, Op.CLOSE_SESSION -> byteArrayOf()
            Op.GET_DEVICE_INFO -> deviceInfo
            Op.GET_STORAGE_IDS -> words(1L, storageId)
            PanasonicOp.GET_LIVEVIEW_FORMATS -> Fixtures.hex("liveview_formats")
            PanasonicOp.GET_PROP_VALUE -> propertyValue(op.params.single(), if (op.params.single() == Prop.MODE_POS) modePos else 1L)
            PanasonicOp.GET_PROP_DESC -> Fixtures.hex("desc_iso")
            Op.GET_OBJECT_HANDLES -> words(1L, 42L)
            else -> error("Unexpected driver opcode: " + op.code.toString(16))
        }
        return PtpResponse(Rc.OK, dataBytes = payload.size.toLong()) to payload
    }
    override suspend fun drainEvents(timeoutMs: Int): List<PtpEvent> {
        UsbIoThread.requireIoThread("DriverTransport.drainEvents")
        return emptyList()
    }
    override suspend fun close(timeoutMs: Int) {
        UsbIoThread.requireIoThread("DriverTransport.close")
        isOpen = false
    }
    private fun propertyValue(property: Long, value: Long): ByteArray = words(property, 4L, value)
    private fun words(vararg values: Long): ByteArray = ByteBuffer.allocate(values.size * 4)
        .order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach { putInt(it.toInt()) } }.array()
}

internal class DriverRig(
    val link: DriverTransport = DriverTransport(),
    val store: DriverCache = DriverCache(),
    registry: CameraProfileRegistry = CameraProfileRegistry(),
    snapshot: suspend () -> TetherDebugConfig.Snapshot = { TetherDebugConfig.Snapshot() },
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val target = driverTarget()
    private val transportFactory = object : TransportFactory {
        override fun supports(target: CameraTarget): Boolean = target is UsbTarget
        override suspend fun create(target: CameraTarget): Transport = link
    }
    private val scheduler = PtpScheduler {
        val ioDispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, UsbIoThread.THREAD_NAME) }.asCoroutineDispatcher()
        object : PtpScheduler.IoWorker {
            override val dispatcher = ioDispatcher
            override fun shutdown() = ioDispatcher.close()
        }
    }
    private val config = mockk<TetherDebugConfig>().also { config ->
        coEvery { config.snapshot() } coAnswers { snapshot() }
    }
    val session = CameraSession(
        transportFactory, scheduler, TetherDiagLogger(), scope,
        discover = { target }, nowMs = { System.nanoTime() / 1_000_000L },
    )
    val driver = PtpUsbDriver(session, scope, registry, store.cache, config, TetherDiagLogger())
    init { UsbIoThread.resetForTest() }
    suspend fun published(predicate: (CameraCapabilities) -> Boolean = { it.probedAt > 0L }): CameraCapabilities =
        withTimeout(5_000) { driver.capabilities.first(predicate) }
    suspend fun close() {
        try { withTimeout(5_000) { driver.disconnect(CloseReason.USER) } }
        finally {
            scope.cancel()
            withTimeout(5_000) { scope.coroutineContext[Job]!!.join() }
            UsbIoThread.resetForTest()
        }
    }
}

internal suspend fun withDriverRig(
    link: DriverTransport = DriverTransport(),
    store: DriverCache = DriverCache(),
    registry: CameraProfileRegistry = CameraProfileRegistry(),
    snapshot: suspend () -> TetherDebugConfig.Snapshot = { TetherDebugConfig.Snapshot() },
    block: suspend (DriverRig) -> Unit,
) {
    val rig = DriverRig(link, store, registry, snapshot)
    try { block(rig) } finally { rig.close() }
}
