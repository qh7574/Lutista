package cn.alittlecookie.lutista.tether.transfer

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.Uri
import android.os.Process
import androidx.documentfile.provider.DocumentFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cn.alittlecookie.lutista.manager.SettingsManager
import cn.alittlecookie.lutista.tether.objects.CameraObject
import cn.alittlecookie.lutista.util.SafDirectoryAccess
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Runs against a different app UID and Android's real persistable URI grant enforcement. */
@RunWith(AndroidJUnit4::class)
class AndroidTransferSafInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val settings = SettingsManager(context)
    private val grants = mutableListOf<Uri>()
    private var previousMonitor = ""

    @Before fun rememberSettings() = runBlocking {
        previousMonitor = settings.monitorPath.first()
        assertNotEquals(Process.myUid(), context.packageManager.getApplicationInfo(instrumentation.context.packageName, 0).uid)
    }

    @After fun restoreSettings() = runBlocking {
        settings.setMonitorPath(previousMonitor)
        for (tree in grants) runCatching {
            context.contentResolver.releasePersistableUriPermission(tree, READ_WRITE)
        }
        Unit
    }

    @Test fun missingGrantRequiresReselectionInsteadOfPretendingWritable() {
        val tree = grant(0)
        val failure = assertThrows(IOException::class.java) { SafDirectoryAccess.writableDirectory(context, tree.toString()) }
        assertTrue(failure.message.orEmpty().contains("重新选择"))
    }

    @Test fun retainedReadOnlyGrantCannotBeUpgradedWithoutWrite() {
        val tree = grant(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val failure = assertThrows(IOException::class.java) { SafDirectoryAccess.persistReadWrite(context, tree) }
        assertTrue(failure.message.orEmpty().contains("读写权限"))
        assertThrows(IOException::class.java) { SafDirectoryAccess.writableDirectory(context, tree.toString()) }
        assertFalse(context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission })
    }

    @Test fun persistedReadWriteGrantReturnsOriginalWithUnknownCapacityAndRecoversIdempotently() = runBlocking {
        val tree = grant(READ_WRITE)
        SafDirectoryAccess.persistReadWrite(context, tree)
        assertTrue(context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission })
        settings.setMonitorPath(tree.toString())
        val capacityChecks = AtomicInteger()
        val storage = AndroidTransferTargetStorage(context, settings, TetherProcessingSuppression()) {
            capacityChecks.incrementAndGet()
            null
        }
        val bytes = jpeg()
        val key = key()
        val info = info(bytes)
        val target = storage.prepare(key, info, TransferKind.AUTO)
        val result: DownloadedTransfer
        try {
            target.write(bytes, bytes.size)
            target.finish()
            result = target.commit()
        } finally { target.close() }
        assertTrue(capacityChecks.get() > 0)
        assertArrayEquals(bytes, context.contentResolver.openInputStream(Uri.parse(result.targetPath))!!.use { it.readBytes() })
        assertArrayEquals(bytes, File(requireNotNull(result.localPath)).readBytes())
        val record = TransferRecord(key = key, kind = TransferKind.AUTO, handle = info.handle,
            name = info.name, size = info.size, createdAt = 1)
        // New storage instance simulates a restarted transfer coordinator reading its durable journal.
        val restarted = AndroidTransferTargetStorage(context, settings, TetherProcessingSuppression()) { null }
        assertEquals(result, restarted.recover(record))
        assertEquals(result, restarted.recover(record))
        assertEquals(1, DocumentFile.fromTreeUri(context, tree)!!.listFiles().size)
        target.abort() // Abort after commit must not remove a completed original.
        assertArrayEquals(bytes, context.contentResolver.openInputStream(Uri.parse(result.targetPath))!!.use { it.readBytes() })
    }

    @Test fun optionalRelativeDestinationUsesTheExistingTreeGrant() {
        val tree = grant(READ_WRITE)
        SafDirectoryAccess.persistReadWrite(context, tree)
        val nested = SafDirectoryAccess.writableDirectory(context, "$tree|nested/photos")
        assertTrue(nested.isDirectory)
        assertEquals("photos", nested.name)
        assertNotNull(DocumentFile.fromTreeUri(context, tree)!!.findFile("nested")!!.findFile("photos"))
    }

    @Test fun providerWriteFailureRemovesUncommittedVisibleDocumentAndStaging() = runBlocking {
        val tree = grant(READ_WRITE, "fail-")
        SafDirectoryAccess.persistReadWrite(context, tree)
        settings.setMonitorPath(tree.toString())
        val storage = AndroidTransferTargetStorage(context, settings, TetherProcessingSuppression()) { null }
        val bytes = jpeg()
        val key = key()
        val info = info(bytes)
        val target = storage.prepare(key, info, TransferKind.AUTO)
        target.write(bytes, bytes.size)
        target.finish()
        try {
            try {
                target.commit()
                throw AssertionError("Injected provider failure was not observed")
            } catch (expected: IOException) {
                assertTrue(expected.message.orEmpty().isNotBlank())
            }
        } finally { target.abort() }
        assertEquals(0, DocumentFile.fromTreeUri(context, tree)!!.listFiles().size)
        assertFalse(File(context.filesDir, ".tether-sources/AUTO-$key").listFiles().orEmpty().any { it.isFile })
        val record = TransferRecord(key = key, kind = TransferKind.AUTO, handle = info.handle,
            name = info.name, size = info.size, createdAt = 1)
        assertEquals(null, storage.recover(record))
    }

    private fun grant(access: Int, prefix: String = "root-"): Uri {
        val nonce = UUID.randomUUID().toString()
        val root = prefix + nonce
        val ready = CountDownLatch(1)
        val result = AtomicReference<Uri>()
        val providerUid = AtomicInteger(-1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent) {
                if (intent.getStringExtra("nonce") != nonce) return
                result.set(Uri.parse(requireNotNull(intent.getStringExtra("uri"))))
                providerUid.set(intent.getIntExtra("uid", -1))
                ready.countDown()
            }
        }
        context.registerReceiver(receiver, IntentFilter(SafRegressionDocumentsProvider.GRANTED), Context.RECEIVER_EXPORTED)
        try {
            context.startActivity(Intent().setComponent(ComponentName(instrumentation.context.packageName, SafGrantActivity::class.java.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("target", context.packageName)
                .putExtra("root", root).putExtra("nonce", nonce).putExtra("access", access))
            assertTrue("Test provider did not complete the URI grant", ready.await(10, TimeUnit.SECONDS))
            assertTrue(providerUid.get() > 0)
            assertNotEquals("Provider must run under a different UID", Process.myUid(), providerUid.get())
            return requireNotNull(result.get()).also(grants::add)
        } finally { context.unregisterReceiver(receiver) }
    }

    private fun key() = "saf_" + UUID.randomUUID().toString().replace("-", "")
    private fun info(bytes: ByteArray) = CameraObject(7, 1, 0x3801, 0, bytes.size.toLong(), "PHOTO.JPG", "", false)
    private fun jpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888)
        return try { ByteArrayOutputStream().use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream))
            stream.toByteArray()
        } } finally { bitmap.recycle() }
    }

    companion object {
        private const val READ_WRITE = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
