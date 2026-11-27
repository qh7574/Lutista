package cn.alittlecookie.lutista.tether.driver

import cn.alittlecookie.lutista.tether.profile.CameraCapabilities
import cn.alittlecookie.lutista.tether.profile.Feature
import cn.alittlecookie.lutista.tether.profile.profiles.GenericPtpProfile
import cn.alittlecookie.lutista.tether.profile.profiles.LumixS1Profile
import cn.alittlecookie.lutista.tether.protocol.Capability
import cn.alittlecookie.lutista.tether.protocol.CapabilityEvidence
import org.junit.Assert.*
import org.junit.Test

class ControllerAvailabilityTest {
    private val features = listOf(Feature.LiveView, Feature.ConfigRead, Feature.ConfigWrite,
        Feature.CapturePhoto, Feature.CaptureMovie)
    @Test fun `unknown remains tryable for declared protocols while unsupported is unavailable`() {
        val profile = LumixS1Profile()
        val unknown = CameraCapabilities.empty()
        for (feature in features) {
            assertTrue(feature.name, profile.allowsController(feature, unknown))
            assertFalse(feature.name, profile.allowsController(feature,
                unknown.with(feature, Capability.no(CapabilityEvidence.PTP_REJECTED))))
        }
    }
    @Test fun `unidentified generic PTP never grants vendor controllers from unknown or forced supported flags`() {
        val profile = GenericPtpProfile()
        for (feature in features) {
            assertFalse(profile.allowsController(feature, CameraCapabilities.empty()))
            assertFalse(profile.allowsController(feature, CameraCapabilities.empty().with(feature,
                Capability.ok(CapabilityEvidence.STATIC_OPS))))
        }
    }
}
