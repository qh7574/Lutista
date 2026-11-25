package cn.alittlecookie.lutista.tether.transport

import cn.alittlecookie.lutista.tether.protocol.PtpContainerCodec

/**
 * 一次事务的**发送阶段**：帧构造 + 写序列（纯逻辑，可 JVM 单测）。
 *
 * 抽出来的理由与 `DataPhasePump` 相同：真正的 `bulkTransfer` 在单测里无法运行
 * （`UsbDeviceConnection` 是 android.jar 的 stub），但「写什么、按什么顺序写、要不要补 ZLP、
 * 短写怎么处理」这四件事决定了通道会不会错位，必须可断言。
 *
 * PTP 的阶段顺序不可调换：
 * ```
 * [命令容器] → [DATA OUT 容器] → 读响应
 * ```
 * 同一事务的所有容器共享同一个 txid。
 */
internal object BulkWriteFrames {

    /**
     * 构造一次事务要写的容器序列。
     *
     * - [dataOut] 为 `null` 或空数组 → 只有命令容器（空 DATA 阶段视为不存在）；
     * - 否则追加一个 DATA 容器，`code = op.code`（数据容器回显 opcode）、`txid` 与命令相同。
     */
    fun build(op: PtpOp, transactionId: Long, dataOut: ByteArray?): List<ByteArray> {
        val command = PtpContainerCodec.encodeCommand(op.code, transactionId, op.params)
        if (dataOut == null || dataOut.isEmpty()) return listOf(command)
        val data = PtpContainerCodec.encodeData(op.code, transactionId, dataOut)
        return listOf(command, data)
    }

    /**
     * 按顺序把 [frames] 写完。
     *
     * 规则（每条都对应一次真机事故）：
     * 1. **短写必须循环写完**，返回非正数则抛 [PtpTimeoutException]（写不完整还去读响应 = 边界错位）；
     * 2. 某帧长度恰为 [maxPacketSize] 整数倍时，在本帧之后补一个 0 长写（ZLP），
     *    否则相机会等剩余的包；空帧本身不写（避免无意义的 0 长写）。
     *
     * @param write 实际写入函数 `(buffer, offset, length) -> 实际写入字节数`；0 长写表示 ZLP。
     * @return **写入的容器总字节数（含每个容器的 12 B 头）**，不是 payload 字节数。
     *   生产调用方不使用该值（`PtpUsbTransport.writeFrames` 忽略它），保留只为单测能断言"写全了"。
     */
    fun writeAll(
        frames: List<ByteArray>,
        maxPacketSize: Int,
        write: (buffer: ByteArray, offset: Int, length: Int) -> Int,
    ): Int {
        require(maxPacketSize > 0) { "maxPacketSize 必须为正：$maxPacketSize" }
        var total = 0
        for (frame in frames) {
            if (frame.isEmpty()) continue
            var offset = 0
            while (offset < frame.size) {
                val written = write(frame, offset, frame.size - offset)
                if (written <= 0) {
                    throw PtpTimeoutException(
                        "bulk OUT 写入失败（已写 $offset/${frame.size} 字节，返回 $written）",
                    )
                }
                offset += written
            }
            total += frame.size
            if (frame.size % maxPacketSize == 0) {
                val zlp = write(frame, 0, 0)
                if (zlp < 0) {
                    throw PtpTimeoutException("bulk OUT 的 0 长包（ZLP）写入失败（返回 $zlp）")
                }
            }
        }
        return total
    }
}
