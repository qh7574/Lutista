package cn.alittlecookie.lutista.tether.session

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import cn.alittlecookie.lutista.tether.debug.TetherDiagLogger
import cn.alittlecookie.lutista.tether.driver.CameraTarget
import cn.alittlecookie.lutista.tether.driver.ConnectOutcome
import cn.alittlecookie.lutista.tether.driver.UsbTarget
import cn.alittlecookie.lutista.tether.protocol.PtpConstants
import cn.alittlecookie.lutista.tether.protocol.PtpContainerCodec
import cn.alittlecookie.lutista.tether.transport.PtpException
import cn.alittlecookie.lutista.tether.transport.PtpOp
import cn.alittlecookie.lutista.tether.transport.PtpProtocolException
import cn.alittlecookie.lutista.tether.transport.PtpTransportAdapter
import cn.alittlecookie.lutista.tether.transport.PtpUsbTransport
import cn.alittlecookie.lutista.tether.transport.Transport
import cn.alittlecookie.lutista.tether.transport.TransportFactory
import cn.alittlecookie.lutista.tether.transport.UsbIoThread
import cn.alittlecookie.lutista.tether.transport.UsbPtpDiagnostics
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Both overflow guards run through the production adapter, USB transport and DATA pump.
 * Only Android USB objects, clocks and thread creation are replaced. Every byte is
 * synthetic: these fixtures contain no camera dump, serial number or hardware evidence.
 */
class PtpPayloadLimitTest {
    private class ExecutorWorker : PtpScheduler.IoWorker {
        override val dispatcher: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor {
            Thread(it, UsbIoThread.THREAD_NAME)
        }.asCoroutineDispatcher()
        override fun shutdown() = dispatcher.close()
    }

