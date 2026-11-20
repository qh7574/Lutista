package cn.alittlecookie.lutista.data.database.dao

import cn.alittlecookie.lutista.data.database.entity.FilterEntity
import cn.alittlecookie.lutista.data.database.entity.WatermarkEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the *real* `updateSortOrders` body declared as a default implementation on
 * [FilterDao] / [WatermarkDao].
 *
 * The fakes below override only the single-row `updateSortOrder`, therefore every assertion here
 * runs the production code that maps a display position onto `sortOrder` (0-based index, iteration
 * order, defensive skip of `id == 0`). Mocking the DAO would bypass that body entirely, which is
 * why plain fakes are used instead of mockk.
 *
 * Not covered here (needs a device / Robolectric): the `@Transaction` wrapper around the default
 * method. It is verified structurally — the Room generated code wraps the call in
 * `performInTransactionSuspending { super.updateSortOrders(...) }`.
 */
class RecipeSortOrderDaoTest {

    private class RecordingFilterDao : FilterDao {
        val sortOrderWrites = mutableListOf<Pair<Long, Int>>()
        var wholeEntityUpdates = 0

        override suspend fun updateSortOrder(id: Long, sortOrder: Int) {
            sortOrderWrites += id to sortOrder
        }

        override suspend fun update(filter: FilterEntity) {
            wholeEntityUpdates++
        }

        override fun getAllFilters(): Flow<List<FilterEntity>> = emptyFlow()

        override fun getEnabledFilters(): Flow<List<FilterEntity>> = emptyFlow()

        override suspend fun getFilterById(id: Long): FilterEntity? = null

        override suspend fun getFilterByName(name: String): FilterEntity? = null

        override suspend fun getDefaultFilter(): FilterEntity? = null

        override fun getDefaultFilterFlow(): Flow<FilterEntity?> = emptyFlow()

        override suspend fun insert(filter: FilterEntity): Long = 0L

        override suspend fun delete(filter: FilterEntity) = Unit

        override suspend fun deleteById(id: Long) = Unit

        override suspend fun updateUnifiedLut(id: Long, path: String?, hash: String?) = Unit

        override suspend fun updateTimestamp(id: Long, timestamp: Long) = Unit

        override fun getBuiltinFilters(): Flow<List<FilterEntity>> = emptyFlow()

        override suspend fun getBuiltinFiltersList(): List<FilterEntity> = emptyList()

        override suspend fun deleteAllBuiltinFilters() = Unit

        override suspend fun getBuiltinFilterCount(): Int = 0
    }

    private class RecordingWatermarkDao : WatermarkDao {
        val sortOrderWrites = mutableListOf<Pair<Long, Int>>()
        var wholeEntityUpdates = 0

        override suspend fun updateSortOrder(id: Long, sortOrder: Int) {
            sortOrderWrites += id to sortOrder
        }

        override suspend fun update(watermark: WatermarkEntity) {
            wholeEntityUpdates++
        }

        override fun getAllWatermarks(): Flow<List<WatermarkEntity>> = emptyFlow()

        override fun getEnabledWatermarks(): Flow<List<WatermarkEntity>> = emptyFlow()

        override suspend fun getWatermarkById(id: Long): WatermarkEntity? = null

        override suspend fun getWatermarkByName(name: String): WatermarkEntity? = null

        override suspend fun insert(watermark: WatermarkEntity): Long = 0L

        override suspend fun delete(watermark: WatermarkEntity) = Unit

        override suspend fun deleteById(id: Long) = Unit
    }

    @Test
    fun `filter order is written as zero based indexes in list order`() = runBlocking {
        val dao = RecordingFilterDao()

        dao.updateSortOrders(listOf(9L, 3L, 7L))

        assertEquals(listOf(9L to 0, 3L to 1, 7L to 2), dao.sortOrderWrites)
        assertEquals(0, dao.wholeEntityUpdates)
    }

    @Test
    fun `watermark order is written as zero based indexes in list order`() = runBlocking {
        val dao = RecordingWatermarkDao()

        dao.updateSortOrders(listOf(4L, 1L, 2L))

        assertEquals(listOf(4L to 0, 1L to 1, 2L to 2), dao.sortOrderWrites)
        assertEquals(0, dao.wholeEntityUpdates)
    }

    @Test
    fun `empty order writes nothing`() = runBlocking {
        val filterDao = RecordingFilterDao()
        val watermarkDao = RecordingWatermarkDao()

        filterDao.updateSortOrders(emptyList())
        watermarkDao.updateSortOrders(emptyList())

        assertTrue(filterDao.sortOrderWrites.isEmpty())
        assertTrue(watermarkDao.sortOrderWrites.isEmpty())
    }

    @Test
    fun `unpersisted id zero is skipped without shifting later rows`() = runBlocking {
        val dao = RecordingFilterDao()

        dao.updateSortOrders(listOf(5L, 0L, 8L))

        // Row id 0 can only come from an in-memory placeholder (never from the database), so it is
        // skipped; the remaining rows still keep their own display position to avoid collisions.
        assertEquals(listOf(5L to 0, 8L to 2), dao.sortOrderWrites)
    }

    @Test
    fun `duplicate indexes never collapse even when the caller repeats an id`() = runBlocking {
        val dao = RecordingWatermarkDao()

        dao.updateSortOrders(listOf(1L, 1L))

        assertEquals(listOf(1L to 0, 1L to 1), dao.sortOrderWrites)
    }
}
