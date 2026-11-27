package cn.alittlecookie.lutista.tether.transfer

import cn.alittlecookie.lutista.tether.objects.CameraObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Properties

/** The same preparation transaction is used for plain output and SAF's durable local original. */
class TransferPreparationTest {
    private fun properties(part: File, saf: Boolean = false) = Properties().apply {
        setProperty("partial", part.absolutePath)
        setProperty(if (saf) "local" else "target", File(part.parentFile, "IMG.JPG").absolutePath)
        setProperty("type", if (saf) "saf" else "file")
        setProperty("size", "4")
    }
    private fun expectFailure(action: () -> Unit) {
        try { action(); fail("injected preparation failure must escape") } catch (_: IOException) { }
    }

    @Test fun failedReservationFailsClosedWithoutCreatingPartialOrLeakingToken() {
        val root = Files.createTempDirectory("transfer-reserve-fail").toFile()
        try {
            val suppression = TetherProcessingSuppression()
            val exclusions = File(root, "exclusions.properties").apply { writeText("") }
            suppression.configure(exclusions)
            assertTrue(exclusions.delete()); assertTrue(exclusions.mkdir()) // Cannot replace a directory with the journal.
            val part = File(root, "IMG.JPG.part"); val journal = File(root, "transfer.properties")
            expectFailure {
                TransferPreparation.open(part, journal, properties(part), suppression, "IMG.JPG", 4)
            }
            assertFalse(part.exists()); assertFalse(journal.exists())
            assertTrue(suppression.shouldSuppress(displayName = "IMG.JPG", size = 4))
            assertFalse(File(root, "exclusions.properties.part").exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun failedJournalWriteHappensBeforeAnyPartialAndRollsBackReservation() {
        for (saf in listOf(false, true)) {
            val root = Files.createTempDirectory("transfer-journal-fail").toFile()
            try {
                val suppression = TetherProcessingSuppression()
                val part = File(root, "IMG.JPG.part"); val journal = File(root, "transfer.properties")
                expectFailure {
                    TransferPreparation.open(part, journal, properties(part, saf), suppression, "IMG.JPG", 4,
                        writeJournal = { file, _ ->
                            assertFalse(part.exists())
                            File(file.parentFile, file.name + ".part").writeText("incomplete journal")
                            throw IOException("journal full")
                        })
                }
                assertTrue(root.listFiles()!!.isEmpty())
                assertFalse(suppression.shouldSuppress(displayName = "IMG.JPG", size = 4))
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun outputOpenFailureRemovesOwnedPartialAndJournalForFileAndSaf() {
        for (saf in listOf(false, true)) {
            val root = Files.createTempDirectory("transfer-open-fail").toFile()
            try {
                val suppression = TetherProcessingSuppression()
                val part = File(root, "IMG.JPG.part"); val journal = File(root, "transfer.properties")
                expectFailure {
                    TransferPreparation.open(part, journal, properties(part, saf), suppression, "IMG.JPG", 4,
                        openOutput = {
                            assertTrue(it.isFile)
                            assertNotNull(FileTransferTargetStorage.readJournal(journal)?.getProperty("reservation"))
                            throw IOException("output open denied")
                        })
                }
                assertTrue(root.listFiles()!!.isEmpty())
                assertFalse(suppression.shouldSuppress(displayName = "IMG.JPG", size = 4))
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun foreignPartialIsRetainedBothBeforePreparationAndDuringAllocationRace() {
        for (preexisting in listOf(false, true)) {
            val root = Files.createTempDirectory("transfer-owned-part").toFile()
            try {
                val suppression = TetherProcessingSuppression()
                val part = File(root, "IMG.JPG.part"); val journal = File(root, "transfer.properties")
                val foreign = byteArrayOf(9, 8, 7)
                if (preexisting) part.writeBytes(foreign)
                expectFailure {
                    TransferPreparation.open(part, journal, properties(part), suppression, "IMG.JPG", 4,
                        writeJournal = { file, values ->
                            FileTransferTargetStorage.writeJournal(file, values)
                            part.writeBytes(foreign) // Another writer wins createNewFile before this transaction.
                        })
                }
                assertArrayEquals(foreign, part.readBytes())
                assertFalse(journal.exists())
                assertFalse(suppression.shouldSuppress(displayName = "IMG.JPG", size = 4))
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun inaccessibleJournalDirectoryFailsBeforeCreatingStoragePartial() = runBlocking {
        val root = Files.createTempDirectory("transfer-journal-dir").toFile()
        try {
            val state = File(root, "state-as-file").apply { writeText("occupied") }
            val storage = FileTransferTargetStorage({ root }, state, { true }, TetherProcessingSuppression(), { 1024 })
            val info = CameraObject(7, 1, 0x3801, 0, 4, "IMG.JPG", "raw", false)
            try { storage.prepare("key", info, TransferKind.MANUAL); fail("journal directory must fail") }
            catch (_: IOException) { }
            assertFalse(root.listFiles()!!.any { it.name.endsWith(".part") })
            assertEquals("occupied", state.readText())
        } finally { root.deleteRecursively() }
    }
}
