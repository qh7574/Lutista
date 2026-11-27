package cn.alittlecookie.lutista.tether.session

import cn.alittlecookie.lutista.tether.controls.ScriptedCamera
import cn.alittlecookie.lutista.tether.controls.value
import cn.alittlecookie.lutista.tether.config.CameraProperty
import cn.alittlecookie.lutista.tether.config.PropertyCodec
import kotlinx.coroutines.CoroutineScope
import cn.alittlecookie.lutista.tether.profile.profiles.GenericPtpProfile
import cn.alittlecookie.lutista.tether.profile.profiles.LumixS1Profile
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class CaptureControllerTest {
    @Test fun `photo press uses cached mode exact shutter and 800ms debounce plus event completion`() = runBlocking {
        val camera = ScriptedCamera(); val time = AtomicLong(0); val events = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        val capture = CaptureController(camera, MutableStateFlow(0L), LumixS1Profile(), this, events, time::get)
        try {
            assertEquals(PressResult.Accepted(CaptureAction.Photo), capture.press())
            assertTrue(capture.ui.value.busy)
            time.set(900)
            assertTrue(capture.press() is PressResult.Ignored) // busy survives debounce interval
            events.emit(Unit); capture.ui.first { !it.busy }
            time.set(799)
            assertTrue(capture.press() is PressResult.Ignored)
            time.set(800)
            assertEquals(PressResult.Accepted(CaptureAction.Photo), capture.press())
            assertEquals(2, camera.calls.size)
            camera.calls.forEach { assertEquals(0x9404, it.op.code); assertEquals(listOf(0x03000011L), it.op.params.toList()) }
            assertFalse(capture.ui.value.recording)
        } finally { capture.close(); camera.dispose() }
    }
    @Test fun `movie dual state toggles once per 1500ms and stop waits for completion event`() = runBlocking {
        val camera = ScriptedCamera(); val time = AtomicLong(0); val events = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        val capture = CaptureController(camera, MutableStateFlow(5L), LumixS1Profile(), this, events, time::get)
        try {
            assertEquals(PressResult.Accepted(CaptureAction.MovieStarted), capture.press())
            assertTrue(capture.ui.value.recording); assertFalse(capture.ui.value.busy)
            time.set(1499)
            assertTrue(capture.press() is PressResult.Ignored)
            time.set(1500)
            assertEquals(PressResult.Accepted(CaptureAction.MovieStopped), capture.press())
            assertFalse(capture.ui.value.recording); assertTrue(capture.ui.value.busy)
            time.set(3001)
            assertTrue(capture.press() is PressResult.Ignored)
            events.emit(Unit); capture.ui.first { !it.busy }
            assertEquals(PressResult.Accepted(CaptureAction.MovieStarted), capture.press())
            capture.close()
            assertEquals(3, camera.calls.size)
            assertTrue(camera.calls.all { it.op.code == 0x9404 && it.op.params.toList() == listOf(0x03000011L) })
        } finally { capture.close(); camera.dispose() }
    }
    @Test fun `cancelled admitted movie press preserves accepted recording with no compensation toggle`() = runBlocking {
        withTimeout(5_000) {
            val camera = ScriptedCamera(); val time = AtomicLong(0)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            camera.handler = { entered.complete(Unit); release.await(); ScriptedCamera.Reply() }
            val capture = CaptureController(camera, MutableStateFlow(5L), LumixS1Profile(), this, MutableSharedFlow(), time::get)
            try {
                val press = async(start = CoroutineStart.UNDISPATCHED) { capture.press() }
                entered.await(); press.cancel(); release.complete(Unit); press.join()
                assertTrue(capture.ui.value.recording)
                assertFalse(capture.ui.value.busy)
                assertEquals(1, camera.calls.size)
                time.set(1500)
                assertEquals(PressResult.Accepted(CaptureAction.MovieStopped), capture.press())
                assertEquals(2, camera.calls.size)
                assertEquals(1, camera.maxActive)
            } finally { release.complete(Unit); capture.close(); camera.dispose() }
        }
    }
    @Test fun `cancelled queued press sends nothing and restores debounce admission`() = runBlocking {
        withTimeout(5_000) {
            val camera = ScriptedCamera(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val capture = CaptureController(camera, MutableStateFlow(5L), LumixS1Profile(), this, MutableSharedFlow(), { 0 })
            try {
                val blocking = async(start = CoroutineStart.UNDISPATCHED) { camera.runOp { entered.complete(Unit); release.await() } }
                entered.await()
                val press = async(start = CoroutineStart.UNDISPATCHED) { capture.press() }
                press.cancel(); release.complete(Unit); blocking.await(); press.join()
                assertTrue(camera.calls.isEmpty())
                assertFalse(capture.ui.value.busy)
                assertFalse(capture.ui.value.recording)
                assertEquals(PressResult.Accepted(CaptureAction.MovieStarted), capture.press())
                assertEquals(1, camera.calls.size)
            } finally { release.complete(Unit); capture.close(); camera.dispose() }
        }
    }
    @Test fun `queued caller cancelled before worker entry never reads mode or sends shutter even if picked`() = runBlocking {
        withTimeout(5_000) {
            val camera = ScriptedCamera(); val queued = CompletableDeferred<Unit>(); val picked = CompletableDeferred<Unit>()
            var first = true
            // Deterministically emulate scheduler pickup before its cancelled awaiter runs catch.
            val port = object : PtpControlPort by camera {
                override suspend fun <T> runOp(priority: PtpScheduler.Priority, block: suspend (PtpSessionApi) -> T): T =
                    withContext(NonCancellable) {
                        if (priority == PtpScheduler.Priority.USER && first) {
                            first = false; queued.complete(Unit); picked.await()
                        }
                        camera.runOp(priority, block)
                    }
            }
            val capture = CaptureController(port, MutableStateFlow(5L), LumixS1Profile(), this, MutableSharedFlow(), { 0 },
                confirmMode = { api ->
                    api.getProperty(CameraProperty.MODE)
                    5L
                })
            try {
                val press = async(start = CoroutineStart.UNDISPATCHED) { capture.press() }
                queued.await(); press.cancel(); picked.complete(Unit); press.join()
                assertTrue(camera.calls.isEmpty())
                assertFalse(capture.ui.value.busy)
                assertFalse(capture.ui.value.recording)
                assertNull(capture.ui.value.lastError)
                assertTrue(capture.resumePresses())
            } finally { picked.complete(Unit); capture.close(); camera.dispose() }
        }
    }

    @Test fun `photo and movie completion use bounded fallbacks without extra shutter USB`() = runBlocking {
        withTimeout(5_500) {
            val camera = ScriptedCamera(); val time = AtomicLong(0); val mode = MutableStateFlow<Long?>(0)
            val capture = CaptureController(camera, mode, LumixS1Profile(), this, MutableSharedFlow(), time::get)
            try {
                capture.press(); capture.ui.first { !it.busy }
                mode.value = 5; time.set(2000); capture.press()
                time.set(3500); capture.press(); assertTrue(capture.ui.value.busy)
                capture.ui.first { !it.busy }
                assertFalse(capture.ui.value.recording)
                assertEquals(3, camera.calls.size)
            } finally { capture.close(); camera.dispose() }
        }
    }
    @Test fun `unknown mode generic profile or refused shutter never flips local state`() = runBlocking {
        val camera = ScriptedCamera(); val mode = MutableStateFlow<Long?>(null)
        val capture = CaptureController(camera, mode, LumixS1Profile(), this, MutableSharedFlow())
        val generic = CaptureController(camera, MutableStateFlow(5L), GenericPtpProfile(), this, MutableSharedFlow())
        try {
            assertTrue(capture.press() is PressResult.Ignored)
            assertTrue(generic.press() is PressResult.Ignored)
            assertTrue(camera.calls.isEmpty())
            mode.value = 5
            camera.handler = { ScriptedCamera.Reply(code = 0x2019) }
            assertEquals(PressResult.Rejected(0x2019), capture.press())
            assertFalse(capture.ui.value.recording); assertFalse(capture.ui.value.busy)
            assertEquals(1, camera.calls.size)
        } finally { capture.close(); generic.close(); camera.dispose() }
    }

    private fun confirming(camera: ScriptedCamera, cachedMode: MutableStateFlow<Long?>, scope: CoroutineScope,
        events: MutableSharedFlow<Unit>, time: AtomicLong, observed: (Long) -> Unit = {}): CaptureController =
        CaptureController(camera, cachedMode, LumixS1Profile(), scope, events, time::get,
            confirmMode = { api ->
                val data = api.getProperty(CameraProperty.MODE)
                check(data.response.code == 0x2001)
                PropertyCodec.decodeValue(data.payload, CameraProperty.MODE)
            }, onModeObserved = observed)

    @Test fun `physical photo to video is confirmed before capture and exit waits admitted start then unique stop`() = runBlocking {
        withTimeout(6_000) {
            val camera = ScriptedCamera(); val time = AtomicLong(0); val events = MutableSharedFlow<Unit>(extraBufferCapacity = 2)
            val cached = MutableStateFlow<Long?>(0); val physical = AtomicLong(5)
            val startEntered = CompletableDeferred<Unit>(); val startReply = CompletableDeferred<Unit>()
            val stopEntered = CompletableDeferred<Unit>(); val stopReply = CompletableDeferred<Unit>()
            var toggles = 0
            camera.handler = { call -> when (call.op.code) {
                0x9402 -> ScriptedCamera.Reply(value(CameraProperty.MODE, physical.get()))
                0x9404 -> {
                    if (++toggles == 1) { startEntered.complete(Unit); startReply.await() }
                    else { stopEntered.complete(Unit); stopReply.await() }
                    ScriptedCamera.Reply()
                }
                else -> error("Unexpected USB opcode")
            } }
            val capture = confirming(camera, cached, this, events, time) { cached.value = it }
            try {
                val press = async(start = CoroutineStart.UNDISPATCHED) { capture.press() }
                startEntered.await()
                assertFalse(capture.ui.value.recording) // Start response is still on the fake wire.
                val exit = async(start = CoroutineStart.UNDISPATCHED) { capture.finishRecording() }
                assertFalse(exit.isCompleted)
                assertTrue(capture.press() is PressResult.Ignored)
                assertEquals(1, toggles)
                time.set(1500); startReply.complete(Unit)
                assertEquals(PressResult.Accepted(CaptureAction.MovieStarted), press.await())
                stopEntered.await()
                assertEquals(5L, cached.value)
                assertTrue(capture.ui.value.recording)
                assertFalse(exit.isCompleted) // Closing waits for the stop response, too.
                stopReply.complete(Unit)
                camera.runOp(PtpScheduler.Priority.ABORT) {}
                events.emit(Unit)
                exit.await()
                assertFalse(capture.ui.value.recording)
                assertEquals(2, toggles)
                capture.finishRecording() // Idempotent exit sends nothing else.
                assertEquals(listOf(0x9402, 0x9404, 0x9402, 0x9404), camera.calls.map { it.op.code })
                assertTrue(capture.resumePresses())
                assertEquals(1, camera.maxActive)
            } finally { startReply.complete(Unit); stopReply.complete(Unit); capture.close(); camera.dispose() }
        }
    }

    @Test fun `physical video to photo uses photo debounce and owned video mode changes never guess a toggle`() = runBlocking {
        val camera = ScriptedCamera(); val time = AtomicLong(0); val events = MutableSharedFlow<Unit>(extraBufferCapacity = 2)
        val physical = AtomicLong(0); val cached = MutableStateFlow<Long?>(5)
        camera.handler = { call -> ScriptedCamera.Reply(if (call.op.code == 0x9402) value(CameraProperty.MODE, physical.get()) else byteArrayOf()) }
        val capture = confirming(camera, cached, this, events, time) { cached.value = it }
        try {
            assertEquals(PressResult.Accepted(CaptureAction.Photo), capture.press())
            assertEquals(0L, cached.value); assertFalse(capture.ui.value.recording)
            events.emit(Unit); capture.ui.first { !it.busy }
            physical.set(5); time.set(1500)
            assertEquals(PressResult.Accepted(CaptureAction.MovieStarted), capture.press())
            physical.set(0); time.set(3000)
            assertTrue(capture.press() is PressResult.Ignored)
            assertTrue(capture.ui.value.recording)
            assertTrue(capture.stopOwnedRecording() is PressResult.Ignored)
            assertTrue(capture.ui.value.lastError!!.contains("模式已改变"))
            assertEquals(2, camera.calls.count { it.op.code == 0x9404 })
            physical.set(5); time.set(4500)
            assertEquals(PressResult.Accepted(CaptureAction.MovieStopped), capture.stopOwnedRecording())
            assertEquals(3, camera.calls.count { it.op.code == 0x9404 })
            assertFalse(capture.ui.value.recording)
        } finally { capture.close(); camera.dispose() }
    }

    @Test fun `failed exit stop preserves recording and permits explicit retry without an extra start`() = runBlocking {
        val camera = ScriptedCamera(); val time = AtomicLong(0); var stopCode = 0x2001
        camera.handler = { call -> if (call.op.code == 0x9402) ScriptedCamera.Reply(value(CameraProperty.MODE, 5)) else ScriptedCamera.Reply(code = stopCode) }
        val capture = confirming(camera, MutableStateFlow(5L), this, MutableSharedFlow(), time)
        try {
            capture.press(); time.set(1500); stopCode = 0x2019
            try { capture.finishRecording(); fail("Refused stop must prevent an ordinary successful exit") }
            catch (_: IllegalStateException) { }
            assertTrue(capture.ui.value.recording)
            assertFalse(capture.ui.value.busy)
            assertTrue(capture.press() is PressResult.Ignored) // Exit admission stays closed.
            stopCode = 0x2001; time.set(3000)
            assertEquals(PressResult.Accepted(CaptureAction.MovieStopped), capture.stopOwnedRecording())
            assertFalse(capture.ui.value.recording)
            assertNull(capture.stopOwnedRecording())
            assertEquals(3, camera.calls.count { it.op.code == 0x9404 })
        } finally { capture.close(); camera.dispose() }
    }

    @Test fun `fatal or detached exit never adds a toggle and malformed mode never reaches shutter`() = runBlocking {
        val camera = ScriptedCamera(); val time = AtomicLong(0)
        camera.handler = { call -> ScriptedCamera.Reply(if (call.op.code == 0x9402) value(CameraProperty.MODE, 5) else byteArrayOf()) }
        val capture = confirming(camera, MutableStateFlow(5L), this, MutableSharedFlow(), time)
        try {
            capture.press(); camera.isFatal = true; time.set(1500)
            assertNull(capture.stopOwnedRecording())
            capture.close()
            assertEquals(1, camera.calls.count { it.op.code == 0x9404 })
            assertTrue(capture.ui.value.recording)
            assertFalse(capture.resumePresses())
        } finally { capture.close(); camera.dispose() }
        val malformed = ScriptedCamera()
        val other = confirming(malformed, MutableStateFlow(5L), this, MutableSharedFlow(), AtomicLong(0))
        try {
            try { other.press(); fail("Malformed mode cannot trigger capture") } catch (_: IllegalArgumentException) { }
            assertEquals(listOf(0x9402), malformed.calls.map { it.op.code })
            assertFalse(other.ui.value.recording)
        } finally { other.close(); malformed.dispose() }
    }
}
