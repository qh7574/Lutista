package cn.alittlecookie.lutista.tether.session

import cn.alittlecookie.lutista.tether.protocol.PtpConstants
import cn.alittlecookie.lutista.tether.transport.PtpResponse

data class PtpData(val response: PtpResponse, val payload: ByteArray)

interface PtpSessionApi {
    val warmed: Boolean
    suspend fun openSession(id: Long = PtpConstants.DEFAULT_SESSION_ID, timeoutMs: Int = 1_000): PtpResponse
    suspend fun closeSession(timeoutMs: Int = 1_000): PtpResponse
    suspend fun getDeviceInfo(timeoutMs: Int = 1_000): PtpData
    suspend fun getStorageIds(timeoutMs: Int = 1_000): PtpData
    suspend fun getObjectHandles(
        storage: Long = PtpConstants.ALL_STORAGE_IDS,
        format: Long = 0L,
        parent: Long = PtpConstants.ROOT_PARENT_HANDLE,
        timeoutMs: Int = 500,
    ): PtpData
    suspend fun getProperty(propId: Long, timeoutMs: Int = 200): PtpData
    suspend fun setProperty(propId: Long, payload: ByteArray, timeoutMs: Int = 1_000): PtpResponse
    suspend fun listPropertyDescriptor(propId: Long, timeoutMs: Int = 300): PtpData
    suspend fun liveViewFormats(timeoutMs: Int = 300): PtpData
    suspend fun getObjectInfo(handle: Long): PtpData = deferredFeature()
    suspend fun getThumb(handle: Long): PtpData = deferredFeature()
    suspend fun getObject(handle: Long, sink: (ByteArray, Int) -> Unit): PtpResponse = deferredFeature()
    suspend fun getPartialObject(handle: Long, offset: Long, length: Long): PtpData = deferredFeature()
    suspend fun liveView(on: Boolean): PtpResponse = deferredFeature()
    suspend fun liveViewFrame(sink: (ByteArray, Int) -> Unit): PtpResponse = deferredFeature()
    suspend fun setLiveViewFormat(payload: ByteArray): PtpResponse = deferredFeature()
    suspend fun initiateCapture(release: Boolean): PtpResponse = deferredFeature()
    suspend fun movieRec(start: Boolean): PtpResponse = deferredFeature()
}

private fun deferredFeature(): Nothing = throw UnsupportedOperationException("This operation belongs to a later tether delivery")
