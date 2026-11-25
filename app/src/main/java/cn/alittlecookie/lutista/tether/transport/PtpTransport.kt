package cn.alittlecookie.lutista.tether.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import cn.alittlecookie.lutista.tether.protocol.PtpConstants

/**
 * `open()` 内 `GetDeviceInfo` 预检的失败分类。
 *
 * 预检发生在 `OpenSession` **之前**，此时还没有「会话」这个概念，所以预检失败
 * **不等于**通道判死：它只产出诊断信息，不置 fatal（与「会话内事务超时」语义不同）。
 */
enum class PreflightFailure {
    /** bulk 超时 / 读写失败：相机没在合理时间内回 `GetDeviceInfo`。 */
    TIMEOUT,

    /** 相机明确回了非 `0x2001`。 */
    REJECTED,

    /** 回包解析失败（长度自校验不过 / 结构非法 / 通道错位）。 */
    PARSE_ERROR,
}

/**
 * 预检结果判定（纯函数，可 JVM 单测）。
 *
 * 优先级：先看异常类型，再看响应码。返回 null 表示预检成功。
 */
object PreflightOutcomes {
    fun resolve(responseCode: Int?, thrown: Throwable?): PreflightFailure? = when {
        thrown is PtpTimeoutException -> PreflightFailure.TIMEOUT
        // 任何其它异常（PtpParseException / PtpProtocolException / PtpException）都归到解析侧：
        // 共同点是「拿不到可信的 DeviceInfo」，对调用方的处置动作完全一样。
        thrown != null -> PreflightFailure.PARSE_ERROR
        responseCode == null -> null
        responseCode != PtpConstants.Rc.OK -> PreflightFailure.REJECTED
        else -> null
    }
}

/**
 * 设备能力（`GetDeviceInfo` + USB 描述符的合并结果）。
 *
 * u32 一律用 Long 承载（`and 0xFFFF_FFFFL`），避免 Int 负数回归：handle / storageId / format 全部 Long。
 *
 * @property vendorExtensionId S1 = `0x1C`（Panasonic）；`0x00` 或解析异常 → 未知机型形态。
 * @property operationsSupported ⚠️ **不是白名单**：S1 声明的 19 条里没有任何一条私有码，
 *   而 `0x9402/0x9403/0x9108/0x9404/0x9412/0x9706` 全部实测可用。只能正向使用。
 * @property devicePropertiesSupported S1 仅 `{0x5001}`。
 * @property bcdUsb `0x210` = USB2 HS，`0x310` = USB3.1 SS（仅诊断，代码不按它分支）。
 * @property bulkMaxPacketSize 512 / 1024，**只用于下载读长度对齐**（不对齐第二个包读不动）。
 * @property usbVendorId USB 描述符里的 VID（S1 = `0x04DA`），Profile 匹配用。
 *   **解析器（DeviceInfoParser）填 0，真实值由 `PtpUsbTransport.open()` 从 `UsbDevice` 覆盖**；
 *   `open()` 结束前会断言它非 0（0 = 忘了覆盖，属于必须暴露的 bug）。
 * @property usbProductId USB 描述符里的 PID（S1 = `0x2382`），同上。
 * @property preflightFailure 只有 `open()` 返回的 caps 会带它：null = 预检成功；
 *   非 null 时 caps 里的能力字段是「纯 USB 描述符版本」，供诊断用，**不能当作会话可用**。
 */
data class PtpDeviceCaps(
    val vendorExtensionId: Long,
    val manufacturer: String,
    val model: String,
    val deviceVersion: String,
    val serialNumber: String,
    val operationsSupported: Set<Long>,
    val eventsSupported: Set<Long>,
    val devicePropertiesSupported: Set<Long>,
    val imageFormats: Set<Long>,
    val bcdUsb: Int,
    val bulkMaxPacketSize: Int,
    val usbVendorId: Int = 0,
    val usbProductId: Int = 0,
    val preflightFailure: PreflightFailure? = null,
)

/** 一次 PTP 操作（opcode + 最多 5 个 u32 参数）。 */
data class PtpOp(val code: Int, val params: LongArray = LongArray(0)) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PtpOp) return false
        return code == other.code && params.contentEquals(other.params)
    }

    override fun hashCode(): Int = 31 * code + params.contentHashCode()

    override fun toString(): String = "PtpOp(0x${code.toString(16)}, ${params.joinToString { it.toString() }})"
}

