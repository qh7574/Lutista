package cn.alittlecookie.lutista.tether.objects

import cn.alittlecookie.lutista.data.database.entity.toEntity
import org.junit.Assert.*
import org.junit.Test

class CameraObjectKeysTest {
    private val info = CameraObject(1, 2, 0x3801, 0, 300, "IMG.JPG", "20261125T120000", false)

    @Test fun sessionAndImageCacheKeysChangeOnReconnectAndStorageOrKindChange() {
        val key = CameraThumbKey(CameraSessionKey("camera:1", 100), 1, 5)
        val alternatives = listOf(
            key.copy(session = CameraSessionKey("camera:1", 101)),
            key.copy(session = CameraSessionKey("camera:2", 100)),
            key.copy(storageId = 2), key.copy(handle = 6), key.copy(stamp = 1),
            key.copy(kind = CameraThumbKey.Kind.RAW_PREVIEW_1920),
        )
        assertEquals(7, (alternatives + key).map { it.cacheKey }.toSet().size)
    }

    @Test fun persistentTransferDedupeIgnoresHandleButIncludesEveryPersistentField() {
        val key = CameraObjectKeys.transferDedupeKey("serial", info)
        assertEquals(64, key.length)
        assertEquals(key, CameraObjectKeys.transferDedupeKey("serial", info.copy(handle = 99)))
        val alternatives = listOf(
            CameraObjectKeys.transferDedupeKey("other", info),
            CameraObjectKeys.transferDedupeKey("serial", info.copy(name = "OTHER.JPG")),
            CameraObjectKeys.transferDedupeKey("serial", info.copy(size = 301)),
            CameraObjectKeys.transferDedupeKey("serial", info.copy(dateTakenRaw = "raw-date")),
        )
        assertFalse(alternatives.contains(key))
        assertNotEquals(
            CameraObjectKeys.transferDedupeKey("a", "b:c", 1, "d"),
            CameraObjectKeys.transferDedupeKey("a:b", "c", 1, "d"),
        )
    }

    @Test fun roomMappingRetainsRawMetadataAndSessionNamespace() {
        val session = CameraSessionKey("serial", 123)
        val row = info.copy(seq = 42).toEntity(session, updatedAt = 500)
        assertEquals(session.id, row.sessionId)
        assertEquals(session.serial, row.serial)
        assertEquals(500L, row.updatedAt)
        assertEquals(info.copy(seq = 42), row.toCameraObject())
    }
}
