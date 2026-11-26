package cn.alittlecookie.lutista.tether.debug

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.os.SystemClock
import cn.alittlecookie.lutista.BuildConfig
import cn.alittlecookie.lutista.tether.protocol.PtpConstants
import cn.alittlecookie.lutista.tether.protocol.PtpContainerCodec
import cn.alittlecookie.lutista.tether.transport.PtpDeviceCaps
import cn.alittlecookie.lutista.tether.transport.PtpDiagnostics
import cn.alittlecookie.lutista.tether.transport.PtpEvent
import cn.alittlecookie.lutista.tether.transport.PtpOp
import cn.alittlecookie.lutista.tether.transport.PtpResponse
import cn.alittlecookie.lutista.tether.transport.PtpTimeoutException
import cn.alittlecookie.lutista.tether.transport.PtpTransportAdapter
import cn.alittlecookie.lutista.tether.transport.PtpUsbTransport
import cn.alittlecookie.lutista.tether.transport.PreflightFailure
import cn.alittlecookie.lutista.tether.transport.Transport
import cn.alittlecookie.lutista.tether.transport.UsbIoThread
import cn.alittlecookie.lutista.tether.transport.UsbPtpDiagnostics
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors

/**
 * Synthetic JVM unit outcomes only. Every USB object, reply, event and clock below is a test fake.
 * TargetObserved checks controller/production-transport behavior under that synthetic input; it
 * supplies no real-device evidence and cannot satisfy any hardware verification gate.
 */
class TetherDiagnosticsUnitTest {
    @Before fun resetOwnerBeforeSyntheticUnit() = UsbIoThread.resetForTest()
    @After fun resetOwnerAfterSyntheticUnit() = UsbIoThread.resetForTest()

    @Test fun syntheticUnitMasterDisabledLeavesAllActionsAndCallerConnectionUntouched() = runBlocking {
        val h = ControllerHarness()
        val transport = DrainOnlyTransport()
        val truth = SyntheticDiagnostics()
        val device = mockk<UsbDevice>()
        val connection = mockk<UsbDeviceConnection>()

        assertEquals(TetherDebugConfig.Snapshot(), h.controller.syncConfig())
        assertUnitResult(h, h.controller.smallRxStatus(), "small_rx", TetherDiagnostics.FaultStatus.Disabled)
        assertUnitResult(h, h.controller.runConcurrentFixture(transport, truth), "concurrent", TetherDiagnostics.FaultStatus.Disabled)
        assertUnitResult(h, h.controller.forceTimeout(transport, truth), "timeout", TetherDiagnostics.FaultStatus.Disabled)
        assertUnitResult(h, h.controller.runClaimFailureFixture(device, connection, truth), "claim_fail", TetherDiagnostics.FaultStatus.Disabled)
        assertTrue(h.controller.drainEvents(transport, truth).isEmpty())
        assertTrue(transport.drainTimeouts.isEmpty())
        transport.assertNoCommandsOrClose()
        assertEquals(0, truth.pendingCollections)
        assertFalse(h.logger.enabled)
        assertTrue(h.controller.snapshot().isEmpty())
        verify { device wasNot Called; connection wasNot Called }
    }

    @Test fun syntheticUnitEnabledDefaultsDisableFaultSwitchesAndBlockUnownedActions() = runBlocking {
        assumeTrue(BuildConfig.DEBUG)
        val h = ControllerHarness(TetherDebugConfig.Snapshot(enabled = true))
        val transport = DrainOnlyTransport()
        val truth = SyntheticDiagnostics(open = true)
        val device = mockk<UsbDevice>()
        val connection = mockk<UsbDeviceConnection>()

        assertEquals(TetherDebugConfig.Snapshot(enabled = true), h.controller.syncConfig())
        assertTrue(h.logger.enabled)
        assertUnitResult(h, h.controller.smallRxStatus(), "small_rx", TetherDiagnostics.FaultStatus.Disabled)
        assertUnitResult(h, h.controller.runConcurrentFixture(transport, truth), "concurrent", TetherDiagnostics.FaultStatus.Disabled)
        assertUnitResult(h, h.controller.runClaimFailureFixture(device, connection, truth), "claim_fail", TetherDiagnostics.FaultStatus.Disabled)
        val timeout = h.controller.forceTimeout(transport, truth)
        assertUnitResult(h, timeout, "timeout", TetherDiagnostics.FaultStatus.Blocked)
        assertTrue(timeout.detail.contains("requires_open_nonfatal_transport_on_owner_thread"))
        assertTrue(h.controller.drainEvents(transport, truth).isEmpty())
        assertTrue(h.controller.snapshot().any { it.contains("status=BLOCKED; reason=requires_open_transport_on_owner_thread") })
        assertTrue(transport.drainTimeouts.isEmpty())
        transport.assertNoCommandsOrClose()
        assertEquals(0, truth.pendingCollections)
        assertEquals(0, UsbIoThread.violationCount())
        verify { device wasNot Called; connection wasNot Called }
    }

