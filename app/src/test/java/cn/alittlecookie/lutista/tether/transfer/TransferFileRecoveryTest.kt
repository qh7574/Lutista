package cn.alittlecookie.lutista.tether.transfer

import cn.alittlecookie.lutista.tether.objects.CameraObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class TransferFileRecoveryTest {
    private fun info(name: String = "IMG.JPG", size: Long = 4) = CameraObject(7, 1, 0x3801, 0, size, name, "raw", false)
    private fun row(key: String = "key", state: TransferState = TransferState.RUNNING, path: String = "") =
        TransferRecord(key = key, kind = TransferKind.MANUAL, handle = 7, name = "IMG.JPG", size = 4,
            state = state, targetPath = path, createdAt = 1L)
    private fun storage(root: File, validator: (File) -> Boolean = { true }, available: Long = Long.MAX_VALUE) =
        FileTransferTargetStorage({ root }, root, validator, TetherProcessingSuppression(), { available })

    @Test fun crashAfterCommitBeforeDatabaseWriteRecoversOriginalExactly() = runBlocking {
        val root = Files.createTempDirectory("transfer-commit").toFile()
        try {
            val first = storage(root); val target = first.prepare("key", info(), TransferKind.MANUAL)
            target.write(byteArrayOf(9, 8, 7, 6), 4); target.finish()
            val original = target.commit(); target.close()
            val restored = storage(root).recover(row())!!
            assertEquals(original, restored)
            assertArrayEquals(byteArrayOf(9, 8, 7, 6), File(restored.localPath!!).readBytes())
            assertEquals(1, root.listFiles()!!.count { it.extension.equals("jpg", true) })
        } finally { root.deleteRecursively() }
    }

    @Test fun incompletePartialIsDeletedAndRestartsAtZero() = runBlocking {
        val root = Files.createTempDirectory("transfer-partial").toFile()
        try {
            val first = storage(root); val target = first.prepare("key", info(), TransferKind.MANUAL)
            target.write(byteArrayOf(9, 8), 2); target.close() // Simulate process death, no abort.
            val restored = storage(root)
            assertNull(restored.recover(row()))
            assertFalse(root.listFiles()!!.any { it.name.endsWith(".part") })
            val restarted = restored.prepare("key", info(), TransferKind.MANUAL)
            restarted.write(byteArrayOf(1, 2, 3, 4), 4); restarted.finish()
            val result = restarted.commit(); restarted.close()
            assertArrayEquals(byteArrayOf(1, 2, 3, 4), File(result.localPath!!).readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun unrelatedTargetCreatedWhilePartialExistsIsNeverRecoveredOrDeleted() = runBlocking {
        val root = Files.createTempDirectory("transfer-unrelated").toFile()
        try {
            val first = storage(root); val target = first.prepare("key", info(), TransferKind.MANUAL)
            target.write(byteArrayOf(9, 8), 2); target.close()
            val unrelated = File(target.destinationHint); unrelated.writeBytes(byteArrayOf(5, 5, 5, 5))
            assertNull(storage(root).recover(row(path = unrelated.absolutePath)))
            assertArrayEquals(byteArrayOf(5, 5, 5, 5), unrelated.readBytes())
            assertFalse(root.listFiles()!!.any { it.name.endsWith(".part") })
        } finally { root.deleteRecursively() }
    }

    @Test fun occupiedNameCannotBeOverwrittenAndAbortRemovesOnlyPart() = runBlocking {
        val root = Files.createTempDirectory("transfer-race").toFile()
        try {
            val target = storage(root).prepare("key", info(), TransferKind.MANUAL)
            target.write(byteArrayOf(1, 2, 3, 4), 4); target.finish()
            val occupied = File(target.destinationHint); occupied.writeBytes(byteArrayOf(6, 6, 6, 6))
            try { target.commit(); fail("occupied output must fail") } catch (_: IOException) { }
            target.abort(); target.close()
            assertArrayEquals(byteArrayOf(6, 6, 6, 6), occupied.readBytes())
            assertFalse(root.listFiles()!!.any { it.name.endsWith(".part") })
        } finally { root.deleteRecursively() }
    }

    @Test fun invalidJpegCannotCommitEvenWhenDeclaredSizeMatches() = runBlocking {
        val root = Files.createTempDirectory("transfer-invalid-jpeg").toFile()
        try {
            val target = storage(root, validator = { false }).prepare("key", info(), TransferKind.MANUAL)
            target.write(byteArrayOf(1, 2, 3, 4), 4)
            try { target.finish(); fail("decoder rejection must fail") } catch (_: IOException) { }
            target.abort(); target.close()
            assertFalse(root.listFiles()!!.any { it.name.endsWith(".JPG") || it.name.endsWith(".part") })
        } finally { root.deleteRecursively() }
    }

    @Test fun exactHeadroomAndOverflowAreRejectedBeforeCreatingAnyPartial() = runBlocking {
        val root = Files.createTempDirectory("transfer-space").toFile()
        try {
            try { storage(root, available = 5).prepare("key", info(), TransferKind.MANUAL); fail("10% ceiling margin must fit strictly") }
            catch (_: IOException) { }
            try { FileTransferTargetStorage.checkSpace(Long.MAX_VALUE, Long.MAX_VALUE); fail("overflow must reject") }
            catch (_: IOException) { }
            assertTrue(root.listFiles()!!.isEmpty())
            FileTransferTargetStorage.checkSpace(4, 6)
        } finally { root.deleteRecursively() }
    }

    @Test fun filenameAndPersistentExclusionsSurviveHostileCameraNamesAndRestart() {
        val root = Files.createTempDirectory("transfer-name").toFile()
        try {
            assertEquals("_CON.JPG", FileTransferTargetStorage.safeFilename("../../CON.JPG"))
            val raw = "folder" + 92.toChar() + ".." + 92.toChar() + "a:b?c.JPG"
            val safe = FileTransferTargetStorage.safeFilename(raw)
            assertEquals("a_b_c.JPG", safe)
            assertTrue(FileTransferTargetStorage.safeFilename("图".repeat(100) + ".JPG").toByteArray().size <= 180)
            val file = File(root, "exclusions.properties")
            val first = TetherProcessingSuppression(); first.configure(file)
            val token = first.reserve("IMG.JPG", 4, "/monitor/IMG.JPG")
            first.complete(token, "/monitor/IMG.JPG", 4)
            val restored = TetherProcessingSuppression(); restored.configure(file)
            repeat(3) { assertTrue(restored.shouldSuppress("/monitor/IMG.JPG", "IMG.JPG", 4)) }
            assertTrue(restored.shouldSuppress("/app/.tether-sources/a/IMG.JPG"))
            assertTrue(restored.shouldSuppress("/monitor/IMG.JPG.part"))
            assertFalse(restored.shouldSuppress(displayName = "IMG.JPG", size = 5))
        } finally { root.deleteRecursively() }
    }
}
