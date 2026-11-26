package cn.alittlecookie.lutista.tether.session

import android.hardware.usb.UsbDevice
import cn.alittlecookie.lutista.tether.Fixtures
import cn.alittlecookie.lutista.tether.debug.TetherDiagLogger
import cn.alittlecookie.lutista.tether.debug.TetherDiagnostics
import cn.alittlecookie.lutista.tether.driver.CameraTarget
import cn.alittlecookie.lutista.tether.driver.CloseReason
import cn.alittlecookie.lutista.tether.driver.ConnectOutcome
import cn.alittlecookie.lutista.tether.driver.UsbTarget
import cn.alittlecookie.lutista.tether.probe.ProbeBudgetExceededException
import cn.alittlecookie.lutista.tether.protocol.DeviceInfoParser
import cn.alittlecookie.lutista.tether.transport.PreflightFailure
import cn.alittlecookie.lutista.tether.transport.PtpDeviceCaps
import cn.alittlecookie.lutista.tether.transport.PtpDiagnostics
import cn.alittlecookie.lutista.tether.transport.PtpEvent
import cn.alittlecookie.lutista.tether.transport.PtpException
import cn.alittlecookie.lutista.tether.transport.PtpOp
import cn.alittlecookie.lutista.tether.transport.PtpResponse
import cn.alittlecookie.lutista.tether.transport.PtpTimeoutException
import cn.alittlecookie.lutista.tether.transport.Transport
import cn.alittlecookie.lutista.tether.transport.TransportFactory
import cn.alittlecookie.lutista.tether.transport.UsbIoThread
import io.mockk.every
import io.mockk.mockk
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Real session and scheduler; only USB transport, clock and Android thread creation are replaced. */
class CameraSessionTest {
    private data class Call(
        val op: PtpOp, val expectedData: Boolean, val dataOut: ByteArray?,
        val dataSink: ((ByteArray, Int) -> Unit)?, val timeoutMs: Int,
    )
    private data class IoObservation(val operation: String, val thread: Thread, val bound: Boolean)

    private class ExecutorWorker : PtpScheduler.IoWorker {
        override val dispatcher: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor {
            Thread(it, UsbIoThread.THREAD_NAME)
        }.asCoroutineDispatcher()
        override fun shutdown() = dispatcher.close()
    }

