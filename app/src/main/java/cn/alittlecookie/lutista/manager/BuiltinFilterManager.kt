package cn.alittlecookie.lutista.manager

import android.content.Context
import cn.alittlecookie.lutista.data.database.entity.FilterEntity
import cn.alittlecookie.lutista.data.repository.FilterRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class BuiltinFilterManager(
    private val context: Context,
    private val filterRepository: FilterRepository,
    private val settingsManager: SettingsManager,
    private val logger: LogManager
) {
    companion object {
        private const val TAG = "BuiltinFilterManager"
        const val CURRENT_BUILTIN_FILTER_VERSION = 2
        private const val FILTERS_DIR = "filters"
        private const val ASSETS_FILTERS_DIR = "filters"
    }

    data class ReleasedFilter(
        val name: String,
        val filterDir: File,
        val lutFile: File
    )

    suspend fun initializeBuiltinFilters() = withContext(Dispatchers.IO) {
        try {
            val savedVersion = settingsManager.builtinFilterVersion.first()
            logger.i(TAG, "内置滤镜版本检查: 保存版本=$savedVersion, 当前版本=$CURRENT_BUILTIN_FILTER_VERSION")

            if (savedVersion < CURRENT_BUILTIN_FILTER_VERSION) {
                logger.i(TAG, "需要更新内置滤镜，开始初始化...")
                deleteAllBuiltinFilters()
                val releasedFilters = releaseFilterFiles()
                createBuiltinFilterEntities(releasedFilters)
                settingsManager.setBuiltinFilterVersion(CURRENT_BUILTIN_FILTER_VERSION)
                logger.i(TAG, "内置滤镜初始化完成，共释放 ${releasedFilters.size} 个滤镜")
            } else {
                logger.i(TAG, "内置滤镜版本已是最新，跳过初始化")
            }
        } catch (e: Exception) {
            logger.e(TAG, "初始化内置滤镜失败", e)
        }
    }

    private suspend fun releaseFilterFiles(): List<ReleasedFilter> = withContext(Dispatchers.IO) {
        val releasedFilters = mutableListOf<ReleasedFilter>()
        val filtersBaseDir = File(context.filesDir, FILTERS_DIR)
        filtersBaseDir.mkdirs()

        try {
            val assetFiles = context.assets.list(ASSETS_FILTERS_DIR) ?: emptyArray()
            logger.d(TAG, "Assets中的滤镜文件: ${assetFiles.joinToString()}")

            for (fileName in assetFiles) {
                if (!fileName.endsWith(".cube", ignoreCase = true)) continue

                val filterName = fileName.substringBeforeLast(".")
                val filterDir = File(filtersBaseDir, filterName)
                filterDir.mkdirs()

                val destFile = File(filterDir, "${filterName}.cube")
                context.assets.open("$ASSETS_FILTERS_DIR/$fileName").use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }

                releasedFilters.add(
                    ReleasedFilter(
                        name = filterName,
                        filterDir = filterDir,
                        lutFile = destFile
                    )
                )
                logger.d(TAG, "释放滤镜: $filterName -> ${destFile.absolutePath}")
            }
        } catch (e: Exception) {
            logger.e(TAG, "释放滤镜文件失败", e)
        }

        releasedFilters
    }

    private suspend fun createBuiltinFilterEntities(releasedFilters: List<ReleasedFilter>) {
        val sorted = releasedFilters.sortedBy { it.name.lowercase() }
        for ((index, releasedFilter) in sorted.withIndex()) {
            try {
                val existingFilter = filterRepository.getFilterByName(releasedFilter.name)
                if (existingFilter != null) {
                    logger.w(TAG, "滤镜已存在，跳过: ${releasedFilter.name}")
                    continue
                }

                val entity = FilterEntity(
                    name = releasedFilter.name,
                    lutPath1 = releasedFilter.lutFile.absolutePath,
                    lutConcentration1 = 1.0f,
                    isBuiltIn = true,
                    filterDirPath = releasedFilter.filterDir.absolutePath,
                    sortOrder = index,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )

                filterRepository.insert(entity)
                logger.d(TAG, "创建内置滤镜实体: ${releasedFilter.name}")
            } catch (e: Exception) {
                logger.e(TAG, "创建滤镜实体失败: ${releasedFilter.name}", e)
            }
        }
    }

    private suspend fun deleteAllBuiltinFilters() {
        try {
            val builtinFilters = filterRepository.getAllFilters().first()
                .filter { it.isBuiltIn }

            for (filter in builtinFilters) {
                filter.filterDirPath?.let { dirPath ->
                    val dir = File(dirPath)
                    if (dir.exists()) {
                        dir.deleteRecursively()
                        logger.d(TAG, "删除滤镜目录: $dirPath")
                    }
                }
                filterRepository.delete(filter)
            }

            val filtersBaseDir = File(context.filesDir, FILTERS_DIR)
            if (filtersBaseDir.exists()) {
                filtersBaseDir.deleteRecursively()
                filtersBaseDir.mkdirs()
            }

            logger.i(TAG, "已删除所有内置滤镜")
        } catch (e: Exception) {
            logger.e(TAG, "删除内置滤镜失败", e)
        }
    }

    suspend fun getBuiltinFilters(): List<FilterEntity> {
        return filterRepository.getAllFilters().first().filter { it.isBuiltIn }
    }

    suspend fun copyBuiltinFilter(filterId: Long): FilterEntity? {
        val filter = filterRepository.getFilterById(filterId) ?: return null
        if (!filter.isBuiltIn) return null

        val baseName = filter.name
        val allFilters = filterRepository.getAllFilters().first()
        val copyNum = allFilters
            .mapNotNull { e ->
                val prefix = "${baseName}-拷贝"
                if (e.name.startsWith(prefix))
                    e.name.removePrefix(prefix).toIntOrNull()
                else null
            }
            .maxOrNull()
            ?.plus(1) ?: 1
        val newName = "${baseName}-拷贝$copyNum"

        val filtersBaseDir = File(context.filesDir, FILTERS_DIR)
        val newDir = File(filtersBaseDir, newName)
        newDir.mkdirs()

        filter.filterDirPath?.let { oldDirPath ->
            val oldDir = File(oldDirPath)
            if (oldDir.exists()) {
                oldDir.copyRecursively(newDir, overwrite = true)
            }
        }

        val newLutPath1 = filter.lutPath1?.let { oldPath ->
            val oldFile = File(oldPath)
            if (oldFile.exists()) {
                val newFile = File(newDir, oldFile.name)
                oldFile.copyTo(newFile, overwrite = true)
                newFile.absolutePath
            } else null
        }

        val newEntity = FilterEntity(
            name = newName,
            lutPath1 = newLutPath1,
            lutConcentration1 = filter.lutConcentration1,
            lutPath2 = filter.lutPath2,
            lutConcentration2 = filter.lutConcentration2,
            // Was missing: duplicating a built-in filter reset the global LUT
            // strength back to 1.0, so the copy did not match the original.
            lutGlobalStrength = filter.lutGlobalStrength,
            lutPrecision = filter.lutPrecision,
            exposure = filter.exposure,
            contrast = filter.contrast,
            highlights = filter.highlights,
            shadows = filter.shadows,
            saturation = filter.saturation,
            colorTemperature = filter.colorTemperature,
            tint = filter.tint,
            curveJson = filter.curveJson,
            lchJson = filter.lchJson,
            vignetteStrength = filter.vignetteStrength,
            grainJson = filter.grainJson,
            isBuiltIn = false,
            filterDirPath = newDir.absolutePath,
            sortOrder = -1,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        val newId = filterRepository.insert(newEntity)
        logger.i(TAG, "拷贝内置滤镜: ${filter.name} -> $newName")
        return filterRepository.getFilterById(newId)
    }
}
