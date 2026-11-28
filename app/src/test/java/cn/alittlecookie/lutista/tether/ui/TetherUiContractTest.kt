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
    fun `landscape reserves the approved rail widths`() {
        val spec = TetherLayoutSpec.fromSize(900f, 400f)
        assertEquals(TetherLayoutSpec.LEFT_RAIL_WIDTH_DP, spec.leftRailWidthDp)
        assertEquals(TetherLayoutSpec.RIGHT_RAIL_WIDTH_DP, spec.rightRailWidthDp)
        assertEquals(1.4817f, spec.previewAspectRatio, 0.00001f)
        assertEquals(104, spec.leftRailWidthDp)
        assertEquals(84, spec.rightRailWidthDp)
    }

    @Test
    fun `portrait panel keeps its three approved stages`() {
        assertEquals(listOf(96f, 268f, 372f), TetherPortraitPanelHeights)
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

    @Test
    fun `gallery type mask survives a persistence round trip`() {
        assertEquals(DefaultTetherGalleryTypes, tetherGalleryTypesOf(tetherGalleryTypeMask(DefaultTetherGalleryTypes)))
        assertEquals(setOf(TetherMediaKind.Jpeg), tetherGalleryTypesOf(tetherGalleryTypeMask(setOf(TetherMediaKind.Jpeg))))
        assertEquals(
            setOf(TetherMediaKind.Raw, TetherMediaKind.Video),
            tetherGalleryTypesOf(tetherGalleryTypeMask(setOf(TetherMediaKind.Raw, TetherMediaKind.Video))),
        )
        // 0 表示"从未保存过"，回落到全部类型而不是空集合。
        assertEquals(DefaultTetherGalleryTypes, tetherGalleryTypesOf(0))
        // Unknown 不占位，不会因为相机给出未知类型而让筛选只剩空集。
        assertEquals(0, TetherMediaKind.Unknown.galleryMaskBit())
    }

    @Test
    fun `gallery sort and filter reorder and hide items`() {
        val items = listOf(
            media(handle = 1, kind = TetherMediaKind.Jpeg, name = "B.JPG", size = 5, capturedAt = "2024-05-02 10:00"),
            media(handle = 2, kind = TetherMediaKind.Raw, name = "A.RW2", size = 30, capturedAt = "2024-05-01 09:00"),
            media(handle = 3, kind = TetherMediaKind.Video, name = "C.MOV", size = 12, capturedAt = "2024-05-03 08:00"),
            media(handle = 4, kind = TetherMediaKind.Unknown, name = "D.BIN", size = 1, capturedAt = null),
        )
        val all = DefaultTetherGalleryTypes

        // 新→旧：没有拍摄时间的排在最后。
        assertEquals(
            listOf(3L, 1L, 2L, 4L),
            visibleTetherMedia(items, TetherGallerySort.CapturedDesc, all).map { it.handle },
        )
        // 旧→新：没有拍摄时间的排在最前。
        assertEquals(
            listOf(4L, 2L, 1L, 3L),
            visibleTetherMedia(items, TetherGallerySort.CapturedAsc, all).map { it.handle },
        )
        assertEquals(
            listOf(2L, 1L, 3L, 4L),
            visibleTetherMedia(items, TetherGallerySort.NameAsc, all).map { it.handle },
        )
        assertEquals(
            listOf(2L, 3L, 1L, 4L),
            visibleTetherMedia(items, TetherGallerySort.SizeDesc, all).map { it.handle },
        )
        // 未知类型始终保留；被关掉的类型隐藏。
        assertEquals(
            listOf(2L, 4L),
            visibleTetherMedia(items, TetherGallerySort.SizeDesc, setOf(TetherMediaKind.Raw)).map { it.handle },
        )
    }

    private fun media(handle: Long, kind: TetherMediaKind, name: String, size: Long, capturedAt: String?) =
        TetherMediaUi(
            handle = handle,
            name = name,
            kind = kind,
            storageId = 1L,
            directoryHandle = 2L,
            sizeBytes = size,
            capturedAt = capturedAt,
        )

}
