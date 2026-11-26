package cn.alittlecookie.lutista.tether.driver

import cn.alittlecookie.lutista.tether.transport.PtpDiagnostics
import cn.alittlecookie.lutista.tether.transport.PtpEvent
import cn.alittlecookie.lutista.tether.transport.PtpOp
import cn.alittlecookie.lutista.tether.transport.PtpResponse
import cn.alittlecookie.lutista.tether.transport.PtpTimeoutException
import cn.alittlecookie.lutista.tether.transport.PtpTransport
import cn.alittlecookie.lutista.tether.transport.PtpTransportAdapter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class PtpTransportAdapterContractTest {
    @Test fun transactionForwardsEveryArgumentAndResponseWithoutCopyingDataOut() = runBlocking {
        val delegate = mockk<PtpTransport>()
        val diagnostics = mockk<PtpDiagnostics>()
        val adapter = PtpTransportAdapter(delegate, diagnostics)
        val op = PtpOp(0x9403, longArrayOf(0x02000081L))
        val outgoing = byteArrayOf(1, 2, 3, 4)
        val response = PtpResponse(0x2001, longArrayOf(9L), 2L)
        val incoming = byteArrayOf(5, 6)
        var received: ByteArray? = null
        val sink: (ByteArray, Int) -> Unit = { bytes, count ->
            assertSame(incoming, bytes)
            assertEquals(2, count)
            received = bytes
        }
        coEvery { delegate.transaction(any(), any(), any(), any(), any()) } coAnswers {
            assertSame(op, firstArg<PtpOp>())
            assertEquals(true, secondArg<Boolean>())
            assertSame(outgoing, thirdArg<ByteArray?>())
            assertSame(sink, arg<((ByteArray, Int) -> Unit)?>(3))
            assertEquals(731, arg<Int>(4))
            arg<((ByteArray, Int) -> Unit)?>(3)!!.invoke(incoming, incoming.size)
            response
        }
        assertSame(response, adapter.transaction(op, true, outgoing, sink, 731))
        assertSame(incoming, received)
        assertSame(diagnostics, adapter.diagnostics)
        coVerify(exactly = 0) { delegate.open(any(), any()) }
    }

    @Test fun defaultTransactionPreservesNullDataOutAndTransportDefaults() = runBlocking {
        val delegate = mockk<PtpTransport>()
        val adapter = PtpTransportAdapter(delegate, mockk())
        val response = PtpResponse(0x2006)
        coEvery { delegate.transaction(any(), any(), any(), any(), any()) } coAnswers {
            assertEquals(false, secondArg<Boolean>())
            assertNull(thirdArg<ByteArray?>())
            assertNull(arg<((ByteArray, Int) -> Unit)?>(3))
            assertEquals(4_000, arg<Int>(4))
            response
        }
        assertSame(response, adapter.transaction(PtpOp(0x1007)))
    }

    @Test fun transactionAndCloseKeepOriginalExceptionIdentity() = runBlocking {
        val delegate = mockk<PtpTransport>()
        val adapter = PtpTransportAdapter(delegate, mockk())
        val failure = PtpTimeoutException("original adapter fatal timeout")
        coEvery { delegate.transaction(any(), any(), any(), any(), any()) } throws failure
        coEvery { delegate.close(647) } throws failure
        try { adapter.transaction(PtpOp(0x1009)); fail("transaction should throw") }
        catch (caught: PtpTimeoutException) { assertSame(failure, caught) }
        try { adapter.close(647); fail("close should throw") }
        catch (caught: PtpTimeoutException) { assertSame(failure, caught) }
    }

    @Test fun drainBoundsOnePollAndPreservesEventsOrFailures() = runBlocking {
        val delegate = mockk<PtpTransport>()
        val adapter = PtpTransportAdapter(delegate, mockk())
        val event = PtpEvent(0xC108, longArrayOf(42L), byteArrayOf(7))
        val events = listOf(event)
        coEvery { delegate.drainEvents(any()) } returns events
        assertSame(events, adapter.drainEvents(90))
        assertSame(events, adapter.drainEvents(0))
        coVerify(exactly = 1) { delegate.drainEvents(2) }
        coVerify(exactly = 1) { delegate.drainEvents(1) }
        val failure = PtpTimeoutException("original event timeout")
        coEvery { delegate.drainEvents(2) } throws failure
        try { adapter.drainEvents(); fail("drain should throw") }
        catch (caught: PtpTimeoutException) { assertSame(failure, caught) }
    }
}
