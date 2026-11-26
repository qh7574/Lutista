package cn.alittlecookie.lutista.tether.profile

import cn.alittlecookie.lutista.tether.transport.PtpDeviceCaps

/** 匹配阶段由注册表排序，Profile 自身的 match 只判断是否命中。 */
enum class ProfileMatchLevel { Exact, ModelPrefix, BrandDefault, Generic }

interface CameraProfile {
    val id: String
    val displayName: String
    fun match(vid: Int, pid: Int, caps: PtpDeviceCaps): Boolean
    val matchLevel: ProfileMatchLevel get() = ProfileMatchLevel.Exact
    val quirks: PtpQuirks
    val probePlan: List<ProbeStep>
    val liveViewFormatsHint: List<LiveViewFormatHint>?
    val propertyCatalogSeed: Map<Long, PropertySeed>
    val photoStyleValues: LongRange?
    val capturePhotoSupported: Boolean
    val captureMovieSupported: Boolean
}

data class ProbeStep(
    val feature: Feature?,
    val ops: List<Int>,
    val timeoutMs: Int,
    val judgeId: String,
    val requiresProfileMatch: Boolean = false,
)

/** 仅为展示回退的提示表，不能替代相机枚举出的格式元组。 */
data class LiveViewFormatHint(val width: Int, val height: Int, val x: Int, val freq: Int)

/** v7 §11.1 的分组；T07 可直接复用，避免 Profile 依赖未来配置实现。 */
enum class PropertyGroup { EXPOSURE, COLOR, FOCUS, CAPTURE }
enum class ValueCodecKind { SHUTTER, ISO, APERTURE, EV, WB, RAW_U16, RAW_U32 }

/** fallbackValues 只供显示，永远不能据此推导当前档位 writable。 */
data class PropertySeed(
    val propId: Long,
    val group: PropertyGroup,
    val codec: ValueCodecKind,
    val fallbackValues: List<Long> = emptyList(),
)