    /** Holds polling, capability work and automatic fatal cleanup until assertions finish. */
    private class ManualDispatcher : CoroutineDispatcher() {
        private val pending = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.add(block) }
    }

    private data class UsbCounts(val bulkIn: Int, val bulkOut: Int, val interruptIn: Int, val queuedChunks: Int)

    private class UsbScript {
        val clock = AtomicLong(1_000)
        val device = mockk<UsbDevice>()
        val connection = mockk<UsbDeviceConnection>()
        val raw = PtpUsbTransport(now = { clock.incrementAndGet() })
        val adapter = PtpTransportAdapter(raw, UsbPtpDiagnostics(raw))
        val inChunks = ConcurrentLinkedQueue<ByteArray>()
        val inSizes = CopyOnWriteArrayList<Int>()
        val outFrames = CopyOnWriteArrayList<ByteArray>()
        val interruptReads = AtomicInteger()
        val releases = AtomicInteger()
        val closes = AtomicInteger()
        private val owner = AtomicReference<Thread?>()

        init {
            val bulkIn = endpoint(0x81, 2, 512)
            val bulkOut = endpoint(0x01, 2, 512)
            val interrupt = endpoint(0x82, 3, 64)
            val intf = mockk<UsbInterface>()
            every { intf.endpointCount } returns 3
            every { intf.getEndpoint(0) } returns bulkIn
            every { intf.getEndpoint(1) } returns bulkOut
            every { intf.getEndpoint(2) } returns interrupt
            every { device.interfaceCount } returns 1
            every { device.getInterface(0) } returns intf
            every { device.vendorId } returns 0x1234
            every { device.productId } returns 0x5678
            every { device.deviceName } returns "synthetic-payload-limit-camera"
            every { connection.claimInterface(intf, true) } answers { observeIo(); true }
            every { connection.releaseInterface(intf) } answers { observeIo(); releases.incrementAndGet(); true }
            every { connection.close() } answers { observeIo(); closes.incrementAndGet(); Unit }
            every { connection.rawDescriptors } returns null
            every { connection.bulkTransfer(any(), any(), any(), any(), any()) } answers {
                transfer(args[0] as UsbEndpoint, args[1] as ByteArray, args[2] as Int, args[3] as Int, args[4] as Int)
            }
            every { connection.bulkTransfer(any(), any(), any(), any()) } answers {
                transfer(args[0] as UsbEndpoint, args[1] as ByteArray, 0, args[2] as Int, args[3] as Int)
            }
        }

        private fun endpoint(address: Int, type: Int, maxPacket: Int): UsbEndpoint = mockk<UsbEndpoint>().also {
            every { it.address } returns address
            every { it.type } returns type
            every { it.maxPacketSize } returns maxPacket
        }

        private fun observeIo() {
            assertTrue("USB must use the bound worker", UsbIoThread.isIoThread())
            val current = Thread.currentThread()
            assertEquals(UsbIoThread.THREAD_NAME, current.name.substringBefore(" @coroutine#"))
            owner.compareAndSet(null, current)
            assertSame("All USB calls must keep the same owner", owner.get(), current)
        }

        private fun transfer(endpoint: UsbEndpoint, buffer: ByteArray, offset: Int, length: Int, timeoutMs: Int): Int {
            observeIo()
            assertTrue("USB reads and writes need a finite timeout", timeoutMs > 0)
            return when (endpoint.address) {
                0x01 -> {
                    assertTrue("These small command frames never need OUT ZLP", length > 0)
                    outFrames.add(buffer.copyOfRange(offset, offset + length))
                    length
                }
                0x81 -> {
                    assertEquals("Production bulk IN requests remain 64 KiB", CHUNK_BYTES, length)
                    val chunk = inChunks.poll()
                    if (chunk == null) {
                        inSizes.add(-1) // Unexpected extra reads terminate instead of spinning.
                        -1
                    } else {
                        assertTrue("Script chunks fit one aligned USB read", chunk.size <= length)
                        chunk.copyInto(buffer, offset)
                        inSizes.add(chunk.size)
                        chunk.size
                    }
                }
                0x82 -> { interruptReads.incrementAndGet(); -1 }
                else -> error("Unexpected synthetic endpoint")
            }
        }

        fun counts() = UsbCounts(inSizes.size, outFrames.size, interruptReads.get(), inChunks.size)

        fun queueData(opcode: Int, txid: Long, payload: ByteArray) {
            inChunks.add(PtpContainerCodec.encodeData(opcode, txid, payload))
            inChunks.add(PtpContainerCodec.encodeResponse(PtpConstants.Rc.OK, txid))
        }

        fun queueOverflow(opcode: Int, txid: Long) {
            assertEquals(LIMIT_BYTES.toLong(), PtpSessionApiImpl.MAX_DIAGNOSTIC_BYTES)
            val declaredPayload = LIMIT_BYTES + 1 + CHUNK_BYTES
            inChunks.add(ByteBuffer.allocate(PtpContainerCodec.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(PtpContainerCodec.HEADER_SIZE + declaredPayload)
                .putShort(PtpConstants.ContainerType.DATA.toShort()).putShort(opcode.toShort())
                .putInt(txid.toInt()).array())
            repeat(LIMIT_BYTES / CHUNK_BYTES) { inChunks.add(ByteArray(CHUNK_BYTES) { 0x5A.toByte() }) }
            inChunks.add(byteArrayOf(0x7C)) // The first byte above the allowed 4 MiB.
            inChunks.add(ByteArray(CHUNK_BYTES) { 0x6B.toByte() }) // Must remain unread.
            inChunks.add(PtpContainerCodec.encodeResponse(PtpConstants.Rc.OK, txid))
        }

        fun assertStoppedInsideData(reads: Int, writes: Int, txid: Long) {
            assertEquals(reads, inSizes.size)
            assertEquals(writes, outFrames.size)
            assertEquals(listOf(PtpContainerCodec.HEADER_SIZE) + List(64) { CHUNK_BYTES } + listOf(1), inSizes.takeLast(66))
            val remaining = inChunks.toList()
            assertEquals("Sink abort must leave DATA tail and RESPONSE unread", listOf(CHUNK_BYTES, 12), remaining.map { it.size })
            assertArrayEquals(PtpContainerCodec.encodeResponse(PtpConstants.Rc.OK, txid), remaining.last())
            assertTrue("The actual L1 transport must latch fatal before any cleanup", raw.isFatal)
            assertTrue("Fatal is observed before releasing the USB connection", raw.isOpen)
            assertEquals(0, releases.get())
            assertEquals(0, closes.get())
        }

        fun assertCommands(vararg commands: Pair<Int, Long>) {
            assertEquals(commands.toList(), outFrames.map { frame ->
                val header = checkNotNull(PtpContainerCodec.peekHeader(frame))
                assertEquals(PtpConstants.ContainerType.COMMAND, header.type)
                assertEquals(frame.size, header.length)
                header.code to header.transactionId
            })
        }
    }

    @Before fun before() { UsbIoThread.resetForTest() }

    @After fun after() {
        try {
            assertFalse("The worker must relinquish its binding", UsbIoThread.isBound())
            assertEquals(0, UsbIoThread.violationCount())
        } finally { UsbIoThread.resetForTest() }
    }

    @Test
    fun `PtpSessionApi data overflow latches real L1 fatal and later API call performs no USB`() = runBlocking {
        withTimeout(10_000) {
            val usb = UsbScript()
            usb.queueData(PtpConstants.Op.GET_DEVICE_INFO, 1L, syntheticDeviceInfo())
            usb.queueOverflow(PtpConstants.Op.GET_DEVICE_INFO, 2L)
            val scheduler = PtpScheduler { ExecutorWorker() }
            var frozen: UsbCounts? = null
            try {
                scheduler.start()
                val caps = scheduler.run { usb.raw.open(usb.device, usb.connection) }
                assertNull(caps.preflightFailure)
                assertEquals(0x1CL, caps.vendorExtensionId)
                assertFalse(usb.raw.isFatal)
                val api = PtpSessionApiImpl(usb.adapter)
                val overflow = failure<PtpProtocolException> { scheduler.run { api.getDeviceInfo() } }
                assertEquals("Diagnostic payload exceeds bounded buffer", overflow.message)
                assertFalse("Aborted warm-up must not become successful", api.warmed)
                usb.assertStoppedInsideData(reads = 68, writes = 2, txid = 2L)
                usb.assertCommands(PtpConstants.Op.GET_DEVICE_INFO to 1L, PtpConstants.Op.GET_DEVICE_INFO to 2L)
                frozen = usb.counts()

                // The scheduler is still usable here: rejection therefore comes from L1 itself.
                val rejected = failure<PtpException> { scheduler.run { api.getStorageIds() } }
                assertTrue(rejected.message.orEmpty().contains("fatal"))
                assertEquals("A later API call must perform neither USB writes nor reads", frozen, usb.counts())
                assertTrue(usb.raw.isFatal)
            } finally { scheduler.close { usb.raw.close() } }
            assertEquals("Fatal cleanup must release resources without another USB transaction", frozen, usb.counts())
            assertEquals(1, usb.releases.get())
            assertEquals(1, usb.closes.get())
            assertFalse(usb.raw.isOpen)
        }
    }

    @Test
    fun `CameraSession readDiagnostic overflow latches real L1 fatal and blocks all later session entrypoints`() = runBlocking {
        withTimeout(10_000) {
            val usb = UsbScript()
            usb.queueData(PtpConstants.Op.GET_DEVICE_INFO, 1L, syntheticDeviceInfo())
            usb.inChunks.add(PtpContainerCodec.encodeResponse(PtpConstants.Rc.OK, 2L))
            usb.queueData(PtpConstants.Op.GET_DEVICE_INFO, 3L, syntheticDeviceInfo())
            usb.queueData(PtpConstants.Op.GET_STORAGE_IDS, 4L, ByteArray(4))
            val probe = PtpOp(PtpConstants.PanasonicOp.GET_PROP_VALUE, longArrayOf(0x02000001L))
            usb.queueOverflow(probe.code, 5L)
            val scheduler = PtpScheduler { ExecutorWorker() }
            val uncaught = CopyOnWriteArrayList<Throwable>()
            val scope = CoroutineScope(SupervisorJob() + ManualDispatcher() + CoroutineExceptionHandler { _, cause -> uncaught.add(cause) })
            val creates = AtomicInteger()
            val factory = object : TransportFactory {
                override fun supports(target: CameraTarget) = target is UsbTarget && target.device === usb.device
                override suspend fun create(target: CameraTarget): Transport {
                    assertTrue("Factory open must run on bound ptp-io", UsbIoThread.isIoThread())
                    assertSame(usb.device, (target as UsbTarget).device)
                    assertEquals(1, creates.incrementAndGet())
                    usb.raw.open(usb.device, usb.connection)
                    return PtpTransportAdapter(usb.raw, UsbPtpDiagnostics(usb.raw))
                }
            }
            val session = CameraSession(factory, scheduler, TetherDiagLogger(false, { usb.clock.get() }, { _, _ -> }), scope, nowMs = { usb.clock.get() })
            var frozen: UsbCounts? = null
            try {
                assertEquals(ConnectOutcome.Connected, session.connect(UsbTarget(usb.device, true)))
                // Finish any raced post-handshake drain, then freeze that hook as well as scope jobs.
                scheduler.setEventDrain(null)
                scheduler.run { Unit }
                assertEquals(7, usb.counts().bulkIn)
                assertEquals(4, usb.counts().bulkOut)
                assertFalse(usb.raw.isFatal)
                assertEquals(TetherPhase.Connected, session.state.value.phase)

                val overflow = failure<PtpProtocolException> { session.readDiagnostic(probe, 1_500) }
                assertEquals("Probe payload exceeds bounded buffer", overflow.message)
                usb.assertStoppedInsideData(reads = 73, writes = 5, txid = 5L)
                usb.assertCommands(PtpConstants.Op.GET_DEVICE_INFO to 1L, PtpConstants.Op.OPEN_SESSION to 2L,
                    PtpConstants.Op.GET_DEVICE_INFO to 3L, PtpConstants.Op.GET_STORAGE_IDS to 4L, probe.code to 5L)
                assertEquals(TetherPhase.Fatal, session.state.value.phase)
                assertEquals(true, session.transportFatal)
                assertTrue(session.isFatal)
                frozen = usb.counts()

                failure<PtpProtocolException> { session.readDiagnostic(probe, 1_500) }
                var userBlockRan = false
                failure<PtpProtocolException> { session.runOp { userBlockRan = true; it.getDeviceInfo() } }
                var diagnosticBlockRan = false
                failure<PtpProtocolException> { session.runDiagnostic { _, _ -> diagnosticBlockRan = true } }
                assertFalse(userBlockRan)
                assertFalse(diagnosticBlockRan)
                assertEquals("Fatal session admission must preserve every USB count and unread chunk", frozen, usb.counts())
                assertTrue(usb.raw.isFatal)
            } finally {
                scope.cancel()
                try { session.close() } finally { scheduler.close { usb.raw.close() } }
            }
            assertEquals("Fatal close must not issue CloseSession or drain more USB bytes", frozen, usb.counts())
            assertEquals(1, usb.releases.get())
            assertEquals(1, usb.closes.get())
            assertFalse(usb.raw.isOpen)
            assertTrue("No uncaught background failure: " + uncaught, uncaught.isEmpty())
        }
    }

    private suspend inline fun <reified T : Throwable> failure(noinline block: suspend () -> Unit): T {
        val thrown = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected " + T::class.java.simpleName + ", got " + thrown, thrown is T)
        return thrown as T
    }

    private fun syntheticDeviceInfo(): ByteArray = ByteArrayOutputStream().apply {
        fun u16(value: Int) { write(value and 0xFF); write((value ushr 8) and 0xFF) }
        fun u32(value: Long) { repeat(4) { write(((value ushr (8 * it)) and 0xFF).toInt()) } }
        fun string(value: String) {
            if (value.isEmpty()) write(0) else { write(value.length + 1); write((value + '\u0000').toByteArray(Charsets.UTF_16LE)) }
        }
        u16(100); u32(0x1CL); u16(100); string(""); u16(0)
        val operations = listOf(PtpConstants.Op.GET_DEVICE_INFO, PtpConstants.Op.OPEN_SESSION, PtpConstants.Op.CLOSE_SESSION,
            PtpConstants.Op.GET_STORAGE_IDS, PtpConstants.PanasonicOp.GET_PROP_VALUE)
        u32(operations.size.toLong()); operations.forEach { u16(it) }
        repeat(4) { u32(0) } // Empty events, properties, capture formats and image formats.
        string("Synthetic Maker"); string("Payload Limit Fixture"); string("test-1"); string("")
    }.toByteArray()

    private companion object {
        const val LIMIT_BYTES = 4 * 1024 * 1024
        const val CHUNK_BYTES = 64 * 1024
    }
}
