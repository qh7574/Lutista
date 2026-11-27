package cn.alittlecookie.lutista.tether.transfer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class TetherProcessingSuppressionTest {
    @Test fun configureDoesNotRewriteOrCreateAnEmptyJournal() {
        val root = Files.createTempDirectory("suppression-config").toFile()
        try {
            val journal = File(root, "exclusions.properties")
            val original = "existing journal bytes\n".toByteArray()
            journal.writeBytes(original)
            val suppression = TetherProcessingSuppression()
            suppression.configure(journal)
            assertTrue(suppression.isAvailable())
            assertTrue(original.contentEquals(journal.readBytes()))

            val missing = File(root, "empty.properties")
            TetherProcessingSuppression().also {
                it.configure(missing)
                assertTrue(it.isAvailable())
                assertFalse(missing.exists())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun invalidJournalAtStartupDoesNotThrowAndFailsClosed() {
        val root = Files.createTempDirectory("suppression-invalid-journal").toFile()
        try {
            val journal = File(root, "exclusions.properties")
            assertTrue(journal.mkdir())
            val suppression = TetherProcessingSuppression()
            suppression.configure(journal)
            assertFalse(suppression.isAvailable())
            assertNotNull(suppression.availabilityError())
            assertTrue(suppression.shouldSuppress(path = "/camera/DCIM/IMG.JPG", displayName = "IMG.JPG", size = 123))
            assertThrows(IOException::class.java) { suppression.reserve("IMG.JPG", 123) }
        } finally { root.deleteRecursively() }
    }

    @Test fun persistenceFailureAfterStartupMakesEveryMonitorCandidateFailClosed() {
        val root = Files.createTempDirectory("suppression-persist-failure").toFile()
        try {
            val journal = File(root, "exclusions.properties")
            val suppression = TetherProcessingSuppression()
            suppression.configure(journal)
            assertTrue(suppression.isAvailable())
            assertTrue(journal.mkdir())

            assertThrows(IOException::class.java) { suppression.reserve("IMG.JPG", 123) }
            assertFalse(File(root, "exclusions.properties.part").exists())
            assertFalse(suppression.isAvailable())
            assertTrue(suppression.shouldSuppress(path = "/camera/DCIM/IMG.JPG"))
        } finally { root.deleteRecursively() }
    }
}
