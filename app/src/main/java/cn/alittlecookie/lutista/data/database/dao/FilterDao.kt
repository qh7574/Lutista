package cn.alittlecookie.lutista.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import cn.alittlecookie.lutista.data.database.entity.FilterEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FilterDao {

    @Query("SELECT * FROM filters ORDER BY sortOrder ASC, createdAt DESC")
    fun getAllFilters(): Flow<List<FilterEntity>>

    @Query("SELECT * FROM filters WHERE isEnabled = 1 ORDER BY sortOrder ASC, createdAt DESC")
    fun getEnabledFilters(): Flow<List<FilterEntity>>

    @Query("SELECT * FROM filters WHERE id = :id")
    suspend fun getFilterById(id: Long): FilterEntity?

    @Query("SELECT * FROM filters WHERE name = :name LIMIT 1")
    suspend fun getFilterByName(name: String): FilterEntity?

    @Query("SELECT * FROM filters WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefaultFilter(): FilterEntity?

    @Query("SELECT * FROM filters WHERE isDefault = 1 LIMIT 1")
    fun getDefaultFilterFlow(): Flow<FilterEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(filter: FilterEntity): Long

    @Update
    suspend fun update(filter: FilterEntity)

    @Delete
    suspend fun delete(filter: FilterEntity)

    @Query("DELETE FROM filters WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE filters SET unifiedLutPath = :path, unifiedLutHash = :hash WHERE id = :id")
    suspend fun updateUnifiedLut(id: Long, path: String?, hash: String?)

    @Query("UPDATE filters SET updatedAt = :timestamp WHERE id = :id")
    suspend fun updateTimestamp(id: Long, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT * FROM filters WHERE isBuiltIn = 1 ORDER BY name ASC")
    fun getBuiltinFilters(): Flow<List<FilterEntity>>

    @Query("SELECT * FROM filters WHERE isBuiltIn = 1 ORDER BY name ASC")
    suspend fun getBuiltinFiltersList(): List<FilterEntity>

    @Query("DELETE FROM filters WHERE isBuiltIn = 1")
    suspend fun deleteAllBuiltinFilters()

    @Query("SELECT COUNT(*) FROM filters WHERE isBuiltIn = 1")
    suspend fun getBuiltinFilterCount(): Int

    /**
     * Overwrites the `sortOrder` of a single row and nothing else.
     *
     * Reordering must never go through [update]: a full-entity write pushes a whole stale snapshot
     * back into the table (e.g. an old `unifiedLutPath` or `isEnabled` value captured before the
     * drag), which silently reverts unrelated edits.
     */
    @Query("UPDATE filters SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Int)

    /**
     * Persists [orderedIds] as the new list order: the first id gets `sortOrder = 0`, the next `1`
     * and so on.
     *
     * Runs inside a single transaction so observers of [getAllFilters] never see a half-applied
     * order while the batch is being written.
     */
    @Transaction
    suspend fun updateSortOrders(orderedIds: List<Long>) {
        orderedIds.forEachIndexed { index, id ->
            if (id != 0L) updateSortOrder(id, index)
        }
    }
}
