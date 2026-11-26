package cn.alittlecookie.lutista.tether.debug

import cn.alittlecookie.lutista.BuildConfig
import cn.alittlecookie.lutista.tether.profile.CameraCapabilities
import cn.alittlecookie.lutista.tether.profile.Feature
import cn.alittlecookie.lutista.tether.protocol.Capability
import cn.alittlecookie.lutista.tether.protocol.CapabilityEvidence

/** Four debug degradations plus the untouched baseline. Never adds supported/writable assertions. */
enum class CapabilityOverride {
    None, BrowseOnly, NoLiveView, NoConfig, UnknownPtp;

    /** Apply to an observed snapshot; this function performs no I/O and preserves device identity. */
    fun apply(caps: CameraCapabilities): CameraCapabilities {
        if (!BuildConfig.DEBUG || this == None) return caps
        val detail = "debug override=$name; controlled_simulation=true"
        if (this == UnknownPtp) return caps.asUnknown(detail)
        val suppressed = when (this) {
            BrowseOnly -> liveViewFeatures + configFeatures + setOf(Feature.ModeRead, Feature.CapturePhoto, Feature.CaptureMovie)
            NoLiveView -> liveViewFeatures
            NoConfig -> configFeatures
            None, UnknownPtp -> emptySet()
        }
        return suppressed.fold(caps) { result, feature ->
            result.with(feature, Capability.no(CapabilityEvidence.INDETERMINATE, detail))
        }
    }

    companion object {
        /** Corrupt/old stored values safely select the baseline; release always selects None. */
        fun fromPreference(value: String?): CapabilityOverride =
            if (BuildConfig.DEBUG) entries.firstOrNull { it.name == value } ?: None else None

        private val liveViewFeatures = setOf(Feature.LiveView, Feature.LiveViewFormatSwitch, Feature.LiveViewFilterPreview)
        private val configFeatures = setOf(Feature.ConfigRead, Feature.ConfigWrite, Feature.PhotoStyle)
    }
}
