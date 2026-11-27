package cn.alittlecookie.lutista.tether.config

import cn.alittlecookie.lutista.tether.controls.descriptor
import cn.alittlecookie.lutista.tether.controls.fixture
import cn.alittlecookie.lutista.tether.controls.value
import cn.alittlecookie.lutista.tether.profile.ValueCodecKind
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class CameraPropertyTest {
    @Test fun `all real descriptor fixtures parse with tree span and enum widths`() {
        val expected = listOf("iso" to CameraProperty.ISO, "shutter" to CameraProperty.SHUTTER,
            "aperture" to CameraProperty.APERTURE, "whitebalance" to CameraProperty.WHITE_BALANCE,
            "ev" to CameraProperty.EV, "photostyle" to CameraProperty.PHOTO_STYLE)
        val parsed = expected.associate { (name, id) -> id to PropertyCodec.decodeDescriptor(fixture("desc_$name"), id) }
        assertEquals(29, parsed.getValue(CameraProperty.ISO).values.size)
        assertEquals(16, parsed.getValue(CameraProperty.WHITE_BALANCE).values.size)
        assertEquals(23, parsed.getValue(CameraProperty.PHOTO_STYLE).values.size)
        assertEquals(59, parsed.getValue(CameraProperty.SHUTTER).values.size)
        assertEquals(35L, parsed.getValue(CameraProperty.APERTURE).current)
        assertEquals(78, parsed.getValue(CameraProperty.APERTURE).values.size)
        assertTrue(parsed.getValue(CameraProperty.APERTURE).writable)
        val upper = PropertyCodec.decodeDescriptor(fixture("desc_iso"), CameraProperty.ISO_LIMIT)
        assertEquals(9, upper.values.size)
    }
    @Test fun `real EV range siblings validate without poisoning enum or inventing write options`() {
        val bytes = fixture("desc_ev")
        val exposure = PropertyCodec.decodeDescriptor(bytes, CameraProperty.EV)
        assertEquals(31, exposure.values.size)
        assertEquals(0L, exposure.current)
        assertTrue(exposure.writable)
        val sibling = PropertyCodec.decodeDescriptor(bytes, 0x02000062L)
        assertEquals(2, sibling.width)
        assertEquals(0L, sibling.current)
        assertTrue(sibling.values.isEmpty())
        assertFalse(sibling.writable)
        // Two trailing 40-byte range records each have a 32-byte payload.
        val firstRange = bytes.size - 80
        val badForm = bytes.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(firstRange + 28, 3) }
        val badSize = bytes.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(firstRange + 4, 30) }
        assertTrue(runCatching { PropertyCodec.decodeDescriptor(badForm, CameraProperty.EV) }.isFailure)
        assertTrue(runCatching { PropertyCodec.decodeDescriptor(badSize, CameraProperty.EV) }.isFailure)
    }
    @Test fun `truncated trees unknown datatype and corrupt span are rejected`() {
        val good = descriptor(CameraProperty.SHUTTER, 125000, listOf(125000, 250000))
        for (length in 0 until good.size) assertTrue("length=$length", runCatching {
            PropertyCodec.decodeDescriptor(good.copyOf(length), CameraProperty.SHUTTER)
        }.isFailure)
        val corruptSpan = good.copyOf().also { it[24] = 0 }
        assertTrue(runCatching { PropertyCodec.decodeDescriptor(corruptSpan, CameraProperty.SHUTTER) }.isFailure)
        val corruptType = good.copyOf().also { it[52] = 5 }
        assertTrue(runCatching { PropertyCodec.decodeDescriptor(corruptType, CameraProperty.SHUTTER) }.isFailure)
    }
    @Test fun `count zero is supported readonly and retains real current value`() {
        val d = PropertyCodec.decodeDescriptor(descriptor(CameraProperty.SHUTTER, 125000, emptyList()), CameraProperty.SHUTTER)
        assertEquals(125000L, d.current)
        assertEquals(emptyList<Long>(), d.values)
        assertFalse(d.writable)
    }
    @Test fun `value echo width and exact length are checked and write defaults to four bytes`() {
        assertEquals(0xFFFFFFFFL, PropertyCodec.decodeValue(value(CameraProperty.ISO, 0xFFFFFFFFL), CameraProperty.ISO + 1))
        assertEquals(35L, PropertyCodec.decodeValue(fixture("value_aperture"), CameraProperty.APERTURE + 1))
        assertTrue(runCatching { PropertyCodec.decodeValue(value(CameraProperty.ISO, 400), CameraProperty.EV + 1) }.isFailure)
        assertTrue(runCatching { PropertyCodec.decodeValue(value(CameraProperty.ISO, 400) + 0, CameraProperty.ISO + 1) }.isFailure)
        val b = ByteBuffer.wrap(PropertyCodec.encodeValue(CameraProperty.WHITE_BALANCE + 1, 2)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(12, b.capacity()); assertEquals(0x02000051, b.int); assertEquals(4, b.short.toInt()); assertEquals(0, b.short.toInt()); assertEquals(2, b.int)
    }
    @Test fun `display sentinels and shutter fractions preserve the camera values`() {
        assertEquals("AUTO", PropertyCodec.label(ValueCodecKind.ISO, 0xFFFFFFFFL))
        assertEquals("BULB", PropertyCodec.label(ValueCodecKind.SHUTTER, 0xFFFFFFFFL))
        assertEquals("—", PropertyCodec.label(ValueCodecKind.APERTURE, 0xFFFEL))
        assertEquals("f/3.5", PropertyCodec.label(ValueCodecKind.APERTURE, 35))
        assertEquals("60s", PropertyCodec.label(ValueCodecKind.SHUTTER, 0x80000000L + 60000))
        assertEquals(listOf("1/1.3", "1/2.5", "1/3.2", "1/13"), listOf(1300L, 2500, 3200, 13000).map {
            PropertyCodec.label(ValueCodecKind.SHUTTER, it) })
        assertEquals("-0.3", PropertyCodec.label(ValueCodecKind.EV, 0x8001))
        assertEquals("+0.7", PropertyCodec.label(ValueCodecKind.EV, 2))
        assertEquals("0", PropertyCodec.label(ValueCodecKind.EV, 0))
        assertEquals("0x5", PropertyCodec.label(ValueCodecKind.WB, 5)) // no invented fluorescent entry
    }
}
