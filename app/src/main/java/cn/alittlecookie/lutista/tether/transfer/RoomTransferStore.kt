package cn.alittlecookie.lutista.tether.transfer

import cn.alittlecookie.lutista.data.database.dao.TetherTransferDao
import cn.alittlecookie.lutista.data.database.entity.TetherTransferEntity

class RoomTransferStore(private val dao: TetherTransferDao) : TransferStore {
    override suspend fun find(key: String, kind: TransferKind): TransferRecord? = dao.byKey(key, kind.name)?.toRecord()
    override suspend fun insertIfAbsent(record: TransferRecord): TransferRecord {
        dao.insertIgnore(record.toEntity())
        return checkNotNull(dao.byKey(record.key, record.kind.name)).toRecord()
    }
    override suspend fun save(record: TransferRecord) { dao.update(record.toEntity()) }
    override suspend fun recoverable(): List<TransferRecord> = dao.pending().map { it.toRecord() }
    private fun TransferRecord.toEntity() = TetherTransferEntity(
        id, key, kind.name, handle, name, size, targetPath, state.name, bytesDone, taskId, error, createdAt, updatedAt
    )
    private fun TetherTransferEntity.toRecord() = TransferRecord(
        id, dedupeKey, TransferKind.valueOf(kind), handle, name, size, targetPath,
        TransferState.valueOf(state), bytesDone, taskId, error, createdAt, updatedAt
    )
}
