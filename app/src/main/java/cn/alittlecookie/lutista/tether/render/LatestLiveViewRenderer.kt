package cn.alittlecookie.lutista.tether.render

import cn.alittlecookie.lutista.data.model.FilterConfig
import cn.alittlecookie.lutista.tether.liveview.LiveViewFrame
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One in flight + one replaceable pending input/output. Output ownership transfers at receive;
 * only never-delivered outputs are automatically closed. No cancellation of in-flight JNI work.
 */
internal class LatestFrameProcessor<I, O : AutoCloseable>(
    parentScope: CoroutineScope,
    private val onDropped: () -> Unit = {},
    private val process: suspend (I) -> O?,
) : AutoCloseable {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val input = Channel<I>(Channel.CONFLATED, onUndeliveredElement = { onDropped() })
    private val output = Channel<O>(Channel.CONFLATED, onUndeliveredElement = { it.close(); onDropped() })
    val frames: Flow<O> = output.receiveAsFlow()
    init {
        scope.launch {
            try {
                for (frame in input) {
                    val result = try { process(frame) } catch (e: CancellationException) { throw e
                    } catch (_: Exception) { null }
                    if (result != null) {
                        if (!output.trySend(result).isSuccess) result.close()
                    }
                }
            } finally {
                input.cancel()
                output.cancel()
            }
        }
    }
    fun submit(frame: I): Boolean = input.trySend(frame).isSuccess
    override fun close() { input.cancel(); output.cancel(); scope.cancel() }
}

/** Single-consumer latest-only bridge. Collect frames and clear the View before closing its frame. */
class LatestLiveViewRenderer(
    parentScope: CoroutineScope,
    private val renderer: LiveViewRenderer,
) : AutoCloseable {
    private data class Request(val frame: LiveViewFrame, val filter: FilterConfig?)
    private val closed = AtomicBoolean(false)
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val dropped = MutableStateFlow(0L)
    private val processor = LatestFrameProcessor<Request, RenderedFrame>(scope,
        onDropped = { dropped.update { it + 1 } },
    ) { renderer.render(it.frame, it.filter) }
    val frames: Flow<RenderedFrame> = processor.frames
    val stats: StateFlow<RenderStats> = combine(renderer.stats, dropped) { stats, count ->
        stats.copy(droppedFrames = stats.droppedFrames + count)
    }.stateIn(scope, SharingStarted.Eagerly, renderer.stats.value)

    init {
        scope.coroutineContext[Job]?.invokeOnCompletion {
            processor.close()
            if (closed.compareAndSet(false, true)) renderer.close()
        }
    }

    /** Copies the JPEG so the queue never retains borrowed transport/caller memory. */
    fun submit(frame: LiveViewFrame, filter: FilterConfig? = null): Boolean {
        if (closed.get() || frame.jpeg.size !in 4..JpegDecodePlan.MAX_JPEG_BYTES) return false
        val owned = LiveViewFrame(frame.jpeg.copyOf(), frame.width, frame.height, frame.capturedAtMs)
        return processor.submit(Request(owned, filter))
    }
    fun setFilterEnabled(enabled: Boolean) = renderer.setFilterEnabled(enabled)
    fun setTargetFps(fps: Int) = renderer.setTargetFps(fps)
    fun onFramePresented(frame: RenderedFrame) = renderer.onFramePresented(frame)
    override fun close() {
        processor.close()
        if (closed.compareAndSet(false, true)) renderer.close()
        scope.cancel()
    }
}
