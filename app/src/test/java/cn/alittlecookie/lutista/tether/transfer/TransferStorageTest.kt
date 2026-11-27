package cn.alittlecookie.lutista.tether.transfer

import cn.alittlecookie.lutista.tether.objects.CameraObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TransferStorageTest {
    private fun objectInfo(name: String = "IMG:01.JPG", size: Long) = CameraObject(
        handle = 7, storageId = 1, format = 0x3801, parent = 0, size = size,
        name = name, dateTakenRaw = "2026-11-27T00:00:00", isDir = false
    )

    @Test fun safeNameRejectsTraversalAndCollisionGetsSuffix() = runBlocking {
        val root = Files.createTempDirectory("transfer-storage").toFile()
        try {
            val storage = FileTransferTargetStorage(
                directoryFor = { root }, stateDirectory = root,
                jpegValidator = { true }, usableSpace = { Long.MAX_VALUE }
            )
            val bytes = byteArrayOf(1, 2, 3, 4)
            fun write(name: String): String = runBlocking {
                val target = storage.prepare("abc123", objectInfo(name, bytes.size.toLong()), TransferKind.MANUAL)
                target.write(bytes, bytes.size); target.finish(); target.commit().targetPath
            }
            val first = write("../IMG:01.JPG")
            val second = write("../IMG:01.JPG")
            assertNotEquals(first, second)
            assertTrue(File(first).isFile && File(second).isFile)
            assertTrue(File(first).name.none { it == ':' || it == '/' || it == '\\' })
        } finally { root.deleteRecursively() }
    }

    @Test fun mismatchAbortsOnlyCurrentPartialAndLeavesNoPart() = runBlocking {
        val root = Files.createTempDirectory("transfer-mismatch").toFile()
        try {
            val storage = FileTransferTargetStorage(
                directoryFor = { root }, stateDirectory = root,
                jpegValidator = { true }, usableSpace = { Long.MAX_VALUE }
            )
            val target = storage.prepare("badkey", objectInfo(size = 5), TransferKind.MANUAL)
            target.write(byteArrayOf(1, 2), 2)
            try { target.finish(); fail("size mismatch should fail") } catch (_: Exception) { }
            target.abort()
            assertEquals(emptyList<File>(), root.listFiles()?.filter { it.extension == "part" })
        } finally { root.deleteRecursively() }
    }

    @Test fun suppressionBindingMatchesProviderUriAndUnknownSize() {
        val root = Files.createTempDirectory("suppression").toFile()
        try {
            val suppression = TetherProcessingSuppression()
            suppression.configure(File(root, "journal.properties"))
            val token = suppression.reserve("pending.jpg", null)
            suppression.bind(token, "content://provider/document/77", "renamed.jpg")
            assertTrue(suppression.shouldSuppress("content://provider/document/77", "renamed.jpg", 0))
            suppression.complete(token, "content://provider/document/77", 123)
            assertTrue(suppression.shouldSuppress(size = 123, displayName = "renamed.jpg"))
            suppression.release(token)
            assertFalse(suppression.shouldSuppress("content://provider/document/77", "renamed.jpg", 123))
        } finally { root.deleteRecursively() }
    }
}
