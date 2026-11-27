package cn.alittlecookie.lutista.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import cn.alittlecookie.lutista.MainActivity
import cn.alittlecookie.lutista.R
import cn.alittlecookie.lutista.data.database.entity.TaskSource
import cn.alittlecookie.lutista.manager.LogManager
import cn.alittlecookie.lutista.manager.SettingsManager
import cn.alittlecookie.lutista.tether.transfer.TetherProcessingSuppression
import cn.alittlecookie.lutista.util.MediaStoreScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

class FileMonitorService : LifecycleService(), KoinComponent {

    private val taskSubmissionManager: TaskSubmissionManager by inject()
    private val logger: LogManager by inject()
    private val settingsManager: SettingsManager by inject()
    private val mediaStoreScanner: MediaStoreScanner by inject()

    private val scannedFiles = ConcurrentHashMap.newKeySet<String>()
    private val skippedFiles = ConcurrentHashMap.newKeySet<String>()
    private var monitorPath: String = ""
    private var lastMediaStoreTimestamp: Long = 0L
    private var monitoringJob: Job? = null
    private var onlyNewFiles: Boolean = true
    private val binder = android.os.Binder()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification(0))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                val newPath = intent.getStringExtra(EXTRA_PATH) ?: ""
                if (newPath.isEmpty()) {
                    logger.w(TAG, "监控路径为空，无法启动")
                    return START_NOT_STICKY
                }
                if (newPath == monitorPath && monitoringJob?.isActive == true) {
                    logger.d(TAG, "已在监控相同路径，跳过重复启动")
                    return START_STICKY
                }
                monitoringJob?.cancel()
                monitorPath = newPath
                onlyNewFiles = intent.getBooleanExtra(EXTRA_ONLY_NEW, true)
                lastMediaStoreTimestamp = System.currentTimeMillis() / 1000 - 60
                mediaStoreScanner.clearSeenUris()
                scannedFiles.clear()
                skippedFiles.clear()
                if (onlyNewFiles) {
                    lifecycleScope.launch(Dispatchers.IO) { markExistingFilesAsSeen() }
                }
                CoroutineScope(Dispatchers.IO).launch { settingsManager.resetProcessedCount() }
                startForeground(NOTIFICATION_ID, createNotification(0))
                startMonitoring()
            }
            ACTION_STOP -> {
                CoroutineScope(Dispatchers.IO).launch { settingsManager.setMonitorRunning(false) }
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    private fun startMonitoring() {
        logger.i(TAG, "开始监控文件夹: $monitorPath")

        lifecycleScope.launch {
            settingsManager.processedCount.distinctUntilChanged().collectLatest { count ->
                updateNotification(count)
            }
        }

        monitoringJob = lifecycleScope.launch {
            logger.i(TAG, "监控循环已启动")
            while (isActive) {
                try {
                    scanAndProcessFiles()
                } catch (e: Exception) {
                    logger.e(TAG, "扫描文件夹异常", e)
                }
                delay(SCAN_INTERVAL)
            }
            logger.w(TAG, "监控循环已停止 (isActive=false)")
        }
    }

    private suspend fun scanAndProcessFiles() = withContext(Dispatchers.IO) {
        val isSafUri = monitorPath.startsWith("content://")
        val newFiles = if (isSafUri) {
            scanSafFolder(monitorPath.toUri())
        } else {
            scanFolderWithMediaStore(monitorPath)
        }

        if (newFiles.isNotEmpty()) {
            logger.i(TAG, "本次扫描发现 ${newFiles.size} 个新文件")
        } else {
            logger.i(TAG, "本次扫描未发现新文件 (monitorPath=$monitorPath)")
        }

        for (filePath in newFiles) {
            if (filePath in scannedFiles || filePath in skippedFiles) continue

            try {
                val file = File(filePath)
                if (TetherProcessingSuppression.shared.shouldSuppress(filePath, file.name, file.length())) {
                    scannedFiles.add(filePath)
                    continue
                }
                if (!file.exists() || !file.canRead()) {
                    logger.w(TAG, "文件不可访问，跳过: $filePath")
                    skippedFiles.add(filePath)
                    continue
                }

                scannedFiles.add(filePath)
                taskSubmissionManager.submitTask(
                    sourcePath = filePath,
                    source = TaskSource.MONITOR
                )
                logger.i(TAG, "发现新文件: $filePath")
            } catch (e: Exception) {
                logger.e(TAG, "处理文件异常: $filePath", e)
                skippedFiles.add(filePath)
            }
        }
    }

    /**
     * Scan using MediaStore (preferred) + fall back to File.listFiles.
     * MediaStore-detected files are system-verified and skip the stability check.
     */
    private suspend fun scanFolderWithMediaStore(folderPath: String): List<String> {
        val results = mutableListOf<String>()

        if (mediaStoreScanner.isMediaStoreAccessible(folderPath)) {
            try {
                val images = mediaStoreScanner.queryNewImages(folderPath, lastMediaStoreTimestamp)

                for (image in images) {
                    if (TetherProcessingSuppression.shared.shouldSuppress(
                            image.uri.toString(), image.displayName, image.size)) {
                        if (image.dateAdded > lastMediaStoreTimestamp) lastMediaStoreTimestamp = image.dateAdded
                        continue
                    }
                    val localPath = copyMediaStoreToCache(image.uri, image.displayName)
                    if (localPath != null) {
                        results.add(localPath)
                    }
                    if (image.dateAdded > lastMediaStoreTimestamp) {
                        lastMediaStoreTimestamp = image.dateAdded
                    }
                }

                if (images.isNotEmpty()) {
                    logger.d(TAG, "MediaStore发现 ${images.size} 张新图")
                    return results
                }
            } catch (e: SecurityException) {
                logger.w(TAG, "MediaStore查询被拒绝，回退到文件系统扫描")
            } catch (e: Exception) {
                logger.w(TAG, "MediaStore扫描失败，回退到文件系统", e)
            }
        }

        val fallbackFiles = scanFolderWithFilesystem(folderPath)
        results.addAll(fallbackFiles)
        return results
    }

    /**
     * When onlyNewFiles=true, pre-mark all existing files as "already seen"
     * so they won't be processed on subsequent scan cycles.
     */
    private fun markExistingFilesAsSeen() {
        var count = 0
        if (monitorPath.startsWith("content://")) {
            try {
                val docDir = DocumentFile.fromTreeUri(this, monitorPath.toUri())
                docDir?.listFiles()?.forEach { child ->
                    val name = child.name ?: return@forEach
                    if (isImageFile(name)) {
                        scannedFiles.add(child.uri.toString())
                        count++
                    }
                }
            } catch (_: Exception) { }
        } else {
            val dir = File(monitorPath)
            if (dir.isDirectory) {
                dir.listFiles { f -> f.isFile && isImageFile(f.name) }?.forEach {
                    scannedFiles.add(it.absolutePath)
                    count++
                }
            }
        }
        if (mediaStoreScanner.isMediaStoreAccessible(monitorPath)) {
            try {
                val images = mediaStoreScanner.queryLatestImages(monitorPath, 0)
                mediaStoreScanner.markAllAsSeen(images.map { it.uri.toString() })
                logger.d(TAG, "MediaStore: 已标记 ${images.size} 个已有文件")
            } catch (_: SecurityException) { }
        }
        logger.i(TAG, "标记了 $count 个已有文件 (onlyNewFiles=true)")
    }

    /**
     * Fallback: scan using java.io.File with batch stability check.
     */
    private suspend fun scanFolderWithFilesystem(folderPath: String): List<String> {
        val results = mutableListOf<String>()
        val dir = File(folderPath)
        if (!dir.exists() || !dir.isDirectory) return results

        val imageFiles = dir.listFiles { file ->
            file.isFile && isImageFile(file.name) && file.absolutePath !in scannedFiles &&
                !TetherProcessingSuppression.shared.shouldSuppress(file.absolutePath, file.name, file.length())
        }?.filter { it.absolutePath !in skippedFiles && it.canRead() } ?: return results

        if (imageFiles.isEmpty()) return results

        val sizesBefore = imageFiles.map { it.length() }
        delay(FILE_STABILITY_DELAY.milliseconds)
        val sizesAfter = imageFiles.map { it.length() }

        for (i in imageFiles.indices) {
            val file = imageFiles[i]
            val path = file.absolutePath
            try {
                if (sizesBefore[i] == sizesAfter[i] && sizesAfter[i] > 0) {
                    results.add(path)
                } else {
                    logger.i(TAG, "文件尚未写入完成，下次重试: $path")
                }
            } catch (e: Exception) {
                logger.e(TAG, "处理文件异常: $path", e)
                skippedFiles.add(path)
            }
        }
        return results
    }

    /**
     * Scan a SAF (content://) folder using DocumentFile.
     * Collects all candidates, does one batch stability wait, then processes stable files.
     */
    private suspend fun scanSafFolder(treeUri: Uri): List<String> {
        val results = mutableListOf<String>()
        try {
            logger.i(TAG, "SAF: 开始扫描 $treeUri")
            val docDir = DocumentFile.fromTreeUri(this, treeUri)
            if (docDir == null) {
                logger.w(TAG, "SAF: fromTreeUri 返回 null，可能权限过期: $treeUri")
                return results
            }
            val children = docDir.listFiles()

            data class SafCandidate(val doc: DocumentFile, val uriStr: String, val name: String)
            val candidates = mutableListOf<SafCandidate>()

            for (child in children) {
                if (!child.isFile) continue
                val name = child.name ?: continue
                if (!isImageFile(name)) continue
                val uriString = child.uri.toString()
                if (TetherProcessingSuppression.shared.shouldSuppress(uriString, name, child.length())) {
                    scannedFiles.add(uriString)
                    continue
                }
                if (uriString in scannedFiles || uriString in skippedFiles) continue
                candidates.add(SafCandidate(child, uriString, name))
            }

            if (candidates.isEmpty()) return results

            logger.i(TAG, "SAF扫描: 发现 ${candidates.size} 个候选文件，等待 ${FILE_STABILITY_DELAY}ms")
            val sizesBefore = candidates.map { it.doc.length() }
            delay(FILE_STABILITY_DELAY.milliseconds)
            val sizesAfter = candidates.map { it.doc.length() }

            var stableCount = 0
            for (i in candidates.indices) {
                val (doc, uriStr, name) = candidates[i]
                if (sizesBefore[i] != sizesAfter[i] || sizesAfter[i] <= 0L) {
                    logger.i(TAG, "文件尚未写入完成，下次重试: $name (${sizesBefore[i]} → ${sizesAfter[i]})")
                    continue
                }
                if (TetherProcessingSuppression.shared.shouldSuppress(uriStr, name, sizesAfter[i])) {
                    scannedFiles.add(uriStr)
                    continue
                }
                stableCount++
                scannedFiles.add(uriStr)
                val localPath = copySafFileToCache(doc)
                if (localPath != null) {
                    results.add(localPath)
                }
            }
        } catch (e: Exception) {
            logger.e(TAG, "SAF文件夹扫描失败", e)
        }
        return results
    }

    /**
     * Copy a MediaStore content URI to a local cache file, preserving the original display name.
     */
    private fun copyMediaStoreToCache(uri: Uri, displayName: String): String? {
        return try {
            val baseName = displayName.substringBeforeLast('.')
            val ext = displayName.substringAfterLast('.', "")
            var cacheFile = File(cacheDir, displayName)
            var counter = 1
            while (cacheFile.exists()) {
                cacheFile = File(cacheDir, "${baseName}_$counter.$ext")
                counter++
            }
            contentResolver.openInputStream(uri)?.use { input ->
                cacheFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            logger.d(TAG, "MediaStore文件已复制到缓存: $displayName → ${cacheFile.absolutePath}")
            cacheFile.absolutePath
        } catch (e: Exception) {
            logger.e(TAG, "复制MediaStore文件到缓存失败: $displayName ($uri)", e)
            null
        }
    }

    /**
     * Copy a DocumentFile from SAF to a local cache file for processing.
     */
    private fun copySafFileToCache(docFile: DocumentFile): String? {
        return try {
            val name = docFile.name ?: "saf_image_${System.currentTimeMillis()}"
            val cacheFile = File(cacheDir, name)
            contentResolver.openInputStream(docFile.uri)?.use { input ->
                cacheFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            cacheFile.absolutePath
        } catch (e: Exception) {
            logger.e(TAG, "复制SAF文件到缓存失败: ${docFile.uri}", e)
            null
        }
    }

    private suspend fun isFileStable(file: File): Boolean = withContext(Dispatchers.IO) {
        val size1 = file.length()
        delay(FILE_STABILITY_DELAY.milliseconds)
        val size2 = file.length()
        size1 == size2 && size1 > 0
    }

    private fun isImageFile(name: String): Boolean {
        val ext = name.substringAfterLast('.').lowercase()
        return ext in IMAGE_EXTENSIONS
    }

    fun retrySkippedFiles() {
        val files = skippedFiles.toList()
        skippedFiles.clear()
        for (path in files) {
            scannedFiles.remove(path)
        }
        logger.i(TAG, "已重置 ${files.size} 个跳过的文件")
    }

    fun updateMonitorPath(newPath: String) {
        monitorPath = newPath
        scannedFiles.clear()
        skippedFiles.clear()
        mediaStoreScanner.clearSeenUris()
        lastMediaStoreTimestamp = System.currentTimeMillis() / 1000 - 60
        logger.i(TAG, "监控路径已更新: $newPath")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "文件监控",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "监控文件夹中的新图片"
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(count: Int): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("实时处理已启动")
            .setContentText("已处理 $count 张图片")
            .setSmallIcon(R.drawable.ic_stat_lutista)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(count: Int) {
        val notification = createNotification(count)
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        CoroutineScope(Dispatchers.IO).launch { settingsManager.setMonitorRunning(false) }
        super.onDestroy()
        logger.i(TAG, "文件监控服务已停止")
    }

    companion object {
        private const val TAG = "FileMonitorService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "file_monitor_channel"
        private const val SCAN_INTERVAL = 3000L
        private const val FILE_STABILITY_DELAY = 2000L
        const val ACTION_START = "cn.alittlecookie.lutista.START_MONITOR"
        const val ACTION_STOP = "cn.alittlecookie.lutista.STOP_MONITOR"
        const val EXTRA_PATH = "monitor_path"
        const val EXTRA_ONLY_NEW = "only_new_files"

        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp", "tiff", "dng")
    }
}
