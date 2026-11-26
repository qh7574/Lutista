package cn.alittlecookie.lutista.tether.profile.profiles

import cn.alittlecookie.lutista.tether.probe.ProbePlan
import cn.alittlecookie.lutista.tether.profile.CameraProfile
import cn.alittlecookie.lutista.tether.profile.LiveViewFormatHint
import cn.alittlecookie.lutista.tether.profile.PropertyGroup
import cn.alittlecookie.lutista.tether.profile.PropertySeed
import cn.alittlecookie.lutista.tether.profile.PtpQuirks
import cn.alittlecookie.lutista.tether.profile.ValueCodecKind
import cn.alittlecookie.lutista.tether.protocol.PtpConstants.Prop
import cn.alittlecookie.lutista.tether.transport.PtpDeviceCaps

class LumixS1Profile : CameraProfile {
    override val id = "lumix-s1"
    override val displayName = "Panasonic LUMIX DC-S1"
    override fun match(vid: Int, pid: Int, caps: PtpDeviceCaps): Boolean =
        vid == 0x04DA && pid == 0x2382 && caps.model == "DC-S1"
    override val quirks = PtpQuirks()
    override val probePlan = ProbePlan.standard()
    override val liveViewFormatsHint = listOf(
        LiveViewFormatHint(640, 432, 32, 30),
        LiveViewFormatHint(1120, 752, 350, 30),
        LiveViewFormatHint(1600, 1072, 700, 15),
    )
    /** v7 §11.3 白名单；0x22/0xA2 是该表列出的上限与图像格式属性。 */
    override val propertyCatalogSeed = listOf(
        PropertySeed(Prop.ISO_CONTAINER, PropertyGroup.EXPOSURE, ValueCodecKind.ISO),
        PropertySeed(0x02000022L, PropertyGroup.EXPOSURE, ValueCodecKind.ISO),
        PropertySeed(Prop.SHUTTER_CONTAINER, PropertyGroup.EXPOSURE, ValueCodecKind.SHUTTER),
        PropertySeed(Prop.APERTURE_CONTAINER, PropertyGroup.EXPOSURE, ValueCodecKind.APERTURE),
        PropertySeed(Prop.EXPOSURE_COMPENSATION_CONTAINER, PropertyGroup.EXPOSURE, ValueCodecKind.EV),
        PropertySeed(Prop.WHITE_BALANCE_CONTAINER, PropertyGroup.COLOR, ValueCodecKind.WB),
        PropertySeed(Prop.PHOTO_STYLE, PropertyGroup.COLOR, ValueCodecKind.RAW_U16, (1L..22L).toList()),
        PropertySeed(Prop.MODE_POS, PropertyGroup.CAPTURE, ValueCodecKind.RAW_U16, listOf(0L, 1L, 2L, 3L, 5L, 7L)),
        PropertySeed(0x020000A2L, PropertyGroup.CAPTURE, ValueCodecKind.RAW_U16),
    ).associateBy { it.propId }
    override val photoStyleValues: LongRange = 1L..22L
    override val capturePhotoSupported = true
    override val captureMovieSupported = true
}
