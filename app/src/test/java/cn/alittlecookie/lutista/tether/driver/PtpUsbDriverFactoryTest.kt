package cn.alittlecookie.lutista.tether.driver

import android.os.SystemClock
import cn.alittlecookie.lutista.tether.debug.TetherDebugConfig
import cn.alittlecookie.lutista.tether.debug.TetherDiagLogger
import cn.alittlecookie.lutista.tether.profile.CameraProfileRegistry
import cn.alittlecookie.lutista.tether.transport.Transport
import cn.alittlecookie.lutista.tether.transport.TransportFactory
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PtpUsbDriverFactoryTest {
    @Test fun rejectedTransportIsNeitherSupportedNorCreated() {
        val raw = FactoryTransport(false)
        val factory = factory(raw)
        val scope = CoroutineScope(SupervisorJob())
        try {
            val target = driverTarget()
            assertFalse(factory.supports(target))
            assertNull(factory.create(target, scope))
            assertEquals(0, raw.creates)
        } finally { scope.cancel() }
    }

    @Test fun everyCreateOwnsFreshSessionSchedulerAndStatus() {
        val raw = FactoryTransport(true)
        val factory = factory(raw)
        val scope = CoroutineScope(SupervisorJob())
        try {
            val target = driverTarget()
            val first = factory.create(target, scope) as PtpUsbDriver
            val second = factory.create(target, scope) as PtpUsbDriver
            assertNotSame(first, second)
            assertNotSame(first.session, second.session)
            assertNotSame(first.session.scheduler, second.session.scheduler)
            assertNotSame(first.status, second.status)
            assertSame(first.session.caps, first.capabilities)
            assertNull(first.status.battery.value)
            assertNull(first.status.modePos.value)
            assertNull(first.objects)
            assertNull(first.liveView)
            assertNull(first.config)
            assertNull(first.capture)
            assertEquals(0, raw.creates)
        } finally { scope.cancel() }
    }

    @Test fun connectDiscoversOriginalTargetAndForwardsPermissionWithoutOpeningUsb() = runBlocking {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 100L
        val raw = FactoryTransport(true)
        val target = driverTarget(permissionGranted = false)
        val seen = mutableListOf<UsbTarget>()
        val factory = factory(raw) { seen += it; false }
        val scope = CoroutineScope(SupervisorJob())
        try {
            val first = factory.create(target, scope)!!
            val second = factory.create(target, scope)!!
            assertEquals(ConnectOutcome.PermissionDenied, first.connect())
            assertEquals(ConnectOutcome.PermissionDenied, second.connect())
            assertEquals(2, seen.size)
            assertSame(target, seen[0])
            assertSame(target, seen[1])
            assertEquals(0, raw.creates)
        } finally {
            scope.cancel()
            scope.coroutineContext[Job]!!.join()
            unmockkStatic(SystemClock::class)
        }
    }

    private fun factory(
        transport: TransportFactory,
        permission: suspend (UsbTarget) -> Boolean = { it.permissionGranted },
    ): CameraDriverFactory = PtpUsbDriverFactory(
        transport, CameraProfileRegistry(), DriverCache().cache, mockk<TetherDebugConfig>(),
        TetherDiagLogger(), permission,
    )

    private class FactoryTransport(private val accepted: Boolean) : TransportFactory {
        var creates = 0
        override fun supports(target: CameraTarget): Boolean = accepted
        override suspend fun create(target: CameraTarget): Transport? {
            creates++
            error("A factory must not acquire USB handles before connect/permission")
        }
    }
}
