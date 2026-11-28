package cn.alittlecookie.lutista.tether.objects

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 「清晰大图」磁盘缓存的边界：命中/未命中、7 天过期、容量淘汰、原子提交。
 *
 * 这些边界都是给真机用的：相机句柄只在一次会话内稳定，隔几天再来的缓存命中率约为 0，
 * 所以过期必须真的删；9 MB 一张的图必须按容量淘汰，否则手机存储会被慢慢吃掉。
 */
class TetherPreviewCacheTest {
    @get:Rule
    val temp = TemporaryFolder()

    private var now = 1_000_000L

    private fun cache(
        maxBytes: Long = TetherPreviewCache.DEFAULT_MAX_BYTES,
        maxAgeMs: Long = TetherPreviewCache.DEFAULT_MAX_AGE_MS,
    ): TetherPreviewCache = TetherPreviewCache(
        directory = File(temp.root, "previews"),
        maxBytes = maxBytes,
        maxAgeMs = maxAgeMs,
        clock = { now },
    )

    private fun publish(cache: TetherPreviewCache, key: String, bytes: Int, at: Long = now): File {
        now = at
        val tempFile = cache.tempFile(key)
        tempFile.writeBytes(ByteArray(bytes))
        return cache.commit(tempFile, key).also { it.setLastModified(at) }
    }

    private fun entries(): List<File> =
        File(temp.root, "previews").listFiles().orEmpty().filter { it.isFile }

    @Test
    fun missingEntryReturnsNull() {
        assertNull(cache().get("absent"))
    }

    @Test
    fun committedEntryIsServedAndTouchedOnHit() {
        val cache = cache()
        publish(cache, "a", 10, at = 1_000_000L)
        now = 2_000_000L
        val hit = cache.get("a")
        assertNotNull(hit)
        assertEquals(10L, hit!!.length())
        // 命中必须刷新 lastModified，否则热图会被新图按 LRU 挤掉。
        assertEquals(2_000_000L, hit.lastModified())
    }

    @Test
    fun entryOlderThanMaxAgeIsRejectedAndDeleted() {
        val cache = cache(maxAgeMs = 1_000L)
        val file = publish(cache, "old", 10, at = 1_000_000L)
        now = 1_000_000L + 1_001L
        assertNull(cache.get("old"))
        assertFalse(file.exists())
    }

    @Test
    fun commitReplacesThePreviousFileWithoutLeavingPartFiles() {
        val cache = cache()
        val first = publish(cache, "same", 4)
        val replaced = publish(cache, "same", 8)
        assertEquals(8L, replaced.length())
        assertEquals(first.absolutePath, replaced.absolutePath)
        // `.part` 是下载中间态，提交后不应残留。
        assertTrue(entries().none { it.name.endsWith(".part") })
        assertEquals(1, entries().size)
    }

    @Test
    fun pruneDeletesStalePartFiles() {
        val cache = cache()
        publish(cache, "b", 10)
        val leftover = cache.tempFile("c").also { it.writeBytes(ByteArray(3)) }
        cache.prune()
        assertFalse(leftover.exists())
    }

    @Test
    fun exceedingCapacityEvictsOldestAccessesDownToNinetyPercent() {
        // 每张 100 字节，上限 500 字节；写 6 张后必须回落到 450 字节以内（4 张）。
        val cache = cache(maxBytes = 500L)
        val written = (0 until 6).map { index -> publish(cache, "k$index", 100, at = 1_000_000L + index) }
        assertEquals(4, entries().size)
        // 保留的是最近访问的 k2..k5，k0/k1 被淘汰。
        assertFalse(written[0].exists())
        assertFalse(written[1].exists())
        assertTrue(written[2].exists())
        assertTrue(written[5].exists())
    }

    @Test
    fun evictionKeepsRecentlyViewedHeatInsteadOfOldestWritten() {
        val cache = cache(maxBytes = 300L)
        publish(cache, "old", 100, at = 1_000_000L)
        val mid = publish(cache, "mid", 100, at = 1_000_100L)
        publish(cache, "new", 100, at = 1_000_200L)
        now = 1_000_300L
        // 重新看一次 old：它应该比 mid 更「新」，被淘汰的应是 mid。
        assertNotNull(cache.get("old"))
        publish(cache, "extra", 100, at = 1_000_400L)
        now = 1_000_500L
        assertNotNull(cache.get("old"))
        // 400 字节 > 300 上限，回落到 270：先淘汰 mid，再淘汰 new，old 因为刚被访问而保留。
        assertNull(cache.get("mid"))
        assertFalse(mid.exists())
    }

    @Test
    fun keyOfChangesWithEveryIdentityField() {
        val cache = cache()
        val base = CameraThumbKey(
            CameraSessionKey("serial", 7), storageId = 1, handle = 0x10, kind = CameraThumbKey.Kind.FULL_JPEG,
        )
        val key = cache.keyOf(base, size = 100, stamp = 5)
        assertEquals(key, cache.keyOf(base, size = 100, stamp = 5))
        assertTrue(key != cache.keyOf(base, size = 101, stamp = 5))
        assertTrue(key != cache.keyOf(base, size = 100, stamp = 6))
        assertTrue(key != cache.keyOf(base.copy(handle = 0x11), size = 100, stamp = 5))
        assertTrue(key != cache.keyOf(base.copy(kind = CameraThumbKey.Kind.THUMB_160), size = 100, stamp = 5))
    }

    @Test(expected = IOException::class)
    fun commitFailsWhenTheTargetCannotBeReplaced() {
        val cache = cache()
        val target = publish(cache, "locked", 4)
        // 目标名被一个非空目录占住：既删不掉也 rename 不过去。
        target.delete()
        assertTrue(target.mkdirs())
        assertTrue(File(target, "child").createNewFile())
        val tempFile = cache.tempFile("locked").also { it.writeBytes(ByteArray(4)) }
        cache.commit(tempFile, "locked")
    }
}
