package cn.alittlecookie.lutista.tether.profile

import cn.alittlecookie.lutista.tether.protocol.PtpConstants

/**
 * v8 §2.2 的 17 个行为字段。默认值来自 DC-S1 固件 2.4。
 * bulkMaxPacketSize 不在此固化，由当前 USB 端点提供。
 */
data class PtpQuirks(
    val storageIdForObjectQuery: Long = PtpConstants.ALL_STORAGE_IDS,
    val objectHandleFormatMustBeZero: Boolean = true,
    val objectInfoFilenameOffsetCandidates: List<Int> = listOf(52, 48, 44),
    /** 仅诊断提示。不得硬编码此偏移；T06 必须搜索 FF D8（SOI）确定 JPEG 起点。 */
    val liveViewHeaderBytes: Int = 180,
    val liveViewFormatTupleMustBeEchoed: Boolean = true,
    val liveViewFormatSwitchSettleMs: Long = 1_000L,
    val storageInfoUsable: Boolean = false,
    val supportsPropertyDescriptor: Boolean = true,
    val supportsModeWrite: Boolean = false,
    val objectHandleIsSessionScoped: Boolean = true,
    val captureMovieIsToggleInVideoMode: Boolean = true,
    /** 定稿 §5.1 真机值 0x03000011；旧 v8 数位笔误不沿用。 */
    val captureReleaseParam: Long = PtpConstants.Param.CAPTURE_SHUTTER,
    val videoModePos: Long = 5L,
    val jpegFormat: Long = 0x3801L,
    val rawFormat: Long = 0x3800L,
    val movieFormat: Long = 0x300DL,
    val associationFormat: Long = 0x3001L,
)
