package cn.alittlecookie.lutista.tether.objects

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import androidx.core.net.toUri
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import coil.ImageLoader
import coil.request.ImageResult
import coil.intercept.Interceptor
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.Options
import coil.size.Size
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import okio.Buffer
import okio.Path.Companion.toOkioPath
import okio.buffer
import okio.source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import timber.log.Timber

private val imageFetchPermits = Semaphore(2)

/** 清晰大图同一时刻只允许一个下载：串行化既省 USB 带宽，也避免两条数据段互相干扰。 */
private val fullJpegDownloadLock = Mutex()

/** Includes both connection and storage: reconnecting must not reuse old handle pixels. */
data class CameraThumbKey(
    val session: CameraSessionKey,
    val storageId: Long,
    val handle: Long,
    val kind: Kind = Kind.THUMB_160,
    val stamp: Long = 0,
) {
    enum class Kind { THUMB_160, RAW_PREVIEW_1920, FULL_JPEG }
    val cacheKey: String get() = "tether:${session.id}:$storageId:$handle:${kind.name}:$stamp"
}

class CameraThumbKeyer : Keyer<CameraThumbKey> {
    override fun key(data: CameraThumbKey, options: Options): String = data.cacheKey
}

class CameraThumbFetcher(
    private val sourceProvider: () -> CameraObjectSource?,
    private val key: CameraThumbKey,
    private val options: Options,
    private val previews: TetherPreviewCache,
    private val localOriginal: (Long) -> String?,
) : Fetcher {
    override suspend fun fetch(): FetchResult? = imageFetchPermits.withPermit { fetchBounded() }

    private suspend fun fetchBounded(): FetchResult? {
        val source = sourceProvider() ?: throw IOException("Camera object source is disconnected")
        check(source.sessionKey == key.session) { "Camera image request belongs to an expired session" }
        if (key.kind == CameraThumbKey.Kind.FULL_JPEG) return fullJpeg(source)
        return embeddedPreviewResult(source)
    }

    /** 内嵌预览（缩略图或 RAW 的 1920 内嵌 JPEG）：≤4 MiB，一次性放进内存。 */
    private suspend fun embeddedPreviewResult(source: CameraObjectSource): FetchResult? {
        val bytes = when (key.kind) {
            CameraThumbKey.Kind.RAW_PREVIEW_1920 -> source.fetchEmbeddedPreview(key.handle)
            CameraThumbKey.Kind.THUMB_160, CameraThumbKey.Kind.FULL_JPEG -> thumbnailOrVideoFrame(source, key.handle)
        } ?: return null
        check(bytes.size.toLong() <= PtpObjectSource.MAX_IN_MEMORY_BYTES) { "Camera preview exceeds 4 MiB" }
        return SourceResult(
            source = ImageSource(source = Buffer().write(bytes), context = options.context),
            mimeType = "image/jpeg", dataSource = DataSource.NETWORK,
        )
    }

    /**
     * 清晰大图（FULL_JPEG）：**本地原片 → 磁盘缓存 → 分块下载**。
     *
     * 三条路径都不会把相机留在数据段中途：
     * - 本地原片：自动回传/手动下载过的图直接读本地文件，零 USB 流量；
     * - 磁盘缓存：128 MiB / 7 天 LRU，第二次打开同一张图不再走 USB；
     * - 分块下载：每块 512 KiB 一次独立 PTP 事务，取消只发生在事务边界。
     */
    private suspend fun fullJpeg(source: CameraObjectSource): FetchResult? {
        val info = source.getObjectInfo(key.handle)
        check(info.storageId == key.storageId) { "Full image preview requires the requested storage" }
        if (info.isDir || info.format != 0x3801L) {
            // 非 JPEG 没有「整份下载」之外的清晰路径，硬走会抛异常导致整张图空白；
            // 降级成内嵌预览（RAW 的 1920 长边 JPEG）至少能看，且不额外占 USB 带宽。
            Timber.tag(TAG_THUMB).d(
                "整图预览降级为内嵌预览 handle=0x%x format=0x%x",
                key.handle, info.format,
            )
            return embeddedPreviewResult(source)
        }
        val stamp = info.dateTakenRaw.hashCode().toLong()
        val cacheKey = previews.keyOf(key, info.size, stamp)
        localOriginal(key.handle)?.let { local ->
            localSource(local)?.let {
                Timber.tag(TAG_THUMB).v("整图预览命中本地原片 handle=0x%x", key.handle)
                return it
            }
        }
        previews.get(cacheKey)?.let { file ->
            Timber.tag(TAG_THUMB).v("整图预览命中磁盘缓存 handle=0x%x file=%s", key.handle, file.name)
            return fileResult(file)
        }
        return fileResult(downloadFullJpeg(source, cacheKey, info.size))
    }

    /** 同一时刻只允许一个大图下载：串行化既保护 USB 带宽，也避免两条数据段互相干扰。 */
    private suspend fun downloadFullJpeg(source: CameraObjectSource, cacheKey: String, size: Long): File =
        fullJpegDownloadLock.withLock {
            previews.get(cacheKey)?.let { return@withLock it }
            val temp = previews.tempFile(cacheKey)
            val startedAt = System.nanoTime()
            try {
                var written = 0L
                temp.outputStream().use { output ->
                    written = source.streamObjectChunked(key.handle, size) { bytes -> output.write(bytes) }
                }
                check(written == size) { "整图预览下载不完整：$written/$size" }
                val elapsed = (System.nanoTime() - startedAt) / 1_000_000
                Timber.tag(TAG_THUMB).i(
                    "整图预览下载完成 handle=0x%x bytes=%d elapsed=%dms",
                    key.handle, written, elapsed,
                )
                previews.commit(temp, cacheKey)
            } catch (cancel: CancellationException) {
                temp.delete()
                throw cancel
            } catch (failure: Throwable) {
                temp.delete()
                throw failure
            }
        }

    /** 本地原片可能是 SAF `content://` 文档，也可能是普通文件路径。 */
    private fun localSource(local: String): FetchResult? = runCatching {
        if (local.startsWith("content://")) {
            val uri = local.toUri()
            val stream = options.context.contentResolver.openInputStream(uri)
                ?: throw IOException("无法读取本地原片：$local")
            SourceResult(
                source = ImageSource(source = stream.source().buffer(), context = options.context),
                mimeType = "image/jpeg",
                dataSource = DataSource.DISK,
            )
        } else {
            val file = File(local)
            if (!file.isFile) throw IOException("本地原片不存在：$local")
            fileResult(file)
        }
    }.onFailure { Timber.tag(TAG_THUMB).w(it, "整图预览读取本地原片失败：%s", local) }.getOrNull()

    private fun fileResult(file: File): FetchResult = SourceResult(
        source = ImageSource(file = file.toOkioPath()),
        mimeType = "image/jpeg",
        dataSource = DataSource.DISK,
    )

    /**
     * Some cameras answer GetThumb for movies with a broken payload or InvalidObjectHandle.
     * Fall back to decoding the first sync frame from sparse GetPartialObject reads so the
     * gallery still shows a poster frame without downloading the whole movie.
     *
     * Every branch is logged: the previous build failed silently, and the hardware log had no
     * evidence at all about which of the three exits (GetThumb / frame decode / nothing) happened.
     */
    private suspend fun thumbnailOrVideoFrame(source: CameraObjectSource, handle: Long): ByteArray? {
        val thumbnail = try {
            source.fetchThumb(handle)
        } catch (cancel: CancellationException) {
            // 取消不是「GetThumb 失败」：吞掉它会把一次用户滑动变成“成功返回占位图”，
            // 协程继续跑（D6）。传输层已经做完收尾，这里只负责把取消原样抛出去。
            throw cancel
        } catch (failure: Exception) {
            Timber.tag(TAG_THUMB).i(
                failure,
                "GetThumb 失败 handle=0x%s，改用视频首帧",
                handle.toString(16),
            )
            // 首帧也失败时不要抛出去：整格会变成空白，用户看不到任何东西。
            // 保留一个可见占位，比“什么都没有”更接近可用。
            return videoPoster(source, handle) ?: placeholderPoster()
        }
        if (thumbnail.looksLikeJpeg()) {
            Timber.tag(TAG_THUMB).v("GetThumb 成功 handle=0x%s %d 字节", handle.toString(16), thumbnail.size)
            return thumbnail
        }
        Timber.tag(TAG_THUMB).i(
            "GetThumb 不是 JPEG（%d 字节，首字节=0x%02x）handle=0x%s，改用视频首帧",
            thumbnail.size,
            thumbnail.firstOrNull()?.toInt()?.and(0xFF) ?: -1,
            handle.toString(16),
        )
        return videoPoster(source, handle) ?: thumbnail
    }

    /** 视频海报：先尝试真首帧，失败则给可见占位，绝不返回 null。 */
    private suspend fun videoPoster(source: CameraObjectSource, handle: Long): ByteArray? =
        videoFrame(source, handle) ?: placeholderPoster()

    private suspend fun videoFrame(source: CameraObjectSource, handle: Long): ByteArray? {
        val info = try {
            source.getObjectInfo(handle)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            Timber.tag(TAG_THUMB).w(failure, "视频首帧：getObjectInfo 失败 handle=0x%s", handle.toString(16))
            return null
        }
        val extension = info.name.substringAfterLast('.', "").lowercase()
        if (info.format != MOVIE_FORMAT && extension !in MOVIE_EXTENSIONS) {
            Timber.tag(TAG_THUMB).i(
                "视频首帧：对象不是视频（name=%s format=0x%04x）handle=0x%s",
                info.name,
                info.format,
                handle.toString(16),
            )
            return null
        }
        if (info.size <= 0) {
            Timber.tag(TAG_THUMB).w("视频首帧：对象长度非法 size=%d handle=0x%s", info.size, handle.toString(16))
            return null
        }
        // 1) 稀疏区间读：只取 moov 与首个关键帧，几乎不占 USB 带宽。
        sparseFrame(source, handle, info.size)?.let { return it }
        // 2) 降级：这台相机的 GetPartialObject 对 MOV 可能直接拒绝（InvalidObjectHandle），
        //    而 GetObject 整段读取在真机上是验证过可用的（约 22 MB/s）。只在体积可控时降级，
        //    这样视频格至少能显示真实首帧，而不是永远占位。
        if (info.size > MAX_POSTER_DOWNLOAD_BYTES) {
            Timber.tag(TAG_THUMB).w(
                "视频首帧：区间读失败且体积超限（%d 字节），保留占位 handle=0x%s",
                info.size,
                handle.toString(16),
            )
            return null
        }
        return downloadedFrame(source, handle, info.size)
    }

    private fun sparseFrame(source: CameraObjectSource, handle: Long, size: Long): ByteArray? {
        val data = PtpRangeMediaDataSource(source, handle, size)
        return try {
            decodeFrame(
                label = "稀疏读",
                handle = handle,
                detail = { "rangeReads=${data.readCount} bytes=${data.readBytes}" },
            ) { retriever -> retriever.setDataSource(data) }
        } finally {
            data.close()
        }
    }

    private suspend fun downloadedFrame(
        source: CameraObjectSource,
        handle: Long,
        size: Long,
    ): ByteArray? {
        val file = try {
            File.createTempFile("tether-video-", ".mov", options.context.cacheDir)
        } catch (failure: Exception) {
            Timber.tag(TAG_THUMB).w(failure, "视频首帧：无法创建临时文件 handle=0x%s", handle.toString(16))
            return null
        }
        return try {
            file.outputStream().use { output ->
                source.streamObject(handle, { bytes, length -> output.write(bytes, 0, length) })
            }
            Timber.tag(TAG_THUMB).i(
                "视频首帧：区间读不可用，已整段下载 %d 字节用于取帧 handle=0x%s",
                file.length(),
                handle.toString(16),
            )
            decodeFrame("整段下载", handle, { "bytes=${file.length()}/${size}" }) { retriever ->
                retriever.setDataSource(file.absolutePath)
            }
        } catch (cancel: CancellationException) {
            // 整段下载是唯一可能被取消打断数据段的路径：transport 已按 libgphoto2 的方式
            // 收尾（Cancel + 精确排空），这里必须把取消继续往上抛，不能变成占位图。
            throw cancel
        } catch (failure: Exception) {
            Timber.tag(TAG_THUMB).w(failure, "视频首帧：整段下载取帧失败 handle=0x%s", handle.toString(16))
            null
        } finally {
            file.delete()
        }
    }

    private fun decodeFrame(
        label: String,
        handle: Long,
        detail: () -> String,
        bind: (MediaMetadataRetriever) -> Unit,
    ): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            bind(retriever)
            val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_PREVIOUS_SYNC)
            if (frame == null) {
                Timber.tag(TAG_THUMB).w(
                    "视频首帧[%s]：取帧失败（%s）handle=0x%s",
                    label,
                    detail(),
                    handle.toString(16),
                )
                return null
            }
            try {
                val poster = encodePoster(frame)
                if (poster != null) {
                    Timber.tag(TAG_THUMB).i(
                        "视频首帧[%s]：成功 handle=0x%s %s poster=%d",
                        label,
                        handle.toString(16),
                        detail(),
                        poster.size,
                    )
                }
                poster
            } finally {
                frame.recycle()
            }
        } catch (failure: Exception) {
            Timber.tag(TAG_THUMB).w(
                failure,
                "视频首帧[%s]：异常 handle=0x%s %s",
                label,
                handle.toString(16),
                detail(),
            )
            null
        } finally {
            retriever.release()
        }
    }

    /** 相机侧完全拿不到画面时的可见占位（深色卡片 + 播放三角），保证图库不出现空白格。 */
    private fun placeholderPoster(): ByteArray? = runCatching {
        val width = VIDEO_FRAME_LONG_EDGE
        val height = width * 9 / 16
        val bitmap = createBitmap(width, height, Bitmap.Config.RGB_565)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(0xFF202124.toInt())
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE8EAED.toInt() }
            val cx = width / 2f
            val cy = height / 2f
            val radius = height * 0.22f
            val path = Path().apply {
                moveTo(cx - radius * 0.6f, cy - radius)
                lineTo(cx + radius, cy)
                lineTo(cx - radius * 0.6f, cy + radius)
                close()
            }
            canvas.drawPath(path, paint)
            ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, output)
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }.getOrNull()

    private fun encodePoster(frame: Bitmap): ByteArray? {
        val scaled = if (frame.width > VIDEO_FRAME_LONG_EDGE) {
            val height = maxOf(1, frame.height * VIDEO_FRAME_LONG_EDGE / frame.width)
            frame.scale(VIDEO_FRAME_LONG_EDGE, height)
        } else {
            frame
        }
        return try {
            ByteArrayOutputStream().use { output ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, output)
                output.toByteArray()
            }
        } finally {
            if (scaled !== frame) scaled.recycle()
        }
    }

    class Factory(
        private val source: () -> CameraObjectSource?,
        private val previews: TetherPreviewCache,
        private val localOriginal: (Long) -> String?,
    ) : Fetcher.Factory<CameraThumbKey> {
        override fun create(data: CameraThumbKey, options: Options, imageLoader: ImageLoader): Fetcher =
            CameraThumbFetcher(source, data, options, previews, localOriginal)
    }
}

