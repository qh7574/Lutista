package cn.alittlecookie.lutista.tether.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import cn.alittlecookie.lutista.data.model.FilterConfig
import cn.alittlecookie.lutista.domain.engine.ImageProcessingEngine
import cn.alittlecookie.lutista.tether.liveview.LiveViewFrame
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Exclusive ownership transfers to the consumer. Replace/clear ImageView's drawable BEFORE close().
 * Renderer shutdown never closes a frame that has already been returned to its consumer.
 */
class RenderedFrame internal constructor(
    val bitmap: Bitmap,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val capturedAtMs: Long,
    val filtered: Boolean,
    private val releaseBitmap: (Bitmap) -> Unit,
) : AutoCloseable {
    private val released = AtomicBoolean(false)
    val isClosed: Boolean get() = released.get()
    override fun close() { if (released.compareAndSet(false, true)) releaseBitmap(bitmap) }
}

interface LiveViewRenderer : AutoCloseable {
    val stats: StateFlow<RenderStats>
    /** Borrow JPEG bytes until this call returns; never mutate/recycle an upstream input. */
    suspend fun render(frame: LiveViewFrame, filter: FilterConfig? = null): RenderedFrame?
    fun setFilterEnabled(enabled: Boolean)
    fun setTargetFps(fps: Int)
    /** Call on the UI thread after binding the bitmap, for actual presentation/latency statistics. */
    fun onFramePresented(frame: RenderedFrame)
    /** Surface/layout changes are geometry-only; renderers have no reference to camera commands. */
    fun attach(width: Int, height: Int) { require(width > 0 && height > 0) }
    fun detach() = Unit
}

/** Small pool containing ONLY released/unpublished bitmaps; displayed bitmaps never enter it. */
private class PreviewBitmapPool : AutoCloseable {
    private val bitmaps = ArrayDeque<Bitmap>()
    private var closed = false
    @Synchronized fun take(width: Int, height: Int): Bitmap? {
        val iterator = bitmaps.iterator()
        while (iterator.hasNext()) {
            val bitmap = iterator.next()
            if (bitmap.width == width && bitmap.height == height) {
                iterator.remove()
                return bitmap
            }
        }
        return null
    }
    @Synchronized fun release(bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        if (!closed && bitmap.isMutable && bitmap.config == Bitmap.Config.ARGB_8888 && bitmaps.size < 2) {
            bitmaps.addLast(bitmap)
        } else bitmap.recycle()
    }
    @Synchronized override fun close() {
        closed = true
        while (bitmaps.isNotEmpty()) bitmaps.removeFirst().recycle()
    }
}

