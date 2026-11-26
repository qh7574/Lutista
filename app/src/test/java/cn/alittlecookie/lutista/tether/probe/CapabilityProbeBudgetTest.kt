package cn.alittlecookie.lutista.tether.probe

import cn.alittlecookie.lutista.tether.profile.CameraProfile
import cn.alittlecookie.lutista.tether.profile.Feature
import cn.alittlecookie.lutista.tether.profile.profiles.LumixS1Profile
import cn.alittlecookie.lutista.tether.protocol.Availability
import cn.alittlecookie.lutista.tether.protocol.CapabilityEvidence
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CapabilityProbeBudgetTest {
    private fun browseOnly(): CameraProfile = object : CameraProfile by LumixS1Profile() {
        override val probePlan = ProbePlan.standard().filter { it.judgeId == ProbePlan.P5 }
    }

    @Test fun allFiveTimeoutsAreCappedAtStepLimitsAndTotal1500() = runBlocking {
        val fixture = ProbeFixtures.load("s1Full")
        val caps = ProbeFixtures.caps(fixture)
        val driver = ProbeFixtures.driver(fixture)
        driver.probe().probe(caps, ProbeFixtures.profile(caps))
        assertEquals(listOf(300, 200, 200, 300, 500), driver.calls.map { it.timeoutMs })
        assertEquals(1500, driver.calls.sumOf { it.timeoutMs })
    }

    @Test fun totalDeadlineExhaustionSkipsEveryRemainingTransaction() = runBlocking {
        val caps = ProbeFixtures.caps()
        val driver = FakeProbe(listOf(FakeReply(0x9414, listOf(0x0D800012L), code = 0x2005, elapsedMs = 1500)))
        val result = driver.probe().probe(caps, LumixS1Profile())
        assertEquals(1, driver.calls.size)
        assertEquals(1500L, result.capabilities.totalProbeMs)
        assertEquals(CapabilityEvidence.NOT_PROBED, result.capabilities[Feature.ModeRead].evidence)
        assertEquals(CapabilityEvidence.NOT_PROBED, result.capabilities[Feature.ObjectBrowse].evidence)
    }

    @Test fun allowedWildcardRejectionsRetryExactlyOnceWithRemainingBudget() = runBlocking {
        for (rejection in listOf(0x2006, 0x2013)) {
            val driver = FakeProbe(listOf(
                FakeReply(0x1007, listOf(0xFFFFFFFFL, 0L, 0xFFFFFFFFL), code = rejection, elapsedMs = 480),
                FakeReply(0x1007, listOf(0x10001L, 0L, 0xFFFFFFFFL), data = ProbeFixtures.u32(0L), elapsedMs = 20),
            ))
            val result = driver.probe().probe(ProbeFixtures.caps(), browseOnly(), listOf(0L, 0xFFFFFFFFL, 0x10001L, 0x10002L))
            assertEquals(listOf(500, 20), driver.calls.map { it.timeoutMs })
            assertEquals(500L, result.capabilities.totalProbeMs)
            assertEquals(Availability.Supported, result.capabilities[Feature.ObjectBrowse].availability)
            driver.assertConsumed()
        }
    }

    @Test fun exhaustedP5BudgetNeverStartsConcreteRetry() = runBlocking {
        val driver = FakeProbe(listOf(FakeReply(0x1007, listOf(0xFFFFFFFFL, 0L, 0xFFFFFFFFL), code = 0x2013, elapsedMs = 500)))
        val result = driver.probe().probe(ProbeFixtures.caps(), browseOnly(), listOf(0x10001L))
        assertEquals(1, driver.calls.size)
        assertEquals(Availability.Unknown, result.capabilities[Feature.ObjectBrowse].availability)
        assertEquals(CapabilityEvidence.NOT_PROBED, result.capabilities[Feature.ObjectBrowse].evidence)
    }

    @Test fun noConcreteStorageKeepsWildcardRejectionUnknown() = runBlocking {
        val driver = FakeProbe(listOf(FakeReply(0x1007, listOf(0xFFFFFFFFL, 0L, 0xFFFFFFFFL), code = 0x2006)))
        val result = driver.probe().probe(ProbeFixtures.caps(), browseOnly())
        assertEquals(1, driver.calls.size)
        assertEquals(Availability.Unknown, result.capabilities[Feature.ObjectBrowse].availability)
        assertTrue(result.capabilities[Feature.ObjectBrowse].mayProbe)
    }

    @Test fun deviceBusyIsNotOneOfTheRetryableRejections() = runBlocking {
        val driver = FakeProbe(listOf(FakeReply(0x1007, listOf(0xFFFFFFFFL, 0L, 0xFFFFFFFFL), code = 0x2019)))
        val result = driver.probe().probe(ProbeFixtures.caps(), browseOnly(), listOf(0x10001L))
        assertEquals(1, driver.calls.size)
        assertEquals(Availability.Unknown, result.capabilities[Feature.ObjectBrowse].availability)
    }

    @Test fun twoActualNonfatalStorageRejectionsConfirmUnsupported() = runBlocking {
        val driver = FakeProbe(listOf(
            FakeReply(0x1007, listOf(0xFFFFFFFFL, 0L, 0xFFFFFFFFL), code = 0x2013),
            FakeReply(0x1007, listOf(0x10001L, 0L, 0xFFFFFFFFL), code = 0x2013),
        ))
        val result = driver.probe().probe(ProbeFixtures.caps(), browseOnly(), listOf(0x10001L))
        assertEquals(2, driver.calls.size)
        assertEquals(Availability.Unsupported, result.capabilities[Feature.ObjectBrowse].availability)
    }

    @Test fun genericPtpWithoutKnownStorageDoesNotBlindlyProbe() = runBlocking {
        val caps = ProbeFixtures.caps().copy(usbVendorId = 0x1234)
        val driver = FakeProbe(emptyList())
        val result = driver.probe().probe(caps, ProbeFixtures.profile(caps))
        assertTrue(driver.calls.isEmpty())
        assertEquals(0L, result.capabilities.totalProbeMs)
        assertEquals(Availability.Unknown, result.capabilities[Feature.ObjectBrowse].availability)
    }
}
