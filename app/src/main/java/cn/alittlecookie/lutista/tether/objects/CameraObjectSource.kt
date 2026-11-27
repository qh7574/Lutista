package cn.alittlecookie.lutista.tether.objects

import kotlinx.coroutines.flow.Flow

/** Handles are valid only in this serial + connection namespace. */
data class CameraSessionKey(val serial: String, val connectAt: Long) {
    init {
        require(serial.isNotBlank()) { "A camera serial (or stable device identity) is required" }
        require(connectAt >= 0) { "connectAt must be non-negative" }
    }
    val id: String get() = "${serial.length}:$serial:$connectAt"
}

data class CameraObject(
    val handle: Long,
    val storageId: Long,
    val format: Long,
    val parent: Long,
    val size: Long,
    val name: String,
    /** Camera clock text, preserved verbatim; never the primary gallery sort key. */
    val dateTakenRaw: String,
    val isDir: Boolean,
    val seq: Long = 0,
)

data class ObjectIdentity(val storageId: Long, val handle: Long)

sealed interface ObjectPage {
    /** At most 50 objects, in discovery order. Includes directories. */
    data class Batch(val items: List<CameraObject>) : ObjectPage
    data class Complete(val total: Int, val byStorage: Map<Long, Int>) : ObjectPage
}

interface CameraObjectSource {
    val sessionKey: CameraSessionKey
    /** Root query followed by directory descent. A probe seed contains roots only. */
    fun listTree(): Flow<ObjectPage>
    suspend fun listChildren(parent: Long, storageId: Long? = null): List<CameraObject>
    suspend fun getObjectInfo(handle: Long): CameraObject
    suspend fun fetchThumb(handle: Long): ByteArray
    /** Only the first 512 KiB is read. Returns the largest decodable embedded JPEG. */
    suspend fun fetchRawPreview(handle: Long): ByteArray?
    /** Bounded to 4 MiB. Larger objects must use [streamObject]. */
    suspend fun fetchObject(handle: Long): ByteArray
    /** Synchronous payload sink: consume/copy length bytes before returning. */
    suspend fun streamObject(
        handle: Long,
        sink: (ByteArray, Int) -> Unit,
        onProgress: (Long) -> Unit = {},
    ): Long
}
