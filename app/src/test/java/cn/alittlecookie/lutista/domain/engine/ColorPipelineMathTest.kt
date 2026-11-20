package cn.alittlecookie.lutista.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * Golden tests that lock [ColorPipelineMath.applyExposure] to the same maths as
 * `applyExposure()` in `app/src/main/cpp/shaders/unified.comp`.
 *
 * The shader cannot share code with Kotlin, so these values are the contract
 * between the GPU preview and the CPU `.cube` export. Every expected value below
 * is either derived by hand in a comment or is a structural property (identity /
 * monotonicity / white point) that both sides must satisfy.
 */
class ColorPipelineMathTest {

    // ── 1. ev == 0 is a bit-exact identity ────────────────────────────────

    @Test
    fun `ev zero is bit exact identity for the full 8 bit ramp`() {
        for (i in 0..255) {
            val input = i / 255f
            val output = ColorPipelineMath.applyExposure(input, 0f)
            // delta = 0 -> exact float equality. The shader skips the whole
            // exposure block when `params.exposure == 0.0`, so the CPU side must
            // not even round-trip through linear light here.
            assertEquals("code value $i must be untouched at EV=0", input, output, 0f)
        }
    }

    @Test
    fun `ev zero is bit exact identity for out of range inputs`() {
        // Not reachable from the shader (input is clamped to [0,1] before step 3)
        // but the identity contract should not depend on the value.
        assertEquals(-0.25f, ColorPipelineMath.applyExposure(-0.25f, 0f), 0f)
        assertEquals(1.5f, ColorPipelineMath.applyExposure(1.5f, 0f), 0f)
    }

    // ── 2. Monotonicity ──────────────────────────────────────────────────

    @Test
    fun `output is monotonic non decreasing for fixed ev`() {
        val evs = floatArrayOf(-2f, -1f, -0.5f, 0.5f, 1f, 2f, 5f)
        for (ev in evs) {
            var previous = ColorPipelineMath.applyExposure(0f, ev)
            for (i in 1..255) {
                val current = ColorPipelineMath.applyExposure(i / 255f, ev)
                assertTrue(
                    "EV=$ev not monotonic at code value $i: $previous -> $current",
                    current >= previous
                )
                previous = current
            }
        }
    }

    // ── 3. White point & numerical sanity ────────────────────────────────

    @Test
    fun `white stays white and never exceeds one`() {
        val evs = floatArrayOf(0.5f, 1f, 2f, 5f)
        for (ev in evs) {
            val white = ColorPipelineMath.applyExposure(1f, ev)
            assertTrue("EV=$ev produced NaN/Inf", white.isFinite())
            assertTrue("EV=$ev white collapsed to $white (expected >= 0.99)", white >= 0.99f)
            assertTrue("EV=$ev white exceeded 1.0: $white", white <= 1f)
        }
    }

    @Test
    fun `no NaN or Inf anywhere in the ramp for any ev`() {
        val evs = floatArrayOf(-5f, -2f, -1f, -0.1f, 0.1f, 0.5f, 1f, 2f, 5f)
        for (ev in evs) {
            for (i in 0..255) {
                val out = ColorPipelineMath.applyExposure(i / 255f, ev)
                assertTrue("EV=$ev code $i -> $out is not finite", out.isFinite())
                assertTrue("EV=$ev code $i -> $out out of range", out in -0.0001f..1.0001f)
            }
        }
    }

    // ── 4. Golden value, hand derived ────────────────────────────────────

