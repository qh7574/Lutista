package cn.alittlecookie.lutista.tether.profile.profiles

import cn.alittlecookie.lutista.tether.probe.ProbePlan
import cn.alittlecookie.lutista.tether.profile.CameraProfile
import cn.alittlecookie.lutista.tether.profile.LiveViewFormatHint
import cn.alittlecookie.lutista.tether.profile.ProfileMatchLevel
import cn.alittlecookie.lutista.tether.profile.PropertySeed
import cn.alittlecookie.lutista.tether.profile.PtpQuirks
import cn.alittlecookie.lutista.tether.transport.PtpDeviceCaps

/** 未识别 PTP：会话方必须先用 GetDeviceInfo/GetStorageIDs 完成热身。 */
class GenericPtpProfile : CameraProfile {
    override val id = "ptp-generic"
    override val displayName = "Generic PTP"
    override val matchLevel = ProfileMatchLevel.Generic
    override fun match(vid: Int, pid: Int, caps: PtpDeviceCaps): Boolean = true
    /** 0 表示优先使用会话已经取得的具体 StorageID；缺失时 Probe 不盲发。 */
    override val quirks = PtpQuirks(storageIdForObjectQuery = 0L, liveViewHeaderBytes = 0, supportsPropertyDescriptor = false)
    override val probePlan = ProbePlan.standard().filter { it.judgeId == ProbePlan.P5 }
    override val liveViewFormatsHint: List<LiveViewFormatHint>? = null
    override val propertyCatalogSeed: Map<Long, PropertySeed> = emptyMap()
    override val photoStyleValues: LongRange? = null
    override val capturePhotoSupported = false
    override val captureMovieSupported = false
}
