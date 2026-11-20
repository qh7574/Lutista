package cn.alittlecookie.lutista.data.repository

import cn.alittlecookie.lutista.data.database.dao.WatermarkDao
import cn.alittlecookie.lutista.data.database.entity.WatermarkEntity
import cn.alittlecookie.lutista.watermark.model.BaseImageElement
import cn.alittlecookie.lutista.watermark.model.Scene
import cn.alittlecookie.lutista.watermark.util.SceneSerializer
import kotlinx.coroutines.flow.Flow

class WatermarkRepository(
    private val watermarkDao: WatermarkDao
) {
    fun getAllWatermarks(): Flow<List<WatermarkEntity>> = watermarkDao.getAllWatermarks()

    fun getEnabledWatermarks(): Flow<List<WatermarkEntity>> = watermarkDao.getEnabledWatermarks()

    suspend fun getWatermarkById(id: Long): WatermarkEntity? = watermarkDao.getWatermarkById(id)

    suspend fun getWatermarkByName(name: String): WatermarkEntity? = watermarkDao.getWatermarkByName(name)

    suspend fun insert(watermark: WatermarkEntity): Long = watermarkDao.insert(watermark)

    suspend fun update(watermark: WatermarkEntity) = watermarkDao.update(watermark)

    suspend fun delete(watermark: WatermarkEntity) = watermarkDao.delete(watermark)

    suspend fun deleteById(id: Long) = watermarkDao.deleteById(id)

    /**
     * Persists the user-visible ordering of the watermark list.
     *
     * @param orderedIds watermark ids in the order they should be displayed; index becomes
     *     `sortOrder`.
     */
    suspend fun updateSortOrders(orderedIds: List<Long>) = watermarkDao.updateSortOrders(orderedIds)

    fun deserializeScene(entity: WatermarkEntity): Scene {
        return try {
            SceneSerializer.fromJson(entity.configJson)
        } catch (_: Exception) {
            Scene(baseImage = BaseImageElement(imageUri = ""))
        }
    }

    fun serializeScene(scene: Scene): String {
        return SceneSerializer.toJson(scene)
    }

    suspend fun getSceneById(id: Long): Scene? {
        val entity = getWatermarkById(id) ?: return null
        return deserializeScene(entity)
    }

    suspend fun createDefault(): Long {
        val defaultScene = Scene(
            baseImage = BaseImageElement(imageUri = "")
        )
        val entity = WatermarkEntity(
            name = "未命名水印",
            configJson = serializeScene(defaultScene)
        )
        return insert(entity)
    }
}
