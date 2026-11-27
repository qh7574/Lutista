package cn.alittlecookie.lutista.tether.session

import cn.alittlecookie.lutista.tether.protocol.PtpConstants
import cn.alittlecookie.lutista.tether.transport.PtpProtocolException
import cn.alittlecookie.lutista.tether.transport.PtpOp
import cn.alittlecookie.lutista.tether.transport.PtpResponse
import cn.alittlecookie.lutista.tether.transport.Transport
import java.io.ByteArrayOutputStream

class PtpSessionApiImpl(private val transport: Transport) : PtpSessionApi {
    override var warmed: Boolean = false
        private set
    override suspend fun openSession(id: Long, timeoutMs: Int): PtpResponse {
        val response = transport.transaction(PtpOp(PtpConstants.Op.OPEN_SESSION, longArrayOf(id)), timeoutMs = timeoutMs)
        if (response.code == PtpConstants.Rc.OK) warmed = false
        return response
    }
    override suspend fun closeSession(timeoutMs: Int): PtpResponse {
        try { return transport.transaction(PtpOp(PtpConstants.Op.CLOSE_SESSION), timeoutMs = timeoutMs) }
        finally { warmed = false }
    }
    override suspend fun getDeviceInfo(timeoutMs: Int): PtpData =
        data(PtpOp(PtpConstants.Op.GET_DEVICE_INFO), timeoutMs).also {
            if (it.response.code == PtpConstants.Rc.OK) warmed = true
        }
    override suspend fun getStorageIds(timeoutMs: Int): PtpData =
        data(PtpOp(PtpConstants.Op.GET_STORAGE_IDS), timeoutMs).also {
            if (it.response.code == PtpConstants.Rc.OK) warmed = true
        }
    override suspend fun getObjectHandles(storage: Long, format: Long, parent: Long, timeoutMs: Int): PtpData {
        check(warmed) { "GetObjectHandles requires a successful post-OpenSession warm-up" }
        require(storage == PtpConstants.ALL_STORAGE_IDS) { "Public enumeration uses all storage IDs" }
        require(format == PtpConstants.ANY_OBJECT_FORMAT) { "Format filtering is unsupported" }
        require(parent in 1..0xFFFF_FFFFL) { "Enumerate the root sentinel or a directory handle, never parent=0" }
        return data(PtpOp(PtpConstants.Op.GET_OBJECT_HANDLES, longArrayOf(storage, format, parent)), timeoutMs)
    }
    override suspend fun getProperty(propId: Long, timeoutMs: Int) =
        data(PtpOp(PtpConstants.PanasonicOp.GET_PROP_VALUE, longArrayOf(propId)), timeoutMs)
    override suspend fun setProperty(propId: Long, payload: ByteArray, timeoutMs: Int): PtpResponse =
        transport.transaction(PtpOp(PtpConstants.PanasonicOp.SET_PROP_VALUE, longArrayOf(propId)), dataOut = payload, timeoutMs = timeoutMs)
    override suspend fun listPropertyDescriptor(propId: Long, timeoutMs: Int) =
        data(PtpOp(PtpConstants.PanasonicOp.GET_PROP_DESC, longArrayOf(propId, 0L, 0L)), timeoutMs)
    override suspend fun liveViewFormats(timeoutMs: Int) =
        data(PtpOp(PtpConstants.PanasonicOp.GET_LIVEVIEW_FORMATS, longArrayOf(PtpConstants.Param.LIVEVIEW_FORMAT_TABLE)), timeoutMs)

    override suspend fun getObjectInfo(handle: Long): PtpData =
        data(PtpOp(PtpConstants.Op.GET_OBJECT_INFO, longArrayOf(objectHandle(handle))), 1_000)

    override suspend fun getThumb(handle: Long): PtpData =
        data(PtpOp(PtpConstants.Op.GET_THUMB, longArrayOf(objectHandle(handle))), 4_000)

    /** The transport drains the transaction even if a consumer cancels or throws. */
    override suspend fun getObject(handle: Long, sink: (ByteArray, Int) -> Unit): PtpResponse =
        transport.transaction(PtpOp(PtpConstants.Op.GET_OBJECT, longArrayOf(objectHandle(handle))),
            expectedData = true, dataSink = sink, timeoutMs = 30_000)

    override suspend fun getPartialObject(handle: Long, offset: Long, length: Long): PtpData {
        objectHandle(handle)
        require(offset in 0..0xFFFF_FFFFL) { "Partial-object offset must fit u32" }
        require(length in 1..MAX_PARTIAL_BYTES) { "Partial-object length exceeds 512 KiB" }
        require(offset + length <= 0x1_0000_0000L) { "Partial-object range overflows u32" }
        return data(PtpOp(PtpConstants.Op.GET_PARTIAL_OBJECT, longArrayOf(handle, offset, length)),
            4_000, length)
    }

    override suspend fun liveView(on: Boolean): PtpResponse = transport.transaction(
        PtpOp(PtpConstants.PanasonicOp.LIVEVIEW_CONTROL,
            longArrayOf(if (on) 0x0D000010L else 0x0D000011L)), timeoutMs = 1_000)

    override suspend fun liveViewFrame(sink: (ByteArray, Int) -> Unit): PtpResponse =
        transport.transaction(PtpOp(PtpConstants.PanasonicOp.GET_LIVEVIEW_FRAME),
            expectedData = true, dataSink = sink, timeoutMs = 1_000)

    override suspend fun setLiveViewFormat(payload: ByteArray): PtpResponse {
        require(payload.size == 16) { "A liveview format is the exact 16-byte table tuple payload" }
        // 0x9415 / 0x0D800011: v7 §10, confirmed by the final protocol notes.
        return transport.transaction(PtpOp(0x9415, longArrayOf(0x0D800011L)),
            dataOut = payload, timeoutMs = 1_000)
    }

    /** release is retained for source compatibility; S1 has one discrete shutter command. */
    override suspend fun initiateCapture(release: Boolean): PtpResponse = shutter()
    /** The device command toggles; start/stop intent is maintained by CaptureController. */
    override suspend fun movieRec(start: Boolean): PtpResponse = shutter()
    private suspend fun shutter(): PtpResponse = transport.transaction(
        PtpOp(PtpConstants.PanasonicOp.CAPTURE, longArrayOf(0x03000011L)), timeoutMs = 1_000)

    private fun objectHandle(handle: Long): Long {
        require(handle in 1..0xFFFF_FFFEL) { "Object handle must be a non-sentinel u32" }
        return handle
    }

    private suspend fun data(op: PtpOp, timeoutMs: Int, maxBytes: Long = MAX_DIAGNOSTIC_BYTES): PtpData {
        val output = ByteArrayOutputStream()
        val response = transport.transaction(op, expectedData = true, dataSink = { chunk, length ->
            if (length !in 0..chunk.size || output.size().toLong() + length > maxBytes) {
                throw PtpProtocolException("Diagnostic payload exceeds bounded buffer")
            }
            output.write(chunk, 0, length)
        }, timeoutMs = timeoutMs)
        return PtpData(response, output.toByteArray())
    }
    companion object {
        const val MAX_DIAGNOSTIC_BYTES = 4L * 1024 * 1024
        const val MAX_PARTIAL_BYTES = 512L * 1024
    }
}
