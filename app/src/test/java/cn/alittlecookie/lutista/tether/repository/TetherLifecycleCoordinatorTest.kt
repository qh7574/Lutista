package cn.alittlecookie.lutista.tether.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Exercises the coordinators used by TetherRepository/Service, without Android or USB. */
class TetherLifecycleCoordinatorTest {
    @Test fun `event is registered before source is published and waits for transfer readiness`() = runBlocking {
        val barrier = SessionEventBarrier<Any, Any>()
        val window = barrier.begin()
        val driver = Any()
        val event = requireNotNull(barrier.register(driver, 7, 42))
        val consumer = async(start = CoroutineStart.UNDISPATCHED) { event.window.ready.await() }
        assertFalse(consumer.isCompleted)
        assertEquals(setOf(42L), window.observedHandles)
        val source = Any()
        assertTrue(barrier.publish(window, driver, source))
        assertSame(source, consumer.await())
        assertTrue(barrier.isCurrent(event))
    }

    @Test fun `baseline retains the identical append only observed handle set`() {
        val barrier = SessionEventBarrier<Any, Any>()
        val window = barrier.begin()
        val driver = Any()
        barrier.register(driver, 3, 100)
        barrier.publish(window, driver, Any())
        val baselineObserved = window.observedHandles
        barrier.register(driver, 3, 101)
        assertSame(baselineObserved, window.observedHandles)
        assertEquals(setOf(100L, 101L), baselineObserved)
        assertNull(barrier.register(Any(), 3, 999))
        assertFalse(999L in baselineObserved)
    }

    @Test fun `closing an unpublished window releases waiters without accepting old publication`() = runBlocking {
        val barrier = SessionEventBarrier<Any, Any>()
        val old = barrier.begin()
        val driver = Any()
        val event = requireNotNull(barrier.register(driver, 1, 9))
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { old.ready.await() }
        barrier.invalidate(old)
        try { waiting.await(); fail("Closed source wait must be cancelled") }
        catch (_: CancellationException) { }
        val next = barrier.begin()
        assertFalse(barrier.publish(old, driver, Any()))
        assertFalse(barrier.isCurrent(event))
        assertTrue(next.observedHandles.isEmpty())
        assertNotNull(barrier.register(driver, 2, 9))
        barrier.invalidate(old) // Late old cleanup cannot invalidate the next connection.
        assertTrue(barrier.isCurrent(next))
    }

    @Test fun `pump generation changes before ready invalidate old event ownership`() {
        val barrier = SessionEventBarrier<Any, Any>()
        val window = barrier.begin()
        val driver = Any()
        val old = requireNotNull(barrier.register(driver, 1, 10))
        val current = requireNotNull(barrier.register(driver, 2, 11))
        assertFalse(barrier.isCurrent(old))
        assertTrue(barrier.isCurrent(current))
        assertEquals(setOf(11L), window.observedHandles)
        assertTrue(barrier.publish(window, driver, Any()))
        assertNull(barrier.register(driver, 3, 12))
        assertEquals(setOf(11L), window.observedHandles)
    }

    @Test fun `service cannot be reused until expected destruction is acknowledged`() = runBlocking {
        val leases = ForegroundServiceLease()
        val old = leases.begin()
        assertTrue(leases.started(old.token))
        old.ready.await()
        assertSame(old, leases.requestStop())
        assertFalse(leases.accepts(old.token))
        assertFalse(old.stopped.isCompleted)
        try { leases.begin(); fail("Must await onDestroy before another enable") }
        catch (_: IllegalStateException) { }
        assertFalse(leases.destroyed(old.token))
        old.stopped.await()
        val next = leases.begin()
        assertTrue(next.token > old.token)
        assertTrue(leases.accepts(next.token))
    }

    @Test fun `old service callbacks cannot complete disable or fail a new attempt`() = runBlocking {
        val leases = ForegroundServiceLease()
        val old = leases.begin()
        leases.requestStop()
        leases.destroyed(old.token)
        val next = leases.begin()
        assertFalse(leases.started(old.token))
        assertFalse(leases.failed(old.token, IllegalStateException("old start")))
        assertFalse(leases.destroyed(old.token))
        assertFalse(next.ready.isCompleted)
        assertSame(next, leases.current())
        assertTrue(leases.started(next.token))
        next.ready.await()
    }

    @Test fun `unexpected current service death requests shutdown and releases readiness`() = runBlocking {
        val leases = ForegroundServiceLease()
        val lease = leases.begin()
        assertTrue(leases.destroyed(lease.token))
        assertTrue(lease.stopped.isCompleted)
        assertNull(leases.current())
        try { lease.ready.await(); fail("Destroyed service cannot authorize USB open") }
        catch (_: CancellationException) { }
        assertFalse(leases.destroyed(lease.token))
    }

    @Test fun `synchronous service start rejection retires only its own attempt`() = runBlocking {
        val leases = ForegroundServiceLease()
        val first = leases.begin()
        val rejected = IllegalStateException("foreground start rejected")
        leases.startRejected(first.token, rejected)
        assertTrue(first.stopped.isCompleted)
        try { first.ready.await(); fail("Rejected start cannot become ready") }
        catch (failure: IllegalStateException) {
            assertEquals(rejected.javaClass, failure.javaClass)
            assertEquals(rejected.message, failure.message)
        }
        val next = leases.begin()
        leases.startRejected(first.token, rejected)
        assertSame(next, leases.current())
    }

    @Test fun `service start rejection preserves security failure in recovered cause chain`() = runBlocking {
        val leases = ForegroundServiceLease()
        val first = leases.begin()
        val denied = SecurityException("foreground service permission denied")
        val rejected = IllegalStateException("foreground start rejected", denied)
        leases.startRejected(first.token, rejected)
        assertTrue(first.stopped.isCompleted)
        assertNull(leases.current())
        assertFalse(leases.destroyed(first.token))
        try { first.ready.await(); fail("Rejected start cannot become ready") }
        catch (failure: IllegalStateException) {
            assertEquals(rejected.javaClass, failure.javaClass)
            assertEquals(rejected.message, failure.message)
            assertTrue("Coroutine recovery must preserve the original permission denial",
                generateSequence<Throwable>(failure) { it.cause }.any { it === denied })
        }
    }

    @Test fun `cleanup attempts USB and service after independent stop and cache failures`() = runBlocking {
        val called = mutableListOf<String>()
        val failures = runTetherCleanup(
            TetherCleanupStep("capture") { called += "capture"; error("stop not confirmed") },
            TetherCleanupStep("transfer") { called += "transfer"; throw CancellationException("local cancellation") },
            TetherCleanupStep("usb") { called += "usb" },
            TetherCleanupStep("cache") { called += "cache"; error("database unavailable") },
            TetherCleanupStep("service") { called += "service" },
        )
        assertEquals(listOf("capture", "transfer", "usb", "cache", "service"), called)
        assertEquals(listOf("capture", "transfer", "cache"), failures.map { it.step })
    }

    @Test fun `cancelling an enable waiter cannot cancel its release stages`() = runBlocking {
        withTimeout(2_000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val called = mutableListOf<String>()
            val closing = launch {
                runTetherCleanup(
                    TetherCleanupStep("drain") { entered.complete(Unit); release.await(); called += "drain" },
                    TetherCleanupStep("usb") { called += "usb" },
                    TetherCleanupStep("service") { called += "service" },
                )
            }
            entered.await()
            closing.cancel()
            release.complete(Unit)
            closing.join()
            assertEquals(listOf("drain", "usb", "service"), called)
        }
    }
}
