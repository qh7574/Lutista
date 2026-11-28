package cn.alittlecookie.lutista.tether.objects

import cn.alittlecookie.lutista.tether.protocol.PtpConstants
import cn.alittlecookie.lutista.tether.session.PtpData
import cn.alittlecookie.lutista.tether.transport.PtpResponse
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun objectInfo(
    storage: Long = 1, format: Long = 0x3801, parent: Long = 0,
    size: Long = 3, name: String = "IMG_0001.JPG", date: String = "20261125T120000",
    filenameOffset: Int = 52,
): ByteArray {
    fun ptp(text: String): ByteArray = if (text.isEmpty()) byteArrayOf(0) else
        byteArrayOf((text.length + 1).toByte()) + (text + '\u0000').toByteArray(Charsets.UTF_16LE)
    val strings = ptp(name) + ptp(date)
    return ByteBuffer.allocate(filenameOffset + strings.size).order(ByteOrder.LITTLE_ENDIAN).apply {
        putInt(0, storage.toInt()); putShort(4, format.toShort())
        putInt(8, size.toInt()); putInt(38, parent.toInt())
        position(filenameOffset); put(strings)
    }.array()
}

internal fun objectHandles(vararg handles: Long): ByteArray =
    ByteBuffer.allocate(4 + handles.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
        putInt(handles.size); handles.forEach { putInt(it.toInt()) }
    }.array()

internal fun okData(bytes: ByteArray) = PtpData(PtpResponse(PtpConstants.Rc.OK), bytes)

internal class FakeObjectAccess : ObjectAccess {
    val queriedParents = mutableListOf<Long>()
    val infoReads = mutableListOf<Long>()
    var children: Map<Long, List<Long>> = emptyMap()
    var infos: Map<Long, ByteArray> = emptyMap()
    var infoProvider: ((Long) -> ByteArray)? = null
    var handlesProvider: ((Long) -> PtpData)? = null
    var thumbnail = byteArrayOf(1, 2, 3)
    var head = byteArrayOf()
    var partialFailure: Throwable? = null
    /** 需要「按偏移返回切片」时分块下载用例用它，优先于 [head]。 */
    var partialProvider: ((Long, Long, Long) -> PtpData)? = null
    var partialCode = PtpConstants.Rc.OK
    var thumbCode = PtpConstants.Rc.OK
    var thumbReads = 0
    var partialLength = 0L
    var partialOffset = 0L
    var streamCalls = 0
    var streamAction: (sink: (ByteArray, Int) -> Unit) -> PtpResponse = { sink ->
        sink(byteArrayOf(1, 2, 3), 3); PtpResponse(PtpConstants.Rc.OK)
    }
    override suspend fun handles(parent: Long): PtpData {
        queriedParents += parent
        return handlesProvider?.invoke(parent) ?: okData(objectHandles(*(children[parent].orEmpty().toLongArray())))
    }
    override suspend fun info(handle: Long): PtpData {
        infoReads += handle
        return okData(infoProvider?.invoke(handle) ?: infos.getValue(handle))
    }
    override suspend fun thumb(handle: Long): PtpData {
        thumbReads++
        return PtpData(PtpResponse(thumbCode), thumbnail)
    }
    override suspend fun partial(handle: Long, offset: Long, length: Long): PtpData {
        partialLength = length
        partialOffset = offset
        partialFailure?.let { throw it }
        partialProvider?.let { return it(handle, offset, length) }
        return PtpData(PtpResponse(partialCode), head)
    }
    override suspend fun stream(handle: Long, sink: (ByteArray, Int) -> Unit): PtpResponse {
        streamCalls++
        return streamAction(sink)
    }
}

/** Marker-valid synthetic JPEG, with its size/tag stored in a harmless APP segment. */
internal fun jpeg(tag: Int): ByteArray = byteArrayOf(
    0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(), 0, 3, tag.toByte(),
    0xff.toByte(), 0xda.toByte(), 0, 2, 1, 2, 3, 0xff.toByte(), 0xd9.toByte(),
)
