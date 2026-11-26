package cn.alittlecookie.lutista.tether.session

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import cn.alittlecookie.lutista.tether.transport.PtpDeviceCaps
import cn.alittlecookie.lutista.tether.transport.PtpDiagnostics
import cn.alittlecookie.lutista.tether.transport.PtpEvent
import cn.alittlecookie.lutista.tether.transport.PtpOp
import cn.alittlecookie.lutista.tether.transport.PtpResponse
import cn.alittlecookie.lutista.tether.transport.PtpTimeoutException
import cn.alittlecookie.lutista.tether.transport.PtpTransport
import cn.alittlecookie.lutista.tether.transport.PtpTransportAdapter
import cn.alittlecookie.lutista.tether.transport.Transport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PtpSessionApiTest {
    private data class Call(
        val op: PtpOp,
        val expectedData: Boolean,
        val dataOut: ByteArray?,
        val dataSink: ((ByteArray, Int) -> Unit)?,
        val timeoutMs: Int,
    )

    private class RecordingTransport : Transport {
        val calls = mutableListOf<Call>()
        var response = PtpResponse(0x2001)
        var failure: Throwable? = null
        var onCall: (Call) -> Unit = {}
        override suspend fun transaction(
            op: PtpOp, expectedData: Boolean, dataOut: ByteArray?,
            dataSink: ((ByteArray, Int) -> Unit)?, timeoutMs: Int,
        ): PtpResponse {
            val call = Call(op, expectedData, dataOut, dataSink, timeoutMs)
            calls.add(call)
            failure?.let { throw it }
            onCall(call)
            return response
        }
        override suspend fun drainEvents(timeoutMs: Int): List<PtpEvent> = emptyList()
        override suspend fun close(timeoutMs: Int) = Unit
    }

    private class LegacyTransport(val recorder: RecordingTransport) : PtpTransport {
        override suspend fun open(device: UsbDevice, connection: UsbDeviceConnection): PtpDeviceCaps =
            error("The adapter must not open a connection")
        override suspend fun transaction(
            op: PtpOp, expectedData: Boolean, dataOut: ByteArray?,
            dataSink: ((ByteArray, Int) -> Unit)?, timeoutMs: Int,
        ) = recorder.transaction(op, expectedData, dataOut, dataSink, timeoutMs)
        override suspend fun drainEvents(timeoutMs: Int) = recorder.drainEvents(timeoutMs)
        override suspend fun close(timeoutMs: Int) = recorder.close(timeoutMs)
    }

    private val diagnostics = object : PtpDiagnostics {
        override val isOpen = true
        override val isFatal = false
        override val deviceCaps: PtpDeviceCaps? = null
        override val discardedEventContainers = 0
        override fun collectPendingEvents(): List<PtpEvent> = emptyList()
    }

    @Test fun `adapter preserves all five transaction arguments and response identity`() = runBlocking {
        val transport = RecordingTransport()
        val adapter = PtpTransportAdapter(LegacyTransport(transport), diagnostics)
        val op = PtpOp(0x9403, longArrayOf(0xFFFF_FFFEL))
        val out = byteArrayOf(1, 2, 3)
        var delivered: ByteArray? = null
        val sink: (ByteArray, Int) -> Unit = { bytes, count -> delivered = bytes.copyOf(count) }
        val input = byteArrayOf(4, 5, 99)
        transport.onCall = { it.dataSink!!.invoke(input, 2) }
        transport.response = PtpResponse(0x2001, longArrayOf(0xFFFF_FFFFL), 2)
        val response = adapter.transaction(op, true, out, sink, 137)
        val call = transport.calls.single()
        assertSame(op, call.op)
        assertTrue(call.expectedData)
        assertSame(out, call.dataOut)
        assertSame(sink, call.dataSink)
        assertEquals(137, call.timeoutMs)
        assertSame(transport.response, response)
        assertSame(diagnostics, adapter.diagnostics)
        assertArrayEquals(byteArrayOf(4, 5), delivered)
    }

    @Test fun `adapter preserves the actual transport exception without wrapping`() = runBlocking {
        val transport = RecordingTransport()
        val failure = PtpTimeoutException("actual USB timeout")
        transport.failure = failure
        val adapter = PtpTransportAdapter(LegacyTransport(transport), diagnostics)
        assertSame(failure, expectFailure<PtpTimeoutException> { adapter.transaction(PtpOp(0x1001)) })
        val call = transport.calls.single()
        assertFalse(call.expectedData)
        assertNull(call.dataOut)
        assertNull(call.dataSink)
        assertEquals(4_000, call.timeoutMs)
    }

    @Test fun `open and close send only their own command and exact timeout`() = runBlocking {
        val transport = RecordingTransport()
        val api: PtpSessionApi = PtpSessionApiImpl(transport)
        assertSame(transport.response, api.openSession(0xFFFF_FFFEL, 731))
        assertFalse(api.warmed)
        assertSame(transport.response, api.closeSession(419))
        assertEquals(listOf(PtpOp(0x1002, longArrayOf(0xFFFF_FFFEL)), PtpOp(0x1003)), transport.calls.map { it.op })
        assertEquals(listOf(731, 419), transport.calls.map { it.timeoutMs })
        transport.calls.forEach { assertFalse(it.expectedData); assertNull(it.dataOut); assertNull(it.dataSink) }
    }

    @Test fun `setProperty forwards the original payload including empty payload`() = runBlocking {
        val transport = RecordingTransport()
        val api: PtpSessionApi = PtpSessionApiImpl(transport)
        for (payload in listOf(byteArrayOf(9, 8, 7), byteArrayOf())) {
            assertSame(transport.response, api.setProperty(0x0200_0031L, payload, 617))
            val call = transport.calls.last()
            assertEquals(PtpOp(0x9403, longArrayOf(0x0200_0031L)), call.op)
            assertSame(payload, call.dataOut)
            assertFalse(call.expectedData)
            assertNull(call.dataSink)
            assertEquals(617, call.timeoutMs)
        }
    }

    @Test fun `read helpers send correct op params data flag and caller timeout`() = runBlocking {
        val transport = RecordingTransport()
        val api: PtpSessionApi = PtpSessionApiImpl(transport)
        api.getDeviceInfo(101)
        api.getStorageIds(102)
        api.getProperty(0xF200_0031L, 103)
        api.listPropertyDescriptor(0xF200_0031L, 104)
        api.liveViewFormats(105)
        assertEquals(listOf(
            PtpOp(0x1001), PtpOp(0x1004),
            PtpOp(0x9402, longArrayOf(0xF200_0031L)),
            PtpOp(0x9108, longArrayOf(0xF200_0031L, 0, 0)),
            PtpOp(0x9414, longArrayOf(0x0D80_0012L)),
        ), transport.calls.map { it.op })
        assertEquals(listOf(101, 102, 103, 104, 105), transport.calls.map { it.timeoutMs })
        transport.calls.forEach { assertTrue(it.expectedData); assertNull(it.dataOut); assertNotNull(it.dataSink) }
    }

    @Test fun `data sink copies valid bytes synchronously before USB buffer reuse`() = runBlocking {
        val transport = RecordingTransport()
        transport.response = PtpResponse(0x2001, dataBytes = 3)
        transport.onCall = { call ->
            val reused = byteArrayOf(1, 2, 99, 99)
            call.dataSink!!.invoke(reused, 2)
            reused.fill(7)
            reused[0] = 3
            call.dataSink.invoke(reused, 1)
            reused.fill(0)
        }
        val result = PtpSessionApiImpl(transport).getDeviceInfo(123)
        assertSame(transport.response, result.response)
        assertArrayEquals(byteArrayOf(1, 2, 3), result.payload)
    }

    @Test fun `1007 cannot be first after OpenSession and emits no transport call`() = runBlocking {
        val transport = RecordingTransport()
        val api: PtpSessionApi = PtpSessionApiImpl(transport)
        api.openSession()
        expectFailure<IllegalStateException> { api.getObjectHandles() }
        assertEquals(listOf(0x1002), transport.calls.map { it.op.code })
        assertFalse(api.warmed)
    }

    @Test fun `failed DeviceInfo and StorageIDs do not permit 1007`() = runBlocking {
        val transport = RecordingTransport()
        val api: PtpSessionApi = PtpSessionApiImpl(transport)
        api.openSession()
        transport.response = PtpResponse(0x2005)
        api.getDeviceInfo()
        assertFalse(api.warmed)
        expectFailure<IllegalStateException> { api.getObjectHandles() }
        api.getStorageIds()
        assertFalse(api.warmed)
        expectFailure<IllegalStateException> { api.getObjectHandles() }
        assertEquals(listOf(0x1002, 0x1001, 0x1004), transport.calls.map { it.op.code })
    }

    @Test fun `either successful warmup permits all-storage format-zero enumeration`() = runBlocking {
        for (warmWithDeviceInfo in listOf(true, false)) {
            val transport = RecordingTransport()
            val api: PtpSessionApi = PtpSessionApiImpl(transport)
            api.openSession()
            if (warmWithDeviceInfo) api.getDeviceInfo() else api.getStorageIds()
            assertTrue(api.warmed)
            api.getObjectHandles(timeoutMs = 317)
            val call = transport.calls.last()
            assertEquals(PtpOp(0x1007, longArrayOf(0xFFFF_FFFFL, 0, 0xFFFF_FFFFL)), call.op)
            assertTrue(call.expectedData)
            assertNull(call.dataOut)
            assertNotNull(call.dataSink)
            assertEquals(317, call.timeoutMs)
        }
    }

    @Test fun `public enumeration forbids storage and format filters before USB`() = runBlocking {
        val transport = RecordingTransport()
        val api: PtpSessionApi = PtpSessionApiImpl(transport)
        api.openSession()
        api.getDeviceInfo()
        val count = transport.calls.size
        expectFailure<IllegalArgumentException> { api.getObjectHandles(storage = 0x0001_0001L) }
        expectFailure<IllegalArgumentException> { api.getObjectHandles(format = 0x3801L) }
        assertEquals(count, transport.calls.size)
        api.getObjectHandles(parent = 0xF000_0010L)
        assertArrayEquals(longArrayOf(0xFFFF_FFFFL, 0, 0xF000_0010L), transport.calls.last().op.params)
    }

    @Test fun `reopen and throwing CloseSession both clear successful warmup`() = runBlocking {
        val transport = RecordingTransport()
        val api: PtpSessionApi = PtpSessionApiImpl(transport)
        api.openSession(); api.getDeviceInfo()
        api.openSession()
        assertFalse(api.warmed)
        expectFailure<IllegalStateException> { api.getObjectHandles() }
        api.getStorageIds()
        assertTrue(api.warmed)
        val closeFailure = PtpTimeoutException("close response timeout")
        transport.failure = closeFailure
        assertSame(closeFailure, expectFailure<PtpTimeoutException> { api.closeSession() })
        assertFalse(api.warmed)
    }

    @Test fun `diagnostic accumulation rejects data over the four MiB limit`() = runBlocking {
        val transport = RecordingTransport()
        transport.onCall = { call ->
            val chunk = ByteArray(1024 * 1024)
            repeat(5) { call.dataSink!!.invoke(chunk, chunk.size) }
        }
        val api: PtpSessionApi = PtpSessionApiImpl(transport)
        val failure = expectFailure<cn.alittlecookie.lutista.tether.transport.PtpProtocolException> { api.getDeviceInfo() }
        assertTrue(failure.message!!.contains("bounded buffer"))
        assertFalse(api.warmed)
        assertEquals(1, transport.calls.size)
    }

    @Test fun `deferred object liveview capture and movie APIs send no USB command`() = runBlocking {
        val transport = RecordingTransport()
        val api: PtpSessionApi = PtpSessionApiImpl(transport)
        val calls: List<suspend () -> Unit> = listOf(
            { api.getObjectInfo(1); Unit }, { api.getThumb(1); Unit },
            { api.getObject(1) { _, _ -> }; Unit }, { api.getPartialObject(1, 0, 8); Unit },
            { api.liveView(true); Unit }, { api.liveViewFrame { _, _ -> }; Unit },
            { api.setLiveViewFormat(byteArrayOf(1)); Unit }, { api.initiateCapture(false); Unit },
            { api.movieRec(true); Unit },
        )
        calls.forEach { expectFailure<UnsupportedOperationException>(it) }
        assertTrue(transport.calls.isEmpty())
    }

    private suspend inline fun <reified T : Throwable> expectFailure(noinline block: suspend () -> Unit): T {
        val failure = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected "+T::class.java.simpleName+", got "+failure, failure is T)
        return failure as T
    }
}
