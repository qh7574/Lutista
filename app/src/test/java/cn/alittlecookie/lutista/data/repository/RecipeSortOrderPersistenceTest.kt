package cn.alittlecookie.lutista.data.repository

import cn.alittlecookie.lutista.data.database.dao.FilterDao
import cn.alittlecookie.lutista.data.database.dao.WatermarkDao
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Guards the repository half of the recipe-room reordering fix: the whole ordered id list must be
 * handed to the DAO in exactly the order the user sees, and it must go through the dedicated
 * `sortOrder` batch instead of N whole-entity updates.
 *
 * The SQL itself (`UPDATE ... SET sortOrder = :sortOrder`) and the `@Transaction` wrapper can only
 * be exercised by Room, i.e. in an instrumented test, so they are covered here by contract only.
 */
class RecipeSortOrderPersistenceTest {

    @Test
    fun `filter repository forwards the reordered id list unchanged`() = runBlocking {
        val dao = mockk<FilterDao>(relaxed = true)
        val repository = FilterRepository(dao)
        val reorderedIds = listOf(9L, 3L, 7L)

        repository.updateSortOrders(reorderedIds)

        coVerify(exactly = 1) { dao.updateSortOrders(reorderedIds) }
    }

    @Test
    fun `filter repository also accepts an empty order`() = runBlocking {
        val dao = mockk<FilterDao>(relaxed = true)
        val repository = FilterRepository(dao)

        repository.updateSortOrders(emptyList())

        coVerify(exactly = 1) { dao.updateSortOrders(emptyList()) }
    }

    @Test
    fun `watermark repository forwards the reordered id list unchanged`() = runBlocking {
        val dao = mockk<WatermarkDao>(relaxed = true)
        val repository = WatermarkRepository(dao)
        val reorderedIds = listOf(4L, 1L, 2L)

        repository.updateSortOrders(reorderedIds)

        coVerify(exactly = 1) { dao.updateSortOrders(reorderedIds) }
    }

    @Test
    fun `reordering never falls back to whole entity updates`() = runBlocking {
        // Regression guard: the old implementation persisted the order with N `update(entity)`
        // calls carrying whatever snapshot it had read before the drag. That path is what silently
        // reverted unrelated fields, so it must not come back.
        val filterDao = mockk<FilterDao>(relaxed = true)
        val watermarkDao = mockk<WatermarkDao>(relaxed = true)

        FilterRepository(filterDao).updateSortOrders(listOf(2L, 1L))
        WatermarkRepository(watermarkDao).updateSortOrders(listOf(2L, 1L))

        coVerify(exactly = 0) { filterDao.update(any()) }
        coVerify(exactly = 0) { watermarkDao.update(any()) }
    }

    @Test
    fun `forwarding neither sorts nor deduplicates the caller order`() = runBlocking {
        val dao = mockk<FilterDao>(relaxed = true)
        val repository = FilterRepository(dao)

        repository.updateSortOrders(listOf(5L, 5L, 1L))

        coVerify(exactly = 1) { dao.updateSortOrders(listOf(5L, 5L, 1L)) }
    }
}
