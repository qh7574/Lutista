package cn.alittlecookie.lutista.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "processing_tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sourcePath: String,
    val outputPath: String = "",
    val filterId: Long? = null,
    val watermarkId: Long? = null,
    val status: String = "PENDING",
    val priority: Int = 1,
    val progress: Float = 0f,
    val retryCount: Int = 0,
    val errorMessage: String? = null,
    val source: String = "MANUAL",
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    /** Real original destination (including SAF URI); sourcePath remains a readable original file. */
    val originalSourcePath: String? = null,
    /** Stable TETHER import identity, persisted before publishing the processing task. */
    val submissionKey: String? = null
)

enum class TaskStatus {
    PENDING,
    PROCESSING,
    RETRYING,
    COMPLETED,
    FAILED,
    CANCELLED
}

enum class TaskSource {
    MANUAL,
    TETHER,
    MONITOR
}
