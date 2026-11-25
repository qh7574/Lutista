package cn.alittlecookie.lutista.tether.protocol

import cn.alittlecookie.lutista.tether.protocol.PtpConstants.ContainerType

/**
 * 一个已解析的 PTP 容器头（12 字节，全小端）。
 *
 * @property length 整个容器的字节数（含 12 字节头）。
 * @property type 容器类型，取值见 [ContainerType]。
 * @property code 命令容器 = opcode；数据容器 = 所属 opcode 的 echo；响应容器 = 响应码；事件容器 = 事件码。
 * @property transactionId u32，用 Long 承载（`and 0xFFFF_FFFFL`）；相机主动事件恒为 `0xFFFFFFFF`。
 */
data class PtpContainerHeader(
    val length: Int,
    val type: Int,
    val code: Int,
    val transactionId: Long,
) {
    /** payload（数据段）字节数。 */
    val payloadLength: Int get() = length - PtpConstants.CONTAINER_HEADER_SIZE
}

/**
 * 一个完整容器（payload 已从接收缓冲区拷贝出来）。
 *
 * 大对象下载**不要**走这里——86 MB 的 DATA 容器必须靠 [PtpContainerHeader.payloadLength]
 * 边读边写盘，见 `PtpUsbTransport.transaction` 的流式分支。
 */
data class PtpContainer(
    val header: PtpContainerHeader,
    val payload: ByteArray,
) {
    val type: Int get() = header.type
    val code: Int get() = header.code
    val transactionId: Long get() = header.transactionId

    /** 把 payload 按 u32 数组解析（响应容器的参数段）。长度不是 4 的整数倍时向下取整。 */
    fun payloadAsParams(): LongArray = PtpContainerCodec.parseParams(payload)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PtpContainer) return false
        return header == other.header && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = 31 * header.hashCode() + payload.contentHashCode()
}

/**
 * PTP 容器的编解码（纯函数，零状态，可 JVM 单测）。
 *
 * 三类使用姿势：
 * 1. 发送：`encodeCommand` / `encodeResponse` / `encodeData` / `encodeEvent`。
 * 2. 接收小容器：`decodeNext` / `decodeAll`（返回 consumed，不丢残留字节）。
 * 3. 接收大对象：先 `peekHeader` 拿 `payloadLength`，再按长度流式搬运 payload
 *    （不要用 `decodeNext` 等整个容器到齐）。
 *
 * 关于「粘包 / 半包」：
 * - 缓冲区里同时有多个容器 → `decodeAll` 依次解出，`consumed` 只覆盖真正解完的部分。
 * - 只收到半个容器 → 返回 [Decoded.Incomplete]，`consumed` 为 0，残留字节由调用方保留。
 */
object PtpContainerCodec {

    /** 容器头字节数。 */
    const val HEADER_SIZE: Int = PtpConstants.CONTAINER_HEADER_SIZE

    /** u32 掩码。 */
    const val MAX_U32: Long = 0xFFFF_FFFFL

    /** 单次解码结果。 */
    sealed interface Decoded {
        /** 解出一个完整容器。[consumed] 为本次前进的字节数（= 容器长度）。 */
        data class Container(val container: PtpContainer, val consumed: Int) : Decoded

        /** 数据不够（半个容器），需要继续读。 */
        data object Incomplete : Decoded

        /** 结构非法（长度字段 < 12、类型未知等），缓冲区已不可信。 */
        data class Corrupt(val reason: String) : Decoded
    }

    /** [decodeAll] 的返回值。 */
    data class DecodeAllResult(val containers: List<PtpContainer>, val consumed: Int)

    // ────────────────────────────── 解码 ──────────────────────────────

