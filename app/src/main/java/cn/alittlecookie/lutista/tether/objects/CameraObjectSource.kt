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

/** A discovered directory rejected its own child query; other branches can remain readable. */
data class UnavailableDirectory(val directory: CameraObject, val responseCode: Int)

sealed interface ObjectPage {
    /** At most 50 objects, in discovery order. Includes directories. */
    data class Batch(val items: List<CameraObject>) : ObjectPage
    data class Complete(
        val total: Int,
        val byStorage: Map<Long, Int>,
        val unavailableDirectories: List<UnavailableDirectory> = emptyList(),
    ) : ObjectPage
}

interface CameraObjectSource {
    val sessionKey: CameraSessionKey
    /** Root query followed by directory descent. A probe seed contains roots only. */
    fun listTree(): Flow<ObjectPage>
    suspend fun listChildren(parent: Long, storageId: Long? = null): List<CameraObject>
    suspend fun getObjectInfo(handle: Long): CameraObject
    suspend fun fetchThumb(handle: Long): ByteArray
    /** Reads an arbitrary window of an object (one PTP transaction, at most 512 KiB).
     * Used to seed a range-backed video decoder without downloading the whole clip. */
    suspend fun fetchRange(handle: Long, offset: Long, length: Int): ByteArray =
        throw java.io.IOException("Range reads are not supported by this camera source")
    /** Only the first 512 KiB is read. Returns the largest decodable embedded JPEG. */
    suspend fun fetchRawPreview(handle: Long): ByteArray?

    /**
     * 读出对象头部内嵌的 JPEG 预览（JPEG 与 RAW 都适用），最多 512 KiB。
     *
     * 真机 `logs_1795841499743` 证明「全屏预览拉整张 9 MB 原图」会在用户返回时取消数据段，
     * 把相机逼进拒收命令的状态；因此 JPEG 预览也走这条有界读取。
     * 默认退化为 [fetchRawPreview]，由实现类覆盖成真正的「按头部扫描」。
     */
    suspend fun fetchEmbeddedPreview(handle: Long): ByteArray? = fetchRawPreview(handle)
    /** Bounded to 4 MiB. Larger objects must use [streamObject]. */
    suspend fun fetchObject(handle: Long): ByteArray

    /**
     * 分块整份下载：每一块都是一次独立的 PTP 事务（`GetPartialObject`，≤512 KiB）。
     *
     * 这是「清晰大图」的安全通道：**取消只会发生在事务边界**，绝不会像整段 `GetObject`
     * 那样把相机留在数据段中途（真机 logs_1795841499743 12:51 两次这样把相机逼死）。
     * 默认实现退化为一次 [streamObject]，实现类应覆盖成分块循环。
     *
     * @return 实际读到的字节数；`< size` 说明对象在中途结束（相机侧异常）。
     */
    suspend fun streamObjectChunked(
        handle: Long,
        size: Long,
        onChunk: (ByteArray) -> Unit,
    ): Long = streamObject(handle, sink = { bytes, length -> onChunk(bytes.copyOf(length)) })
    /** Synchronous payload sink: consume/copy length bytes before returning. */
    suspend fun streamObject(
        handle: Long,
        sink: (ByteArray, Int) -> Unit,
        onProgress: (Long) -> Unit = {},
    ): Long
}