    @Test fun syntheticUnitSyncWithAllSwitchesOnlyEnablesObservationAndMasterDisableClearsIt() = runBlocking {
        assumeTrue(BuildConfig.DEBUG)
        val state = TetherDebugConfig.Snapshot(true, CapabilityOverride.None, true, true, true, true)
        val h = ControllerHarness(state)
        assertEquals(state, h.controller.syncConfig())
        assertTrue(h.logger.enabled)
        assertTrue(h.controller.snapshot().isEmpty())
        h.logger.log("FAULT", "synthetic_unit_marker")
        assertEquals(1, h.controller.snapshot().size)
        h.state = TetherDebugConfig.Snapshot()
        assertEquals(TetherDebugConfig.Snapshot(), h.controller.syncConfig())
        assertFalse(h.logger.enabled)
        assertTrue(h.controller.snapshot().isEmpty())
    }

    @Test fun syntheticUnitOwnerGuardsRejectClosedOrFatalTransactionsBeforeTransportUse() = onSyntheticOwner {
        assumeTrue(BuildConfig.DEBUG)
        val h = ControllerHarness(TetherDebugConfig.Snapshot(enabled = true, faultConcurrent = true))
        val transport = DrainOnlyTransport()
        for (truth in listOf(SyntheticDiagnostics(), SyntheticDiagnostics(open = true, fatal = true))) {
            for (result in listOf(
                h.controller.runConcurrentFixture(transport, truth),
                h.controller.forceTimeout(transport, truth),
            )) {
                assertUnitResult(h, result, result.fault, TetherDiagnostics.FaultStatus.Blocked)
                assertTrue(result.detail.contains("requires_open_nonfatal_transport_on_owner_thread"))
                assertNull(result.exceptionType)
                assertNull(result.exceptionMessage)
            }
        }
        assertTrue(h.controller.drainEvents(transport, SyntheticDiagnostics()).isEmpty())
        assertTrue(transport.drainTimeouts.isEmpty())
        transport.assertNoCommandsOrClose()
        assertEquals(0, UsbIoThread.violationCount())
    }

    @Test fun syntheticUnitConcurrentEnabledStillRequiresTheBoundOwner() = runBlocking {
        assumeTrue(BuildConfig.DEBUG)
        val h = ControllerHarness(TetherDebugConfig.Snapshot(enabled = true, faultConcurrent = true))
        val transport = DrainOnlyTransport()
        val result = h.controller.runConcurrentFixture(transport, SyntheticDiagnostics(open = true))
        transport.assertNoCommandsOrClose()
        assertUnitResult(h, result, "concurrent", TetherDiagnostics.FaultStatus.Blocked)
        assertTrue(result.detail.contains("requires_open_nonfatal_transport_on_owner_thread"))
        assertEquals(0, UsbIoThread.violationCount())
    }