private const val MOVIE_FORMAT = 0x300DL
private const val VIDEO_FRAME_LONG_EDGE = 320
private val MOVIE_EXTENSIONS = setOf("mov", "mp4")

/**
 * 整段下载取帧的体积上限。
 *
 * 从 64 MiB 收到 8 MiB 的原因（真机 logs_1795788978642）：一段 58.9 MB 的 MOV 走这条路径，
 * 传输到一半被 Coil 取消，**相机被留在数据段里**，之后所有命令 bulk OUT 立即 -1，
 * 用户看到的是「查看机内照片时 USB 断开且无法恢复」。8 MiB 只覆盖短片段；
 * 更长的视频直接落到 [placeholderPoster]（320×180 深底 + 播放三角），
 * 即「宁可显示一个正确的占位，也不为缩略图拖动几十 MB 的 USB 流量」。
 */
private const val MAX_POSTER_DOWNLOAD_BYTES = 8L * 1024 * 1024

/** 缩略图分支的固定 tag：真机日志里必须能一眼看出视频格走了哪条分支。 */
private const val TAG_THUMB = "TetherThumb"

private fun ByteArray.looksLikeJpeg(): Boolean =
    size > 3 && this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte()

/**
 * Lets MediaMetadataRetriever parse a camera movie without downloading it entirely.
 * Only the moov/header and the first keyframe are usually touched, so reads stay sparse.
 */
