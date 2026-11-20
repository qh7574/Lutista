package cn.alittlecookie.lutista.util

import cn.alittlecookie.lutista.data.database.entity.FilterEntity
import cn.alittlecookie.lutista.data.model.CurveConfig
import cn.alittlecookie.lutista.data.model.FilterConfig
import cn.alittlecookie.lutista.data.model.FloatPoint
import cn.alittlecookie.lutista.data.model.GrainConfig
import cn.alittlecookie.lutista.data.model.LchAdjustment
import cn.alittlecookie.lutista.data.model.LchConfig
import org.json.JSONArray
import org.json.JSONObject

object FilterConfigJson {

    // ── CurveConfig ──────────────────────────────────────────────

    fun curveConfigToJson(config: CurveConfig): String {
        if (config.whiteChannel.isEmpty() && config.redChannel.isEmpty() &&
            config.greenChannel.isEmpty() && config.blueChannel.isEmpty()
        ) return "{}"
        return JSONObject().apply {
            put("w", pointsToJson(config.whiteChannel))
            put("r", pointsToJson(config.redChannel))
            put("g", pointsToJson(config.greenChannel))
            put("b", pointsToJson(config.blueChannel))
        }.toString()
    }

    fun jsonToCurveConfig(json: String): CurveConfig {
        if (json.isBlank() || json == "{}") return CurveConfig()
        return try {
            val obj = JSONObject(json)
            CurveConfig(
                whiteChannel = jsonToPoints(obj.optJSONArray("w")),
                redChannel = jsonToPoints(obj.optJSONArray("r")),
                greenChannel = jsonToPoints(obj.optJSONArray("g")),
                blueChannel = jsonToPoints(obj.optJSONArray("b"))
            )
        } catch (_: Exception) { CurveConfig() }
    }

    private fun pointsToJson(points: List<FloatPoint>): JSONArray {
        return JSONArray().apply {
            points.forEach { p ->
                put(JSONObject().apply {
                    put("x", p.x.toDouble())
                    put("y", p.y.toDouble())
                })
            }
        }
    }