    @Test fun syntheticUnitPassiveDefaultDrainRunsThreeSerializedTwoMillisecondRounds() = onSyntheticOwner {
        assumeTrue(BuildConfig.DEBUG)
        val h = ControllerHarness(TetherDebugConfig.Snapshot(enabled = true))
        val first = syntheticEvent(0xD123, 0xFFFF_FFFFL)
        val second = syntheticEvent(0x4006, 7L)
        val transport = DrainOnlyTransport(listOf(listOf(first), emptyList(), listOf(second)))
        val truth = SyntheticDiagnostics(open = true, discarded = 9)

        val result = h.controller.drainEvents(transport, truth)

        assertEquals(listOf(first, second), result)
        assertSame(first, result[0])
        assertSame(second.raw, result[1].raw)
        assertEquals(listOf(2, 2, 2), transport.drainTimeouts)
        transport.assertNoCommandsOrClose()
        assertEquals(listOf(Thread.currentThread(), Thread.currentThread(), Thread.currentThread()), transport.drainThreads)
        assertEquals(0, truth.pendingCollections)
        val lines = h.controller.snapshot()
        val drains = lines.filter { it.contains(" DRAIN ") }
        assertEquals(3, drains.size)
        listOf(1, 0, 1).forEachIndexed { index, count ->
            assertTrue(drains[index].contains("round=${index + 1}; count=$count; discardedEventContainers=9; isOpen=true; isFatal=false"))
        }
        assertTrue(lines.any { it.contains("code=0xD123; params=[0xFFFFFFFF]; rawBytes=16; rawKind=normalized_container; source=controlled_drain") })
        assertTrue(lines.any { it.contains("code=0x4006; params=[0x7]") })
        assertSyntheticUnitLogs(lines)
        assertEquals(0, UsbIoThread.violationCount())
    }

    @Test fun syntheticUnitPassiveDrainClampsRequestedRoundsToOneThroughThree() = onSyntheticOwner {
        assumeTrue(BuildConfig.DEBUG)
        for ((requested, expected) in listOf(-5 to 1, 0 to 1, 1 to 1, 2 to 2, 3 to 3, 4 to 3, Int.MAX_VALUE to 3)) {
            val h = ControllerHarness(TetherDebugConfig.Snapshot(enabled = true))
            val transport = DrainOnlyTransport()
            assertTrue(h.controller.drainEvents(transport, SyntheticDiagnostics(open = true), requested).isEmpty())
            assertEquals("synthetic unit rounds=$requested", List(expected) { 2 }, transport.drainTimeouts)
            transport.assertNoCommandsOrClose()
            assertEquals(expected, h.controller.snapshot().count { it.contains(" DRAIN ") })
            assertSyntheticUnitLogs(h.controller.snapshot())
        }
    }

    @Test fun syntheticUnitSmallReceiveStatusReportsPublicApiLimitWithoutFabricatingARejection() = onSyntheticOwner {
        assumeTrue(BuildConfig.DEBUG)
        val h = ControllerHarness(TetherDebugConfig.Snapshot(enabled = true, faultSmallRx = true))
        val usb = QueuedSyntheticUsb(h.logger, rxCapacity = 1)
        try {
            usb.openWithSyntheticPreflightRejection()
            val writesBefore = usb.writes.size
            val readsBefore = usb.bulkReads.size
            val result = h.controller.smallRxStatus()
            assertUnitResult(h, result, "small_rx", TetherDiagnostics.FaultStatus.Blocked)
            assertTrue(result.detail.contains("reserveTail() compacts/doubles capacity"))
            assertTrue(result.detail.contains("small rxCapacity does not reach request<maxPacketSize"))
            assertTrue(result.detail.contains("no reflection/hidden API used"))
            assertNull(result.exceptionType)
            assertNull(result.exceptionMessage)
            assertEquals(writesBefore, usb.writes.size)
            assertEquals(readsBefore, usb.bulkReads.size)

            usb.queueDataAndOk(2L)
            val response = usb.transport.transaction(PtpOp(PtpConstants.Op.GET_DEVICE_INFO), expectedData = true)
            assertEquals(3L, response.dataBytes)
            assertTrue(usb.bulkReads.all { it.length >= 512 && it.length % 512 == 0 })
            assertFalse(usb.truth.isFatal)
            assertFalse(h.controller.snapshot().any { it.contains(" FILL_REJECTED ") })
            assertSyntheticUnitLogs(h.controller.snapshot())
        } finally { usb.transport.close() }
    }