private class PtpRangeMediaDataSource(
    private val source: CameraObjectSource,
    private val handle: Long,
    private val size: Long,
) : MediaDataSource() {
    private val pages = object : LinkedHashMap<Long, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean =
            this.size > MAX_CACHED_PAGES
    }

    /** 供日志与预算判断：已被相机读取的页数与字节数。 */
    @Volatile
    var readCount: Int = 0
        private set

    @Volatile
    var readBytes: Long = 0
        private set

    /** 相机对某页返回短读时，数据到该偏移为止，后续读取按 EOF 处理。 */
    @Volatile
    private var truncatedAt: Long = Long.MAX_VALUE

    @Volatile
    private var failureLogged = false

    private fun page(index: Long): ByteArray? {
        pages[index]?.let { return it }
        val offset = index * PAGE_BYTES
        val length = minOf(PAGE_BYTES, size - offset).toInt()
        if (offset >= truncatedAt || readBytes >= MAX_TOTAL_READ_BYTES) return null
        val bytes = try {
            runBlocking { source.fetchRange(handle, offset, length) }
        } catch (failure: Exception) {
            // 不抛给 MediaMetadataRetriever：它会把异常当成致命错误并放弃整个文件。
            logOnce(failure, "视频首帧：区间读失败 offset=$offset length=$length")
            return null
        }
        readCount++
        readBytes += bytes.size
        if (bytes.size < length) {
            // 相机（或 PTP 数据段）只给了部分数据：记录下来，之后按 EOF 处理。
            truncatedAt = offset + bytes.size
            Timber.tag(TAG_THUMB).i(
                "视频首帧：短读 offset=%d expected=%d received=%d，按 EOF 处理",
                offset,
                length,
                bytes.size,
            )
        }
        if (bytes.isEmpty()) return null
        pages[index] = bytes
        return bytes
    }

    private fun logOnce(failure: Exception, message: String) {
        if (failureLogged) return
        failureLogged = true
        Timber.tag(TAG_THUMB).w(failure, "%s handle=0x%s", message, handle.toString(16))
    }

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position < 0 || position >= this.size || position >= truncatedAt || size <= 0) return -1
        var at = position
        var written = 0
        while (written < size && at < this.size && at < truncatedAt) {
            val index = at / PAGE_BYTES
            val inPage = (at % PAGE_BYTES).toInt()
            val bytes = page(index) ?: break
            if (inPage >= bytes.size) break
            val chunk = minOf(bytes.size - inPage, size - written)
            bytes.copyInto(buffer, offset + written, inPage, inPage + chunk)
            at += chunk
            written += chunk
        }
        return if (written == 0) -1 else written
    }

    override fun getSize(): Long = minOf(size, truncatedAt)

    override fun close() {
        pages.clear()
    }

    private companion object {
        const val PAGE_BYTES = 512L * 1024
        const val MAX_CACHED_PAGES = 6

        /** 约 48 页：足够覆盖 moov + 首个关键帧，同时避免一条坏视频把 USB 占满。 */
        const val MAX_TOTAL_READ_BYTES = 24L * 1024 * 1024
    }
}

