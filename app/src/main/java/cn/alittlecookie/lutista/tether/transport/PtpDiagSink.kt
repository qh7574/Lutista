package cn.alittlecookie.lutista.tether.transport

/**
 * [PtpUsbTransport] 的**可选 USB 诊断采集器**（T01b）。
 *
 * ## 定位
 * 只把 transport 私有方法内部的**运行时真值**以原始类型交给消费方，用于补齐
 * `.docs/联机模式-T01-真机快速验证手册-2026-11-26.md` §3.5「路径 B 最小入口」8 类日志中的
 * **#3（`fill()` 的 request / read / ZLP）**、**#4（写方向帧长 / 短写循环 / ZLP）**、
 * **#5（`[CMD][DATA]` 容器真值）**，以及 #6 的事务级收支（rc / dataBytes / 耗时 / 异常）。
 * 这三类在零改 T01 时只能靠镜像重建推断，TV-07 / TV-08 因此一直 BLOCKED。
 *
 * 以下字段**不属于本钩子**，由 T02 的 L1 装饰器 `PtpDiagTransport` 与调度层取得：
 * #1 `PtpDeviceCaps` 全字段（含 `preflightFailure`）由 `PtpDiagTransport.open()` 从 `open()` 返回值打印，
 * 不在本接口范围；#2 三端点地址与 `isOpen/isFatal` 状态变化、
 * #7 `drainEvents` 事件与 `discardedEventContainers` / 未知码计数、
 * #8 线程名 / `violationCount` / `claimInterface` 结果。
 *
 * ## 开销契约
 * [PtpUsbTransport] 的第三个构造参数 `diag` **默认 `null`**，此时诊断路径**无额外分配**：
 * 所有采集点经内联 `emitDiag` 判断接收者，null 时**不求值回调和实参**，
 * 因此 `PtpContainerCodec.peekHeader` / `mapIndexed` / 单调时钟 `now()` / 字符串拼接
 * 都不会发生；协议路径上只多几次可空判断和原始类型局部变量的赋值，不产生任何对象。
 *
 * ## 使用约定
 * - 纯 Kotlin、**零 Android 依赖**，可直接 JVM 单测；所有方法带默认空实现，
 *   消费方只需覆盖关心的那几个（也可用 [NO_OP] 做委托）。
 * - **本接口只采集，不做任何控制流决策**：不得改变超时 / fatal / ZLP / 短写 / txid /
 *   异常类型与 message 等任何语义；实现方应避免抛异常，transport 也会隔离采集与回调抛错，
 *   保留 USB 收发、事务原本的返回值与异常，不新增 logcat。
 * - **本接口不打 logcat**：tag 约定（`T01Verify` / `T02Diag` / `PtpUsbTransport`，
 *   见 `.docs/联机模式-T02-验证方案-2026-11-26.md` §3.3.0 与 T02 实施规格 §4.2 双 tag）
 *   与「每行前缀单调时钟 ms」全部由消费方决定；本文件不引用 Timber / `android.util.Log`。
 * - 所有数值（响应码、opcode、容器 type）请照抄真机 dump / `PtpConstants`，消费方负责
 *   格式化成十六进制，本接口只传原始数值。
 *
 * @see PtpUsbTransport
 * @see PtpDiagFrame
 */
interface PtpDiagSink {

    /**
     * 每次 bulk IN（`fill()` 里 `conn.bulkTransfer` 返回后）调用一次，**含失败与 ZLP 路径**。
     * 该回调发生在异常抛出之前，因此 `read < 0` 时也能拿到真值。
     *
     * @param request 已按 [maxPacketSize] 向下对齐后的实际请求长度（手册 #3 / TV-07 #1#2）。
     * @param read `bulkTransfer` 的原始返回值；`0` = ZLP，`< 0` = 超时/失败（TV-07 #4#7）。
     * @param maxPacketSize 运行时 `inEp.maxPacketSize`（读不到时 512 兜底，TV-07 #8）。
     * @param endpointAddress bulk IN 端点地址（如 `0x81`）。
     * @param isZlp `read == 0`。
     * @param isTimeout `read < 0`；为 true 时紧接着会抛 `PtpTimeoutException`。
     */
    fun onBulkIn(
        request: Int,
        read: Int,
        maxPacketSize: Int,
        endpointAddress: Int,
        isZlp: Boolean,
        isTimeout: Boolean,
    ) = Unit

    /**
     * `fill()` 在 `request < maxPacketSize` 分支抛异常之前调用（TV-07 #3 需要的 tail / maxPacket）。
     * 正常路径结构上不可达（`RxBuffer.reserveTail` 会 compact + 倍增扩容），仅供故障注入取证。
     *
     * @param tail `rx.reserveTail(READ_CHUNK)` 返回的尾部可用空间（未对齐、未夹到 64 KiB 之前）。
     * @param maxPacketSize 参与对齐判定的运行时值。
     */
    fun onFillRejected(tail: Int, maxPacketSize: Int) = Unit

