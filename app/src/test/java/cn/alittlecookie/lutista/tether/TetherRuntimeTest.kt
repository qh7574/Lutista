package cn.alittlecookie.lutista.tether.session

import android.hardware.usb.UsbDevice
import cn.alittlecookie.lutista.BuildConfig
import cn.alittlecookie.lutista.tether.debug.TetherDebugConfig
import cn.alittlecookie.lutista.tether.debug.TetherDiagLogger
import cn.alittlecookie.lutista.tether.driver.CameraDriverFactory
import cn.alittlecookie.lutista.tether.driver.CloseReason
import cn.alittlecookie.lutista.tether.driver.ConnectOutcome
import cn.alittlecookie.lutista.tether.driver.PtpUsbDriver
import cn.alittlecookie.lutista.tether.driver.UsbTarget
import cn.alittlecookie.lutista.tether.profile.CameraCapabilities
import cn.alittlecookie.lutista.tether.protocol.PtpConstants
import cn.alittlecookie.lutista.tether.transport.PtpDeviceCaps
import cn.alittlecookie.lutista.tether.transport.PtpException
import cn.alittlecookie.lutista.tether.transport.PtpResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import io.mockk.verify
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Real runtime orchestration; no Android main looper, USB connection or camera is used. */
class TetherRuntimeTest {
    private val runtimes = mutableListOf<TetherRuntime>()
    private val main = object : MainCoroutineDispatcher() {
        override val immediate: MainCoroutineDispatcher get() = this
        override fun isDispatchNeeded(context: CoroutineContext) = false
        override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
    }

    @Before fun before() { assumeTrue(BuildConfig.DEBUG) }

    @After fun after() { runtimes.forEach { it.dispose() } }

    private data class Harness(
        val runtime: TetherRuntime,
        val session: CameraSession,
        val driver: PtpUsbDriver,
        val api: PtpSessionApi,
        val open: AtomicBoolean,
        val trace: MutableList<String>,
        val hub: UsbHub,
        val device: UsbDevice,
        val drivers: CameraDriverFactory,
        val connectTargets: MutableList<UsbTarget>,
    )

    private fun harness(): Harness {
        val device = mockk<UsbDevice>()
        every { device.deviceName } returns "/dev/bus/usb/test"
        every { device.vendorId } returns 0x04DA
        every { device.productId } returns 0x2382
        val hub = mockk<UsbHub>()
        every { hub.events } returns MutableSharedFlow<UsbHub.Event>()
        every { hub.enumerate() } returns listOf(device)
        every { hub.hasPermission(device) } returns true
        every { hub.close() } just runs

        val settings = TetherDebugConfig.Snapshot(enabled = true)
        val config = mockk<TetherDebugConfig>()
        every { config.state } returns MutableStateFlow(settings)
        coEvery { config.snapshot() } returns settings
        val session = mockk<CameraSession>()
        val state = MutableStateFlow(TetherState())
        val eventPump = EventPump()
        val open = AtomicBoolean(false)
        every { session.state } returns state
        every { session.deviceCaps } returns MutableStateFlow<PtpDeviceCaps?>(null)
        every { session.eventPump } returns eventPump
        every { session.events } returns eventPump.events
        every { session.transportFatal } returns false
        every { session.transportOpen } answers { open.get() }
        val trace = mutableListOf<String>()
        val driver = mockk<PtpUsbDriver>()
        every { driver.session } returns session
        every { driver.capabilities } returns MutableStateFlow(CameraCapabilities.empty())
        val connectTargets = mutableListOf<UsbTarget>()
        coEvery { driver.connect(any()) } coAnswers {
            val current = firstArg<UsbTarget>()
            connectTargets += current
            if (!current.permissionGranted) {
                state.value = TetherState(TetherPhase.NotAttached, message = "USB 授权未完成，可重试")
                ConnectOutcome.PermissionDenied
            } else {
                trace += "connect"
                open.set(true)
                state.value = TetherState(TetherPhase.Connected)
                ConnectOutcome.Connected
            }
        }
        coEvery { driver.disconnect(any()) } coAnswers {
            // This also fails the cancellation test if cleanup loses NonCancellable.
            currentCoroutineContext().ensureActive()
            trace += "close:" + firstArg<CloseReason>()
            open.set(false)
            state.value = TetherState(TetherPhase.Idle, message = "已关闭")
        }
        val api = mockk<PtpSessionApi>()
        fun ok() = PtpData(PtpResponse(PtpConstants.Rc.OK), byteArrayOf())
        coEvery { api.getDeviceInfo(1_000) } coAnswers { trace += "deviceInfo"; ok() }
        coEvery { api.getStorageIds(1_000) } coAnswers { trace += "storageIds"; ok() }
        coEvery { api.liveViewFormats(300) } coAnswers { trace += "liveViewFormats"; ok() }
        coEvery { api.getProperty(PtpConstants.Prop.MODE_POS, 200) } coAnswers { trace += "modePos"; ok() }
        coEvery { api.listPropertyDescriptor(PtpConstants.Prop.ISO_CONTAINER, 300) } coAnswers { trace += "isoDescriptor"; ok() }
        coEvery { session.runOp<Unit>(any(), any()) } coAnswers {
            secondArg<suspend (PtpSessionApi) -> Unit>().invoke(api)
        }
        coEvery { session.drainDiagnosticEvents(3) } coAnswers { trace += "drain:3"; Unit }
        val drivers = mockk<CameraDriverFactory>()
        every { drivers.create(any(), any()) } returns driver
        val logger = TetherDiagLogger(false, { 0L }, { _, _ -> })
        // Capture Main in the runtime's scope, then restore Dispatchers before any coVerify.
        // A test-wide static mock also records coVerify's internal getDefault() calls.
        mockkStatic(Dispatchers::class)
        val runtime = try {
            every { Dispatchers.Main } returns main
            TetherRuntime(hub, drivers, config, logger).also { runtimes += it }
        } finally { unmockkStatic(Dispatchers::class) }
        return Harness(runtime, session, driver, api, open, trace, hub, device, drivers, connectTargets)
    }

