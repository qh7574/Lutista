package cn.alittlecookie.lutista.tether.profile.profiles

import cn.alittlecookie.lutista.tether.probe.ProbePlan
import cn.alittlecookie.lutista.tether.profile.CameraProfile
import cn.alittlecookie.lutista.tether.profile.LiveViewFormatHint
import cn.alittlecookie.lutista.tether.profile.ProfileMatchLevel
import cn.alittlecookie.lutista.tether.profile.PropertySeed
import cn.alittlecookie.lutista.tether.profile.PtpQuirks
import cn.alittlecookie.lutista.tether.transport.PtpDeviceCaps

class GenericLumixProfile : CameraProfile {
    override val id = "lumix-generic"
    override val displayName = "Panasonic LUMIX"
    override val matchLevel = ProfileMatchLevel.BrandDefault
    override fun match(vid: Int, pid: Int, caps: PtpDeviceCaps): Boolean = vid == 0x04DA
    override val quirks = PtpQuirks(supportsPropertyDescriptor = false)
    // 无格调范围/描述符事实，保守省去 P3/P4，保留 Unknown 而非伪造 Unsupported。
    override val probePlan = ProbePlan.standard().filter { it.judgeId != ProbePlan.P3 && it.judgeId != ProbePlan.P4 }
    override val liveViewFormatsHint: List<LiveViewFormatHint>? = null
    override val propertyCatalogSeed: Map<Long, PropertySeed> = emptyMap()
    override val photoStyleValues: LongRange? = null
    override val capturePhotoSupported = false
    override val captureMovieSupported = false
}
