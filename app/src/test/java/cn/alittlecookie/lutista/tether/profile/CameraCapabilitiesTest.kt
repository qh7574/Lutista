package cn.alittlecookie.lutista.tether.profile

import cn.alittlecookie.lutista.tether.protocol.Availability
import cn.alittlecookie.lutista.tether.protocol.Capability
import cn.alittlecookie.lutista.tether.protocol.CapabilityEvidence
import org.junit.Assert.*
import org.junit.Test

class CameraCapabilitiesTest {
    private val yes = Capability.ok(CapabilityEvidence.PTP_OK)
    private val no = Capability.no(CapabilityEvidence.PTP_REJECTED)

    @Test fun fourteenFeatureNamesAreStableCacheKeys() {
        assertEquals(listOf("ObjectBrowse", "ObjectDownload", "LiveView", "LiveViewFormatSwitch", "ConfigRead", "ConfigWrite", "CapturePhoto", "CaptureMovie", "PhotoStyle", "ModeRead", "Battery", "Thumbnail", "RawPreview", "LiveViewFilterPreview"), Feature.entries.map { it.name })
    }

    @Test fun allSixShapesPreserveUnknownSemantics() {
        val empty = CameraCapabilities.empty()
        assertEquals(UsableShape.Unrecognized, empty.usableShape)
        assertEquals(UsableShape.Nothing, empty.with(Feature.ObjectBrowse, no).with(Feature.LiveView, no).usableShape)
        assertEquals(UsableShape.BrowseOnly, empty.with(Feature.ObjectBrowse, yes).usableShape)
        assertEquals(UsableShape.LiveViewOnly, empty.with(Feature.LiveView, yes).usableShape)
        val both = empty.with(Feature.ObjectBrowse, yes).with(Feature.LiveView, yes)
        assertEquals(UsableShape.LiveViewNoConfig, both.usableShape)
        assertEquals(UsableShape.Full, both.with(Feature.ConfigRead, yes).usableShape)
        assertEquals(14, empty.unknown.size)
        assertTrue(empty[Feature.LiveView].mayProbe)
    }

    @Test fun unknownOverrideClearsWritableAndDoesNotMutateOriginal() {
        val original = CameraCapabilities.empty().with(Feature.ConfigWrite, yes.copy(writable = true))
        val masked = original.asUnknown("debug simulation")
        assertTrue(original[Feature.ConfigWrite].writable)
        for (feature in Feature.entries) {
            assertEquals(Availability.Unknown, masked[feature].availability)
            assertEquals("debug simulation", masked[feature].detail)
            assertFalse(masked[feature].writable)
        }
    }
}
