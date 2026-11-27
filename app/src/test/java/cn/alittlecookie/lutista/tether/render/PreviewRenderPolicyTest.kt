package cn.alittlecookie.lutista.tether.render

import org.junit.Assert.*
import org.junit.Test

class PreviewRenderPolicyTest {
    private class Clock(var ms: Long = 0) : PreviewClock { override fun nowMs() = ms }

    @Test fun `default is off at fifteen and only supported rates are accepted`() {
        val policy = PreviewRenderPolicy(Clock())
        assertFalse(policy.enabled)
        assertEquals(15, policy.targetFps)
        assertFalse(policy.shouldProcess(true, 640, 432))
        for (rate in listOf(10, 15, 30)) { policy.setTargetFps(rate); assertEquals(rate, policy.targetFps) }
        try { policy.setTargetFps(20); fail("unsupported rate") } catch (_: IllegalArgumentException) { }
    }

    @Test fun `throttle uses elapsed monotonic time at every rate`() {
        for (rate in listOf(10, 15, 30)) {
            val clock = Clock()
            val policy = PreviewRenderPolicy(clock)
            policy.setTargetFps(rate)
            policy.setEnabled(true)
            var count = 0
            for (time in 0L until 1_000L) {
                clock.ms = time
                if (policy.shouldProcess(true, 640, 432)) { count++; policy.onProcessed() }
            }
            assertTrue("rate=$rate count=$count", count <= rate)
            assertTrue("rate=$rate count=$count", count >= rate - 1)
        }
    }

    @Test fun `rounded camera timestamps retain target cadence without false degradation`() {
        for (rate in listOf(10, 15, 30)) {
            val clock = Clock(2_000_000)
            val policy = PreviewRenderPolicy(clock)
            policy.setTargetFps(rate)
            policy.setEnabled(true)
            var count = 0
            for (frameIndex in 0 until rate * 5) {
                clock.ms = 2_000_000 + frameIndex * 1_000L / rate
                if (policy.shouldProcess(true, 640, 432)) { count++; policy.onProcessed() }
            }
            assertEquals("rate=$rate", rate * 5, count)
            assertNull(policy.degradeReason)
            // A pause does not accumulate permission for a burst at the same timestamp.
            clock.ms += 500
            assertTrue(policy.shouldProcess(true, 640, 432))
            assertFalse(policy.shouldProcess(true, 640, 432))
        }
    }

    @Test fun `three seconds below eighty percent degrades at eight twelve twenty four`() {
        for ((rate, minimum) in listOf(10 to 8, 15 to 12, 30 to 24)) {
            for (successesPerSecond in listOf(minimum - 1, minimum)) {
                val clock = Clock()
                val policy = PreviewRenderPolicy(clock)
                policy.setTargetFps(rate)
                policy.setEnabled(true)
                for (time in 0L..4_000L step 10L) {
                    clock.ms = time
                    policy.shouldProcess(true, 640, 432)
                    if (time > 0 && time * successesPerSecond / 1_000 !=
                        (time - 10) * successesPerSecond / 1_000
                    ) policy.onProcessed()
                    policy.evaluate()
                }
                assertEquals("target=$rate actual=$successesPerSecond",
                    if (successesPerSecond < minimum) DegradeReason.LowProcessedFps else null,
                    policy.degradeReason)
            }
        }
    }

    @Test fun `slow path is not disabled before three seconds and recovery is explicit`() {
        val clock = Clock()
        val policy = PreviewRenderPolicy(clock)
        policy.setEnabled(true)
        assertTrue(policy.shouldProcess(true, 640, 432))
        clock.ms = 2_999
        policy.evaluate()
        assertNull(policy.degradeReason)
        clock.ms = 3_000
        policy.evaluate()
        assertEquals(DegradeReason.LowProcessedFps, policy.degradeReason)
        policy.setTargetFps(10)
        assertFalse(policy.shouldProcess(true, 640, 432))
        assertFalse(policy.shouldProcess(false, 640, 432))
        clock.ms = 9_000
        assertFalse(policy.shouldProcess(true, 640, 432))
        policy.setEnabled(true)
        assertTrue(policy.shouldProcess(true, 640, 432))
        assertNull(policy.degradeReason)
    }

    @Test fun `no filter never starts low FPS timer and large JPEG falls back immediately`() {
        val clock = Clock()
        val policy = PreviewRenderPolicy(clock)
        policy.setEnabled(true)
        assertFalse(policy.shouldProcess(false, 640, 432))
        clock.ms = 60_000
        policy.evaluate()
        assertNull(policy.degradeReason)
        assertTrue(policy.shouldProcess(true, 640, 432))
        assertFalse(policy.shouldProcess(true, 1120, 752))
        assertEquals(DegradeReason.UnsupportedResolution, policy.degradeReason)
    }

