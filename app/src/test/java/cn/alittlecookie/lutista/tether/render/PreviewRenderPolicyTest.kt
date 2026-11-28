package cn.alittlecookie.lutista.tether.render

import org.junit.Assert.*
import org.junit.Test

class PreviewRenderPolicyTest {
    private class Clock(var ms: Long = 0) : PreviewClock { override fun nowMs() = ms }

    @Test fun `default is off and every positive target rate is accepted`() {
        val policy = PreviewRenderPolicy(Clock())
        assertFalse(policy.enabled)
        assertEquals(15, policy.targetFps)
        assertFalse(policy.shouldProcess(true, 640, 432))
        for (rate in listOf(1, 10, 15, 20, 24, 30, 60, 120, 240)) {
            policy.setTargetFps(rate)
            assertEquals(rate, policy.targetFps)
        }
        for (rate in listOf(0, -1)) {
            try { policy.setTargetFps(rate); fail("nonpositive rate") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun `throttle uses elapsed monotonic time without restricting filter availability`() {
        for (rate in listOf(10, 15, 20, 30, 60, 120, 240)) {
            val clock = Clock()
            val policy = PreviewRenderPolicy(clock)
            policy.setTargetFps(rate)
            policy.setEnabled(true)
            var count = 0
            for (time in 0L until 1_000L) {
                clock.ms = time
                if (policy.shouldProcess(true, 6000, 4000)) { count++; policy.onProcessed() }
            }
            assertTrue("rate=$rate count=$count", count <= rate)
            assertTrue("rate=$rate count=$count", count >= rate - 1)
            assertEquals(FilterState.On, policy.state(true))
            assertNull(policy.degradeReason)
        }
    }

    @Test fun `rounded high FPS camera timestamps retain cadence without disabling high resolution filter`() {
        for (rate in listOf(10, 15, 30, 60, 120, 240)) {
            val clock = Clock(2_000_000)
            val policy = PreviewRenderPolicy(clock)
            policy.setTargetFps(rate)
            policy.setEnabled(true)
            var count = 0
            for (frameIndex in 0 until rate * 5) {
                clock.ms = 2_000_000 + frameIndex * 1_000L / rate
                if (policy.shouldProcess(true, 6000, 4000)) { count++; policy.onProcessed() }
            }
            assertEquals("rate=$rate", rate * 5, count)
            assertNull(policy.degradeReason)
            clock.ms += 500
            assertTrue(policy.shouldProcess(true, 6000, 4000))
            assertFalse(policy.shouldProcess(true, 6000, 4000))
        }
    }

    @Test fun `all source resolutions remain filtered across format changes`() {
        val clock = Clock()
        val policy = PreviewRenderPolicy(clock)
        policy.setEnabled(true)
        for ((width, height) in listOf(640 to 432, 1120 to 752, 1600 to 1072, 3840 to 2160, 6000 to 4000)) {
            clock.ms += 1_000
            val action = policy.frameAction(true, width, height)
            assertEquals("${width}x$height", PreviewFrameAction.Filter, action)
            assertEquals(PreviewFrameAction.Filter, policy.completeFrame(action, true))
            assertEquals(FilterState.On, policy.state(true))
            assertNull(policy.degradeReason)
        }
    }

    @Test fun `sustained near-zero processed FPS never disables or emits an original`() {
        for (rate in listOf(15, 60, 120, 240)) {
            val clock = Clock()
            val policy = PreviewRenderPolicy(clock)
            policy.setEnabled(true)
            policy.setTargetFps(rate)
            var successful = 0
            for (frame in 0 until 240 * 60) {
                clock.ms = frame * 1_000L / 240
                val action = policy.frameAction(true, 6000, 4000)
                // Only two completed GPU frames per second; the rest are rate-capped or GPU busy.
                val output = action == PreviewFrameAction.Filter && frame % 120 == 0
                val result = policy.completeFrame(action, output)
                assertNotEquals(PreviewFrameAction.Passthrough, result)
                if (result == PreviewFrameAction.Filter) successful++
                policy.evaluate()
                assertEquals("rate=$rate at ${clock.ms}ms", FilterState.On, policy.state(true))
                assertNull(policy.degradeReason)
            }
            assertTrue(successful > 0)
            assertTrue(policy.processedFps() < rate * 0.8f)
            clock.ms += 60_000
            policy.evaluate()
            assertEquals(FilterState.On, policy.state(true))
            assertEquals(PreviewFrameAction.Filter, policy.frameAction(true, 6000, 4000))
        }
    }

    @Test fun `busy GPU output remains a dropped filtered frame for a full minute`() {
        val clock = Clock()
        val policy = PreviewRenderPolicy(clock)
        policy.setEnabled(true)
        policy.setTargetFps(120)
        for (frame in 0 until 240 * 60) {
            clock.ms = frame * 1_000L / 240
            val action = policy.frameAction(true, 6000, 4000)
            assertEquals(PreviewFrameAction.Drop, policy.completeFrame(action, false))
            assertEquals(FilterState.On, policy.state(true))
            assertNull(policy.degradeReason)
        }
        assertEquals(0f, policy.processedFps(), 0f)
    }

    @Test fun `rate-capped filtered stream never flashes an original`() {
        for (rate in listOf(10, 15, 30, 60, 120)) {
            val clock = Clock()
            val policy = PreviewRenderPolicy(clock)
            policy.setTargetFps(rate)
            policy.setEnabled(true)
            var filtered = 0
            var dropped = 0
            for (frame in 0 until 240 * 4) {
                clock.ms = frame * 1_000L / 240
                val action = policy.frameAction(true, 3840, 2160)
                when (policy.completeFrame(action, action == PreviewFrameAction.Filter)) {
                    PreviewFrameAction.Filter -> filtered++
                    PreviewFrameAction.Drop -> dropped++
                    PreviewFrameAction.Passthrough -> fail("target=$rate must never flash an original")
                }
            }
            assertEquals("target=$rate", rate * 4, filtered)
            assertEquals("target=$rate", 240 * 4 - filtered, dropped)
            assertNull(policy.degradeReason)
        }
    }

    @Test fun `missing or explicitly disabled filter remains passthrough without degradation`() {
        val clock = Clock()
        val policy = PreviewRenderPolicy(clock)
        assertEquals(PreviewFrameAction.Passthrough, policy.frameAction(true, 6000, 4000))
        policy.setEnabled(true)
        assertEquals(PreviewFrameAction.Passthrough, policy.frameAction(false, 6000, 4000))
        clock.ms = 60_000
        policy.evaluate()
        assertNull(policy.degradeReason)
        assertEquals(PreviewFrameAction.Filter, policy.frameAction(true, 6000, 4000))
        policy.setEnabled(false)
        assertEquals(PreviewFrameAction.Passthrough, policy.frameAction(true, 6000, 4000))
        assertEquals(FilterState.Off, policy.state(true))
    }

    @Test fun `actual rendering exception stays distinct from performance drops until explicit retry`() {
        val clock = Clock()
        val policy = PreviewRenderPolicy(clock)
        policy.setEnabled(true)
        val action = policy.frameAction(true, 3840, 2160)
        assertEquals(PreviewFrameAction.Passthrough,
            policy.completeFrame(action, filteredOutputAvailable = false, renderingFailed = true))
        assertEquals(FilterState.Degraded, policy.state(true))
        assertEquals(DegradeReason.RenderingFailure, policy.degradeReason)
        policy.setTargetFps(120)
        policy.setEnabled(true) // Repeated settings emissions are not a new enable session.
        clock.ms = 60_000
        assertEquals(PreviewFrameAction.Passthrough, policy.frameAction(true, 3840, 2160))
        policy.setEnabled(false)
        policy.setEnabled(true)
        assertEquals(PreviewFrameAction.Filter, policy.frameAction(true, 3840, 2160))
        assertNull(policy.degradeReason)
    }

    @Test fun `drop notice gate permits one notice per enable session and rejects stale work`() {
        val gate = PreviewFilterDropGate()
        assertNull(gate.currentSession())
        assertFalse(gate.onDropped(null))
        gate.setEnabled(true)
        val first = checkNotNull(gate.currentSession())
        assertTrue(gate.onDropped(first))
        repeat(10_000) { assertFalse(gate.onDropped(first)) }
        gate.setEnabled(true)
        assertEquals(first, gate.currentSession())
        assertFalse(gate.onDropped(first))
        gate.setEnabled(false)
        assertFalse(gate.onDropped(first))
        assertFalse(gate.isCurrent(first))
        gate.setEnabled(true)
        val second = checkNotNull(gate.currentSession())
        assertNotEquals(first, second)
        assertFalse(gate.onDropped(first))
        assertTrue(gate.onDropped(second))
        assertFalse(gate.onDropped(second))
    }

    @Test fun `decode bounds are dynamic sampled and reject malformed or extreme dimensions`() {
        assertEquals(JpegDecodePlan(1, 640, 432), JpegDecodePlan.forBounds(640, 432))
        assertEquals(JpegDecodePlan(1, 1600, 1072), JpegDecodePlan.forBounds(1600, 1072))
        assertEquals(JpegDecodePlan(4, 1500, 1000), JpegDecodePlan.forBounds(6000, 4000))
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