/**
 * 相机事件。
 *
 * @property code 事件码（只消费 `0xC108`；`0xC101/0xC107/0x4006` 是噪声/状态）。
 * @property params u32 参数（`0xC108` 的 `params[0]` = 新对象 handle）。
 * @property raw **规范化后的容器字节**：由 `code + params` 重新编码得到，与线上收到的字节等价
 *   （同 code、同 params、txid 恒 `0xFFFFFFFF`），但**不是** UART/URB 原始字节——不保留
 *   接收缓冲区里的填充或分片痕迹。诊断够用，不要拿它做逐字节比对。
 */
data class PtpEvent(val code: Int, val params: LongArray, val raw: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PtpEvent) return false
        return code == other.code && params.contentEquals(other.params) && raw.contentEquals(other.raw)
    }

    override fun hashCode(): Int {
        var result = code
        result = 31 * result + params.contentHashCode()
        result = 31 * result + raw.contentHashCode()
        return result
    }
}

/**
 * 一次事务的响应。
 *
 * @property code 响应码（`0x2001` = OK）。
 * @property params 响应参数（u32）。
 * @property dataBytes 数据阶段**实际**收到的 payload 字节数（不是容器头声明值）。
 */
data class PtpResponse(
    val code: Int,
    val params: LongArray = LongArray(0),
    val dataBytes: Long = 0L,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PtpResponse) return false
        return code == other.code && params.contentEquals(other.params) && dataBytes == other.dataBytes
    }

    override fun hashCode(): Int = (31 * code + params.contentHashCode()) * 31 + dataBytes.hashCode()

    override fun toString(): String = "PtpResponse(0x${code.toString(16)}, dataBytes=$dataBytes)"
}

/** 传输层异常基类。 */
open class PtpException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * bulk 超时 / 读写失败。
 *
 * 语义是 **fatal**：通道可能已经停在 halt，必须拔插相机（定稿 §6 约束 8）。
 * 传输层会同时把内部 `fatal` 置位，之后的所有事务直接抛异常，不做 `clear_halt` 硬重试。
 */
class PtpTimeoutException(message: String, cause: Throwable? = null) : PtpException(message, cause)

/** 回包结构非法（长度字段/容器类型不可信）。缓冲区已不可信，调用方必须丢弃该连接。 */
class PtpProtocolException(message: String, cause: Throwable? = null) : PtpException(message, cause)

/**
 * transport 层唯一出口：持有 [UsbDeviceConnection]、64 KB rx 缓冲、事件端点。
 *
 * ## 调用方必须遵守的 7 条硬约束（每条都对应真机事故）
 *
 * 1. **bulk IN 一律 64 KB 缓冲整包读**。禁止按容器头 12 字节精确读——那会让相机 IN FIFO 卡死
 *    且跨 App 重启不恢复。读长度按 `maxPacketSize`（512/1024）对齐。
 * 2. **同一个 [UsbDeviceConnection] 不能多线程并发收发**。并发 `bulkTransfer` 全部返回 -1。
 *    事件端点只能在同线程内联排空，不要开线程池。
 * 3. `expectedData = false` 但相机仍发数据时，实现**照常把数据收完再返回**，否则 FIFO 卡死。
 * 4. **所有 USB I/O 不得在主线程**：本接口所有方法都必须在 `ptp-io` 线程调用，
 *    实现里做线程断言（debug 抛异常，release 计数打点）。
 * 5. 超时/NACK 之后通道可能已停在 halt：[drainEvents] 只作诊断，**不要**用 `clear_halt` 硬重试"自愈"。
 * 6. `0x1007` **不能作为 `OpenSession` 后第一条命令**（4 次复现：读容器超时 4.08~4.18 s，
 *    之后所有命令写失败，只能拔插 USB 恢复）。正确顺序：
 *    `OpenSession → GetDeviceInfo(0x1001) 或 GetStorageIDs(0x1004) → 再发 0x1007`。
 *    这是**调用方（session 层）的义务**，transport 层不做拦截，但 KDoc 写死在这里避免踩坑。
 * 7. 事件 `txid` 恒为 `0xFFFFFFFF`（相机主动事件不带事务号），解码时不要用它做校验。
 */
interface PtpTransport {