/** Enforce preview policy even when callers do not use the request extension. */
private class CameraCachePolicyInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val key = chain.request.data as? CameraThumbKey ?: return chain.proceed(chain.request)
        val side = if (key.kind == CameraThumbKey.Kind.THUMB_160) 160 else 1920
        // 缩略图与内嵌预览都进内存缓存；清晰大图不进池（一张 6000×4000 的 ARGB_565 约 46 MiB，
        // 会瞬间把 12 MiB 的缩略图池挤空），但用内嵌预览当占位，重进时先出图再补清晰图，避免闪白。
        val placeholderKey = key.copy(kind = CameraThumbKey.Kind.RAW_PREVIEW_1920).cacheKey
        val request = chain.request.newBuilder()
            .memoryCacheKey(key.cacheKey)
            .diskCachePolicy(CachePolicy.DISABLED)
            .memoryCachePolicy(
                if (key.kind == CameraThumbKey.Kind.FULL_JPEG) CachePolicy.DISABLED else CachePolicy.ENABLED,
            )
            .placeholderMemoryCacheKey(if (key.kind == CameraThumbKey.Kind.FULL_JPEG) placeholderKey else null)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .allowHardware(false)
            .build()
        // Coil requires the original SizeResolver identity; bound the resolved size on the chain.
        return chain.withSize(Size(side, side)).proceed(request)
    }
}

