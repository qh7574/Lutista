package cn.alittlecookie.lutista.manager

import android.content.Context
import android.os.Build
import android.util.Log
import cn.alittlecookie.lutista.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.system.exitProcess

/**
 * 日志与诊断信息（仅在用户开启「Logcat日志」时工作）。
 *
 * 三条链路：
 * 1. [FileLogTree] —— Timber 调用落 `app_*.log`，同时回填 [LogHistory]。
 * 2. [LogcatCaptureManager] —— 本进程全部 logcat 落 `logcat_*.log`（release 下唯一能看到
 *    DEBUG 级与 native 日志的通道），同时回填 [LogHistory]。
 * 3. 崩溃钩子 —— 进程退出前把「[LogHistory] 最近 N 行 + 环境信息 + 堆栈」写 `crash_*.txt`，
 *    因为崩溃时已经没有机会再去读 logcat。
 *
 * 语义：开关打开 = 记录；关闭 = 停止、卸载崩溃钩子、**清空已记录的历史与文件**。
 */
class LogManager(
    private val context: Context,
    private val settingsManager: SettingsManager
) {
    private val logDir = File(context.filesDir, "logs")
    private val history = LogHistory(LogHistory.DEFAULT_CAPACITY)
    private val _logEntries = MutableStateFlow<List<LogEntry>>(emptyList())
    val logEntries: StateFlow<List<LogEntry>> = _logEntries.asStateFlow()

    private var fileTree: FileLogTree? = null
    private var logcatCapture: LogcatCaptureManager? = null
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 崩溃钩子里不能再去阻塞读 DataStore，用这个标记代替。 */
    @Volatile
    private var loggingEnabled = false

    private var crashHandlerInstalled = false
    private var previousCrashHandler: Thread.UncaughtExceptionHandler? = null

    init {
        logDir.mkdirs()
        setupTimber()
        // Clean logs older than 7 days on every startup
        appScope.launch(Dispatchers.IO) {
            cleanOldLogs(7)
        }
    }

    private fun setupTimber() {
        loggingEnabled = settingsManager.isLogEnabledSync()
        if (loggingEnabled) {
            startLogging()
        }
    }

    /**
     * 观察 logEnabled 变化，运行时动态开关。
     * 必须在主线程调用，确保 SettingsManager Flow 正常运行。
     */
    fun observeLogEnabled() {
        appScope.launch {
            settingsManager.logEnabled.collect { enabled ->
                if (enabled) {
                    startLogging()
                } else {
                    stopLogging()
                }
            }
        }
    }

    private fun startLogging() {
        loggingEnabled = true
        if (fileTree == null) {
            fileTree = FileLogTree(logDir, history)
            Timber.plant(fileTree!!)
        }
        if (logcatCapture == null) {
            logcatCapture = LogcatCaptureManager(logDir, history).also { it.start() }
        }
        installCrashHandler()
        appScope.launch(Dispatchers.IO) {
            cleanOldLogs(7)
        }
    }

    /**
     * 关闭日志：停止捕获、卸载崩溃钩子，并清空已记录的内容。
     * 「关闭即清空」是产品要求 —— 关掉开关后不应再有历史残留在磁盘上。
     */
    private fun stopLogging() {
        loggingEnabled = false
        fileTree?.let { Timber.uproot(it) }
        fileTree = null
        logcatCapture?.destroy()
        logcatCapture = null
        uninstallCrashHandler()
        history.clear()
        _logEntries.value = emptyList()
        appScope.launch(Dispatchers.IO) {
            clearLogFiles()
        }
    }

    // ---------------------------------------------------------------- 崩溃钩子

    private fun installCrashHandler() {
        if (crashHandlerInstalled) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        previousCrashHandler = previous
        crashHandlerInstalled = true
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // 崩溃路径上任何异常都必须被吞掉，否则会掩盖原始崩溃。
            try {
                writeCrashReport(thread, error)
            } catch (_: Throwable) {
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                // 没有前任处理器时复刻系统默认行为：直接杀掉进程，不能让 app 假活着。
                android.os.Process.killProcess(android.os.Process.myPid())
                exitProcess(10)
            }
        }
    }

    private fun uninstallCrashHandler() {
        if (!crashHandlerInstalled) return
        Thread.setDefaultUncaughtExceptionHandler(previousCrashHandler)
        previousCrashHandler = null
        crashHandlerInstalled = false
    }

    private fun writeCrashReport(thread: Thread, error: Throwable) {
        if (!loggingEnabled) return
        val now = Date()
        logDir.mkdirs()
        val file = File(logDir, "crash_${CRASH_FILE_FORMAT.format(now)}.txt")
        file.writeText(
            CrashReport.format(
                environment = environmentReport(),
                threadInfo = "${thread.name} (id=${thread.id})",
                history = history.snapshot(),
                error = error,
                timestamp = READABLE_FORMAT.format(now)
            )
        )
    }

    // ---------------------------------------------------------------- 导出

    suspend fun exportLogs(): File? = withContext(Dispatchers.IO) {
        try {
            val exportDir = File(context.cacheDir, "log_export")
            exportDir.mkdirs()
            val zipFile = File(exportDir, "logs_${System.currentTimeMillis()}.zip")

            val logFiles = logDir.listFiles()?.filter { it.isFile } ?: emptyList()

            ZipOutputStream(zipFile.outputStream()).use { zos ->
                logFiles.forEach { file ->
                    val entry = ZipEntry(file.name)
                    zos.putNextEntry(entry)
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
                // 环境信息永远写进包里：即使日志是空的，也能知道是哪台机器、哪一版、
                // 以及捕获器为什么没抓到东西。
                zos.putNextEntry(ZipEntry(DEVICE_INFO_FILE))
                zos.write(buildDeviceInfo(logFiles.size).toByteArray())
                zos.closeEntry()
            }

            zipFile
        } catch (e: Exception) {
            Timber.e(e, "导出日志失败")
            null
        }
    }

    /**
     * 环境信息清单。刻意放进导出包而不是靠 UI 展示：界面保持原样，
     * 但拿到的日志必须自带能定位问题的上下文。
     */
    private fun buildDeviceInfo(logFileCount: Int): String = buildString {
        appendLine("==== Lutista 诊断信息 ====")
        appendLine("导出时间: ${READABLE_FORMAT.format(Date())}")
        appendLine()
        environmentReport().forEach { appendLine(it) }
        appendLine("log files: $logFileCount")
        appendLine("memory entries: ${_logEntries.value.size}")
        appendLine()
        appendLine("---- 说明 ----")
        appendLine("- crash_*.txt 为崩溃现场：崩溃前最近 ${LogHistory.DEFAULT_CAPACITY} 行日志 + 堆栈。")
        appendLine("- release/perf 包经 R8 混淆，堆栈需要用相同 versionCode + gitSha 的")
        appendLine("  mapping.txt 执行 retrace 才能还原。")
    }

    private fun environmentReport(): List<String> = buildList {
        add("app: ${BuildConfig.APPLICATION_ID}")
        add("version: ${BuildConfig.VERSION_NAME} (versionCode ${BuildConfig.VERSION_CODE})")
        add("buildType: ${BuildConfig.BUILD_TYPE}")
        add("gitSha: ${BuildConfig.GIT_SHA}")
        add("device: ${Build.MANUFACTURER} ${Build.MODEL} (device=${Build.DEVICE}, product=${Build.PRODUCT})")
        add("android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        add("abis: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        add("locale: ${Locale.getDefault()} | timezone: ${TimeZone.getDefault().id}")
        add("logcat capture: ${logcatCapture?.status ?: "未启动"}")
        add("memory buffer: ${history.size()}/${LogHistory.DEFAULT_CAPACITY} 行")
    }

    // ---------------------------------------------------------------- 日志入口

    fun d(tag: String, message: String) {
        Timber.tag(tag).d(message)
        addLogEntry(LogLevel.DEBUG, tag, message)
    }

    fun i(tag: String, message: String) {
        Timber.tag(tag).i(message)
        addLogEntry(LogLevel.INFO, tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Timber.tag(tag).w(throwable, message)
        } else {
            Timber.tag(tag).w(message)
        }
        addLogEntry(LogLevel.WARN, tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Timber.tag(tag).e(throwable, message)
        } else {
            Timber.tag(tag).e(message)
        }
        addLogEntry(LogLevel.ERROR, tag, message, throwable)
    }

    private fun addLogEntry(
        level: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable? = null
    ) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
            throwable = throwable
        )
        val current = _logEntries.value.toMutableList()
        current.add(entry)
        if (current.size > MAX_MEMORY_ENTRIES) {
            current.removeAt(0)
        }
        _logEntries.value = current
    }

    // ---------------------------------------------------------------- 清理

    suspend fun cleanOldLogs(keepDays: Int = 7) = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - keepDays * 24 * 60 * 60 * 1000L
        logDir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }

    /** 删除日志目录下的所有文件（关闭开关时调用）。 */
    private fun clearLogFiles() {
        logDir.listFiles()?.forEach { file ->
            if (file.isFile) {
                file.delete()
            }
        }
    }

    companion object {
        private const val MAX_MEMORY_ENTRIES = 1000
        private const val DEVICE_INFO_FILE = "device.txt"
        private val READABLE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        private val CRASH_FILE_FORMAT = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
    }
}

data class LogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val throwable: Throwable? = null
)

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/**
 * 把 Timber 调用写进 `app_*.log` 并回填 [LogHistory]。
 *
 * 不再过滤 DEBUG：release 下 `Timber.DebugTree` 不会 plant，这里是这些调用的唯一出口，
 * 丢掉 DEBUG 等于丢掉一半信息。是否记录整体由「Logcat日志」开关控制。
 */
class FileLogTree(
    private val logDir: File,
    private val history: LogHistory? = null
) : Timber.Tree() {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val date = dateFormat.format(Date())
        val timestamp = timeFormat.format(Date())
        val level = when (priority) {
            Log.VERBOSE -> "V"
            Log.DEBUG -> "D"
            Log.INFO -> "I"
            Log.WARN -> "W"
            Log.ERROR -> "E"
            else -> "?"
        }
        val throwableStr = t?.let { "\n${it.stackTraceToString()}" } ?: ""
        val line = "$timestamp $level/$tag: $message$throwableStr"

        history?.add(line)
        try {
            File(logDir, "app_$date.log").appendText("$line\n")
        } catch (_: Exception) {
        }
    }
}