    /**
     * 只读容器头（不要求 payload 到齐）。
     *
     * @return 头部；可用字节不足 12 时返回 null。
     */
    fun peekHeader(
        buffer: ByteArray,
        offset: Int = 0,
        available: Int = buffer.size - offset,
    ): PtpContainerHeader? {
        if (offset < 0 || available < HEADER_SIZE || offset + HEADER_SIZE > buffer.size) return null
        val rawLength = readU32(buffer, offset)
        // 合法的 payload 不会超过 2 GB；超过即非法容器（用 Int 承载便于后续下标运算）。
        val length = if (rawLength > Int.MAX_VALUE.toLong()) -1 else rawLength.toInt()
        return PtpContainerHeader(
            length = length,
            type = readU16(buffer, offset + 4),
            code = readU16(buffer, offset + 6),
            transactionId = readU32(buffer, offset + 8),
        )
    }

    /**
     * 尝试解出缓冲区里的**下一个**完整容器。
     *
     * 注意：payload 可能远超缓冲区容量（86 MB 的 MOV），此时本方法永远返回 [Decoded.Incomplete]，
     * 调用方应改用 `peekHeader` + 按长度流式搬运。
     */
    fun decodeNext(
        buffer: ByteArray,
        offset: Int = 0,
        available: Int = buffer.size - offset,
    ): Decoded {
        val header = peekHeader(buffer, offset, available) ?: return Decoded.Incomplete
        if (header.length < HEADER_SIZE) {
            return Decoded.Corrupt("容器长度 ${header.length} 小于头长 $HEADER_SIZE")
        }
        if (header.length > available) return Decoded.Incomplete
        // 防御：调用方把 available 传大了（谎报可用字节）时，不能让 copyOfRange 抛 AIOOBE
        // ——本方法的契约是「只返回 Decoded，不抛异常」。这种情形本质仍是「数据不够」，
        // 返回 Incomplete 让调用方继续读；生产路径里 available 恒等于真实可读字节，走不到这里。
        if (offset + header.length > buffer.size) return Decoded.Incomplete
        val payload = buffer.copyOfRange(offset + HEADER_SIZE, offset + header.length)
        return Decoded.Container(PtpContainer(header, payload), header.length)
    }

    /**
     * 解出缓冲区里所有完整容器；遇到半个容器就停下并保留其字节（[DecodeAllResult.consumed]）。
     * 遇到非法容器抛 [PtpParseException]（缓冲区已不可信，调用方必须丢弃整个连接状态）。
     */
    fun decodeAll(
        buffer: ByteArray,
        offset: Int = 0,
        available: Int = buffer.size - offset,
    ): DecodeAllResult {
        val out = ArrayList<PtpContainer>(4)
        var cursor = 0
        while (true) {
            when (val decoded = decodeNext(buffer, offset + cursor, available - cursor)) {
                is Decoded.Container -> {
                    out += decoded.container
                    cursor += decoded.consumed
                    if (cursor >= available) break
                }

                Decoded.Incomplete -> break
                is Decoded.Corrupt -> throw PtpParseException("容器结构非法：${decoded.reason}")
            }
        }
        return DecodeAllResult(out, cursor)
    }

    // ────────────────────────────── 编码 ──────────────────────────────

    /** 命令容器。 */
    fun encodeCommand(
        opcode: Int,
        transactionId: Long,
        params: LongArray = LongArray(0),
    ): ByteArray = buildContainer(
        type = ContainerType.COMMAND,
        code = opcode,
        transactionId = transactionId,
        payload = paramsToPayload(params),
    )

    /** 数据容器（[offset]/[length] 允许只取源数组的一段，避免大对象二次拷贝）。 */
    fun encodeData(
        opcode: Int,
        transactionId: Long,
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size - offset,
    ): ByteArray {
        require(offset >= 0 && length >= 0 && offset + length <= data.size) {
            "数据段范围非法：offset=$offset length=$length size=${data.size}"
        }
        val payload = if (offset == 0 && length == data.size) data else data.copyOfRange(offset, offset + length)
        return buildContainer(ContainerType.DATA, opcode, transactionId, payload)
    }

    /** 响应容器（主机侧只在自测/单测里构造）。 */
    fun encodeResponse(
        responseCode: Int,
        transactionId: Long,
        params: LongArray = LongArray(0),
    ): ByteArray = buildContainer(
        type = ContainerType.RESPONSE,
        code = responseCode,
        transactionId = transactionId,
        payload = paramsToPayload(params),
    )