    /**
     * 一次事务**待写容器**的真值，顺序 = [frames] 的列表顺序（手册 #5 / TV-08 #1#2）。
     * 有非空 `dataOut` 时固定为 `[CMD][DATA]` 两帧；`dataOut` 为 null / 空数组时只有命令容器一帧。
     * 消费方用 `frames[0].transactionId == frames[1].transactionId`、`frames[0].type == COMMAND`、
     * `frames[1].type == DATA` 即可证明「两帧顺序固定且 txid 共用」——这里的字段是从
     * `BulkWriteFrames.build()` 产出的**真实字节**用 `PtpContainerCodec.peekHeader` 解出的，
     * 不是 L1 的镜像重建值。
     *
     * 注意：`open()` 内 `GetDeviceInfo` 预检也是一条事务，同样会产生本回调（单帧）。
     *
     * @param opcode 本次事务的 opcode（DATA 容器的 `code` 会回显它）。
     */
    fun onFrames(opcode: Int, frames: List<PtpDiagFrame>) = Unit

    /**
     * 每次 bulk OUT（`BulkWriteFrames.writeAll` 的 `write` 出口）调用一次，
     * **短写循环的每一次迭代、以及帧末的 0 长 ZLP 补写都会各调一次**（手册 #4 / TV-07 #5#6）。
     *
     * @param frameIndex 本次写所属的容器序号，与 [onFrames] 的 `frameIndex` 对齐；
     *   正常情况下 ≥ 0，`-1` 表示未能反查（消费方应视为异常诊断信号）。
     * @param writeSequence 本次 `writeFrames` 内从 1 开始单调递增的写序号 ——
     *   同一 `frameIndex` 下出现多个递增序号即证明短写循环确实在跑。
     * @param offset 传给 `bulkTransfer` 的起始下标；同一帧内短写时逐次递增（TV-07 #6）。
     * @param length 本次实际请求长度；`0` 表示帧末 ZLP 补写（TV-07 #5）。
     * @param written `bulkTransfer` 的原始返回值；`<= 0` 且非 ZLP 时紧接着会抛
     *   `PtpTimeoutException("bulk OUT 写入失败（已写 X/Y 字节，返回 Z）")`。
     * @param isZlp `length == 0`。
     */
    fun onBulkOut(
        frameIndex: Int,
        writeSequence: Int,
        offset: Int,
        length: Int,
        written: Int,
        isZlp: Boolean,
    ) = Unit

    /**
     * 每条事务**结束**时调用一次（在 `transactionInternal()` 的 `finally` 里发出，
     * 覆盖成功与 `PtpException` 失败；调用方错误如「未 open」「已 fatal」「并发」发生在 try 之外，
     * 不产生本回调，由消费方装饰器自行记录）。
     *
     * @param opcode 事务 opcode。
     * @param transactionId 本次事务的真实 txid（与 [onFrames] 解出的值同源）。
     * @param responseCode 成功时的 `PtpResponse.code`（原始数值，消费方自行格式化 hex）；失败为 null。
     * @param dataBytes 已完整收完的数据阶段 payload 字节数；中途失败的数据阶段不计入，
     *   因此失败时不能据此断言实际收到的部分字节数，流式消费方须在自己的 dataSink 累计。
     * @param elapsedMs 事务耗时（单调时钟 ms 差）；仅在 diag 启用时额外读取一次时钟。
     * @param isFatal 回调发出瞬间的 `isFatal` 真值（会话内失败已置 true，预检失败保持 false）。
     * @param isPreflight 是否为 `open()` 的 `GetDeviceInfo` 预检（`fatalOnFailure = false`）。
     * @param exceptionType 事务 try 内失败的 `javaClass.simpleName`；成功为 null。
     *   dataSink 抛错也如实记录并原样重抛，只有 `PtpException` 才沿用原有 fatal 规则；
     *   try 外的未 open / 已 fatal / 并发检查不产生本回调。
     * @param exceptionMessage 异常的**完整** `message` 原文（不截断、不改写、不重新拼装）。
     */
    fun onTransaction(
        opcode: Int,
        transactionId: Long,
        responseCode: Int?,
        dataBytes: Long,
        elapsedMs: Long,
        isFatal: Boolean,
        isPreflight: Boolean,
        exceptionType: String?,
        exceptionMessage: String?,
    ) = Unit

    companion object {
        /** 无操作实现，便于消费方用类委托（`by PtpDiagSink.NO_OP`）只覆盖部分方法。 */
        val NO_OP: PtpDiagSink = object : PtpDiagSink {}
    }
}

/**
 * [PtpDiagSink.onFrames] 里一个待写容器的头真值（全部从实际发出的字节用
 * `PtpContainerCodec.peekHeader` 解出；`length` 为容器头声明的完整长度，含 12 字节头）。
 *
 * @property frameIndex 容器在本次事务发送序列中的序号（0 = CMD，1 = DATA）。
 * @property type 容器类型，取值见 `PtpConstants.ContainerType`（1 COMMAND / 2 DATA）。
 * @property code 命令容器 = opcode；DATA 容器 = opcode 的 echo。
 * @property transactionId u32 事务号（用 Long 承载）；同一事务的两帧必须相同。
 * @property length 容器总字节数（含头）。
 */
data class PtpDiagFrame(
    val frameIndex: Int,
    val type: Int,
    val code: Int,
    val transactionId: Long,
    val length: Int,
)
