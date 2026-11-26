package cn.alittlecookie.lutista.tether.profile

import cn.alittlecookie.lutista.tether.profile.profiles.GenericLumixProfile
import cn.alittlecookie.lutista.tether.profile.profiles.GenericPtpProfile
import cn.alittlecookie.lutista.tether.profile.profiles.LumixS1Profile
import cn.alittlecookie.lutista.tether.transport.PtpDeviceCaps

/** 四级匹配；同阶段保持注册顺序，新机型不需要改变任何消费端。 */
class CameraProfileRegistry(
    private val profiles: List<CameraProfile> = listOf(LumixS1Profile(), GenericLumixProfile(), GenericPtpProfile()),
) {
    fun resolve(vid: Int, pid: Int, caps: PtpDeviceCaps): CameraProfile {
        for (level in ProfileMatchLevel.entries) {
            profiles.firstOrNull { it.matchLevel == level && it.match(vid, pid, caps) }?.let { return it }
        }
        return GenericPtpProfile()
    }
}