    /** 事件容器（txid 恒为 `0xFFFFFFFF`）。 */
    fun encodeEvent(
        eventCode: Int,
        params: LongArray = LongArray(0),
        transactionId: Long = PtpConstants.EVENT_TRANSACTION_ID,
    ): ByteArray = buildContainer(
        type = ContainerType.EVENT,
        code = eventCode,
        transactionId = transactionId,
        payload = paramsToPayload(params),
    )

    // ────────────────────────────── 工具 ──────────────────────────────

    /** 小端 u16（无符号）。 */
    fun readU16(buffer: ByteArray, offset: Int): Int =
        (buffer[offset].toInt() and 0xFF) or ((buffer[offset + 1].toInt() and 0xFF) shl 8)

    /** 小端 u32（无符号，用 Long 承载）。 */
    fun readU32(buffer: ByteArray, offset: Int): Long =
        (readU16(buffer, offset).toLong()) or (readU16(buffer, offset + 2).toLong() shl 16)

    /** 写小端 u16。 */
    fun writeU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    /** 写小端 u32。 */
    fun writeU32(target: ByteArray, offset: Int, value: Long) {
        writeU16(target, offset, (value and 0xFFFFL).toInt())
        writeU16(target, offset + 2, ((value ushr 16) and 0xFFFFL).toInt())
    }

    /** 把 payload 按 u32 数组解析；尾部不足 4 字节的部分忽略（正常相机不会出现）。 */
    fun parseParams(payload: ByteArray): LongArray {
        val count = payload.size / 4
        val out = LongArray(count)
        for (i in 0 until count) out[i] = readU32(payload, i * 4)
        return out
    }

    /** 参数数组 → payload 字节。 */
    fun paramsToPayload(params: LongArray): ByteArray {
        if (params.isEmpty()) return EMPTY
        val out = ByteArray(params.size * 4)
        for (i in params.indices) writeU32(out, i * 4, params[i] and MAX_U32)
        return out
    }

    private val EMPTY = ByteArray(0)

    private fun buildContainer(
        type: Int,
        code: Int,
        transactionId: Long,
        payload: ByteArray,
    ): ByteArray {
        val total = HEADER_SIZE + payload.size
        val out = ByteArray(total)
        writeU32(out, 0, total.toLong())
        writeU16(out, 4, type)
        writeU16(out, 6, code and 0xFFFF)
        writeU32(out, 8, transactionId and MAX_U32)
        if (payload.isNotEmpty()) System.arraycopy(payload, 0, out, HEADER_SIZE, payload.size)
        return out
    }
}

/**
 * PTP transaction id 生成器。
 *
 * 规则（PTP 规范 + 定稿 §1.4）：
 * - 从 1 开始自增；
 * - `0xFFFFFFFF` 是相机主动事件的专用值，**永远不能分配给主机事务**；
 * - `0` 也是非法事务号；
 * - 回绕：`0xFFFFFFFE` 的下一个是 `1`。
 *
 * 该类只被单线程（`ptp-io`）使用的可能性最大，但仍加锁，避免误用时静默产生重复 txid。
 */
class PtpTransactionIdGenerator(start: Long = 1L) {

    private val lock = Any()
    private var next: Long = normalize(start)

    /** 取下一个事务号并前进。 */
    fun next(): Long = synchronized(lock) {
        val current = next
        next = increment(current)
        current
    }

    /** 预读下一个事务号（不自增）。 */
    fun peek(): Long = synchronized(lock) { next }

    /** 重置到 [start]（新会话使用）。 */
    fun reset(start: Long = 1L) = synchronized(lock) { next = normalize(start) }

    private fun normalize(value: Long): Long {
        val masked = value and PtpContainerCodec.MAX_U32
        return if (masked == 0L || masked == PtpConstants.EVENT_TRANSACTION_ID) 1L else masked
    }

    private fun increment(value: Long): Long {
        val raw = (value + 1) and PtpContainerCodec.MAX_U32
        return if (raw == 0L || raw == PtpConstants.EVENT_TRANSACTION_ID) 1L else raw
    }
}
