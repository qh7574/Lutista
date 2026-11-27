package cn.alittlecookie.lutista.tether.render

import cn.alittlecookie.lutista.data.database.entity.FilterEntity
import cn.alittlecookie.lutista.data.model.CurveConfig
import cn.alittlecookie.lutista.data.model.FilterConfig
import cn.alittlecookie.lutista.data.model.LchConfig
import cn.alittlecookie.lutista.data.repository.FilterRepository
import cn.alittlecookie.lutista.manager.SettingsManager
import cn.alittlecookie.lutista.util.FilterConfigJson.toConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** One color pass using the same global filter as output. No watermark/overlay path is provided. */
data class PreviewFilterChain(val color: PreviewColorPass? = null) {
    val isEmpty: Boolean get() = color == null
    val gpuPassCount: Int get() = if (color == null) 0 else 1
}

data class PreviewColorPass(
    val filterId: Long,
    val filter: FilterConfig,
    val disabledFields: Set<String> = setOf("grain"),
)

/** Reacts to edits/deletion/disable as well as selection changes; never re-enables a degraded renderer. */
class LiveViewFilterSource(
    private val globalFilterIds: Flow<Long>,
    private val allFilters: Flow<List<FilterEntity>>,
) {
    constructor(settings: SettingsManager, filters: FilterRepository) :
        this(settings.globalFilterId, filters.getAllFilters())

    fun chain(enabled: Boolean): Flow<PreviewFilterChain> = chain(flowOf(enabled))

    fun chain(enabled: Flow<Boolean>): Flow<PreviewFilterChain> =
        combine(enabled, globalFilterIds, allFilters) { on, selectedId, filters ->
            val selected = filters.firstOrNull { it.id == selectedId && it.isEnabled }
            val config = if (on && selectedId > 0L) selected?.toConfig() else null
            previewChain(config)
        }.distinctUntilChanged()

    fun filters(enabled: Flow<Boolean>): Flow<FilterConfig?> = chain(enabled).map { it.color?.filter }

    companion object {
        /** Also used by direct callers so grain-only/no-op selections don't consume GPU bandwidth. */
        fun previewChain(config: FilterConfig?): PreviewFilterChain {
            if (config == null) return PreviewFilterChain()
            val preview = config.copy(grainConfig = config.grainConfig.copy(globalStrength = 0f))
            val hasLut = preview.lutGlobalStrength > 0f &&
                ((!preview.lutPath1.isNullOrBlank() && preview.lutConcentration1 > 0f) ||
                    (!preview.lutPath2.isNullOrBlank() && preview.lutConcentration2 > 0f))
            val hasColor = hasLut || preview.exposure != 0f || preview.contrast != 0f ||
                preview.highlights != 0f || preview.shadows != 0f || preview.saturation != 0f ||
                preview.colorTemperature != 0f || preview.tint != 0f || preview.vignetteStrength != 0f ||
                preview.curveConfig != CurveConfig() || preview.lchConfig != LchConfig()
            return if (hasColor) PreviewFilterChain(PreviewColorPass(preview.id, preview)) else PreviewFilterChain()
        }
    }
}
