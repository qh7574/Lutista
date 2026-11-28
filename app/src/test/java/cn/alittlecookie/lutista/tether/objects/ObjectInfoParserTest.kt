package cn.alittlecookie.lutista.tether.objects

import cn.alittlecookie.lutista.tether.profile.PtpQuirks
import cn.alittlecookie.lutista.tether.protocol.PtpParseException
import org.junit.Assert.*
import org.junit.Test

class ObjectInfoParserTest {
    @Test fun parsesUnsignedFieldsAndProfileFilenameLayouts() {
        for (offset in listOf(52, 48, 44)) {
            val info = ObjectInfoParser.parse(0x80000000, objectInfo(
                storage = 0xFFFFFFFFL, parent = 0x80000001, size = 0xF0000000L,
                name = "照片.JPG", date = "20261125T120000", filenameOffset = offset,
            ), PtpQuirks(), seq = 9)
            assertEquals(0xFFFFFFFFL, info.storageId)
            assertEquals(0x80000000L, info.handle)
            assertEquals(0x80000001L, info.parent)
            assertEquals(0xF0000000L, info.size)
            assertEquals("照片.JPG", info.name)
            assertEquals("20261125T120000", info.dateTakenRaw)
            assertEquals(9L, info.seq)
        }
    }

    @Test fun standardParentObjectDoesNotReadImageBitDepth() {
        // Independent PTP ObjectInfo bytes: ImageBitDepth=24 at 34, ParentObject=0x12345678 at 38.
        // Do not build this regression dataset with objectInfo(), which previously shared the bug.
        val payload = """
            00 00 01 00 01 38 00 00 D2 04 00 00
            01 38 00 00 00 00 A0 00 00 00 78 00 00 00
            70 17 00 00 A0 0F 00 00 18 00 00 00
            78 56 34 12 00 00 00 00 00 00 00 00 00 00
            06 41 00 2E 00 4A 00 50 00 47 00 00 00 00
        """.trimIndent().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()
        val info = ObjectInfoParser.parse(7, payload, PtpQuirks())
        assertEquals(0x12345678L, info.parent)
        assertEquals(0x00010000L, info.storageId)
        assertEquals(1234L, info.size)
        assertEquals("A.JPG", info.name)
        assertEquals("", info.dateTakenRaw)
        for (rootParent in listOf(0L, 0xFFFFFFFFL)) {
            val rootPayload = payload.copyOf().apply {
                for (index in 0..3) this[38 + index] = (rootParent ushr (8 * index)).toByte()
            }
            assertEquals(rootParent, ObjectInfoParser.parse(7, rootPayload, PtpQuirks()).parent)
        }
    }

    @Test fun onlyProfileAssociationIsDirectoryAndUnknownFormatsRemainUnknown() {
        val quirks = PtpQuirks(associationFormat = 0xBEEF)
        assertTrue(ObjectInfoParser.parse(1, objectInfo(format = 0xBEEF), quirks).isDir)
        for (format in listOf(0x3006L, 0xB003L, 0x300DL, 0x3800L)) {
            val info = ObjectInfoParser.parse(1, objectInfo(format = format), quirks)
            assertFalse(info.isDir)
            assertEquals(format, info.format)
        }
    }

    @Test fun rejectsTruncatedOrControlCharacterFilenameAndPreservesRawDate() {
        val truncated = assertThrows(PtpParseException::class.java) { ObjectInfoParser.parse(1, ByteArray(41), PtpQuirks()) }
        assertEquals("ObjectInfo fixed fields are truncated", truncated.message)
        assertThrows(PtpParseException::class.java) {
            ObjectInfoParser.parse(1, objectInfo(name = "bad\n.jpg"), PtpQuirks())
        }
        val info = ObjectInfoParser.parse(1, objectInfo(date = "camera-local-raw"), PtpQuirks())
        assertEquals("camera-local-raw", info.dateTakenRaw)
    }

    @Test fun handlesSkipCountAndValidateExactUnsignedDataset() {
        assertEquals(listOf(0xFFFFFFFEL, 1L), ObjectInfoParser.handles(objectHandles(0xFFFFFFFEL, 1)))
        assertEquals(emptyList<Long>(), ObjectInfoParser.handles(objectHandles()))
        assertThrows(PtpParseException::class.java) { ObjectInfoParser.handles(byteArrayOf(1, 0, 0, 0)) }
        assertThrows(PtpParseException::class.java) { ObjectInfoParser.handles(objectHandles(1) + 0.toByte()) }
        assertThrows(IllegalArgumentException::class.java) { ObjectInfoParser.handles(objectHandles(0)) }
        assertThrows(IllegalArgumentException::class.java) { ObjectInfoParser.handles(objectHandles(0xFFFFFFFFL)) }
    }
}
