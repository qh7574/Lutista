package cn.alittlecookie.lutista.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import cn.alittlecookie.lutista.tether.objects.CameraObject
import cn.alittlecookie.lutista.tether.objects.CameraSessionKey

@Entity(
    tableName = "camera_objects",
    indices = [
        Index(value = ["serial", "sessionId", "storageId", "handle"], unique = true, name = "idx_camera_objects_handle"),
        Index(value = ["serial", "sessionId", "storageId", "parent"], name = "idx_camera_objects_parent"),
        Index(value = ["sessionId", "isDir", "handle"], orders = [Index.Order.ASC, Index.Order.ASC, Index.Order.DESC], name = "idx_camera_objects_sort"),
    ],
)
data class CameraObjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serial: String,
    val sessionId: String,
    val handle: Long,
    val storageId: Long,
    val format: Long,
    val parent: Long,
    val size: Long,
    val name: String,
    val dateTaken: String,
    val isDir: Boolean,
    val seq: Long,
    val durationMs: Long? = null,
    val updatedAt: Long,
) {
    fun toCameraObject() = CameraObject(handle, storageId, format, parent, size, name, dateTaken, isDir, seq)
}

fun CameraObject.toEntity(session: CameraSessionKey, updatedAt: Long): CameraObjectEntity = CameraObjectEntity(
    serial = session.serial, sessionId = session.id, handle = handle, storageId = storageId,
    format = format, parent = parent, size = size, name = name, dateTaken = dateTakenRaw,
    isDir = isDir, seq = seq, updatedAt = updatedAt,
)
