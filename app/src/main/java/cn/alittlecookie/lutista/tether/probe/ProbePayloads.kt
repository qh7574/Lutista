package cn.alittlecookie.lutista.tether.probe

import cn.alittlecookie.lutista.tether.protocol.PtpConstants.Prop

/** 仅覆盖 P2/P3/P4/P5 的最小只读判断；完整属性描述符模型属于 T03/T07。 */
internal object ProbePayloads {
    fun propertyValue(bytes: ByteArray, property: Long): Long? = checked {
        require(u32(bytes, 0) == property)
        val width = u32(bytes, 4)
        require(width == 2L || width == 4L)
        require(bytes.size.toLong() == 8L + width)
        if (width == 2L) u16(bytes, 8).toLong() else u32(bytes, 8)
    }

    fun handles(bytes: ByteArray): List<Long>? = checked {
        val count = u32(bytes, 0)
        require(count <= (bytes.size - 4L) / 4L && bytes.size.toLong() == 4L + count * 4L)
        List(count.toInt()) { u32(bytes, 4 + it * 4) }
    }

    /**
     * v7 §11.2：属性节点 size=20，其后有 n 个子记录，span 包含子记录头；
     * 仅取 ISO_VALUE 下 code=1 的枚举数，不把 ISO 上限的 9 项误作 ISO 档位。
     * 逐层验证 count/span/类型/恰好耗尽；坏 DATA 是 Unknown，而非 transport fatal。
     */
    fun isoDescriptorCount(bytes: ByteArray): Int? = checked {
        var nodes = 0
        var answer: Int? = null
        fun record(start: Int, limit: Int, depth: Int, inIsoValue: Boolean): Int {
            require(depth <= 32 && ++nodes <= 4_096)
            require(start >= 0 && start.toLong() + 8L <= limit)
            val code = u32(bytes, start)
            val size = u32(bytes, start + 4)
            val payload = start + 8
            val endLong = payload.toLong() + size
            require(endLong <= limit && size >= 20L)
            val end = endLong.toInt()
            require(u32(bytes, payload + 4) == 0x00010001L && u32(bytes, payload + 8) == 0L)
            return when (u32(bytes, payload)) {
                20L -> {
                    require(size == 20L)
                    val count = u32(bytes, payload + 12)
                    val span = u32(bytes, payload + 16)
                    val childrenEndLong = end.toLong() + span
                    require(childrenEndLong <= limit && count <= span / 8L)
                    val childrenEnd = childrenEndLong.toInt()
                    var cursor = end
                    repeat(count.toInt()) {
                        cursor = record(cursor, childrenEnd, depth + 1, inIsoValue || code == Prop.ISO_VALUE)
                    }
                    require(cursor == childrenEnd)
                    cursor
                }
                0L -> {
                    require(size >= 30L && u32(bytes, payload + 12) == 1L)
                    val width = when (u32(bytes, payload + 16)) { 4L -> 2; 6L -> 4; else -> error("Unsupported datatype") }
                    require(u32(bytes, payload + 20) == 2L)
                    val count = u32(bytes, payload + 24 + width)
                    require(size == 28L + width + count * width)
                    if (inIsoValue && code == 1L) {
                        require(answer == null && count <= Int.MAX_VALUE)
                        answer = count.toInt()
                    }
                    end
                }
                else -> error("Unknown descriptor node")
            }
        }
        require(u32(bytes, 0) == Prop.ISO_CONTAINER)
        require(record(0, bytes.size, 0, false) == bytes.size)
        requireNotNull(answer)
    }

    private inline fun <T> checked(block: () -> T): T? = try {
        block()
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: IllegalStateException) {
        null
    }

    private fun u16(bytes: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset.toLong() + 2L <= bytes.size)
        return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun u32(bytes: ByteArray, offset: Int): Long {
        require(offset >= 0 && offset.toLong() + 4L <= bytes.size)
        return (bytes[offset].toLong() and 0xFFL) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFFL) shl 24)
    }
}