/** Bounded JPEG decode, no watermark, no CPU filter and no camera/source calls. */
open class JpegPassthroughRenderer(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: PreviewClock = PreviewClock.Monotonic,
) : LiveViewRenderer {
    private val policy = PreviewRenderPolicy(clock)
    private val stateLock = Any()
    private val renderMutex = Mutex()
    private val pool = PreviewBitmapPool()
    private val closed = AtomicBoolean(false)
    private val _stats = MutableStateFlow(RenderStats())
    override val stats: StateFlow<RenderStats> = _stats.asStateFlow()
    private val presentedAt = ArrayDeque<Long>()
    private val latencySamples = ArrayDeque<Int>()
    protected open val supportsFilters = false

    override fun setFilterEnabled(enabled: Boolean) = synchronized(stateLock) {
        val effective = enabled && supportsFilters
        if (policy.enabled == effective) return@synchronized
        policy.setEnabled(effective)
        _stats.value = _stats.value.copy(filterState = FilterState.Off, degradeReason = null)
    }

    override fun setTargetFps(fps: Int) = synchronized(stateLock) {
        policy.setTargetFps(fps)
        _stats.value = _stats.value.copy(targetFps = fps)
    }

    protected open fun filterBitmap(bitmap: Bitmap, filter: FilterConfig): Bitmap? = null

    override suspend fun render(frame: LiveViewFrame, filter: FilterConfig?): RenderedFrame? {
        if (closed.get() || frame.jpeg.size !in 4..JpegDecodePlan.MAX_JPEG_BYTES) return null
        val effectiveFilter = LiveViewFilterSource.previewChain(filter).color?.filter
        var output: RenderedFrame? = null
        try {
            withContext(dispatcher) {
                renderMutex.withLock {
                    if (closed.get()) return@withLock
                    currentCoroutineContext().ensureActive()
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size, bounds)
                    val plan = JpegDecodePlan.forBounds(bounds.outWidth, bounds.outHeight) ?: return@withLock
                    var decoded: Bitmap? = decode(frame.jpeg, plan) ?: return@withLock
                    try {
                        val action = synchronized(stateLock) {
                            policy.frameAction(effectiveFilter != null, bounds.outWidth, bounds.outHeight)
                        }
                        var renderingFailed = false
                        val filtered = if (action == PreviewFrameAction.Filter && effectiveFilter != null) {
                            // Grain is deliberately disabled for JPEG preview; watermark is never invoked.
                            try {
                                filterBitmap(checkNotNull(decoded), effectiveFilter)
                            } catch (e: CancellationException) { throw e
                            } catch (_: Exception) { renderingFailed = true; null }
                        } else null
                        if (filtered != null && filtered !== decoded) {
                            pool.release(checkNotNull(decoded))
                            decoded = filtered
                        }
                        val completedAction = synchronized(stateLock) {
                            val resultAction = policy.completeFrame(action, filtered != null, renderingFailed)
                            _stats.value = _stats.value.copy(
                                processedFps = policy.processedFps(),
                                droppedFrames = _stats.value.droppedFrames + if (resultAction == PreviewFrameAction.Drop) 1 else 0,
                                filterState = policy.state(effectiveFilter != null),
                                degradeReason = policy.degradeReason,
                                targetFps = policy.targetFps,
                                sourceWidth = bounds.outWidth,
                                sourceHeight = bounds.outHeight,
                            )
                            resultAction
                        }
                        currentCoroutineContext().ensureActive()
                        // Throttling and a busy/null GPU output retain the last filtered frame.
                        // Only a disabled/missing filter or a real rendering failure emits the original.
                        if (completedAction != PreviewFrameAction.Drop && !closed.get()) {
                            output = RenderedFrame(checkNotNull(decoded), bounds.outWidth, bounds.outHeight,
                                frame.capturedAtMs, filtered != null, pool::release)
                            decoded = null // ownership transferred to output, never auto-recycled
                        }
                    } finally { decoded?.let(pool::release) }
                }
            }
            currentCoroutineContext().ensureActive()
            return output
        } catch (e: CancellationException) {
            output?.close()
            throw e
        } catch (_: Exception) {
            output?.close()
            return null
        } catch (_: OutOfMemoryError) {
            output?.close()
            return null
        }
    }

    private fun decode(jpeg: ByteArray, plan: JpegDecodePlan): Bitmap? {
        val reusable = pool.take(plan.width, plan.height)
        val options = BitmapFactory.Options().apply {
            inSampleSize = plan.sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
            inScaled = false
            inBitmap = reusable
        }
        var decoded: Bitmap? = null
        try {
            decoded = try {
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
            } catch (_: IllegalArgumentException) {
                options.inBitmap = null
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
            }
            return decoded
        } finally {
            if (reusable != null && decoded !== reusable) pool.release(reusable)
        }
    }

    override fun onFramePresented(frame: RenderedFrame) = synchronized(stateLock) {
        if (closed.get() || frame.isClosed) return@synchronized
        val now = clock.nowMs()
        presentedAt.addLast(now)
        while (presentedAt.isNotEmpty() && presentedAt.peekFirst() <= now - 1_000L) presentedAt.removeFirst()
        // Source and renderer both use nanoTime monotonic milliseconds (never wall time).
        latencySamples.addLast((now - frame.capturedAtMs).coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
        while (latencySamples.size > 120) latencySamples.removeFirst()
        val sorted = latencySamples.sorted()
        val p95 = sorted[((sorted.size * 95 + 99) / 100 - 1).coerceAtLeast(0)]
        _stats.value = _stats.value.copy(presentedFps = presentedAt.size.toFloat(), addedLatencyP95Ms = p95)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) pool.close()
    }
}

class VulkanLiveViewRenderer(
    private val engine: ImageProcessingEngine,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    clock: PreviewClock = PreviewClock.Monotonic,
) : JpegPassthroughRenderer(dispatcher, clock) {
    override val supportsFilters = true
    // Accessed only under the base renderer's single-worker mutex.
    private var rgba = ByteArray(0)
    override fun filterBitmap(bitmap: Bitmap, filter: FilterConfig): Bitmap? {
        val length = Math.multiplyExact(Math.multiplyExact(bitmap.width, bitmap.height), 4)
        if (rgba.size != length) rgba = ByteArray(length)
        bitmap.copyPixelsToBuffer(ByteBuffer.wrap(rgba))
        return engine.tryProcessLiveViewRgba(rgba, bitmap.width, bitmap.height, filter)
    }
}
