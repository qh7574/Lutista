package cn.alittlecookie.lutista.tether.render

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class GpuFilterLeaseTest {
    @Test fun `static upload and dispatch are indivisible and preview yields immediately`() {
        val lease = GpuFilterLease()
        val uploaded = CountDownLatch(1)
        val dispatch = CountDownLatch(1)
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val executor = Executors.newSingleThreadExecutor()
        try {
            val static = executor.submit {
                lease.withLease {
                    calls.add("static LUT")
                    lease.invalidate()
                    calls.add("static curves")
                    uploaded.countDown()
                    check(dispatch.await(5, TimeUnit.SECONDS))
                    calls.add("static dispatch")
                }
            }
            assertTrue(uploaded.await(5, TimeUnit.SECONDS))
            assertNull(lease.tryWithLease { calls.add("preview upload"); "unexpected" })
            assertEquals(listOf("static LUT", "static curves"), calls)
            dispatch.countDown()
            static.get(5, TimeUnit.SECONDS)
            assertEquals("preview", lease.tryWithLease { calls.add("preview dispatch"); "preview" })
            assertEquals(listOf("static LUT", "static curves", "static dispatch", "preview dispatch"), calls)
        } finally { dispatch.countDown(); executor.shutdownNow() }
    }

    @Test fun `busy static lease drops preview original and successful retry stays filtered`() {
        val lease = GpuFilterLease()
        var now = 0L
        val policy = PreviewRenderPolicy(PreviewClock { now })
        policy.setEnabled(true)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val static = executor.submit {
                lease.withLease { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val action = policy.frameAction(true, 640, 432)
            assertEquals(PreviewFrameAction.Filter, action)
            val unavailableOutput = lease.tryWithLease { "filtered bitmap" }
            assertNull(unavailableOutput)
            assertEquals(PreviewFrameAction.Drop, policy.completeFrame(action, unavailableOutput != null))
            assertEquals(FilterState.On, policy.state(true))
            assertEquals(0f, policy.processedFps(), 0f)
            release.countDown()
            static.get(5, TimeUnit.SECONDS)
            now = 100
            val retry = policy.frameAction(true, 640, 432)
            val filteredOutput = lease.tryWithLease { "filtered bitmap" }
            assertNotNull(filteredOutput)
            assertEquals(PreviewFrameAction.Filter, policy.completeFrame(retry, filteredOutput != null))
            assertNull(policy.degradeReason)
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun `generation invalidates per-instance cache after another filter or native lifetime change`() {
        val lease = GpuFilterLease()
        var uploadedFilter: String? = null
        class EngineCache {
            var generation = Long.MIN_VALUE
            var filter: String? = null
            var uploads = 0
            fun process(selected: String) = lease.withLease {
                if (generation != lease.generation) filter = null
                if (filter != selected) {
                    uploadedFilter = selected
                    uploads++
                    lease.invalidate()
                    filter = selected
                }
                generation = lease.generation
                assertEquals(selected, uploadedFilter)
            }
        }
        val static = EngineCache()
        val preview = EngineCache()
        static.process("A")
        static.process("A")
        assertEquals(1, static.uploads)
        preview.process("B")
        static.process("A")
        assertEquals(2, static.uploads)
        preview.process("B")
        assertEquals(2, preview.uploads)
        lease.withLease { uploadedFilter = null; lease.invalidate() } // destroy + init generation
        preview.process("B")
        assertEquals(3, preview.uploads)
    }

    @Test fun `session is reentrant and exception releases lease`() {
        val lease = GpuFilterLease()
        assertEquals(1L, lease.withLease { lease.tryWithLease { lease.invalidate() } })
        try { lease.withLease { throw IllegalStateException("dispatch failed") } } catch (_: IllegalStateException) { }
        assertEquals(2L, lease.tryWithLease { lease.invalidate() })
    }
}
