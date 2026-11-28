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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
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
    private val onInputDropped: (I) -> Unit = {},
    private val onOutputDropped: (O) -> Unit = {},
    private val process: suspend (I) -> O?,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val input = Channel<I>(Channel.CONFLATED, onUndeliveredElement = {
        if (!closed.get()) { onDropped(); onInputDropped(it) }
    })
    private val output = Channel<O>(Channel.CONFLATED, onUndeliveredElement = {
        it.close()
        if (!closed.get()) { onDropped(); onOutputDropped(it) }
    })
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
                closed.set(true)
                input.cancel()
                output.cancel()
            }
        }
    }
    fun submit(frame: I): Boolean = !closed.get() && input.trySend(frame).isSuccess
    override fun close() {
        closed.set(true)
        input.cancel()
        output.cancel()
        scope.cancel()
    }
}

/** Single-consumer latest-only bridge. Collect frames and clear the View before closing its frame. */
class LatestLiveViewRenderer(
    parentScope: CoroutineScope,
    private val renderer: LiveViewRenderer,
) : AutoCloseable {
    private data class Request(val frame: LiveViewFrame, val filter: FilterConfig?, val filterSession: Long?)
    private data class Output(val frame: RenderedFrame, val filterSession: Long?) : AutoCloseable {
        override fun close() = frame.close()
    }
    private val closed = AtomicBoolean(false)
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val dropGate = PreviewFilterDropGate()
    private val noticeChannel = Channel<Pair<Long, PreviewRenderNotice>>(Channel.CONFLATED)
    /** Consumable once per false -> true filter session. Stats/UI re-collection cannot replay it. */
    val notices: Flow<PreviewRenderNotice> = noticeChannel.receiveAsFlow()
        .filter { dropGate.isCurrent(it.first) }
        .map { it.second }
    private val dropped = MutableStateFlow(0L)
    private val processor = LatestFrameProcessor<Request, Output>(scope,
        onDropped = { dropped.update { it + 1 } },
        onInputDropped = { reportDrop(it.filterSession) },
        onOutputDropped = { if (it.frame.filtered) reportDrop(it.filterSession) },
    ) { request ->
        val rendered = renderer.render(request.frame, request.filter)
        if (rendered == null) reportDrop(request.filterSession)
        rendered?.let { Output(it, request.filterSession) }
    }
    val frames: Flow<RenderedFrame> = processor.frames.map { it.frame }
    val stats: StateFlow<RenderStats> = combine(renderer.stats, dropped) { stats, count ->
        stats.copy(droppedFrames = stats.droppedFrames + count)
    }.stateIn(scope, SharingStarted.Eagerly, renderer.stats.value)

    init {
        scope.coroutineContext[Job]?.invokeOnCompletion {
            processor.close()
            noticeChannel.cancel()
            if (closed.compareAndSet(false, true)) renderer.close()
        }
    }

    private fun reportDrop(filterSession: Long?) {
        if (closed.get() || renderer.stats.value.filterState != FilterState.On) return
        if (dropGate.onDropped(filterSession)) {
            noticeChannel.trySend(checkNotNull(filterSession) to PreviewRenderNotice.FilterFramesDropped)
        }
    }

    /** Copies the JPEG so the queue never retains borrowed transport/caller memory. */
    fun submit(frame: LiveViewFrame, filter: FilterConfig? = null): Boolean {
        if (closed.get() || frame.jpeg.size !in 4..JpegDecodePlan.MAX_JPEG_BYTES) return false
        val owned = LiveViewFrame(frame.jpeg.copyOf(), frame.width, frame.height, frame.capturedAtMs)
        val filterSession = if (LiveViewFilterSource.previewChain(filter).isEmpty) null else dropGate.currentSession()
        return processor.submit(Request(owned, filter, filterSession))
    }
    fun setFilterEnabled(enabled: Boolean) {
        if (closed.get()) return
        dropGate.setEnabled(enabled)
        renderer.setFilterEnabled(enabled)
    }
    fun setTargetFps(fps: Int) = renderer.setTargetFps(fps)
    fun onFramePresented(frame: RenderedFrame) = renderer.onFramePresented(frame)
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            processor.close()
            noticeChannel.cancel()
            renderer.close()
        }
        scope.cancel()
    }
}