    @Test fun syntheticUnitQueuedProductionReentryHitsBusyGuardOnOwnerWithoutSecondUsbCommand() = onSyntheticOwner {
        assumeTrue(BuildConfig.DEBUG)
        val h = ControllerHarness(TetherDebugConfig.Snapshot(enabled = true, faultConcurrent = true))
        val usb = QueuedSyntheticUsb(h.logger)
        try {
            usb.openWithSyntheticPreflightRejection()
            usb.clearObservedIo()
            usb.queueDataAndOk(2L)
            val violationsBefore = UsbIoThread.violationCount()

            val result = h.controller.runConcurrentFixture(PtpTransportAdapter(usb.transport, usb.truth), usb.truth)

            assertUnitResult(h, result, "concurrent", TetherDiagnostics.FaultStatus.TargetObserved)
            assertEquals(IllegalStateException::class.java.simpleName, result.exceptionType)
            assertEquals(TetherDiagnostics.BUSY_MESSAGE, result.exceptionMessage)
            assertTrue(result.detail.contains("reentered=true; outerException=null"))
            assertTrue(result.detail.contains("evidenceScope=controlled_reentry"))
            assertEquals(violationsBefore, UsbIoThread.violationCount())
            assertEquals(1, usb.writes.size)
            assertArrayEquals(PtpContainerCodec.encodeCommand(PtpConstants.Op.GET_DEVICE_INFO, 2L), usb.writes.single())
            assertEquals(2, usb.bulkReads.size)
            assertTrue(usb.ioThreads.all { it === Thread.currentThread() })
            assertTrue(usb.truth.isOpen)
            assertFalse(usb.truth.isFatal)

            // A new synthetic transaction proves the rejected nested entry did not leave busy set.
            usb.queueDataAndOk(3L)
            assertEquals(PtpConstants.Rc.OK, usb.transport.transaction(PtpOp(PtpConstants.Op.GET_DEVICE_INFO), expectedData = true).code)
            assertEquals(2, usb.writes.size)
        } finally { usb.transport.close() }
    }

    @Test fun syntheticUnitQueuedResponseWithoutDataCannotFabricateAConcurrentTarget() = onSyntheticOwner {
        assumeTrue(BuildConfig.DEBUG)
        val h = ControllerHarness(TetherDebugConfig.Snapshot(enabled = true, faultConcurrent = true))
        val usb = QueuedSyntheticUsb(h.logger)
        try {
            usb.openWithSyntheticPreflightRejection()
            usb.clearObservedIo()
            usb.bulkReplies += PtpContainerCodec.encodeResponse(PtpConstants.Rc.OK, 2L)
            val result = h.controller.runConcurrentFixture(usb.transport, usb.truth)
            assertUnitResult(h, result, "concurrent", TetherDiagnostics.FaultStatus.Blocked)
            assertTrue(result.detail.contains("reentered=false"))
            assertNull(result.exceptionType)
            assertEquals(1, usb.writes.size)
            assertFalse(usb.truth.isFatal)
        } finally { usb.transport.close() }
    }

    @Test fun syntheticUnitQueuedTimeoutUsesOneMillisecondAndProductionFatalFollowupDoesNoIo() = onSyntheticOwner {
        assumeTrue(BuildConfig.DEBUG)
        val h = ControllerHarness(TetherDebugConfig.Snapshot(enabled = true))
        val usb = QueuedSyntheticUsb(h.logger)
        try {
            usb.openWithSyntheticPreflightRejection()
            usb.clearObservedIo()
            val result = h.controller.forceTimeout(usb.transport, usb.truth)
            assertUnitResult(h, result, "timeout", TetherDiagnostics.FaultStatus.TargetObserved)
            assertEquals(PtpTimeoutException::class.java.simpleName, result.exceptionType)
            assertTrue(result.exceptionMessage.orEmpty().contains("bulk IN"))
            assertTrue(result.detail.contains("isOpen=true; isFatal=true"))
            assertTrue(result.detail.contains("followupException=PtpException; followupMessage=传输层已置 fatal（上一次超时/NACK），必须拔插相机后重建连接"))
            assertTrue(result.detail.contains("evidenceScope=read_only_1ms_and_fatal_fail_fast"))
            assertTrue(usb.truth.isFatal)
            assertEquals(1, usb.writes.size)
            assertEquals(1, usb.bulkReads.size)
            assertEquals(1, usb.bulkReads.single().timeoutMs)
            assertTrue(usb.ioThreads.all { it === Thread.currentThread() })
            assertEquals(0, UsbIoThread.violationCount())

            val blocked = h.controller.forceTimeout(usb.transport, usb.truth)
            assertUnitResult(h, blocked, "timeout", TetherDiagnostics.FaultStatus.Blocked)
            assertEquals(1, usb.writes.size)
            assertEquals(1, usb.bulkReads.size)
        } finally { usb.transport.close() }
    }

