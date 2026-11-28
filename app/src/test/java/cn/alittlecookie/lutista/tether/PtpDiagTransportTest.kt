package cn.alittlecookie.lutista.tether.debug

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import cn.alittlecookie.lutista.BuildConfig
import cn.alittlecookie.lutista.tether.transport.*
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class PtpDiagTransportTest {
    @Test fun everyArgumentReturnAndCallbackReferenceIsForwardedUnchanged() = runBlocking {
        val delegate = RecordingTransport()
        val diagnostics = ReadOnlyDiagnostics()
        val wrapper = wrapper(delegate, diagnostics, false)
        val device = mockk<UsbDevice>()
        val connection = mockk<UsbDeviceConnection>()
        assertSame(delegate.caps, wrapper.open(device, connection))
        assertSame(device, delegate.device)
        assertSame(connection, delegate.connection)
        val op = PtpOp(0x1001, longArrayOf(0xFFFF_FFFFL))
        val dataOut = byteArrayOf(8, 9, 10)
        val callback: (ByteArray, Int) -> Unit = { bytes, count ->
            assertSame(delegate.chunk, bytes)
            assertEquals(2, count)
        }
        assertSame(delegate.response, wrapper.transaction(op, true, dataOut, callback, 678))
        assertSame(op, delegate.op)
        assertTrue(delegate.expectedData)
        assertSame(dataOut, delegate.dataOut)
        assertSame(callback, delegate.dataSink)
        assertEquals(678, delegate.transactionTimeout)
        assertSame(delegate.events, wrapper.drainEvents(17))
        assertEquals(17, delegate.drainTimeout)
        wrapper.close(987)
        assertEquals(987, delegate.closeTimeout)
        assertEquals(listOf("open", "transaction", "drain", "close"), delegate.calls)
        assertEquals(0, diagnostics.pendingCollections)
    }

    @Test fun recoveryStateReportedByTheTransportSurvivesTheDiagnosticWrapper() {
        val delegate = RecordingTransport().also {
            it.openRecoveryMs = 1_234L
            it.cameraSilent = true
        }
        val wrapper = wrapper(delegate, ReadOnlyDiagnostics(), false)
        // 真机回归：包装层漏转发会让会话层读到默认 0/false，导致预算不退、文案指向拔数据线。
        assertEquals(1_234L, wrapper.openRecoveryMs)
        assertTrue(wrapper.cameraSilent)
        delegate.openRecoveryMs = 0L
        delegate.cameraSilent = false
        assertEquals(0L, wrapper.openRecoveryMs)
        assertFalse(wrapper.cameraSilent)
    }

    @Test fun allPhasesPreserveOriginalExceptionIncludingCancellationAndErrors() = runBlocking {
        for (phase in listOf("open", "transaction", "drain", "close")) {
            for (failure in listOf(PtpException("synthetic original"), CancellationException("original cancellation"), AssertionError("original error"))) {
                val delegate = RecordingTransport().also { it.failingPhase = phase; it.failure = failure }
                val wrapper = wrapper(delegate, ReadOnlyDiagnostics(), true)
                val device = mockk<UsbDevice>()
                every { device.interfaceCount } returns 0
                val thrown = try {
                    when (phase) {
                        "open" -> wrapper.open(device, mockk())
                        "transaction" -> wrapper.transaction(PtpOp(0x1001), timeoutMs = 37)
                        "drain" -> wrapper.drainEvents(1)
                        else -> wrapper.close(99)
                    }
                    null
                } catch (actual: Throwable) { actual }
                assertSame("phase=$phase", failure, thrown)
                assertEquals(listOf(phase), delegate.calls)
            }
        }
    }

    @Test fun openReturnedCapsArePreflightTruthEvenAfterSuccessfulPreflightTransaction() = runBlocking {
        assumeTrue(BuildConfig.DEBUG)
        val logger = logger(true)
        logger.onTransaction(0x1001, 1, 0x2001, 3, 1, false, true, null, null)
        val delegate = RecordingTransport()
        val diagnostics = ReadOnlyDiagnostics().also { it.caps = delegate.caps.copy(preflightFailure = null, model = "stale facade") }
        val wrapper = PtpDiagTransport(delegate, diagnostics, logger, { 5L })
        val device = mockk<UsbDevice>()
        every { device.interfaceCount } returns 0
        assertSame(delegate.caps, wrapper.open(device, mockk()))
        val capsLine = logger.snapshot().single { it.contains(" CAPS ") }
        assertTrue(capsLine.contains("model=Mock Camera"))
        assertTrue(capsLine.contains("preflightFailure=PARSE_ERROR; source=open_return"))
        assertFalse(capsLine.contains("stale facade"))
        assertEquals(0, diagnostics.capsReads)
        assertEquals(0, diagnostics.pendingCollections)
    }

    @Test fun endpointAndEventLogsUseObservedFieldsWithoutConsumingFacadeEvents() = runBlocking {
        assumeTrue(BuildConfig.DEBUG)
        val logger = logger(true)
        val delegate = RecordingTransport()
        val diagnostics = ReadOnlyDiagnostics()
        val wrapper = PtpDiagTransport(delegate, diagnostics, logger, { 42L })
        val endpoint = mockk<UsbEndpoint>()
        every { endpoint.address } returns 0x83
        every { endpoint.direction } returns 0x80
        every { endpoint.type } returns 2
        every { endpoint.maxPacketSize } returns 1000
        val intf = mockk<UsbInterface>()
        every { intf.endpointCount } returns 1
        every { intf.getEndpoint(0) } returns endpoint
        val device = mockk<UsbDevice>()
        every { device.interfaceCount } returns 1
        every { device.getInterface(0) } returns intf
        wrapper.open(device, mockk())
        val events = wrapper.drainEvents(2)
        assertSame(delegate.events, events)
        assertSame(delegate.events[0].raw, events[0].raw)
        val lines = logger.snapshot()
        assertTrue(lines.any { it.contains("address=0x83; type=2; direction=0x80; maxPacketSize=1000") })
        assertTrue(lines.any { it.contains("code=0xD123; params=[0xFFFFFFFF]; kind=unknown; rawBytes=3; rawKind=normalized_container") })
        assertTrue(lines.any { it.contains("code=0x4006;") && it.contains("kind=noise") })
        assertTrue(lines.any { it.contains("discardedEventContainers=72; unknownEventCount=1; noiseEventCount=1; counterScope=decorated_drain") })
        assertEquals(0, diagnostics.pendingCollections)
        assertEquals(0, diagnostics.capsReads)
    }

    @Test fun disabledObservationReadsNeitherClockDescriptorsNorDiagnosticFacade() = runBlocking {
        var clockReads = 0
        val delegate = RecordingTransport()
        val diagnostics = ReadOnlyDiagnostics().also { it.throwOnRead = true }
        val wrapper = PtpDiagTransport(delegate, diagnostics, logger(false), { clockReads++; error("disabled clock") })
        val device = mockk<UsbDevice>()
        assertSame(delegate.caps, wrapper.open(device, mockk()))
        assertSame(delegate.response, wrapper.transaction(PtpOp(0x1001)))
        assertSame(delegate.events, wrapper.drainEvents())
        wrapper.close()
        assertEquals(0, clockReads)
        assertEquals(0, diagnostics.reads)
        verify(exactly = 0) { device.interfaceCount }
    }

    @Test fun brokenObserversCannotReplaceDelegateResultsOrExceptions() = runBlocking {
        val delegate = RecordingTransport()
        val diagnostics = ReadOnlyDiagnostics().also { it.throwOnRead = true }
        val badLogger = TetherDiagLogger(true, { throw AssertionError("observer clock") }, { _, _ -> throw AssertionError("observer output") })
        val wrapper = PtpDiagTransport(delegate, diagnostics, badLogger, { throw AssertionError("timing observer") })
        val device = mockk<UsbDevice>()
        every { device.interfaceCount } throws AssertionError("descriptor observer")
        assertSame(delegate.caps, wrapper.open(device, mockk()))
        assertSame(delegate.response, wrapper.transaction(PtpOp(0x1001)))
        assertSame(delegate.events, wrapper.drainEvents())
        delegate.failingPhase = "transaction"
        val original = PtpTimeoutException("synthetic original ep=0x81 request=65536 timeout=1")
        delegate.failure = original
        val actual = try { wrapper.transaction(PtpOp(0x1001)); null } catch (error: Throwable) { error }
        assertSame(original, actual)
        wrapper.close()
    }

    private fun logger(enabled: Boolean) = TetherDiagLogger(enabled, { 1L }, { _, _ -> })
    private fun wrapper(delegate: PtpTransport, diagnostics: PtpDiagnostics, enabled: Boolean) =
        PtpDiagTransport(delegate, diagnostics, logger(enabled), { 42L })

    private class ReadOnlyDiagnostics : PtpDiagnostics {
        var reads = 0
        var capsReads = 0
        var pendingCollections = 0
        var throwOnRead = false
        var caps: PtpDeviceCaps? = null
        private fun observe() { reads++; if (throwOnRead) throw AssertionError("facade observer") }
        override val isOpen: Boolean get() { observe(); return true }
        override val isFatal: Boolean get() { observe(); return false }
        override val deviceCaps: PtpDeviceCaps? get() { capsReads++; return caps }
        override val discardedEventContainers: Int get() { observe(); return 72 }
        override fun collectPendingEvents(): List<PtpEvent> { pendingCollections++; error("decorator must not consume pending events") }
    }

    private class RecordingTransport : PtpTransport {
        val calls = mutableListOf<String>()
        val caps = TetherDiagLoggerTest.syntheticCaps()
        val response = PtpResponse(0x2001, longArrayOf(0xFFFF_FFFFL), 2L)
        val chunk = byteArrayOf(11, 12, 99)
        val events = listOf(PtpEvent(0xD123, longArrayOf(0xFFFF_FFFFL), byteArrayOf(1, 2, 3)), PtpEvent(0x4006, longArrayOf(5), byteArrayOf(4)))
        var device: UsbDevice? = null
        var connection: UsbDeviceConnection? = null
        var op: PtpOp? = null
        var expectedData = false
        var dataOut: ByteArray? = null
        var dataSink: ((ByteArray, Int) -> Unit)? = null
        var transactionTimeout = 0
        var drainTimeout = 0
        var closeTimeout = 0
        override var openRecoveryMs: Long = 0L
        override var cameraSilent: Boolean = false
        var failingPhase: String? = null
        var failure: Throwable = PtpException("synthetic")
        private fun called(phase: String) { calls += phase; if (phase == failingPhase) throw failure }
        override suspend fun open(device: UsbDevice, connection: UsbDeviceConnection): PtpDeviceCaps {
            called("open"); this.device = device; this.connection = connection; return caps
        }
        override suspend fun close(timeoutMs: Int) { called("close"); closeTimeout = timeoutMs }
        override suspend fun transaction(op: PtpOp, expectedData: Boolean, dataOut: ByteArray?, dataSink: ((ByteArray, Int) -> Unit)?, timeoutMs: Int): PtpResponse {
            called("transaction"); this.op = op; this.expectedData = expectedData; this.dataOut = dataOut
            this.dataSink = dataSink; transactionTimeout = timeoutMs; dataSink?.invoke(chunk, 2); return response
        }
        override suspend fun drainEvents(timeoutMs: Int): List<PtpEvent> { called("drain"); drainTimeout = timeoutMs; return events }
    }
}