    /**
     * Negative EV must be a **pure linear gain** (`W = 1` makes the roll-off
     * curve collapse to identity, so darkening never compresses anything).
     *
     * Hand derivation for `applyExposure(1.0, EV=-0.1)`:
     * ```
     * gain   = 2 ^ -0.1                      = 0.9330330
     * w      = clamp(gain, 1, 4)             = 1        (no roll-off)
     * lin    = srgbToLinear(1.0) * gain      = 0.9330330
     * toned  = lin * (1 + lin/w^2) / (1+lin) = lin      (identity at w = 1)
     * out    = linearToSrgb(0.9330330)       = 0.9699662  (= 247.34 / 255)
     * ```
     * Before the fix (knee hard-wired to 0.9) this returned 0.9677333
     * (246.77 / 255): a darkened white was being compressed for no reason.
     */
    @Test
    fun `negative ev is a pure linear gain with no rolloff`() {
        val out = ColorPipelineMath.applyExposure(1f, -0.1f)
        assertEquals(0.9699662, out.toDouble(), 1e-5)
        // Same thing expressed in 8 bit code values.
        assertEquals(247.34, (out * 255f).toDouble(), 0.05)
    }

    /**
     * Crossing EV = 0 must not produce a visible step: the values on both sides
     * of zero have to be continuous with the identity used at EV == 0.
     *
     * Before the knee ramp, `EV = +0.001` returned 250.86/255 while `EV = 0`
     * returned 255.00 -> a 4.17/255 C0 step over the top 4% of the range.
     */
    @Test
    fun `ev zero is continuous with its neighbourhood`() {
        val whiteAtZero = ColorPipelineMath.applyExposure(1f, 0f)
        val tolerance = 1f / 255f
        for (eps in floatArrayOf(1e-3f, -1e-3f, 1e-4f, -1e-4f, 1e-2f, -1e-2f)) {
            val white = ColorPipelineMath.applyExposure(1f, eps)
            assertTrue(
                "white stepped by ${(white - whiteAtZero) * 255f}/255 at EV=$eps",
                kotlin.math.abs(white - whiteAtZero) < tolerance
            )
        }
    }

    @Test
    fun `weak positive ev keeps the white point pinned`() {
        // The roll-off white point is tied to the gain, so the source white must
        // land on exactly 1.0 for *every* positive EV, however small. This is
        // what stops a +0.1 EV nudge from turning whites into light grey.
        val evs = floatArrayOf(1e-3f, 0.05f, 0.1f, 0.25f, 0.5f, 1f, 2f)
        for (ev in evs) {
            val white = ColorPipelineMath.applyExposure(1f, ev)
            assertTrue("EV=$ev white collapsed to ${white * 255f}", white >= 254.9f / 255f)
        }
    }

    /**
     * Hand derivation for `applyExposure(128/255, EV=1)`:
     *
     * ```
     * v      = 128 / 255                     = 0.50196078
     * t      = (v + 0.055) / 1.055           = 0.52792486
     * lin    = t ^ 2.4                       = 0.21586050
     * gain   = 2 ^ 1                         = 2
     * w      = clamp(2, 1, 4)                = 2
     * L      = lin * gain                    = 0.43172100
     * L'     = L * (1 + L/w^2) / (1 + L)     = 0.4317210 * 1.1079303 / 1.4317210
     *        = 0.33415158
     * out    = 1.055 * L' ^ (1/2.4) - 0.055  = 0.61312793   (= 156.35 / 255)
     * ```
     *
     * Historic reference points for the same input:
     *   * original bug   (`c *= 2^EV` in sRGB) : 1.0        (255, pure white)
     *   * pure linear light gain               : 0.68845345 (176)
     *   * this film curve (what ships now)     : 0.61312793 (156)
     * Lightroom lands around 160 for the same input, which is why the film curve
     * is the one that "feels" like a real exposure slider.
     */
    @Test
    fun `golden mid grey at plus one stop`() {
        val expected = 0.61312793f
        val actual = ColorPipelineMath.applyExposure(128f / 255f, 1f)
        assertEquals(expected.toDouble(), actual.toDouble(), 1e-5)
    }