    @Test fun syntheticUnitSuccessfulOneMillisecondReplyRemainsBlockedForTimeoutTarget() = onSyntheticOwner {
        assumeTrue(BuildConfig.DEBUG)
        val h = ControllerHarness(TetherDebugConfig.Snapshot(enabled = true))
        val usb = QueuedSyntheticUsb(h.logger)
        try {
            usb.openWithSyntheticPreflightRejection()
            usb.clearObservedIo()
            usb.queueDataAndOk(2L)
            val result = h.controller.forceTimeout(PtpTransportAdapter(usb.transport, usb.truth), usb.truth)
            assertUnitResult(h, result, "timeout", TetherDiagnostics.FaultStatus.Blocked)
            assertNull(result.exceptionType)
            assertNull(result.exceptionMessage)
            assertTrue(result.detail.contains("followupException=null"))
            assertFalse(usb.truth.isFatal)
            assertEquals(1, usb.writes.size)
            assertTrue(usb.bulkReads.all { it.timeoutMs == 1 })
        } finally { usb.transport.close() }
    }

    @Test fun syntheticUnitClaimFixtureGuardsLeaveConnectionCallerOwned() = runBlocking {
        assumeTrue(BuildConfig.DEBUG)
        val h = ControllerHarness(TetherDebugConfig.Snapshot(enabled = true, faultClaimFail = true))
        val device = mockk<UsbDevice>()
        val connection = mockk<UsbDeviceConnection>()
        val unowned = h.controller.runClaimFailureFixture(device, connection, SyntheticDiagnostics())
        assertUnitResult(h, unowned, "claim_fail", TetherDiagnostics.FaultStatus.Blocked)
        assertTrue(unowned.detail.contains("requires_disconnected_session_and_owner_thread; connectionUntouched=true"))
        onSyntheticOwner {
            val active = h.controller.runClaimFailureFixture(device, connection, SyntheticDiagnostics(open = true))
            assertUnitResult(h, active, "claim_fail", TetherDiagnostics.FaultStatus.Blocked)
            assertTrue(active.detail.contains("connectionUntouched=true"))
        }
        verify { device wasNot Called; connection wasNot Called }
    }

    @Test fun syntheticUnitClaimFalseObservesExactProductionFailureAndClosesRawHandleOnly() = onSyntheticOwner {
        assumeTrue(BuildConfig.DEBUG)
        val h = ControllerHarness(TetherDebugConfig.Snapshot(enabled = true, faultClaimFail = true))
        val usb = QueuedSyntheticUsb(h.logger)
        every { usb.connection.claimInterface(usb.intf, true) } returns false
        val result = h.controller.runClaimFailureFixture(usb.device, usb.connection, SyntheticDiagnostics())
        assertUnitResult(h, result, "claim_fail", TetherDiagnostics.FaultStatus.TargetObserved)
        assertEquals("PtpException", result.exceptionType)
        assertEquals(PtpDiagTransport.CLAIM_FAILED_MESSAGE, result.exceptionMessage)
        assertTrue(result.detail.contains("opened=false; actualClaimFailure=true; isOpen=false; isFatal=false"))
        assertTrue(result.detail.contains("releaseResult=not_observed"))
        assertTrue(usb.writes.isEmpty())
        assertTrue(usb.bulkReads.isEmpty())
        verify(exactly = 1) { usb.connection.claimInterface(usb.intf, true) }
        verify(exactly = 1) { usb.connection.close() }
        verify(exactly = 0) { usb.connection.releaseInterface(any()) }
    }

    @Test fun syntheticUnitSuccessfulClaimIsBlockedForFailureTargetAndNeverBecomesHardwareEvidence() = onSyntheticOwner {
        assumeTrue(BuildConfig.DEBUG)
        val h = ControllerHarness(TetherDebugConfig.Snapshot(enabled = true, faultClaimFail = true))
        val usb = QueuedSyntheticUsb(h.logger)
        // The controller creates its own transport, so this scoped fake supplies its Android clock.
        mockkStatic(SystemClock::class)
        try {
            every { SystemClock.elapsedRealtime() } returns 0L
            usb.bulkReplies += PtpContainerCodec.encodeResponse(0x2002, 1L)
            val result = h.controller.runClaimFailureFixture(usb.device, usb.connection, SyntheticDiagnostics())
            assertUnitResult(h, result, "claim_fail", TetherDiagnostics.FaultStatus.Blocked)
            assertTrue(result.detail.contains("opened=true; actualClaimFailure=false; isOpen=false; isFatal=false"))
            assertTrue(result.detail.contains("releaseResult=not_observed"))
            assertNull(result.exceptionType)
            assertNull(result.exceptionMessage)
            assertEquals(1, usb.writes.size)
            verify(exactly = 1) { usb.connection.claimInterface(usb.intf, true) }
            verify(exactly = 1) { usb.connection.releaseInterface(usb.intf) }
            verify(exactly = 2) { usb.connection.close() }
        } finally { unmockkStatic(SystemClock::class) }
    }

