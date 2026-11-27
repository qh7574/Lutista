package cn.alittlecookie.lutista.tether.objects

import org.junit.Assert.*
import org.junit.Test

class JpegScannerTest {
    @Test fun decodesEveryCompleteCandidateAndChoosesLargestDecodableArea() {
        val small = jpeg(1)
        val brokenButMarkerComplete = jpeg(9)
        val large = jpeg(3)
        val bytes = byteArrayOf(4, 5) + small + brokenButMarkerComplete + large
        val tags = mutableListOf<Int>()
        val winner = JpegScanner.largestDecodable(bytes) { all, offset, _ ->
            val tag = all[offset + 6].toInt()
            tags += tag
            when (tag) {
                1 -> JpegScanner.Size(160, 120)
                3 -> JpegScanner.Size(1920, 1080)
                else -> null // Bounds/marker presence alone is not a successful decode.
            }
        }
        assertEquals(listOf(1, 9, 3), tags)
        assertArrayEquals(large, winner)
    }

    @Test fun nestedThumbnailEndDoesNotTruncateOuterJpegAndBothAreCandidates() {
        val inner = jpeg(1)
        val appLength = inner.size + 2
        val outer = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe1.toByte(),
            (appLength ushr 8).toByte(), appLength.toByte()) + inner +
            byteArrayOf(0xff.toByte(), 0xda.toByte(), 0, 2, 8, 0xff.toByte(), 0, 9,
                0xff.toByte(), 0xd0.toByte(), 7, 0xff.toByte(), 0xd9.toByte())
        val segments = JpegScanner.allSegments(outer)
        assertEquals(listOf(JpegScanner.Segment(0, outer.size), JpegScanner.Segment(6, inner.size)), segments)
        assertArrayEquals(outer, JpegScanner.largestDecodable(outer) { _, offset, _ ->
            if (offset == 0) JpegScanner.Size(1920, 1080) else JpegScanner.Size(160, 120)
        })
    }

    @Test fun rejectsIncompleteOversizeAndPathologicalCandidates() {
        assertTrue(JpegScanner.allSegments(jpeg(1).dropLast(1).toByteArray()).isEmpty())
        assertNull(JpegScanner.largestDecodable(jpeg(1)) { _, _, _ -> null })
        assertThrows(IllegalArgumentException::class.java) {
            JpegScanner.allSegments(ByteArray(512 * 1024 + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            JpegScanner.allSegments(ByteArray(257 * 2) { if (it % 2 == 0) 0xff.toByte() else 0xd8.toByte() })
        }
    }
}
