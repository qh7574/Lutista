package cn.alittlecookie.lutista.tether.objects

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import cn.alittlecookie.lutista.tether.profile.PtpQuirks
import cn.alittlecookie.lutista.tether.protocol.PtpConstants
import cn.alittlecookie.lutista.tether.session.CameraSession
import cn.alittlecookie.lutista.tether.session.PtpData
import cn.alittlecookie.lutista.tether.session.PtpScheduler
import cn.alittlecookie.lutista.tether.session.PtpSessionApi
import cn.alittlecookie.lutista.tether.transport.PtpResponse
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.yield

class CameraObjectResponseException(val operation: String, val responseCode: Int) :
    IOException("$operation rejected: 0x${responseCode.toString(16)}")

/** All physical calls in the production adapter are admitted through CameraSession.runOp. */
internal interface ObjectAccess {
    suspend fun handles(parent: Long): PtpData
    suspend fun info(handle: Long): PtpData
    suspend fun thumb(handle: Long): PtpData
    suspend fun partial(handle: Long, offset: Long, length: Long): PtpData
    suspend fun stream(handle: Long, sink: (ByteArray, Int) -> Unit): PtpResponse
}

class PtpObjectSource internal constructor(
    private val access: ObjectAccess,
    private val quirks: PtpQuirks,
    override val sessionKey: CameraSessionKey,
    gallerySeed: List<Long>? = null,
    private val decodeJpeg: (ByteArray, Int, Int) -> JpegScanner.Size? = ::decodeJpegSize,
) : CameraObjectSource {
    private val rootSeed = gallerySeed?.toList()?.also { roots -> roots.forEach(ObjectInfoParser::requireHandle) }

    constructor(
        session: CameraSession,
        quirks: PtpQuirks,
        sessionKey: CameraSessionKey,
        gallerySeed: List<Long>? = null,
    ) : this(ScheduledObjectAccess(session), quirks, sessionKey, gallerySeed)

    override fun listTree(): Flow<ObjectPage> = flow {
        // Never use the obsolete parent=0 full-enumeration path. Even a probe seed is only the root level.
        val visited = HashSet<ObjectIdentity>()
        val directories = ArrayDeque<CameraObject>()
        val unavailableDirectories = ArrayList<UnavailableDirectory>()
        val byStorage = linkedMapOf<Long, Int>()
        val pending = ArrayList<CameraObject>(PAGE_SIZE)
        var sequence = 0L
        var workSinceYield = 0
        suspend fun accept(handles: List<Long>, parent: CameraObject?) {
            for (handle in handles.distinct().sortedDescending()) {
                currentCoroutineContext().ensureActive()
                val info = getObjectInfo(handle)
                val belongs = if (parent == null) isRootParent(info.parent)
                    else info.storageId == parent.storageId && info.parent == parent.handle
                if (belongs && visited.add(ObjectIdentity(info.storageId, info.handle))) {
                    check(visited.size <= MAX_OBJECTS) { "Camera object tree exceeds safety limit" }
                    val item = info.copy(seq = sequence++)
                    byStorage[item.storageId] = (byStorage[item.storageId] ?: 0) + 1
                    pending += item
                    if (item.isDir) directories.addLast(item)
                    if (pending.size == PAGE_SIZE) {
                        emit(ObjectPage.Batch(pending.toList()))
                        pending.clear()
                    }
                }
                if (++workSinceYield == PAGE_SIZE) {
                    workSinceYield = 0
                    yield()
                }
            }
            // A small root/directory batch must become visible without waiting for the whole tree.
            if (pending.isNotEmpty()) {
                emit(ObjectPage.Batch(pending.toList()))
                pending.clear()
            }
        }
        accept(rootSeed ?: readHandles(PtpConstants.ROOT_PARENT_HANDLE), null)
        while (directories.isNotEmpty()) {
            val directory = directories.removeFirst()
            val children = try {
                readHandles(directory.handle)
            } catch (failure: CameraObjectResponseException) {
                // DC-S1 exposes 101_MRRC associations that reject their child query with 0x2013,
                // while the sibling 102_MRRC is readable (TV-02b, 2026-11-26). This is branch-local:
                // never retry parent=0, swallow a root failure, or hide another response/USB error.
                if (failure.responseCode != PtpConstants.Rc.STORE_NOT_AVAILABLE) throw failure
                unavailableDirectories += UnavailableDirectory(directory, failure.responseCode)
                continue
            }
            accept(children, directory)
        }
        emit(ObjectPage.Complete(visited.size, byStorage.toMap(), unavailableDirectories.toList()))
    }

    override suspend fun listChildren(parent: Long, storageId: Long?): List<CameraObject> {
        require(parent == PtpConstants.ROOT_PARENT_HANDLE || parent in 1L..0xFFFF_FFFEL) {
            "Use root=0xFFFFFFFF or a directory handle; parent=0 enumeration is forbidden"
        }
        val result = linkedMapOf<ObjectIdentity, CameraObject>()
        for ((index, handle) in readHandles(parent).distinct().sortedDescending().withIndex()) {
            val info = getObjectInfo(handle)
            if ((storageId == null || info.storageId == storageId) &&
                (if (parent == PtpConstants.ROOT_PARENT_HANDLE) isRootParent(info.parent) else info.parent == parent)) {
                result.putIfAbsent(ObjectIdentity(info.storageId, info.handle), info.copy(seq = index.toLong()))
            }
            if ((index + 1) % PAGE_SIZE == 0) yield()
        }
        return result.values.toList()
    }

    override suspend fun getObjectInfo(handle: Long): CameraObject {
        ObjectInfoParser.requireHandle(handle)
        return ObjectInfoParser.parse(handle, payload(access.info(handle), "GetObjectInfo"), quirks)
    }

    override suspend fun fetchThumb(handle: Long): ByteArray {
        ObjectInfoParser.requireHandle(handle)
        return payload(access.thumb(handle), "GetThumb")
    }

    override suspend fun fetchRawPreview(handle: Long): ByteArray? {
        require(getObjectInfo(handle).format == quirks.rawFormat) { "Object is not a profile RAW format" }
        return fetchEmbeddedPreview(handle)
    }

    override suspend fun fetchEmbeddedPreview(handle: Long): ByteArray? {
        ObjectInfoParser.requireHandle(handle)
        val preview = try {
            val head = payload(access.partial(handle, 0L, RAW_PREVIEW_BYTES.toLong()), "GetPartialObject")
            check(head.size <= RAW_PREVIEW_BYTES) { "Embedded preview exceeded its requested window" }
            try { JpegScanner.largestDecodable(head, decodeJpeg) } catch (_: IllegalArgumentException) { null }
        } catch (_: CameraObjectResponseException) {
            null
        }
        if (preview != null) return preview
        // Only recover from a protocol rejection/no embedded preview. Transport failures propagate unchanged.
        return try { fetchThumb(handle) } catch (_: CameraObjectResponseException) { null }
    }

    override suspend fun fetchRange(handle: Long, offset: Long, length: Int): ByteArray {
        require(offset >= 0) { "Partial-object offset must be non-negative" }
        require(length in 1..RAW_PREVIEW_BYTES) { "Partial-object length exceeds 512 KiB" }
        return payload(access.partial(handle, offset, length.toLong()), "GetPartialObject")
    }

    override suspend fun fetchObject(handle: Long): ByteArray {
        val info = getObjectInfo(handle)
        require(!info.isDir && info.format == quirks.jpegFormat) { "In-memory full preview is JPEG only" }
        require(info.size <= MAX_IN_MEMORY_BYTES) { "Object exceeds 4 MiB; use streamObject" }
        val output = ByteArrayOutputStream(info.size.toInt())
        val response = access.stream(handle) { bytes, length ->
            checkChunk(bytes, length)
            check(output.size().toLong() + length <= MAX_IN_MEMORY_BYTES) { "Object payload exceeds 4 MiB" }
            output.write(bytes, 0, length)
        }
        requireOk(response, "GetObject")
        check(output.size().toLong() == info.size) { "Object length mismatch: expected ${info.size}, got ${output.size()}" }
        return output.toByteArray()
    }

    override suspend fun streamObject(handle: Long, sink: (ByteArray, Int) -> Unit, onProgress: (Long) -> Unit): Long {
        val info = getObjectInfo(handle)
        require(!info.isDir) { "A directory cannot be downloaded" }
        var received = 0L
        val context = currentCoroutineContext()
        val response = access.stream(handle) { bytes, length ->
            // 取消点：**只有在已经传了 1 MiB 以上时**才生效（libgphoto2 `ptp_usb_getdata`
            // 同款门槛，原注释是「canceling did not work on ... of 200kb」）。200 KiB 级的
            // 请求几十毫秒就跑完，为它中途撒手只会在相机侧留下半截数据段——真机
            // `logs_1795791570292` 22:58:28 的 `OUT -1` 风暴正是这么来的，收益为零。
            // 「停下之后相机会停在数据段」的收尾由传输层负责（PtpUsbTransport 的取消路径会
            // 发类请求 Cancel + 按声明长度精确排空），这里**不要**自己吞掉取消。
            if (received >= CANCEL_GRACE_BYTES) context.ensureActive()
            checkChunk(bytes, length)
            received = Math.addExact(received, length.toLong())
            check(received <= info.size) { "Object payload exceeds its declared size" }
            sink(bytes, length)
            onProgress(received)
        }
        requireOk(response, "GetObject")
        check(received == info.size) { "Object length mismatch: expected ${info.size}, got $received" }
        return received
    }

    private suspend fun readHandles(parent: Long): List<Long> =
        ObjectInfoParser.handles(payload(access.handles(parent), "GetObjectHandles"))

    private fun payload(data: PtpData, operation: String): ByteArray {
        requireOk(data.response, operation)
        check(data.payload.size.toLong() <= MAX_IN_MEMORY_BYTES) { "$operation payload exceeds 4 MiB" }
        return data.payload
    }

    private fun requireOk(response: PtpResponse, operation: String) {
        if (response.code != PtpConstants.Rc.OK) throw CameraObjectResponseException(operation, response.code)
    }

    private fun checkChunk(bytes: ByteArray, length: Int) {
        require(length in 0..bytes.size) { "Invalid streaming payload length" }
    }

    companion object {
        const val PAGE_SIZE = 50
        const val MAX_IN_MEMORY_BYTES = 4L * 1024 * 1024
        const val RAW_PREVIEW_BYTES = 512 * 1024

        /**
         * 流式下载允许「中途取消」的最小已传字节数。
         *
         * 对齐 libgphoto2 `ptp_usb_getdata` 的取消门槛（1 MiB）：小对象一次传完比「取消 +
         * 复位通道」划算得多，而中途撒手会让相机留在数据段半途，后续命令全部 `OUT -1`。
         */
        const val CANCEL_GRACE_BYTES = 1L * 1024 * 1024
        private const val MAX_OBJECTS = 100_000
        private fun isRootParent(parent: Long) = parent == 0L || parent == PtpConstants.ROOT_PARENT_HANDLE
    }
}