    @Test fun `rate-capped filtered stream drops frames without emitting alternating originals`() {
        for (rate in listOf(10, 15, 30)) {
            val clock = Clock()
            val policy = PreviewRenderPolicy(clock)
            policy.setTargetFps(rate)
            policy.setEnabled(true)
            var filtered = 0
            var dropped = 0
            var originals = 0
            // A 60fps source must remain consistently filtered at every supported target rate.
            for (frame in 0 until 240) {
                clock.ms = frame * 1_000L / 60
                val action = policy.frameAction(true, 640, 432)
                when (policy.completeFrame(action, action == PreviewFrameAction.Filter)) {
                    PreviewFrameAction.Filter -> filtered++
                    PreviewFrameAction.Drop -> dropped++
                    PreviewFrameAction.Passthrough -> originals++
                }
            }
            assertEquals("target=$rate", rate * 4, filtered)
            assertEquals("target=$rate", 240 - filtered, dropped)
            assertEquals("target=$rate must never flash an original", 0, originals)
            assertNull(policy.degradeReason)
        }
    }

    @Test fun `off missing filter and degraded stay passthrough while only explicit enable recovers`() {
        val clock = Clock()
        val policy = PreviewRenderPolicy(clock)
        assertEquals(PreviewFrameAction.Passthrough, policy.frameAction(true, 640, 432))
        policy.setEnabled(true)
        assertEquals(PreviewFrameAction.Passthrough, policy.frameAction(false, 640, 432))
        assertEquals(PreviewFrameAction.Filter, policy.frameAction(true, 640, 432))
        assertEquals(PreviewFrameAction.Drop, policy.frameAction(true, 640, 432))
        clock.ms = 3_000
        assertEquals(PreviewFrameAction.Passthrough, policy.frameAction(true, 640, 432))
        assertEquals(DegradeReason.LowProcessedFps, policy.degradeReason)
        policy.setTargetFps(30)
        clock.ms = 9_000
        assertEquals(PreviewFrameAction.Passthrough, policy.frameAction(true, 640, 432))
        policy.setEnabled(true)
        assertEquals(PreviewFrameAction.Filter, policy.frameAction(true, 640, 432))
        policy.setEnabled(false)
        assertEquals(PreviewFrameAction.Passthrough, policy.frameAction(true, 640, 432))
        policy.setEnabled(true)
        assertEquals(PreviewFrameAction.Passthrough, policy.frameAction(true, 1120, 752))
        assertEquals(DegradeReason.UnsupportedResolution, policy.degradeReason)
    }

    @Test fun `failed filter output drops originals until three-second explicit degradation`() {
        for (rate in listOf(10, 15, 30)) {
            val clock = Clock()
            val policy = PreviewRenderPolicy(clock)
            policy.setTargetFps(rate)
            policy.setEnabled(true)
            for (time in 0L until 3_000L step 10L) {
                clock.ms = time
                val action = policy.frameAction(true, 640, 432)
                // Null/error output is not a processed frame and must never expose the original.
                assertEquals("target=$rate time=$time", PreviewFrameAction.Drop,
                    policy.completeFrame(action, filteredOutputAvailable = false))
                assertEquals(FilterState.On, policy.state(true))
                assertEquals(0f, policy.processedFps(), 0f)
            }
            clock.ms = 3_000
            val degradedAction = policy.frameAction(true, 640, 432)
            assertEquals(PreviewFrameAction.Passthrough, policy.completeFrame(degradedAction, false))
            assertEquals(FilterState.Degraded, policy.state(true))
            assertEquals(DegradeReason.LowProcessedFps, policy.degradeReason)
            clock.ms = 10_000
            assertEquals(PreviewFrameAction.Passthrough, policy.frameAction(true, 640, 432))
            policy.setEnabled(true)
            val retry = policy.frameAction(true, 640, 432)
            assertEquals(PreviewFrameAction.Filter, policy.completeFrame(retry, true))
        }
    }

    @Test fun `decode bounds are dynamic sampled and reject malformed or extreme dimensions`() {
        assertEquals(JpegDecodePlan(1, 640, 432), JpegDecodePlan.forBounds(640, 432))
        assertEquals(JpegDecodePlan(1, 1600, 1072), JpegDecodePlan.forBounds(1600, 1072))
        val large = checkNotNull(JpegDecodePlan.forBounds(6000, 4000))
        assertEquals(JpegDecodePlan(4, 1500, 1000), large)
        val portrait = checkNotNull(JpegDecodePlan.forBounds(4000, 6000))
        assertEquals(1000, portrait.width)
        assertEquals(1500, portrait.height)
        for ((width, height) in listOf(0 to 10, -1 to 10, Int.MAX_VALUE to 5, 20000 to 20000)) {
            assertNull(JpegDecodePlan.forBounds(width, height))
        }
        val odd = checkNotNull(JpegDecodePlan.forBounds(3201, 1801))
        assertTrue(odd.width <= JpegDecodePlan.MAX_DECODED_EDGE)
        assertTrue(odd.width.toLong() * odd.height <= JpegDecodePlan.MAX_DECODED_PIXELS)
    }
}
