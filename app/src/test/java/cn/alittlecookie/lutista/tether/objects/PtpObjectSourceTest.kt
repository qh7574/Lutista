package cn.alittlecookie.lutista.tether.objects

import cn.alittlecookie.lutista.tether.profile.PtpQuirks
import cn.alittlecookie.lutista.tether.protocol.PtpConstants
import cn.alittlecookie.lutista.tether.transport.PtpResponse
import java.io.IOException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PtpObjectSourceTest {
    private val root = 0xFFFFFFFFL
    private fun source(access: ObjectAccess, seed: List<Long>? = null) = PtpObjectSource(
        access, PtpQuirks(), CameraSessionKey("serial", 123), seed,
        decodeJpeg = { bytes, offset, _ ->
            val tag = bytes[offset + 6].toInt()
            if (tag == 9) null else JpegScanner.Size(tag * 160, tag * 120)
        },
    )

    @Test fun rootSeedIsOnlyRootAndStillDrillsDirectoriesWithoutParentZero() = runBlocking {
        val access = FakeObjectAccess().apply {
            children = mapOf(10L to listOf(10, 20, 20, 21), 21L to listOf(10, 20))
            infos = mapOf(
                10L to objectInfo(format = 0x3001, parent = 0),
                20L to objectInfo(parent = 10),
                21L to objectInfo(format = 0x3001, parent = 10),
            )
            // Even inconsistent metadata that turns an already seen root into a child cannot loop.
            infoProvider = { handle -> when (handle) {
                10L -> objectInfo(format = 0x3001, parent = if (queriedParents.lastOrNull() == 21L) 21 else 0)
                20L -> objectInfo(parent = queriedParents.lastOrNull() ?: 10)
                else -> infos.getValue(handle)
            } }
        }
        val pages = source(access, listOf(10, 10)).listTree().toList()
        assertEquals(listOf(10L, 21L), access.queriedParents)
        assertFalse(access.queriedParents.contains(0L))
        assertEquals(listOf(10L, 21L, 20L), pages.filterIsInstance<ObjectPage.Batch>().flatMap { it.items }.map { it.handle })
        assertEquals(ObjectPage.Complete(3, mapOf(1L to 3)), pages.last())
    }

    @Test fun defaultEnumerationStartsWithRootAndYieldsPagesOfAtMostFifty() = runBlocking {
        val handles = (1L..123L).toList()
        val access = FakeObjectAccess().apply {
            children = mapOf(root to handles)
            infos = handles.associateWith { objectInfo(name = "P$it.JPG") }
        }
        val pages = source(access).listTree().toList()
        assertEquals(listOf(root), access.queriedParents)
        val batches = pages.filterIsInstance<ObjectPage.Batch>()
        assertEquals(listOf(50, 50, 23), batches.map { it.items.size })
        assertEquals(handles.reversed(), batches.flatMap { it.items }.map { it.handle })
        assertEquals((0L..122L).toList(), batches.flatMap { it.items }.map { it.seq })
    }

    @Test fun deduplicatesByStorageAndHandleRatherThanHandleAlone() = runBlocking {
        val access = FakeObjectAccess().apply {
            children = mapOf(root to listOf(100, 200), 100L to listOf(1, 1), 200L to listOf(1, 1))
            infoProvider = { handle -> when (handle) {
                100L -> objectInfo(storage = 1, format = 0x3001)
                200L -> objectInfo(storage = 2, format = 0x3001)
                else -> {
                    val parent = queriedParents.last()
                    objectInfo(storage = if (parent == 100L) 1 else 2, parent = parent)
                }
            } }
        }
        val objects = source(access).listTree().toList().filterIsInstance<ObjectPage.Batch>().flatMap { it.items }
        assertEquals(setOf(ObjectIdentity(1, 1), ObjectIdentity(2, 1)), objects.filter { it.handle == 1L }.map { ObjectIdentity(it.storageId, it.handle) }.toSet())
        assertEquals(4, objects.size)
    }

    @Test fun obsoleteParentZeroIsRejectedBeforeAnyOperation() = runBlocking {
        val access = FakeObjectAccess()
        try {
            source(access).listChildren(0)
            fail("parent=0 must never be sent")
        } catch (_: IllegalArgumentException) { }
        assertTrue(access.queriedParents.isEmpty())
    }

    @Test fun rawWindowChoosesLargestCandidateAndFallsBackOnlyForRecoverableFailures() = runBlocking {
        val access = FakeObjectAccess().apply {
            infos = mapOf(1L to objectInfo(format = 0x3800))
            head = jpeg(1) + jpeg(9) + jpeg(3)
        }
        assertArrayEquals(jpeg(3), source(access).fetchRawPreview(1))
        assertEquals(512L * 1024, access.partialLength)
        assertEquals(0, access.thumbReads)
        access.partialCode = 0x2005 // OperationNotSupported
        assertArrayEquals(access.thumbnail, source(access).fetchRawPreview(1))
        access.partialFailure = IOException("USB disconnected")
        try {
            source(access).fetchRawPreview(1)
            fail("Transport error must propagate")
        } catch (error: IOException) {
            assertEquals("USB disconnected", error.message)
        }
        assertEquals(1, access.thumbReads)
    }

    @Test fun thumbnailLimitAndRawFormatRulesAreEnforced() = runBlocking {
        val access = FakeObjectAccess().apply {
            thumbnail = ByteArray(4 * 1024 * 1024 + 1)
            infos = mapOf(1L to objectInfo(format = 0x300D))
        }
        try { source(access).fetchThumb(1); fail("Oversized thumbnail accepted") } catch (_: IllegalStateException) { }
        try { source(access).fetchRawPreview(1); fail("MOV treated as RAW") } catch (_: IllegalArgumentException) { }
        assertEquals(0L, access.partialLength)
    }

    @Test fun largeDownloadPassesReusableChunksDirectlyToSinkWithoutAggregation() = runBlocking {
        val chunk = ByteArray(64 * 1024)
        val chunks = 100
        val access = FakeObjectAccess().apply {
            infos = mapOf(1L to objectInfo(size = chunk.size.toLong() * chunks, format = 0x300D))
            streamAction = { sink -> repeat(chunks) { sink(chunk, chunk.size) }; PtpResponse(PtpConstants.Rc.OK) }
        }
        var count = 0
        val progress = mutableListOf<Long>()
        val bytes = source(access).streamObject(1, { actual, length ->
            assertSame(chunk, actual); assertEquals(chunk.size, length); count++
        }, progress::add)
        assertEquals(100, count)
        assertEquals(6_553_600L, bytes)
        assertEquals(bytes, progress.last())
        assertEquals(100, progress.size)
    }

    @Test fun inMemoryFullPreviewRejectsBigJpegBeforeStreamingAndDetectsLengthMismatch() = runBlocking {
        val access = FakeObjectAccess().apply { infos = mapOf(1L to objectInfo(size = 4L * 1024 * 1024 + 1)) }
        try { source(access).fetchObject(1); fail("Big JPEG accepted into memory") } catch (_: IllegalArgumentException) { }
        assertEquals(0, access.streamCalls)
        access.infos = mapOf(1L to objectInfo(size = 4))
        try { source(access).fetchObject(1); fail("Wrong object length accepted") } catch (_: IllegalStateException) { }
    }
}
