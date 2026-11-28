package cn.alittlecookie.lutista.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 滤镜直出产物绝不能落在输入文件夹里，否则主页监控会把它当成新照片再处理一遍。 */
class TetherOutputPlacementTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun configuredOutputFolderWinsWhenItDiffersFromTheMonitorFolder() {
        val monitor = temporary.newFolder("monitor").path
        val output = temporary.newFolder("output").path
        val defaultDir = temporary.newFolder("default").path
        assertEquals(TetherOutputPlacement.Placement(output), TetherOutputPlacement.resolve(output, monitor, defaultDir))
    }

    @Test fun configuredOutputFolderEqualToMonitorFallsBackToTheDefaultOutputFolder() {
        val monitor = temporary.newFolder("monitor").path
        val defaultDir = temporary.newFolder("default").path
        val placement = TetherOutputPlacement.resolve(monitor, monitor, defaultDir)
        assertEquals(defaultDir, placement.base)
        assertEquals("配置的输出文件夹就是输入文件夹", placement.reason)
    }

    @Test fun defaultOutputFolderEqualToMonitorAllocatesTheDedicatedSubfolder() {
        val monitor = temporary.newFolder("monitor").path
        val placement = TetherOutputPlacement.resolve("", monitor, monitor)
        assertEquals(File(monitor, TetherOutputPlacement.FALLBACK_SUBDIR).path, placement.base)
        assertEquals("默认输出文件夹就是输入文件夹", placement.reason)
    }

    @Test fun blankMonitorKeepsTheConfiguredOutputFolder() {
        val output = temporary.newFolder("output").path
        val defaultDir = temporary.newFolder("default").path
        assertEquals(output, TetherOutputPlacement.resolve(output, "", defaultDir).base)
        assertEquals(defaultDir, TetherOutputPlacement.resolve("", "", defaultDir).base)
    }

    @Test fun safTreeAndFileSystemPathAreNeverTreatedAsTheSameLocation() {
        val monitor = "content://com.android.externalstorage.documents/tree/primary%3APictures"
        val output = temporary.newFolder("output").path
        val placement = TetherOutputPlacement.resolve(output, monitor, temporary.newFolder("default").path)
        assertEquals(output, placement.base)
        assertNull(placement.reason)
    }
}
