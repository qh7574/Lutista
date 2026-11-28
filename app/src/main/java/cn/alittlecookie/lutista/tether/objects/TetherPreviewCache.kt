package cn.alittlecookie.lutista.tether.objects

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * 联机模式「清晰大图」的磁盘缓存。
 *
 * 为什么需要它：相机原图（DC-S1 的 JPEG 约 9 MB）只能整份下载才能清晰显示；
 * 以前每次进全屏都重新下载，既慢又容易把相机拖进坏状态。
 *
 * 过期与容量边界（这三条是硬约束，改之前先想清楚）：
 * 1. **容量上限 128 MiB**：超过后按「最近访问时间」从旧到新淘汰，直到回落到上限的 90%
 *    （留 10% 余量，避免每次写入都触发一次全目录扫描）。
 * 2. **年龄上限 7 天**：超过 7 天没再被访问的文件直接删除。相机句柄只在一次会话内稳定，
 *    隔了几天再来的缓存几乎不可能命中，留着只占空间。
 * 3. **命中即刷新访问时间**：用文件的 `lastModified` 当「最近访问时间」，命中时 touch 一下，
 *    这样看过的热图不会被新图挤掉。
 *
 * 写入一律先写 `<key>.part` 再 rename，进程被杀不会留下半张图；读取时 `.part` 文件会被忽略。
 */
class TetherPreviewCache(
    private val directory: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** 返回可直接交给解码器的文件；命中时刷新访问时间。 */
    fun get(key: String): File? {
        val file = file(key)
        if (!file.isFile) return null
        val age = clock() - file.lastModified()
        if (age > maxAgeMs) {
            file.delete()
            return null
        }
        // touch：用修改时间记录「最近一次访问」，失败不影响命中。
        file.setLastModified(clock())
        return file
    }

    /** 下载用的临时文件；调用方负责在失败时删除。 */
    fun tempFile(key: String): File {
        ensureDirectory()
        return File(directory, name(key) + PART_SUFFIX)
    }

    /** 原子提交：`<key>.part` → `<key>.jpg`，并顺手做一次容量/年龄清理。 */
    fun commit(temp: File, key: String): File {
        ensureDirectory()
        val target = file(key)
        if (target.exists() && !target.delete()) throw IOException("无法替换旧的预览缓存：$target")
        if (!temp.renameTo(target)) throw IOException("无法提交预览缓存：$temp → $target")
        target.setLastModified(clock())
        prune()
        return target
    }

    /** 按容量与年龄清理；任何异常都不向外抛（缓存清理失败不该影响看图）。 */
    fun prune() {
        runCatching {
            val now = clock()
            val entries = directory.listFiles().orEmpty().filter { it.isFile }
            var total = 0L
            val alive = ArrayList<File>(entries.size)
            for (entry in entries) {
                val age = now - entry.lastModified()
                when {
                    entry.name.endsWith(PART_SUFFIX) -> entry.delete()
                    age > maxAgeMs -> entry.delete()
                    else -> {
                        total += entry.length()
                        alive += entry
                    }
                }
            }
            if (total <= maxBytes) return
            alive.sortBy { it.lastModified() }
            val target = (maxBytes * 9 / 10).coerceAtLeast(0)
            for (entry in alive) {
                if (total <= target) break
                val length = entry.length()
                if (entry.delete()) total -= length
            }
        }
    }

    /** 缓存键：会话 + 存储 + 句柄 + 对象大小 + 修改时间，任一变化都视为不同的图。 */
    fun keyOf(key: CameraThumbKey, size: Long, stamp: Long): String =
        "${key.session.id}:${key.storageId}:${key.handle}:${key.kind.name}:$size:$stamp"

    private fun file(key: String): File = File(directory, name(key))

    private fun name(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        val hex = StringBuilder(32)
        for (index in 0 until 16) {
            val value = digest[index].toInt() and 0xFF
            hex.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return hex.append(".jpg").toString()
    }

    private fun ensureDirectory() {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("无法创建预览缓存目录：$directory")
        }
    }

    companion object {
        const val PART_SUFFIX = ".part"
        const val HEX = "0123456789abcdef"

        /** 128 MiB：约 12 张 9 MB 原图，覆盖一次相册回看的常见范围。 */
        const val DEFAULT_MAX_BYTES = 128L * 1024 * 1024

        /** 7 天未访问即过期。 */
        const val DEFAULT_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    }
}
