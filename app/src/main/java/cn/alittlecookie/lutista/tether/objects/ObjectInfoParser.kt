package cn.alittlecookie.lutista.tether.objects

import cn.alittlecookie.lutista.tether.profile.PtpQuirks
import cn.alittlecookie.lutista.tether.protocol.PtpParseException
import cn.alittlecookie.lutista.tether.protocol.PtpStringReader

/** ObjectInfo fixed fields are unsigned little-endian; filename layout comes from the profile. */
object ObjectInfoParser {
    fun parse(handle: Long, data: ByteArray, quirks: PtpQuirks, seq: Long = 0): CameraObject {
        requireHandle(handle)
        if (data.size < 38) throw PtpParseException("ObjectInfo fixed fields are truncated")
        val strings = quirks.objectInfoFilenameOffsetCandidates.asSequence().mapNotNull { offset ->
            try {
                val filename = PtpStringReader.read(data, offset)
                if (filename.value.isBlank() || filename.value.any { it.code < 32 }) return@mapNotNull null
                val date = PtpStringReader.read(data, offset + filename.bytesConsumed)
                if (date.value.any { it.code < 32 }) return@mapNotNull null
                filename.value to date.value
            } catch (_: PtpParseException) {
                null
            }
        }.firstOrNull() ?: throw PtpParseException("ObjectInfo has no valid profile filename layout")
        val format = u16(data, 4).toLong()
        return CameraObject(
            handle = handle, storageId = u32(data, 0), format = format,
            parent = u32(data, 34), size = u32(data, 8), name = strings.first,
            dateTakenRaw = strings.second, isDir = format == quirks.associationFormat, seq = seq,
        )
    }

    /** Count is validated and skipped, including for an empty dataset. */
    fun handles(data: ByteArray): List<Long> {
        if (data.size < 4) throw PtpParseException("Object handle count is truncated")
        val count = u32(data, 0)
        if (4L + count * 4L != data.size.toLong()) throw PtpParseException("Object handle count does not match payload")
        return List(count.toInt()) { u32(data, 4 + it * 4).also(::requireHandle) }
    }

    internal fun requireHandle(handle: Long) {
        require(handle in 1L..0xFFFF_FFFEL) { "Object handle must be a non-reserved u32" }
    }

    private fun u16(data: ByteArray, at: Int): Int {
        if (at < 0 || at.toLong() + 2 > data.size) throw PtpParseException("ObjectInfo u16 is truncated")
        return (data[at].toInt() and 255) or ((data[at + 1].toInt() and 255) shl 8)
    }
    private fun u32(data: ByteArray, at: Int): Long =
        u16(data, at).toLong() or (u16(data, at + 2).toLong() shl 16)
}
