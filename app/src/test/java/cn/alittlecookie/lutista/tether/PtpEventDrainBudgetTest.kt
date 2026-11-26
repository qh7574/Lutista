package cn.alittlecookie.lutista.tether

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import cn.alittlecookie.lutista.tether.protocol.PtpConstants
import cn.alittlecookie.lutista.tether.protocol.PtpContainerCodec
import cn.alittlecookie.lutista.tether.transport.PtpException
import cn.alittlecookie.lutista.tether.transport.PtpOp
import cn.alittlecookie.lutista.tether.transport.PtpTimeoutException
import cn.alittlecookie.lutista.tether.transport.PtpUsbTransport
import cn.alittlecookie.lutista.tether.transport.UsbIoThread
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference

/** Synthetic USB scripts exercise the production transport; these are not hardware evidence. */
class PtpEventDrainBudgetTest {
    @Before
    fun setUp() {
        UsbIoThread.resetForTest()
        UsbIoThread.bind()
    }

    @After
    fun tearDown() {
        UsbIoThread.resetForTest()
    }

    @Test
    fun `constant clock sustained event stream stops at eight reads and releases busy`() = runBlocking {
        val h = UsbHarness()
        h.open()
        var next = 0L
        h.interruptPacket = { event(++next) }

        val events = h.transport.drainEvents(2)

        assertEquals((1L..8L).toList(), events.map { it.params.single() })
        assertEquals(List(8) { 2 }, h.interruptTimeouts)
        assertFalse(h.transport.isFatal)
        val beforeTransaction = h.bulkCalls
        h.responses.add(PtpContainerCodec.encodeResponse(PtpConstants.Rc.OK, 2L))
        assertEquals(PtpConstants.Rc.OK, h.transport.transaction(PtpOp(PtpConstants.Op.GET_DEVICE_INFO)).code)
        assertEquals("follow-up transaction must write a command and read its response", beforeTransaction + 2, h.bulkCalls)
        assertTrue(h.responses.isEmpty())
        h.transport.close()
        verify(exactly = 1) { h.conn.releaseInterface(h.intf) }
        verify(exactly = 1) { h.conn.close() }
    }

    @Test
    fun `constant clock sustained noise stream stops at eight reads without fatal`() = runBlocking {
        val h = UsbHarness()
        h.open()
        val noise = event(1L).also { PtpContainerCodec.writeU16(it, 4, 5) }
        h.interruptPacket = { noise }

        assertTrue(h.transport.drainEvents(2).isEmpty())

        assertEquals(8, h.interruptTimeouts.size)
        assertEquals(8, h.transport.discardedEventContainers)
        assertFalse(h.transport.isFatal)
        assertTrue(h.transport.isOpen)
    }

    @Test
    fun `advancing clock stops sustained stream at eight milliseconds`() = runBlocking {
        val h = UsbHarness()
        h.open()
        h.interruptPacket = { event(1L) }
        h.afterInterrupt = { timeout -> h.clock += timeout }
        val started = h.clock

        assertEquals(4, h.transport.drainEvents(2).size)

        assertEquals(8L, h.clock - started)
        assertEquals(List(4) { 2 }, h.interruptTimeouts)
        assertFalse(h.transport.isFatal)
    }

    @Test
    fun `last read timeout shrinks to remaining millisecond`() = runBlocking {
        val h = UsbHarness()
        h.open()
        h.interruptPacket = { event(1L) }
        h.afterInterrupt = { timeout -> h.clock += if (h.interruptTimeouts.size == 1) 7 else timeout }
        val started = h.clock

        assertEquals(2, h.transport.drainEvents(2).size)

        assertEquals(listOf(2, 1), h.interruptTimeouts)
        assertEquals(8L, h.clock - started)
        assertFalse(h.transport.isFatal)
    }

