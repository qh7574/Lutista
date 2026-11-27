package cn.alittlecookie.lutista.tether.session

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class TetherConnectionLeaseTest {
    @Test fun `same identity is reentrant and non owner release cannot steal connection`() {
        val lease = TetherConnectionLease()
        val formal = String(charArrayOf('a')); val diagnostic = String(charArrayOf('a'))
        assertEquals(formal, diagnostic)
        assertNotSame(formal, diagnostic)
        assertTrue(lease.tryAcquire(formal))
        assertTrue(lease.tryAcquire(formal))
        assertFalse(lease.tryAcquire(diagnostic))
        lease.release(diagnostic)
        assertFalse(lease.tryAcquire(diagnostic))
        lease.release(formal) // One release ends even a repeated acquisition.
        assertTrue(lease.tryAcquire(diagnostic))
        lease.release(formal)
        assertTrue(lease.tryAcquire(diagnostic))
        lease.release(diagnostic); lease.release(diagnostic)
        assertTrue(lease.tryAcquire(formal))
    }

    @Test fun `simultaneous distinct runtimes admit exactly one USB owner`() {
        val lease = TetherConnectionLease(); val contenders = 12
        val executor = Executors.newFixedThreadPool(contenders)
        val ready = CountDownLatch(contenders); val start = CountDownLatch(1); val attempted = CountDownLatch(contenders)
        val admitted = AtomicInteger(); val release = CountDownLatch(1)
        val owners = List(contenders) { Any() }
        try {
            val jobs = owners.map { owner -> executor.submit {
                ready.countDown(); check(start.await(3, TimeUnit.SECONDS))
                val acquired = lease.tryAcquire(owner)
                if (acquired) admitted.incrementAndGet()
                attempted.countDown()
                if (acquired) { check(release.await(3, TimeUnit.SECONDS)); lease.release(owner) }
            } }
            assertTrue(ready.await(3, TimeUnit.SECONDS)); start.countDown()
            assertTrue(attempted.await(3, TimeUnit.SECONDS))
            assertEquals(1, admitted.get())
            assertFalse(lease.tryAcquire(Any()))
            release.countDown(); jobs.forEach { it.get(3, TimeUnit.SECONDS) }
            assertTrue(lease.tryAcquire(Any()))
        } finally { start.countDown(); release.countDown(); executor.shutdownNow() }
    }

    @Test fun `all callers share the process singleton`() {
        val firstRuntime = TetherConnectionLease.global
        val secondRuntime = TetherConnectionLease.global
        val first = Any(); val second = Any()
        try {
            assertSame(firstRuntime, secondRuntime)
            assertTrue(firstRuntime.tryAcquire(first))
            assertFalse(secondRuntime.tryAcquire(second))
        } finally { firstRuntime.release(first); secondRuntime.release(second) }
    }
}
