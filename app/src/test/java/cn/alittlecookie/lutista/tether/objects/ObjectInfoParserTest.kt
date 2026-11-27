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
        assertThrows(PtpParseException::class.java) { ObjectInfoParser.parse(1, ByteArray(37), PtpQuirks()) }
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
