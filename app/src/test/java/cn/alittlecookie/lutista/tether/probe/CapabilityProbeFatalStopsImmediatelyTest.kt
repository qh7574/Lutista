package cn.alittlecookie.lutista.tether.probe

import cn.alittlecookie.lutista.tether.profile.CameraProfile
import cn.alittlecookie.lutista.tether.profile.profiles.LumixS1Profile
import cn.alittlecookie.lutista.tether.transport.PtpProtocolException
import cn.alittlecookie.lutista.tether.transport.PtpResponse
import cn.alittlecookie.lutista.tether.transport.PtpTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CapabilityProbeFatalStopsImmediatelyTest {
    @Test fun firstRealTimeoutIsRethrownByIdentityWithoutWaitingForSecond() = runBlocking {
        val expected = PtpTimeoutException("synthetic bulk-IN failure")
        var commands = 0
        // fatal 在事务前已置位另有测试；这里异常必须由第一笔真实事务产生。
        val actualProbe = CapabilityProbe(execute = { _, _ -> commands++; throw expected }, isFatal = { commands > 0 })
        try { actualProbe.probe(ProbeFixtures.caps(), LumixS1Profile()); fail("must abort") }
        catch (actual: PtpTimeoutException) { assertSame(expected, actual) }
        assertEquals(1, commands)
    }

    @Test fun protocolFailureAfterFirstReplyStopsBeforeP3() = runBlocking {
        val expected = PtpProtocolException("synthetic invalid container")
        var commands = 0
        val probe = CapabilityProbe(execute = { _, _ ->
            commands++
            if (commands == 2) throw expected
            PtpResponse(0x2005) to byteArrayOf()
        }, isFatal = { commands >= 2 })
        try { probe.probe(ProbeFixtures.caps(), LumixS1Profile()); fail("must abort") }
        catch (actual: PtpProtocolException) { assertSame(expected, actual) }
        assertEquals(2, commands)
    }

    @Test fun alreadyFatalConnectionExecutesNoOpcode() = runBlocking {
        var commands = 0
        val probe = CapabilityProbe(execute = { _, _ -> commands++; PtpResponse(0x2001) to byteArrayOf() }, isFatal = { true })
        try { probe.probe(ProbeFixtures.caps(), LumixS1Profile()); fail("must abort") }
        catch (_: ProbeFatalException) { assertEquals(0, commands) }
    }

    @Test fun fatalFlagReturnedWithNonfatalResponseIsCheckedImmediately() = runBlocking {
        var fatal = false
        var commands = 0
        val probe = CapabilityProbe(execute = { _, _ -> commands++; fatal = true; PtpResponse(0x2005) to byteArrayOf() }, isFatal = { fatal })
        try { probe.probe(ProbeFixtures.caps(), LumixS1Profile()); fail("must abort") }
        catch (_: ProbeFatalException) { assertEquals(1, commands) }
    }

    @Test fun fatalP5RejectionCannotStartConcreteStorageRetry() = runBlocking {
        val profile = object : CameraProfile by LumixS1Profile() {
            override val probePlan = ProbePlan.standard().filter { it.judgeId == ProbePlan.P5 }
        }
        val driver = FakeProbe(listOf(FakeReply(0x1007, listOf(0xFFFFFFFFL, 0L, 0xFFFFFFFFL), code = 0x2013, makeFatal = true)))
        try { driver.probe().probe(ProbeFixtures.caps(), profile, listOf(0x10001L)); fail("must abort") }
        catch (_: ProbeFatalException) { assertEquals(1, driver.calls.size) }
    }

    @Test fun cancellationIsPropagatedAndDoesNotContinueThePlan() = runBlocking {
        val expected = CancellationException("connection scope closed")
        var commands = 0
        val probe = CapabilityProbe(execute = { _, _ -> commands++; throw expected }, isFatal = { false })
        try { probe.probe(ProbeFixtures.caps(), LumixS1Profile()); fail("must cancel") }
        catch (actual: CancellationException) { assertSame(expected, actual) }
        assertEquals(1, commands)
    }
}
