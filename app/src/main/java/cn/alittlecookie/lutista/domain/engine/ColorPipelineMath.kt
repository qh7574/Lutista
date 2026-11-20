package cn.alittlecookie.lutista.domain.engine

import kotlin.math.pow

/**
 * Pure, Android-free colour math shared by the CPU side of the pipeline
 * (currently the `.cube` exporter).
 *
 * **Contract**: every function here mirrors the corresponding GLSL helper in
 * `app/src/main/cpp/shaders/unified.comp` formula-for-formula. The two
 * implementations cannot share code (Kotlin vs GLSL), so they are locked
 * together by `ColorPipelineMathTest`, which uses hand-computed golden values.
 * If you touch one side, you must touch the other and re-derive the goldens.
 */
object ColorPipelineMath {

    /**
     * Upper bound for the roll-off white point `W`.
     *
     * `W` is pinned to the exposure gain (`W = clamp(gain, 1, MAX_ROLLOFF_GAIN)`)
     * so that the source white lands exactly on 1.0 — white stays white instead
     * of turning grey.
     *
     * The cap only kicks in above +2 EV. For `EV <= 2`, `W == gain`, so the
     * response is fully determined by the white-point constraint above; raising
     * the cap cannot make `+1 EV` punchier without greying the whites.
     */
    const val MAX_ROLLOFF_GAIN: Float = 4f

    /** sRGB encoding threshold from IEC 61966-2-1 (linear segment end). */
    private const val SRGB_LINEAR_THRESHOLD: Float = 0.04045f

    /** Inverse threshold, in linear light. */
    private const val LINEAR_SRGB_THRESHOLD: Float = 0.0031308f

    /**
     * sRGB (display encoded) -> linear light, per channel, IEC 61966-2-1.
     *
     * @param v display-encoded value, nominally in `[0, 1]`.
     * @return linear-light value.
     */
    fun srgbToLinear(v: Float): Float {
        return if (v <= SRGB_LINEAR_THRESHOLD) {
            v / 12.92f
        } else {
            ((v + 0.055f) / 1.055f).pow(2.4f)
        }
    }

    /**
     * Linear light -> sRGB (display encoded), per channel.
     *
     * @param v linear-light value, nominally in `[0, 1]`.
     * @return display-encoded value.
     */
    fun linearToSrgb(v: Float): Float {
        return if (v <= LINEAR_SRGB_THRESHOLD) {
            v * 12.92f
        } else {
            1.055f * v.pow(1f / 2.4f) - 0.055f
        }
    }

    /**
     * RGB overload of [srgbToLinear]; mirrors the shader's `srgbToLinear(vec3)`.
     *
     * @param c display-encoded `[r, g, b]`.
     * @return new linear-light `[r, g, b]` array.
     */
    fun srgbToLinear(c: FloatArray): FloatArray {
        require(c.size >= 3) { "RGB triplet expected, got ${c.size} components" }
        return floatArrayOf(srgbToLinear(c[0]), srgbToLinear(c[1]), srgbToLinear(c[2]))
    }

    /**
     * RGB overload of [linearToSrgb]; mirrors the shader's `linearToSrgb(vec3)`.
     *
     * @param c linear-light `[r, g, b]`.
     * @return new display-encoded `[r, g, b]` array.
     */
    fun linearToSrgb(c: FloatArray): FloatArray {
        require(c.size >= 3) { "RGB triplet expected, got ${c.size} components" }
        return floatArrayOf(linearToSrgb(c[0]), linearToSrgb(c[1]), linearToSrgb(c[2]))
    }

    /**
     * Photographic exposure in linear light, followed by a film-style highlight
     * roll-off (extended Reinhard) whose white point is pinned to the gain.
     *
     * Equivalent to `applyExposure()` in `unified.comp`:
     * ```
     * gain = 2^ev
     * w    = clamp(gain, 1, MAX_ROLLOFF_GAIN)
     * lin  = srgbToLinear(clamp(src, 0, 1)) * gain
     * lin  = lin * (1 + lin / w^2) / (1 + lin)
     * out  = linearToSrgb(clamp(lin, 0, 1))
     * ```
     *
     * Why not a bare gain: the gain is correct (one stop), but on 8-bit SDR a
     * bare gain clips everything above sRGB 0.73 at +1 EV, so a small slider
     * move flattens the highlights into white. Lightroom-like editors run the
     * gained value through a film curve, which brightens the mid-tones while
     * keeping highlight separation.
     *
     * Properties:
     * * `ev <= 0` -> `w = 1` and the curve collapses to identity on `lin`, i.e.
     *   a pure linear darkening with no compression at all.
     * * `ev == 0` -> exact identity (also short-circuited in the shader).
     * * `out(w) == 1`, so the source white lands on exactly 1.0 and never turns
     *   grey.
     * * strictly increasing; finite for every input in `[0,1] x [-5,5]`.
     * * continuous at `ev = 0`: `w` approaches 1 and the curve approaches
     *   identity, so there is no step when the slider crosses zero.
     *
     * @param srgb display-encoded value, expected in `[0, 1]`.
     * @param ev   exposure in stops (stored `FilterConfig.exposure / 10`).
     * @return display-encoded value in `[0, 1]`.
     */
    fun applyExposure(srgb: Float, ev: Float): Float {
        if (ev == 0f) return srgb
        val gain = 2f.pow(ev)
        // GLSL: clamp(gain, 1.0, MAX_ROLLOFF_GAIN)
        val w = gain.coerceIn(1f, MAX_ROLLOFF_GAIN)
        val exposed = srgbToLinear(srgb.coerceIn(0f, 1f)) * gain
        val toned = exposed * (1f + exposed / (w * w)) / (1f + exposed)
        return linearToSrgb(toned.coerceIn(0f, 1f))
    }

    /**
     * Convenience overload for RGB triplets. Applies [applyExposure] channel by
     * channel, exactly like the shader's `applyExposure(vec3, float)`.
     *
     * @param srgb display-encoded `[r, g, b]`.
     * @param ev   exposure in stops.
     * @return new display-encoded `[r, g, b]` array.
     */
    fun applyExposure(srgb: FloatArray, ev: Float): FloatArray {
        require(srgb.size >= 3) { "RGB triplet expected, got ${srgb.size} components" }
        return floatArrayOf(
            applyExposure(srgb[0], ev),
            applyExposure(srgb[1], ev),
            applyExposure(srgb[2], ev)
        )
    }

    /**
     * GLSL `smoothstep`, clamped to `[0, 1]` (Hermite interpolation).
     *
     * @param edge0 lower edge; when equal to [edge1] a step is returned.
     * @param edge1 upper edge.
     * @param x     input value.
     * @return smoothed 0..1 ramp.
     */
    fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
        if (edge0 == edge1) return if (x < edge0) 0f else 1f
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