    private class ControllerHarness(var state: TetherDebugConfig.Snapshot = TetherDebugConfig.Snapshot()) {
        private val config = mockk<TetherDebugConfig>()
        val logger = TetherDiagLogger(false, { 0L }, { _, _ -> })
        val controller = TetherDiagnostics(config, logger)
        init { coEvery { config.snapshot() } answers { state } }
    }

    private class SyntheticDiagnostics(
        private val open: Boolean = false,
        private val fatal: Boolean = false,
        private val discarded: Int = 0,
    ) : PtpDiagnostics {
        var pendingCollections = 0
        override val isOpen get() = open
        override val isFatal get() = fatal
        override val deviceCaps: PtpDeviceCaps? get() = error("synthetic unit action must not query capabilities")
        override val discardedEventContainers get() = discarded
        override fun collectPendingEvents(): List<PtpEvent> {
            pendingCollections += 1
            error("synthetic unit controller must not consume pending-event facade")
        }
    }

    /** Counts forbidden calls as well: controller actions can catch their thrown errors. */
    private class DrainOnlyTransport(private val rounds: List<List<PtpEvent>> = emptyList()) : Transport {
        val drainTimeouts = mutableListOf<Int>()
        val drainThreads = mutableListOf<Thread>()
        private var transactionAttempts = 0
        private var closeAttempts = 0
        override suspend fun transaction(op: PtpOp, expectedData: Boolean, dataOut: ByteArray?, dataSink: ((ByteArray, Int) -> Unit)?, timeoutMs: Int): PtpResponse {
            transactionAttempts += 1
            throw AssertionError("synthetic unit passive/guarded action issued a transaction: $op")
        }
        override suspend fun drainEvents(timeoutMs: Int): List<PtpEvent> {
            check(UsbIoThread.isIoThread()) { "synthetic drain left owner thread" }
            drainTimeouts += timeoutMs
            drainThreads += Thread.currentThread()
            return rounds.getOrElse(drainTimeouts.lastIndex) { emptyList() }
        }
        override suspend fun close(timeoutMs: Int) {
            closeAttempts += 1
            throw AssertionError("synthetic unit action closed caller transport")
        }
        fun assertNoCommandsOrClose() {
            assertEquals("synthetic unit action issued no commands", 0, transactionAttempts)
            assertEquals("synthetic unit action left caller transport open", 0, closeAttempts)
        }
    }

    private data class SyntheticRead(val length: Int, val timeoutMs: Int)

    /** Queued synthetic bytes drive production parsing, busy and fatal logic; no device is opened. */
    private class QueuedSyntheticUsb(logger: TetherDiagLogger, rxCapacity: Int = 128 * 1024) {
        val device = mockk<UsbDevice>()
        val connection = mockk<UsbDeviceConnection>()
        val intf = mockk<UsbInterface>()
        val bulkReplies = ArrayDeque<ByteArray>()
        val writes = mutableListOf<ByteArray>()
        val bulkReads = mutableListOf<SyntheticRead>()
        val ioThreads = mutableListOf<Thread>()
        val transport = PtpUsbTransport(rxCapacity = rxCapacity, now = { 0L }, diag = logger)
        val truth = UsbPtpDiagnostics(transport)

