package cn.alittlecookie.lutista.tether.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.os.SystemClock
import cn.alittlecookie.lutista.tether.protocol.DeviceInfoParser
import cn.alittlecookie.lutista.tether.protocol.PtpConstants
import cn.alittlecookie.lutista.tether.protocol.PtpContainer
import cn.alittlecookie.lutista.tether.protocol.PtpContainerCodec
import cn.alittlecookie.lutista.tether.protocol.PtpParseException
import cn.alittlecookie.lutista.tether.protocol.PtpTransactionIdGenerator
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 基于 [UsbDeviceConnection] 的 PTP 传输实现。
 *
 * ## 三条不可协商的实现细节（v7 §4.4.1，每一行都对应一个真机事故）
 * 1. 每次 bulk IN 至少请求 64 KiB，按容器头 `len` 取用，多余留在 [RxBuffer]；
 * 2. 读长度向下取整到 `maxPacketSize`（512/1024）整数倍，否则第二个包读不动；
 * 3. payload 从 `容器头之后` 开始取（12 字节头绝不写进文件）。
 *
 * ## 本类的线程模型
 * 所有方法都断言 `ptp-io` 线程（[UsbIoThread]），并用 [busy] 拦住「同连接并发收发」。
 * 内部**没有**线程池、没有 `Dispatchers.IO` 切换——调度是 `PtpScheduler`（T02）的职责。
 *
 * ## 超时语义
 * **会话内**事务的 bulk 超时/NACK、回包结构非法 → 置 [isFatal]、抛异常、之后所有事务直接失败。
 * 不做 `clear_halt` 硬重试（定稿 §6 约束 8）：通道可能已停在 halt，正确处置是拔插相机。
 *
 * **唯一例外**：`open()` 内的 `GetDeviceInfo` 预检（发生在 `OpenSession` 之前，不属于会话内事务）
 * 失败时**不置 fatal**，只把结果写进 [PtpDeviceCaps.preflightFailure]；
 * 此时 `isOpen == true` 但「预检失败 ≠ 通道可用」，调用方必须把随后的 `OpenSession` 视为可能失败。
 */
