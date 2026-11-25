package cn.alittlecookie.lutista.tether.transport

/**
 * 数据阶段（DATA 容器 payload）搬运的纯逻辑。
 *
 * 单独抽出来有两个理由：
 * 1. **可 JVM 单测**：`PtpUsbTransport` 依赖 `UsbDeviceConnection`，整条事务无法在单测里跑；
 *    但这里最容易出错的一步（分块大小）可以脱离 USB 验证。
 * 2. **把踩过的坑钉死**：`rx` 里常常同时有「上一次 URB 的残留字节」+「新容器」，
 *    如果分块只按 `min(rx 可用, 剩余长度)` 算，就会一次性拷出超过中转缓冲的字节 → 数组越界。
 *    因此分块必须是 `min(rx 可用, 剩余长度, 中转缓冲大小)` 三重约束。
 *
 * 纯 Kotlin、零 Android 依赖。
 */
internal object DataPhasePump {

    /** 单块搬运上限：三重约束，缺一不可。 */
    fun chunkSize(available: Int, remaining: Int, stagingSize: Int): Int {
        require(available >= 0 && remaining >= 0 && stagingSize > 0) {
            "chunkSize 参数非法：available=$available remaining=$remaining stagingSize=$stagingSize"
        }
        return minOf(available, remaining, stagingSize)
    }

    /**
     * 把长度为 [payloadLength] 的 payload 从 [source] 搬走（[sink] 为 null 时丢弃但仍全部搬完）。
     *
     * @param fill 当 [source] 空时调用，负责再灌一批字节进来（实现里会发起一次 bulk IN）；
     *   必须「要么灌到字节、要么抛异常」，否则本方法会一直空转（实现里靠事务 deadline 兜底抛超时）。
     * @param sink 数据接收器 `(chunk, len)`，chunk 的有效数据从下标 0 开始且在回调内有效。
     * @return 实际搬运的字节数（正常 == [payloadLength]）。
     */
    fun pump(
        source: RxBuffer,
        payloadLength: Int,
        staging: ByteArray,
        fill: () -> Unit,
        sink: ((ByteArray, Int) -> Unit)?,
    ): Int {
        require(payloadLength >= 0) { "payloadLength 不能为负：$payloadLength" }
        if (payloadLength == 0) return 0

        var moved = 0
        var remaining = payloadLength
        while (remaining > 0) {
            if (source.isEmpty) {
                fill()
                // fill 正常会灌入字节；若它只返回 0 长包，下一轮继续等（超时由 fill 自己抛）。
                continue
            }
            val take = chunkSize(source.size, remaining, staging.size)
            if (sink != null) {
                source.copyTo(staging, 0, take)
                sink(staging, take)
            }
            source.skip(take)
            moved += take
            remaining -= take
        }
        return moved
    }
}
