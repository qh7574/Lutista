package cn.alittlecookie.lutista.tether.render

import cn.alittlecookie.lutista.data.model.FilterConfig
import cn.alittlecookie.lutista.tether.liveview.LiveViewFrame
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class LatestFrameProcessorTest {
    private class Owned(val number: Int) : AutoCloseable {
        var closes = 0
        override fun close() { closes++ }
    }

    @Test fun `slow render retains only latest pending input`() = runBlocking {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val processed = mutableListOf<Int>()
            val drops = AtomicInteger()
            val processor = LatestFrameProcessor<Int, Owned>(this, { drops.incrementAndGet() }) {
                processed.add(it)
                if (it == 1) { entered.complete(Unit); release.await() }
                Owned(it)
            }
            try {
                val received = async(start = CoroutineStart.UNDISPATCHED) { processor.frames.take(2).toList() }
                assertTrue(processor.submit(1))
                entered.await()
                for (number in 2..100) assertTrue(processor.submit(number))
                release.complete(Unit)
                val results = received.await()
                assertEquals(listOf(1, 100), processed)
                assertEquals(listOf(1, 100), results.map { it.number })
                assertEquals(98, drops.get())
                results.forEach { assertEquals(0, it.closes); it.close() }
            } finally { release.complete(Unit); processor.close() }
        }
    }

    @Test fun `only unreceived outputs are closed on overwrite and shutdown`() = runBlocking {
        withTimeout(5_000) {
            val made = mutableListOf<Owned>()
            val processor = LatestFrameProcessor<Int, Owned>(this) { Owned(it).also(made::add) }
            try {
                processor.submit(1); yield()
                processor.submit(2); yield()
                assertEquals(1, made[0].closes)
                val displayed = processor.frames.first()
                assertEquals(2, displayed.number)
                processor.submit(3); yield()
                processor.close()
                assertEquals(0, displayed.closes) // still visible in AndroidView until consumer releases it
                assertEquals(1, made[2].closes)
                displayed.close()
                assertEquals(1, displayed.closes)
                assertFalse(processor.submit(4))
            } finally { processor.close() }
        }
    }

    @Test fun `one failed render does not kill latest frame worker`() = runBlocking {
        withTimeout(5_000) {
            val attempted = CompletableDeferred<Unit>()
            val processor = LatestFrameProcessor<Int, Owned>(this) {
                if (it == 1) { attempted.complete(Unit); error("bad JPEG") }
                Owned(it)
            }
            try {
                processor.submit(1)
                attempted.await()
                processor.submit(2)
                val good = processor.frames.first()
                assertEquals(2, good.number)
                good.close()
            } finally { processor.close() }
        }
    }

    private class Clock(var ms: Long = 0) : PreviewClock { override fun nowMs() = ms }

    /** No Android Bitmap/JNI: a virtual-clock GPU that is busy and skips every attempted frame. */
    private class BusyRenderer(private val blockFirst: Boolean = false) : LiveViewRenderer {
        private val clock = Clock()
        private val policy = PreviewRenderPolicy(clock)
        override val stats = MutableStateFlow(RenderStats())
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val completed = Channel<Long>(Channel.UNLIMITED)
        val attempts = mutableListOf<Long>()
        var fail = false
        var closes = 0
        override fun setFilterEnabled(enabled: Boolean) {
            policy.setEnabled(enabled)
            stats.value = stats.value.copy(filterState = policy.state(true), degradeReason = policy.degradeReason)
        }
        override fun setTargetFps(fps: Int) { policy.setTargetFps(fps) }
        override suspend fun render(frame: LiveViewFrame, filter: FilterConfig?): RenderedFrame? {
            attempts.add(frame.capturedAtMs)
            if (attempts.size == 1) {
                entered.complete(Unit)
                if (blockFirst) release.await()
            }
            clock.ms = frame.capturedAtMs
            val action = policy.frameAction(filter != null, frame.width, frame.height)
            val result = policy.completeFrame(action, filteredOutputAvailable = false, renderingFailed = fail)
            stats.value = stats.value.copy(
                filterState = policy.state(filter != null), degradeReason = policy.degradeReason,
                droppedFrames = stats.value.droppedFrames + if (result == PreviewFrameAction.Drop) 1 else 0,
                sourceWidth = frame.width, sourceHeight = frame.height, targetFps = policy.targetFps,
            )
            completed.send(frame.capturedAtMs)
            return null
        }
        override fun onFramePresented(frame: RenderedFrame) = Unit
        override fun close() { closes++ }
    }

    private fun highResolutionFrame(number: Int) = LiveViewFrame(byteArrayOf(1, 2, 3, 4),
        width = 6000, height = 4000, capturedAtMs = number * 1_000L / 240)

    @Test fun `high resolution high FPS pressure keeps filter on and emits once until toggled off and on`() = runBlocking {
        withTimeout(5_000) {
            val renderer = BusyRenderer(blockFirst = true)
            val pipeline = LatestLiveViewRenderer(this, renderer)
            val notices = mutableListOf<PreviewRenderNotice>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { pipeline.notices.collect { notices.add(it) } }
            val color = FilterConfig(exposure = 10f)
            try {
                pipeline.setTargetFps(240)
                pipeline.setFilterEnabled(true)
                assertTrue(pipeline.submit(highResolutionFrame(0), color))
                renderer.entered.await()
                for (number in 1 until 1_000) assertTrue(pipeline.submit(highResolutionFrame(number), color))
                yield()
                assertEquals(listOf(PreviewRenderNotice.FilterFramesDropped), notices)
                renderer.release.complete(Unit)
                assertEquals(0L, renderer.completed.receive())
                assertEquals(999 * 1_000L / 240, renderer.completed.receive())
                val stats = pipeline.stats.first { it.droppedFrames >= 1_000 }
                assertEquals(listOf(0L, 999 * 1_000L / 240), renderer.attempts)
                assertEquals(FilterState.On, stats.filterState)
                assertNull(stats.degradeReason)
                assertEquals(6_000, stats.sourceWidth)
                assertEquals(4_000, stats.sourceHeight)

                // Duplicate settings, FPS changes, filter edits and many new drops are the same session.
                pipeline.setFilterEnabled(true)
                pipeline.setTargetFps(120)
                for (number in 1_000..1_100) {
                    assertTrue(pipeline.submit(highResolutionFrame(number), color.copy(exposure = 20f)))
                    renderer.completed.receive()
                    yield()
                }
                assertEquals(1, notices.size)
                assertEquals(FilterState.On, renderer.stats.value.filterState)
                assertNull(renderer.stats.value.degradeReason)

                pipeline.setFilterEnabled(false)
                pipeline.setFilterEnabled(true)
                assertTrue(pipeline.submit(highResolutionFrame(1_200), color))
                renderer.completed.receive()
                yield()
                assertEquals(listOf(PreviewRenderNotice.FilterFramesDropped, PreviewRenderNotice.FilterFramesDropped), notices)
                pipeline.close()
                assertEquals(1, renderer.closes)
                assertFalse(pipeline.submit(highResolutionFrame(1_201), color))
            } finally {
                renderer.release.complete(Unit)
                pipeline.close()
                collector.cancelAndJoin()
                renderer.completed.cancel()
            }
        }
    }

    @Test fun `notices discard old filter sessions and do not replay after collector recreation`() = runBlocking {
        withTimeout(5_000) {
            val renderer = BusyRenderer()
            val pipeline = LatestLiveViewRenderer(this, renderer)
            val color = FilterConfig(exposure = 10f)
            val notices = mutableListOf<PreviewRenderNotice>()
            try {
                pipeline.setFilterEnabled(true)
                pipeline.submit(highResolutionFrame(0), color)
                renderer.completed.receive()
                yield() // The notice is buffered while no consumer is listening.
                pipeline.setFilterEnabled(false)
                pipeline.setFilterEnabled(true)
                var collector = launch(start = CoroutineStart.UNDISPATCHED) { pipeline.notices.collect { notices.add(it) } }
                yield()
                assertTrue(notices.isEmpty())
                pipeline.submit(highResolutionFrame(240), color)
                renderer.completed.receive()
                yield()
                assertEquals(1, notices.size)
                collector.cancelAndJoin()
                collector = launch(start = CoroutineStart.UNDISPATCHED) { pipeline.notices.collect { notices.add(it) } }
                pipeline.submit(highResolutionFrame(480), color)
                renderer.completed.receive()
                yield()
                assertEquals(1, notices.size)
                collector.cancelAndJoin()
            } finally {
                pipeline.close()
                renderer.completed.cancel()
            }
        }
    }

    @Test fun `actual rendering failure is not reported as a performance drop`() = runBlocking {
        withTimeout(5_000) {
            val renderer = BusyRenderer().apply { fail = true }
            val pipeline = LatestLiveViewRenderer(this, renderer)
            val notices = mutableListOf<PreviewRenderNotice>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) { pipeline.notices.collect { notices.add(it) } }
            try {
                pipeline.setFilterEnabled(true)
                pipeline.submit(highResolutionFrame(0), FilterConfig(exposure = 10f))
                renderer.completed.receive()
                yield()
                assertEquals(FilterState.Degraded, renderer.stats.value.filterState)
                assertEquals(DegradeReason.RenderingFailure, renderer.stats.value.degradeReason)
                assertTrue(notices.isEmpty())
            } finally {
                pipeline.close()
                collector.cancelAndJoin()
                renderer.completed.cancel()
            }
        }
    }
}
