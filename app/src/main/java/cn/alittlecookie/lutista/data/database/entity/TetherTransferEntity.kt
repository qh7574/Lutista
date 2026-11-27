package cn.alittlecookie.lutista.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Persistent transfer history. A handle is diagnostic only and never a persistent identity. */
@Entity(
    tableName = "tether_transfers",
    indices = [
        Index(value = ["dedupeKey", "kind"], unique = true, name = "idx_tether_transfers_dedupe"),
        Index(value = ["state", "createdAt"], name = "idx_tether_transfers_state"),
    ],
)
data class TetherTransferEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dedupeKey: String,
    val kind: String,
    val handle: Long,
    val name: String,
    val size: Long,
    val targetPath: String = "",
    val state: String = "PENDING",
    @ColumnInfo(defaultValue = "0") val bytesDone: Long = 0,
    val taskId: Long? = null,
    val error: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)
