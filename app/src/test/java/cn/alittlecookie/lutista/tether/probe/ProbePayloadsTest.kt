package cn.alittlecookie.lutista.tether.probe

import cn.alittlecookie.lutista.tether.Fixtures
import org.junit.Assert.*
import org.junit.Test

class ProbePayloadsTest {
    @Test fun isoDescriptorSelects29ValuesInsteadOfNineUpperLimitValues() {
        assertEquals(29, ProbePayloads.isoDescriptorCount(Fixtures.hex("desc_iso")))
    }

    @Test fun isoDescriptorRejectsAllTruncationsAndTrailingOrWrongRootBytes() {
        val wire = Fixtures.hex("desc_iso")
        for (end in 0 until wire.size) assertNull("prefix length=$end", ProbePayloads.isoDescriptorCount(wire.copyOf(end)))
        assertNull(ProbePayloads.isoDescriptorCount(wire + byteArrayOf(0)))
        assertNull(ProbePayloads.isoDescriptorCount(wire.copyOf().also { it[0] = 0x21 }))
    }

    @Test fun descriptorZeroEnumerationIsSupportedButReadOnlyByRule() {
        val leafPayload = ProbeFixtures.u32(0L, 0x10001L, 0L, 1L, 6L, 2L, 100L, 0L)
        val leaf = ProbeFixtures.u32(1L, leafPayload.size.toLong()) + leafPayload
        val isoValue = branch(0x02000021L, leaf)
        val isoContainer = branch(0x02000020L, isoValue)
        assertEquals(0, ProbePayloads.isoDescriptorCount(isoContainer))
        val cap = cn.alittlecookie.lutista.tether.protocol.CapabilityRules.judgeIsoDescriptor(0)
        assertEquals(cn.alittlecookie.lutista.tether.protocol.Availability.Supported, cap.availability)
        assertFalse(cap.writable)
    }

    @Test fun propertyWidthEchoAndExactConsumptionAreRequired() {
        val wire = Fixtures.hex("value_mode_pos")
        assertEquals(3L, ProbePayloads.propertyValue(wire, 0x02000082L))
        assertNull(ProbePayloads.propertyValue(wire, 0x02000083L))
        assertNull(ProbePayloads.propertyValue(wire.copyOf(wire.size - 1), 0x02000082L))
        assertNull(ProbePayloads.propertyValue(wire + byteArrayOf(0), 0x02000082L))
        assertNull(ProbePayloads.propertyValue(ProbeFixtures.hex("820000020100000003"), 0x02000082L))
    }

    @Test fun handlesUseUnsignedLongAndRejectHugeCountsBeforeAllocation() {
        assertEquals(listOf(0xFFFFFFFFL), ProbePayloads.handles(ProbeFixtures.u32(1L, 0xFFFFFFFFL)))
        assertNull(ProbePayloads.handles(ProbeFixtures.u32(0xFFFFFFFFL)))
        assertNull(ProbePayloads.handles(ProbeFixtures.u32(1L)))
        assertNull(ProbePayloads.handles(ProbeFixtures.u32(0L, 3L)))
    }

    private fun branch(code: Long, child: ByteArray): ByteArray =
        ProbeFixtures.u32(code, 20L, 20L, 0x10001L, 0L, 1L, child.size.toLong()) + child
}