    private fun jsonToPoints(arr: JSONArray?): List<FloatPoint> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            try {
                val obj = arr.getJSONObject(i)
                FloatPoint(obj.getDouble("x").toFloat(), obj.getDouble("y").toFloat())
            } catch (_: Exception) { null }
        }
    }

    // ── LchConfig ────────────────────────────────────────────────

    fun lchConfigToJson(config: LchConfig): String {
        if (config == LchConfig()) return "{}"
        return JSONObject().apply {
            put("red", lchToJson(config.red))
            put("orange", lchToJson(config.orange))
            put("yellow", lchToJson(config.yellow))
            put("green", lchToJson(config.green))
            put("cyan", lchToJson(config.cyan))
            put("blue", lchToJson(config.blue))
            put("purple", lchToJson(config.purple))
            put("skin", lchToJson(config.skin))
        }.toString()
    }

    fun jsonToLchConfig(json: String): LchConfig {
        if (json.isBlank() || json == "{}") return LchConfig()
        return try {
            val obj = JSONObject(json)
            LchConfig(
                red = jsonToLch(obj.optJSONObject("red")),
                orange = jsonToLch(obj.optJSONObject("orange")),
                yellow = jsonToLch(obj.optJSONObject("yellow")),
                green = jsonToLch(obj.optJSONObject("green")),
                cyan = jsonToLch(obj.optJSONObject("cyan")),
                blue = jsonToLch(obj.optJSONObject("blue")),
                purple = jsonToLch(obj.optJSONObject("purple")),
                skin = jsonToLch(obj.optJSONObject("skin"))
            )
        } catch (_: Exception) { LchConfig() }
    }

    private fun lchToJson(adj: LchAdjustment): JSONObject {
        return JSONObject().apply {
            put("l", adj.lightness.toDouble())
            put("c", adj.chroma.toDouble())
            put("h", adj.hue.toDouble())
        }
    }

    private fun jsonToLch(obj: JSONObject?): LchAdjustment {
        if (obj == null) return LchAdjustment()
        return LchAdjustment(
            lightness = obj.optDouble("l", 0.0).toFloat(),
            chroma = obj.optDouble("c", 0.0).toFloat(),
            hue = obj.optDouble("h", 0.0).toFloat()
        )
    }

    // ── GrainConfig ──────────────────────────────────────────────

    fun grainConfigToJson(config: GrainConfig): String {
        if (config == GrainConfig()) return "{}"
        return JSONObject().apply {
            put("globalStrength", config.globalStrength.toDouble())
            put("grainSize", config.grainSize.toDouble())
            put("shadowThreshold", config.shadowThreshold.toDouble())
            put("highlightThreshold", config.highlightThreshold.toDouble())
            put("shadowGrainRatio", config.shadowGrainRatio.toDouble())
            put("midtoneGrainRatio", config.midtoneGrainRatio.toDouble())
            put("highlightGrainRatio", config.highlightGrainRatio.toDouble())
            put("shadowSizeRatio", config.shadowSizeRatio.toDouble())
            put("midtoneSizeRatio", config.midtoneSizeRatio.toDouble())
            put("highlightSizeRatio", config.highlightSizeRatio.toDouble())
            put("redChannelRatio", config.redChannelRatio.toDouble())
            put("blueChannelRatio", config.blueChannelRatio.toDouble())
            put("channelCorrelation", config.channelCorrelation.toDouble())
            put("colorPreservation", config.colorPreservation.toDouble())
        }.toString()
    }

    fun jsonToGrainConfig(json: String): GrainConfig {
        if (json.isBlank() || json == "{}") return GrainConfig()
        return try {
            val obj = JSONObject(json)
            GrainConfig(
                globalStrength = obj.optDouble("globalStrength", 0.0).toFloat(),
                grainSize = obj.optDouble("grainSize", 1.0).toFloat(),
                shadowThreshold = obj.optDouble("shadowThreshold", 85.0).toFloat(),
                highlightThreshold = obj.optDouble("highlightThreshold", 170.0).toFloat(),
                shadowGrainRatio = obj.optDouble("shadowGrainRatio", 0.6).toFloat(),
                midtoneGrainRatio = obj.optDouble("midtoneGrainRatio", 1.0).toFloat(),
                highlightGrainRatio = obj.optDouble("highlightGrainRatio", 0.3).toFloat(),
                shadowSizeRatio = obj.optDouble("shadowSizeRatio", 1.5).toFloat(),
                midtoneSizeRatio = obj.optDouble("midtoneSizeRatio", 1.0).toFloat(),
                highlightSizeRatio = obj.optDouble("highlightSizeRatio", 0.6).toFloat(),
                redChannelRatio = obj.optDouble("redChannelRatio", 0.9).toFloat(),
                blueChannelRatio = obj.optDouble("blueChannelRatio", 1.2).toFloat(),
                channelCorrelation = obj.optDouble("channelCorrelation", 0.9).toFloat(),
                colorPreservation = obj.optDouble("colorPreservation", 0.95).toFloat()
            )
        } catch (_: Exception) { GrainConfig() }
    }

    // ── FilterConfig ↔ FilterEntity ──────────────────────────────

    fun FilterConfig.toEntity(existing: FilterEntity? = null): FilterEntity {
        return FilterEntity(
            id = existing?.id ?: this.id,
            name = this.name.ifEmpty { existing?.name ?: "新滤镜" },
            lutPath1 = this.lutPath1,
            lutConcentration1 = this.lutConcentration1,
            lutPath2 = this.lutPath2,
            lutConcentration2 = this.lutConcentration2,
            lutGlobalStrength = this.lutGlobalStrength,
            lutPrecision = this.lutPrecision,
            exposure = this.exposure,
            contrast = this.contrast,
            highlights = this.highlights,
            shadows = this.shadows,
            saturation = this.saturation,
            colorTemperature = this.colorTemperature,
            tint = this.tint,
            curveJson = curveConfigToJson(this.curveConfig),
            lchJson = lchConfigToJson(this.lchConfig),
            vignetteStrength = this.vignetteStrength,
            grainJson = grainConfigToJson(this.grainConfig),
            unifiedLutPath = existing?.unifiedLutPath,
            unifiedLutHash = existing?.unifiedLutHash,
            isEnabled = existing?.isEnabled ?: true,
            isDefault = existing?.isDefault ?: false,
            isBuiltIn = existing?.isBuiltIn ?: false,
            filterDirPath = existing?.filterDirPath,
            sortOrder = existing?.sortOrder ?: 0,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
    }

    fun FilterEntity.toConfig(): FilterConfig {
        return FilterConfig(
            id = this.id,
            name = this.name,
            lutPath1 = this.lutPath1,
            lutConcentration1 = this.lutConcentration1,
            lutPath2 = this.lutPath2,
            lutConcentration2 = this.lutConcentration2,
            lutGlobalStrength = this.lutGlobalStrength,
            lutPrecision = this.lutPrecision,
            exposure = this.exposure,
            contrast = this.contrast,
            highlights = this.highlights,
            shadows = this.shadows,
            saturation = this.saturation,
            colorTemperature = this.colorTemperature,
            tint = this.tint,
            curveConfig = jsonToCurveConfig(this.curveJson),
            lchConfig = jsonToLchConfig(this.lchJson),
            vignetteStrength = this.vignetteStrength,
            grainConfig = jsonToGrainConfig(this.grainJson),
            unifiedLutPath = this.unifiedLutPath,
            unifiedLutHash = this.unifiedLutHash
        )
    }
}
