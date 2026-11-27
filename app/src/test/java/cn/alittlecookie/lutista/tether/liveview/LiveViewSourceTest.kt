package cn.alittlecookie.lutista.tether.liveview

import cn.alittlecookie.lutista.tether.controls.ScriptedCamera
import cn.alittlecookie.lutista.tether.controls.fixture
import cn.alittlecookie.lutista.tether.controls.jpeg
import cn.alittlecookie.lutista.tether.profile.PtpQuirks
import cn.alittlecookie.lutista.tether.session.PtpScheduler
import cn.alittlecookie.lutista.tether.transport.PtpTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class LiveViewSourceTest {
    @Test fun `real format fixture round trips exact opaque tuples and malformed tables fail`() {
        val bytes = fixture("liveview_formats")
        val formats = LiveViewCodec.formats(bytes)
        assertEquals(3, formats.size)
        assertEquals(listOf(32, 350, 700), formats.map { it.x })
        for ((index, format) in formats.withIndex()) {
            assertArrayEquals(bytes.copyOfRange(12 + index * 8, 20 + index * 8), format.payload().copyOfRange(8, 16))
            val header = ByteBuffer.wrap(format.payload()).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(0x0D800011, header.int); assertEquals(8, header.int)
        }
        assertTrue(runCatching { LiveViewCodec.formats(bytes.copyOf(bytes.size - 1)) }.isFailure)
        assertTrue(runCatching { LiveViewCodec.formats(bytes.copyOf().also { it[10] = 4 }) }.isFailure)
    }
    @Test fun `SOI is searched and actual photo video dimensions override format hints`() {
        for ((prefix, height) in listOf(180 to 432, 161 to 344, 257 to 720)) {
            val input = jpeg(prefix, 640, height)
            val frame = LiveViewCodec.frame(input, 99)!!
            assertEquals(640, frame.width); assertEquals(height, frame.height); assertEquals(99L, frame.capturedAtMs)
            assertEquals(0xFF, frame.jpeg[0].toInt() and 255)
            input.fill(0)
            assertEquals(0xD8, frame.jpeg[1].toInt() and 255) // producer owns a fresh array
        }
        assertNull(LiveViewCodec.frame(jpeg().dropLast(2).toByteArray(), 0))
        assertNull(LiveViewCodec.frame(byteArrayOf(1, 2, 3), 0))
    }
    @Test fun `start and switch stop settle at least 1000ms echo format and discard three frames`() = runBlocking {
        withTimeout(8_000) {
            val camera = ScriptedCamera()
            var selected = 0
            val frames = AtomicInteger()
            camera.handler = { call -> when (call.op.code) {
                0x9414 -> ScriptedCamera.Reply(fixture("liveview_formats"))
                0x9415 -> { selected = if (call.dataOut!!.contentEquals(LiveViewCodec.formats(fixture("liveview_formats"))[1].payload())) 1 else 0; ScriptedCamera.Reply() }
                0x9706 -> { frames.incrementAndGet(); ScriptedCamera.Reply(jpeg(if (selected == 0) 180 else 161, 640, if (selected == 0) 432 else 344)) }
                0x9412 -> ScriptedCamera.Reply()
                else -> error("Unexpected operation ${call.op}")
            } }
            val source = LiveViewSource(camera, this, PtpQuirks(liveViewFormatSwitchSettleMs = 1))
            try {
                assertEquals(LiveViewCodec.formats(fixture("liveview_formats"))[0], source.start())
                assertEquals(432, source.latestFrame.filterNotNull().first().height)
                assertEquals(3L, source.stats.value.discarded)
                source.setFormat(1)
                assertEquals(344, source.latestFrame.filterNotNull().first().height)
                assertEquals(6L, source.stats.value.discarded)
                val calls = camera.calls.toList()
                for ((index, call) in calls.withIndex().filter { it.value.op.code == 0x9415 }) {
                    val stop = calls.take(index).last { it.op.code == 0x9412 && it.op.params.single() == 0x0D000011L }
                    assertTrue("settle=${call.at - stop.at}", call.at - stop.at >= 1000)
                    assertEquals(0x9412, calls[index + 1].op.code)
                    assertEquals(0x0D000010L, calls[index + 1].op.params.single())
                    assertTrue(LiveViewCodec.formats(fixture("liveview_formats")).any { it.payload().contentEquals(call.dataOut) })
                }
                source.stop()
                val count = frames.get(); delay(30)
                assertEquals(count, frames.get())
                assertNull(source.latestFrame.value)
                assertEquals(LiveViewPhase.Stopped, source.state.value.phase)
                assertEquals(1, camera.maxActive)
            } finally { source.abandon(); camera.dispose() }
        }
    }
    @Test fun `cancelling during settle never sets format or starts the camera`() = runBlocking {
        val camera = ScriptedCamera(); val stopped = CompletableDeferred<Unit>()
        camera.handler = { call ->
            if (call.op.code == 0x9412) stopped.complete(Unit)
            if (call.op.code == 0x9414) ScriptedCamera.Reply(fixture("liveview_formats")) else ScriptedCamera.Reply()
        }
        val source = LiveViewSource(camera, this, PtpQuirks())
        try {
            val start = async(start = CoroutineStart.UNDISPATCHED) { source.start() }
            stopped.await(); start.cancelAndJoin()
            assertFalse(camera.calls.any { it.op.code == 0x9415 || (it.op.code == 0x9412 && it.op.params.single() == 0x0D000010L) })
            assertEquals(LiveViewPhase.Stopped, source.state.value.phase)
            assertNull(source.latestFrame.value)
        } finally { source.abandon(); camera.dispose() }
    }
    @Test fun `stop during an admitted frame drains once and publishes no stale frame`() = runBlocking {
        withTimeout(5_000) {
            val camera = ScriptedCamera(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            camera.handler = { call -> when (call.op.code) {
                0x9414 -> ScriptedCamera.Reply(fixture("liveview_formats"))
                0x9706 -> { entered.complete(Unit); release.await(); ScriptedCamera.Reply(jpeg()) }
                else -> ScriptedCamera.Reply()
            } }
            val source = LiveViewSource(camera, this, PtpQuirks())
            try {
                source.start(); entered.await()
                val stop = async(start = CoroutineStart.UNDISPATCHED) { source.stop() }
                release.complete(Unit); stop.await()
                assertNull(source.latestFrame.value)
                assertEquals(1, camera.calls.count { it.op.code == 0x9706 })
                assertEquals(0x0D000011L, camera.calls.last().op.params.single())
            } finally { release.complete(Unit); source.abandon(); camera.dispose() }
        }
    }
    @Test fun `latest only storage and style discard continue without a frame backlog`() = runBlocking {
        withTimeout(5_000) {
            val camera = ScriptedCamera(); val n = AtomicLong(0)
            camera.handler = { call -> when (call.op.code) {
                0x9414 -> ScriptedCamera.Reply(fixture("liveview_formats"))
                0x9706 -> { n.incrementAndGet(); ScriptedCamera.Reply(jpeg()) }
                else -> ScriptedCamera.Reply()
            } }
            val source = LiveViewSource(camera, this, PtpQuirks(), clock = { n.get() })
            try {
                source.start()
                source.stats.first { it.published >= 12 }
                val newest = source.latestFrame.filterNotNull().first()
                assertTrue(newest.capturedAtMs >= 15)
                val before = source.stats.value.discarded
                source.discardNextFrames()
                source.stats.first { it.discarded >= before + 3 }
                assertEquals(before + 3, source.stats.value.discarded)
                assertEquals(1, camera.maxActive)
            } finally { source.stop(); camera.dispose() }
        }
    }
    @Test fun `foreground download yields frames without a false reconnect watchdog`() = runBlocking {
        withTimeout(5_000) {
            val camera = ScriptedCamera(); val now = AtomicLong(0)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            camera.handler = { call -> when (call.op.code) {
                0x9414 -> ScriptedCamera.Reply(fixture("liveview_formats"))
                0x9706 -> ScriptedCamera.Reply(jpeg())
                else -> ScriptedCamera.Reply()
            } }
            val source = LiveViewSource(camera, this, PtpQuirks(), clock = now::get)
            try {
                source.start(); source.stats.first { it.published > 0 }
                val download = async(start = CoroutineStart.UNDISPATCHED) {
                    camera.runOp(PtpScheduler.Priority.USER) { entered.complete(Unit); release.await() }
                }
                entered.await()
                val before = source.stats.first { it.userYields > 0 }.userYields
                val frames = camera.calls.count { it.op.code == 0x9706 }
                now.set(10_000)
                source.stats.first { it.userYields >= before + 2 }
                assertEquals(LiveViewPhase.Running, source.state.value.phase)
                assertEquals(frames, camera.calls.count { it.op.code == 0x9706 })
                release.complete(Unit); download.await()
                source.latestFrame.filterNotNull().first { it.capturedAtMs == 10_000L }
                assertEquals(1, camera.maxActive)
            } finally { release.complete(Unit); source.stop(); camera.dispose() }
        }
    }

    @Test fun `transport errors terminate immediately and three second starvation asks reconnect`() = runBlocking {
        withTimeout(6_000) {
            for (fatal in listOf(true, false)) {
                val camera = ScriptedCamera(); val now = AtomicLong(0); var notified = false
                camera.handler = { call -> when (call.op.code) {
                    0x9414 -> ScriptedCamera.Reply(fixture("liveview_formats"))
                    0x9706 -> if (fatal) { camera.isFatal = true; throw PtpTimeoutException("fatal frame") }
                        else { now.addAndGet(1_001); ScriptedCamera.Reply() }
                    else -> ScriptedCamera.Reply()
                } }
                val source = LiveViewSource(camera, this, PtpQuirks(), clock = { now.get() }, onReconnectRequired = { notified = true })
                try {
                    source.start(); source.state.first { it.phase == LiveViewPhase.Failed }
                    assertTrue(notified)
                    assertTrue(camera.calls.count { it.op.code == 0x9706 } <= if (fatal) 1 else 4)
                    val count = camera.calls.size
                    source.abandon(); delay(20)
                    assertEquals(count, camera.calls.size)
                } finally { source.abandon(); camera.dispose() }
            }
        }
    }
}
