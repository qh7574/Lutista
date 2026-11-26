package cn.alittlecookie.lutista.tether.probe

import cn.alittlecookie.lutista.tether.profile.CameraProfile
import cn.alittlecookie.lutista.tether.profile.Feature
import cn.alittlecookie.lutista.tether.profile.profiles.LumixS1Profile
import cn.alittlecookie.lutista.tether.protocol.Availability
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CapabilityProbeNoSideEffectTest {
    @Test fun wholePlanValidationRejectsAllKnownSideEffectOpcodesBeforeFirstIo() = runBlocking {
        for (sideEffect in listOf(0x9103, 0x9104, 0x9403, 0x9415, 0x100B)) {
            var calls = 0
            val profile = object : CameraProfile by LumixS1Profile() {
                override val probePlan = ProbePlan.standard().mapIndexed { index, step ->
                    if (index == 3) step.copy(ops = listOf(sideEffect)) else step
                }
            }
            val probe = CapabilityProbe(execute = { _, _ -> calls++; error("Forbidden probe side effect") }, isFatal = { false })
            try { probe.probe(ProbeFixtures.caps(), profile); fail("opcode must be rejected") }
            catch (_: IllegalArgumentException) { assertEquals(0, calls) }
        }
    }

    @Test fun outOfOrderOrDuplicateJudgesCannotPartiallyRun() = runBlocking {
        val standard = ProbePlan.standard()
        for (plan in listOf(standard.reversed(), listOf(standard[0], standard[0]))) {
            val driver = FakeProbe(emptyList())
            val profile = object : CameraProfile by LumixS1Profile() { override val probePlan = plan }
            try { driver.probe().probe(ProbeFixtures.caps(), profile); fail("order must be rejected") }
            catch (_: IllegalArgumentException) { assertTrue(driver.calls.isEmpty()) }
        }
    }

    @Test fun vendorJudgeCannotRemoveMatchGuardOrRerouteToCaptureFeature() {
        val standard = ProbePlan.standard()
        for (modified in listOf(standard[0].copy(requiresProfileMatch = false), standard[0].copy(feature = Feature.CapturePhoto))) {
            try { ProbePlan.validate(listOf(modified)); fail("guard or feature route must not be changed") }
            catch (_: IllegalArgumentException) { /* rejected before an executor exists */ }
        }
    }

    @Test fun mismatchedPanasonicProfileNeverRunsVendorReadsOrAssertsCapture() = runBlocking {
        val caps = ProbeFixtures.caps().copy(usbVendorId = 0x1234, usbProductId = 7, model = "Unrecognized")
        val driver = FakeProbe(listOf(FakeReply(0x1007, listOf(0xFFFFFFFFL, 0L, 0xFFFFFFFFL), data = ProbeFixtures.u32(0L))))
        val result = driver.probe().probe(caps, LumixS1Profile())
        assertEquals(listOf(0x1007), driver.calls.map { it.op.code })
        assertEquals(Availability.Unknown, result.capabilities[Feature.ModeRead].availability)
        assertEquals(Availability.Unknown, result.capabilities[Feature.CapturePhoto].availability)
        assertEquals(Availability.Unknown, result.capabilities[Feature.CaptureMovie].availability)
    }

    @Test fun confirmedProbeOpcodeSetContainsExactlyFourReadOperations() {
        assertEquals(setOf(0x9414, 0x9402, 0x9108, 0x1007), ProbePlan.allowedOps)
        assertEquals(listOf(300, 200, 200, 300, 500), ProbePlan.standard().map { it.timeoutMs })
        assertEquals(1500L, ProbePlan.TOTAL_BUDGET_MS)
    }
}
