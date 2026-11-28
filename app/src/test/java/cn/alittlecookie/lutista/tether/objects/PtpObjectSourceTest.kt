package cn.alittlecookie.lutista.tether.objects

import cn.alittlecookie.lutista.tether.profile.PtpQuirks
import cn.alittlecookie.lutista.tether.protocol.PtpConstants
import cn.alittlecookie.lutista.tether.session.PtpData
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

    @Test fun recursiveDualStorageTreeKeepsJpegsAndRejectsWrongStorageOrParent() = runBlocking {
        val access = FakeObjectAccess().apply {
            children = mapOf(
                root to listOf(10, 20), 10L to listOf(11, 21), 20L to listOf(21, 11),
                11L to listOf(12, 22, 13), 21L to listOf(22, 12),
            )
            infos = mapOf(
                10L to objectInfo(storage = 0x00010000, format = 0x3001, parent = 0, name = "DCIM"),
                20L to objectInfo(storage = 0x00020000, format = 0x3001, parent = root, name = "DCIM"),
                11L to objectInfo(storage = 0x00010000, format = 0x3001, parent = 10, name = "100PHOTO"),
                21L to objectInfo(storage = 0x00020000, format = 0x3001, parent = 20, name = "100PHOTO"),
                12L to objectInfo(storage = 0x00010000, parent = 11, name = "CARD1.JPG").apply { this[34] = 24 },
                22L to objectInfo(storage = 0x00020000, parent = 21, name = "CARD2.JPG").apply { this[34] = 24 },
                13L to objectInfo(storage = 0x00010000, parent = 99, name = "WRONG_PARENT.JPG"),
            )
        }
        val pages = source(access).listTree().toList()
        val objects = pages.filterIsInstance<ObjectPage.Batch>().flatMap { it.items }
        assertEquals(listOf(root, 20L, 10L, 21L, 11L), access.queriedParents)
        assertFalse(access.queriedParents.contains(0L))
        assertEquals(setOf(10L, 20L, 11L, 21L, 12L, 22L), objects.map { it.handle }.toSet())
        assertEquals(mapOf(12L to 11L, 22L to 21L), objects.filterNot { it.isDir }.associate { it.handle to it.parent })
        assertEquals(ObjectPage.Complete(6, mapOf(0x00020000L to 3, 0x00010000L to 3)), pages.last())

        val camera = source(access)
        assertEquals(listOf(12L), camera.listChildren(11, 0x00010000).map { it.handle })
        assertEquals(listOf(22L), camera.listChildren(21, 0x00020000).map { it.handle })
    }

    @Test fun documentedS1UnavailableSiblingsDoNotHideReadable102Folder() = runBlocking {
        // Condensed TV-02b_20261126_DC-S1 trace: real handles, names, sizes and rejected branches.
        // ObjectInfo bytes below are synthetic; no raw ObjectInfo capture was present in that evidence.
        val dcim1 = 0x11000000L
        val dcim2 = 0x51000000L
        val unreadable1 = 0x11194000L
        val readable = 0x11198000L
        val unreadable2 = 0x51194000L
        val jpeg1 = 0x12199F89L
        val jpeg2 = 0x12199F91L
        val movie = 0x13199F93L
        val directories = listOf(unreadable1, readable, unreadable2)
        val access = FakeObjectAccess().apply {
            children = mapOf(root to listOf(dcim1, dcim2), dcim1 to directories, dcim2 to directories,
                readable to listOf(jpeg1, jpeg2, movie))
            infos = mapOf(
                dcim1 to objectInfo(storage = 0x00010001, format = 0x3001, name = "DCIM"),
                dcim2 to objectInfo(storage = 0x00020001, format = 0x3001, name = "DCIM"),
                unreadable1 to objectInfo(storage = 0x00010001, format = 0x3001, parent = dcim1, name = "101_MRRC"),
                readable to objectInfo(storage = 0x00010001, format = 0x3001, parent = dcim1, name = "102_MRRC"),
                unreadable2 to objectInfo(storage = 0x00020001, format = 0x3001, parent = dcim2, name = "101_MRRC"),
                jpeg1 to objectInfo(storage = 0x00010001, parent = readable, size = 7417856, name = "P1028073.JPG"),
                jpeg2 to objectInfo(storage = 0x00010001, parent = readable, size = 11463168, name = "P1028081.JPG"),
                movie to objectInfo(storage = 0x00010001, format = 0x300D, parent = readable,
                    size = 87681974, name = "P1028083.MOV"),
            )
            handlesProvider = { parent ->
                if (parent == unreadable1 || parent == unreadable2) {
                    PtpData(PtpResponse(PtpConstants.Rc.STORE_NOT_AVAILABLE), byteArrayOf())
                } else okData(objectHandles(*children[parent].orEmpty().toLongArray()))
            }
        }
        val pages = source(access).listTree().toList()
        val items = pages.filterIsInstance<ObjectPage.Batch>().flatMap { it.items }
        assertEquals(listOf(root, dcim2, dcim1, unreadable2, readable, unreadable1), access.queriedParents)
        assertFalse(access.queriedParents.contains(0L))
        assertEquals(setOf("P1028073.JPG", "P1028081.JPG", "P1028083.MOV"),
            items.filterNot { it.isDir }.map { it.name }.toSet())
        assertEquals(8, items.size)
        assertEquals(8, items.map { ObjectIdentity(it.storageId, it.handle) }.distinct().size)
        val complete = pages.last() as ObjectPage.Complete
        assertEquals(8, complete.total)
        assertEquals(mapOf(0x00010001L to 6, 0x00020001L to 2), complete.byStorage)
        assertEquals(setOf(unreadable1, unreadable2), complete.unavailableDirectories.map { it.directory.handle }.toSet())
        assertTrue(complete.unavailableDirectories.all {
            it.directory.isDir && it.directory.name == "101_MRRC" && it.responseCode == PtpConstants.Rc.STORE_NOT_AVAILABLE
        })
    }

    @Test fun storeNotAvailableAtRootIsStillAnEnumerationFailure() = runBlocking {
        val access = FakeObjectAccess().apply {
            handlesProvider = { PtpData(PtpResponse(PtpConstants.Rc.STORE_NOT_AVAILABLE), byteArrayOf()) }
        }
        try {
            source(access).listTree().toList()
            fail("A rejected root query must not appear to be an empty successful gallery")
        } catch (failure: CameraObjectResponseException) {
            assertEquals(PtpConstants.Rc.STORE_NOT_AVAILABLE, failure.responseCode)
        }
        assertEquals(listOf(root), access.queriedParents)
    }

    @Test fun explicitChildQueryStillReportsStoreNotAvailable() = runBlocking {
        val access = FakeObjectAccess().apply {
            handlesProvider = { PtpData(PtpResponse(PtpConstants.Rc.STORE_NOT_AVAILABLE), byteArrayOf()) }
        }
        try {
            source(access).listChildren(0x51194000)
            fail("An explicit unreadable directory must not look empty")
        } catch (failure: CameraObjectResponseException) {
            assertEquals(PtpConstants.Rc.STORE_NOT_AVAILABLE, failure.responseCode)
        }
        assertEquals(listOf(0x51194000L), access.queriedParents)
    }

    @Test fun otherDirectoryResponseCodesStillFailWithoutParentZeroFallback() = runBlocking {
        for (code in listOf(0x2002, 0x2006, 0x2008, 0x2009)) {
            val access = FakeObjectAccess().apply {
                infos = mapOf(10L to objectInfo(format = 0x3001, name = "DCIM"))
                handlesProvider = { parent ->
                    if (parent == root) okData(objectHandles(10)) else PtpData(PtpResponse(code), byteArrayOf())
                }
            }
            try {
                source(access).listTree().toList()
                fail("Unexpected directory response 0x${code.toString(16)} was hidden")
            } catch (failure: CameraObjectResponseException) {
                assertEquals(code, failure.responseCode)
            }
            assertEquals(listOf(root, 10L), access.queriedParents)
        }
    }

    @Test fun directoryTransportFailurePropagatesUnchanged() = runBlocking {
        val disconnected = IOException("USB disconnected while listing directory")
        val access = FakeObjectAccess().apply {
            infos = mapOf(10L to objectInfo(format = 0x3001, name = "DCIM"))
            handlesProvider = { parent ->
                if (parent == root) okData(objectHandles(10)) else throw disconnected
            }
        }
        try {
            source(access).listTree().toList()
            fail("USB failure must not be skipped as an unreadable directory")
        } catch (failure: IOException) {
            assertSame(disconnected, failure)
        }
        assertEquals(listOf(root, 10L), access.queriedParents)
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

    @Test fun embeddedPreviewWorksForJpegObjectsWithoutWholeFileDownload() = runBlocking {
        // 真机 logs_1795841499743：全屏预览曾拉整张 9 MB 原图，返回键取消后相机拒收命令。
        val access = FakeObjectAccess().apply {
            infos = mapOf(1L to objectInfo(format = 0x3801))
            head = jpeg(1) + jpeg(7) + jpeg(2)
        }
        assertArrayEquals(jpeg(7), source(access).fetchEmbeddedPreview(1))
        assertEquals(512L * 1024, access.partialLength)
        assertEquals(0, access.thumbReads)
        assertEquals(0, access.streamCalls)
    }

    @Test fun rangeReadsUseTheRequestedOffsetForSparseVideoDecoding() = runBlocking {
        val access = FakeObjectAccess().apply { head = jpeg(7) }
        val bytes = source(access).fetchRange(1, 12_345L, 64 * 1024)
        assertArrayEquals(jpeg(7), bytes)
        assertEquals(12_345L, access.partialOffset)
        assertEquals(64L * 1024, access.partialLength)
        try { source(access).fetchRange(1, 0, 512 * 1024 + 1); fail("Oversized range accepted") }
        catch (_: IllegalArgumentException) { }
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