    /**
     * `EV=2`, `128/255`:
     * ```
     * gain = 4, w = 4, L = 0.21586050 * 4 = 0.86344200
     * L'   = 0.863442 * (1 + 0.863442/16) / 1.863442 = 0.48841919
     * out  = 1.055 * 0.48841919 ^ (1/2.4) - 0.055    = 0.72764040  (= 185.55)
     * ```
     * Pure linear would be 0.93739130 (239) - the compression is the point.
     */
    @Test
    fun `golden mid grey at plus two stops`() {
        val expected = 0.72764040f
        val actual = ColorPipelineMath.applyExposure(128f / 255f, 2f)
        assertEquals(expected.toDouble(), actual.toDouble(), 1e-5)
    }

    // ── 5. Film curve: identity only at EV <= 0, compression above ───────

    @Test
    fun `ev at or below zero is an exact linear light gain`() {
        // w = clamp(gain, 1, 4) == 1 for every ev <= 0, and the roll-off curve is
        // the identity at w = 1, so the response must equal a plain gain.
        for (ev in floatArrayOf(-5f, -2f, -1f, -0.1f)) {
            for (i in 1..255) {
                val v = i / 255f
                val expected = ColorPipelineMath.linearToSrgb(
                    ColorPipelineMath.srgbToLinear(v) * 2f.pow(ev)
                )
                assertEquals(
                    "EV=$ev code value $i must be a pure linear gain",
                    expected.toDouble(),
                    ColorPipelineMath.applyExposure(v, ev).toDouble(),
                    1e-6
                )
            }
        }
    }

    @Test
    fun `above white the film curve compresses instead of clipping`() {
        // EV=1, input 0.9: pure gain would clip to 1.0 (255, flat white).
        //   lin = 0.7874124 * 2 = 1.5748248, w = 2
        //   L'  = 1.5748248 * (1 + 1.5748248/4) / 2.5748248 = 0.8523756
        //   out = 0.93209516   (= 237.68)
        val out = ColorPipelineMath.applyExposure(0.9f, 1f)
        assertEquals(0.93209516, out.toDouble(), 1e-4)
        assertTrue("must not clip to white, got $out", out < 0.999f)
        assertTrue("must stay above the shadow end, got $out", out > 0.9f)
    }

    @Test
    fun `compression is monotonic in ev for a fixed highlight`() {
        // A brighter EV must never darken a pixel; that would make the slider feel
        // broken around the roll-off.
        var previous = ColorPipelineMath.applyExposure(0.9f, -2f)
        for (step in -19..50) {
            val ev = step / 10f
            val current = ColorPipelineMath.applyExposure(0.9f, ev)
            assertTrue("EV=$ev went backwards: $previous -> $current", current >= previous - 1e-6f)
            previous = current
        }
    }

    // ── 6. RGB overload is per-channel ───────────────────────────────────

    @Test
    fun `rgb overload matches the per channel function`() {
        val input = floatArrayOf(0.25f, 0.5f, 0.9f)
        val out = ColorPipelineMath.applyExposure(input, 1.5f)
        for (i in 0..2) {
            assertEquals(
                ColorPipelineMath.applyExposure(input[i], 1.5f).toDouble(),
                out[i].toDouble(),
                0.0
            )
        }
    }

    // ── 7. smoothStep parity with GLSL ───────────────────────────────────

    @Test
    fun `smoothstep matches glsl definition`() {
        assertEquals(0f, ColorPipelineMath.smoothStep(0f, 1f, -1f), 0f)
        assertEquals(0f, ColorPipelineMath.smoothStep(0f, 1f, 0f), 0f)
        assertEquals(0.5f, ColorPipelineMath.smoothStep(0f, 1f, 0.5f), 1e-7f)
        assertEquals(1f, ColorPipelineMath.smoothStep(0f, 1f, 1f), 0f)
        assertEquals(1f, ColorPipelineMath.smoothStep(0f, 1f, 2f), 0f)
        // Degenerate edges behave like a step function (GLSL is undefined here,
        // we choose the well defined branch used by the exporter).
        assertEquals(0f, ColorPipelineMath.smoothStep(0.5f, 0.5f, 0.4f), 0f)
        assertEquals(1f, ColorPipelineMath.smoothStep(0.5f, 0.5f, 0.6f), 0f)
    }
}
