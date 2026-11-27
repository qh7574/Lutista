package cn.alittlecookie.lutista.tether.liveview

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Fresh immutable-by-contract JPEG storage; the producer never mutates/reuses a published array. */
data class LiveViewFrame(val jpeg: ByteArray, val width: Int, val height: Int, val capturedAtMs: Long)

data class LiveViewFormat(val height: Int, val width: Int, val x: Int, val frequency: Int) {
    init { require(height > 0 && width > 0 && listOf(height, width, x, frequency).all { it in 0..65535 }) }
    /** Re-emit the exact advertised tuple, including opaque x and frequency. */
    fun payload(): ByteArray = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
        putInt(0x0D800011); putInt(8)
        putShort(height.toShort()); putShort(width.toShort()); putShort(x.toShort()); putShort(frequency.toShort())
    }.array()
}

object LiveViewCodec {
    fun formats(payload: ByteArray): List<LiveViewFormat> {
        require(payload.size >= 12) { "Truncated liveview format table" }
        val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        require(b.int == 0x0D800012) { "Liveview format echo mismatch" }
        require((b.int.toLong() and 0xFFFF_FFFFL) == payload.size.toLong() - 8) { "Format table size mismatch" }
        val count = b.short.toInt() and 0xFFFF
        require((b.short.toInt() and 0xFFFF) == 8 && count in 1..256 && b.remaining() == count * 8) { "Invalid format tuples" }
        return List(count) {
            LiveViewFormat(b.short.toInt() and 0xFFFF, b.short.toInt() and 0xFFFF,
                b.short.toInt() and 0xFFFF, b.short.toInt() and 0xFFFF)
        }
    }

    /** Search SOI dynamically: real photo/video prefixes differ (180/161 bytes). */
    fun frame(payload: ByteArray, capturedAtMs: Long): LiveViewFrame? {
        for (start in 0 until payload.size - 1) {
            if (u8(payload[start]) != 0xFF || u8(payload[start + 1]) != 0xD8) continue
            val dimensions = dimensions(payload, start) ?: continue
            var end = start + 2
            while (end + 1 < payload.size) {
                if (u8(payload[end]) == 0xFF && u8(payload[end + 1]) == 0xD9)
                    return LiveViewFrame(payload.copyOfRange(start, end + 2), dimensions.first, dimensions.second, capturedAtMs)
                end++
            }
        }
        return null
    }
    private fun dimensions(bytes: ByteArray, start: Int): Pair<Int, Int>? {
        var p = start + 2
        while (p + 3 < bytes.size) {
            if (u8(bytes[p++]) != 0xFF) return null
            while (p < bytes.size && u8(bytes[p]) == 0xFF) p++
            if (p >= bytes.size) return null
            val marker = u8(bytes[p++])
            if (marker == 0xD9 || marker == 0xDA || marker == 0) return null
            if (marker == 0x01 || marker in 0xD0..0xD7) continue
            if (p + 1 >= bytes.size) return null
            val length = (u8(bytes[p]) shl 8) or u8(bytes[p + 1])
            if (length < 2 || length > bytes.size - p) return null
            if (marker in 0xC0..0xCF && marker !in setOf(0xC4, 0xC8, 0xCC)) {
                if (length < 8) return null
                val height = (u8(bytes[p + 3]) shl 8) or u8(bytes[p + 4])
                val width = (u8(bytes[p + 5]) shl 8) or u8(bytes[p + 6])
                return if (height in 1..16384 && width in 1..16384) width to height else null
            }
            p += length
        }
        return null
    }
    private fun u8(value: Byte) = value.toInt() and 255
}
