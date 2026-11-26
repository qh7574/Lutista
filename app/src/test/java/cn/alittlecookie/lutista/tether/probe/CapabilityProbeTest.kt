package cn.alittlecookie.lutista.tether.probe

import cn.alittlecookie.lutista.tether.profile.Feature
import cn.alittlecookie.lutista.tether.profile.UsableShape
import cn.alittlecookie.lutista.tether.protocol.Availability
import cn.alittlecookie.lutista.tether.protocol.CapabilityEvidence
import cn.alittlecookie.lutista.tether.protocol.DeviceInfoParser
import cn.alittlecookie.lutista.tether.protocol.PtpParseException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class CapabilityProbeTest {
    private suspend fun scenario(name: String): Pair<ProbeResult, FakeProbe> {
        val fixture = ProbeFixtures.load(name)
        val caps = ProbeFixtures.caps(fixture)
        val driver = ProbeFixtures.driver(fixture)
        val result = driver.probe().probe(caps, ProbeFixtures.profile(caps), ProbeFixtures.storage(fixture))
        driver.assertConsumed()
        return result to driver
    }

    @Test fun s1FullUsesObservedPayloadsAndSavesUnsignedGallerySeed() = runBlocking {
        val (result, driver) = scenario("s1Full")
        val caps = result.capabilities
        assertEquals(UsableShape.Full, caps.usableShape)
        assertEquals("lumix-s1", caps.profileId)
        assertEquals(39L, caps.totalProbeMs)
        assertEquals(1234L, caps.probedAt)
        assertEquals(3L, caps.probedModePos)
        assertEquals(listOf(17L, 0x80000002L), result.gallerySeed)
        for (feature in Feature.entries - Feature.LiveViewFilterPreview) {
            assertEquals(feature.name, Availability.Supported, caps[feature].availability)
        }
        assertTrue(caps[Feature.ConfigWrite].writable)
        assertTrue(caps[Feature.ConfigWrite].detail!!.contains("count=29"))
        assertEquals(CapabilityEvidence.PROFILE_ASSERTED, caps[Feature.CapturePhoto].evidence)
        assertEquals(CapabilityEvidence.STATIC_OPS, caps[Feature.Battery].evidence)
        assertEquals(Availability.Unknown, caps[Feature.LiveViewFilterPreview].availability)
        assertEquals(listOf(0x9414, 0x9402, 0x9402, 0x9108, 0x1007), driver.calls.map { it.op.code })
        assertTrue(driver.logs.last().contains("totalProbeMs=39"))
    }

    @Test fun explicitReadOnlyRejectionsProduceBrowseOnly() = runBlocking {
        val caps = scenario("browseOnly").first.capabilities
        assertEquals(UsableShape.BrowseOnly, caps.usableShape)
        assertEquals(Availability.Unsupported, caps[Feature.LiveView].availability)
        assertEquals(Availability.Unsupported, caps[Feature.ConfigRead].availability)
        assertEquals(CapabilityEvidence.PTP_REJECTED, caps[Feature.PhotoStyle].evidence)
        assertFalse(caps[Feature.ConfigWrite].writable)
    }

    @Test fun liveViewWithRejectedConfigUsesNoConfigShape() = runBlocking {
        val caps = scenario("noConfig").first.capabilities
        assertEquals(UsableShape.LiveViewNoConfig, caps.usableShape)
        assertEquals(Availability.Supported, caps[Feature.LiveView].availability)
        assertEquals(Availability.Unsupported, caps[Feature.ConfigRead].availability)
        assertEquals(Availability.Unsupported, caps[Feature.ConfigWrite].availability)
    }

    @Test fun timeoutProvenBeforeUsbKeepsLiveViewAttemptableAndContinues() = runBlocking {
        val (result, driver) = scenario("liveViewTimeout")
        val caps = result.capabilities
        assertEquals(UsableShape.BrowseOnly, caps.usableShape)
        assertEquals(Availability.Unknown, caps[Feature.LiveView].availability)
        assertEquals(CapabilityEvidence.TIMEOUT, caps[Feature.LiveView].evidence)
        assertTrue(caps[Feature.LiveView].mayProbe)
        assertEquals(5, driver.calls.size)
        assertEquals(Availability.Supported, caps[Feature.ConfigRead].availability)
    }

    @Test fun unknownPtpUsesOnlyConcreteStorageAndPreservesUnknowns() = runBlocking {
        val (result, driver) = scenario("unknownPtp")
        assertEquals("ptp-generic", result.capabilities.profileId)
        assertEquals(UsableShape.Unrecognized, result.capabilities.usableShape)
        assertEquals(listOf(0x1007), driver.calls.map { it.op.code })
        assertEquals(listOf(0x10001L, 0L, 0xFFFFFFFFL), driver.calls.single().op.params.toList())
        assertTrue(result.capabilities[Feature.CapturePhoto].mayProbe)
        assertEquals(CapabilityEvidence.NOT_PROBED, result.capabilities[Feature.PhotoStyle].evidence)
        assertNull(result.gallerySeed)
    }

    @Test fun corruptDeviceInfoFailsBeforeAnyProbeCanBeCreated() {
        val fixture = ProbeFixtures.load("corruptDeviceInfo")
        var commands = 0
        try {
            val caps = DeviceInfoParser.parse(ProbeFixtures.hex(fixture.getValue("deviceInfoHex").jsonPrimitive.content))
            commands++ // 只有解析成功才会进入组装，坏回包绝不可到此。
            ProbeFixtures.profile(caps)
            fail("Malformed DeviceInfo must not enter probing")
        } catch (_: PtpParseException) {
            assertEquals(0, commands)
        }
    }

    @Test fun wrongModeEchoIsUnknownAndDoesNotPoisonLaterSteps() = runBlocking {
        val fixture = ProbeFixtures.load("s1Full")
        val original = ProbeFixtures.driver(fixture)
        val caps = ProbeFixtures.caps(fixture)
        val execute: suspend (cn.alittlecookie.lutista.tether.transport.PtpOp, Int) -> Pair<cn.alittlecookie.lutista.tether.transport.PtpResponse, ByteArray> = { op, timeout ->
            val reply = original.execute(op, timeout)
            if (op.params.firstOrNull() == 0x02000082L) reply.first to ProbeFixtures.hex("83000002020000000300") else reply
        }
        val result = CapabilityProbe(execute, { false }, { original.now }).probe(caps, ProbeFixtures.profile(caps))
        assertEquals(Availability.Unknown, result.capabilities[Feature.ConfigRead].availability)
        assertEquals(Availability.Unknown, result.capabilities[Feature.ModeRead].availability)
        assertNull(result.capabilities.probedModePos)
        assertEquals(Availability.Supported, result.capabilities[Feature.PhotoStyle].availability)
        assertEquals(Availability.Supported, result.capabilities[Feature.ObjectBrowse].availability)
    }

    @Test fun truncatedSuccessfulHandlesIsIndeterminateInsteadOfUnsupported() = runBlocking {
        val caps = ProbeFixtures.caps().copy(usbVendorId = 0x1234)
        val driver = FakeProbe(listOf(FakeReply(0x1007, listOf(7L, 0L, 0xFFFFFFFFL), data = ProbeFixtures.u32(2L, 11L))))
        val result = driver.probe().probe(caps, ProbeFixtures.profile(caps), listOf(7L))
        assertEquals(Availability.Unknown, result.capabilities[Feature.ObjectBrowse].availability)
        assertEquals(CapabilityEvidence.INDETERMINATE, result.capabilities[Feature.ObjectBrowse].evidence)
        assertNull(result.gallerySeed)
    }

    @Test fun emptyGalleryStillConfirmsBrowseSupport() = runBlocking {
        val caps = ProbeFixtures.caps().copy(usbVendorId = 0x1234)
        val driver = FakeProbe(listOf(FakeReply(0x1007, listOf(7L, 0L, 0xFFFFFFFFL), data = ProbeFixtures.u32(0L))))
        val result = driver.probe().probe(caps, ProbeFixtures.profile(caps), listOf(7L))
        assertEquals(Availability.Supported, result.capabilities[Feature.ObjectBrowse].availability)
        assertEquals(emptyList<Long>(), result.gallerySeed)
    }
}
