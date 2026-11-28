package cn.alittlecookie.lutista.service

import java.io.File

/**
 * Chooses the folder for a TETHER "滤镜直出" product.
 *
 * A product written inside the monitor folder would be re-imported by Home's monitor and
 * processed a second time, so the monitored folder is never used as the output base:
 * the configured output folder wins, then the default output folder, and only when both
 * coincide with the monitor folder do we allocate a dedicated subfolder.
 */
internal object TetherOutputPlacement {
    const val FALLBACK_SUBDIR = "滤镜直出"

    data class Placement(val base: String, val reason: String? = null)

    fun resolve(customOutput: String, monitor: String, defaultDir: String): Placement {
        val monitorBlank = monitor.isBlank()
        if (customOutput.isNotBlank() && (monitorBlank || !sameLocation(customOutput, monitor))) {
            return Placement(customOutput)
        }
        if (monitorBlank) return Placement(defaultDir)
        if (!sameLocation(defaultDir, monitor)) {
            val reason = if (customOutput.isBlank()) null else "配置的输出文件夹就是输入文件夹"
            return Placement(defaultDir, reason)
        }
        return Placement(File(defaultDir, FALLBACK_SUBDIR).path, "默认输出文件夹就是输入文件夹")
    }

    /** A SAF tree URI and a filesystem path can never describe the same location. */
    private fun sameLocation(left: String, right: String): Boolean {
        if (left.isBlank() || right.isBlank()) return false
        val leftSaf = left.startsWith("content://")
        if (leftSaf != right.startsWith("content://")) return false
        if (leftSaf) return left.trimEnd('/') == right.trimEnd('/')
        val canonical = runCatching { File(left).canonicalPath to File(right).canonicalPath }.getOrNull()
        return if (canonical != null) canonical.first == canonical.second else
            File(left).absolutePath == File(right).absolutePath
    }
}
