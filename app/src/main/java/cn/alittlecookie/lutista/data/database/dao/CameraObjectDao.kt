package cn.alittlecookie.lutista.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import cn.alittlecookie.lutista.data.database.entity.CameraObjectEntity
import kotlinx.coroutines.flow.Flow

data class CameraStorageCount(val storageId: Long, val count: Int)

@Dao
interface CameraObjectDao {
    @Query("SELECT * FROM camera_objects WHERE sessionId=:sessionId AND isDir=0 AND (:storageId IS NULL OR storageId=:storageId) AND (:parent IS NULL OR parent=:parent) ORDER BY handle DESC, storageId ASC LIMIT :limit OFFSET :offset")
    fun observeFilteredPage(sessionId: String, storageId: Long?, parent: Long?, limit: Int = 100, offset: Int = 0): Flow<List<CameraObjectEntity>>

    @Query("SELECT * FROM camera_objects WHERE sessionId=:sessionId AND isDir=1 ORDER BY storageId ASC, handle DESC")
    fun observeDirectories(sessionId: String): Flow<List<CameraObjectEntity>>

    @Query("SELECT storageId, COUNT(*) AS count FROM camera_objects WHERE sessionId=:sessionId AND isDir=0 GROUP BY storageId ORDER BY storageId ASC")
    fun observeStorageCounts(sessionId: String): Flow<List<CameraStorageCount>>

    @Query("SELECT * FROM camera_objects WHERE sessionId=:sessionId AND isDir=0 ORDER BY handle DESC, storageId ASC LIMIT :limit OFFSET :offset")
    suspend fun page(sessionId: String, limit: Int = 50, offset: Int = 0): List<CameraObjectEntity>

    @Query("SELECT * FROM camera_objects WHERE sessionId=:sessionId AND isDir=0 ORDER BY handle DESC, storageId ASC LIMIT :limit OFFSET :offset")
    fun observePage(sessionId: String, limit: Int = 50, offset: Int = 0): Flow<List<CameraObjectEntity>>

    @Query("SELECT COUNT(*) FROM camera_objects WHERE sessionId=:sessionId AND isDir=0")
    fun observeCount(sessionId: String): Flow<Int>

    @Query("SELECT * FROM camera_objects WHERE sessionId=:sessionId AND storageId=:storageId AND handle=:handle LIMIT 1")
    suspend fun byHandle(sessionId: String, storageId: Long, handle: Long): CameraObjectEntity?

    @Query("SELECT * FROM camera_objects WHERE sessionId=:sessionId AND handle=:handle ORDER BY storageId")
    suspend fun findByHandle(sessionId: String, handle: Long): List<CameraObjectEntity>

    @Query("SELECT * FROM camera_objects WHERE sessionId=:sessionId AND storageId=:storageId AND parent=:parent ORDER BY isDir DESC, handle DESC")
    suspend fun children(sessionId: String, storageId: Long, parent: Long): List<CameraObjectEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<CameraObjectEntity>)

    @Query("DELETE FROM camera_objects WHERE sessionId=:sessionId")
    suspend fun clearSession(sessionId: String)

    @Query("DELETE FROM camera_objects WHERE sessionId=:sessionId AND storageId=:storageId AND handle=:handle")
    suspend fun delete(sessionId: String, storageId: Long, handle: Long)

    @Query("UPDATE camera_objects SET durationMs=:durationMs WHERE sessionId=:sessionId AND storageId=:storageId AND handle=:handle")
    suspend fun setDuration(sessionId: String, storageId: Long, handle: Long, durationMs: Long)
}
