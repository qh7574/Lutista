package cn.alittlecookie.lutista.tether.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TetherUiContractTest {
    @Test
    fun `breakpoint is based on current viewport with a strict 1 point 2 ratio`() {
        assertFalse(TetherLayoutSpec.fromSize(600f, 500f).landscape)
        assertTrue(TetherLayoutSpec.fromSize(601f, 500f).landscape)
        assertFalse(TetherLayoutSpec.fromSize(360f, 800f).landscape)
    }

    @Test
    fun `landscape reserves the approved control widths independently of collapse`() {
        val expanded = TetherLayoutSpec.fromSize(900f, 400f)
        val collapsed = TetherLayoutSpec.fromSize(900f, 400f, sidebarCollapsed = true)
        assertEquals(208, expanded.leftPanelWidthDp)
        assertEquals(48, collapsed.leftPanelWidthDp)
        assertEquals(92, expanded.rightToolbarWidthDp)
        assertEquals(92, collapsed.rightToolbarWidthDp)
        assertEquals(1.4817f, expanded.previewAspectRatio, 0.00001f)
    }

    @Test
    fun `portrait peek respects the 45 percent preview cap on tall windows`() {
        val layout = TetherLayoutSpec.portrait(628f, 800f, 0)
        assertEquals(360f, layout.previewHeightDp, 0.001f)
        assertEquals(56f, layout.panelHeightDp, 0.001f)
        assertEquals(48f, layout.modeHeightDp, 0.001f)
    }

    @Test
    fun `expanded portrait panel overlays preview within its reserved region`() {
        val layout = TetherLayoutSpec.portrait(308f, 480f, 2)
        assertEquals(240f, layout.previewHeightDp, 0.001f)
        assertEquals(260f, layout.panelHeightDp, 0.001f)
        assertEquals(308f, layout.panelHeightDp + layout.modeHeightDp, 0.001f)
    }

    @Test
    fun `very short viewports never produce a negative or overflowing panel`() {
        listOf(0f, 20f, 48f, 90f, 180f).forEach { available ->
            (0..2).forEach { stage ->
                val layout = TetherLayoutSpec.portrait(available, available + 172f, stage)
                assertTrue(layout.panelHeightDp >= 0f)
                assertTrue(layout.previewHeightDp in 0f..available)
                assertTrue(layout.panelHeightDp + layout.modeHeightDp <= available)
            }
        }
    }

    @Test
    fun `missing and blank exposure values always use an em dash`() {
        assertEquals("—", TetherParameterUi().displayText)
        assertEquals("—", TetherParameterUi(value = 100, displayValue = " ").displayText)
    }

    @Test
    fun `parameter editing requires a writable nonempty descriptor and no active write`() {
        val option = TetherParameterOption(100, "100")
        assertFalse(TetherParameterUi(writable = true).enabled)
        assertFalse(TetherParameterUi(options = listOf(option)).enabled)
        assertFalse(TetherParameterUi(options = listOf(option), writable = true, writing = true).enabled)
        assertTrue(TetherParameterUi(options = listOf(option), writable = true).enabled)
    }

    @Test
    fun `live view format selection follows only the explicitly applied camera index`() {
        val options = listOf(
            TetherLiveViewFormatUi(index = 4, label = "档位 5", dimensions = "1120 × 752"),
            TetherLiveViewFormatUi(index = 8, label = "档位 9", dimensions = "1600 × 1072"),
        )
        assertEquals(null, TetherLiveViewUi(formats = options).selectedFormat)
        assertEquals("1600 × 1072", TetherLiveViewUi(formats = options, selectedFormatIndex = 8).selectedFormat?.dimensions)
        assertEquals(null, TetherLiveViewUi(formats = options, selectedFormatIndex = 1).selectedFormat)
    }

    @Test
    fun `resolution availability reports no fake formats and actionable gating reasons`() {
        val options = listOf(
            TetherLiveViewFormatUi(0, "档位 1", "640 × 432"),
            TetherLiveViewFormatUi(1, "档位 2", "1120 × 752"),
        )
        fun state(
            connected: Boolean = true,
            canSwitch: Boolean = true,
            running: Boolean = true,
            switching: Boolean = false,
            recording: Boolean = false,
            formats: List<TetherLiveViewFormatUi> = options,
        ) = TetherUiState(connected = connected, capabilities = TetherCapabilities(canSwitchLiveViewFormat = canSwitch),
            liveView = TetherLiveViewUi(running = running, switchingFormat = switching, isRecording = recording, formats = formats))

        assertEquals("相机未连接", state(connected = false).liveViewFormatUnavailableReason())
        assertEquals("正在切换预览分辨率…", state(switching = true).liveViewFormatUnavailableReason())
        assertEquals("相机未返回可用的预览分辨率", state(formats = emptyList()).liveViewFormatUnavailableReason())
        assertEquals("相机仅提供一种预览分辨率", state(formats = options.take(1)).liveViewFormatUnavailableReason())
        assertEquals("当前相机能力未开放预览分辨率切换", state(canSwitch = false).liveViewFormatUnavailableReason())
        assertEquals("录像中无法切换预览分辨率", state(recording = true).liveViewFormatUnavailableReason())
        assertEquals("实时预览启动后可切换分辨率", state(running = false).liveViewFormatUnavailableReason())
        val ready = state()
        assertEquals("正在读取相机支持的预览分辨率…", ready.copy(liveView = ready.liveView.copy(starting = true)).liveViewFormatUnavailableReason())
        assertEquals("拍摄处理中，暂时无法切换预览分辨率", ready.copy(liveView = ready.liveView.copy(captureBusy = true)).liveViewFormatUnavailableReason())
        assertEquals(null, ready.liveViewFormatUnavailableReason())
    }

}