class PtpUsbTransport(
    rxCapacity: Int = RxBuffer.DEFAULT_CAPACITY,
    /**
     * 单调时钟（毫秒）。默认用 [SystemClock.elapsedRealtime]，测试可注入假时钟 ——
     * `SystemClock` 在 JVM 单测里是 android.jar 的 stub，不可调用。
     */
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
) : PtpTransport {

    private companion object {
        const val LOG_TAG = "PtpUsbTransport"

        /** bulk IN 单次请求字节数：64 KiB（512/1024 的整数倍，天然对齐）。 */
        const val READ_CHUNK = 64 * 1024

        /** 中断端点接收缓冲容量（事件容器很小，8 KiB 足够）。 */
        const val INTR_RX_CAPACITY = 8 * 1024

        /** 端点兜底地址（定稿 §1.1 实测：bulkIn 0x81 / bulkOut 0x01 / intrIn 0x82）。 */
        const val FALLBACK_BULK_IN_ADDRESS = 0x81
        const val FALLBACK_BULK_OUT_ADDRESS = 0x01
        const val FALLBACK_INTR_IN_ADDRESS = 0x82

        /** 端点方向位（避免使用已废弃的 UsbConstants）。 */
        const val ENDPOINT_DIR_IN = 0x80

        /** 传输类型（UsbEndpoint.getType() 的取值）。 */
        const val ENDPOINT_XFER_BULK = 2
        const val ENDPOINT_XFER_INTERRUPT = 3

        /** `open()` 内 GetDeviceInfo 自检用短超时（设备级命令，正常 3~6 ms）。 */
        const val DEVICE_INFO_TIMEOUT_MS = 1_500

        /** `GetDeviceInfo` 回包长度上限：S1 是 223 B，留足余量但仍远小于任何"通道错位狂吐数据"的规模。 */
        const val MAX_DEVICE_INFO_BYTES = 64 * 1024

        /** 非数据容器的长度上限（响应/事件永远很小；超过说明通道错位）。 */
        const val MAX_CONTROL_CONTAINER_BYTES = 4 * 1024 * 1024

        /** `maxPacketSize` 读不到时的兜底值（USB 2.0 HS）。 */
        const val FALLBACK_MAX_PACKET = 512
    }

    private var connection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var bulkIn: UsbEndpoint? = null
    private var bulkOut: UsbEndpoint? = null
    private var intrIn: UsbEndpoint? = null

    private val rx = RxBuffer(rxCapacity)
    private val intrRx = RxBuffer(INTR_RX_CAPACITY)
    private val readBuf = ByteArray(READ_CHUNK)
    private val intrBuf = ByteArray(INTR_RX_CAPACITY)

    /** 交给 `dataSink` 的中转缓冲（接口签名是 `(ByteArray, Int)`，无法传偏移，只能拷一次）。 */
    private val sinkBuf = ByteArray(READ_CHUNK)

    private val txids = PtpTransactionIdGenerator()
    private val busy = AtomicBoolean(false)
    private val pendingEvents = ArrayList<PtpEvent>()

    /** [discardedEventContainers] 的计数器（可跨线程读，只在 IO 线程自增）。 */
    private val discardedEventCounter = AtomicInteger(0)

    @Volatile
    private var maxPacketSize: Int = FALLBACK_MAX_PACKET

    @Volatile
    private var intrMaxPacketSize: Int = FALLBACK_MAX_PACKET

    @Volatile
    private var fatal: Boolean = false

    @Volatile
    private var caps: PtpDeviceCaps? = null

    /** 是否已 open（close 之后为 false）。 */
    val isOpen: Boolean get() = connection != null

    /** 通道是否已被判死（超时/结构非法）。true 时所有事务直接抛异常，需 close + 拔插。 */
    val isFatal: Boolean get() = fatal

    /** `open()` 时拿到的设备能力（含 USB 侧字段）。 */
    val deviceCaps: PtpDeviceCaps? get() = caps

    /**
     * 中断端点上被丢弃的「非事件容器」数量（诊断用；`close()` 时归零）。
     *
     * 为什么需要它：中断通道遇到 `type ∉ 1..4` 是**丢弃 + 计数**而不是判死（见 [drainEvents]），
     * 如果没有计数，这种「相机在事件端点上乱发东西」的情况会完全静默。
     */
    val discardedEventContainers: Int get() = discardedEventCounter.get()

    /**
     * 取走「事务过程中在 bulk IN 上顺带收到的事件」。
     * 正常路径不会走到这里（事件走中断端点），保留它只为诊断与容错。
     *
     * 与其他 public 方法一样**必须在 IO 线程调用**（它读写 [pendingEvents]）。
     */
    fun collectPendingEvents(): List<PtpEvent> {
        UsbIoThread.requireIoThread("PtpUsbTransport.collectPendingEvents")
        if (pendingEvents.isEmpty()) return emptyList()
        val out = ArrayList(pendingEvents)
        pendingEvents.clear()
        return out
    }

    // ────────────────────────────── open / close ──────────────────────────────

    override suspend fun open(device: UsbDevice, connection: UsbDeviceConnection): PtpDeviceCaps {
        UsbIoThread.requireIoThread("PtpUsbTransport.open")
        check(this.connection == null) { "PtpUsbTransport 已经 open，必须先 close 才能复用" }

        if (device.interfaceCount <= 0) {
            throw PtpException("USB 设备没有 interface 0，无法按 PTP 通信")
        }
        val intf: UsbInterface = device.getInterface(0)
        if (!connection.claimInterface(intf, true)) {
            throw PtpException("claimInterface(0) 失败：设备可能被系统 MTP/PTP 进程占用")
        }

        try {
            val inEp = findEndpoint(intf, ENDPOINT_XFER_BULK, ENDPOINT_DIR_IN, FALLBACK_BULK_IN_ADDRESS)
            val outEp = findEndpoint(intf, ENDPOINT_XFER_BULK, 0, FALLBACK_BULK_OUT_ADDRESS)
            val intrEp = findEndpointOrNull(intf, ENDPOINT_XFER_INTERRUPT, ENDPOINT_DIR_IN, FALLBACK_INTR_IN_ADDRESS)

            this.connection = connection
            this.usbInterface = intf
            this.bulkIn = inEp
            this.bulkOut = outEp
            this.intrIn = intrEp
            this.maxPacketSize = inEp.maxPacketSize.takeIf { it > 0 } ?: FALLBACK_MAX_PACKET
            this.intrMaxPacketSize = intrEp?.maxPacketSize?.takeIf { it > 0 } ?: FALLBACK_MAX_PACKET
            this.fatal = false
            this.txids.reset()
            this.rx.clear()
            this.intrRx.clear()
            this.pendingEvents.clear()

            val usbOnly = PtpDeviceCaps(
                vendorExtensionId = 0L,
                manufacturer = "",
                model = "",
                deviceVersion = "",
                serialNumber = "",
                operationsSupported = emptySet(),
                eventsSupported = emptySet(),
                devicePropertiesSupported = emptySet(),
                imageFormats = emptySet(),
                bcdUsb = readBcdUsb(connection),
                bulkMaxPacketSize = maxPacketSize,
                usbVendorId = device.vendorId,
                usbProductId = device.productId,
            )
            val probed = probeDeviceInfo(usbOnly)
            check(probed.usbVendorId != 0 && probed.usbProductId != 0) {
                "PtpDeviceCaps 的 USB VID/PID 没有被 UsbDevice 覆盖（vid=${probed.usbVendorId}, " +
                    "pid=${probed.usbProductId}）：这是 open() 的实现 bug，必须暴露而不是静默"
            }
            this.caps = probed
            return probed
        } catch (t: Throwable) {
            // claim 成功但后续失败：必须把接口还回去，否则下次连接会 claim 失败。
            detachState()
            runCatching { connection.releaseInterface(intf) }
            runCatching { connection.close() }
            throw t
        }
    }

    override suspend fun close(timeoutMs: Int) {
        UsbIoThread.requireIoThread("PtpUsbTransport.close")
        val conn = connection
        val intf = usbInterface
        detachState()
        if (conn != null) {
            if (intf != null) {
                runCatching { conn.releaseInterface(intf) }
                    .onFailure { Timber.tag(LOG_TAG).w(it, "releaseInterface 失败（忽略）") }
            }
            runCatching { conn.close() }
                .onFailure { Timber.tag(LOG_TAG).w(it, "UsbDeviceConnection.close 失败（忽略）") }
        }
    }

    // ────────────────────────────── transaction ──────────────────────────────

    override suspend fun transaction(
        op: PtpOp,
        expectedData: Boolean,
        dataOut: ByteArray?,
        dataSink: ((ByteArray, Int) -> Unit)?,
        timeoutMs: Int,
    ): PtpResponse = transactionInternal(
        op = op,
        expectedData = expectedData,
        dataOut = dataOut,
        dataSink = dataSink,
        timeoutMs = timeoutMs,
        // 会话内事务：超时/NACK 一律置 fatal（约束 8），不重试、不 clear_halt。
        fatalOnFailure = true,
    )

    /**
     * 事务实现主体。
     *
     * **try 的边界是刻意的**（P2）：`未 open` / 已 fatal / 并发 属于**调用方错误**，
     * 留在 try 之外，不置 fatal；而端点缺失、写入失败、读超时、结构非法都属于**通道层面出了问题**，
     * 一律在 try 内置 fatal。
     *
     * @param fatalOnFailure 通道层失败是否置 fatal。会话内事务必须为 true；
     *   `open()` 的 `GetDeviceInfo` 预检传 false（预检不属于会话内事务，见 [PreflightFailure]）。
     */
    private suspend fun transactionInternal(
        op: PtpOp,
        expectedData: Boolean,
        dataOut: ByteArray?,
        dataSink: ((ByteArray, Int) -> Unit)?,
        timeoutMs: Int,
        fatalOnFailure: Boolean,
    ): PtpResponse {
        UsbIoThread.requireIoThread("PtpUsbTransport.transaction")
        // ── 以下三条是「调用方错误」：不碰 fatal，否则会把「没连上」误报成「相机坏了」 ──
        val conn = connection ?: throw PtpException("PtpUsbTransport 未 open（或已 close）")
        if (fatal) {
            throw PtpException("传输层已置 fatal（上一次超时/NACK），必须拔插相机后重建连接")
        }
        if (!busy.compareAndSet(false, true)) {
            throw IllegalStateException("同一个 UsbDeviceConnection 上出现了并发 bulk 收发（约束 3 禁止）")
        }

        val txid = txids.next()
        val deadline = now() + timeoutMs.coerceAtLeast(1)
        try {
            val inEp = bulkIn ?: throw PtpException("bulk IN 端点缺失（open() 未完成或端点被系统回收）")
            // 阶段顺序：命令容器 → DATA OUT 容器 → 读响应，txid 共用。
            writeFrames(conn, BulkWriteFrames.build(op, txid, dataOut), deadline)

            var dataBytes = 0L
            var sawDataPhase = false
            var response: PtpResponse? = null

            while (response == null) {

                // 约束 1：永远整包 64 KB 读，凑够 12 字节头再判断。
                while (rx.size < PtpContainerCodec.HEADER_SIZE) fill(conn, inEp, deadline)

                val header = PtpContainerCodec.peekHeader(rx.buffer, rx.readOffset, rx.size)
                    ?: throw PtpProtocolException("无法解析容器头（可读 ${rx.size} 字节）")

                when (header.type) {
                    PtpConstants.ContainerType.DATA -> {
                        sawDataPhase = true
                        if (header.length < PtpContainerCodec.HEADER_SIZE) {
                            throw PtpProtocolException("数据容器长度 ${header.length} 小于 ${PtpContainerCodec.HEADER_SIZE}")
                        }
                        // 约束 3：先吃掉 12 字节头，再按声明长度流式搬运 payload。
                        rx.skip(PtpContainerCodec.HEADER_SIZE)
                        dataBytes += DataPhasePump.pump(
                            source = rx,
                            payloadLength = header.payloadLength,
                            staging = sinkBuf,
                            fill = { fill(conn, inEp, deadline) },
                            sink = dataSink,
                        )
                    }

                    PtpConstants.ContainerType.RESPONSE -> {
                        val container = readControlContainer(conn, inEp, deadline)
                        response = PtpResponse(
                            code = container.code,
                            params = container.payloadAsParams(),
                            dataBytes = dataBytes,
                        )
                    }

                    PtpConstants.ContainerType.EVENT -> {
                        // 正常不会出现在 bulk IN 上；出现就顺带收下，绝不打断事务。
                        pendingEvents += toEvent(readControlContainer(conn, inEp, deadline))
                    }

                    else -> throw PtpProtocolException(
                        "相机回了非预期容器类型 ${header.type}（疑似通道错位，需拔插 USB）",
                    )
                }
            }

            // 走到这里 response 必然非空（循环条件就是 response == null）。
            val result = response
            if (expectedData && !sawDataPhase) {
                Timber.tag(LOG_TAG).w(
                    "事务 0x%x 预期有数据段，但只收到响应 rc=0x%x",
                    op.code,
                    result.code,
                )
            }
            if (!expectedData && sawDataPhase) {
                // 约束 3：这是合法情形，数据已经照常收完；只做诊断打点。
                Timber.tag(LOG_TAG).d("事务 0x%x 未预期却有数据段（已整包收完 %d 字节）", op.code, dataBytes)
            }
            return result
        } catch (t: PtpException) {
            // 通道层面的一切失败（超时 / 结构非法 / 短写 / 端点丢失 / 缓冲不足）都归这里：
            // 约束 8 —— 之后通道可能停在 halt，除了拔插没有可靠的自愈手段。
            if (fatalOnFailure) fatal = true
            throw t
        } finally {
            busy.set(false)
        }
    }

    // ────────────────────────────── drainEvents ──────────────────────────────

    /**
     * 排空 0x82 中断端点。**必须在 IO 线程调用。**
     *
     * ## 两条通道对「非法容器」的策略**故意不同**（不要"统一"）
     * - **响应通道**（[transaction]）：`type ∉ 1..4` → 抛 [PtpProtocolException] 并**判 fatal**。
     *   理由：响应通道错位意味着此后所有容器边界都不可信，继续读只会拿到垃圾。
     * - **中断通道**（本方法）：`type ∉ 1..4` → **丢弃 + 计数**（[discardedEventContainers]），
     *   **绝不判死会话**。理由：事件通道与命令/数据通道相互独立，
     *   一个陌生容器不该让用户的会话失效（"事件通道坏 ≠ 会话坏"）。
     *
     * 与 [PtpContainerCodec.Decoded.Corrupt] 的分工：`Corrupt` 是「头部自相矛盾」，
     * 属**缓冲区级**处理（清空该端点残留）；`type` 非法是**容器级**处理（跳过这个容器、继续解下一个）。
     * 两者不冲突、也不重复。
     */
    override suspend fun drainEvents(timeoutMs: Int): List<PtpEvent> {
        UsbIoThread.requireIoThread("PtpUsbTransport.drainEvents")
        val conn = connection ?: return emptyList()
        val ep = intrIn ?: return emptyList()
        // 定稿 §1.4 / v7 §4.4.1：必须 1~2 ms，40 ms 会白吃 80 ms。
        val perRead = timeoutMs.coerceIn(1, 2)
        if (!busy.compareAndSet(false, true)) {
            Timber.tag(LOG_TAG).d("drainEvents 撞上进行中的事务，本轮跳过")
            return emptyList()
        }

        val out = ArrayList<PtpEvent>(4)
        try {
            val request = intrMaxPacketSize.coerceIn(8, intrBuf.size)
            intrRx.reserveTail(request)
            while (true) {
                // 没有事件时**不会自旋**：一次读不到就 break，不在 1~2 ms 的短超时上循环重试。
                val read = conn.bulkTransfer(ep, intrBuf, request, perRead)
                if (read <= 0) break
                intrRx.append(intrBuf, 0, read)
                while (true) {
                    when (val decoded = PtpContainerCodec.decodeNext(intrRx.buffer, intrRx.readOffset, intrRx.size)) {
                        is PtpContainerCodec.Decoded.Container -> {
                            if (decoded.container.type == PtpConstants.ContainerType.EVENT) {
                                out += toEvent(decoded.container)
                            } else {
                                // 中断端点上的非事件容器：丢弃并计数（不抛、不判死会话）。
                                discardedEventCounter.incrementAndGet()
                                Timber.tag(LOG_TAG).w(
                                    "中断端点收到非事件容器 type=%d code=0x%x，已丢弃（累计 %d）",
                                    decoded.container.type,
                                    decoded.container.code,
                                    discardedEventCounter.get(),
                                )
                            }
                            // 接受与丢弃两条路径都必须前进，否则会卡在同一容器上反复解 → 活锁。
                            intrRx.skip(decoded.consumed)
                        }

                        PtpContainerCodec.Decoded.Incomplete -> break

                        is PtpContainerCodec.Decoded.Corrupt -> {
                            Timber.tag(LOG_TAG).w("事件容器结构非法：%s（丢弃该端点残留）", decoded.reason)
                            intrRx.clear()
                            break
                        }
                    }
                }
            }
        } finally {
            busy.set(false)
        }
        return out
    }

    // ────────────────────────────── 内部：USB I/O ──────────────────────────────

    /**
     * 读一批字节进 [rx]。
     *
     * 请求长度 = `min(64 KiB, 尾部空间)` 并向下取整到 [maxPacketSize]（约束 1 的第二个坑）。
     */
    private fun fill(conn: UsbDeviceConnection, endpoint: UsbEndpoint, deadline: Long) {
        val tail = rx.reserveTail(READ_CHUNK)
        var request = minOf(READ_CHUNK, tail)
        request -= request % maxPacketSize
        if (request < maxPacketSize) {
            throw PtpException("rx 缓冲尾部空间不足（tail=$tail maxPacket=$maxPacketSize），无法按对齐长度读")
        }
        val timeout = remainingTimeoutMs(deadline)
        val read = conn.bulkTransfer(endpoint, readBuf, request, timeout)
        if (read < 0) {
            throw PtpTimeoutException(
                "bulk IN 超时/失败（ep=0x${endpoint.address.toString(16)} request=$request timeout=${timeout}ms）",
            )
        }
        if (read == 0) {
            // 整包边界上的 0 长包（ZLP）：继续读，但不能再白等一轮。
            if (remainingTimeoutMs(deadline) <= 1) {
                throw PtpTimeoutException("bulk IN 只收到 ZLP，事务超时")
            }
            return
        }
        rx.append(readBuf, 0, read)
    }

    /**
     * 从 [rx] 里取一个完整的小容器（响应/事件），必要时继续读。
     * 超过 [MAX_CONTROL_CONTAINER_BYTES] 说明通道错位，直接判协议非法。
     */
    private fun readControlContainer(
        conn: UsbDeviceConnection,
        endpoint: UsbEndpoint,
        deadline: Long,
    ): PtpContainer {
        while (true) {
            val header = PtpContainerCodec.peekHeader(rx.buffer, rx.readOffset, rx.size)
            if (header != null) {
                if (header.length < PtpContainerCodec.HEADER_SIZE) {
                    throw PtpProtocolException("容器长度 ${header.length} 小于头长 ${PtpContainerCodec.HEADER_SIZE}")
                }
                if (header.length > MAX_CONTROL_CONTAINER_BYTES) {
                    throw PtpProtocolException(
                        "响应/事件容器长度 ${header.length} 超出上限 $MAX_CONTROL_CONTAINER_BYTES（疑似通道错位）",
                    )
                }
                if (rx.size >= header.length) {
                    val decoded = PtpContainerCodec.decodeNext(rx.buffer, rx.readOffset, rx.size)
                    if (decoded !is PtpContainerCodec.Decoded.Container) {
                        throw PtpProtocolException("容器解码失败：$decoded")
                    }
                    rx.skip(decoded.consumed)
                    return decoded.container
                }
            }
            fill(conn, endpoint, deadline)
        }
    }

    /**
     * 按顺序写出一组容器（命令 → DATA OUT）。
     *
     * 短写循环与 ZLP 规则都在 [BulkWriteFrames.writeAll] 里（纯逻辑、有单测），这里只提供
     * 真正的 `bulkTransfer` 出口。写不完整会抛 [PtpTimeoutException]，事务随判 fatal ——
     * 「写一半就去读响应」会让之后所有容器边界错位。
     */
    private fun writeFrames(conn: UsbDeviceConnection, frames: List<ByteArray>, deadline: Long) {
        val outEp = bulkOut ?: throw PtpException("bulk OUT 端点缺失（open() 未完成或端点被系统回收）")
        BulkWriteFrames.writeAll(frames, maxPacketSize) { buffer, offset, length ->
            conn.bulkTransfer(outEp, buffer, offset, length, remainingTimeoutMs(deadline))
        }
    }

    private fun remainingTimeoutMs(deadline: Long): Int {
        val left = deadline - now()
        if (left <= 0) {
            throw PtpTimeoutException("事务超时（deadline 已过）")
        }
        return left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    // ────────────────────────────── 内部：辅助 ──────────────────────────────

    /**
     * `open()` 内的 `GetDeviceInfo` 预检。
     *
     * **预检失败不置 fatal**：它发生在 `OpenSession` 之前，还没有「会话」概念；
     * 把它升级成「整条连接判死」会让用户看到「插上就坏且没有诊断」，而拔插后大概率能恢复。
     * 失败信息落进 [PtpDeviceCaps.preflightFailure]，由 session 层决定提示文案与处置。
     *
     * 失败时会清空 [rx]（可能残留半个容器），避免污染紧随其后的 `OpenSession`。
     */
    private suspend fun probeDeviceInfo(base: PtpDeviceCaps): PtpDeviceCaps {
        val payload = ByteArrayOutputStream(DeviceInfoParser.S1_DEVICE_INFO_SIZE)

        val response: PtpResponse = try {
            transactionInternal(
                op = PtpOp(PtpConstants.Op.GET_DEVICE_INFO),
                expectedData = true,
                dataOut = null,
                dataSink = { chunk, length ->
                    // 防御：通道错位时相机可能一直吐数据，无上限累积会 OOM。
                    // 超限直接抛（外层 catch 会归成 PARSE_ERROR），不再继续收。
                    if (payload.size() + length > MAX_DEVICE_INFO_BYTES) {
                        throw PtpProtocolException(
                            "GetDeviceInfo 回包超过 $MAX_DEVICE_INFO_BYTES 字节上限（疑似通道错位）",
                        )
                    }
                    payload.write(chunk, 0, length)
                },
                timeoutMs = DEVICE_INFO_TIMEOUT_MS,
                fatalOnFailure = false,
            )
        } catch (t: Throwable) {
            rx.clear()
            val failure = PreflightOutcomes.resolve(null, t) ?: PreflightFailure.PARSE_ERROR
            Timber.tag(LOG_TAG).w(
                t,
                "open 预检 GetDeviceInfo 失败（%s）：caps 降级为纯 USB 描述符，isFatal 保持 false",
                failure,
            )
            return base.copy(preflightFailure = failure)
        }

        if (response.code != PtpConstants.Rc.OK) {
            rx.clear()
            val failure = PreflightOutcomes.resolve(response.code, null) ?: PreflightFailure.REJECTED
            Timber.tag(LOG_TAG).w(
                "open 预检 GetDeviceInfo 返回 0x%x（%s）：caps 降级为纯 USB 描述符",
                response.code,
                failure,
            )
            return base.copy(preflightFailure = failure)
        }

        return try {
            DeviceInfoParser.parse(payload.toByteArray()).withUsb(base)
        } catch (t: PtpParseException) {
            Timber.tag(LOG_TAG).w(t, "open 预检 GetDeviceInfo 回包解析失败（PARSE_ERROR）")
            base.copy(preflightFailure = PreflightFailure.PARSE_ERROR)
        }
    }

    /** 保留 USB 描述符字段（这些字段不在 GetDeviceInfo 回包里）。 */
    private fun PtpDeviceCaps.withUsb(usb: PtpDeviceCaps): PtpDeviceCaps = copy(
        bcdUsb = usb.bcdUsb,
        bulkMaxPacketSize = usb.bulkMaxPacketSize,
        usbVendorId = usb.usbVendorId,
        usbProductId = usb.usbProductId,
    )

    private fun toEvent(container: PtpContainer): PtpEvent {
        val params = container.payloadAsParams()
        return PtpEvent(
            code = container.code,
            params = params,
            raw = PtpContainerCodec.encodeEvent(container.code, params),
        )
    }

    private fun detachState() {
        connection = null
        usbInterface = null
        bulkIn = null
        bulkOut = null
        intrIn = null
        caps = null
        fatal = false
        busy.set(false)
        rx.clear()
        intrRx.clear()
        pendingEvents.clear()
        discardedEventCounter.set(0)
    }

    private fun findEndpoint(
        intf: UsbInterface,
        type: Int,
        direction: Int,
        fallbackAddress: Int,
    ): UsbEndpoint = findEndpointOrNull(intf, type, direction, fallbackAddress)
        ?: throw PtpException(
            "interface 0 上找不到端点（type=$type direction=0x${direction.toString(16)} " +
                "fallback=0x${fallbackAddress.toString(16)}），设备可能不是标准 PTP 实现",
        )

    private fun findEndpointOrNull(
        intf: UsbInterface,
        type: Int,
        direction: Int,
        fallbackAddress: Int,
    ): UsbEndpoint? {
        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            if (ep.type == type && (ep.address and ENDPOINT_DIR_IN) == direction) return ep
        }
        // 兜底：按实测地址匹配（有些固件的属性位不规范）。
        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            if (ep.address == fallbackAddress) return ep
        }
        return null
    }

    /** 从原始描述符里取 `bcdUSB`（仅诊断用；取不到返回 0）。 */
    private fun readBcdUsb(conn: UsbDeviceConnection): Int = try {
        val raw = conn.rawDescriptors
        if (raw == null || raw.size < 4) {
            0
        } else {
            (raw[2].toInt() and 0xFF) or ((raw[3].toInt() and 0xFF) shl 8)
        }
    } catch (t: Throwable) {
        Timber.tag(LOG_TAG).w(t, "读取 bcdUSB 失败（忽略，仅影响诊断）")
        0
    }
}
