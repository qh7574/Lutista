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

    private suspend fun data(op: PtpOp, timeoutMs: Int): PtpData {
        val output = ByteArrayOutputStream()
        val response = transport.transaction(op, expectedData = true, dataSink = { chunk, length ->
            if (output.size().toLong() + length > MAX_DIAGNOSTIC_BYTES) {
                throw PtpProtocolException("Diagnostic payload exceeds bounded buffer")
            }
            output.write(chunk, 0, length)
        }, timeoutMs = timeoutMs)
        return PtpData(response, output.toByteArray())
    }
    companion object { const val MAX_DIAGNOSTIC_BYTES = 4L * 1024 * 1024 }
}
