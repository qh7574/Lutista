package cn.alittlecookie.lutista.service

import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class TetherOutputAllocatorTest {
    @Test fun sameDirectoryAndBasenameKeepsOriginalAndUsesFreeSuffix() = withFiles { root ->
        val original = File(root, "IMG.jpg").apply { writeText("camera-original") }
        val occupied = File(root, "IMG_1.jpg").apply { writeText("another-original") }
        val allocated = TetherOutputAllocator.allocateFile(
            original, original.path, original.path, explicit = false
        ).use {
            it.stream.write("processed".toByteArray())
            it.file
        }
        assertEquals("IMG_2.jpg", allocated.name)
        assertEquals("processed", allocated.readText())
        assertEquals("camera-original", original.readText())
        assertEquals("another-original", occupied.readText())
    }

    @Test fun explicitCanonicalAliasesOfEitherOriginalAreRejected() = withFiles { root ->
        val stage = File(root, "stage").apply { mkdirs() }
        val output = File(root, "output").apply { mkdirs() }
        val source = File(stage, "IMG.jpg").apply { writeText("readable-original") }
        val original = File(output, "IMG.jpg").apply { writeText("destination-original") }
        for (alias in listOf(File(stage, "../stage/IMG.jpg"), File(output, "../output/IMG.jpg"))) {
            expectIoFailure {
                TetherOutputAllocator.allocateFile(alias, source.path, original.path, explicit = true).use { }
            }
        }
        assertEquals("readable-original", source.readText())
        assertEquals("destination-original", original.readText())
    }

    @Test fun occupiedOrConcurrentlyCreatedOtherFileIsNeverOverwritten() = withFiles { root ->
        val source = File(root, "camera.jpg").apply { writeText("original") }
        val occupied = File(root, "processed.jpg").apply { writeText("existing-photo") }
        expectIoFailure {
            TetherOutputAllocator.allocateFile(occupied, source.path, null, explicit = true).use { }
        }
        val requested = File(root, "raced.jpg")
        val allocated = TetherOutputAllocator.allocateFile(
            requested, source.path, null, explicit = false,
            beforeCreate = { candidate ->
                // Deterministically create a competitor AFTER choosing the candidate, BEFORE open.
                if (candidate.name == requested.name) candidate.writeText("concurrent-photo")
            }
        ).use {
            it.stream.write("new-output".toByteArray())
            it.file
        }
        assertEquals("raced_1.jpg", allocated.name)
        assertEquals("new-output", allocated.readText())
        assertEquals("concurrent-photo", requested.readText())
        assertEquals("existing-photo", occupied.readText())
        assertEquals("original", source.readText())
    }

    @Test fun explicitSymlinkAliasCannotOverwriteOriginal() = withFiles { root ->
        val source = File(root, "original.jpg").apply { writeText("original") }
        val alias = File(root, "alias.jpg")
        try {
            Files.createSymbolicLink(alias.toPath(), source.toPath())
        } catch (e: IOException) {
            assumeNoException("Host does not permit creating symlinks", e)
        } catch (e: UnsupportedOperationException) {
            assumeNoException("Host does not support symlinks", e)
        } catch (e: SecurityException) {
            assumeNoException("Host does not permit creating symlinks", e)
        }
        expectIoFailure {
            TetherOutputAllocator.allocateFile(alias, source.path, source.path, explicit = true).use { }
        }
        assertEquals("original", source.readText())
    }

    @Test fun safRequiresNewDocumentAndRejectsAnOriginalIdentity() {
        expectIoFailure { TetherOutputAllocator.requireNewSafDestination("content://provider/document/original") }
        expectIoFailure { TetherOutputAllocator.requireUnprotectedDocument("provider:original", setOf("provider:original")) }
        TetherOutputAllocator.requireNewSafDestination("")
        TetherOutputAllocator.requireUnprotectedDocument("provider:new", setOf("provider:original"))
    }

    private fun expectIoFailure(block: () -> Unit) {
        try {
            block()
            fail("Must reject a conflicting TETHER destination")
        } catch (_: IOException) { }
    }

    private fun withFiles(block: (File) -> Unit) {
        val root = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val temporary = File(root, ".docs/.tmp").apply { mkdirs() }
        val directory = Files.createTempDirectory(temporary.toPath(), "tether-output-test-").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }
}
