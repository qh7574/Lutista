package cn.alittlecookie.lutista.tether.objects

import android.content.Context
import android.graphics.Bitmap
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
import java.io.Closeable
import java.io.File
import java.io.IOException
import okio.Buffer
import okio.Path.Companion.toOkioPath
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private val imageFetchPermits = Semaphore(2)

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
) : Fetcher {
    override suspend fun fetch(): FetchResult? = imageFetchPermits.withPermit { fetchBounded() }

    private suspend fun fetchBounded(): FetchResult? {
        val source = sourceProvider() ?: throw IOException("Camera object source is disconnected")
        check(source.sessionKey == key.session) { "Camera image request belongs to an expired session" }
        if (key.kind == CameraThumbKey.Kind.FULL_JPEG) {
            val info = source.getObjectInfo(key.handle)
            require(info.storageId == key.storageId && info.format == 0x3801L && !info.isDir) {
                "Full image preview requires a JPEG from the requested storage"
            }
            // Temporary decoder input, not a disk cache. Original objects can be tens of MiB.
            val file = File.createTempFile("tether-preview-", ".jpg", options.context.cacheDir)
            var handedToDecoder = false
            try {
                file.outputStream().use { output ->
                    source.streamObject(key.handle, { bytes, length -> output.write(bytes, 0, length) })
                }
                val result = SourceResult(
                    source = ImageSource(file = file.toOkioPath(), closeable = Closeable { file.delete() }),
                    mimeType = "image/jpeg", dataSource = DataSource.NETWORK,
                )
                handedToDecoder = true
                return result
            } finally {
                if (!handedToDecoder) file.delete()
            }
        }
        val bytes = when (key.kind) {
            CameraThumbKey.Kind.THUMB_160 -> source.fetchThumb(key.handle)
            CameraThumbKey.Kind.RAW_PREVIEW_1920 -> source.fetchRawPreview(key.handle)
            CameraThumbKey.Kind.FULL_JPEG -> error("Handled above")
        } ?: return null
        check(bytes.size.toLong() <= PtpObjectSource.MAX_IN_MEMORY_BYTES) { "Camera preview exceeds 4 MiB" }
        return SourceResult(
            source = ImageSource(source = Buffer().write(bytes), context = options.context),
            mimeType = "image/jpeg", dataSource = DataSource.NETWORK,
        )
    }

    class Factory(private val source: () -> CameraObjectSource?) : Fetcher.Factory<CameraThumbKey> {
        override fun create(data: CameraThumbKey, options: Options, imageLoader: ImageLoader): Fetcher =
            CameraThumbFetcher(source, data, options)
    }
}

/** Enforce preview policy even when callers do not use the request extension. */
private class CameraCachePolicyInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val key = chain.request.data as? CameraThumbKey ?: return chain.proceed(chain.request)
        val side = if (key.kind == CameraThumbKey.Kind.THUMB_160) 160 else 1920
        val request = chain.request.newBuilder()
            .memoryCacheKey(key.cacheKey)
            .diskCachePolicy(CachePolicy.DISABLED)
            .memoryCachePolicy(if (key.kind == CameraThumbKey.Kind.THUMB_160) CachePolicy.ENABLED else CachePolicy.DISABLED)
            .size(if (key.kind == CameraThumbKey.Kind.THUMB_160) 160 else 1920)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .allowHardware(false)
            .build()
        return chain.withSize(Size(side, side)).proceed(request)
    }
}

/** Coil 2.x gallery loader. Only small thumbnails share the bounded memory pool. */
fun buildTetherImageLoader(context: Context, source: () -> CameraObjectSource?): ImageLoader =
    ImageLoader.Builder(context)
        .components {
            add(CameraCachePolicyInterceptor())
            add(CameraThumbKeyer())
            add(CameraThumbFetcher.Factory(source))
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
        .memoryCachePolicy(if (key.kind == CameraThumbKey.Kind.THUMB_160) CachePolicy.ENABLED else CachePolicy.DISABLED)
        .size(if (key.kind == CameraThumbKey.Kind.THUMB_160) 160 else 1920)
        .bitmapConfig(Bitmap.Config.RGB_565)
        .allowHardware(false)