private class ScheduledObjectAccess(private val session: CameraSession) : ObjectAccess {
    // A retained old source must never address the same handles in a later connection.
    private val connection = session.deviceCaps.value
    private suspend fun <T> run(block: suspend (PtpSessionApi) -> T): T =
        session.runOp(PtpScheduler.Priority.USER) { api ->
            check(connection != null && session.deviceCaps.value === connection) { "Camera object session is stale" }
            block(api)
        }
    override suspend fun handles(parent: Long) = run {
        it.getObjectHandles(PtpConstants.ALL_STORAGE_IDS, PtpConstants.ANY_OBJECT_FORMAT, parent)
    }
    override suspend fun info(handle: Long) = run { it.getObjectInfo(handle) }
    override suspend fun thumb(handle: Long) = run { it.getThumb(handle) }
    override suspend fun partial(handle: Long, offset: Long, length: Long) = run { it.getPartialObject(handle, offset, length) }
    override suspend fun stream(handle: Long, sink: (ByteArray, Int) -> Unit) = run { it.getObject(handle, sink) }
}

/** Bounds first, then a real sampled decode; bounds alone do not prove a JPEG is decodable. */
private fun decodeJpegSize(bytes: ByteArray, offset: Int, length: Int): JpegScanner.Size? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, offset, length, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while ((bounds.outWidth.toLong() / sample) * (bounds.outHeight.toLong() / sample) > 1_048_576L) sample *= 2
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    val bitmap = BitmapFactory.decodeByteArray(bytes, offset, length, options) ?: return null
    bitmap.recycle()
    return JpegScanner.Size(bounds.outWidth, bounds.outHeight)
}
