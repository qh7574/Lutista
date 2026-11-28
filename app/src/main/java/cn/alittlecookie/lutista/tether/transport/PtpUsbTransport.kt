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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
 * ## 超时语义（2026-11-28 第七轮修订，见交付记录 §13）
 * **会话内**事务的 bulk 超时/NACK、回包结构非法 → 先尝试**有界收尾**（[resyncChannel]：
 * 清 halt → 精确排空数据段 → 读响应容器；读不到才升级到 `GetDeviceStatus` + 类请求
 * `DeviceReset(0x66)` + `SET_INTERFACE`），只有**证明不了「事务已闭合」时**才置 [isFatal]。
 * 判据是三个条件同时成立：没升级到设备级复位、数据段按声明长度排空、读到本事务的
 * 结构合法 RESPONSE 容器。真机证据：收尾成功的那次会话可以继续用，收尾失败的会话
 * 只会把下一条命令连带钉死（`logs_1795794239593` 23:42:28）。
 *
 * `fill()` 本身仍然**零额外 I/O**（一次命令写 + 一次超时读即抛，有单测锁住）——
 * 收尾只发生在 [transactionInternal] 的 catch 里，共一次。
 *
 * 真正的自救也仍在**下一次** `open()`：预检 `GetDeviceInfo` 返回
 * [PreflightFailure.TIMEOUT] 时做一次**有界**通道复位后重试一次预检，
 * 把相机从「事务未完成」里拉回来。只有 TIMEOUT 才复位：`REJECTED`/`PARSE_ERROR` 说明相机有应答。
 * 若上一次失败的事务已经自己收尾干净（[lastTransactionRecovered]），`open()` 不再重复复位。
 *
 * **协程取消不等于通道故障**：命令已经写出去之后被取消，相机必然还在等一个数据段或响应，
 * 主机这时候撒手会留下「错位响应」，之后所有命令立即失败。所以取消路径在抛之前先用
 * `NonCancellable` 做一次同样的有界复位（不置 fatal，见 [transactionInternal] 的 catch）。
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
    /**
     * 可选的 USB 诊断采集器（T01b）。**默认 `null` 时零分配、零开销**：所有采集点都是
     * 内联 `emitDiag` 的可空判断，关闭时不求值回调与实参，因此不会有额外对象分配、
     * 字符串拼接或额外的时钟读取。本钩子只采集运行时真值，不改变任何协议逻辑、状态机、
     * 异常类型 / message、超时 / fatal / ZLP / 短写 / txid 语义；详见 [PtpDiagSink]。
     */
    private val diag: PtpDiagSink? = null,
    /**
     * 可选：把当前 `UsbDeviceConnection` 丢掉、重新 `openDevice()` 拿一个新 fd 的回调。
     *
     * 为什么需要：真机 `logs_1795829123428`（2026-11-28 09:24）里手机与相机都完整重启过，
     * 仍然是「控制传输全成功、bulk OUT 立刻 -1、bulk IN 立刻 -1」，而 `haltBefore` 两端都是
     * clear —— 说明设备侧没报 halt，主机侧这条 usbfs 通道自己也坏了。Android 没有暴露
     * `resetDevice()`/端口复位，唯一还能做的主机侧动作就是**关掉 fd 重新打开**
     * （新 fd 会重建内核端点状态，再配合 `SET_INTERFACE` 复位数据翻转）。
     *
     * 默认 `null`（JVM 单测与不支持重开的调用方不走这条路径）。
     */
    private val reopenConnection: (() -> UsbDeviceConnection?)? = null,
) : PtpTransport {

    private companion object {
        const val LOG_TAG = "PtpUsbTransport"

        /**
         * 通道复位总结日志的格式串。
         *
         * 抽成常量是因为 lint 的 `BinaryOperationInTimber` 会把日志调用里的字符串拼接判为问题；
         * 放在常量里拼接既满足格式要求，也让真机日志的字段顺序有唯一事实来源。
         */
        const val RESYNC_LOG_FORMAT = "通道复位（%s）：halt 前 %s 后 %s；cancel=%s cleared(in=%s out=%s) " +
            "drained=%d/%d drainComplete=%s status=%d classReset=%s setInterface=%s " +
            "resetWritten=%d responseCode=%s escalated=%s"

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

        /**
         * 预检**重试**的超时。第一次预检已经零回包时，再用 1500 ms 去等没有任何信息增量，
         * 却会把 5 s 连接预算吃光：真机 `logs_1795826645352` 每轮预算 1.5 s（首写）+1.5 s
         * （清 halt 重写）+1.5 s（重试首写）+… 直接超预算，用户看到的是「连接超时」，
         * 而不是「请关掉相机电源」——判定已经做出了，却没能送达用户。
         */
        const val PREFLIGHT_RETRY_TIMEOUT_MS = 800

        /**
         * STALL 后清 halt 重写的超时上限。12~24 字节的命令要么立刻进端点缓冲、要么被 NAK；
         * 用满原始超时只会把「设备已经不收命令」这件事拖成两倍时长。
         */
        const val STALL_RETRY_TIMEOUT_MS = 300

        /** `GetDeviceInfo` 回包长度上限：S1 是 223 B，留足余量但仍远小于任何"通道错位狂吐数据"的规模。 */
        const val MAX_DEVICE_INFO_BYTES = 64 * 1024

        /** 非数据容器的长度上限（响应/事件永远很小；超过说明通道错位）。 */
        const val MAX_CONTROL_CONTAINER_BYTES = 4 * 1024 * 1024

        /** `maxPacketSize` 读不到时的兜底值（USB 2.0 HS）。 */
        const val FALLBACK_MAX_PACKET = 512

        /**
         * 数据段内 bulk IN 超时的重试次数。
         *
         * 真机（2026-11-27 第二份日志）出现过“一次 bulk IN 超时之后，重新连接只有 -1 或永远不应答”，
         * 必须拔数据线才能恢复：相机在数据段里被主机丢下后会一直停在“事务未完成”，
         * 之后所有命令都被它挡住。数据段里的 URB 超时其实是可重试的（URB 被取消，设备侧不受影响），
         * 所以先原地继续等，绝不中途放弃数据段 —— 只有真的等不到才判 fatal。
         */
        const val DATA_PHASE_RETRIES = 3

        /** 每次数据段重试追加的等待预算（重新起算 deadline）。 */
        const val DATA_PHASE_RETRY_MS = 1_500L

        /**
         * `bulk OUT` 首包被设备 NAK 之后、清 halt 重试**成功**时给事务续的预算。
         *
         * 真机 logs_1795794239593（23:42:28）的证据链：重试预检的第一次写吃满 1500 ms 超时返回 -1，
         * 清 halt 之后重写**成功了**（相机收下了命令），但事务 deadline 已经在第一次写里耗尽，
         * 紧接着的 `fill()` 在 2 ms 内以「事务超时（deadline 已过）」抛异常 ——
         * 一条本来已经救回来的事务被当场判死，而它的响应容器又留在相机里，
         * 下一条命令因此 `bulk OUT -1`，连锁成「无法连接」。
         *
         * 恢复耗时不能算进事务预算：续一个够读完响应的量（正常响应是毫秒级）。
         */
        const val OUT_RECOVERY_GRACE_MS = 1_500L

        /**
         * 清端点 halt 的控制传输超时。
         *
         * 为什么是 150 ms 而不是 500 ms：相机在线时 `CLEAR_FEATURE` / `GET_STATUS` 都是**毫秒级**
         * 返回（真机日志里是 0~4 ms），这个超时只用来给「设备已经消失」兜底。而整个 `open()`
         * 还挂在 [CameraSession] 的 5 s 连接预算上（`"Connection exceeded the fixed 5 second budget"`），
         * 500 ms 会让一次复位白白吃掉半秒预算 —— 真机（logs_1795788978642）就是这样：
         * 复位其实**成功了**（重试预检 4 ms 内 OK），却因为总共花了 5.44 s 被判超时。
         */
        const val CLEAR_HALT_TIMEOUT_MS = 150

        /** USB 标准请求：CLEAR_FEATURE(ENDPOINT_HALT)，用于复位上一次会话留下的 halt。 */
        const val USB_REQ_CLEAR_FEATURE = 0x01
        const val USB_FEATURE_ENDPOINT_HALT = 0x00
        const val USB_RECIP_ENDPOINT = 0x02

        /** PTP DeviceReset：让相机中止挂起的事务，用于「命令收下但不回应」的死结。 */
        const val DEVICE_RESET_OPCODE = 0x1003
        const val DEVICE_RESET_TXID = 0xFFFFFFFFL

        // ── PTP-over-USB 的**类请求**（bmRequestType recipient=interface）──
        //
        // 为什么不用 PTP 命令做恢复：相机停在上一次会话的「数据段未完成」时，**新的 PTP 命令
        // 写不进去也读不出回应**（真机 logs_1795791570292：`bulk OUT 首包写入失败 … 返回 -1`，
        // 而同一时刻 `CLEAR_FEATURE(ENDPOINT_HALT)` 却被接受）。类请求由 USB 设备控制器直接
        // 处理，不经过 PTP 事务队列 —— 这正是 libgphoto2 的做法（camlibs/ptp2/usb.c
        // `ptp_usb_control_cancel_request` / `ptp_usb_control_device_reset_request` /
        // `ptp_usb_control_get_device_status`），也是「钉死」状态下唯一的出路。
        //
        // 请求码取自 MS PTP-over-USB 规范：0x64 Cancel / 0x66 Device Reset / 0x67 Get Device Status。

        /** `bmRequestType` 的 type 位：0x20 = class。 */
        const val USB_TYPE_CLASS = 0x20

        /**
         * `bmRequestType` 的 recipient 位：`0x01` = interface。
         *
         * USB 2.0 §9.3.2：0 = Device、1 = Interface、2 = Endpoint。所以类请求的 bmRequestType
         * 是 `0x20 | 0x01 | 0x00 = 0x21`（主机→设备）、`0x20 | 0x01 | 0x80 = 0xA1`（设备→主机）。
         * 曾误写成 0x00（device recipient），相机很可能直接拒绝 —— 单测 `controls` 记录抓到了。
         */
        const val USB_RECIP_INTERFACE = 0x01
        const val USB_DIR_OUT = 0x00

        /** 取消当前事务；payload = 6 字节（事件码 0x4001 + 事务号）。 */
        const val USB_CLASS_REQ_CANCEL = 0x64
        const val PTP_EC_CANCEL_TRANSACTION = 0x4001
        const val CANCEL_PAYLOAD_BYTES = 6

        /** 设备级复位：重置 PTP 事务队列、端点的 data toggle 与 halt。 */
        const val USB_CLASS_REQ_DEVICE_RESET = 0x66

        /** 读设备状态（2 字节，bit0 = 需要复位）。 */
        const val USB_CLASS_REQ_GET_DEVICE_STATUS = 0x67

        /**
         * 通道复位的**排空**预算：单次 150 ms，总量 8 MiB。
         *
         * 8 MiB 而不是 512 KiB：真机 logs_1795791570292 的 `drained=262144 reads=4`
         * 正好是上一版的上限（4 × 64 KiB），而中断的那条事务是 8 MiB 级的整段下载 ——
         * 排空被上限截断，相机仍留着几百 KiB 没发完，下一条命令立刻 `OUT -1`。
         * 排空必须**按事务声明的长度读满**（libgphoto2 `ptp_usb_getdata` 的
         * `bytes_to_read = length - bytes_read`），而不是拍脑袋定个小上限。
         */
        const val RESYNC_DRAIN_READS = 128
        const val RESYNC_DRAIN_BYTES = 8L * 1024 * 1024

        /** 单次排空/控制传输的超时：相机在线时是 0~4 ms，这个值只给「设备已消失」兜底。 */
        const val RESYNC_TIMEOUT_MS = 150

        /** 排空的总时长兜底（秒级），防止相机慢速滴数据把 IO 线程钉住。 */
        const val RESYNC_DRAIN_BUDGET_MS = 1_500L

        /**
         * 排空之后最多再读几次「响应容器」。
         *
         * 为什么必须是 2 而不是 16：上一版写成 `repeat(16) { … return@repeat }`
         * （`return@repeat` 是 continue，不是 break）→ 16 × 300 ms = 4.8 s，
         * 这就是真机上「通道复位」整整花掉 5.44 s 的**唯一**原因。
         */
        const val RESYNC_RESPONSE_READS = 2

        /** USB 标准请求 `GET_STATUS`（recipient=endpoint）：读端点 halt 位（bit0）用。 */
        const val USB_REQ_GET_STATUS = 0x00
        const val USB_DIR_IN = 0x80
    }

    /**
     * 事务内可变 deadline。
     *
     * 数据段重试要把 deadline 往后顺延，所以它不能是 `val Long`；用可变持有者而不是
     * `() -> Long` lambda，避免每次 64 KiB 读都分配对象。
     */
    private class TxDeadline(var value: Long)

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

    /** 只在 IO 线程与事务内改写：当前是否正处在 DATA 容器的 payload 搬运中。 */
    private var inDataPhase: Boolean = false

    /** 本次事务已经为「数据段 bulk IN 超时」重试过几次。事务开始/容器边界处归零。 */
    private var dataPhaseRetries: Int = 0

    /**
     * 当前 DATA 容器**声明的** payload 长度（0 = 不在数据段）。
     *
     * 三个字段一起构成「数据段还剩多少在飞」的精确账，取消/失败时靠它把通道读回容器边界
     * （而不是拍脑袋排空一个固定上限）。缺了它就只能「大概排空」，
     * 排不干净的那部分会变成下一条事务的「错位响应」—— 真机 OUT -1 风暴的成因。
     */
    private var dataPhasePayload: Int = 0

    /** 当前 DATA 容器里**已经从 USB 读进 [rx]** 的字节数（含已交给 sink 的部分）。 */
    private var dataPhaseReadFromWire: Int = 0

    /** 当前 DATA 容器里**已经交给 sink（离开 [rx]）**的字节数。 */
    private var dataPhaseDelivered: Int = 0

    /**
     * 最近一条事务失败时，[resyncChannel] 是否**已经把它收干净**（读到了响应容器）。
     *
     * 用来避免重复付恢复预算：`transactionInternal` 的失败分支已经收尾过一次，
     * `open()` 的预检重试就不必再复位一遍（一次坏连接付两遍排空，会顶穿 5 s 连接预算）。
     * 只在 USB IO 线程上读写，不需要同步。
     */
    private var lastTransactionRecovered: Boolean = false

    /** 最近一次「失败事务自收尾」实际花掉的毫秒数；供 `open()` 上报 [openRecoveryMs]。 */
    private var lastTransactionRecoveryMs: Long = 0

    /**
     * 本次 `open()` 里通道自愈实际花掉的毫秒数（没有自愈就是 0）。
     *
     * 会话层的 5 s 连接预算只买「PTP 会话建立」，不该为传输层自愈买单：真机
     * `logs_1795791570292` 的 22:57:56 那一次，复位 455 ms 成功、重新预检 4 ms OK，却在
     * `open()` 返回后被预算判死（`connectionMs=2027` 与 `Connection exceeded the fixed 5 second
     * budget` 交替）。这里只上报**确实发生过自愈**的耗时，`CameraSession` 据此按有界额度补预算；
     * 干净连接保持 0，既有预算契约（201E 恢复、factory 超预算）都不受影响。
     */
    override var openRecoveryMs: Long = 0
        private set

    /**
     * 本次 `open()` 期间是否从 bulk IN 上读到过**至少一个字节**（预检请求或收尾排空都算）。
     *
     * 与 [cameraSilent] 配对：真机 `logs_1795824713270` 的 6 次连接尝试里，相机连一个字节都没回，
     * 而所有控制传输（清 halt / `SET_INTERFACE` / 类请求 DeviceReset）都「成功」——说明主机侧
     * USB 栈正常、相机端 PTP 引擎卡死。只在 USB IO 线程上读写。
     */
    private var openSawBytes: Boolean = false

    /** 本次 `open()` 是否「完全沉默」；语义见 [Transport.cameraSilent]。 */
    override var cameraSilent: Boolean = false
        private set

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
            this.inDataPhase = false
            this.dataPhaseRetries = 0
            this.openRecoveryMs = 0
            this.lastTransactionRecoveryMs = 0
            this.openSawBytes = false
            this.cameraSilent = false
            // 上一次会话若在数据段里被打断，端点可能停在 halt：新版 open 主动清掉，
            // 这样「一次超时之后必须拔数据线」的多数情况不需要用户真的拔线。
            // 先读 halt 位、再清：只记 `clearHalt(in=true out=true)` 无法区分「相机把端点停住了」
            // 与「端点本来就干净」——真机 logs_1795826645352 就是缺这一位，导致无法判断是相机
            // USB 固件卡死还是手机 host 栈卡死。
            val haltBeforeIn = endpointHaltBit(connection, inEp)
            val haltBeforeOut = endpointHaltBit(connection, outEp)
            // **只在设备自报端点 halt 时才清**。从 userspace 直接发 CLEAR_FEATURE 只会让
            // 设备把该端点的 data toggle 归到 DATA0，而手机 host 控制器侧的 toggle 不会跟着复位
            // （那是内核 usb_clear_halt() 的职责，Android 的 UsbDeviceConnection 没有暴露它）——
            // 两端 toggle 一旦错开，后续 bulk OUT 会被设备当成重传丢弃或一直 NAK，表现就是
            // 「连接后第一条命令就 -1」。libgphoto2 同样不在 open 时清 halt，只在读写出错时清一次。
            val needsClearIn = haltBeforeIn == 1
            val needsClearOut = haltBeforeOut == 1
            val haltIn = if (needsClearIn) clearEndpointHalt(connection, inEp) else false
            val haltOut = if (needsClearOut) clearEndpointHalt(connection, outEp) else false
            Timber.tag(LOG_TAG).i(
                "open: vid=0x%04x pid=0x%04x bulkIn=0x%02x bulkOut=0x%02x intrIn=%s " +
                    "haltBefore(in=%s out=%s) clearHalt(in=%s out=%s)",
                device.vendorId,
                device.productId,
                inEp.address,
                outEp.address,
                intrEp?.address ?: "none",
                describeHalt(haltBeforeIn),
                describeHalt(haltBeforeOut),
                if (needsClearIn) haltIn.toString() else "skipped",
                if (needsClearOut) haltOut.toString() else "skipped",
            )

            // 这里**不做**预排空：干净的连接上没有任何残余数据，先读一次会白吃一个
            // 150 ms 超时，也会打乱「预检只走一次 IN + 一次 OUT」这条既有契约。
            // 上一次会话留下的残余数据由预检失败后的 `resyncChannel()` 负责排空。
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
            var probed = probeDeviceInfo(usbOnly)
            if (probed.preflightFailure == PreflightFailure.TIMEOUT) {
                // 真机证据（logs_1795787131379 / logs_1795824713270）：预检失败时要么 bulk OUT 在 4 ms
                // 内返回 -1、要么 bulk IN 永不回应，而 CLEAR_FEATURE(ENDPOINT_HALT) 是被相机接受的 ——
                // 说明上一次会话留下的「数据段未完成」把通道钉死了（也可能是相机固件自己卡死）。
                // 有回包时做一次**有界**通道复位（读 halt 位 → 清 halt → 排空残留数据 →
                // DeviceReset → 写不进就 SET_INTERFACE 再试一次），然后只重试一次预检；
                // 完全沉默时只清 halt（理由见下面那个分支）。成功的连接完全不经过这段代码。
                //
                // 注意：`probeDeviceInfo` **不会抛**（它把失败写进 preflightFailure），
                // 所以这里必须看返回值而不是靠 catch —— 上一版写成 catch 就成了死代码。
                //
                // 只用 **TIMEOUT**（bulk 读写层面没拿到回包）触发复位：这才是「通道被钉死」的形态。
                // `REJECTED` / `PARSE_ERROR` 意味着相机**有应答**（明确回了非 OK、或回了非法结构），
                // 此时 DeviceReset 会白白拆掉一个还活着的会话，甚至自己制造出「数据段未完成」。
                //
                // 失败的那条事务已经在 `transactionInternal` 的失败分支里尽力收过尾：
                // 它若读到了响应容器（`channelClosed`），通道在协议层就是干净的，
                // 这里只需干净地重试一次预检 —— 再复位一遍等于让一次坏连接付两遍预算，
                // 而 `open()` 全程都挂在 5 s 连接预算上。
                if (lastTransactionRecovered) {
                    Timber.tag(LOG_TAG).w("open 预检失败（TIMEOUT），失败事务已自收尾，直接重试预检")
                    openRecoveryMs = lastTransactionRecoveryMs
                } else if (!openSawBytes) {
                    // 完全沉默（真机 `logs_1795824713270` 的 6 次尝试都是这个形态）：预检 1.5 s
                    // **零字节**，而 CLEAR_FEATURE、SET_INTERFACE、类请求 DeviceReset(0x66)、
                    // PTP DeviceReset(0x1003) 全部「成功」——主机侧 USB 栈正常，相机端 PTP 引擎
                    // 已经不干活了；拔插数据线也不恢复，只有把相机电源关掉再打开才清得掉。
                    //
                    // 复位梯子对「完全沉默」的相机从来没救活过一次，却要花掉秒级预算：
                    // 真机那一次 open() 总共 5.47 s，全被梯子吃掉，**连 OpenSession 都没发出去**
                    // 就被 5 s 连接预算判死。这里只清 halt（很便宜）后重试一次预检，把预算留给
                    // OpenSession —— 相机若只是「短暂发呆」，OpenSession 才是真正能验证它的地方。
                    Timber.tag(LOG_TAG).w(
                        "open 预检超时且零回包（相机端 PTP 引擎可能已卡死）：跳过复位升级，只清 halt 后重试一次预检",
                    )
                    val recoveryStartedAt = now()
                    clearEndpointHalt(connection, inEp)
                    clearEndpointHalt(connection, outEp)
                    rx.clear()
                    openRecoveryMs = (now() - recoveryStartedAt).coerceAtLeast(0)
                } else {
                    Timber.tag(LOG_TAG).w("open 预检失败（%s），尝试通道复位后重试一次", probed.preflightFailure)
                    val recoveryStartedAt = now()
                    resyncChannel("预检失败=" + probed.preflightFailure)
                    openRecoveryMs = (now() - recoveryStartedAt).coerceAtLeast(0)
                }
                // 第一次预检零回包时用更短超时重试：判定（cameraSilent）已经成立，
                // 这里只是给「相机短暂发呆」留最后一次机会，不能让它吃掉连接预算。
                val retried = probeDeviceInfo(usbOnly, if (openSawBytes) DEVICE_INFO_TIMEOUT_MS else PREFLIGHT_RETRY_TIMEOUT_MS)
                Timber.tag(LOG_TAG).w("重试预检：%s", retried.preflightFailure ?: "OK")
                probed = retried
                cameraSilent = probed.preflightFailure == PreflightFailure.TIMEOUT && !openSawBytes
                if (cameraSilent) {
                    Timber.tag(LOG_TAG).w(
                        "open 预检两次都零回包：判定相机无响应（cameraSilent），只有关掉相机电源再打开才能恢复",
                    )
                }
            }
            // 预检仍然失败 → 最后一招：把这条 usbfs 通道整个换掉（关 fd 重新 openDevice +
            // claim + SET_INTERFACE）。真机 `logs_1795829123428` 证明设备侧 halt 位是 clear，
            // 控制传输也能通，但 bulk 两端立刻 -1 —— 这是主机侧通道自身坏了，只有新 fd 能救。
            if (probed.preflightFailure != null) {
                val reopenedAt = now()
                val fresh = reopenUsbConnection(device, intf)
                if (fresh != null) {
                    openSawBytes = false
                    cameraSilent = false
                    val reopened = probeDeviceInfo(usbOnly, PREFLIGHT_RETRY_TIMEOUT_MS)
                    Timber.tag(LOG_TAG).w("USB 重新打开后预检：%s", reopened.preflightFailure ?: "OK")
                    probed = reopened
                    cameraSilent = probed.preflightFailure == PreflightFailure.TIMEOUT && !openSawBytes
                    openRecoveryMs += (now() - reopenedAt).coerceAtLeast(0)
                }
            }
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

    /**
     * 丢掉当前 usbfs 通道、重新 `openDevice()` 拿一个新 fd，并重新 claim/SET_INTERFACE。
     *
     * 这是主机侧能做的最后一步复位：Android 没有暴露端口复位（`UsbDeviceConnection` 里既没有
     * `resetDevice()` 也没有 `clearHalt()`），新 fd + `SET_INTERFACE` 是唯一还能重建内核端点状态
     * 与数据翻转的办法。任何一步失败都只返回 null（由调用方继续按失败处理），绝不抛。
     */
    private fun reopenUsbConnection(device: UsbDevice, intf: UsbInterface): UsbDeviceConnection? {
        val reopen = reopenConnection ?: return null
        runCatching { connection?.releaseInterface(intf) }
        runCatching { connection?.close() }
        connection = null
        val fresh = runCatching { reopen() }.getOrElse { failure ->
            Timber.tag(LOG_TAG).w(failure, "USB 重新打开失败（openDevice 返回 null 或抛异常）")
            null
        } ?: return null
        val claimed = runCatching { fresh.claimInterface(intf, true) }.getOrDefault(false)
        if (!claimed) {
            Timber.tag(LOG_TAG).w("USB 重新打开后 claimInterface(0) 失败：接口可能仍被系统占用")
            runCatching { fresh.close() }
            return null
        }
        val setInterface = runCatching { fresh.setInterface(intf) }.getOrDefault(false)
        connection = fresh
        Timber.tag(LOG_TAG).w(
            "USB 重新打开：fd 已更换 claim=true setInterface=%s（内核端点状态与数据翻转已重建）",
            setInterface,
        )
        return fresh
    }

    /**
     * 清掉端点上的 `ENDPOINT_HALT`（USB 标准 `CLEAR_FEATURE`）。
     *
     * 为什么必须做：相机在数据段里被主机丢下（读超时后直接放弃事务）会把端点留成 halt，
     * 之后同一台设备上所有 bulk 传输立刻返回 -1，用户看到的是“必须拔数据线才能重连”。
     * 重新 open 时清一次，能把“上一条会话残留的 halt”消掉；清不掉也不影响正常流程，
     * 只是记录真值，供下一次真机日志定位。
     */
    private fun clearEndpointHalt(conn: UsbDeviceConnection, endpoint: UsbEndpoint): Boolean {
        val result = runCatching {
            conn.controlTransfer(
                USB_RECIP_ENDPOINT,
                USB_REQ_CLEAR_FEATURE,
                USB_FEATURE_ENDPOINT_HALT,
                endpoint.address,
                null,
                0,
                CLEAR_HALT_TIMEOUT_MS,
            )
        }.getOrElse { failure ->
            Timber.tag(LOG_TAG).w(failure, "CLEAR_FEATURE(ENDPOINT_HALT) ep=0x%02x 抛出", endpoint.address)
            return false
        }
        return result >= 0
    }

    /**
     * `bulk OUT` 的唯一出口：首包（`offset == 0`）写入失败时，先清一次端点 halt 再原样重试一次。
     *
     * 为什么只重试 `offset == 0`：短写循环里已经发出去的字节不能重发，否则容器边界错位；
     * 而「一个字节都没写出去就返回 -1」是干净的失败，重试不会重复任何数据。
     *
     * 真机证据（logs_1795787131379）：OUT 在 4 ms 内返回 -1，而 `CLEAR_FEATURE` 是被接受的，
     * 说明端点停在 halt（相机固件或主机控制器侧，Java API 拿不到 errno）。清完 halt 重试是
     * 唯一安全的补救；仍然失败就交给事务层判 fatal。
     */
    private fun bulkOut(
        conn: UsbDeviceConnection,
        endpoint: UsbEndpoint,
        buffer: ByteArray,
        offset: Int,
        length: Int,
        timeoutMs: Int,
        onStallCleared: (() -> Unit)? = null,
    ): Int {
        val first = conn.bulkTransfer(endpoint, buffer, offset, length, timeoutMs)
        // `length == 0` 是 ZLP：0 是它的**成功**返回值，不是 STALL，绝不能当失败重试
        // （重试会在帧尾多塞一个 0 长包，把容器边界推歪）。
        if (first > 0 || offset != 0 || length == 0) return first
        Timber.tag(LOG_TAG).w(
            "bulk OUT 首包写入失败（ep=0x%02x length=%d 返回 %d），清 halt 后重试一次",
            endpoint.address,
            length,
            first,
        )
        if (!clearEndpointHalt(conn, endpoint)) return first
        // 重写用短超时：清 halt 之后该端点在毫秒级就能接受这 12~24 字节；再等满 1.5 s 只是
        // 把「设备完全不收命令」拖成两倍时长，把连接预算烧光（真机 logs_1795826645352）。
        val retryTimeout = minOf(timeoutMs, STALL_RETRY_TIMEOUT_MS)
        val retried = conn.bulkTransfer(endpoint, buffer, offset, length, retryTimeout)
        // 重试成功说明「清 halt」确实解开了这条通道 —— 但第一次写已经把事务 deadline 吃掉了，
        // 必须续期，否则紧接着的读响应会立刻以「deadline 已过」失败（见
        // [OUT_RECOVERY_GRACE_MS] 的真机证据），把救回来的事务再判死一次。
        if (retried > 0) onStallCleared?.invoke()
        return retried
    }

    /**
     * 通道复位：把相机从「数据段没发完」的死结里拉回来。
     *
     * 真机证据（`logs_1795782688409`、`logs_1795787131379`、`logs_1795791570292`）：一次数据段
     * 被主机丢下之后，重新 open 只有两种结果——要么 12 字节命令写不进去（-1），要么命令写进去了
     * 但相机永不回应。前者是端点停住，后者是相机侧还挂着一个未完成的容器。
     *
     * 恢复梯子（每一步都落日志，因为 Java API 拿不到 errno，日志是唯一定性手段）：
     * 1. `GET_STATUS` 读两个端点的 halt 位（区分「相机自报 halt」与「主机侧状态未复位」）；
     * 2. `CLEAR_FEATURE(ENDPOINT_HALT)` 清 halt；
     * 3. **类请求 Cancel(0x64)**：让相机丢掉这条事务。它由设备控制器直接处理，不需要相机先能
     *    接收新的 PTP 命令（libgphoto2 `ptp_usb_control_cancel_request` 同款）——
     *    这正是「OUT 立即 -1、IN 永不回应」那种钉死状态下唯一的出路；
     * 4. **精确排空**：只读「本事务数据段还没读走的那部分」，读完后 [rx] 正好落在响应容器边界；
     * 5. 读响应容器：**读到即事务闭合、通道干净，到此为止**（正常取消走的就是这条路，
     *    不做任何复位 —— 复位会白白丢掉相机的会话状态）；
     * 6. 读不到 → `Get Device Status(0x67)` + 类请求 `Device Reset(0x66)` + 再清一次 halt；
     * 7. 仍读不到 → 最后手段：`SET_INTERFACE`（复位端点与 data toggle）+ PTP `DeviceReset(0x1003)`。
     *
     * 预算硬上限（**必须与 `CameraSession.CONNECT_BUDGET_MS` = 5 s 对账**，见下方常量注释）：
     * 排空 8 MiB / 1.5 s、控制传输 6 × 150 ms、响应读取 2 × 150 ms、最后一档 2 × 150 ms →
     * 最坏约 2.5 s，实测正常取消约 0.1 s（读到响应立刻返回）。
     * 保证「一条坏掉的连接」不会变成无限 USB 流量，也不会把一次成功的复位判成连接超时。
     *
     * @param transactionId 被中止事务的事务号。只有 `open()` 的补救复位拿不到事务号，传 `-1`：
     *   此时既不发 Cancel，也不校验响应的事务号（只校验容器结构）。
     *   事务路径（`transactionInternal` 的 catch）永远传真实事务号。
     * @return 仅当「没升级到设备级复位」「数据段按声明长度排空」「读到本事务的结构合法 RESPONSE」
     *   三者同时成立才为 `true`。任何一项不成立，调用方都必须维持 fatal —— 否则会把半死的通道
     *   当干净通道继续用，下一条命令立刻 `OUT -1`。
     */
    private fun resyncChannel(reason: String, transactionId: Long = -1L): Boolean {
        val conn = connection ?: return false
        val intf = usbInterface ?: return false
        val inEp = bulkIn ?: return false
        val outEp = bulkOut ?: return false

        val haltBefore = haltSummary(conn, inEp, outEp)
        val clearedIn = clearEndpointHalt(conn, inEp)
        val clearedOut = clearEndpointHalt(conn, outEp)

        // 3) 类请求取消：告诉相机「这条事务不要了」。
        val cancelled = if (transactionId >= 0) cancelTransaction(conn, intf, transactionId) else false

        // 4) 精确排空：rx 里属于本数据段的先丢掉，再按「声明长度 - 已读」把线上的读满。
        //
        // 这里必须用「payload 还差多少」而不是「读了多少 - 交了多少」：bulk IN 一次读 64 KiB，
        // 同一包里完全可能先装着数据段尾巴、紧接着就是下一条 RESPONSE 容器。若用后者，
        // 响应字节会被算进「数据段已读」，`rx.skip` 多跳一段，把已经读进来的响应当垃圾吃掉，
        // 于是 readResponseContainer 读不到 → 误判 escalated → 本可自愈的会话被判死（D1）。
        val pendingInRx = minOf((dataPhasePayload - dataPhaseDelivered).coerceAtLeast(0), rx.size)
        if (pendingInRx > 0) rx.skip(pendingInRx)
        val onWire = (dataPhasePayload - dataPhaseReadFromWire).coerceAtLeast(0)
        // 只知道「还差多少」时才能精确跳过：跳少了留下脏字节（下一条命令 OUT -1），
        // 跳多了会把响应容器当垃圾吃掉（真机 logs_1795791570292 的 drained=262144 就是
        // 上一版固定 4 × 64 KiB 撞到上限、8 MiB 数据段根本没跳完）。
        // 不知道长度时（预检路径没有容器头）不做猜测式排空 —— 真机那条路径 `rx=0 dataBytes=0`，
        // 线上根本没有残余，读了只会白吃一个超时。
        val drained = if (onWire > 0) drainExact(conn, inEp, onWire, reason) else 0L
        dataPhasePayload = 0
        dataPhaseReadFromWire = 0
        dataPhaseDelivered = 0

        // 5) 读响应容器：读到「本事务的、结构合法的响应」就是干净的收尾。
        var responseCode = readResponseContainer(conn, inEp, transactionId, reason)
        // 一旦升级到设备级复位，相机会重置事务号与会话状态，此后「通道能否继续用」无从证明，
        // 所以 escalated 之后无论读到什么，都不得向调用方宣称通道干净。
        var escalated = false
        var status = -2
        var classReset = false
        var setInterface: Boolean? = null
        var resetWritten = 0

        if (responseCode == null) {
            // 6) 相机没给响应：问状态 + 设备级复位（类请求，写不进去也不影响后续步骤）。
            escalated = true
            status = getDeviceStatus(conn, intf)
            classReset = classDeviceReset(conn, intf)
            clearEndpointHalt(conn, inEp)
            clearEndpointHalt(conn, outEp)
            // 复位后事务号已被相机重置，旧 txid 不能再用于校验，只认结构合法的响应。
            responseCode = readResponseContainer(conn, inEp, -1L, reason)
        }
        if (responseCode == null) {
            // 7) 最后手段：SET_INTERFACE 复位端点与 data toggle，再补一条 PTP DeviceReset。
            escalated = true
            setInterface = runCatching { conn.setInterface(intf) }.getOrElse { failure ->
                Timber.tag(LOG_TAG).w(failure, "通道复位（%s）：SET_INTERFACE 抛出", reason)
                false
            }
            resetWritten = writeDeviceReset(conn, inEp, outEp, reason)
        }

        rx.clear()
        inDataPhase = false
        dataPhaseRetries = 0
        // 排空是否真的读满：只读了一部分就把响应当干净的，等于把脏字节留给下一条命令。
        val drainComplete = onWire <= 0 || drained >= onWire.toLong()
        Timber.tag(LOG_TAG).i(
            RESYNC_LOG_FORMAT,
            reason,
            haltBefore,
            haltSummary(conn, inEp, outEp),
            cancelled,
            clearedIn,
            clearedOut,
            drained,
            onWire,
            drainComplete,
            status,
            classReset,
            setInterface?.toString() ?: "skipped",
            resetWritten,
            responseCode,
            escalated,
        )
        // 三个条件缺一不可：没升级设备级复位、数据段排干净、读到本事务的结构合法响应。
        return !escalated && drainComplete && responseCode != null
    }

    /**
     * 按**事务声明的长度**把数据段尾巴读满（libgphoto2 `ptp_usb_getdata` 的
     * `bytes_to_read = length - bytes_read`）。
     *
     * 为什么不能只排空一个固定上限：真机 `logs_1795791570292` 的 `drained=262144 reads=4`
     * 正好是上一版的上限，而中断的那条是 8 MiB 级整段下载 —— 排空被截断，相机仍留着几百 KiB
     * 没发完，下一条命令立刻 `OUT -1`。有界：8 MiB / 128 次 / 1.5 s。
     *
     * 请求长度按 [maxPacketSize] 向下取整（约束 2：bulk IN 的请求长度必须是最大包长的整数倍），
     * 只有尾段不足一个包时才按剩余字节请求。
     */
    private fun drainExact(
        conn: UsbDeviceConnection,
        inEp: UsbEndpoint,
        remaining: Int,
        reason: String,
    ): Long {
        if (remaining <= 0) return 0
        val scratch = ByteArray(READ_CHUNK)
        val deadline = now() + RESYNC_DRAIN_BUDGET_MS
        var drained = 0L
        var left = remaining.toLong()
        var reads = 0
        while (left > 0 && reads < RESYNC_DRAIN_READS && now() < deadline) {
            val cap = minOf(left, scratch.size.toLong()).toInt()
            val request = if (maxPacketSize > 0 && cap > maxPacketSize) {
                cap / maxPacketSize * maxPacketSize
            } else {
                cap
            }
            val read = runCatching { conn.bulkTransfer(inEp, scratch, request, RESYNC_TIMEOUT_MS) }
                .getOrElse { failure ->
                    Timber.tag(LOG_TAG).w(failure, "通道复位（%s）：排空读取抛出", reason)
                    -1
                }
            reads++
            if (read <= 0) break
            drained += read
            left -= read
        }
        return drained
    }

    /**
     * 排空之后读一个**结构合法**的响应容器，返回它的返回码；读不到合法响应返回 `null`。
     *
     * 为什么不能只数「读到多少字节」：`bulkTransfer > 0` 这个判据会被四类残留骗过 ——
     * 数据段没排完的尾巴、迟到的 DATA 容器、上一条被放弃事务的旧响应、bulk IN 上的 EVENT。
     * 只有「按声明长度读满的 RESPONSE 容器 + 事务号对得上」才能证明这条事务真的闭合；
     * 其余一律当未闭合，让调用方维持 fatal（否则会把半死不活的通道当干净通道继续用，
     * 下一条命令立刻 OUT -1 —— 真机 `logs_1795794239593` 23:42:28 就是这样连锁失败的）。
     *
     * 只做 [RESYNC_RESPONSE_READS] 次 `bulkTransfer`，**必须能返回**（上一版 16 × 300 ms 的教训）。
     *
     * @param expectedTxid 期望的事务号；`-1` 表示调用方拿不到（`open()` 的补救复位，
     *   或已经发过设备级复位之后），此时只校验容器结构。
     */
    private fun readResponseContainer(
        conn: UsbDeviceConnection,
        inEp: UsbEndpoint,
        expectedTxid: Long,
        reason: String,
    ): Int? {
        // 数据段尾巴和响应容器常常落在同一个 bulk IN 包里：取消/超时发生时，响应可能**已经在
        // `rx` 里**（`DataPhasePump` 只搬走声明长度的 payload，后面那段留在缓冲）。这时再去
        // USB 读只会白等 2×150 ms 并把本来已经闭合的事务误判成「收不干净」。
        bufferedResponseContainer(expectedTxid, reason)?.let { return it }
        val buffer = ByteArray(minOf(READ_CHUNK, MAX_CONTROL_CONTAINER_BYTES))
        var filled = 0
        var reads = 0
        while (reads < RESYNC_RESPONSE_READS) {
            val request = buffer.size - filled
            if (request <= 0) return null
            val read = runCatching { conn.bulkTransfer(inEp, buffer, filled, request, RESYNC_TIMEOUT_MS) }
                .getOrElse { failure ->
                    Timber.tag(LOG_TAG).w(failure, "通道复位（%s）：读响应抛出", reason)
                    -1
                }
            reads++
            if (read <= 0) {
                Timber.tag(LOG_TAG).w("通道复位（%s）：第 %d 次读响应返回 %d，通道未闭合", reason, reads, read)
                return null
            }
            filled += read
            openSawBytes = true
            val header = PtpContainerCodec.peekHeader(buffer, 0, filled) ?: continue
            if (header.type != PtpConstants.ContainerType.RESPONSE) {
                Timber.tag(LOG_TAG).w(
                    "通道复位（%s）：期望 RESPONSE 容器，实到 type=0x%02x（length=%d），判未闭合",
                    reason,
                    header.type,
                    header.length,
                )
                return null
            }
            if (header.length < PtpContainerCodec.HEADER_SIZE || header.length > buffer.size) {
                Timber.tag(LOG_TAG).w("通道复位（%s）：响应容器长度 %d 非法，判未闭合", reason, header.length)
                return null
            }
            if (expectedTxid >= 0 && header.transactionId != expectedTxid) {
                Timber.tag(LOG_TAG).w(
                    "通道复位（%s）：响应事务号 0x%x 与期望 0x%x 不符（旧响应残留），判未闭合",
                    reason,
                    header.transactionId,
                    expectedTxid,
                )
                return null
            }
            if (filled < header.length) continue
            val decoded = PtpContainerCodec.decodeNext(buffer, 0, filled)
            val container = (decoded as? PtpContainerCodec.Decoded.Container)?.container
            if (container == null) {
                Timber.tag(LOG_TAG).w("通道复位（%s）：响应容器解码失败 %s，判未闭合", reason, decoded)
                return null
            }
            Timber.tag(LOG_TAG).i(
                "通道复位（%s）：读到事务 0x%x 的响应 rc=0x%04x",
                reason,
                container.transactionId,
                container.code,
            )
            return container.code
        }
        return null
    }

    /**
     * 从 `rx` 里取一个**已经在缓冲里**的 RESPONSE 容器（数据段尾巴之后的响应）。
     *
     * 判据与 USB 路径完全一致：结构合法（[PtpContainerCodec.decodeNext] 只返回 `Decoded`，不抛）、
     * 按声明长度到齐、事务号匹配（`expectedTxid < 0` 只验结构）。
     * 命中才 `rx.skip`；未命中**不动** `rx` —— 残留字节由 [resyncChannel] 收尾时统一清掉。
     */
    private fun bufferedResponseContainer(expectedTxid: Long, reason: String): Int? {
        if (rx.size < PtpContainerCodec.HEADER_SIZE) return null
        val decoded = PtpContainerCodec.decodeNext(rx.buffer, rx.readOffset, rx.size)
        val container = (decoded as? PtpContainerCodec.Decoded.Container)?.container ?: return null
        if (container.type != PtpConstants.ContainerType.RESPONSE) return null
        if (expectedTxid >= 0 && container.transactionId != expectedTxid) {
            Timber.tag(LOG_TAG).w(
                "通道复位（%s）：缓冲内响应事务号 0x%x 与期望 0x%x 不符（旧响应残留），判未闭合",
                reason,
                container.transactionId,
                expectedTxid,
            )
            return null
        }
        rx.skip(decoded.consumed)
        Timber.tag(LOG_TAG).i(
            "通道复位（%s）：缓冲内已有事务 0x%x 的响应 rc=0x%04x（与数据段同包到达），无需再读 USB",
            reason,
            container.transactionId,
            container.code,
        )
        return container.code
    }
    /** 类请求 `Cancel(0x64)`：payload = 事件码 0x4001 + 事务号（MS PTP-over-USB）。 */
    private fun cancelTransaction(conn: UsbDeviceConnection, intf: UsbInterface, transactionId: Long): Boolean {
        val payload = ByteArray(CANCEL_PAYLOAD_BYTES)
        payload[0] = (PTP_EC_CANCEL_TRANSACTION and 0xFF).toByte()
        payload[1] = ((PTP_EC_CANCEL_TRANSACTION shr 8) and 0xFF).toByte()
        for (i in 0 until 4) {
            payload[2 + i] = ((transactionId shr (8 * i)) and 0xFF).toByte()
        }
        val result = runCatching {
            conn.controlTransfer(
                USB_TYPE_CLASS or USB_RECIP_INTERFACE or USB_DIR_OUT,
                USB_CLASS_REQ_CANCEL,
                0,
                intf.id,
                payload,
                payload.size,
                CLEAR_HALT_TIMEOUT_MS,
            )
        }.getOrElse { failure ->
            Timber.tag(LOG_TAG).w(failure, "类请求 Cancel(0x64) 抛出（相机可能不支持）")
            return false
        }
        return result >= 0
    }

    /** 类请求 `Device Reset(0x66)`：设备级复位，不需要相机能接收 PTP 命令。 */
    private fun classDeviceReset(conn: UsbDeviceConnection, intf: UsbInterface): Boolean {
        val result = runCatching {
            conn.controlTransfer(
                USB_TYPE_CLASS or USB_RECIP_INTERFACE or USB_DIR_OUT,
                USB_CLASS_REQ_DEVICE_RESET,
                0,
                intf.id,
                null,
                0,
                CLEAR_HALT_TIMEOUT_MS,
            )
        }.getOrElse { failure ->
            Timber.tag(LOG_TAG).w(failure, "类请求 DeviceReset(0x66) 抛出（相机可能不支持）")
            return false
        }
        return result >= 0
    }

    /** 类请求 `Get Device Status(0x67)`：返回状态字节；控制传输失败返回 -1。 */
    private fun getDeviceStatus(conn: UsbDeviceConnection, intf: UsbInterface): Int {
        val status = ByteArray(2)
        val result = runCatching {
            conn.controlTransfer(
                USB_TYPE_CLASS or USB_RECIP_INTERFACE or USB_DIR_IN,
                USB_CLASS_REQ_GET_DEVICE_STATUS,
                0,
                intf.id,
                status,
                status.size,
                CLEAR_HALT_TIMEOUT_MS,
            )
        }.getOrElse { failure ->
            Timber.tag(LOG_TAG).w(failure, "类请求 GetDeviceStatus(0x67) 抛出（相机可能不支持）")
            return -1
        }
        if (result < 0) return -1
        return status[0].toInt() and 0xFF
    }


    /**
     * 最后手段：写一条 PTP `DeviceReset(0x1003)`，并把它回的响应容器吃掉。
     *
     * **为什么必须吃掉响应**：`DeviceReset` 的响应是一个 12 字节 RESPONSE 容器。上一版写完不读，
     * 于是它留在了 bulk IN 上：下一次 `open()` 的第一条 `GetDeviceInfo` 很可能先把这条旧响应
     * 当成回包读走 → `PARSE_ERROR` 或超时（第六轮真机日志里 `resetWritten=12` 之后紧跟
     * `通道复位后重新预检：TIMEOUT` 正是这个形态）。libgphoto2 在这一步用的是类请求 0x66
     * （[classDeviceReset]，无响应容器），PTP 0x1003 只是它不可用时的兜底。
     *
     * 设备刚复位过，事务号不可信，所以只要求「结构合法的 RESPONSE」（`expectedTxid = -1`）。
     * 读不到也只记日志：本方法的返回值仍然只表示「命令是否写出去了」。
     *
     * @return `bulkTransfer` 的返回字节数（`<= 0` 表示没写出去）。
     */
    private fun writeDeviceReset(
        conn: UsbDeviceConnection,
        inEp: UsbEndpoint,
        outEp: UsbEndpoint,
        reason: String,
    ): Int {
        val reset = PtpContainerCodec.encodeCommand(DEVICE_RESET_OPCODE, DEVICE_RESET_TXID)
        val written = runCatching {
            conn.bulkTransfer(outEp, reset, reset.size, RESYNC_TIMEOUT_MS)
        }.getOrElse { failure ->
            Timber.tag(LOG_TAG).w(failure, "通道复位：DeviceReset 写入抛出")
            -1
        }
        if (written <= 0) return written
        val responseCode = readResponseContainer(conn, inEp, -1L, reason)
        Timber.tag(LOG_TAG).i(
            "通道复位（%s）：PTP DeviceReset 已写出（%d B），响应容器=%s",
            reason,
            written,
            responseCode?.let { "rc=0x" + it.toString(16).padStart(4, '0') } ?: "未读到",
        )
        return written
    }

    private fun haltSummary(conn: UsbDeviceConnection, inEp: UsbEndpoint, outEp: UsbEndpoint): String =
        "in=" + describeHalt(endpointHaltBit(conn, inEp)) + ",out=" + describeHalt(endpointHaltBit(conn, outEp))

    /**
     * 读端点 halt 位（USB `GET_STATUS`，recipient=endpoint，bit0 = Halt）。
     *
     * 这是本项目唯一能区分「相机固件 stall」与「主机控制器端点状态未复位」的观测点：
     * `bulkTransfer` 只返回 -1，拿不到 errno；`GET_STATUS` 至少能告诉你端点是否自报 halt。
     * 控制传输本身失败（设备已消失等）返回 -1，日志记作 `unknown`。
     */
    private fun endpointHaltBit(conn: UsbDeviceConnection, endpoint: UsbEndpoint): Int {
        val status = ByteArray(2)
        val result = runCatching {
            conn.controlTransfer(
                USB_RECIP_ENDPOINT or USB_DIR_IN,
                USB_REQ_GET_STATUS,
                0,
                endpoint.address,
                status,
                2,
                CLEAR_HALT_TIMEOUT_MS,
            )
        }.getOrElse { -1 }
        if (result < 0) return -1
        return status[0].toInt() and 0x01
    }

    private fun describeHalt(bit: Int): String = when (bit) {
        0 -> "clear"
        1 -> "halt"
        else -> "unknown"
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
            throw PtpException("传输层已置 fatal（上一次超时/NACK），请把相机电源关掉再打开后重新连接")
        }
        if (!busy.compareAndSet(false, true)) {
            throw IllegalStateException("同一个 UsbDeviceConnection 上出现了并发 bulk 收发（约束 3 禁止）")
        }

        val txid = txids.next()
        val startedAtMs = now()
        val deadline = TxDeadline(startedAtMs + timeoutMs.coerceAtLeast(1))
        lastTransactionRecovered = false
        inDataPhase = false
        dataPhaseRetries = 0
        dataPhasePayload = 0
        dataPhaseReadFromWire = 0
        dataPhaseDelivered = 0
        // 诊断起点由 deadline 反推，避免 diag == null 时为了算耗时多读一次单调时钟。
        val diagStartedAtMs = if (diag != null) startedAtMs else 0L
        var dataBytes = 0L
        var diagResponseCode = 0
        var diagCompleted = false
        var diagException: Throwable? = null
        // 提到 try 外面：取消路径（下面的 catch）要根据「命令是否已经写出去」决定要不要复位通道。
        var commandWritten = false
        // 同样提到 try 外面：catch 里要靠 `transactionClosed` 判断「事务是否已在协议层闭合」。
        // 闭合了就不许再发 Cancel / Device Reset —— 那只会把干净通道弄脏。
        var transactionClosed = false
        // 数据段里下游（Coil/全屏预览）抛出的异常：先记下来，把剩余 payload 读完、
        // 事务闭合之后再原样抛回调用方。真机 logs_1795830209506 09:41:50：全屏看照片按返回
        // → Coil 取消 GetObject(0x1009) → 主机只排空 0/7,996,940 字节、找不到响应
        // → 相机 USB 直接掉线（DETACHED），重连后继续失败；libgphoto2 的 ptp_usb_getdata
        // 同样是「读满声明长度再返回」，从不半路撒手。
        var downstreamFailure: Throwable? = null
        try {
            val inEp = bulkIn ?: throw PtpException("bulk IN 端点缺失（open() 未完成或端点被系统回收）")
            // 阶段顺序：命令容器 → DATA OUT 容器 → 读响应，txid 共用。
            val frames = BulkWriteFrames.build(op, txid, dataOut)
            emitDiag {
                onFrames(
                    opcode = op.code,
                    frames = frames.mapIndexed { index, frame ->
                        val header = PtpContainerCodec.peekHeader(frame)
                        PtpDiagFrame(
                            frameIndex = index,
                            type = header?.type ?: -1,
                            code = header?.code ?: -1,
                            transactionId = header?.transactionId ?: -1L,
                            length = header?.length ?: frame.size,
                        )
                    },
                )
            }
            writeFrames(conn, frames, deadline)
            commandWritten = true

            var sawDataPhase = false
            var response: PtpResponse? = null

            while (response == null) {

                // 约束 1：永远整包 64 KB 读，凑够 12 字节头再判断。
                while (rx.size < PtpContainerCodec.HEADER_SIZE) fill(conn, inEp, deadline)

                val header = PtpContainerCodec.peekHeader(rx.buffer, rx.readOffset, rx.size)
                    ?: throw PtpProtocolException("无法解析容器头（可读 ${rx.size} 字节）")
                // 又回到容器边界：数据段的重试预算重新给，避免长事务被前一次重试吃光。
                dataPhaseRetries = 0

                when (header.type) {
                    PtpConstants.ContainerType.DATA -> {
                        sawDataPhase = true
                        if (header.length < PtpContainerCodec.HEADER_SIZE) {
                            throw PtpProtocolException("数据容器长度 ${header.length} 小于 ${PtpContainerCodec.HEADER_SIZE}")
                        }
                        // 约束 3：先吃掉 12 字节头，再按声明长度流式搬运 payload。
                        rx.skip(PtpContainerCodec.HEADER_SIZE)
                        // 账目：本容器声明的长度，以及「凑头时顺带读进来」的那部分 payload。
                        // 中断时靠这两笔账算出「线上还欠多少」，见 resyncChannel 的第 4 步。
                        dataPhasePayload = header.payloadLength
                        dataPhaseReadFromWire = rx.size.coerceAtMost(header.payloadLength)
                        dataPhaseDelivered = 0
                        val downstream = dataSink
                        inDataPhase = true
                        try {
                            dataBytes += DataPhasePump.pump(
                                source = rx,
                                payloadLength = header.payloadLength,
                                staging = sinkBuf,
                                fill = { fill(conn, inEp, deadline) },
                                sink = { bytes, length ->
                                    // 下游第一次抛异常（取消 / 写盘失败 / 大小不符）之后不再喂它，
                                    // 但**继续读**：数据段必须读完，否则相机会停在「数据段未完成」，
                                    // 之后所有命令立刻失败（真机 logs_1795830209506 的 USB 掉线）。
                                    if (downstreamFailure == null) {
                                        try {
                                            downstream?.invoke(bytes, length)
                                        } catch (cancel: CancellationException) {
                                            // 只有「取消」才值得把数据段读完再抛（真机 09:41:50 的
                                            // USB 掉线）；协议违规 / 写盘失败 / 缓冲超限一律立刻中断：
                                            // 那种情况下声明的长度本身已不可信，继续读只是赌运气。
                                            downstreamFailure = cancel
                                        }
                                    }
                                    dataPhaseDelivered += length
                                },
                            )
                        } finally {
                            inDataPhase = false
                        }
                    }

                    PtpConstants.ContainerType.RESPONSE -> {
                        val container = readControlContainer(conn, inEp, deadline)
                        response = PtpResponse(
                            code = container.code,
                            params = container.payloadAsParams(),
                            dataBytes = dataBytes,
                        )
                        transactionClosed = true
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
            // 事务已经闭合（响应容器读到了），现在才把下游的失败原样抛回去。
            // 顺序很关键：先闭合、再抛，相机永远不会被留在「数据段未完成」，
            // 调用方拿到的仍是原本那个异常（CancellationException / 写盘失败 / 大小不符）。
            downstreamFailure?.let { failure ->
                Timber.tag(LOG_TAG).w(
                    "事务 0x%x 的数据段被下游打断（%s），已读完剩余 %d 字节并收下响应 rc=0x%x 后再抛出",
                    op.code,
                    failure.javaClass.simpleName,
                    dataBytes,
                    result.code,
                )
                throw failure
            }
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
            diagResponseCode = result.code
            diagCompleted = true
            return result
        } catch (t: PtpException) {
            // 通道层面的一切失败（超时 / 结构非法 / 短写 / 端点丢失 / 缓冲不足）都归这里。
            //
            // **命令已经写出去、响应却没读到** = 相机侧留着一个「半开事务」：响应容器还在设备
            // 缓冲区里没人取，下一条命令的 `bulk OUT` 就会被设备 NAK 到超时。真机
            // logs_1795794239593 的 23:42:28 → 23:42:30 正是这样一条一条连锁的：
            // 预检写超时 → 重试写成功但 deadline 已过、响应没读 → OpenSession 立刻 `OUT -1`
            // → 判 fatal → 之后每次重连都在同一条死结上打转，用户看到「完全连不上」。
            //
            // 但收尾本身要有「可证明」的前提，否则宁可直接判死：
            // - 命令必须已经**完整**写出（短写/端点丢失时相机手里只有半条命令，排空长度无从算起）；
            // - 只对**超时类**失败收尾（`PtpTimeoutException`）：这类失败时容器头是好的、数据段声明
            //   长度可信，排空与响应校验才能断定「事务闭合」。协议类错误
            //   （`PtpProtocolException`，容器头已不可信）与缓冲不足一律维持 fatal ——
            //   字节流都没法解释了，再读下去只是拿垃圾赌运气。
            val recoverable = commandWritten && !transactionClosed && !diagCompleted && t is PtpTimeoutException
            var channelClosed = false
            if (recoverable) {
                val recoveryStartedAt = now()
                channelClosed = runCatching {
                    resyncChannel(
                        "事务 0x" + op.code.toString(16) + " 失败后收尾（" + t.message + "）",
                        txid,
                    )
                }.getOrElse { failure ->
                    Timber.tag(LOG_TAG).w(failure, "失败事务的通道收尾抛出（忽略，按已尽力处理）")
                    false
                }
                // 记下来给 `open()`：预检失败若是「事务已自收尾」，那这段耗时同样不该算进连接预算。
                lastTransactionRecoveryMs = (now() - recoveryStartedAt).coerceAtLeast(0)
            }
            lastTransactionRecovered = channelClosed
            // 收尾读到响应容器 = 这条事务在协议层闭合，通道可以证明是干净的 → 不判死：
            // 上层把这一次操作当普通失败处理即可，会话继续可用，不必拔插相机。
            // 收不干净才置 fatal（约束 8：之后通道可能停在 halt，只能靠重连）。
            if (fatalOnFailure && !channelClosed) fatal = true
            // 始终输出（不依赖 T01b 诊断开关）：真机日志里“会话为何中断”必须能事后追。
            Timber.tag(LOG_TAG).w(
                t,
                "事务 0x%x 失败：%s（fatal=%s preflight=%s channelClosed=%s rx=%d dataBytes=%d）",
                op.code,
                t.message,
                fatal,
                !fatalOnFailure,
                channelClosed,
                rx.size,
                dataBytes,
            )
            diagException = t
            throw t
        } catch (cancel: CancellationException) {
            // 取消（Coil 缩略图请求被滑走、页面销毁、会话关闭）**不是通道故障**：不置 fatal。
            // 但它会把相机留在「事务未完成」里 —— 真机 logs_1795788978642 的 22:13:03 就是这样：
            // 58.9 MB 的整段下载在数据段中间被取消，rx 里残留 65536 字节，
            // 之后**所有**命令都 bulk OUT 立即 -1。
            //
            // 第五轮真机 logs_1795791570292 证明「只清 halt + 排空 256 KiB」不够：那条被中断的
            // 事务有 8 MiB 级的尾巴，排空被上限截断后相机仍然拒收新命令。所以这里走
            // [resyncChannel] 的完整梯子（类请求 Cancel → 按声明长度精确排空 → 读响应 →
            // 必要时 Device Reset），并把本事务号交给相机，让它主动丢掉这条事务。
            //
            // 判据是「命令是否已经写出去」：写出去之后相机必然在等一个数据段或响应，
            // 主机这时候撒手，那条响应就会变成下一条事务的「错位响应」——所以必须在本事务里
            // 把它读干净（或复位通道）。命令还没写完就被取消则什么都不用做。
            //
            // 复位放在 NonCancellable 里：本协程已经被取消，普通挂起点会立刻再抛一次。
            diagException = cancel
            // `transactionClosed == true` 表示响应容器已经读到、事务在协议层闭合：
            // 这种取消来自「数据段读完后再抛」，通道本来就是干净的，绝不能再去发 Cancel/复位。
            if (commandWritten && !transactionClosed) {
                withContext(NonCancellable) {
                    runCatching {
                        resyncChannel("事务 0x" + op.code.toString(16) + " 被取消（响应未读）", txid)
                    }.onFailure { Timber.tag(LOG_TAG).w(it, "取消后的通道复位抛出（忽略，按已尽力处理）") }
                }
            }
            throw cancel
        } catch (t: Throwable) {
            diagException = t
            throw t
        } finally {
            busy.set(false)
            emitDiag {
                onTransaction(
                    opcode = op.code,
                    transactionId = txid,
                    responseCode = if (diagCompleted) diagResponseCode else null,
                    dataBytes = dataBytes,
                    elapsedMs = now() - diagStartedAtMs,
                    isFatal = fatal,
                    isPreflight = !fatalOnFailure,
                    exceptionType = diagException?.javaClass?.simpleName,
                    exceptionMessage = diagException?.message,
                )
            }
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
            // 连续事件/噪声也必须归还唯一 IO 线程；假时钟不前进时由读取次数兜底。
            val deadline = now() + 8L
            var successfulReads = 0
            val request = intrMaxPacketSize.coerceIn(8, intrBuf.size)
            intrRx.reserveTail(request)
            while (successfulReads < 8) {
                val remaining = deadline - now()
                if (remaining <= 0) break
                // 没有事件时**不会自旋**：一次读不到就 break，不在 1~2 ms 的短超时上循环重试。
                val read = conn.bulkTransfer(ep, intrBuf, request, minOf(perRead.toLong(), remaining).toInt())
                if (read <= 0) break
                successfulReads += 1
                // 每次成功读取仍解完所有完整容器；半包保留到下一轮，不因预算耗尽清空。
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
    private fun fill(conn: UsbDeviceConnection, endpoint: UsbEndpoint, deadline: TxDeadline) {
        val tail = rx.reserveTail(READ_CHUNK)
        var request = minOf(READ_CHUNK, tail)
        request -= request % maxPacketSize
        if (request < maxPacketSize) {
            emitDiag {
                onFillRejected(tail = tail, maxPacketSize = maxPacketSize)
            }
            throw PtpException("rx 缓冲尾部空间不足（tail=$tail maxPacket=$maxPacketSize），无法按对齐长度读")
        }
        val timeout = remainingTimeoutMs(deadline.value)
        val read = conn.bulkTransfer(endpoint, readBuf, request, timeout)
        // 成功 / ZLP / 超时三条路径都在这里出真值；回调先于下面的抛异常，故 read < 0 也能采到。
        emitDiag {
            onBulkIn(
                request = request,
                read = read,
                maxPacketSize = maxPacketSize,
                endpointAddress = endpoint.address,
                isZlp = read == 0,
                isTimeout = read < 0,
            )
        }
        if (read < 0) {
            // 数据段里的读超时是「相机暂时喂不上」（解码 / 文件尾部 seek 都会这样），
            // 不能直接放弃：一旦中途丢下数据段，相机会卡在“事务未完成”，
            // 之后只能拔数据线才能重新连上（真机日志 2026-11-27 20:20 起完全对上）。
            if (inDataPhase && dataPhaseRetries < DATA_PHASE_RETRIES) {
                dataPhaseRetries += 1
                deadline.value = now() + DATA_PHASE_RETRY_MS
                Timber.tag(LOG_TAG).w(
                    "数据段 bulk IN 超时（ep=0x%x request=%d timeout=%dms），继续等待数据段（第 %d/%d 次）",
                    endpoint.address,
                    request,
                    timeout,
                    dataPhaseRetries,
                    DATA_PHASE_RETRIES,
                )
                return
            }
            // 重试用尽：**fill() 自己不做任何额外 USB I/O**，只抛异常。
            //
            // 「超时路径零额外 I/O」是既有契约（有单测锁住：这一次超时读之后必须马上抛出）。
            // 收尾统一交给 [transactionInternal] 的 catch（一次有界 resyncChannel）：
            // 它知道事务号、知道数据段声明长度，比这里就地猜要准确得多。
            throw PtpTimeoutException(
                "bulk IN 超时/失败（ep=0x${endpoint.address.toString(16)} request=$request timeout=${timeout}ms）",
            )
        }
        if (read == 0) {
            // 整包边界上的 0 长包（ZLP）：继续读，但不能再白等一轮。
            if (remainingTimeoutMs(deadline.value) <= 1) {
                throw PtpTimeoutException("bulk IN 只收到 ZLP，事务超时")
            }
            return
        }
        // 账目：数据段里这一包是「已经从线上读进 rx」的字节，取消时用来算线上还欠多少。
        // 上限截到 dataPhasePayload：一次 64 KiB 读可能把数据段尾巴和下一条 RESPONSE 容器
        // 一起读进来，若不截断，响应字节会被算成「数据段已读」，收尾时多跳、把响应吃掉（D1）。
        if (inDataPhase) {
            dataPhaseReadFromWire = minOf(dataPhasePayload, dataPhaseReadFromWire + read)
        }
        openSawBytes = true
        rx.append(readBuf, 0, read)
    }

    /**
     * 从 [rx] 里取一个完整的小容器（响应/事件），必要时继续读。
     * 超过 [MAX_CONTROL_CONTAINER_BYTES] 说明通道错位，直接判协议非法。
     */
    private fun readControlContainer(
        conn: UsbDeviceConnection,
        endpoint: UsbEndpoint,
        deadline: TxDeadline,
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
    private fun writeFrames(conn: UsbDeviceConnection, frames: List<ByteArray>, deadline: TxDeadline) {
        val outEp = bulkOut ?: throw PtpException("bulk OUT 端点缺失（open() 未完成或端点被系统回收）")
        // 清 halt 后重写成功 = 恢复耗时已经花掉，事务预算必须续期（见 [OUT_RECOVERY_GRACE_MS]）。
        // 用 maxOf 而不是直接覆盖：长事务（GET_OBJECT 30 s）的剩余预算不能被一张 1.5 s 的
        // 「续期」截短，否则救回来的大文件下载反而被当场判死。
        val onStallCleared: () -> Unit = {
            deadline.value = maxOf(deadline.value, now() + OUT_RECOVERY_GRACE_MS)
            Timber.tag(LOG_TAG).w(
                "bulk OUT 清 halt 后重写成功（ep=0x%02x），事务预算续期 %d ms",
                outEp.address,
                OUT_RECOVERY_GRACE_MS,
            )
        }
        if (diag == null) {
            BulkWriteFrames.writeAll(frames, maxPacketSize) { buffer, offset, length ->
                bulkOut(conn, outEp, buffer, offset, length, remainingTimeoutMs(deadline.value), onStallCleared)
            }
            return
        }
        var writeSequence = 0
        BulkWriteFrames.writeAll(frames, maxPacketSize) { buffer, offset, length ->
            val written = bulkOut(
                conn,
                outEp,
                buffer,
                offset,
                length,
                remainingTimeoutMs(deadline.value),
                onStallCleared,
            )
            writeSequence += 1
            // writeAll 的短写循环与帧末 ZLP 都会再次进入本 lambda —— 每次都记，不合并。
            emitDiag {
                onBulkOut(
                    frameIndex = frameIndexFor(frames, buffer),
                    writeSequence = writeSequence,
                    offset = offset,
                    length = length,
                    written = written,
                    isZlp = length == 0,
                )
            }
            written
        }
    }

    /**
     * 由 `BulkWriteFrames.writeAll` 传入的缓冲区实例反查帧序号：它始终把 `frames[i]` 本身
     * 传给 `write`（短写与 ZLP 补写复用同一实例），故引用相等即可定位，一次事务至多 2 帧。
     */
    private fun frameIndexFor(frames: List<ByteArray>, buffer: ByteArray): Int {
        for (i in frames.indices) {
            if (frames[i] === buffer) return i
        }
        return -1
    }

    /** 诊断与实参采集都在隔离边界内；关闭时直接返回，内联避免创建回调对象。 */
    private inline fun emitDiag(block: PtpDiagSink.() -> Unit) {
        val sink = diag ?: return
        try {
            sink.block()
        } catch (_: Throwable) {
            // 诊断失败不能丢失 USB 字节、修改 fatal，或覆盖事务原有返回值与异常。
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
    private suspend fun probeDeviceInfo(base: PtpDeviceCaps, timeoutMs: Int = DEVICE_INFO_TIMEOUT_MS): PtpDeviceCaps {
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
                timeoutMs = timeoutMs,
                fatalOnFailure = false,
            )
        } catch (c: CancellationException) {
            // 协程取消不是「预检失败」：不能把它归成 PARSE_ERROR 后继续重试预检，
            // 否则一次用户侧取消会变成两次真实 USB 往返（D2）。
            rx.clear()
            throw c
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
