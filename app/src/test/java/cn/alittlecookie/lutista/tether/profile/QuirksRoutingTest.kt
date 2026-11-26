package cn.alittlecookie.lutista.tether.profile

import cn.alittlecookie.lutista.tether.probe.*
import cn.alittlecookie.lutista.tether.profile.profiles.LumixS1Profile
import cn.alittlecookie.lutista.tether.protocol.Availability
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class QuirksRoutingTest {
    @Test fun suppliedConcreteStorageQuirkAndZeroFormatReachP5() = runBlocking {
        val profile = object : CameraProfile by LumixS1Profile() {
            override val quirks = PtpQuirks(storageIdForObjectQuery = 0x20002L)
            override val probePlan = ProbePlan.standard().filter { it.judgeId == ProbePlan.P5 }
        }
        val driver = FakeProbe(listOf(FakeReply(0x1007, listOf(0x20002L, 0L, 0xFFFFFFFFL), code = 0x2013)))
        val result = driver.probe().probe(ProbeFixtures.caps(), profile, listOf(0x30003L))
        assertEquals(1, driver.calls.size) // 已是具体 ID，禁止转去别的 StorageID 再试。
        assertEquals(Availability.Unsupported, result.capabilities[Feature.ObjectBrowse].availability)
    }

    @Test fun rawPreviewClassificationUsesProfileFormatInsteadOfS1Constant() {
        val profile = object : CameraProfile by LumixS1Profile() { override val quirks = PtpQuirks(rawFormat = 0xB003L) }
        val caps = ProbeFixtures.caps().copy(imageFormats = setOf(0xB003L))
        val probe = CapabilityProbe(execute = { _, _ -> error("P6 must not issue USB commands") }, isFatal = { false })
        assertEquals(Availability.Supported, probe.supplementP6(CameraCapabilities.empty(), caps, profile)[Feature.RawPreview].availability)
        assertEquals(Availability.Unknown, probe.supplementP6(CameraCapabilities.empty(), caps.copy(imageFormats = setOf(0x3800L)), profile)[Feature.RawPreview].availability)
    }

    @Test fun quirksRemainPureHintsAndDoNotEncodeUsbPacketSizeOrModeWritePermission() {
        val quirks = PtpQuirks(objectInfoFilenameOffsetCandidates = listOf(44, 52, 48), liveViewHeaderBytes = 212)
        assertEquals(listOf(44, 52, 48), quirks.objectInfoFilenameOffsetCandidates)
        assertEquals(212, quirks.liveViewHeaderBytes)
        assertFalse(quirks.supportsModeWrite)
        assertEquals(0x03000011L, quirks.captureReleaseParam)
        assertEquals(1000L, quirks.liveViewFormatSwitchSettleMs)
        assertEquals(5L, quirks.videoModePos)
        // ObjectInfo/SOI 搜索及文件分流属于 T03/T06；本测试仅验证 B2 的数据注入边界。
    }
}
