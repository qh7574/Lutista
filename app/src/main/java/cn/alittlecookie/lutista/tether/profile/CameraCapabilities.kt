package cn.alittlecookie.lutista.tether.profile

import cn.alittlecookie.lutista.tether.protocol.Availability
import cn.alittlecookie.lutista.tether.protocol.Capability
import cn.alittlecookie.lutista.tether.protocol.CapabilityEvidence

/** v8 §1.1 的稳定特性名；缓存以 name 编码。 */
enum class Feature(val uiName: String) {
    ObjectBrowse("机内浏览"),
    ObjectDownload("原图下载"),
    LiveView("实时取景"),
    LiveViewFormatSwitch("取景档位切换"),
    ConfigRead("参数读取"),
    ConfigWrite("参数调整"),
    CapturePhoto("遥控拍照"),
    CaptureMovie("遥控录影"),
    PhotoStyle("图片格调"),
    ModeRead("模式读取"),
    Battery("电量"),
    Thumbnail("缩略图"),
    RawPreview("RAW 预览"),
    LiveViewFilterPreview("取景滤镜"),
}

enum class UsableShape { Full, LiveViewNoConfig, BrowseOnly, LiveViewOnly, Nothing, Unrecognized }

/** 不依赖 Android 的能力快照；没有记录的特性恒为 Unknown(NOT_PROBED)。 */
data class CameraCapabilities(
    val model: String,
    val serial: String,
    val firmware: String,
    val profileId: String,
    val probedAt: Long,
    val totalProbeMs: Long,
    private val map: Map<Feature, Capability>,
    /** 探测当时的模式，仅诊断；不参与缓存或 cache key，转盘变化须手动重探。 */
    val probedModePos: Long? = null,
) {
    operator fun get(f: Feature): Capability =
        map[f] ?: Capability.unknown(CapabilityEvidence.NOT_PROBED)

    val unsupported: Set<Feature>
        get() = Feature.entries.filterTo(linkedSetOf()) { this[it].availability == Availability.Unsupported }
    val unknown: Set<Feature>
        get() = Feature.entries.filterTo(linkedSetOf()) { this[it].availability == Availability.Unknown }

    /** 只把明确 Supported 的主链路列为已确认可用，Unknown 保持可试语义。 */
    val usableShape: UsableShape
        get() = when {
            this[Feature.ObjectBrowse].availability == Availability.Supported &&
                this[Feature.LiveView].availability == Availability.Supported ->
                if (this[Feature.ConfigRead].availability == Availability.Supported) UsableShape.Full
                else UsableShape.LiveViewNoConfig
            this[Feature.ObjectBrowse].availability == Availability.Supported -> UsableShape.BrowseOnly
            this[Feature.LiveView].availability == Availability.Supported -> UsableShape.LiveViewOnly
            this[Feature.ObjectBrowse].availability == Availability.Unsupported &&
                this[Feature.LiveView].availability == Availability.Unsupported -> UsableShape.Nothing
            else -> UsableShape.Unrecognized
        }

    fun with(f: Feature, c: Capability): CameraCapabilities = copy(map = map + (f to c))

    /** debug UnknownPtp 覆盖的纯派生；不会保留任何可写断言。 */
    fun asUnknown(detail: String? = null): CameraCapabilities = copy(
        map = Feature.entries.associateWith { Capability.unknown(CapabilityEvidence.INDETERMINATE, detail) },
    )

    companion object {
        fun empty(): CameraCapabilities = CameraCapabilities("", "", "", "", 0L, 0L, emptyMap())
    }
}
