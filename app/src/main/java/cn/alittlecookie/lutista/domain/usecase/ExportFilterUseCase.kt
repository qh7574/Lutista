package cn.alittlecookie.lutista.domain.usecase

import android.content.Context
import android.net.Uri
import cn.alittlecookie.lutista.data.database.entity.FilterEntity
import cn.alittlecookie.lutista.data.model.FloatPoint
import cn.alittlecookie.lutista.data.model.LutData
import cn.alittlecookie.lutista.data.repository.FilterRepository
import cn.alittlecookie.lutista.domain.engine.ColorPipelineMath
import cn.alittlecookie.lutista.manager.LogManager
import cn.alittlecookie.lutista.util.CurveInterpolator
import cn.alittlecookie.lutista.util.FilterConfigJson
import cn.alittlecookie.lutista.util.LutParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class ExportFilterUseCase(
    private val context: Context,
    private val filterRepository: FilterRepository,
    private val lutParser: LutParser,
    private val logger: LogManager
) {
    companion object {
        private const val TAG = "ExportFilterUseCase"
        private const val DEFAULT_UNIFIED_LUT_SIZE = 33
    }

    sealed class ExportResult {
        data class Success(val filePath: String, val exportType: ExportType) : ExportResult()
        data class Error(val message: String) : ExportResult()
    }

    enum class ExportType {
        UNIFIED_LUT,
        FILTER_CONFIG
    }

    suspend fun exportUnifiedLut(filterId: Long): ExportResult = withContext(Dispatchers.IO) {
        try {
            val filter = filterRepository.getFilterById(filterId)
                ?: return@withContext ExportResult.Error("滤镜不存在")

            val unifiedLutData = computeUnifiedLut(filter)
            val cacheFile = java.io.File(context.cacheDir, "export_${System.currentTimeMillis()}.cube")
            cacheFile.outputStream().use { output ->
                writeCubeToStream(output, unifiedLutData, filter.name)
            }

            logger.i(TAG, "统一LUT已生成: ${cacheFile.absolutePath}")
            ExportResult.Success(cacheFile.absolutePath, ExportType.UNIFIED_LUT)
        } catch (e: Exception) {
            logger.e(TAG, "导出统一LUT失败", e)
            ExportResult.Error(e.message ?: "未知错误")
        }
    }

    suspend fun exportUnifiedLutTo(filterId: Long, uri: Uri): ExportResult = withContext(Dispatchers.IO) {
        try {
            val filter = filterRepository.getFilterById(filterId)
                ?: return@withContext ExportResult.Error("滤镜不存在")

            val unifiedLutData = computeUnifiedLut(filter)
            context.contentResolver.openOutputStream(uri)?.use { output ->
                writeCubeToStream(output, unifiedLutData, filter.name)
            } ?: return@withContext ExportResult.Error("无法打开输出文件")

            logger.i(TAG, "统一LUT已导出: $uri")
            ExportResult.Success(uri.toString(), ExportType.UNIFIED_LUT)
        } catch (e: Exception) {
            logger.e(TAG, "导出统一LUT失败", e)
            ExportResult.Error(e.message ?: "未知错误")
        }
    }

    suspend fun exportFilterConfig(filterId: Long): ExportResult = withContext(Dispatchers.IO) {
        try {
            val filter = filterRepository.getFilterById(filterId)
                ?: return@withContext ExportResult.Error("滤镜不存在")

            val cacheFile = java.io.File(context.cacheDir, "export_${System.currentTimeMillis()}.zip")
            java.util.zip.ZipOutputStream(cacheFile.outputStream()).use { zos ->
                writeConfigZip(filter, zos)
            }

            logger.i(TAG, "滤镜配置已生成: ${cacheFile.absolutePath}")
            ExportResult.Success(cacheFile.absolutePath, ExportType.FILTER_CONFIG)
        } catch (e: Exception) {
            logger.e(TAG, "导出滤镜配置失败", e)
            ExportResult.Error(e.message ?: "未知错误")
        }
    }

    suspend fun exportFilterConfigTo(filterId: Long, uri: Uri): ExportResult = withContext(Dispatchers.IO) {
        try {
            val filter = filterRepository.getFilterById(filterId)
                ?: return@withContext ExportResult.Error("滤镜不存在")

            context.contentResolver.openOutputStream(uri)?.use { output ->
                java.util.zip.ZipOutputStream(output).use { zos ->
                    writeConfigZip(filter, zos)
                }
            } ?: return@withContext ExportResult.Error("无法打开输出文件")

            logger.i(TAG, "滤镜配置已导出: $uri")
            ExportResult.Success(uri.toString(), ExportType.FILTER_CONFIG)
        } catch (e: Exception) {
            logger.e(TAG, "导出滤镜配置失败", e)
            ExportResult.Error(e.message ?: "未知错误")
        }
    }

    private fun writeConfigZip(filter: FilterEntity, zos: java.util.zip.ZipOutputStream) {
        filter.lutPath1?.let { lutPath ->
            val lutFile = java.io.File(lutPath)
            if (lutFile.exists()) {
                zos.putNextEntry(java.util.zip.ZipEntry("${filter.name}1.cube"))
                lutFile.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        filter.lutPath2?.let { lutPath ->
            val lutFile = java.io.File(lutPath)
            if (lutFile.exists()) {
                zos.putNextEntry(java.util.zip.ZipEntry("${filter.name}2.cube"))
                lutFile.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        val configJson = createFilterConfigJson(filter)
        zos.putNextEntry(java.util.zip.ZipEntry("${filter.name}.json"))
        zos.write(configJson.toString(2).toByteArray())
        zos.closeEntry()
    }

    private fun computeUnifiedLut(filter: FilterEntity): LutData {
        val size = filter.lutPrecision.coerceIn(2, 128)
        val data = FloatArray(size * size * size * 3)

        val lut1 = filter.lutPath1?.let { loadLutData(it) }
        val lut2 = filter.lutPath2?.let { loadLutData(it) }

        val lutGlobalStrength = filter.lutGlobalStrength
        val lutConcentration1 = filter.lutConcentration1
        val lutConcentration2 = filter.lutConcentration2

        // Build curve LUTs
        val curveConfig = FilterConfigJson.jsonToCurveConfig(filter.curveJson)
        val redCurve = buildCurveLut(curveConfig.redChannel)
        val greenCurve = buildCurveLut(curveConfig.greenChannel)
        val blueCurve = buildCurveLut(curveConfig.blueChannel)
        val whiteCurve = buildCurveLut(curveConfig.whiteChannel)

        // Parse LCH config
        val lchConfig = FilterConfigJson.jsonToLchConfig(filter.lchJson)
        val lchEnabled = listOf(
            lchConfig.red, lchConfig.orange, lchConfig.yellow, lchConfig.green,
            lchConfig.cyan, lchConfig.blue, lchConfig.purple, lchConfig.skin
        ).any { it.lightness != 0f || it.chroma != 0f || it.hue != 0f }

        for (b in 0 until size) {
            for (g in 0 until size) {
                for (r in 0 until size) {
                    val idx = (b * size * size + g * size + r) * 3
                    val red = r.toFloat() / (size - 1)
                    val green = g.toFloat() / (size - 1)
                    val blue = b.toFloat() / (size - 1)

                    val original = floatArrayOf(red, green, blue)
                    var color = original.copyOf()

                    if (lut1 != null) {
                        val applied = lutParser.trilinearInterpolation(lut1, color[0], color[1], color[2])
                        color = floatArrayOf(
                            color[0] * (1 - lutConcentration1) + applied[0] * lutConcentration1,
                            color[1] * (1 - lutConcentration1) + applied[1] * lutConcentration1,
                            color[2] * (1 - lutConcentration1) + applied[2] * lutConcentration1
                        )
                    }

                    if (lut2 != null) {
                        val applied = lutParser.trilinearInterpolation(lut2, color[0], color[1], color[2])
                        color = floatArrayOf(
                            color[0] * (1 - lutConcentration2) + applied[0] * lutConcentration2,
                            color[1] * (1 - lutConcentration2) + applied[1] * lutConcentration2,
                            color[2] * (1 - lutConcentration2) + applied[2] * lutConcentration2
                        )
                    }

                    if (lutGlobalStrength < 1.0f) {
                        color = floatArrayOf(
                            original[0] * (1 - lutGlobalStrength) + color[0] * lutGlobalStrength,
                            original[1] * (1 - lutGlobalStrength) + color[1] * lutGlobalStrength,
                            original[2] * (1 - lutGlobalStrength) + color[2] * lutGlobalStrength
                        )
                    }

                    if (filter.exposure != 0f) {
                        color = applyExposureLinear(color, filter.exposure / 10f)
                    }

                    if (filter.contrast != 0f) {
                        val factor = (100f + filter.contrast) / 100f
                        color = floatArrayOf(
                            ((color[0] - 0.5f) * factor + 0.5f),
                            ((color[1] - 0.5f) * factor + 0.5f),
                            ((color[2] - 0.5f) * factor + 0.5f)
                        )
                    }

                    if (filter.highlights != 0f || filter.shadows != 0f) {
                        val lum = color[0] * 0.299f + color[1] * 0.587f + color[2] * 0.114f
                        // Must match unified.comp exactly: smoothstep masks, not
                        // linear ramps, otherwise the exported .cube no longer
                        // reproduces the GPU preview.
                        val highlightMask = smoothStep(0.5f, 1f, lum)
                        val shadowMask = 1f - smoothStep(0f, 0.5f, lum)
                        val adj = (filter.highlights / 100f) * highlightMask * 0.3f +
                                  (filter.shadows / 100f) * shadowMask * 0.3f
                        color = floatArrayOf(color[0] + adj, color[1] + adj, color[2] + adj)
                    }

                    if (filter.saturation != 0f) {
                        val gray = color[0] * 0.299f + color[1] * 0.587f + color[2] * 0.114f
                        val factor = 1f + filter.saturation / 100f
                        color = floatArrayOf(
                            (gray + (color[0] - gray) * factor),
                            (gray + (color[1] - gray) * factor),
                            (gray + (color[2] - gray) * factor)
                        )
                    }

                    if (filter.colorTemperature != 0f) {
                        // Chromatic gain (see unified.comp step 7): an additive
                        // offset would tint pure black and lift the black point.
                        val adj = filter.colorTemperature / 200f
                        color = floatArrayOf(
                            color[0] * Math.max(1f + adj, 0f),
                            color[1],
                            color[2] * Math.max(1f - adj, 0f)
                        )
                    }

                    if (filter.tint != 0f) {
                        val adj = filter.tint / 200f
                        color = floatArrayOf(color[0], color[1] * Math.max(1f + adj, 0f), color[2])
                    }

                    // Curve: White (master) then per-channel
                    color[0] = color[0].coerceIn(0f, 1f)
                    color[1] = color[1].coerceIn(0f, 1f)
                    color[2] = color[2].coerceIn(0f, 1f)
                    if (curveConfig.whiteChannel.isNotEmpty()) {
                        val wi = (color[0] * 255f).toInt().coerceIn(0, 255)
                        color[0] = whiteCurve[wi]
                        val wj = (color[1] * 255f).toInt().coerceIn(0, 255)
                        color[1] = whiteCurve[wj]
                        val wk = (color[2] * 255f).toInt().coerceIn(0, 255)
                        color[2] = whiteCurve[wk]
                        color = floatArrayOf(color[0].coerceIn(0f, 1f), color[1].coerceIn(0f, 1f), color[2].coerceIn(0f, 1f))
                    }
                    if (curveConfig.redChannel.isNotEmpty()) {
                        val ri = (color[0] * 255f).toInt().coerceIn(0, 255)
                        color[0] = redCurve[ri]
                    }
                    if (curveConfig.greenChannel.isNotEmpty()) {
                        val gi = (color[1] * 255f).toInt().coerceIn(0, 255)
                        color[1] = greenCurve[gi]
                    }
                    if (curveConfig.blueChannel.isNotEmpty()) {
                        val bi = (color[2] * 255f).toInt().coerceIn(0, 255)
                        color[2] = blueCurve[bi]
                    }
                    color = floatArrayOf(color[0].coerceIn(0f, 1f), color[1].coerceIn(0f, 1f), color[2].coerceIn(0f, 1f))

                    // LCH
                    if (lchEnabled) {
                        val lin = ColorPipelineMath.srgbToLinear(color)
                        val oklab = linearToOklab(lin)
                        val L = oklab[0]
                        val C = Math.sqrt((oklab[1] * oklab[1] + oklab[2] * oklab[2]).toDouble()).toFloat()
                        var H = Math.atan2(oklab[2].toDouble(), oklab[1].toDouble()).toFloat()
                        if (H < 0f) H += 6.28318f

                        val centers = floatArrayOf(0.0f, 0.5f, 1.05f, 2.1f, 3.14f, 4.2f, 5.2f, 0.4f)
                        val zoneWidth = 0.7854f
                        val adj = arrayOf(
                            floatArrayOf(lchConfig.red.lightness, lchConfig.red.chroma, lchConfig.red.hue),
                            floatArrayOf(lchConfig.orange.lightness, lchConfig.orange.chroma, lchConfig.orange.hue),
                            floatArrayOf(lchConfig.yellow.lightness, lchConfig.yellow.chroma, lchConfig.yellow.hue),
                            floatArrayOf(lchConfig.green.lightness, lchConfig.green.chroma, lchConfig.green.hue),
                            floatArrayOf(lchConfig.cyan.lightness, lchConfig.cyan.chroma, lchConfig.cyan.hue),
                            floatArrayOf(lchConfig.blue.lightness, lchConfig.blue.chroma, lchConfig.blue.hue),
                            floatArrayOf(lchConfig.purple.lightness, lchConfig.purple.chroma, lchConfig.purple.hue),
                            floatArrayOf(lchConfig.skin.lightness, lchConfig.skin.chroma, lchConfig.skin.hue)
                        )
                        var totalL = 0f; var totalC = 0f; var totalH = 0f; var totalWeight = 0f
                        for (i in 0..7) {
                            var diff = Math.abs(H - centers[i])
                            if (diff > 3.14159f) diff = 6.28318f - diff
                            val sigma = zoneWidth * 0.6f
                            val weight = Math.exp((-diff * diff / (2f * sigma * sigma)).toDouble()).toFloat()
                            if (weight > 0.01f) {
                                totalL += adj[i][0] * weight
                                totalC += adj[i][1] * weight
                                totalH += adj[i][2] * weight
                                totalWeight += weight
                            }
                        }
                        if (totalWeight > 0f) {
                            val newL = (L + totalL / 100f * 0.5f).coerceIn(0f, 1f)
                            val newC = Math.max(C + totalC / 100f * 0.3f, 0f)
                            var newH = H + totalH / 100f * 0.5f
                            if (newH < 0f) newH += 6.28318f
                            if (newH >= 6.28318f) newH -= 6.28318f
                            val newOklab = floatArrayOf(newL, newC * Math.cos(newH.toDouble()).toFloat(), newC * Math.sin(newH.toDouble()).toFloat())
                            val newLin = oklabToLinear(newOklab)
                            color = ColorPipelineMath.linearToSrgb(floatArrayOf(newLin[0].coerceIn(0f, 1f), newLin[1].coerceIn(0f, 1f), newLin[2].coerceIn(0f, 1f)))
                        }
                    }

                    data[idx] = color[0].coerceIn(0f, 1f)
                    data[idx + 1] = color[1].coerceIn(0f, 1f)
                    data[idx + 2] = color[2].coerceIn(0f, 1f)
                }
            }
        }

        return LutData(size = size, data = data, title = filter.name)
    }

    private fun buildCurveLut(points: List<FloatPoint>): FloatArray {
        val lut = FloatArray(256)
        val middlePoints = points.filter { it.x > 0.001f && it.x < 0.999f }
        val startPt = points.firstOrNull()?.takeIf { it.x <= 0.001f }
            ?: FloatPoint(0f, 0f)
        val endPt = points.lastOrNull()?.takeIf { it.x >= 0.999f }
            ?: FloatPoint(1f, 1f)
        val sorted = (listOf(startPt) + middlePoints + listOf(endPt)).sortedBy { it.x }
        for (i in 0 until 256) {
            val x = i.toFloat() / 255f
            val y = CurveInterpolator.interpolate(sorted, x)
            lut[i] = y.coerceIn(0f, 1f)
        }
        return lut
    }

    /**
     * Photographic exposure applied in linear light, followed by a soft
     * highlight shoulder.
     *
     * Thin adapter: the formula itself lives in [ColorPipelineMath.applyExposure]
     * so that the CPU path (.cube export) and the GPU path (`unified.comp`'s
     * `applyExposure()`) cannot drift apart. Any change there MUST be mirrored
     * in the shader, otherwise the exported .cube stops matching the preview.
     *
     * @param color display-encoded sRGB colour, each component expected in [0,1].
     * @param ev    exposure in stops (the stored `FilterConfig.exposure / 10`).
     * @return display-encoded sRGB colour in [0,1].
     */
    private fun applyExposureLinear(color: FloatArray, ev: Float): FloatArray {
        return ColorPipelineMath.applyExposure(color, ev)
    }

    /** GLSL `smoothstep`, see [ColorPipelineMath.smoothStep]. */
    private fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
        return ColorPipelineMath.smoothStep(edge0, edge1, x)
    }

    // sRGB <-> linear light conversions live in [ColorPipelineMath] only; this
    // file used to carry a third, byte-identical copy of the transfer functions.

    private fun linearToOklab(c: FloatArray): FloatArray {
        val l = 0.4122214708f * c[0] + 0.5363325363f * c[1] + 0.0514459929f * c[2]
        val m = 0.2119034982f * c[0] + 0.6806995451f * c[1] + 0.1073969566f * c[2]
        val s = 0.0883024619f * c[0] + 0.2817188376f * c[1] + 0.6299787005f * c[2]
        val l_ = Math.pow(l.toDouble(), 1.0 / 3.0).toFloat()
        val m_ = Math.pow(m.toDouble(), 1.0 / 3.0).toFloat()
        val s_ = Math.pow(s.toDouble(), 1.0 / 3.0).toFloat()
        return floatArrayOf(
            0.2104542553f * l_ + 0.7936177850f * m_ - 0.0040720468f * s_,
            1.9779984951f * l_ - 2.4285922050f * m_ + 0.4505937099f * s_,
            0.0259040371f * l_ + 0.7827717662f * m_ - 0.8086757660f * s_
        )
    }

    private fun oklabToLinear(lab: FloatArray): FloatArray {
        val l_ = lab[0] + 0.3963377774f * lab[1] + 0.2158037573f * lab[2]
        val m_ = lab[0] - 0.1055613458f * lab[1] - 0.0638541728f * lab[2]
        val s_ = lab[0] - 0.0894841775f * lab[1] - 1.2914855480f * lab[2]
        val l = l_ * l_ * l_
        val m = m_ * m_ * m_
        val s = s_ * s_ * s_
        return floatArrayOf(
            4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s,
            -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s,
            -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s
        )
    }

    private fun loadLutData(path: String): LutData? {
        return try {
            val file = java.io.File(path)
            if (!file.exists()) return null
            when {
                path.endsWith(".cube", ignoreCase = true) -> lutParser.parseCube(file)
                path.endsWith(".vlt", ignoreCase = true) -> lutParser.parseVlt(file)
                else -> null
            }
        } catch (e: Exception) {
            logger.e(TAG, "加载LUT失败: $path", e)
            null
        }
    }

    private fun writeCubeToStream(
        output: java.io.OutputStream,
        lutData: LutData,
        title: String
    ) {
        output.bufferedWriter().use { writer ->
            writer.write("# Generated by Lutista")
            writer.newLine()
            writer.write("TITLE \"$title\"")
            writer.newLine()
            writer.write("LUT_3D_SIZE ${lutData.size}")
            writer.newLine()
            writer.write("DOMAIN_MIN 0.0 0.0 0.0")
            writer.newLine()
            writer.write("DOMAIN_MAX 1.0 1.0 1.0")
            writer.newLine()

            val size = lutData.size
            for (b in 0 until size) {
                for (g in 0 until size) {
                    for (r in 0 until size) {
                        val idx = (b * size * size + g * size + r) * 3
                        val red = "%.6f".format(lutData.data[idx].coerceIn(0f, 1f))
                        val green = "%.6f".format(lutData.data[idx + 1].coerceIn(0f, 1f))
                        val blue = "%.6f".format(lutData.data[idx + 2].coerceIn(0f, 1f))
                        writer.write("$red $green $blue")
                        writer.newLine()
                    }
                }
            }
        }
    }

    private fun createFilterConfigJson(filter: FilterEntity): JSONObject {
        return JSONObject().apply {
            put("name", filter.name)
            put("version", 1)
            put("lutConcentration1", filter.lutConcentration1.toDouble())
            put("lutConcentration2", filter.lutConcentration2.toDouble())
            put("lutGlobalStrength", filter.lutGlobalStrength.toDouble())
            put("lutPrecision", filter.lutPrecision)
            put("exposure", filter.exposure.toDouble())
            put("contrast", filter.contrast.toDouble())
            put("highlights", filter.highlights.toDouble())
            put("shadows", filter.shadows.toDouble())
            put("saturation", filter.saturation.toDouble())
            put("colorTemperature", filter.colorTemperature.toDouble())
            put("tint", filter.tint.toDouble())
            put("curveJson", filter.curveJson)
            put("lchJson", filter.lchJson)
            put("vignetteStrength", filter.vignetteStrength.toDouble())
            put("grainJson", filter.grainJson)
        }
    }
}
