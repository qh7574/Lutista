package cn.alittlecookie.lutista.tether.config

import cn.alittlecookie.lutista.tether.controls.ScriptedCamera
import cn.alittlecookie.lutista.tether.controls.descriptor
import cn.alittlecookie.lutista.tether.controls.value
import cn.alittlecookie.lutista.tether.profile.profiles.LumixS1Profile
import cn.alittlecookie.lutista.tether.transport.PtpTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class CameraConfigTest {
    private class Model(val camera: ScriptedCamera) {
        var mode = 0L
        val values = CameraProperty.display.associateWith { when (it) {
            CameraProperty.ISO -> 400L; CameraProperty.SHUTTER -> 125000L
            CameraProperty.APERTURE -> 0xFFFEL; CameraProperty.PHOTO_STYLE -> 2L; else -> 0L
        } }.toMutableMap()
        val enums = CameraProperty.display.associateWith { listOf(values.getValue(it), values.getValue(it) + 1) }.toMutableMap()
        var rejectDescriptor: Long? = null
        var rejectSet = 0x2001
        var ignoreSet = false
        var beforeSet: suspend () -> Unit = {}
        init {
            enums[CameraProperty.PHOTO_STYLE] = (0L..22L).toList()
            enums[CameraProperty.APERTURE] = emptyList()
            camera.handler = { call ->
                val wireId = call.op.params.firstOrNull()
                val id = CameraProperty.whitelist.firstOrNull { CameraProperty.valueId(it) == wireId }
                when (call.op.code) {
                    0x9402 -> ScriptedCamera.Reply(value(requireNotNull(id), if (id == CameraProperty.MODE) mode else values.getValue(id)))
                    0x9108 -> if (wireId == rejectDescriptor) ScriptedCamera.Reply(code = 0x2005)
                        else ScriptedCamera.Reply(descriptor(wireId!!, values.getValue(wireId), enums.getValue(wireId)))
                    0x9403 -> {
                        beforeSet()
                        val b = ByteBuffer.wrap(call.dataOut!!).order(ByteOrder.LITTLE_ENDIAN)
                        assertEquals(wireId!!.toInt(), b.int); assertEquals(4, b.short.toInt()); assertEquals(0, b.short.toInt())
                        val requested = b.int.toLong() and 0xFFFFFFFFL
                        if (rejectSet == 0x2001 && !ignoreSet) values[requireNotNull(id)] = requested
                        ScriptedCamera.Reply(code = rejectSet)
                    }
                    else -> error("Unexpected USB operation ${call.op}")
                }
            }
        }
    }
    @Test fun `refresh reads every visible property and grays each independently`() = runBlocking {
        val c = ScriptedCamera()
        try {
            val m = Model(c)
            m.enums[CameraProperty.SHUTTER] = emptyList()
            m.rejectDescriptor = CameraProperty.ISO
            val config = CameraConfig(c, LumixS1Profile())
            val catalog = config.refreshCatalog()
            assertEquals(CameraProperty.display.toSet(), catalog.properties.keys)
            assertEquals(PropertyAccess.Unsupported, catalog.properties.getValue(CameraProperty.ISO).access)
            assertEquals(400L, catalog.properties.getValue(CameraProperty.ISO).value)
            assertEquals(PropertyAccess.ReadOnly, catalog.properties.getValue(CameraProperty.SHUTTER).access)
            assertEquals("1/125", catalog.properties.getValue(CameraProperty.SHUTTER).label)
            assertEquals("—", catalog.properties.getValue(CameraProperty.APERTURE).label)
            assertEquals(PropertyAccess.Writable, catalog.properties.getValue(CameraProperty.EV).access)
            assertEquals((1L..22L).toSet(), catalog.properties.getValue(CameraProperty.PHOTO_STYLE).descriptor!!.values.toSet())
            assertEquals("格调 2", catalog.properties.getValue(CameraProperty.PHOTO_STYLE).label)
            val reads = c.calls.filter { it.op.code == 0x9402 }.map { it.op.params.single() }
            CameraProperty.display.forEach { assertTrue(reads.contains(CameraProperty.valueId(it))) }
            val before = c.calls.size
            assertTrue(config.writeProperty(CameraProperty.SHUTTER, 125000) is WriteResult.Disabled)
            assertTrue(runCatching { config.readProperty(0x0200FFFF) }.isFailure)
            assertEquals(before, c.calls.size)
        } finally { c.dispose() }
    }
    @Test fun `video submode and actual enum arrays change within the same mode position`() = runBlocking {
        val c = ScriptedCamera()
        try {
            val m = Model(c); m.mode = 5
            var observed: Long? = null
            val config = CameraConfig(c, LumixS1Profile(), onModeObserved = { observed = it })
            val s = config.refreshCatalog()
            assertEquals(5L, observed)
            assertEquals(ModeKey(5, 2), s.mode)
            m.enums[CameraProperty.SHUTTER] = emptyList()
            m.enums[CameraProperty.ISO] = listOf(400, 800, 1600)
            val p = config.refreshCatalog()
            assertEquals(ModeKey(5, 0), p.mode)
            assertEquals(PropertyAccess.ReadOnly, p.properties.getValue(CameraProperty.SHUTTER).access)
            assertEquals(listOf(400L, 800L, 1600L), p.properties.getValue(CameraProperty.ISO).descriptor!!.values)
            m.enums[CameraProperty.APERTURE] = listOf(35, 40)
            m.enums[CameraProperty.SHUTTER] = listOf(125000, 250000)
            val manual = config.refreshCatalog()
            assertEquals(ModeKey(5, 3), manual.mode)
            assertEquals(PropertyAccess.Writable, manual.properties.getValue(CameraProperty.SHUTTER).access)
            assertEquals(1, c.maxActive)
        } finally { c.dispose() }
    }
    @Test fun `successful write immediately reads actual value within 300ms on the same worker`() = runBlocking {
        val c = ScriptedCamera()
        try {
            Model(c)
            val config = CameraConfig(c, LumixS1Profile())
            config.refreshCatalog()
            assertTrue(config.writeProperty(CameraProperty.ISO, 401) is WriteResult.Applied)
            val writes = c.calls.filter { it.op.code == 0x9403 }
            assertEquals(1, writes.size)
            val at = c.calls.indexOf(writes.single())
            assertEquals(0x9402, c.calls[at + 1].op.code)
            assertEquals(300, c.calls[at + 1].timeoutMs)
            assertTrue(c.calls[at + 1].at - writes.single().at < 300)
            assertEquals(401L, config.catalog.value!!.properties.getValue(CameraProperty.ISO).value)
        } finally { c.dispose() }
    }
    @Test fun `readback mismatch preserves actual and rejected value does not disable a property`() = runBlocking {
        val c = ScriptedCamera()
        try {
            val m = Model(c); val config = CameraConfig(c, LumixS1Profile()); config.refreshCatalog()
            m.ignoreSet = true
            assertEquals(WriteResult.Refused(400, null), config.writeProperty(CameraProperty.ISO, 401))
            assertEquals(400L, config.catalog.value!!.properties.getValue(CameraProperty.ISO).value)
            m.rejectSet = 0x201D
            assertEquals(WriteResult.Refused(400, 0x201D), config.writeProperty(CameraProperty.ISO, 401))
            assertEquals(PropertyAccess.Writable, config.catalog.value!!.properties.getValue(CameraProperty.ISO).access)
            m.rejectSet = 0x2005
            assertTrue(config.writeProperty(CameraProperty.ISO, 401) is WriteResult.Disabled)
            assertEquals(PropertyAccess.Unsupported, config.catalog.value!!.properties.getValue(CameraProperty.ISO).access)
            assertEquals(PropertyAccess.Writable, config.catalog.value!!.properties.getValue(CameraProperty.EV).access)
            assertEquals(3, c.calls.count { it.op.code == 0x9403 })
        } finally { c.dispose() }
    }
    @Test fun `cancelled admitted style write still reads back and restores original exactly once`() = runBlocking {
        withTimeout(5_000) {
            val c = ScriptedCamera()
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            try {
                val m = Model(c); val config = CameraConfig(c, LumixS1Profile()); config.refreshCatalog()
                m.beforeSet = { entered.complete(Unit); release.await() }
                val write = async(start = CoroutineStart.UNDISPATCHED) { config.writeProperty(CameraProperty.PHOTO_STYLE, 9) }
                entered.await(); write.cancel(); release.complete(Unit); write.join()
                c.runOp { } // Worker barrier, after the cancelled consumer's real transaction.
                assertTrue(config.photoStyleChanged.value)
                assertEquals(9L, config.catalog.value!!.properties.getValue(CameraProperty.PHOTO_STYLE).value)
                m.beforeSet = {}
                assertTrue(config.restorePhotoStyle() is WriteResult.Applied)
                assertFalse(config.photoStyleChanged.value)
                assertEquals(2L, m.values.getValue(CameraProperty.PHOTO_STYLE))
                assertNull(config.restorePhotoStyle())
                assertEquals(2, c.calls.count { it.op.code == 0x9403 })
            } finally { release.complete(Unit); c.dispose() }
        }
    }
    @Test fun `explicit write unavailability keeps descriptor reads but sends no mutation`() = runBlocking {
        val camera = ScriptedCamera()
        try {
            Model(camera)
            val config = CameraConfig(camera, LumixS1Profile(), writeAllowed = { false })
            config.refreshCatalog()
            val before = camera.calls.size
            assertTrue(config.writeProperty(CameraProperty.ISO, 401) is WriteResult.Disabled)
            assertEquals(before, camera.calls.size)
            assertFalse(config.photoStyleChanged.value)
        } finally { camera.dispose() }
    }

    @Test fun `restoration drains a cancelled first style snapshot before checking saved original`() = runBlocking {
        withTimeout(5_000) {
            val camera = ScriptedCamera(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            try {
                val model = Model(camera); val config = CameraConfig(camera, LumixS1Profile())
                config.refreshCatalog()
                val delegate = camera.handler
                var hold = true
                camera.handler = { call ->
                    if (hold && call.op.code == 0x9402 && call.op.params.single() == CameraProperty.PHOTO_STYLE) {
                        hold = false; entered.complete(Unit); release.await()
                    }
                    delegate(call)
                }
                val writing = async(start = CoroutineStart.UNDISPATCHED) { config.writeProperty(CameraProperty.PHOTO_STYLE, 9) }
                entered.await(); writing.cancelAndJoin()
                val restoring = async(start = CoroutineStart.UNDISPATCHED) { config.restorePhotoStyle() }
                assertFalse(restoring.isCompleted)
                release.complete(Unit)
                assertTrue(restoring.await() is WriteResult.Applied)
                assertEquals(2L, model.values.getValue(CameraProperty.PHOTO_STYLE))
                assertFalse(config.photoStyleChanged.value)
                assertEquals(2, camera.calls.count { it.op.code == 0x9403 })
            } finally { release.complete(Unit); camera.dispose() }
        }
    }

    @Test fun `transport failure halts refresh and fatal cleanup sends no restoration USB`() = runBlocking {
        val c = ScriptedCamera()
        try {
            c.handler = { throw PtpTimeoutException("scripted transport failure") }
            val config = CameraConfig(c, LumixS1Profile())
            assertTrue(runCatching { config.refreshCatalog() }.exceptionOrNull() is PtpTimeoutException)
            assertEquals(1, c.calls.size)
            c.isFatal = true
            assertNull(config.restorePhotoStyle())
            assertEquals(1, c.calls.size)
        } finally { c.dispose() }
    }
}
