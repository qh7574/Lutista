package cn.alittlecookie.lutista.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import cn.alittlecookie.lutista.data.database.entity.TaskEntity
import cn.alittlecookie.lutista.data.database.entity.TaskSource
import cn.alittlecookie.lutista.tether.transfer.TetherProcessingSuppression
import cn.alittlecookie.lutista.data.model.FilterConfig
import cn.alittlecookie.lutista.data.repository.FilterRepository
import cn.alittlecookie.lutista.data.repository.WatermarkRepository
import cn.alittlecookie.lutista.domain.engine.ImageProcessingEngine
import cn.alittlecookie.lutista.manager.LogManager
import cn.alittlecookie.lutista.manager.SettingsManager
import cn.alittlecookie.lutista.util.ExifResolution
import cn.alittlecookie.lutista.util.ExifUtils
import cn.alittlecookie.lutista.util.FileUtils
import cn.alittlecookie.lutista.util.FilenameExpander
import cn.alittlecookie.lutista.util.FilterConfigJson.toConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream

class ImageProcessingService(
    private val context: Context,
    private val engine: ImageProcessingEngine,
    private val filterRepository: FilterRepository,
    private val watermarkRepository: WatermarkRepository,
    private val settingsManager: SettingsManager,
    private val fileUtils: FileUtils,
    private val logger: LogManager
) {
    companion object {
        private const val TAG = "ImageProcessingService"
    }

    suspend fun processImage(
        task: TaskEntity,
        onProgress: (Float) -> Unit
    ) = withContext(Dispatchers.IO) {
        val sourcePath = task.sourcePath
        val isCacheFile = sourcePath.startsWith(context.cacheDir.absolutePath)
        val stagingRoot = File(context.filesDir, ".tether-sources").absolutePath
        val isStagingFile = sourcePath.startsWith(stagingRoot + File.separator)
        var inputBitmap: Bitmap? = null
        var outputBitmap: Bitmap? = null
        var outputReservation: String? = null
        var resolvedOutput: String? = null
        var outputWasPresent = false
        var outputCommitted = false
        var allocatedOutput: TetherOutputAllocator.Allocation? = null

        try {
            logger.i(TAG, "开始处理图像: $sourcePath")

            val sourceFile = File(sourcePath)
            if (!sourceFile.exists()) {
                throw IllegalArgumentException("源文件不存在: $sourcePath")
            }

            onProgress(0.05f)

            // Read EXIF + APP1 from source file before processing
            val sourceBytes = sourceFile.readBytes()
            val app1Segment = ExifUtils.extractApp1FromJpeg(sourceBytes)
            val exifResolution = if (app1Segment != null) {
                val exif = try {
                    androidx.exifinterface.media.ExifInterface(sourceFile.absolutePath)
                } catch (_: Exception) { null }
                if (exif != null) ExifResolution(exif, app1Segment) else null
            } else null
            logger.d(TAG, "EXIF: hasApp1=${app1Segment != null}")

            onProgress(0.1f)

            val filterConfig = resolveFilterConfig(task)
            onProgress(0.15f)

            inputBitmap = BitmapFactory.decodeFile(sourcePath)
                ?.let { ExifUtils.correctOrientation(it, sourcePath) }
                ?: throw IllegalArgumentException("无法读取图像: $sourcePath")

            onProgress(0.2f)

            outputBitmap = if (filterConfig != null) {
                engine.processImagePreview(inputBitmap, filterConfig)
            } else {
                inputBitmap
            }

            onProgress(0.5f)

            val watermarkScene = resolveWatermarkScene(task)
            if (watermarkScene != null) {
                outputBitmap = engine.applyWatermark(outputBitmap, watermarkScene)
            }

            onProgress(0.7f)

            val outputFormat = settingsManager.defaultOutputFormat.first()
            val ext = when (outputFormat) {
                cn.alittlecookie.lutista.manager.OutputFormat.PNG -> "png"
                else -> "jpg"
            }
            val quality = when (outputFormat) {
                cn.alittlecookie.lutista.manager.OutputFormat.PNG -> settingsManager.pngCompression.first()
                else -> settingsManager.jpgQuality.first()
            }
            val format = when (ext) {
                "png" -> Bitmap.CompressFormat.PNG
                else -> Bitmap.CompressFormat.JPEG
            }

            val outputName = if (task.outputPath.isEmpty()) {
                generateOutputFileName(task.sourcePath, ext, filterConfig?.name)
            } else if (task.outputPath.startsWith("content://")) {
                fileUtils.getFileNameFromUri(Uri.parse(task.outputPath))
            } else File(task.outputPath).name
            // Reserve BEFORE resolveOutputPath can create a SAF document visible to the monitor.
            val tetherOutput = task.source == TaskSource.TETHER.name
            if (tetherOutput) {
                TetherOutputAllocator.requireNewSafDestination(task.outputPath)
                outputReservation = TetherProcessingSuppression.shared.reserve(
                    outputName, path = task.outputPath.takeIf { it.isNotEmpty() })
            }
            var outputPath = resolveOutputPath(task, ext, outputName)
            if (tetherOutput && !outputPath.startsWith("content://")) {
                val allocation = TetherOutputAllocator.allocateFile(
                    File(outputPath), sourcePath, task.originalSourcePath, task.outputPath.isNotEmpty()
                ) { candidate ->
                    // An automatic suffix must also be excluded before its atomic creation.
                    outputReservation?.let { TetherProcessingSuppression.shared.bind(it, candidate.absolutePath, candidate.name) }
                }
                allocatedOutput = allocation
                outputPath = allocation.file.absolutePath
            } else if (tetherOutput) {
                val originals = listOfNotNull(sourcePath, task.originalSourcePath)
                    .filter { it.startsWith("content://") }.map(::documentIdentity).toSet()
                TetherOutputAllocator.requireUnprotectedDocument(documentIdentity(outputPath), originals)
            }
            // TETHER allocations are ours; do not infer ownership from exists() after CREATE_NEW.
            resolvedOutput = outputPath
            outputWasPresent = !tetherOutput && if (outputPath.startsWith("content://")) task.outputPath.isNotEmpty()
                else File(outputPath).exists()
            outputReservation?.let { token ->
                val actualName = if (outputPath.startsWith("content://")) {
                    fileUtils.getFileNameFromUri(Uri.parse(outputPath))
                } else File(outputPath).name
                TetherProcessingSuppression.shared.bind(token, outputPath, actualName)
            }
            val written = saveBitmapWithExif(outputBitmap, outputPath, format, quality, exifResolution, allocatedOutput?.stream)
            outputReservation?.let { TetherProcessingSuppression.shared.complete(it, outputPath, written) }
            outputCommitted = true

            onProgress(1.0f)
            logger.i(TAG, "图像处理完成: $outputPath")
        } finally {
            // Encoding can fail before saveBitmapWithExif takes ownership of the reserved stream.
            runCatching { allocatedOutput?.close() }
            if (!outputCommitted && outputReservation != null) {
                val partial = resolvedOutput
                val removed = if (partial == null) true else if (outputWasPresent) false else runCatching {
                    if (partial.startsWith("content://")) {
                        android.provider.DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(partial))
                    } else File(partial).let { !it.exists() || it.delete() }
                }.getOrDefault(false)
                // If cleanup fails, keep the exclusion so a monitor cannot re-import the partial output.
                if (removed) TetherProcessingSuppression.shared.release(outputReservation)
            }
            if (outputBitmap != inputBitmap) {
                inputBitmap?.recycle()
            }
            outputBitmap?.recycle()

            if (isCacheFile || (isStagingFile && outputCommitted)) {
                try {
                    val source = File(sourcePath)
                    source.delete()
                    // The committed destination now owns the only copy; drop the empty staging folder.
                    val parent = source.parentFile
                    if (isStagingFile && parent != null) {
                        parent.listFiles()?.takeIf { it.isEmpty() }?.let { parent.delete() }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private suspend fun resolveFilterConfig(task: TaskEntity): FilterConfig? {
        val filterId = task.filterId ?: run {
            val globalId = settingsManager.globalFilterId.first()
            if (globalId > 0L) globalId else return null
        }
        val entity = filterRepository.getFilterById(filterId) ?: return null
        return entity.toConfig()
    }

    private suspend fun resolveWatermarkScene(task: TaskEntity): cn.alittlecookie.lutista.watermark.model.Scene? {
        val watermarkId = if (task.watermarkId != null) {
            task.watermarkId
        } else {
            val globalId = settingsManager.globalWatermarkId.first()
            if (globalId == "none" || globalId.isEmpty()) return null
            globalId.toLongOrNull() ?: return null
        }
        if (watermarkId <= 0L) return null
        return watermarkRepository.getSceneById(watermarkId)
    }

    private fun documentIdentity(path: String): String {
        val uri = Uri.parse(path)
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
        return if (documentId != null) "${uri.authority}:$documentId" else uri.normalizeScheme().toString()
    }

    private suspend fun resolveOutputPath(task: TaskEntity, ext: String, name: String): String {
        if (task.outputPath.isNotEmpty()) return task.outputPath

        val customPath = settingsManager.outputPath.first()
        if (customPath.isEmpty()) {
            val dir = fileUtils.getDefaultOutputDir()
            return fileUtils.buildOutputPath(dir, name)
        }

        if (customPath.startsWith("content://")) {
            val baseUri = Uri.parse(customPath)
            val fileUri = fileUtils.createOutputFileInSafDir(baseUri, name, "image/$ext")
                ?: return fallbackToDefaultFile(name)
            return fileUri.toString()
        }

        val dir = fileUtils.getOutputDir(customPath)
        return fileUtils.buildOutputPath(dir, name)
    }

    private fun fallbackToDefaultFile(name: String): String {
        val dir = fileUtils.getDefaultOutputDir()
        return fileUtils.buildOutputPath(dir, name)
    }

    private suspend fun generateOutputFileName(sourcePath: String, ext: String, filterName: String?): String {
        val fname = FileUtils.extractBaseName(sourcePath)
        val filter = filterName ?: ""
        val template = settingsManager.outputFilenameTemplate.first()
        val daily = settingsManager.getDailyCounter()
        val monthly = settingsManager.getMonthlyCounter()
        val yearly = settingsManager.getYearlyCounter()
        return "${FilenameExpander.expand(template, fname, filter, daily, monthly, yearly)}.$ext"
    }

    private fun saveBitmapWithExif(bitmap: Bitmap, outputPath: String, format: Bitmap.CompressFormat, quality: Int, exifResolution: ExifResolution?, reservedStream: OutputStream? = null): Long {
        val bos = ByteArrayOutputStream()
        check(bitmap.compress(format, quality, bos)) { "无法编码输出图像" }
        var outputBytes = bos.toByteArray()

        // Inject EXIF via APP1 for JPEG outputs
        if (format == Bitmap.CompressFormat.JPEG && exifResolution?.app1Segment != null) {
            val app1 = ExifUtils.setOrientationInApp1(exifResolution.app1Segment, 1)
            outputBytes = ExifUtils.injectApp1ToJpeg(outputBytes, app1)
            logger.d(TAG, "EXIF injected via APP1 (${app1.size} bytes)")
        }

        if (reservedStream != null) {
            reservedStream.use { it.write(outputBytes) }
        } else if (outputPath.startsWith("content://")) {
            context.contentResolver.openOutputStream(Uri.parse(outputPath))?.use { stream ->
                stream.write(outputBytes)
            } ?: throw IllegalStateException("无法打开输出流: $outputPath")
        } else {
            val file = File(outputPath)
            file.parentFile?.mkdirs()
            file.writeBytes(outputBytes)
        }
        return outputBytes.size.toLong()
    }
}