    private suspend fun assertClosed(h: Harness) {
        assertFalse("Evidence must close the active driver", h.open.get())
        withTimeout(1_000) { h.runtime.state.first { it.phase == TetherPhase.Idle } }
        coVerify(exactly = 1) { h.driver.disconnect(CloseReason.USER) }
    }

    @Test fun evidenceSuccessSurvivesAutomaticClose() = runBlocking {
        val h = harness()

        h.runtime.runEvidence()

        assertClosed(h)
        assertEquals(
            "只读序列已执行；请按实际日志核验，尚未签收真机门禁",
            h.runtime.message.value,
        )
        assertEquals(
            listOf("connect", "deviceInfo", "storageIds", "liveViewFormats", "modePos", "isoDescriptor", "drain:3", "close:USER"),
            h.trace,
        )
        coVerify(exactly = 0) { h.api.setProperty(any(), any(), any()) }
    }

    @Test fun evidenceExceptionSurvivesAutomaticClose() = runBlocking {
        val h = harness()
        coEvery { h.api.getStorageIds(1_000) } throws PtpException("synthetic read failure")

        h.runtime.runEvidence()

        assertClosed(h)
        assertEquals("PtpException: synthetic read failure", h.runtime.message.value)
        coVerify(exactly = 0) { h.session.drainDiagnosticEvents(any()) }
        coVerify(exactly = 0) { h.api.liveViewFormats(any()) }
    }

    @Test fun rejectedResponseSurvivesAutomaticClose() = runBlocking {
        val h = harness()
        coEvery { h.api.getStorageIds(1_000) } returns PtpData(PtpResponse(0x2019), byteArrayOf())

        h.runtime.runEvidence()

        assertClosed(h)
        assertEquals("IllegalStateException: GetStorageIDs rc=0x2019", h.runtime.message.value)
        coVerify(exactly = 0) { h.session.drainDiagnosticEvents(any()) }
    }

    @Test fun cancelledEvidenceStillClosesWithNonCancellableContext() = runBlocking {
        val h = harness()
        val reading = CompletableDeferred<Unit>()
        coEvery { h.api.getStorageIds(1_000) } coAnswers {
            reading.complete(Unit)
            awaitCancellation()
        }
        val job = launch(start = CoroutineStart.UNDISPATCHED) { h.runtime.runEvidence() }
        try {
            withTimeout(1_000) { reading.await() }
            job.cancelAndJoin()

            assertTrue(job.isCancelled)
            assertClosed(h)
            coVerify(exactly = 0) { h.session.drainDiagnosticEvents(any()) }
        } finally { job.cancelAndJoin() }
    }

    @Test fun reusedUsbAddressRefreshesRevokedAndExternallyGrantedPermission() = runBlocking {
        val h = harness()
        assertEquals(ConnectOutcome.Connected, h.runtime.connect())
        h.runtime.disconnect(CloseReason.DETACHED)

        // The same USB address/VID/PID is attached again without its old permission.
        every { h.hub.hasPermission(h.device) } returns false
        assertEquals(ConnectOutcome.PermissionDenied, h.runtime.connect())
        assertFalse(h.open.get())

        // A system grant then becomes visible without changing this target's cache key.
        every { h.hub.hasPermission(h.device) } returns true
        assertEquals(ConnectOutcome.Connected, h.runtime.connect())
        assertEquals(listOf(true, false, true), h.connectTargets.map { it.permissionGranted })
        assertEquals(1, h.connectTargets.map { it.cacheKey }.distinct().size)
        verify(exactly = 1) { h.drivers.create(any(), any()) }
        coVerify(exactly = 0) { h.driver.connect() }
    }

    @Test fun manualCloseKeepsItsExistingMessage() = runBlocking {
        val h = harness()
        assertEquals(ConnectOutcome.Connected, h.runtime.connect())

        h.runtime.disconnect()

        assertClosed(h)
        assertEquals("USB 会话已关闭，原因：USER", h.runtime.message.value)
    }
}