    /** Delays only the session's poll/probe/cleanup coroutines, never the I/O worker. */
    private class ManualDispatcher : CoroutineDispatcher() {
        private val pending = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.add(block) }
        fun runAll() {
            var count = 0
            while (true) {
                val next = pending.poll() ?: return
                check(++count < 1_000) { "Unexpected runnable loop" }
                next.run()
            }
        }
    }

    private class FakeTransport(
        override val deviceCaps: PtpDeviceCaps,
        var warmPayload: ByteArray,
    ) : Transport, PtpDiagnostics {
        @Volatile override var isOpen = true
        @Volatile override var isFatal = false
        @Volatile override var discardedEventContainers = 0
        val calls = CopyOnWriteArrayList<Call>()
        val observations = CopyOnWriteArrayList<IoObservation>()
        val trace = CopyOnWriteArrayList<String>()
        val openCodes = ArrayDeque<Int>()
        val drains = ConcurrentLinkedQueue<List<PtpEvent>>()
        val pendingEvents = ConcurrentLinkedQueue<List<PtpEvent>>()
        val closed = CompletableDeferred<Unit>()
        val closeCount = AtomicInteger()
        var storagePayload = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(2).putInt(0x0001_0001).putInt(0x8000_0001.toInt()).array()
        var onTransaction: suspend (Call) -> PtpResponse? = { null }
        var onDrain: suspend () -> Unit = {}
        var onClose: suspend () -> Unit = {}

        fun observe(operation: String) {
            val observation = IoObservation(operation, Thread.currentThread(), UsbIoThread.isIoThread())
            observations.add(observation)
            trace.add(operation)
            assertTrue(operation+" must use the bound worker", observation.bound)
            assertEquals(UsbIoThread.THREAD_NAME, observation.thread.name.substringBefore(" @coroutine#"))
        }
        override suspend fun transaction(
            op: PtpOp, expectedData: Boolean, dataOut: ByteArray?,
            dataSink: ((ByteArray, Int) -> Unit)?, timeoutMs: Int,
        ): PtpResponse {
            observe("tx:"+op.code.toString(16))
            check(isOpen) { "Fake connection was closed" }
            check(!isFatal) { "Fake transport is already fatal" }
            assertTrue("Every real transaction needs a finite positive timeout", timeoutMs > 0)
            val call = Call(op, expectedData, dataOut, dataSink, timeoutMs)
            calls.add(call)
            onTransaction(call)?.let { return it }
            if (op.code == 0x1002) openCodes.pollFirst()?.let { return PtpResponse(it) }
            val payload = when (op.code) { 0x1001 -> warmPayload; 0x1004 -> storagePayload; else -> byteArrayOf() }
            if (payload.isNotEmpty()) dataSink?.invoke(payload, payload.size)
            return PtpResponse(0x2001, dataBytes = payload.size.toLong())
        }
        override suspend fun drainEvents(timeoutMs: Int): List<PtpEvent> {
            observe("drain")
            assertTrue(timeoutMs in 1..2)
            onDrain()
            return drains.poll() ?: emptyList()
        }
        override fun collectPendingEvents(): List<PtpEvent> {
            observe("pending")
            return pendingEvents.poll() ?: emptyList()
        }
        override suspend fun close(timeoutMs: Int) {
            observe("close")
            assertTrue(timeoutMs in 1..1_000)
            try { onClose() }
            finally { isOpen = false; closeCount.incrementAndGet(); closed.complete(Unit) }
        }
    }

    private class FakeFactory(links: List<FakeTransport>) : TransportFactory {
        private val available = ConcurrentLinkedQueue<Transport>(links)
        val targets = CopyOnWriteArrayList<CameraTarget>()
        val observations = CopyOnWriteArrayList<IoObservation>()
        val createCount = AtomicInteger()
        var supported = true
        var onCreate: suspend (CameraTarget) -> Transport? = { available.poll() }
        override fun supports(target: CameraTarget): Boolean = supported
        override suspend fun create(target: CameraTarget): Transport? {
            val observation = IoObservation("create", Thread.currentThread(), UsbIoThread.isIoThread())
            observations.add(observation)
            assertTrue("Factory open must be on bound ptp-io", observation.bound)
            assertEquals(UsbIoThread.THREAD_NAME, observation.thread.name.substringBefore(" @coroutine#"))
            targets.add(target)
            createCount.incrementAndGet()
            return onCreate(target).also { if (it is FakeTransport) it.observe("create") }
        }
    }

    private data class Harness(
        val session: CameraSession, val scheduler: PtpScheduler, val scope: CoroutineScope,
        val factory: FakeFactory, val dispatcher: ManualDispatcher, val clock: AtomicLong,
        val uncaught: CopyOnWriteArrayList<Throwable>,
    )
    private val harnesses = mutableListOf<Harness>()
    private val gates = mutableListOf<CompletableDeferred<Unit>>()
    private fun gate() = CompletableDeferred<Unit>().also { gates.add(it) }
    private fun link(caps: PtpDeviceCaps = preflightCaps(), payload: ByteArray = Fixtures.hex("device_info")) =
        FakeTransport(caps, payload)
    private fun preflightCaps() = DeviceInfoParser.parse(Fixtures.hex("device_info")).copy(
        bcdUsb = 0x310, bulkMaxPacketSize = 1_024, usbVendorId = 0x04DA, usbProductId = 0x2382,
    )
    private fun target(permission: Boolean = true, name: String = "/dev/bus/usb/001/002"): UsbTarget {
        val device = mockk<UsbDevice>()
        every { device.deviceName } returns name
        every { device.vendorId } returns 0x04DA
        every { device.productId } returns 0x2382
        return UsbTarget(device, permission)
    }
    private fun harness(
        vararg links: FakeTransport,
        permission: suspend (UsbTarget) -> Boolean = { it.permissionGranted },
        discover: suspend () -> UsbTarget? = { null },
        eventDispatcher: CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
    ): Harness {
        val factory = FakeFactory(links.toList())
        val scheduler = PtpScheduler { ExecutorWorker() }
        val dispatcher = ManualDispatcher()
        val clock = AtomicLong(100)
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val scope = CoroutineScope(SupervisorJob()+dispatcher+CoroutineExceptionHandler { _, failure -> uncaught.add(failure) })
        val logger = TetherDiagLogger(false, { clock.get() }, { _, _ -> })
        val session = CameraSession(factory, scheduler, logger, scope, discover, permission, { clock.get() }, eventDispatcher)
        return Harness(session, scheduler, scope, factory, dispatcher, clock, uncaught).also { harnesses.add(it) }
    }
    private fun scenario(block: suspend CoroutineScope.() -> Unit) = runBlocking {
        try { withTimeout(5_000) { block() } }
        finally { gates.forEach { it.complete(Unit) } }
    }
    private fun event(handle: Long) = PtpEvent(0xC108, longArrayOf(handle), byteArrayOf())
    private fun opcodes(link: FakeTransport) = link.calls.map { it.op.code }
    private fun assertAllIo(h: Harness, link: FakeTransport) {
        val owner = h.factory.observations.first().thread
        (h.factory.observations+link.observations).forEach { assertSame(it.operation, owner, it.thread); assertTrue(it.bound) }
    }
    private suspend inline fun <reified T : Throwable> failure(noinline block: suspend () -> Unit): T {
        val caught = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected "+T::class.java.simpleName+", got "+caught, caught is T)
        return caught as T
    }

    // Coroutine stacktrace recovery may copy an awaited failure with the original as its direct cause.
    private fun assertAwaitedFailure(expected: Throwable, actual: Throwable?) {
        assertTrue(
            "Expected the original failure or its coroutine recovery copy, got "+actual,
            actual === expected || actual?.cause === expected,
        )
    }

    @Before fun before() { UsbIoThread.resetForTest() }
    @After fun after() = runBlocking {
        gates.forEach { it.complete(Unit) }
        harnesses.asReversed().forEach {
            it.scope.cancel()
            runCatching { it.session.close() }
            runCatching { it.scheduler.close() }
            assertTrue("Uncaught session task failures: "+it.uncaught, it.uncaught.isEmpty())
        }
        assertFalse(UsbIoThread.isBound())
        assertEquals(0, UsbIoThread.violationCount())
        UsbIoThread.resetForTest()
    }

    @Test fun `connect orders OpenSession DeviceInfo three drains StorageIDs on one bound worker`() = scenario {
        val link = link()
        val h = harness(link)
        assertEquals(ConnectOutcome.Connected, h.session.connect(target()))
        assertEquals(listOf("create", "tx:1002", "tx:1001", "drain", "pending", "drain", "pending", "drain", "pending", "tx:1004"), link.trace.take(10))
        assertEquals(listOf(0x1002, 0x1001, 0x1004), opcodes(link))
        assertEquals(listOf(0x0001_0001L, 0x8000_0001L), h.session.storageIds.value)
        h.session.runOp { it.getObjectHandles() }
        assertEquals(0x1007, link.calls.last().op.code)
        h.session.close()
        assertEquals(1, link.closeCount.get())
        assertAllIo(h, link)
        assertFalse(UsbIoThread.isBound())
    }

    @Test fun `permission and connect requests deduplicate while waiting and when already connected`() = scenario {
        val link = link()
        val permissionEntered = gate()
        val permissionRelease = gate()
        val requests = AtomicInteger()
        val h = harness(link, permission = {
            requests.incrementAndGet(); permissionEntered.complete(Unit); permissionRelease.await(); true
        })
        val requested = target(permission = false)
        val first = async(start = CoroutineStart.UNDISPATCHED) { h.session.connect(requested) }
        permissionEntered.await()
        repeat(8) { assertEquals(ConnectOutcome.AlreadyConnecting, h.session.connect(requested)) }
        assertEquals(1, requests.get())
        assertEquals(0, h.factory.createCount.get())
        permissionRelease.complete(Unit)
        assertEquals(ConnectOutcome.Connected, first.await())
        assertEquals(ConnectOutcome.Connected, h.session.connect(requested))
        assertEquals(1, requests.get())
        assertEquals(1, h.factory.createCount.get())
        assertTrue((h.factory.targets.single() as UsbTarget).permissionGranted)
    }

    @Test fun `discovery miss and denied permission issue no USB open`() = scenario {
        val h = harness(permission = { false })
        assertEquals(ConnectOutcome.NotAttached, h.session.connect())
        assertEquals(ConnectOutcome.PermissionDenied, h.session.connect(target(permission = false)))
        assertEquals(TetherPhase.NotAttached, h.session.state.value.phase)
        assertEquals(0, h.factory.createCount.get())
        assertFalse(UsbIoThread.isBound())
    }

    @Test fun `201E recovers once by orderly CloseSession then OpenSession`() = scenario {
        val link = link()
        link.openCodes.addAll(listOf(0x201E, 0x2001))
        val h = harness(link)
        assertEquals(ConnectOutcome.Connected, h.session.connect(target()))
        assertEquals(listOf(0x1002, 0x1003, 0x1002, 0x1001, 0x1004), opcodes(link))
        assertEquals(1, h.factory.createCount.get())
    }

    @Test fun `a second 201E fails instead of retrying again`() = scenario {
        val link = link()
        link.openCodes.addAll(listOf(0x201E, 0x201E))
        val h = harness(link)
        val result = h.session.connect(target())
        assertTrue(result is ConnectOutcome.Failed)
        assertTrue((result as ConnectOutcome.Failed).cause.message!!.contains("201e"))
        assertEquals(listOf(0x1002, 0x1003, 0x1002), opcodes(link).take(3))
        assertEquals(2, opcodes(link).count { it == 0x1002 })
        assertFalse(opcodes(link).contains(0x1001))
        assertEquals(1, link.closeCount.get())
    }

    @Test fun `preflight failure disallows 201E retry`() = scenario {
        val link = link(preflightCaps().copy(preflightFailure = PreflightFailure.TIMEOUT))
        link.openCodes.add(0x201E)
        val h = harness(link)
        assertTrue(h.session.connect(target()) is ConnectOutcome.Failed)
        assertEquals(1, opcodes(link).count { it == 0x1002 })
        assertFalse(opcodes(link).contains(0x1001))
        assertEquals(1, link.closeCount.get())
    }

    @Test fun `fatal transport never retries 201E or sends protocol CloseSession`() = scenario {
        val link = link()
        link.onTransaction = { call -> if (call.op.code == 0x1002) { link.isFatal = true; PtpResponse(0x201E) } else null }
        val h = harness(link)
        assertTrue(h.session.connect(target()) is ConnectOutcome.Failed)
        assertEquals(listOf(0x1002), opcodes(link))
        assertEquals(1, link.closeCount.get())
    }

    @Test fun `WrongMode depends on warm vendor extension even when L1 was Panasonic`() = scenario {
        val payload = Fixtures.hex("device_info").copyOf()
        ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).putInt(2, 6)
        val link = link(payload = payload)
        val h = harness(link)
        assertEquals(ConnectOutcome.WrongMode, h.session.connect(target()))
        assertEquals(TetherPhase.WrongMode, h.session.state.value.phase)
        assertTrue(h.session.state.value.message.contains("PC(Tether)"))
        assertFalse(opcodes(link).contains(0x1004))
        assertEquals(1, link.closeCount.get())
    }

    @Test fun `timeout and rejected response never masquerade as WrongMode`() = scenario {
        val rejected = link()
        rejected.openCodes.add(0x2019)
        val a = harness(rejected)
        assertTrue(a.session.connect(target()) is ConnectOutcome.Failed)
        assertEquals(TetherPhase.Fatal, a.session.state.value.phase)
        val timedOut = link()
        val original = PtpTimeoutException("warm-up bulk timeout")
        timedOut.onTransaction = { call -> if (call.op.code == 0x1001) { timedOut.isFatal = true; throw original }; null }
        val b = harness(timedOut)
        val result = b.session.connect(target()) as ConnectOutcome.Failed
        assertAwaitedFailure(original, result.cause)
        assertEquals(TetherPhase.Fatal, b.session.state.value.phase)
        assertEquals(listOf(0x1002, 0x1001), opcodes(timedOut))
    }

    @Test fun `Connected precedes blocked capability work and L1 caps remain authoritative`() = scenario {
        val l1 = preflightCaps().copy(model = "L1-only", preflightFailure = PreflightFailure.TIMEOUT)
        val link = link(l1)
        val h = harness(link)
        link.onTransaction = { h.clock.addAndGet(200); null }
        val probeEntered = gate()
        val probeRelease = gate()
        val probeFinished = gate()
        var probeInfo: PtpDeviceCaps? = null
        h.session.setCapabilityWork { current, caps, force ->
            assertEquals(TetherPhase.Connected, current.state.value.phase)
            assertFalse(force)
            probeInfo = caps
            probeEntered.complete(Unit)
            probeRelease.await()
            probeFinished.complete(Unit)
        }
        assertEquals(ConnectOutcome.Connected, h.session.connect(target()))
        assertEquals(600L, h.session.state.value.connectionMs)
        assertTrue(h.session.state.value.connectionMs!! <= 5_000)
        assertSame(l1, h.session.deviceCaps.value)
        assertEquals("DC-S1", h.session.state.value.model)
        assertFalse(probeEntered.isCompleted)
        h.dispatcher.runAll()
        probeEntered.await()
        assertEquals("DC-S1", probeInfo!!.model)
        assertFalse(probeFinished.isCompleted)
        assertEquals(TetherPhase.Connected, h.session.state.value.phase)
        probeRelease.complete(Unit)
        h.dispatcher.runAll()
        probeFinished.await()
    }

    @Test fun `factory exceeding connect budget is released before any PTP command`() = scenario {
        val link = link()
        val h = harness(link)
        h.factory.onCreate = { h.clock.addAndGet(5_001); link }
        val result = h.session.connect(target())
        assertTrue(result is ConnectOutcome.Failed)
        assertTrue((result as ConnectOutcome.Failed).cause.message!!.contains("5 second budget"))
        assertTrue(link.calls.isEmpty())
        assertEquals(1, link.closeCount.get())
        assertFalse(UsbIoThread.isBound())
    }

    @Test fun `connect failure retains primary exception when physical cleanup also throws`() = scenario {
        val link = link()
        val primary = PtpTimeoutException("first OpenSession failure")
        val cleanup = PtpException("release failed")
        link.onTransaction = { link.isFatal = true; throw primary }
        link.onClose = { throw cleanup }
        val h = harness(link)
        val result = h.session.connect(target()) as ConnectOutcome.Failed
        assertAwaitedFailure(primary, result.cause)
        assertTrue(result.cause.suppressed.any { it === cleanup || it.cause === cleanup })
        assertEquals(1, link.closeCount.get())
        assertFalse(h.session.isOpen)
        assertFalse(UsbIoThread.isBound())
    }

    @Test fun `active queued and new operations receive the same original fatal`() = scenario {
        val link = link()
        val h = harness(link)
        assertEquals(ConnectOutcome.Connected, h.session.connect(target()))
        val entered = gate()
        val release = gate()
        val original = PtpTimeoutException("real first property timeout")
        link.onTransaction = { call ->
            if (call.op.code == 0x9402) { entered.complete(Unit); release.await(); link.isFatal = true; throw original }
            null
        }
        val active = async(start = CoroutineStart.UNDISPATCHED) { runCatching { h.session.runOp { it.getProperty(1) } } }
        entered.await()
        val queued = async(start = CoroutineStart.UNDISPATCHED) { runCatching { h.session.runOp { it.getProperty(2) } } }
        assertEquals(2, h.scheduler.pendingUserOps())
        release.complete(Unit)
        assertAwaitedFailure(original, active.await().exceptionOrNull())
        assertAwaitedFailure(original, queued.await().exceptionOrNull())
        assertSame(original, failure<PtpTimeoutException> { h.session.runOp { it.getProperty(3) } })
        assertEquals(TetherPhase.Fatal, h.session.state.value.phase)
        assertEquals(1, opcodes(link).count { it == 0x9402 })
        h.session.close()
        assertFalse(opcodes(link).contains(0x1003))
        assertEquals(1, link.closeCount.get())
    }

    @Test fun `cancelled active caller cannot hide the later real USB fatal from queued work`() = scenario {
        val link = link()
        val h = harness(link)
        h.session.connect(target())
        val entered = gate()
        val release = gate()
        val original = PtpTimeoutException("timeout after caller cancellation")
        link.onTransaction = { call ->
            if (call.op.code == 0x9402) { entered.complete(Unit); release.await(); link.isFatal = true; throw original }
            null
        }
        val caller = async(start = CoroutineStart.UNDISPATCHED) { h.session.runOp { it.getProperty(1) } }
        entered.await()
        val queued = async(start = CoroutineStart.UNDISPATCHED) { runCatching { h.session.runOp { it.getProperty(2) } } }
        caller.cancelAndJoin()
        assertFalse(link.isFatal)
        release.complete(Unit)
        assertAwaitedFailure(original, queued.await().exceptionOrNull())
        assertEquals(TetherPhase.Fatal, h.session.state.value.phase)
        assertSame(original, failure<PtpTimeoutException> { h.session.runOp { it.getProperty(3) } })
        assertEquals(1, opcodes(link).count { it == 0x9402 })
    }

    @Test fun `cancel during factory releases late connection without sending OpenSession`() = scenario {
        val link = link()
        val h = harness(link)
        val entered = gate()
        val release = gate()
        h.factory.onCreate = { entered.complete(Unit); release.await(); link }
        val connecting = async(start = CoroutineStart.UNDISPATCHED) { h.session.connect(target()) }
        entered.await()
        connecting.cancel()
        release.complete(Unit)
        connecting.join()
        assertTrue(connecting.isCancelled)
        assertTrue(link.calls.isEmpty())
        assertEquals(1, link.closeCount.get())
        assertFalse(h.session.isOpen)
        assertFalse(UsbIoThread.isBound())
    }

    @Test fun `public close during factory waits for physical release and stops admission`() = scenario {
        val link = link()
        val h = harness(link)
        val entered = gate()
        val release = gate()
        h.factory.onCreate = { entered.complete(Unit); release.await(); link }
        val connecting = async(start = CoroutineStart.UNDISPATCHED) { h.session.connect(target()) }
        entered.await()
        val closing = async(start = CoroutineStart.UNDISPATCHED) { h.session.close() }
        assertFalse(closing.isCompleted)
        assertEquals(0, link.closeCount.get())
        release.complete(Unit)
        connecting.join()
        closing.await()
        assertTrue(link.calls.isEmpty())
        assertEquals(1, link.closeCount.get())
        assertEquals(TetherPhase.Idle, h.session.state.value.phase)
        assertAllIo(h, link)
    }

    @Test fun `old delayed fatal cleanup cannot close a newly reconnected generation`() = scenario {
        val first = link()
        val second = link()
        val h = harness(first, second)
        val requested = target()
        h.session.connect(requested)
        val original = PtpTimeoutException("old link fatal")
        first.onTransaction = { first.isFatal = true; throw original }
        assertAwaitedFailure(original, failure<PtpTimeoutException> { h.session.runOp { it.getProperty(1) } })
        assertEquals(TetherPhase.Fatal, h.session.state.value.phase)
        // The manual dispatcher still holds the old generation's asynchronous cleanup.
        assertEquals(ConnectOutcome.Connected, h.session.connect(requested))
        assertEquals(1, first.closeCount.get())
        h.dispatcher.runAll()
        h.session.runOp { it.getProperty(2) }
        assertEquals(0, second.closeCount.get())
        assertTrue(h.session.isOpen)
        assertFalse(h.session.isFatal)
        assertEquals(TetherPhase.Connected, h.session.state.value.phase)
    }

    @Test fun `CloseSession timeout still releases the physical handle and unbinds once`() = scenario {
        val link = link()
        val h = harness(link)
        h.session.connect(target())
        val closeFailure = PtpTimeoutException("CloseSession timeout")
        link.onTransaction = { call -> if (call.op.code == 0x1003) { link.isFatal = true; throw closeFailure }; null }
        h.session.close()
        h.session.close()
        assertEquals(1, link.closeCount.get())
        assertEquals(1, opcodes(link).count { it == 0x1003 })
        assertEquals(TetherPhase.Idle, h.session.state.value.phase)
        assertFalse(UsbIoThread.isBound())
        assertAllIo(h, link)
    }

    @Test fun `close drains without publishing to a paused object collector`() = scenario {
        val link = link()
        val h = harness(link)
        h.session.connect(target())
        val entered = gate()
        val release = gate()
        val received = AtomicInteger()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            h.session.events.collect { received.incrementAndGet(); entered.complete(Unit); release.await() }
        }
        h.session.eventPump.accept(listOf(event(1)))
        entered.await()
        link.drains.add((2L..80L).map { event(it) })
        val closing = async(start = CoroutineStart.UNDISPATCHED) { h.session.close() }
        try {
            withTimeout(1_500) { closing.await() }
            assertEquals(1, link.closeCount.get())
            assertEquals(1, received.get())
            assertFalse(UsbIoThread.isBound())
        } finally { release.complete(Unit); collector.cancelAndJoin(); closing.join() }
    }

    @Test fun `close cancels a full-buffer event delivery inside the connect request`() = scenario {
        val link = link()
        val h = harness(link)
        val entered = gate()
        val release = gate()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            h.session.events.collect { entered.complete(Unit); release.await() }
        }
        link.drains.add((1L..80L).map { event(it) })
        val connecting = async(start = CoroutineStart.UNDISPATCHED) { runCatching { h.session.connect(target()) } }
        entered.await()
        h.session.eventPump.stats.first { it.backpressure > 0 }
        val closing = async(start = CoroutineStart.UNDISPATCHED) { h.session.close() }
        try {
            withTimeout(1_500) { closing.await() }
            assertEquals(1, link.closeCount.get())
            assertFalse(h.session.isOpen)
            assertFalse(UsbIoThread.isBound())
        } finally { release.complete(Unit); collector.cancelAndJoin(); connecting.join(); closing.join() }
    }

    @Test fun `full handshake Flow terminates within the fixed five second budget without public close`() = scenario {
        val link = link()
        val h = harness(link)
        val entered = gate()
        val release = gate()
        val received = AtomicInteger()
        val drains = AtomicInteger()
        // Use 4.8s of the fixed budget before delivery; only the remaining 200ms
        // should be spent awaiting a full Flow, independently of collector progress.
        link.onDrain = { if (drains.getAndIncrement() == 0) { h.clock.addAndGet(4_800) }; Unit }
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            h.session.events.collect { received.incrementAndGet(); entered.complete(Unit); release.await() }
        }
        link.drains.add((1L..80L).map { event(it) })
        val startedNs = System.nanoTime()
        val connecting = async(start = CoroutineStart.UNDISPATCHED) { h.session.connect(target()) }
        try {
            entered.await()
            h.session.eventPump.stats.first { it.backpressure > 0 }
            val outcome = withTimeout(1_500) { connecting.await() }
            val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000
            assertTrue(outcome is ConnectOutcome.Failed)
            assertEquals("Connection exceeded the fixed 5 second budget", (outcome as ConnectOutcome.Failed).cause.message)
            assertTrue("Full Flow exceeded the 5s connection ceiling: "+elapsedMs, elapsedMs < CameraSession.CONNECT_BUDGET_MS)
            assertEquals(TetherPhase.Fatal, h.session.state.value.phase)
            assertFalse(release.isCompleted)
            assertEquals(1, received.get())
            assertEquals(1, link.closeCount.get())
            assertFalse(opcodes(link).contains(0x1004))
            assertFalse(h.session.isOpen)
            assertFalse(UsbIoThread.isBound())
            assertAllIo(h, link)
        } finally { release.complete(Unit); collector.cancelAndJoin(); connecting.cancelAndJoin() }
    }

    @Test fun `probe whitelist and format guard run before USB while specific storage fallback stays allowed`() = scenario {
        val link = link()
        val h = harness(link)
        h.session.connect(target())
        val before = link.calls.size
        for (code in listOf(0x1009, 0x9403, 0x9404)) {
            failure<IllegalArgumentException> { h.session.readDiagnostic(PtpOp(code), 100) }
        }
        failure<IllegalArgumentException> {
            h.session.readDiagnostic(PtpOp(0x1007, longArrayOf(0xFFFF_FFFFL, 0x3801, 0xFFFF_FFFFL)), 100)
        }
        assertEquals(before, link.calls.size)
        val fallback = PtpOp(0x1007, longArrayOf(0x0001_0001, 0, 0xFFFF_FFFFL))
        h.session.readDiagnostic(fallback, 2_000)
        assertSame(fallback, link.calls.last().op)
        assertTrue(link.calls.last().expectedData)
        assertEquals(1_500, link.calls.last().timeoutMs)
        assertEquals(TetherPhase.Connected, h.session.state.value.phase)
    }

    @Test fun `probe queue wait consumes budget and cannot issue a late USB transaction`() = scenario {
        val link = link()
        val h = harness(link)
        h.session.connect(target())
        val entered = gate()
        val release = gate()
        val active = async(start = CoroutineStart.UNDISPATCHED) {
            h.session.runOp { entered.complete(Unit); release.await() }
        }
        entered.await()
        val probe = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { h.session.readDiagnostic(PtpOp(0x9402, longArrayOf(1)), 100) }
        }
        assertEquals(2, h.scheduler.pendingUserOps())
        h.clock.addAndGet(101)
        release.complete(Unit)
        active.await()
        assertTrue(probe.await().exceptionOrNull() is ProbeBudgetExceededException)
        assertFalse(opcodes(link).contains(0x9402))
        assertFalse(h.session.isFatal)
        assertEquals(TetherPhase.Connected, h.session.state.value.phase)
    }

    @Test fun `probe queued for 300ms expires before the active USB request ends and issues no USB`() = scenario {
        val link = link()
        val h = harness(link)
        h.session.connect(target())
        val entered = gate()
        val release = gate()
        val active = async(start = CoroutineStart.UNDISPATCHED) {
            h.session.runOp { entered.complete(Unit); release.await() }
        }
        entered.await()
        val before = link.calls.size
        val startedNs = System.nanoTime()
        val probe = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { h.session.readDiagnostic(PtpOp(0x9402, longArrayOf(1)), 300) }
        }
        try {
            assertEquals(2, h.scheduler.pendingUserOps())
            val error = withTimeout(1_500) { probe.await() }.exceptionOrNull()
            val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000
            assertTrue(error is ProbeBudgetExceededException)
            assertTrue("Probe expired before its 300ms budget: "+elapsedMs, elapsedMs >= 250)
            assertFalse(active.isCompleted)
            assertFalse(release.isCompleted)
            assertEquals(1, h.scheduler.pendingUserOps())
            assertEquals(before, link.calls.size)
            assertFalse(opcodes(link).contains(0x9402))
            assertFalse(h.session.isFatal)
            assertEquals(TetherPhase.Connected, h.session.state.value.phase)
        } finally { release.complete(Unit) }
        active.await()
        h.session.runOp { it.getDeviceInfo() }
        assertFalse(opcodes(link).contains(0x9402))
        assertAllIo(h, link)
    }

    @Test fun `diagnostic lambda receives actual transport and diagnostics on owner worker`() = scenario {
        val link = link()
        val h = harness(link)
        h.session.connect(target())
        val response = h.session.runDiagnostic { transport, diagnostics ->
            assertSame(link, transport)
            assertSame(link, diagnostics)
            assertTrue(UsbIoThread.isIoThread())
            transport.transaction(PtpOp(0x9402, longArrayOf(1)), timeoutMs = 147)
        }
        assertEquals(0x2001, response.code)
        assertEquals(147, link.calls.last().timeoutMs)
        assertAllIo(h, link)
    }

    @Test fun `cancelled diagnostic caller cannot hide a swallowed raw fatal behind FaultResult`() = scenario {
        val link = link()
        val h = harness(link)
        h.session.connect(target())
        val entered = gate()
        val release = gate()
        val swallowed = CompletableDeferred<Throwable>()
        val returned = CompletableDeferred<TetherDiagnostics.FaultResult>()
        val queuedRuns = AtomicInteger()
        val original = PtpTimeoutException("swallowed raw diagnostic timeout")
        val drainsBefore = link.trace.count { it == "drain" }
        link.onTransaction = { call ->
            if (call.op.code == 0x1001) {
                entered.complete(Unit)
                release.await()
                link.isFatal = true
                throw original
            }
            null
        }
        val caller = async(start = CoroutineStart.UNDISPATCHED) {
            h.session.runDiagnostic { transport, diagnostics ->
                val observed = checkNotNull(runCatching {
                    transport.transaction(PtpOp(0x1001), expectedData = true, timeoutMs = 1)
                }.exceptionOrNull())
                swallowed.complete(observed)
                val result = TetherDiagnostics.FaultResult(
                    "timeout", TetherDiagnostics.FaultStatus.TargetObserved,
                    "controlled_experiment=true; isFatal="+diagnostics.isFatal,
                    observed.javaClass.simpleName, observed.message,
                )
                returned.complete(result)
                result
            }
        }
        entered.await()
        // Raw scheduler work must be aborted without observing transport fatal by
        // itself; a session.runOp here could latch fatal and conceal the defect.
        val queued = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { h.scheduler.run { queuedRuns.incrementAndGet(); Unit } }
        }
        try {
            caller.cancelAndJoin()
            assertTrue(caller.isCancelled)
            assertFalse(link.isFatal)
            assertFalse(returned.isCompleted)
            assertEquals(2, h.scheduler.pendingUserOps())
            release.complete(Unit)
            val result = withTimeout(1_500) { returned.await() }
            assertSame(original, swallowed.await())
            assertEquals(TetherDiagnostics.FaultStatus.TargetObserved, result.status)
            assertEquals(original.javaClass.simpleName, result.exceptionType)
            assertEquals(original.message, result.exceptionMessage)
            // No extra session API call may cause the missing worker-side latch.
            withTimeout(1_500) { h.session.state.first { it.phase == TetherPhase.Fatal } }
            val queuedFailure = checkNotNull(queued.await().exceptionOrNull())
            assertTrue(queuedFailure is PtpException)
            assertEquals(0, queuedRuns.get())
            assertTrue(h.session.isFatal)
            assertEquals(drainsBefore, link.trace.count { it == "drain" })
            // Session admission exposes the exact latched original before any suspension.
            val firstFatal = failure<PtpException> {
                h.session.runOp<Unit> { error("request executed after fatal") }
            }
            assertAwaitedFailure(firstFatal, queuedFailure)
            h.dispatcher.runAll()
            // Physical close must happen automatically before this test joins
            // the already observed close for deterministic worker termination.
            withTimeout(1_500) { link.closed.await() }
            assertEquals(1, link.closeCount.get())
            h.scheduler.close()
            h.dispatcher.runAll()
            assertAwaitedFailure(firstFatal, failure<PtpException> {
                h.scheduler.run<Unit> { error("request executed after automatic close") }
            })
            assertEquals(TetherPhase.Fatal, h.session.state.value.phase)
            assertTrue(h.session.isFatal)
            assertFalse(h.session.isOpen)
            assertEquals(0, queuedRuns.get())
            assertEquals(drainsBefore, link.trace.count { it == "drain" })
            assertFalse(opcodes(link).contains(0x1003))
            assertFalse(UsbIoThread.isBound())
            assertAllIo(h, link)
        } finally { release.complete(Unit); caller.cancelAndJoin(); queued.join() }
    }

    @Test fun `cancelled close waiter still releases USB after notification cleanup resumes`() = scenario {
        val deliveries = ManualDispatcher()
        val link = link()
        val h = harness(link, eventDispatcher = deliveries)
        h.session.connect(target())
        link.drains.add(listOf(event(1)))
        h.session.drainDiagnosticEvents()
        val closer = launch(start = CoroutineStart.UNDISPATCHED) { h.session.close() }
        try {
            assertFalse(closer.isCompleted)
            assertTrue(link.isOpen)
            closer.cancel()
            kotlinx.coroutines.yield()
            assertFalse("Resource cleanup must survive cancellation of the close waiter", closer.isCompleted)
            assertTrue(link.isOpen)
            deliveries.runAll()
            withTimeout(1_500) { closer.join() }
            assertFalse(link.isOpen)
            assertEquals(1, link.closeCount.get())
            assertFalse(UsbIoThread.isBound())
            assertAllIo(h, link)
        } finally { deliveries.runAll(); closer.join() }
    }

    @Test fun `caller cancelled after fatal flag but before worker throw cannot replace original USB cause`() = scenario {
        for (diagnostic in listOf(false, true)) {
            val link = link()
            val h = harness(link)
            h.session.connect(target())
            val fatalFlagSet = gate()
            val releaseThrow = gate()
            val original = PtpTimeoutException("fatal flag set before original worker throw")
            link.onTransaction = { call ->
                if (call.op.code == 0x9402) {
                    link.isFatal = true
                    fatalFlagSet.complete(Unit)
                    releaseThrow.await()
                    throw original
                }
                null
            }
            val caller = async(start = CoroutineStart.UNDISPATCHED) {
                if (diagnostic) h.session.runDiagnostic { transport, _ -> transport.transaction(PtpOp(0x9402)) }
                else h.session.runOp { it.getProperty(1).response }
            }
            fatalFlagSet.await()
            val queued = async(start = CoroutineStart.UNDISPATCHED) { runCatching { h.scheduler.run { error("USB work admitted after fatal") } } }
            caller.cancelAndJoin()
            releaseThrow.complete(Unit)
            assertAwaitedFailure(original, queued.await().exceptionOrNull())
            assertSame(original, failure<PtpTimeoutException> { h.session.runOp { it.getProperty(2) } })
            assertEquals(1, opcodes(link).count { it == 0x9402 })
            h.session.close()
        }
    }

    @Test fun `paused event collector keeps user USB queue moving and later receives complete batch`() = scenario {
        val link = link()
        val h = harness(link)
        h.session.connect(target())
        val entered = gate()
        val release = gate()
        val handles = CopyOnWriteArrayList<Long>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            h.session.events.collect { added ->
                handles.add(added.handle)
                if (handles.size == 1) { entered.complete(Unit); release.await() }
            }
        }
        link.drains.add((1L..80L).map(::event))
        try {
            withTimeout(1_500) { h.session.drainDiagnosticEvents() }
            entered.await()
            h.session.eventPump.stats.first { it.backpressure > 0 }
            withTimeout(1_500) { h.session.runOp { it.getProperty(1) } }
            assertFalse(release.isCompleted)
            assertEquals(TetherPhase.Connected, h.session.state.value.phase)
            release.complete(Unit)
            withTimeout(1_500) { h.session.eventPump.stats.first { it.objectAdded == 80L } }
            withTimeout(1_500) { while (handles.size < 80) kotlinx.coroutines.yield() }
            assertEquals((1L..80L).toList(), handles.toList())
        } finally { release.complete(Unit); collector.cancelAndJoin() }
    }

    @Test fun `event callback can await a user request without blocking the sole USB worker`() = scenario {
        val link = link()
        val h = harness(link)
        h.session.connect(target())
        val callbackDone = gate()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            h.session.events.collect {
                h.session.runOp { it.getProperty(1) }
                callbackDone.complete(Unit)
            }
        }
        try {
            link.drains.add(listOf(event(1)))
            h.session.drainDiagnosticEvents()
            withTimeout(1_500) { callbackDone.await() }
            assertEquals(1, opcodes(link).count { it == 0x9402 })
            assertAllIo(h, link)
        } finally { collector.cancelAndJoin() }
    }

    @Test fun `bulk event backlog overflow disconnects explicitly instead of accumulating across transactions`() = scenario {
        val link = link()
        val h = harness(link)
        h.session.connect(target())
        val entered = gate()
        val release = gate()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { h.session.events.collect { entered.complete(Unit); release.await() } }
        try {
            link.drains.add((1L..80L).map(::event))
            h.session.drainDiagnosticEvents()
            entered.await()
            h.session.eventPump.stats.first { it.backpressure > 0 }
            link.pendingEvents.add((100L..1100L).map(::event))
            runCatching { h.session.drainDiagnosticEvents() }
            withTimeout(1_500) { h.session.state.first { it.phase == TetherPhase.Fatal } }
            val count = link.calls.size
            failure<PtpException> { h.session.runOp { it.getProperty(2) } }
            assertEquals(count, link.calls.size)
            assertFalse(link.isFatal) // Application backlog failure is distinct from a raw USB fatal.
            assertTrue(h.session.isFatal)
        } finally { release.complete(Unit); collector.cancelAndJoin() }
    }

    @Test fun `detached close publishes Detached and explicit reconnect starts a fresh link`() = scenario {
        val first = link()
        val second = link()
        val h = harness(first, second)
        val requested = target()
        h.session.connect(requested)
        h.session.close(CloseReason.DETACHED)
        assertEquals(TetherPhase.Detached, h.session.state.value.phase)
        assertEquals(1, first.closeCount.get())
        assertEquals(ConnectOutcome.Connected, h.session.connect(requested))
        assertEquals(2, h.factory.createCount.get())
        assertEquals(0, second.closeCount.get())
        assertFalse(h.session.isFatal)
    }
}
