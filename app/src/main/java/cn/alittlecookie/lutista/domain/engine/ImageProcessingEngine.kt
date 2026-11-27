package cn.alittlecookie.lutista.domain.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import cn.alittlecookie.lutista.data.model.FilterConfig
import cn.alittlecookie.lutista.data.model.UnifiedLut
import cn.alittlecookie.lutista.gpu.VulkanGpuEngine
import cn.alittlecookie.lutista.manager.LogManager
import cn.alittlecookie.lutista.manager.SettingsManager
import cn.alittlecookie.lutista.util.ExifUtils
import cn.alittlecookie.lutista.util.LutParser
import cn.alittlecookie.lutista.watermark.model.Scene
import cn.alittlecookie.lutista.watermark.renderer.WatermarkProcessor
import java.nio.ByteBuffer
import kotlin.math.pow

class ImageProcessingEngine(
    private val logger: LogManager,
    private val lutParser: LutParser,
    private val lutProcessor: LutProcessor,
    private val watermarkProcessor: WatermarkProcessor,
    private val vulkanGpuEngine: VulkanGpuEngine,
    private val settingsManager: SettingsManager
) {
    companion object {
        private const val TAG = "ImageProcessingEngine"
    }

    @Volatile private var lastLutPath1: String? = null
    @Volatile private var lastLutPath2: String? = null
    @Volatile private var lastLutSize1: Int = 0
    @Volatile private var lastLutSize2: Int = 0
    @Volatile private var lut1Uploaded = false
    @Volatile private var lut2Uploaded = false
    @Volatile private var lastLoggedEv = Float.NaN

    // A cache belongs to the native resource generation, never merely to this Kotlin instance.
    private var cacheGeneration = Long.MIN_VALUE

    fun invalidateLutCache() = vulkanGpuEngine.withSession {
        clearUploadCache()
        vulkanGpuEngine.invalidateResourceCache()
    }

    private fun clearUploadCache() {
        lastLutPath1 = null
        lastLutPath2 = null
        lastLutSize1 = 0
        lastLutSize2 = 0
        lut1Uploaded = false
        lut2Uploaded = false
        lastCurveHash = null
        cacheGeneration = Long.MIN_VALUE
    }

    /** Must be called inside the shared GPU session (uploads and dispatch are one transaction). */
    private fun processBytesLocked(
        rgbaBytes: ByteArray, width: Int, height: Int, filter: FilterConfig
    ): Bitmap? {
        if (!vulkanGpuEngine.isAvailable) return null
        if (cacheGeneration != vulkanGpuEngine.resourceGeneration) clearUploadCache()
        try {
            uploadLutIfNeeded(filter)
            uploadCurvesIfNeeded(filter)
            cacheGeneration = vulkanGpuEngine.resourceGeneration
            val resultBytes = vulkanGpuEngine.processImage(rgbaBytes, width, height, buildGpuParams(filter))
                ?: return null
            if (resultBytes.size.toLong() != width.toLong() * height * 4) return null
            val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                result.copyPixelsFromBuffer(ByteBuffer.wrap(resultBytes))
                return result
            } catch (e: Exception) {
                result.recycle()
                throw e
            }
        } catch (e: Exception) {
            clearUploadCache()
            throw e
        }
    }

    fun processImageGpuFromBytes(
        rgbaBytes: ByteArray, width: Int, height: Int, filter: FilterConfig
    ): Bitmap? = try {
        // Retain the existing borrowed-input contract: editor buffers can be reused after return.
        val inputCopy = rgbaBytes.copyOf()
        vulkanGpuEngine.withSession { processBytesLocked(inputCopy, width, height, filter) }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        logger.w(TAG, "GPU processing from bytes failed", e)
        null
    }

    /** Non-blocking preview lease; owns neither bitmap nor bytes and never invokes a watermark. */
    fun tryProcessLiveViewRgba(
        rgbaBytes: ByteArray, width: Int, height: Int, filter: FilterConfig
    ): Bitmap? = try {
        vulkanGpuEngine.tryWithSession { processBytesLocked(rgbaBytes, width, height, filter) }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        logger.w(TAG, "Live-view GPU processing failed", e)
        null
    }

    fun processImageGpuFromUri(context: Context, uri: String, filter: FilterConfig): Bitmap? {
        var inputBitmap: Bitmap? = null
        return try {
            inputBitmap = context.contentResolver.openInputStream(Uri.parse(uri))?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }?.let { ExifUtils.correctOrientation(it, context, Uri.parse(uri)) }
            val input = inputBitmap ?: return null
            processImagePreview(input, filter)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            logger.w(TAG, "GPU processing from URI failed", e)
            null
        } finally {
            inputBitmap?.recycle()
        }
    }

    fun processImagePreview(bitmap: Bitmap, filter: FilterConfig): Bitmap {
        val rgbaBytes = ByteArray(Math.multiplyExact(Math.multiplyExact(bitmap.width, bitmap.height), 4))
        bitmap.copyPixelsToBuffer(ByteBuffer.wrap(rgbaBytes))
        return vulkanGpuEngine.withSession {
            processBytesLocked(rgbaBytes, bitmap.width, bitmap.height, filter)
                ?: throw IllegalStateException("Vulkan GPU unavailable or processing failed")
        }
    }

    fun processImageWithUnifiedLut(bitmap: Bitmap, unifiedLut: UnifiedLut): Bitmap {
        logger.d(TAG, "Processing image with unified LUT")
        return lutProcessor.processWithUnifiedLut(bitmap, unifiedLut)
    }

    fun applyWatermark(bitmap: Bitmap, scene: Scene?): Bitmap {
        if (scene == null) return bitmap
        logger.d(TAG, "Applying watermark")
        return watermarkProcessor.applyWatermark(bitmap, scene)
    }

    private fun uploadLutIfNeeded(filter: FilterConfig) {
        val targetSize = settingsManager.getLutPrecisionSync()
        if (filter.lutPath1 != lastLutPath1 || targetSize != lastLutSize1) {
            lut1Uploaded = false
            if (filter.lutPath1 != null) {
                val lut = lutProcessor.loadLutForGpu(filter.lutPath1, targetSize)
                if (lut != null) {
                    check(vulkanGpuEngine.uploadLut(lut, 0)) { "LUT slot 0 upload failed" }
                    lut1Uploaded = true
                }
            }
            lastLutPath1 = filter.lutPath1
            lastLutSize1 = targetSize
        }
        if (filter.lutPath2 != lastLutPath2 || targetSize != lastLutSize2) {
            lut2Uploaded = false
            if (filter.lutPath2 != null) {
                val lut = lutProcessor.loadLutForGpu(filter.lutPath2, targetSize)
                if (lut != null) {
                    check(vulkanGpuEngine.uploadLut(lut, 1)) { "LUT slot 1 upload failed" }
                    lut2Uploaded = true
                }
            }
            lastLutPath2 = filter.lutPath2
            lastLutSize2 = targetSize
        }
    }

    @Volatile private var lastCurveHash: Int? = null

    private fun uploadCurvesIfNeeded(filter: FilterConfig) {
        val curveData = buildCurveData(filter.curveConfig)
        val hash = curveData.contentHashCode()
        if (hash != lastCurveHash) {
            check(vulkanGpuEngine.uploadCurves(curveData)) { "Curve upload failed" }
            lastCurveHash = hash
        }
    }

    private fun buildCurveData(config: cn.alittlecookie.lutista.data.model.CurveConfig): FloatArray {
        val curves = FloatArray(1028)
        val redCurve = buildChannelCurve(config.redChannel)
        val greenCurve = buildChannelCurve(config.greenChannel)
        val blueCurve = buildChannelCurve(config.blueChannel)
        val whiteCurve = buildChannelCurve(config.whiteChannel)
        for (i in 0 until 256) {
            curves[i] = redCurve[i] / 255f
            curves[256 + i] = greenCurve[i] / 255f
            curves[512 + i] = blueCurve[i] / 255f
            curves[768 + i] = whiteCurve[i] / 255f
        }
        curves[1024] = if (config.redChannel.isNotEmpty()) 1f else 0f
        curves[1025] = if (config.greenChannel.isNotEmpty()) 1f else 0f
        curves[1026] = if (config.blueChannel.isNotEmpty()) 1f else 0f
        curves[1027] = if (config.whiteChannel.isNotEmpty()) 1f else 0f
        return curves
    }

    private fun buildChannelCurve(points: List<cn.alittlecookie.lutista.data.model.FloatPoint>): IntArray {
        val lut = IntArray(256)
        val middlePoints = points.filter { it.x > 0.001f && it.x < 0.999f }
        val startPt = points.firstOrNull()?.takeIf { it.x <= 0.001f }
            ?: cn.alittlecookie.lutista.data.model.FloatPoint(0f, 0f)
        val endPt = points.lastOrNull()?.takeIf { it.x >= 0.999f }
            ?: cn.alittlecookie.lutista.data.model.FloatPoint(1f, 1f)
        val sorted = (listOf(startPt) + middlePoints + listOf(endPt)).sortedBy { it.x }
        for (i in 0 until 256) {
            val x = i.toFloat() / 255f
            val y = cn.alittlecookie.lutista.util.CurveInterpolator.interpolate(sorted, x)
            lut[i] = (y.coerceIn(0f, 1f) * 255).toInt().coerceIn(0, 255)
        }
        return lut
    }

    /**
     * Deterministic grain seed for the shader's `random()` / `gaussianNoise()`.
     *
     * The seed used to be `System.currentTimeMillis() % 1000`, i.e. it changed on
     * every frame: the grain pattern visibly boiled while dragging any slider and
     * could never match between the preview and the full resolution export.
     *
     * The seed is derived only from the **spatial** grain settings, so that
     * (a) preview and export render the identical pattern, and (b) changing an
     * amplitude-only knob (global strength, per-tone strength ratios) does not
     * reshuffle the pattern under the user's eyes.
     *
     * @param grain the filter's grain configuration.
     * @return seed in `[0, 1)`.
     */
    private fun grainSeed(grain: cn.alittlecookie.lutista.data.model.GrainConfig): Float {
        var h = grain.grainSize.hashCode()
        h = 31 * h + grain.shadowSizeRatio.hashCode()
        h = 31 * h + grain.midtoneSizeRatio.hashCode()
        h = 31 * h + grain.highlightSizeRatio.hashCode()
        h = 31 * h + grain.shadowThreshold.hashCode()
        h = 31 * h + grain.highlightThreshold.hashCode()
        h = 31 * h + grain.redChannelRatio.hashCode()
        h = 31 * h + grain.blueChannelRatio.hashCode()
        h = 31 * h + grain.channelCorrelation.hashCode()
        h = 31 * h + grain.colorPreservation.hashCode()
        // `and 0x7FFFFFFF` instead of Math.abs() to keep Int.MIN_VALUE positive.
        return ((h and 0x7FFFFFFF) % 1000).toFloat() / 1000f
    }

    private fun buildGpuParams(filter: FilterConfig): FloatArray {
        val params = FloatArray(57)
        var idx = 0
        params[idx++] = if (lut1Uploaded) filter.lutConcentration1 else 0f
        params[idx++] = if (lut2Uploaded) filter.lutConcentration2 else 0f
        // Use the sizes snapshotted by this transaction; preferences may change during dispatch.
        params[idx++] = lastLutSize1.toFloat()
        params[idx++] = lastLutSize2.toFloat()
        // 曝光滑杆存的是 EV*10，这里换算成真正的档位后交给 shader（gain = 2^EV）。
        // 打一条只在数值变化时输出的日志，方便用户回传的 logcat 里直接看到实际档位。
        val exposureEv = filter.exposure / 10f
        if (exposureEv != lastLoggedEv) {
            lastLoggedEv = exposureEv
            logger.d(TAG, "exposure: slider=${filter.exposure} ev=$exposureEv gain=${2f.pow(exposureEv)}")
        }
        params[idx++] = exposureEv
        params[idx++] = filter.contrast
        params[idx++] = filter.highlights
        params[idx++] = filter.shadows
        params[idx++] = filter.saturation
        params[idx++] = filter.colorTemperature
        params[idx++] = filter.tint
        params[idx++] = filter.vignetteStrength
        params[idx++] = filter.grainConfig.globalStrength
        params[idx++] = grainSeed(filter.grainConfig)
        params[idx++] = filter.grainConfig.shadowThreshold / 255f
        params[idx++] = filter.grainConfig.highlightThreshold / 255f
        params[idx++] = filter.grainConfig.shadowGrainRatio
        params[idx++] = filter.grainConfig.midtoneGrainRatio
        params[idx++] = filter.grainConfig.highlightGrainRatio
        params[idx++] = filter.grainConfig.shadowSizeRatio
        params[idx++] = filter.grainConfig.highlightSizeRatio
        params[idx++] = if (lut1Uploaded) 1f else 0f
        params[idx++] = if (lut2Uploaded) 1f else 0f
        params[idx++] = if (filter.grainConfig.globalStrength > 0f) 1f else 0f
        val lchZones = listOf(
            filter.lchConfig.red, filter.lchConfig.orange, filter.lchConfig.yellow,
            filter.lchConfig.green, filter.lchConfig.cyan, filter.lchConfig.blue,
            filter.lchConfig.purple, filter.lchConfig.skin
        )
        val lchEnabled = lchZones.any { it.lightness != 0f || it.chroma != 0f || it.hue != 0f }
        for (zone in lchZones) {
            params[idx++] = zone.lightness
            params[idx++] = zone.chroma
            params[idx++] = zone.hue
        }
        val curveEnabled = filter.curveConfig.redChannel.isNotEmpty() ||
            filter.curveConfig.greenChannel.isNotEmpty() ||
            filter.curveConfig.blueChannel.isNotEmpty() ||
            filter.curveConfig.whiteChannel.isNotEmpty()
        params[idx++] = if (lchEnabled) 1f else 0f
        params[idx++] = if (curveEnabled) 1f else 0f
        params[idx++] = filter.grainConfig.midtoneSizeRatio
        params[idx++] = filter.grainConfig.redChannelRatio
        params[idx++] = filter.grainConfig.blueChannelRatio
        params[idx++] = filter.grainConfig.channelCorrelation
        params[idx++] = filter.grainConfig.colorPreservation
        params[idx++] = filter.grainConfig.grainSize
        params[idx++] = filter.lutGlobalStrength
        return params
    }

    suspend fun computeUnifiedLut(filter: FilterConfig): UnifiedLut {
        val size = filter.lutPrecision
        val data = FloatArray(size * size * size * 3)
        for (b in 0 until size) {
            for (g in 0 until size) {
                for (r in 0 until size) {
                    val rNorm = r.toFloat() / (size - 1)
                    val gNorm = g.toFloat() / (size - 1)
                    val bNorm = b.toFloat() / (size - 1)
                    var color = floatArrayOf(rNorm, gNorm, bNorm)
                    color = lutProcessor.applyLutToColor(color, filter)
                    val idx = (b * size * size + g * size + r) * 3
                    data[idx] = color[0].coerceIn(0f, 1f)
                    data[idx + 1] = color[1].coerceIn(0f, 1f)
                    data[idx + 2] = color[2].coerceIn(0f, 1f)
                }
            }
        }
        return UnifiedLut(size = size, data = data)
    }

    fun computeFilterHash(filter: FilterConfig): String {
        val sb = StringBuilder()
        sb.append(filter.lutPath1)
        sb.append(filter.lutConcentration1)
        sb.append(filter.lutPath2)
        sb.append(filter.lutConcentration2)
        sb.append(filter.lutPrecision)
        sb.append(filter.exposure)
        sb.append(filter.contrast)
        sb.append(filter.highlights)
        sb.append(filter.shadows)
        sb.append(filter.saturation)
        sb.append(filter.colorTemperature)
        sb.append(filter.tint)
        return sb.toString().hashCode().toString()
    }
}