        init {
            val inEp = endpoint(0x81, 2, 512)
            val outEp = endpoint(0x01, 2, 512)
            val intrEp = endpoint(0x82, 3, 64)
            every { intf.endpointCount } returns 3
            every { intf.getEndpoint(0) } returns inEp
            every { intf.getEndpoint(1) } returns outEp
            every { intf.getEndpoint(2) } returns intrEp
            every { device.interfaceCount } returns 1
            every { device.getInterface(0) } returns intf
            every { device.vendorId } returns 0x1234
            every { device.productId } returns 0x5678
            every { connection.claimInterface(intf, true) } returns true
            every { connection.releaseInterface(intf) } returns true
            every { connection.close() } just runs
            every { connection.rawDescriptors } returns null
            every { connection.bulkTransfer(outEp, any(), any(), any(), any()) } answers {
                check(UsbIoThread.isIoThread()) { "synthetic bulk transfer left owner thread" }
                ioThreads += Thread.currentThread()
                val bytes = args[1] as ByteArray
                val offset = args[2] as Int
                val length = args[3] as Int
                writes += bytes.copyOfRange(offset, offset + length)
                length
            }
            every { connection.bulkTransfer(any(), any(), any(), any()) } answers {
                check(UsbIoThread.isIoThread()) { "synthetic bulk transfer left owner thread" }
                ioThreads += Thread.currentThread()
                val ep = args[0] as UsbEndpoint
                val bytes = args[1] as ByteArray
                val length = args[2] as Int
                val timeout = args[3] as Int
                if (ep.address == 0x82) -1 else {
                    check(ep.address == 0x81)
                    bulkReads += SyntheticRead(length, timeout)
                    val reply = bulkReplies.removeFirstOrNull()
                    if (reply == null) -1 else {
                        val count = minOf(reply.size, length)
                        reply.copyInto(bytes, 0, 0, count)
                        if (count < reply.size) bulkReplies.addFirst(reply.copyOfRange(count, reply.size))
                        count
                    }
                }
            }
        }

        suspend fun openWithSyntheticPreflightRejection() {
            // Deliberately synthetic non-OK response avoids borrowing a real-device dump.
            bulkReplies += PtpContainerCodec.encodeResponse(0x2002, 1L)
            val caps = transport.open(device, connection)
            assertEquals(PreflightFailure.REJECTED, caps.preflightFailure)
            assertEquals("preflight issued one real command", 1, writes.size)
            assertEquals("preflight consumed the synthetic response", 1, bulkReads.size)
            assertTrue("no unread preflight response can leak into the action", bulkReplies.isEmpty())
            assertTrue(truth.isOpen)
            assertFalse(truth.isFatal)
        }

        fun queueDataAndOk(txid: Long) {
            bulkReplies += PtpContainerCodec.encodeData(PtpConstants.Op.GET_DEVICE_INFO, txid, byteArrayOf(1, 2, 3))
            bulkReplies += PtpContainerCodec.encodeResponse(PtpConstants.Rc.OK, txid)
        }

        fun clearObservedIo() {
            writes.clear()
            bulkReads.clear()
            ioThreads.clear()
        }

        private fun endpoint(address: Int, type: Int, packet: Int): UsbEndpoint = mockk<UsbEndpoint>().also { ep ->
            every { ep.address } returns address
            every { ep.type } returns type
            every { ep.direction } returns (address and 0x80)
            every { ep.maxPacketSize } returns packet
        }
    }

    private fun syntheticEvent(code: Int, param: Long) =
        PtpEvent(code, longArrayOf(param), PtpContainerCodec.encodeEvent(code, longArrayOf(param)))

    private fun assertUnitResult(h: ControllerHarness, result: TetherDiagnostics.FaultResult, fault: String, status: TetherDiagnostics.FaultStatus) {
        assertEquals("synthetic JVM unit fault; no hardware gate evidence", fault, result.fault)
        assertEquals("synthetic JVM unit status; no hardware gate evidence", status, result.status)
        assertSyntheticUnitLogs(h.controller.snapshot())
    }

    private fun assertSyntheticUnitLogs(lines: List<String>) {
        assertFalse("synthetic unit fixtures must never emit hardware success verdicts", lines.any { Regex("(?i)\\bPASS\\b").containsMatchIn(it) })
    }

    private fun <T> onSyntheticOwner(block: suspend () -> T): T =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, UsbIoThread.THREAD_NAME) }
            .asCoroutineDispatcher().use { dispatcher ->
                runBlocking(dispatcher) {
                    UsbIoThread.bind()
                    try { block() } finally { UsbIoThread.unbind() }
                }
            }
}
