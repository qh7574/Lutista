package cn.alittlecookie.lutista.tether.profile

import cn.alittlecookie.lutista.tether.probe.ProbeFixtures
import cn.alittlecookie.lutista.tether.profile.profiles.GenericLumixProfile
import cn.alittlecookie.lutista.tether.profile.profiles.GenericPtpProfile
import cn.alittlecookie.lutista.tether.profile.profiles.LumixS1Profile
import cn.alittlecookie.lutista.tether.transport.PtpDeviceCaps
import org.junit.Assert.*
import org.junit.Test

class ProfileResolutionTest {
    @Test fun exactS1RequiresVidPidAndFullModel() {
        val registry = CameraProfileRegistry()
        val caps = ProbeFixtures.caps()
        assertEquals("lumix-s1", registry.resolve(0x04DA, 0x2382, caps).id)
        assertEquals("lumix-generic", registry.resolve(0x04DA, 0x2383, caps).id)
        assertEquals("lumix-generic", registry.resolve(0x04DA, 0x2382, caps.copy(model = "DC-S1R")).id)
        assertEquals("ptp-generic", registry.resolve(0x1234, 0x2382, caps).id)
    }

    @Test fun fourMatchLevelsOutrankRegistrationOrder() {
        val caps = ProbeFixtures.caps()
        val prefix = object : CameraProfile by GenericLumixProfile() {
            override val id = "lumix-s-prefix"
            override val matchLevel = ProfileMatchLevel.ModelPrefix
            override fun match(vid: Int, pid: Int, caps: PtpDeviceCaps) = vid == 0x04DA && caps.model.startsWith("DC-S")
        }
        val registry = CameraProfileRegistry(listOf(GenericPtpProfile(), GenericLumixProfile(), prefix, LumixS1Profile()))
        assertEquals("lumix-s1", registry.resolve(0x04DA, 0x2382, caps).id)
        assertEquals("lumix-s-prefix", registry.resolve(0x04DA, 0x9999, caps.copy(model = "DC-S5")).id)
        assertEquals("lumix-generic", registry.resolve(0x04DA, 0x9999, caps.copy(model = "DC-G9")).id)
        assertEquals("ptp-generic", registry.resolve(0x1234, 1, caps).id)
    }

    @Test fun emptyRegistryAlwaysResolvesToConcreteGenericProfile() {
        assertEquals("ptp-generic", CameraProfileRegistry(emptyList()).resolve(1, 2, ProbeFixtures.caps()).id)
    }

    @Test fun genericLumixDoesNotBorrowS1CaptureAssertionsOrDescriptorTable() {
        val profile = GenericLumixProfile()
        assertFalse(profile.capturePhotoSupported)
        assertFalse(profile.captureMovieSupported)
        assertFalse(profile.quirks.supportsPropertyDescriptor)
        assertNull(profile.liveViewFormatsHint)
        assertNull(profile.photoStyleValues)
        assertTrue(profile.propertyCatalogSeed.isEmpty())
    }

    @Test fun s1HintTuplesAndPropertySeedsRemainDataInsteadOfWritableAssertions() {
        val profile = LumixS1Profile()
        assertEquals(listOf(640, 1120, 1600), profile.liveViewFormatsHint.map { it.width })
        assertEquals(listOf(432, 752, 1072), profile.liveViewFormatsHint.map { it.height })
        assertEquals(listOf(32, 350, 700), profile.liveViewFormatsHint.map { it.x })
        assertEquals(9, profile.propertyCatalogSeed.size)
        assertEquals(1L..22L, profile.photoStyleValues)
        assertFalse(CameraCapabilities.empty()[Feature.ConfigWrite].writable)
    }
}
