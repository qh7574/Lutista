package cn.alittlecookie.lutista.tether.transfer

import cn.alittlecookie.lutista.data.repository.FilterRepository
import cn.alittlecookie.lutista.data.repository.WatermarkRepository
import cn.alittlecookie.lutista.manager.SettingsManager
import cn.alittlecookie.lutista.service.TaskSubmissionManager
import cn.alittlecookie.lutista.util.FilterConfigJson.toConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

class TaskSubmissionTetherSubmitter(
    private val submissions: TaskSubmissionManager,
    private val settings: SettingsManager,
    private val filters: FilterRepository,
    private val watermarks: WatermarkRepository,
) : TetherTaskSubmitter {
    override suspend fun automaticEnabled(): Boolean = settings.tetherAutoTransfer.first()
    /** 滤镜直出关闭时绝不提交处理任务：回传只有原片一张，也就不会出现第二张套过滤镜的图。 */
    override suspend fun resolveEffects(): TetherEffects? {
        if (!settings.tetherDirectFilter.first()) return null
        val selectedFilter = settings.globalFilterId.first()
        val filter = if (selectedFilter > 0) try {
            filters.getFilterById(selectedFilter)?.takeIf { it.isEnabled }?.toConfig()?.let { selectedFilter } ?: 0L
        } catch (e: CancellationException) { throw e } catch (_: Exception) { 0L } else 0L
        val selectedWatermark = settings.globalWatermarkId.first().toLongOrNull() ?: 0L
        val watermark = if (selectedWatermark > 0) try {
            watermarks.getSceneById(selectedWatermark)?.let { selectedWatermark } ?: 0L
        } catch (e: CancellationException) { throw e } catch (_: Exception) { 0L } else 0L
        return TetherEffects(filter, watermark).takeIf { it.valid }
    }
    override suspend fun submit(key: String, downloaded: DownloadedTransfer, effects: TetherEffects): Long =
        submissions.submitTether(
            sourcePath = requireNotNull(downloaded.localPath), originalSourcePath = downloaded.targetPath,
            submissionKey = key, filterId = effects.filterId, watermarkId = effects.watermarkId
        )
}
