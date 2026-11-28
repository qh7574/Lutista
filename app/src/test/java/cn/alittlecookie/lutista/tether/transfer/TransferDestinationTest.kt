package cn.alittlecookie.lutista.tether.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TransferDestinationTest {
    @Test fun originalsUseTheExactHomeMonitorFolder() {
        val monitor = "/storage/emulated/0/DCIM/input"
        assertEquals(monitor, TransferDestination.resolve(monitor))
    }

    @Test fun safOriginalUsesExactHomeTreeWithoutDateSubdirectories() {
        val monitor = "content://com.android.externalstorage.documents/tree/primary%3ADCIM%2Finput"
        assertEquals(monitor, TransferDestination.resolve(monitor))
    }

    @Test fun relativeHomeMonitorLocationIsPreserved() {
        val monitor = "content://provider/tree/input|originals/camera"
        assertEquals(monitor, TransferDestination.resolve(monitor))
    }

    @Test fun missingMonitorCannotRedirectDownloadsToOutputOrPrivateStorage() {
        for (monitor in listOf("", "  ")) {
            try {
                TransferDestination.resolve(monitor)
                fail("Every camera original must require the Home monitor folder")
            } catch (missing: IllegalStateException) {
                assertTrue(missing.message.orEmpty().contains("主页面"))
            }
        }
    }
}
