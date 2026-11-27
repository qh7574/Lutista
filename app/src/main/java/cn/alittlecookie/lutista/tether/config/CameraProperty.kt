package cn.alittlecookie.lutista.tether.config

import cn.alittlecookie.lutista.tether.profile.ValueCodecKind
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/** IDs exposed to callers are the profile's canonical/container IDs, never guessed IDs. */
object CameraProperty {
    const val ISO = 0x02000020L
    const val ISO_LIMIT = 0x02000022L
    const val SHUTTER = 0x02000030L
    const val APERTURE = 0x02000040L
    const val WHITE_BALANCE = 0x02000050L
    const val EV = 0x02000060L
    const val MODE = 0x02000082L
    const val PHOTO_STYLE = 0x02000083L
    const val IMAGE_FORMAT = 0x020000A2L
    val whitelist = setOf(ISO, ISO_LIMIT, SHUTTER, APERTURE, WHITE_BALANCE, EV, MODE, PHOTO_STYLE, IMAGE_FORMAT)
    val display = listOf(ISO, SHUTTER, APERTURE, EV, WHITE_BALANCE, PHOTO_STYLE)
    fun valueId(id: Long): Long = when (id) {
        ISO, SHUTTER, APERTURE, WHITE_BALANCE, EV -> id + 1
        else -> id
    }
}

data class PropertyDescriptor(val propertyId: Long, val valueId: Long, val width: Int,
    val current: Long, val values: List<Long>) {
    /** count=0 is a supported current value with no writable enumeration. */
    val writable: Boolean get() = values.isNotEmpty()
}

enum class PropertyAccess { Writable, ReadOnly, Unsupported, Unavailable }
data class PropertyState(val id: Long, val value: Long?, val label: String,
    val access: PropertyAccess, val descriptor: PropertyDescriptor? = null, val detail: String? = null)
data class ModeKey(val mode: Long?, val submode: Int?)
data class PropertyCatalogSnapshot(val mode: ModeKey, val properties: Map<Long, PropertyState>)

/** Strict, bounded parser for 0x9108's record tree (children follow a parent record). */
object PropertyCodec {
    fun decodeValue(payload: ByteArray, expectedId: Long): Long {
        require(payload.size >= 8) { "Truncated property value" }
        val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        require(u32(b) == expectedId) { "Property echo mismatch" }
        val width = u32(b).toInt()
        require(width == 2 || width == 4) { "Unsupported property width" }
        require(payload.size == 8 + width) { "Property length mismatch" }
        return if (width == 2) b.short.toLong() and 0xFFFF else u32(b)
    }

    fun encodeValue(valueId: Long, value: Long, width: Int = 4): ByteArray {
        require(valueId in 0..0xFFFF_FFFFL && value in 0..0xFFFF_FFFFL)
        require(width == 2 || width == 4)
        require(width == 4 || value <= 0xFFFF)
        return ByteBuffer.allocate(8 + width).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(valueId.toInt()); putShort(width.toShort()); putShort(0)
            if (width == 2) putShort(value.toShort()) else putInt(value.toInt())
        }.array()
    }

    fun decodeDescriptor(payload: ByteArray, propertyId: Long): PropertyDescriptor {
        require(payload.size in 28..(1024 * 1024)) { "Descriptor length outside bounds" }
        val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val target = CameraProperty.valueId(propertyId)
        var nodes = 0
        var found: PropertyDescriptor? = null
        fun record(end: Int, depth: Int, ancestors: List<Long>) {
            require(depth <= 16 && ++nodes <= 4096) { "Descriptor tree limit exceeded" }
            require(end - b.position() >= 8) { "Truncated descriptor record" }
            val code = u32(b)
            val size = u32(b)
            require(size <= end - b.position()) { "Descriptor record exceeds parent span" }
            val finish = b.position() + size.toInt()
            require(size >= 20) { "Truncated descriptor header" }
            val marker = u32(b)
            require(u32(b) == 0x00010001L && u32(b) == 0L) { "Invalid descriptor tag" }
            val countOrOne = u32(b)
            if (marker == 20L) {
                require(size == 20L && countOrOne <= 4096) { "Invalid parent record" }
                val childBytes = u32(b)
                require(childBytes <= end - finish) { "Descriptor child span overflow" }
                val childEnd = finish + childBytes.toInt()
                repeat(countOrOne.toInt()) { record(childEnd, depth + 1, ancestors + code) }
                require(b.position() == childEnd) { "Descriptor child span mismatch" }
            } else {
                require(marker == 0L && countOrOne == 1L && size >= 30) { "Invalid value record" }
                val width = when (u32(b)) { 4L -> 2; 6L -> 4; else -> error("Unknown descriptor datatype") }
                val form = u32(b)
                val current = if (width == 2) b.short.toLong() and 0xFFFF else u32(b)
                val values = when (form) {
                    2L -> {
                        val count = u32(b)
                        require(count <= 65536 && size == 28L + width + count * width) { "Enumeration length mismatch" }
                        List(count.toInt()) { if (width == 2) b.short.toLong() and 0xFFFF else u32(b) }
                    }
                    1L -> {
                        // Real EV trees include range siblings. Validate their whole record, but
                        // never synthesize writable options from min/max/step (signed codecs vary).
                        require(size == 24L + 4 * width) { "Range length mismatch" }
                        repeat(3) { if (width == 2) b.short else b.int }
                        emptyList()
                    }
                    else -> error("Invalid property form")
                }
                require(b.position() == finish)
                if (code == 1L && ancestors.lastOrNull() == target) {
                    require(found == null) { "Duplicate property value node" }
                    found = PropertyDescriptor(propertyId, target, width, current, values)
                }
            }
        }
        while (b.hasRemaining()) record(b.limit(), 0, emptyList())
        return requireNotNull(found) { "Missing descriptor for whitelisted property" }
    }

    fun label(kind: ValueCodecKind, value: Long): String = when (kind) {
        ValueCodecKind.ISO -> if (value == 0xFFFF_FFFFL) "AUTO" else value.toString()
        ValueCodecKind.APERTURE -> if (value == 0xFFFEL) "—" else "f/" + decimal(value / 10.0)
        ValueCodecKind.SHUTTER -> when {
            value == 0xFFFF_FFFFL -> "BULB"
            value and 0x8000_0000L != 0L -> decimal((value and 0x7FFF_FFFFL) / 1000.0) + "s"
            value == 0L -> "—"
            else -> "1/" + decimal(value / 1000.0)
        }
        ValueCodecKind.EV -> {
            val thirds = value and 0x7FFFL
            val signed = (if (value and 0x8000L != 0L) -thirds else thirds) / 3.0
            (if (signed > 0) "+" else "") + decimal(signed)
        }
        ValueCodecKind.WB -> when (value) {
            2L -> "AWB"; 4L -> "日光"; 6L -> "白炽"; 7L -> "闪光"
            0x8008L -> "阴天"; 0x800FL -> "阴影"
            in 0x800BL..0x800EL -> "自定义 ${value - 0x800A}"
            in 0x8010L..0x8013L -> "色温 ${value - 0x800F}"
            0x8014L -> "AWB 偏冷"; 0x8015L -> "AWB 偏暖"
            else -> "0x${value.toString(16)}"
        }
        else -> value.toString()
    }
    private fun decimal(value: Double): String = String.format(Locale.ROOT, "%.1f", value).removeSuffix(".0")
    private fun u32(b: ByteBuffer): Long = b.int.toLong() and 0xFFFF_FFFFL
}
