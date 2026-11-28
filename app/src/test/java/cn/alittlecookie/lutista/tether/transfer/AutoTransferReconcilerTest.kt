package cn.alittlecookie.lutista.tether.transfer

import cn.alittlecookie.lutista.tether.objects.CameraObject
import cn.alittlecookie.lutista.tether.objects.CameraSessionKey
import cn.alittlecookie.lutista.tether.objects.UnavailableDirectory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AutoTransferReconcilerTest {
    private val session = CameraSessionKey("camera", 1)
    private fun photo(handle: Long, name: String = "$handle.JPG", parent: Long = 2, storage: Long = 1,
        format: Long = 0x3801, size: Long = 100) = CameraObject(handle, storage, format, parent, size, name, "20261127T160000", false)
    private fun directory(handle: Long, parent: Long = 0) = CameraObject(handle, 1, 0x3001, parent, 0, "$handle", "", true)

    @Test fun missingC108StillAdmitsOnlyNewJpegAndOnlyOnce() = runBlocking {
        val reconciler = AutoTransferReconciler(session)
        val old = photo(3)
        val added = photo(4)
        val received = mutableListOf<Long>()
        reconciler.reconcile(listOf(directory(2), old), automaticEnabled = true) { received += it.handle }
        assertTrue(received.isEmpty())
        repeat(2) {
            reconciler.reconcile(listOf(directory(2), old, added, photo(5, "5.RW2", format = 0x3000),
                photo(6, "6.MOV", format = 0x3000), photo(7, "7.JPG", format = 0x3000)), automaticEnabled = true) { received += it.handle }
        }
        assertEquals(listOf(4L), received)
    }

    @Test fun disabledPeriodAdvancesBaselineWithoutDownloadingOldPhotosOnEnable() = runBlocking {
        val reconciler = AutoTransferReconciler(session)
        val received = mutableListOf<Long>()
        reconciler.reconcile(emptyList(), automaticEnabled = false) { received += it.handle }
        reconciler.reconcile(listOf(photo(3)), automaticEnabled = false) { received += it.handle }
        reconciler.reconcile(listOf(photo(3), photo(4)), automaticEnabled = true) { received += it.handle }
        assertEquals(listOf(4L), received)
    }

    @Test fun cameraDirectoryRolloverAndStorageAreIncludedInNewIdentity() = runBlocking {
        val reconciler = AutoTransferReconciler(session)
        val received = mutableListOf<Pair<Long, Long>>()
        reconciler.reconcile(listOf(directory(2), photo(3)), automaticEnabled = true) {}
        reconciler.reconcile(listOf(directory(2), photo(3), directory(20), photo(21, parent = 20),
            photo(3, storage = 2)), automaticEnabled = true) { received += it.storageId to it.handle }
        assertEquals(listOf(1L to 21L, 2L to 3L), received)
    }

    @Test fun newlyReadableBranchIsBaselinedIncludingNestedOldFiles() = runBlocking {
        val reconciler = AutoTransferReconciler(session)
        val branch = directory(2)
        val received = mutableListOf<Long>()
        reconciler.reconcile(listOf(branch), listOf(UnavailableDirectory(branch, 0x2013)), true) { received += it.handle }
        val recovered = listOf(branch, directory(20, parent = 2), photo(21, parent = 20), photo(3))
        reconciler.reconcile(recovered, automaticEnabled = true) { received += it.handle }
        assertTrue(received.isEmpty())
        reconciler.reconcile(recovered + photo(4), automaticEnabled = true) { received += it.handle }
        assertEquals(listOf(4L), received)
    }

    @Test fun admissionFailureDoesNotSwallowRetryOrRepeatAlreadyAdmittedFiles() = runBlocking {
        val reconciler = AutoTransferReconciler(session)
        reconciler.reconcile(emptyList(), automaticEnabled = true) {}
        val received = mutableListOf<Long>()
        try {
            reconciler.reconcile(listOf(photo(3), photo(4)), automaticEnabled = true) {
                if (it.handle == 4L) throw IOException("metadata not ready")
                received += it.handle
            }
            throw AssertionError("Failure must propagate")
        } catch (_: IOException) { }
        reconciler.reconcile(listOf(photo(3), photo(4)), automaticEnabled = true) { received += it.handle }
        assertEquals(listOf(3L, 4L), received)
    }

    @Test fun reconnectCreatesAFreshBaselineRatherThanRedownloadingExistingFiles() = runBlocking {
        val received = mutableListOf<Long>()
        AutoTransferReconciler(session).reconcile(listOf(photo(3)), automaticEnabled = true) { received += it.handle }
        AutoTransferReconciler(session.copy(connectAt = 2)).reconcile(listOf(photo(3), photo(4)), automaticEnabled = true) { received += it.handle }
        assertTrue(received.isEmpty())
    }
}
