package cn.alittlecookie.lutista.tether.protocol

/**
 * PTP string 读取器（纯函数）。
 *
 * 布局（PIMA 15740 / 真机实测，见定稿 §4.2 的 DCIM hex）：
 * ```
 * [count u8][UTF-16LE × count]
 * ```
 * `count` **包含**终止 0（`"DCIM"` → `05 44 00 43 00 49 00 4d 00 00 00`）。
 *
 * 实现的容错（真机固件不全守规范）：
 * - `count == 0` → 空串，只消耗 1 字节；
 * - 若最后一个 UTF-16 单元不是 0（说明固件用了「不含终止 0」的约定），则按 `count` 个字符解；
 * - 声明长度超出可用范围 → 抛 [PtpParseException]（不静默截断）。
 */
object PtpStringReader {

    /** 一次读取的结果。 */
    data class Result(
        /** 解出的字符串（已去掉终止 NUL）。 */
        val value: String,
        /** 本次消耗的字节数（1 + 2 × count）。 */
        val bytesConsumed: Int,
    )

    /**
     * 从 [data] 的 [offset] 起读一个 PTP string。
     *
     * @param limit 可用区间的右开边界（一般传 `data.size`，也可传某段容器的结尾）。
     */
    fun read(data: ByteArray, offset: Int = 0, limit: Int = data.size): Result {
        if (offset < 0 || offset >= limit || limit > data.size) {
            throw PtpParseException("PTP string 起点非法：offset=$offset limit=$limit size=${data.size}")
        }
        val count = data[offset].toInt() and 0xFF
        if (count == 0) return Result("", 1)

        val bytesNeeded = 1 + count * 2
        if (offset + bytesNeeded > limit) {
            throw PtpParseException(
                "PTP string 长度越界：声称 $count 个字符（$bytesNeeded 字节），" +
                    "实际只剩 ${limit - offset} 字节（offset=$offset limit=$limit）",
            )
        }

        // 规范约定 count 含终止 0；宽容处理「不含终止 0」的固件：先按 count 个单元解，再剥掉尾部 NUL。
        var unitCount = count
        val lastUnit = (data[offset + bytesNeeded - 2].toInt() and 0xFF) or
            ((data[offset + bytesNeeded - 1].toInt() and 0xFF) shl 8)
        if (lastUnit == 0) unitCount -= 1

        if (unitCount <= 0) return Result("", bytesNeeded)

        val value = String(data, offset + 1, unitCount * 2, Charsets.UTF_16LE)
        // 少数固件会在中间夹 NUL（DPC 名字里见过），统一裁掉尾部残留。
        val trimmed = value.trimEnd('\u0000')
        return Result(trimmed, bytesNeeded)
    }
}
