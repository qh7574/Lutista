package cn.alittlecookie.lutista.tether.transport

/** Connection-independent PTP I/O. The factory owns opening the physical connection. */
interface Transport {
    /**
     * How long the factory's `open()` spent on **bounded channel recovery** (clear halt → exact drain →
     * class Cancel/DeviceReset → retry preflight), `0` when the connection opened cleanly.
     *
     * 会话层的连接预算是给「PTP 会话建立」用的，不该把传输层自愈的时间算进去：真机
     * `logs_1795791570292` 的 22:57:56 那一次，复位 455 ms 成功、重新预检 4 ms OK，却在
     * `open()` 返回后被 5 s 预算判死。默认 0 让 fake/干净连接与既有预算用例完全不受影响。
     */
    val openRecoveryMs: Long get() = 0

    /**
     * `open()` 预检期间相机是否**一个字节都没回**（超时且 bulk IN 全程零字节 / 立即 STALL 零字节）。
     *
     * 真机 `logs_1795824713270`（2026-11-28 08:10:54–08:11:49，6 次尝试）显示：这种状态下连
     * `CLEAR_FEATURE(ENDPOINT_HALT)`、`SET_INTERFACE`、类请求 `DeviceReset(0x66)`、PTP
     * `DeviceReset(0x1003)` 全部「成功」，相机却依然不回任何一个 bulk IN 字节；**拔插数据线也不恢复**，
     * 只有把相机电源关掉再打开才清得掉。会话层据此把错误文案从「拔数据线」换成「关相机电源再打开」。
     * 默认 `false`，fake 连接与既有用例不受影响。
     */
    val cameraSilent: Boolean get() = false

    suspend fun transaction(
        op: PtpOp,
        expectedData: Boolean = false,
        dataOut: ByteArray? = null,
        dataSink: ((ByteArray, Int) -> Unit)? = null,
        timeoutMs: Int = 4_000,
    ): PtpResponse
    suspend fun drainEvents(timeoutMs: Int = 2): List<PtpEvent>
    suspend fun close(timeoutMs: Int = 1_000)
}
