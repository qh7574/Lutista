package cn.alittlecookie.lutista.tether.driver

import cn.alittlecookie.lutista.tether.transport.PreflightFailure
import cn.alittlecookie.lutista.tether.transport.PtpDeviceCaps
import cn.alittlecookie.lutista.tether.transport.PtpDiagnostics
import cn.alittlecookie.lutista.tether.transport.PtpEvent
import cn.alittlecookie.lutista.tether.transport.PtpUsbTransport
import cn.alittlecookie.lutista.tether.transport.UsbPtpDiagnostics
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic JVM contract tests; these assertions provide no physical-camera evidence. */
class PtpTransportAdapterTest {
    @Test fun diagnosticsReadsCurrentOpenFatalAndCounterWithoutOtherTransportCalls() {
        val delegate = mockk<PtpUsbTransport>()
        var open = false
        var fatal = true
        var discarded = 7
        every { delegate.isOpen } answers { open }
        every { delegate.isFatal } answers { fatal }
        every { delegate.discardedEventContainers } answers { discarded }
        val diagnostics: PtpDiagnostics = UsbPtpDiagnostics(delegate)

        assertFalse(diagnostics.isOpen)
        assertTrue(diagnostics.isFatal)
        assertEquals(7, diagnostics.discardedEventContainers)
        open = true
        fatal = false
        discarded = 23
        assertTrue(diagnostics.isOpen)
        assertFalse(diagnostics.isFatal)
        assertEquals(23, diagnostics.discardedEventContainers)
        verify(exactly = 2) { delegate.isOpen }
        verify(exactly = 2) { delegate.isFatal }
        verify(exactly = 2) { delegate.discardedEventContainers }
        confirmVerified(delegate)
    }

    @Test fun diagnosticsReturnsOriginalCapsIncludingEveryPreflightFailureAndNullTransitions() {
        val delegate = mockk<PtpUsbTransport>()
        var current: PtpDeviceCaps? = null
        every { delegate.deviceCaps } answers { current }
        val diagnostics: PtpDiagnostics = UsbPtpDiagnostics(delegate)
        assertNull(diagnostics.deviceCaps)
        val failures = listOf<PreflightFailure?>(null) + PreflightFailure.entries
        for (failure in failures) {
            val original = syntheticCaps(failure)
            current = original
            val actual = diagnostics.deviceCaps
            assertSame(original, actual)
            assertSame(failure, actual!!.preflightFailure)
        }
        current = null
        assertNull(diagnostics.deviceCaps)
        verify(exactly = failures.size + 2) { delegate.deviceCaps }
        confirmVerified(delegate)
    }

    @Test fun diagnosticsCollectsOncePerCallAndReturnsOriginalPendingEventLists() {
        val delegate = mockk<PtpUsbTransport>()
        val event = PtpEvent(0xC108, longArrayOf(0xFFFF_FFFFL), byteArrayOf(9, 8, 7))
        val pending = mutableListOf(event)
        val drained = mutableListOf<PtpEvent>()
        every { delegate.collectPendingEvents() } returnsMany listOf(pending, drained)
        val diagnostics: PtpDiagnostics = UsbPtpDiagnostics(delegate)

        val first = diagnostics.collectPendingEvents()
        assertSame(pending, first)
        assertSame(event, first.single())
        assertSame(drained, diagnostics.collectPendingEvents())
        verify(exactly = 2) { delegate.collectPendingEvents() }
        confirmVerified(delegate)
    }

    @Test fun diagnosticsKeepsOriginalCollectionThrowable() = runBlocking<Unit> {
        val delegate = mockk<PtpUsbTransport>()
        val failure = IllegalStateException("synthetic wrong I/O thread")
        every { delegate.collectPendingEvents() } throws failure
        val diagnostics: PtpDiagnostics = UsbPtpDiagnostics(delegate)

        assertSameFailure(failure) { diagnostics.collectPendingEvents() }
        verify(exactly = 1) { delegate.collectPendingEvents() }
        confirmVerified(delegate)
    }

    private suspend fun assertSameFailure(expected: Throwable, operation: suspend () -> Unit) {
        val actual = try {
            operation()
            null
        } catch (failure: Throwable) {
            failure
        }
        assertSame("The original throwable must propagate unchanged", expected, actual)
    }

    private fun syntheticCaps(failure: PreflightFailure?) = PtpDeviceCaps(
        vendorExtensionId = 0x1CL,
        manufacturer = "synthetic maker",
        model = "synthetic adapter contract",
        deviceVersion = "synthetic firmware",
        serialNumber = "synthetic serial",
        operationsSupported = setOf(0x1001L, 0x1007L),
        eventsSupported = setOf(0xC108L),
        devicePropertiesSupported = setOf(0x5001L),
        imageFormats = setOf(0x3801L),
        bcdUsb = 0x0210,
        bulkMaxPacketSize = 512,
        usbVendorId = 0x1234,
        usbProductId = 0x5678,
        preflightFailure = failure,
    )
}