    /**
     * 打开设备：claim interface 0、取三个端点（bulkIn `0x81` / bulkOut `0x01` / intrIn `0x82`）、
     * 读 USB 描述符，并尝试发一次 `GetDeviceInfo` 预检以补齐 [PtpDeviceCaps]。
     *
     * **预检失败不置 fatal**（预检发生在 `OpenSession` 之前，还没有「会话」概念）：
     * 返回的 caps 会带 `preflightFailure != null`，能力字段是「纯 USB 描述符版本」。
     * 但预检失败 **≠ 通道可用**，调用方（session 层）必须：
     * 1. 把紧随其后的 `OpenSession` 视为**可能失败**；
     * 2. 失败时直接提示用户拔插重连，**不要重试**（重试只会加深通道错位）；
     * 3. 预检成功后若想拿到可信能力表，仍需在 `OpenSession` 之后再取一次 `GetDeviceInfo`
     *    （那里失败就按会话内事务处理：超时即 fatal）。
     *
     * 实现会在返回前断言 `usbVendorId`/`usbProductId` 已被 [UsbDevice] 覆盖为非 0
     * （0 表示忘了覆盖，属于必须暴露的程序 bug）。
     *
     * **必须在 IO 线程调用**；换线程立即抛 [IllegalStateException]（debug）。
     */
    suspend fun open(device: UsbDevice, connection: UsbDeviceConnection): PtpDeviceCaps

    /** 只 releaseInterface + close，幂等，不发 `CloseSession`（那是 session 层的事）。必须在 IO 线程调用。 */
    suspend fun close(timeoutMs: Int = 1_000)

    /**
     * 执行一次完整 PTP 事务（命令 → 可选 DATA OUT → 可选 DATA IN → 响应）。**必须在 IO 线程调用。**
     *
     * 阶段顺序**不可调换**（PTP 规定）：
     * ```
     * [命令容器] → [DATA OUT 容器] → [DATA IN 容器 / 响应容器]
     * ```
     * 先写 data 再写命令会让相机把 DATA 当命令头解析，通道立即错位。
     *
     * 实现要点：
     * - [dataOut] 非空且非空数组时，紧跟在命令容器之后写一个 DATA 容器，
     *   **txid 与命令容器同一个值**（PTP 要求同一事务的所有容器共享 txid）；
     * - `dataOut` 为 `null` 或 `size == 0` **不发** DATA 容器 —— PTP 里空 DATA 阶段视为不存在；
     *   S1 上「空 DATA 容器」未实测，保守按「不发」处理；
     * - 写阶段沿用同一套 ZLP 规则：容器长度恰为 `maxPacketSize` 整数倍时补 0 长包；
     * - **短写必须抛异常**：写入不完整却继续读响应，会让后续所有容器的边界错位；
     * - 数据阶段按容器头声明长度**流式**回调 [dataSink]，86 MB 对象不会被整包读进内存；
     * - [expectedData] 只用于诊断/断言：**即使为 false，只要相机发数据也照常收完**（约束 3）；
     * - 会话内超时/NACK/结构非法 → 立即置 fatal 并抛异常，**不重试、不 clear_halt**；
     * - 调用方约束：`0x1007` 不能是本会话 `OpenSession` 之后的第一条命令（约束 6）。
     *
     * @param expectedData 是否预期有数据阶段（IN）。
     * @param dataOut 命令之后要发送的 DATA OUT payload（写属性 `0x9403`、写取景格式 `0x9415`、
     *   `0x1016` 等用）；给 `null` 或空数组表示没有 OUT 数据阶段。
     * @param dataSink 数据接收器 `(chunk, len)`，chunk 的有效数据从下标 0 开始且**只在回调内有效**
     *   （实现复用同一块缓冲）；给 null 表示丢弃但仍整包收。
     * @param timeoutMs 单次事务超时（默认 4 s）；超时即 fatal，不重试。
     */
    suspend fun transaction(
        op: PtpOp,
        expectedData: Boolean = false,
        dataOut: ByteArray? = null,
        dataSink: ((ByteArray, Int) -> Unit)? = null,
        timeoutMs: Int = DEFAULT_TRANSACTION_TIMEOUT_MS,
    ): PtpResponse

    /**
     * 排空中断端点并解出事件。**必须在 IO 线程调用。**
     *
     * [timeoutMs] 必须 1~2 ms（40 ms 会白吃 80 ms）。实现会把它夹在 `1..2`。
     * 只作诊断/事件泵用：**不要**用「drain 干净了」当作可以继续发命令的依据（约束 5）。
     */
    suspend fun drainEvents(timeoutMs: Int = 2): List<PtpEvent>

    companion object {
        /** 单次事务默认超时（ms）。超时 = fatal，不重试。 */
        const val DEFAULT_TRANSACTION_TIMEOUT_MS: Int = 4_000
    }
}
