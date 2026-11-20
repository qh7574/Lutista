package cn.alittlecookie.lutista.manager

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

/**
 * 捕获本进程的 logcat（含 `android.util.Log` 与 native `__android_log_print`）写入
 * `logcat_yyyy-MM-dd.log`，同时回填 [LogHistory] 供崩溃回放。
 *
 * 这是 release 包里唯一能看到 DEBUG 级日志的通道：release 不可调试、`Timber.DebugTree`
 * 不会 plant，但 `logcat --pid <自己>` 只读自己 UID 的日志，不需要任何权限。
 *
 * 两处关键设计（都在修既有缺陷）：
 * 1. **不再调用 `logcat -c`**。原来每轮 `-d` 读完就 `-c` 清空，读到清空之间写入的行会永久丢失。
 *    现在改为记住上一轮最后一行，按行内容去重（缓冲区未绕圈时是精确的）。
 * 2. **失败不再 `break`**。原来的 `catch { break }` 会让一次失败永久停掉捕获，且不留痕迹 ——
 *    表现就是「导出包里空空如也」。现在退避重试，并把状态暴露给 [status]，导出时写进 device.txt。
 */
class LogcatCaptureManager(
    private val logDir: File,
    private val history: LogHistory? = null
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    /** 当前捕获状态，供导出时写进 device.txt（UI 不做改动）。 */
    @Volatile
    var status: String = "未启动"
        private set

    /** 已落盘行数。 */
    @Volatile
    var capturedLineCount: Long = 0L
        private set

    /** 上一轮读到的最后一行原文，用于跨轮去重（只在捕获协程里访问）。 */
    private var lastRawLine: String? = null

    /** Logcat threadtime format: "MM-DD HH:MM:SS.mmm PID TID LEVEL TAG: message" */
    private val logcatPattern = Pattern.compile(
        "^\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s+\\d+\\s+\\d+\\s+([VDIWEF])\\s+(\\S+)\\s*:\\s*(.*)$"
    )

    fun start() {
        if (captureJob?.isActive == true) return
        logDir.mkdirs()
        captureJob = scope.launch {
            val pid = android.os.Process.myPid().toString()
            var failures = 0
            while (isActive) {
                try {
                    val process = ProcessBuilder(
                        "logcat", "-d", "-v", "threadtime", "--pid", pid, "*:D"
                    ).redirectErrorStream(true).start()
                    val raw = process.inputStream.bufferedReader().use { it.readLines() }
                    val exitCode = process.waitFor()
                    if (exitCode != 0 && raw.isEmpty()) {
                        throw IllegalStateException("logcat exit=$exitCode")
                    }

                    val fresh = takeUnconsumed(raw)
                    if (fresh.isNotEmpty()) {
                        val formatted = fresh.map { formatLine(it) }
                        appendLines(formatted)
                        history?.let { sink -> formatted.forEach(sink::add) }
                        capturedLineCount += formatted.size
                    }
                    failures = 0
                    status = "运行中，已捕获 $capturedLineCount 行"
                } catch (e: Exception) {
                    // 某些 ROM 会拒绝 logcat 调用：绝不 break，退避重试并记录原因，
                    // 否则用户看到的现象只是「日志文件是空的」，无从排查。
                    failures++
                    status = "重试中（第 $failures 次）：${e.javaClass.simpleName}: ${e.message}"
                    delay(backoffMillis(failures))
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * 只保留本轮新增的行。
     *
     * 上一轮最后一行的原文在本轮输出里定位（取最后一次出现，避免重复行导致回放），
     * 取它之后的部分。缓冲区绕圈导致该行已不存在时退化为「全部当作新行」—— 宁可重复也不丢。
     */
    private fun takeUnconsumed(raw: List<String>): List<String> {
        if (raw.isEmpty()) return emptyList()
        val marker = lastRawLine
        lastRawLine = raw.last()
        if (marker == null) return raw
        val index = raw.lastIndexOf(marker)
        return if (index >= 0) raw.subList(index + 1, raw.size) else raw
    }

    private fun backoffMillis(failures: Int): Long {
        val shift = (failures - 1).coerceIn(0, 4)
        return (1000L shl shift).coerceAtMost(MAX_BACKOFF_MS)
    }

    private fun formatLine(line: String): String {
        val matcher = logcatPattern.matcher(line)
        if (matcher.matches()) {
            val levelChar = matcher.group(1) ?: ""
            val tag = matcher.group(2) ?: ""
            val message = matcher.group(3) ?: ""
            val level = when (levelChar) {
                "V", "D" -> "D"
                "I" -> "I"
                "W" -> "W"
                else -> "E"
            }
            return "${timeFormat.format(Date())} $level($tag): $message"
        }
        val raw = line.replaceFirst(
            Regex("^\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\s+\\d+\\s+\\d+\\s+[VDIWEF]\\s+"), ""
        )
        return "${timeFormat.format(Date())} D(logcat): $raw"
    }

    private fun appendLines(lines: List<String>) {
        if (lines.isEmpty()) return
        val logFile = File(logDir, "logcat_${dateFormat.format(Date())}.log")
        try {
            logFile.appendText(lines.joinToString(separator = "\n", postfix = "\n"))
        } catch (_: Exception) {
        }
    }

    fun destroy() {
        captureJob?.cancel()
        captureJob = null
        status = "已停止"
        scope.cancel()
    }

    companion object {
        /** 轮询间隔。2s -> 5s：降低常驻开销（每轮要 fork 一个 logcat 进程）。 */
        private const val POLL_INTERVAL_MS = 5_000L
        private const val MAX_BACKOFF_MS = 30_000L
    }
}