    @Test
    fun `all containers in final read are decoded even when its time budget expires`() = runBlocking {
        val h = UsbHarness()
        h.open()
        val packet = event(11L) + event(12L) + event(13L)
        h.interruptPacket = { packet }
        h.afterInterrupt = { h.clock += 8L }

        val events = h.transport.drainEvents(2)

        assertEquals(listOf(11L, 12L, 13L), events.map { it.params.single() })
        events.forEachIndexed { index, decoded -> assertArrayEquals(event(11L + index), decoded.raw) }
        assertEquals(1, h.interruptTimeouts.size)
        assertFalse(h.transport.isFatal)
    }

    @Test
    fun `read cap retains partial header and payload across rounds with following events`() = runBlocking {
        for (split in listOf(6, PtpContainerCodec.HEADER_SIZE + 2)) {
            val h = UsbHarness()
            h.open()
            val packets = ArrayDeque<ByteArray>()
            for (value in 1L..7L) packets.add(event(value))
            val partial = event(9L)
            packets.add(event(8L) + partial.copyOfRange(0, split))
            packets.add(partial.copyOfRange(split, partial.size) + event(10L))
            h.interruptPacket = { packets.pollFirst() }

            val first = h.transport.drainEvents(2)
            assertEquals((1L..8L).toList(), first.map { it.params.single() })
            assertEquals(8, h.interruptTimeouts.size)
            assertEquals(1, packets.size)

            val second = h.transport.drainEvents(2)
            assertEquals(listOf(9L, 10L), second.map { it.params.single() })
            assertArrayEquals(partial, second.first().raw)
            assertEquals(10, h.interruptTimeouts.size)
            assertFalse(h.transport.isFatal)
            h.transport.close()
        }
    }

    @Test
    fun `deadline retains partial event for next round`() = runBlocking {
        val h = UsbHarness()
        h.open()
        val next = event(2L)
        val packets = ArrayDeque<ByteArray>()
        packets.add(event(1L) + next.copyOfRange(0, 6))
        packets.add(next.copyOfRange(6, next.size) + event(3L))
        h.interruptPacket = { packets.pollFirst() }
        h.afterInterrupt = { h.clock += 8L }

        assertEquals(listOf(1L), h.transport.drainEvents(2).map { it.params.single() })
        assertEquals(1, packets.size)
        assertEquals(listOf(2L, 3L), h.transport.drainEvents(2).map { it.params.single() })
        assertFalse(h.transport.isFatal)
    }

    @Test
    fun `empty reads stop immediately and caller timeout remains clamped to one or two`() = runBlocking {
        val h = UsbHarness()
        h.open()
        for (timeout in listOf(-10, 0, 1, 2, 500)) {
            assertTrue(h.transport.drainEvents(timeout).isEmpty())
        }
        assertEquals(listOf(1, 1, 1, 2, 2), h.interruptTimeouts)
        h.interruptPacket = { ByteArray(0) }
        assertTrue(h.transport.drainEvents(2).isEmpty())
        assertEquals(6, h.interruptTimeouts.size)
        assertFalse(h.transport.isFatal)
    }

    @Test
    fun `drain preserves a fatal bulk state and never reopens transaction admission`() = runBlocking {
        val h = UsbHarness()
        h.open()
        val beforeFailure = h.bulkCalls
        try {
            h.transport.transaction(PtpOp(PtpConstants.Op.GET_DEVICE_INFO))
            fail("missing bulk response must fail")
        } catch (_: PtpTimeoutException) {
            assertTrue(h.transport.isFatal)
            assertEquals("fatal must follow a command write and timed-out bulk read", beforeFailure + 2, h.bulkCalls)
        }
        h.interruptPacket = { event(1L) }

        assertEquals(8, h.transport.drainEvents(2).size)
        assertTrue(h.transport.isFatal)
        val bulkCalls = h.bulkCalls
        try {
            h.transport.transaction(PtpOp(PtpConstants.Op.GET_DEVICE_INFO))
            fail("fatal transport must refuse later transactions")
        } catch (_: PtpException) {
            assertEquals(bulkCalls, h.bulkCalls)
        }
    }

