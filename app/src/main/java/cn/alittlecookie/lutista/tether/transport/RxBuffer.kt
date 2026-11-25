package cn.alittlecookie.lutista.tether.transport

/**
 * 跨 URB 剩余字节的游标缓冲（纯 Kotlin，可 JVM 单测）。
 *
 * 为什么需要它：一次 `bulkTransfer` 请求 64 KB，相机可能回：
 * - 一个完整小容器 + 下一个容器的前半截（粘包）；
 * - 一个 86 MB DATA 容器的前 64 KB（半个容器）；
 * - 恰好 `maxPacketSize` 整数倍的包（相机接着补 0 长包）。
 *
 * 因此接收侧必须是「追加 → 按游标取用 → 只前移游标」的结构，**不能**把每次 URB 当独立帧。
 *
 * 内存策略（自行决定，文档未规定）：
 * - 默认容量 128 KB = 一次 64 KB URB + 一份残留，正常情况下永远不扩容；
 * - 游标到尾部但空间不够时先 `compact()`（把残留搬到数组头部），仍不够才倍增；
 * - 所有操作都是数组拷贝，无锁（只在 `ptp-io` 单线程使用）。
 */
class RxBuffer(initialCapacity: Int = DEFAULT_CAPACITY) {

    companion object {
        /** 默认容量：64 KB 单次读 + 残留余量。 */
        const val DEFAULT_CAPACITY: Int = 128 * 1024

        /** 最小容量（防止 `ByteArray(0)` 造成 append 永远需要扩容）。 */
        private const val MIN_CAPACITY: Int = 16
    }

    private var storage: ByteArray = ByteArray(initialCapacity.coerceAtLeast(MIN_CAPACITY))
    private var start: Int = 0
    private var end: Int = 0

    /** 当前可用字节数。 */
    val size: Int get() = end - start

    /** 数组总容量。 */
    val capacity: Int get() = storage.size

    /** 是否没有可读字节。 */
    val isEmpty: Boolean get() = start == end

    /**
     * 底层数组（**零拷贝读取用**）：有效区间是 `[readOffset, readOffset + size)`。
     * 有效期只到下一次修改（append / skip / compact / clear）之前。
     */
    val buffer: ByteArray get() = storage

    /** 有效区间起点（配合 [buffer] 使用）。 */
    val readOffset: Int get() = start

    /** 清空（保留容量）。 */
    fun clear() {
        start = 0
        end = 0
    }

    /** 追加一段字节（允许追加后自动 compact / 扩容）。 */
    fun append(source: ByteArray, offset: Int = 0, length: Int = source.size) {
        require(offset >= 0 && length >= 0 && offset + length <= source.size) {
            "append 范围非法：offset=$offset length=$length size=${source.size}"
        }
        if (length == 0) return
        reserveTail(length)
        System.arraycopy(source, offset, storage, end, length)
        end += length
    }

    /**
     * 把有效区间的 [count] 字节拷到 [dest]（用于把 payload 交给 dataSink 而不破坏游标）。
     *
     * @param srcOffset 相对有效区间起点的偏移。
     */
    fun copyTo(dest: ByteArray, destOffset: Int, count: Int, srcOffset: Int = 0) {
        require(srcOffset >= 0 && count >= 0 && srcOffset + count <= size) {
            "copyTo 源范围非法：srcOffset=$srcOffset count=$count size=$size"
        }
        require(destOffset >= 0 && destOffset + count <= dest.size) {
            "copyTo 目标范围非法：destOffset=$destOffset count=$count dest=${dest.size}"
        }
        if (count == 0) return
        System.arraycopy(storage, start + srcOffset, dest, destOffset, count)
    }

    /** 读走前 [count] 字节（拷贝出来并前移游标）。 */
    fun read(count: Int): ByteArray {
        val out = ByteArray(count)
        copyTo(out, 0, count)
        skip(count)
        return out
    }

    /** 只看前 [count] 字节（不移动游标）。 */
    fun peek(count: Int): ByteArray {
        val out = ByteArray(count)
        copyTo(out, 0, count)
        return out
    }

    /** 前移游标 [count] 字节。 */
    fun skip(count: Int) {
        require(count >= 0 && count <= size) { "skip 越界：count=$count size=$size" }
        start += count
        if (start == end) {
            start = 0
            end = 0
        }
    }

    /**
     * 保证尾部至少有 [required] 字节可写空间，返回实际尾部空间（可能大于 required）。
     *
     * 顺序：尾部够 → 直接返回；compact 后够 → 返回；仍不够 → 倍增扩容。
     *
     * @throws IllegalStateException 扩容会越过 `Int.MAX_VALUE`（`required` 大得离谱，
     *   通常是有人把容器头声明的长度直接喂进来）。**必须抛而不是继续倍增**：
     *   `newCapacity *= 2` 在 2^30 处会溢出成负数、再变成 0，之后条件恒真 → 不分配内存、
     *   不报错、只静默烧 CPU 的死循环，正是最难排查的那种。
     */
    fun reserveTail(required: Int): Int {
        require(required >= 0) { "required 不能为负：$required" }
        if (storage.size - end >= required) return storage.size - end
        compact()
        if (storage.size - end >= required) return storage.size - end
        var newCapacity = storage.size
        while (newCapacity - end < required) {
            if (newCapacity > Int.MAX_VALUE / 2) {
                throw IllegalStateException(
                    "RxBuffer 扩容溢出：required=$required end=$end capacity=$newCapacity",
                )
            }
            newCapacity *= 2
        }
        storage = storage.copyOf(newCapacity)
        return storage.size - end
    }

    /** 把残留字节搬到数组头部（游标归零）。 */
    fun compact() {
        if (start == 0) return
        if (start < end) System.arraycopy(storage, start, storage, 0, end - start)
        end -= start
        start = 0
    }

    override fun toString(): String = "RxBuffer(size=$size, capacity=$capacity, start=$start, end=$end)"
}
