package cn.alittlecookie.lutista.tether.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.alittlecookie.lutista.tether.debug.TetherUiSmokeActivity
import cn.alittlecookie.lutista.tether.objects.CameraObject
import cn.alittlecookie.lutista.tether.objects.CameraObjectSource
import cn.alittlecookie.lutista.tether.objects.CameraSessionKey
import cn.alittlecookie.lutista.tether.objects.CameraThumbKey
import cn.alittlecookie.lutista.tether.objects.ObjectPage
import cn.alittlecookie.lutista.tether.objects.buildTetherImageLoader
import cn.alittlecookie.lutista.tether.objects.cameraThumb
import coil.EventListener
import coil.decode.DataSource
import coil.request.CachePolicy
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real Coil interceptor, JPEG decoder and ImageView target, without USB or a camera. */
@RunWith(AndroidJUnit4::class)
class CameraImageLoaderInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun jpegThumbnailReachesFetcherAndDecodes() = assertThumbnail(JPEG_HANDLE)

    @Test
    fun rawThumbnailReachesTheSameGetThumbPathAndDecodes() = assertThumbnail(RAW_HANDLE)

    private fun assertThumbnail(handle: Long) = runBlocking {
        val source = ImageSourceFixture()
        val loader = buildTetherImageLoader(context) { source }
        try {
            val request = ImageRequest.Builder(context).cameraThumb(source.key(handle)).build()
            val result = loader.execute(request)
            assertSuccess(result)
            assertSame(request.sizeResolver, result.request.sizeResolver)
            assertEquals(listOf(handle), source.thumbCalls)
            val bitmap = (result.drawable as BitmapDrawable).bitmap
            assertTrue(bitmap.width in 1..160 && bitmap.height in 1..160)
            assertTrue(Color.green(bitmap.getPixel(0, 0)) > 180)
        } finally {
            loader.shutdown()
        }
    }

    @Test
    fun directDataRequestStillGetsBoundedPolicyWithoutReplacingSizeResolver() = runBlocking {
        val source = ImageSourceFixture()
        val loader = buildTetherImageLoader(context) { source }
        try {
            // Intentionally bypass cameraThumb(): the interceptor must enforce its policy legally.
            val request = ImageRequest.Builder(context).data(source.key(JPEG_HANDLE)).size(4096).build()
            val result = loader.execute(request)
            assertSuccess(result)
            assertSame(request.sizeResolver, result.request.sizeResolver)
            assertEquals(CachePolicy.DISABLED, result.request.diskCachePolicy)
            assertEquals(CachePolicy.ENABLED, result.request.memoryCachePolicy)
            assertEquals(source.key(JPEG_HANDLE).cacheKey, result.request.memoryCacheKey?.key)
            val bitmap = (result.drawable as BitmapDrawable).bitmap
            assertTrue(bitmap.width in 1..160 && bitmap.height in 1..160)
            val cached = loader.execute(request)
            assertSuccess(cached)
            assertEquals(DataSource.MEMORY_CACHE, (cached as SuccessResult).dataSource)
            assertEquals(listOf(JPEG_HANDLE), source.thumbCalls)
        } finally {
            loader.shutdown()
        }
    }

    @Test
    fun rawEmbeddedPreviewUsesPreviewFetcherAndDoesNotEnterThumbnailCache() = runBlocking {
        val source = ImageSourceFixture()
        val loader = buildTetherImageLoader(context) { source }
        try {
            val key = source.key(RAW_HANDLE).copy(kind = CameraThumbKey.Kind.RAW_PREVIEW_1920)
            val result = loader.execute(ImageRequest.Builder(context).cameraThumb(key).build())
            assertSuccess(result)
            assertEquals(listOf(RAW_HANDLE), source.previewCalls)
            assertTrue(source.thumbCalls.isEmpty())
            assertEquals(CachePolicy.DISABLED, result.request.memoryCachePolicy)
            assertNull(loader.memoryCache?.get(coil.memory.MemoryCache.Key(key.cacheKey)))
            val bitmap = (result.drawable as BitmapDrawable).bitmap
            assertTrue(bitmap.width > 160 && bitmap.width <= 1920)
        } finally {
            loader.shutdown()
        }
    }

    @Test
    fun expiredSessionIsRejectedBeforeReadingCameraPixels() = runBlocking {
        val source = ImageSourceFixture()
        val loader = buildTetherImageLoader(context) { source }
        try {
            val oldKey = source.key(JPEG_HANDLE).copy(session = source.sessionKey.copy(connectAt = 0))
            val result = loader.execute(ImageRequest.Builder(context).cameraThumb(oldKey).build())
            assertTrue(result is ErrorResult)
            val error = (result as ErrorResult).throwable
            assertEquals("Camera image request belongs to an expired session", error.message)
            assertTrue(source.thumbCalls.isEmpty())
            assertTrue(source.previewCalls.isEmpty())
        } finally {
            loader.shutdown()
        }
    }

    @Test
    fun rawAndJpegBindingsPopulateAndDetachActualImageViews() {
        val source = ImageSourceFixture()
        val complete = CountDownLatch(2)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val baseLoader = buildTetherImageLoader(context) { source }
        val loader = baseLoader.newBuilder().eventListener(object : EventListener {
            override fun onSuccess(request: ImageRequest, result: SuccessResult) { complete.countDown() }
            override fun onError(request: ImageRequest, result: ErrorResult) {
                errors += result.throwable
                complete.countDown()
            }
        }).build()
        baseLoader.shutdown()
        try {
            ActivityScenario.launch<TetherUiSmokeActivity>(Intent(context, TetherUiSmokeActivity::class.java)).use { scenario ->
                val views = mutableListOf<ImageView>()
                val bindings = mutableListOf<CameraImageBinding>()
                scenario.onActivity { activity ->
                    val layout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                    listOf(JPEG_HANDLE, RAW_HANDLE).forEach { handle ->
                        val view = ImageView(activity)
                        layout.addView(view, LinearLayout.LayoutParams(160, 120))
                        views += view
                        bindings += CameraImageBinding(activity, loader, source.key(handle))
                    }
                    activity.setContentView(layout)
                    views.zip(bindings).forEach { (view, binding) -> binding.attach(view) }
                }
                assertTrue("Coil did not complete both attached image requests", complete.await(10, TimeUnit.SECONDS))
                assertTrue(errors.joinToString("\n") { it.stackTraceToString() }, errors.isEmpty())
                scenario.onActivity {
                    views.forEach { view ->
                        val bitmap = (view.drawable as? BitmapDrawable)?.bitmap
                        assertNotNull("CameraImageBinding left its ImageView empty", bitmap)
                        assertTrue(Color.green(bitmap!!.getPixel(0, 0)) > 180)
                    }
                    views.zip(bindings).forEach { (view, binding) ->
                        binding.detach(view)
                        assertNull(view.drawable)
                    }
                }
            }
            assertEquals(setOf(JPEG_HANDLE, RAW_HANDLE), source.thumbCalls.toSet())
        } finally {
            loader.shutdown()
        }
    }

    private fun assertSuccess(result: coil.request.ImageResult) {
        assertTrue((result as? ErrorResult)?.throwable?.stackTraceToString() ?: result.toString(), result is SuccessResult)
    }

    private class ImageSourceFixture : CameraObjectSource {
        override val sessionKey = CameraSessionKey("thumbnail-fixture", 1)
        val thumbCalls = Collections.synchronizedList(mutableListOf<Long>())
        val previewCalls = Collections.synchronizedList(mutableListOf<Long>())
        private val thumbnail = jpeg(320, 240)
        private val preview = jpeg(2048, 1536)
        fun key(handle: Long) = CameraThumbKey(sessionKey, 0x10001, handle)
        override suspend fun fetchThumb(handle: Long): ByteArray {
            check(handle == JPEG_HANDLE || handle == RAW_HANDLE)
            thumbCalls += handle
            return thumbnail
        }
        override suspend fun fetchRawPreview(handle: Long): ByteArray {
            check(handle == RAW_HANDLE)
            previewCalls += handle
            return preview
        }
        override fun listTree(): Flow<ObjectPage> = error("Unexpected camera enumeration")
        override suspend fun listChildren(parent: Long, storageId: Long?): List<CameraObject> = error("Unexpected directory read")
        override suspend fun getObjectInfo(handle: Long): CameraObject = error("Unexpected metadata read")
        override suspend fun fetchObject(handle: Long): ByteArray = error("Unexpected full-object download")
        override suspend fun streamObject(handle: Long, sink: (ByteArray, Int) -> Unit, onProgress: (Long) -> Unit): Long =
            error("Unexpected full-object download")
    }

    companion object {
        private const val JPEG_HANDLE = 1L
        private const val RAW_HANDLE = 2L
        private fun jpeg(width: Int, height: Int): ByteArray {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            return try {
                bitmap.eraseColor(Color.GREEN)
                ByteArrayOutputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output))
                    output.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        }
    }
}