/** Coil 2.x gallery loader. Only small thumbnails share the bounded memory pool. */
fun buildTetherImageLoader(
    context: Context,
    source: () -> CameraObjectSource?,
    previews: TetherPreviewCache = TetherPreviewCache(File(context.cacheDir, "tether-previews")),
    localOriginal: (Long) -> String? = { null },
): ImageLoader =
    ImageLoader.Builder(context)
        .components {
            add(CameraCachePolicyInterceptor())
            add(CameraThumbKeyer())
            add(CameraThumbFetcher.Factory(source, previews, localOriginal))
        }
        .memoryCache { MemoryCache.Builder(context).maxSizeBytes(12 * 1024 * 1024).build() }
        .diskCache(null)
        .diskCachePolicy(CachePolicy.DISABLED)
        .bitmapConfig(Bitmap.Config.RGB_565)
        .allowHardware(false)
        .build()

fun ImageRequest.Builder.cameraThumb(key: CameraThumbKey): ImageRequest.Builder =
    data(key)
        .memoryCacheKey(key.cacheKey)
        .diskCachePolicy(CachePolicy.DISABLED)
        .memoryCachePolicy(
            if (key.kind == CameraThumbKey.Kind.FULL_JPEG) CachePolicy.DISABLED else CachePolicy.ENABLED,
        )
        .placeholderMemoryCacheKey(
            if (key.kind == CameraThumbKey.Kind.FULL_JPEG) {
                key.copy(kind = CameraThumbKey.Kind.RAW_PREVIEW_1920).cacheKey
            } else {
                null
            },
        )
        .size(if (key.kind == CameraThumbKey.Kind.THUMB_160) 160 else 1920)
        .bitmapConfig(Bitmap.Config.RGB_565)
        .allowHardware(false)
