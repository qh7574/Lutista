package cn.alittlecookie.lutista.tether.session

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
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
import org.junit.Assert.assertNull
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
        val hubEvents: MutableSharedFlow<UsbHub.Event>,
    )

    private fun usbDevice(name: String = "/dev/bus/usb/test", pid: Int = 0x2382, usable: Boolean = true): UsbDevice {
        val device = mockk<UsbDevice>()
        every { device.deviceName } returns name
        every { device.vendorId } returns 0x04DA
        every { device.productId } returns pid
        every { device.interfaceCount } returns if (usable) 1 else 0
        if (usable) {
            val intf = mockk<UsbInterface>()
            val input = mockk<UsbEndpoint>()
            val output = mockk<UsbEndpoint>()
            every { device.getInterface(0) } returns intf
            every { intf.interfaceClass } returns UsbConstants.USB_CLASS_STILL_IMAGE
            every { intf.interfaceSubclass } returns 1
            every { intf.interfaceProtocol } returns 1
            every { intf.endpointCount } returns 2
            every { intf.getEndpoint(0) } returns input
            every { intf.getEndpoint(1) } returns output
            every { input.type } returns UsbConstants.USB_ENDPOINT_XFER_BULK
            every { output.type } returns UsbConstants.USB_ENDPOINT_XFER_BULK
            every { input.direction } returns UsbConstants.USB_DIR_IN
            every { output.direction } returns UsbConstants.USB_DIR_OUT
        }
        return device
    }

    private fun harness(lease: TetherConnectionLease = TetherConnectionLease()): Harness {
        val device = usbDevice()
        val hub = mockk<UsbHub>()
        val hubEvents = MutableSharedFlow<UsbHub.Event>()
        every { hub.events } returns hubEvents
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
            TetherRuntime(hub, drivers, config, logger, lease).also { runtimes += it }
        } finally { unmockkStatic(Dispatchers::class) }
        return Harness(runtime, session, driver, api, open, trace, hub, device, drivers, connectTargets, hubEvents)
    }

    private suspend fun assertClosed(h: Harness) {
        assertFalse("Evidence must close the active driver", h.open.get())
        withTimeout(1_000) { h.runtime.state.first { it.phase == TetherPhase.Idle } }
        coVerify(exactly = 1) { h.driver.disconnect(CloseReason.USER) }
    }

    @Test fun separateFormalAndDiagnosticRuntimesCannotOpenUsbConcurrently() = runBlocking {
        val lease = TetherConnectionLease()
        val formal = harness(lease)
        val diagnostic = harness(lease)
        formal.runtime.setObjectEventConsumer { _, _ -> }
        assertEquals(ConnectOutcome.Connected, formal.runtime.connect())

        assertTrue(diagnostic.runtime.connect() is ConnectOutcome.Failed)
        coVerify(exactly = 0) { diagnostic.driver.connect(any()) }
        verify(exactly = 0) { diagnostic.drivers.create(any(), any()) }

        formal.runtime.disconnect()
        assertEquals(ConnectOutcome.Connected, diagnostic.runtime.connect())
        assertFalse(formal.open.get())
        assertTrue(diagnostic.open.get())
    }

    @Test fun failedConnectionReleasesLeaseWithoutOpeningAnotherEndpoint() = runBlocking {
        val lease = TetherConnectionLease()
        val failed = harness(lease)
        val retry = harness(lease)
        coEvery { failed.driver.connect(any()) } returns ConnectOutcome.Failed(PtpException("synthetic rejected open"))

        assertTrue(failed.runtime.connect() is ConnectOutcome.Failed)
        assertEquals(ConnectOutcome.Connected, retry.runtime.connect())
        assertFalse(failed.open.get())
    }

    @Test fun disconnectDuringConnectRetainsLeaseUntilOpenAndCleanupFinish() = runBlocking {
        val lease = TetherConnectionLease()
        val first = harness(lease)
        val second = harness(lease)
        val admitted = CompletableDeferred<Unit>()
        val releaseOpen = CompletableDeferred<Unit>()
        coEvery { first.driver.connect(any()) } coAnswers {
            admitted.complete(Unit)
            releaseOpen.await()
            first.open.set(true)
            first.trace += "open-complete"
            ConnectOutcome.Connected
        }
        val connection = launch(start = CoroutineStart.UNDISPATCHED) { first.runtime.connect() }
        withTimeout(1_000) { admitted.await() }
        val disconnection = launch(start = CoroutineStart.UNDISPATCHED) { first.runtime.disconnect() }
        try {
            assertTrue(second.runtime.connect() is ConnectOutcome.Failed)
            coVerify(exactly = 0) { first.driver.disconnect(any()) }
            releaseOpen.complete(Unit)
            withTimeout(1_000) { connection.join(); disconnection.join() }
            assertEquals(listOf("open-complete", "close:USER"), first.trace)
            assertFalse(first.open.get())
            assertEquals(ConnectOutcome.Connected, second.runtime.connect())
        } finally { releaseOpen.complete(Unit); connection.cancelAndJoin(); disconnection.cancelAndJoin() }
    }

    @Test fun formalRuntimeDoesNotAutoReconnectAfterPhysicalDetach() = runBlocking {
        val h = harness()
        h.runtime.setObjectEventConsumer { _, _ -> }
        assertEquals(ConnectOutcome.Connected, h.runtime.connect())
        h.hubEvents.emit(UsbHub.Event.Detached(h.device))
        withTimeout(1_000) { h.runtime.state.first { it.phase == TetherPhase.Idle } }
        h.hubEvents.emit(UsbHub.Event.Attached(h.device))
        assertFalse(h.open.get())
        coVerify(exactly = 1) { h.driver.connect(any()) }
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

    @Test fun noUsbShowsAcknowledgedNoticeWithoutCreatingOrAuthorizingDriver() = runBlocking {
        val h = harness()
        every { h.hub.enumerate() } returns emptyList()
        assertEquals(ConnectOutcome.NotAttached, h.runtime.connect())
        assertEquals(TetherConnectionNotice.NoUsableUsb, h.runtime.connectionNotice.value)
        assertEquals(TetherPhase.NotAttached, h.runtime.state.value.phase)
        assertTrue(h.runtime.message.value.contains("拔下 USB"))
        verify(exactly = 0) { h.hub.hasPermission(any()) }
        verify(exactly = 0) { h.drivers.create(any(), any()) }
        h.runtime.dismissConnectionNotice()
        assertNull(h.runtime.connectionNotice.value)
        assertEquals(ConnectOutcome.NotAttached, h.runtime.connect())
        assertEquals(TetherConnectionNotice.NoUsableUsb, h.runtime.connectionNotice.value)
        coVerify(exactly = 0) { h.driver.connect(any()) }
    }

    @Test fun verifiedMtpDescriptorShowsReplugNoticeWithoutPermissionOrProtocol() = runBlocking {
        val h = harness()
        val mtp = usbDevice(pid = 0x2372)
        every { h.hub.enumerate() } returns listOf(mtp)
        assertEquals(ConnectOutcome.NotAttached, h.runtime.connect())
        assertEquals(TetherConnectionNotice.MtpUsbMode, h.runtime.connectionNotice.value)
        assertTrue(h.runtime.message.value.contains("不能在当前连接上直接重试"))
        assertTrue(h.runtime.message.value.contains("PC(Tether)"))
        verify(exactly = 0) { h.hub.hasPermission(any()) }
        verify(exactly = 0) { h.drivers.create(any(), any()) }
        coVerify(exactly = 0) { h.driver.connect(any()) }
    }

    @Test fun unknownPidWithPtpInterfaceIsNotAssumedToBeMtp() = runBlocking {
        val h = harness()
        every { h.device.productId } returns 0x9999
        assertEquals(ConnectOutcome.Connected, h.runtime.connect())
        assertNull(h.runtime.connectionNotice.value)
        assertEquals(1, h.connectTargets.size)
    }

    @Test fun ptpOnlyOnLaterInterfaceIsRejectedBeforeOpeningInterfaceZero() = runBlocking {
        val h = harness()
        val supported = h.device.getInterface(0)
        val unrelated = mockk<UsbInterface>()
        every { unrelated.interfaceClass } returns UsbConstants.USB_CLASS_VENDOR_SPEC
        every { h.device.interfaceCount } returns 2
        every { h.device.getInterface(0) } returns unrelated
        every { h.device.getInterface(1) } returns supported

        assertEquals(ConnectOutcome.NotAttached, h.runtime.connect())
        assertEquals(TetherConnectionNotice.NoUsableUsb, h.runtime.connectionNotice.value)
        verify(exactly = 0) { h.hub.hasPermission(any()) }
        verify(exactly = 0) { h.drivers.create(any(), any()) }
        verify(exactly = 0) { h.device.getInterface(1) }
        coVerify(exactly = 0) { h.driver.connect(any()) }
    }

    @Test fun unavailableInterfaceShowsGenericNoticeNotMtp() = runBlocking {
        val h = harness()
        val unsupported = usbDevice(pid = 0x9999, usable = false)
        every { h.hub.enumerate() } returns listOf(unsupported)
        assertEquals(ConnectOutcome.NotAttached, h.runtime.connect())
        assertEquals(TetherConnectionNotice.NoUsableUsb, h.runtime.connectionNotice.value)
        verify(exactly = 0) { h.hub.hasPermission(any()) }
        verify(exactly = 0) { h.drivers.create(any(), any()) }
    }

    @Test fun validTargetAfterUnsupportedAndMtpDevicesIsSelected() = runBlocking {
        val h = harness()
        every { h.hub.enumerate() } returns listOf(
            usbDevice("/dev/bus/usb/unsupported", usable = false),
            usbDevice("/dev/bus/usb/mtp", pid = 0x2372),
            h.device,
        )
        assertEquals(ConnectOutcome.Connected, h.runtime.connect())
        assertTrue(h.connectTargets.single().device === h.device)
        assertNull(h.runtime.connectionNotice.value)
    }

    @Test fun noUsableDeviceAfterConnectionReleasesPreviousDriver() = runBlocking {
        val h = harness()
        assertEquals(ConnectOutcome.Connected, h.runtime.connect())
        every { h.hub.enumerate() } returns emptyList()
        assertEquals(ConnectOutcome.NotAttached, h.runtime.connect())
        assertFalse(h.open.get())
        assertEquals(TetherConnectionNotice.NoUsableUsb, h.runtime.connectionNotice.value)
        coVerify(exactly = 1) { h.driver.disconnect(CloseReason.RECONNECT) }
        assertEquals(1, h.connectTargets.size)
    }

    @Test fun parsedWrongModeProducesReplugNoticeWithoutAutomaticRetry() = runBlocking {
        val h = harness()
        coEvery { h.driver.connect(any()) } returns ConnectOutcome.WrongMode
        assertEquals(ConnectOutcome.WrongMode, h.runtime.connect())
        assertEquals(TetherConnectionNotice.WrongPtpMode, h.runtime.connectionNotice.value)
        h.runtime.dismissConnectionNotice()
        assertNull(h.runtime.connectionNotice.value)
        coVerify(exactly = 1) { h.driver.connect(any()) }
    }

    @Test fun ordinaryFailureDoesNotInventMtpEvidence() = runBlocking {
        val h = harness()
        coEvery { h.driver.connect(any()) } returns ConnectOutcome.Failed(PtpException("synthetic timeout"))
        assertTrue(h.runtime.connect() is ConnectOutcome.Failed)
        assertNull(h.runtime.connectionNotice.value)
    }

    @Test fun unsupportedDriverFactoryResultShowsNoUsableDeviceNotice() = runBlocking {
        val h = harness()
        every { h.drivers.create(any(), any()) } returns null
        assertEquals(ConnectOutcome.NotAttached, h.runtime.connect())
        assertEquals(TetherConnectionNotice.NoUsableUsb, h.runtime.connectionNotice.value)
        coVerify(exactly = 0) { h.driver.connect(any()) }
    }

    @Test fun manualCloseKeepsItsExistingMessage() = runBlocking {
        val h = harness()
        assertEquals(ConnectOutcome.Connected, h.runtime.connect())

        h.runtime.disconnect()

        assertClosed(h)
        assertEquals("USB 会话已关闭，原因：USER", h.runtime.message.value)
    }
}
