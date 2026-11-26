package cn.alittlecookie.lutista.tether.debug

import cn.alittlecookie.lutista.BuildConfig
import cn.alittlecookie.lutista.tether.profile.CameraCapabilities
import cn.alittlecookie.lutista.tether.profile.Feature
import cn.alittlecookie.lutista.tether.protocol.Availability
import cn.alittlecookie.lutista.tether.protocol.Capability
import cn.alittlecookie.lutista.tether.protocol.CapabilityEvidence
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class CapabilityOverrideTest {
    @Test fun exactlyFiveValuesAndCorruptPreferencesSelectBaseline() {
        assertEquals(listOf("None", "BrowseOnly", "NoLiveView", "NoConfig", "UnknownPtp"), CapabilityOverride.entries.map { it.name })
        assertEquals(CapabilityOverride.None, CapabilityOverride.fromPreference(null))
        assertEquals(CapabilityOverride.None, CapabilityOverride.fromPreference("BadStoredValue"))
        assertEquals(CapabilityOverride.None, CapabilityOverride.fromPreference("unknownptp"))
        CapabilityOverride.entries.forEach {
            assertEquals(if (BuildConfig.DEBUG) it else CapabilityOverride.None, CapabilityOverride.fromPreference(it.name))
        }
    }

    @Test fun noneReturnsIdenticalSnapshotAndReleaseCannotApplyAnOverride() {
        val caps = mixedCaps()
        assertSame(caps, CapabilityOverride.None.apply(caps))
        if (!BuildConfig.DEBUG) CapabilityOverride.entries.forEach { assertSame(caps, it.apply(caps)) }
    }

    @Test fun unknownPtpOnlyDerivesUnknownReadOnlySnapshotWithoutMutatingIdentityOrEvidenceSource() {
        assumeTrue(BuildConfig.DEBUG)
        val original = mixedCaps()
        val before = Feature.entries.associateWith { original[it] }
        val derived = CapabilityOverride.UnknownPtp.apply(original)
        assertNotSame(original, derived)
        assertIdentityPreserved(original, derived)
        Feature.entries.forEach { feature ->
            assertSame(before.getValue(feature), original[feature])
            assertEquals(Availability.Unknown, derived[feature].availability)
            assertEquals(CapabilityEvidence.INDETERMINATE, derived[feature].evidence)
            assertFalse(derived[feature].writable)
            assertTrue(derived[feature].detail!!.contains("override=UnknownPtp; controlled_simulation=true"))
        }
    }

    @Test fun masksSuppressOnlyTheirDeclaredFeaturesAndNeverPromoteSupportOrWrites() {
        assumeTrue(BuildConfig.DEBUG)
        val live = setOf(Feature.LiveView, Feature.LiveViewFormatSwitch, Feature.LiveViewFilterPreview)
        val config = setOf(Feature.ConfigRead, Feature.ConfigWrite, Feature.PhotoStyle)
        val expectedMasks = mapOf(
            CapabilityOverride.NoLiveView to live,
            CapabilityOverride.NoConfig to config,
            CapabilityOverride.BrowseOnly to (live + config + setOf(Feature.ModeRead, Feature.CapturePhoto, Feature.CaptureMovie)),
        )
        val caps = mixedCaps()
        expectedMasks.forEach { (override, mask) ->
            val derived = override.apply(caps)
            assertIdentityPreserved(caps, derived)
            Feature.entries.forEach { feature ->
                if (feature in mask) {
                    assertEquals(Availability.Unsupported, derived[feature].availability)
                    assertFalse(derived[feature].writable)
                    assertTrue(derived[feature].detail!!.contains("controlled_simulation=true"))
                } else assertSame("unmasked $override/$feature", caps[feature], derived[feature])
                if (caps[feature].availability != Availability.Supported) assertNotEquals(Availability.Supported, derived[feature].availability)
                if (!caps[feature].writable) assertFalse(derived[feature].writable)
            }
        }
    }

    @Test fun browseOnlyKeepsUnknownBrowseUnknownRatherThanInventingAWorkingCamera() {
        assumeTrue(BuildConfig.DEBUG)
        val caps = CameraCapabilities.empty()
        val derived = CapabilityOverride.BrowseOnly.apply(caps)
        assertEquals(Availability.Unknown, derived[Feature.ObjectBrowse].availability)
        assertEquals(CapabilityEvidence.NOT_PROBED, derived[Feature.ObjectBrowse].evidence)
        assertEquals(Availability.Unknown, derived[Feature.ObjectDownload].availability)
        assertFalse(derived[Feature.ObjectDownload].writable)
    }

    private fun mixedCaps() = CameraCapabilities(
        "Synthetic Camera", "synthetic-only-0002", "fixture-fw", "test-profile", 1234L, 56L,
        Feature.entries.withIndex().associate { (index, feature) -> feature to when (index % 3) {
            0 -> Capability.ok(CapabilityEvidence.PTP_OK, "synthetic supported", writable = true)
            1 -> Capability.no(CapabilityEvidence.PTP_REJECTED, "synthetic rejected")
            else -> Capability.unknown(CapabilityEvidence.TIMEOUT, "synthetic timeout")
        } },
    )

    private fun assertIdentityPreserved(a: CameraCapabilities, b: CameraCapabilities) {
        assertEquals(a.model, b.model); assertEquals(a.serial, b.serial); assertEquals(a.firmware, b.firmware)
        assertEquals(a.profileId, b.profileId); assertEquals(a.probedAt, b.probedAt); assertEquals(a.totalProbeMs, b.totalProbeMs)
    }
}
