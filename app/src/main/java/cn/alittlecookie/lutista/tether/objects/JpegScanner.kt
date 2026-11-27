package cn.alittlecookie.lutista.tether.objects

/** Bounded marker walker. APP payloads may themselves contain nested JPEGs. */
object JpegScanner {
    data class Segment(val offset: Int, val length: Int)
    data class Size(val width: Int, val height: Int) {
        val area: Long get() = width.toLong() * height
    }

    fun allSegments(bytes: ByteArray): List<Segment> {
        require(bytes.size <= PtpObjectSource.RAW_PREVIEW_BYTES) { "RAW scan exceeds the 512 KiB window" }
        val segments = ArrayList<Segment>()
        var starts = 0
        for (at in 0 until bytes.size - 1) {
            if (u8(bytes, at) == 255 && u8(bytes, at + 1) == 216) {
                // Reject pathological input rather than silently choosing from a truncated candidate list.
                require(++starts <= MAX_CANDIDATES) { "Too many embedded JPEG candidates" }
                jpegEnd(bytes, at)?.let { segments += Segment(at, it - at) }
            }
        }
        return segments
    }

    /** Every complete candidate is decoded; only the winning JPEG is copied. */
    fun largestDecodable(
        bytes: ByteArray,
        decode: (ByteArray, Int, Int) -> Size?,
    ): ByteArray? {
        var best: Segment? = null
        var bestArea = 0L
        for (segment in allSegments(bytes)) {
            val size = decode(bytes, segment.offset, segment.length) ?: continue
            if (size.width > 0 && size.height > 0 && size.area > bestArea) {
                best = segment
                bestArea = size.area
            }
        }
        return best?.let { bytes.copyOfRange(it.offset, it.offset + it.length) }
    }

    private fun jpegEnd(bytes: ByteArray, start: Int): Int? {
        var cursor = start + 2
        var entropy = false
        while (cursor < bytes.size) {
            if (u8(bytes, cursor) != 255) {
                if (!entropy) return null
                cursor++
                continue
            }
            while (cursor < bytes.size && u8(bytes, cursor) == 255) cursor++
            if (cursor >= bytes.size) return null
            val marker = u8(bytes, cursor++)
            if (entropy && (marker == 0 || marker in 208..215)) continue
            if (marker == 217) return cursor
            if (marker == 216 || marker == 0) return null
            if (marker == 1 || marker in 208..215) continue
            if (cursor + 2 > bytes.size) return null
            val length = (u8(bytes, cursor) shl 8) or u8(bytes, cursor + 1)
            if (length < 2 || cursor.toLong() + length > bytes.size) return null
            cursor += length
            entropy = marker == 218
        }
        return null
    }

    private fun u8(bytes: ByteArray, at: Int) = bytes[at].toInt() and 255
    private const val MAX_CANDIDATES = 256
}