    @Test
    fun `drain still requires bound physical owner before touching USB`() = runBlocking {
        val h = UsbHarness()
        h.open()
        h.interruptPacket = { event(1L) }
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            try {
                runBlocking { h.transport.drainEvents(2) }
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        worker.start()
        worker.join(2_000)

        assertFalse("off-owner call must return before USB", worker.isAlive)
        assertTrue(failure.get() is IllegalStateException)
        assertEquals(1, UsbIoThread.violationCount())
        assertTrue(h.interruptTimeouts.isEmpty())
        assertFalse(h.transport.isFatal)
    }

    private fun event(value: Long): ByteArray =
        PtpContainerCodec.encodeEvent(PtpConstants.Event.OBJECT_ADDED, longArrayOf(value))

    private class UsbHarness {
        var clock = 1_000L
        var interruptPacket: () -> ByteArray? = { null }
        var afterInterrupt: (Int) -> Unit = {}
        val interruptTimeouts = ArrayList<Int>()
        val responses = ArrayDeque<ByteArray>()
        var bulkCalls = 0
        val transport = PtpUsbTransport(now = { clock })
        val conn = mockk<UsbDeviceConnection>()
        val intf = mockk<UsbInterface>()
        private val device = mockk<UsbDevice>()
        private val bulkIn = endpoint(0x81, 2, 512)
        private val bulkOut = endpoint(0x01, 2, 512)
        private val intrIn = endpoint(0x82, 3, 64)

        init {
            every { device.interfaceCount } returns 1
            every { device.getInterface(0) } returns intf
            every { device.vendorId } returns 0x1234
            every { device.productId } returns 0x5678
            every { intf.endpointCount } returns 3
            every { intf.getEndpoint(0) } returns bulkIn
            every { intf.getEndpoint(1) } returns bulkOut
            every { intf.getEndpoint(2) } returns intrIn
            every { conn.claimInterface(intf, true) } returns true
            every { conn.releaseInterface(intf) } returns true
            every { conn.close() } just runs
            every { conn.rawDescriptors } returns null
            every { conn.bulkTransfer(bulkOut, any(), any(), any(), any()) } answers {
                bulkCalls += 1
                args[3] as Int
            }
            // Production fill uses the overload without a buffer offset.
            every { conn.bulkTransfer(bulkIn, any(), any(), any()) } answers {
                bulkCalls += 1
                val packet = responses.pollFirst()
                if (packet == null) {
                    -1
                } else {
                    val length = args[2] as Int
                    check(packet.size <= length)
                    packet.copyInto(args[1] as ByteArray)
                    packet.size
                }
            }
            every { conn.bulkTransfer(intrIn, any(), any(), any()) } answers {
                val timeout = args[3] as Int
                interruptTimeouts += timeout
                check(interruptTimeouts.size <= 32) { "event drain exceeded synthetic watchdog" }
                val packet = interruptPacket()
                afterInterrupt(timeout)
                if (packet == null) {
                    -1
                } else {
                    check(packet.size <= args[2] as Int)
                    packet.copyInto(args[1] as ByteArray)
                    packet.size
                }
            }
        }

        suspend fun open() {
            // Response-only preflight deliberately supplies no DeviceInfo dataset; no live fixture is used.
            responses.add(PtpContainerCodec.encodeResponse(PtpConstants.Rc.OK, 1L))
            transport.open(device, conn)
            assertEquals("preflight must reach both real bulk directions", 2, bulkCalls)
            assertTrue("preflight response must be consumed", responses.isEmpty())
            assertTrue(transport.isOpen)
            assertFalse(transport.isFatal)
        }

        private fun endpoint(address: Int, type: Int, maxPacket: Int): UsbEndpoint = mockk<UsbEndpoint>().also {
            every { it.address } returns address
            every { it.type } returns type
            every { it.maxPacketSize } returns maxPacket
        }
    }
}
