package cn.alittlecookie.lutista.data.repository

import cn.alittlecookie.lutista.data.database.dao.FilterDao
import cn.alittlecookie.lutista.data.database.entity.FilterEntity
import kotlinx.coroutines.flow.Flow

class FilterRepository(
    private val filterDao: FilterDao
) {
    fun getAllFilters(): Flow<List<FilterEntity>> = filterDao.getAllFilters()

    fun getEnabledFilters(): Flow<List<FilterEntity>> = filterDao.getEnabledFilters()

    suspend fun getFilterById(id: Long): FilterEntity? = filterDao.getFilterById(id)

    suspend fun getFilterByName(name: String): FilterEntity? = filterDao.getFilterByName(name)

    suspend fun getDefaultFilter(): FilterEntity? = filterDao.getDefaultFilter()

    fun getDefaultFilterFlow(): Flow<FilterEntity?> = filterDao.getDefaultFilterFlow()

    suspend fun insert(filter: FilterEntity): Long = filterDao.insert(filter)

    suspend fun update(filter: FilterEntity) = filterDao.update(filter)

    suspend fun delete(filter: FilterEntity) = filterDao.delete(filter)

    suspend fun deleteById(id: Long) = filterDao.deleteById(id)

    suspend fun updateUnifiedLut(id: Long, path: String?, hash: String?) =
        filterDao.updateUnifiedLut(id, path, hash)

    suspend fun updateTimestamp(id: Long) = filterDao.updateTimestamp(id)

    /**
     * Persists the user-visible ordering of the filter list.
     *
     * @param orderedIds filter ids in the order they should be displayed; index becomes `sortOrder`.
     */
    suspend fun updateSortOrders(orderedIds: List<Long>) = filterDao.updateSortOrders(orderedIds)
}
