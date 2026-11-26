package cn.alittlecookie.lutista.tether.session

import cn.alittlecookie.lutista.tether.transport.PtpTimeoutException
import cn.alittlecookie.lutista.tether.transport.UsbIoThread
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Real queue/worker tests; no Android looper stub and no coroutine-test dependency. */
class PtpSchedulerTest {
    private val schedulers = mutableListOf<PtpScheduler>()
    private val gates = mutableListOf<CompletableDeferred<Unit>>()

    private class ExecutorWorker : PtpScheduler.IoWorker {
        override val dispatcher: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor {
            Thread(it, UsbIoThread.THREAD_NAME)
        }.asCoroutineDispatcher()
        override fun shutdown() { dispatcher.close() }
    }

    private fun scheduler() = PtpScheduler { ExecutorWorker() }.also { schedulers.add(it) }
    private fun gate() = CompletableDeferred<Unit>().also { gates.add(it) }

    // Coroutine stacktrace recovery may copy an awaited failure with the original as its direct cause.
    private fun assertAwaitedFailure(expected: Throwable, actual: Throwable?) {
        assertTrue(
            "Expected the original failure or its coroutine recovery copy, got "+actual,
            actual === expected || actual?.cause === expected,
        )
    }

    @Before fun before() { UsbIoThread.resetForTest() }

    @After fun after() = runBlocking {
        gates.forEach { it.complete(Unit) }
        schedulers.asReversed().forEach { runCatching { it.close() } }
        UsbIoThread.resetForTest()
    }

    @Test fun `ABORT overtakes USER and equal priorities remain FIFO`() = runBlocking {
        withTimeout(5_000) {
            val scheduler = scheduler()
            val entered = gate()
            val release = gate()
            val order = Collections.synchronizedList(mutableListOf<String>())
            val running = async(start = CoroutineStart.UNDISPATCHED) {
                scheduler.run(PtpScheduler.Priority.PROBE) {
                    entered.complete(Unit)
                    release.await()
                    order.add("running")
                }
            }
            entered.await()
            val probe = async(start = CoroutineStart.UNDISPATCHED) {
                scheduler.run(PtpScheduler.Priority.PROBE) { order.add("probe") }
            }
            val user1 = async(start = CoroutineStart.UNDISPATCHED) {
                scheduler.run { order.add("user1") }
            }
            val abort = async(start = CoroutineStart.UNDISPATCHED) {
                scheduler.run(PtpScheduler.Priority.ABORT) { order.add("abort") }
            }
            val user2 = async(start = CoroutineStart.UNDISPATCHED) {
                scheduler.run { order.add("user2") }
            }
            assertEquals(5, scheduler.pendingUserOps())
            assertNull(scheduler.tryRunFrame { error("frame must yield") })
            release.complete(Unit)
            listOf(running, probe, user1, abort, user2).forEach { it.await() }
            assertEquals(listOf("running", "abort", "user1", "user2", "probe"), order)
        }
    }

    @Test fun `suspension never permits another request and binding precedes first block`() = runBlocking {
        withTimeout(5_000) {
            val scheduler = scheduler()
            val active = AtomicInteger()
            val peak = AtomicInteger()
            val threads = Collections.synchronizedSet(mutableSetOf<Thread>())
            val work = (1..24).map {
                async(start = CoroutineStart.UNDISPATCHED) {
                    scheduler.run {
                        assertTrue(UsbIoThread.isIoThread())
                        assertEquals(UsbIoThread.THREAD_NAME, Thread.currentThread().name.substringBefore(" @coroutine#"))
                        threads.add(Thread.currentThread())
                        peak.updateAndGet { old -> maxOf(old, active.incrementAndGet()) }
                        delay(1)
                        assertTrue(UsbIoThread.isIoThread())
                        threads.add(Thread.currentThread())
                        active.decrementAndGet()
                    }
                }
            }
            work.forEach { it.await() }
            assertEquals(1, peak.get())
            assertEquals(1, threads.size)
            assertEquals(0, UsbIoThread.violationCount())
        }
    }

    @Test fun `cancelling a queued waiter removes its command`() = runBlocking {
        withTimeout(5_000) {
            val scheduler = scheduler()
            val entered = gate()
            val release = gate()
            val running = async(start = CoroutineStart.UNDISPATCHED) {
                scheduler.run { entered.complete(Unit); release.await() }
            }
            entered.await()
            var called = false
            val queued = async(start = CoroutineStart.UNDISPATCHED) {
                scheduler.run { called = true }
            }
            assertEquals(2, scheduler.pendingUserOps())
            queued.cancelAndJoin()
            assertEquals(1, scheduler.pendingUserOps())
            release.complete(Unit)
            running.await()
            scheduler.run {}
            assertFalse(called)
        }
    }

    @Test fun `cancelling active caller does not cancel issued USB work`() = runBlocking {
        withTimeout(5_000) {
            val scheduler = scheduler()
            val entered = gate()
            val release = gate()
            val completed = gate()
            val running = async(start = CoroutineStart.UNDISPATCHED) {
                scheduler.run {
                    entered.complete(Unit)
                    release.await()
                    completed.complete(Unit)
                }
            }
            entered.await()
            running.cancelAndJoin()
            assertFalse(completed.isCompleted)
            val next = async(start = CoroutineStart.UNDISPATCHED) { scheduler.run { 42 } }
            assertFalse(next.isCompleted)
            release.complete(Unit)
            completed.await()
            assertEquals(42, next.await())
        }
    }

    @Test fun `fatal fails every waiter and active start cannot clear the latch`() = runBlocking {
        withTimeout(5_000) {
            val scheduler = scheduler()
            val entered = gate()
            val release = gate()
            val fatal = IllegalStateException("actual transport fatal")
            val running = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { scheduler.run { entered.complete(Unit); release.await() } }
            }
            entered.await()
            val queued = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { scheduler.run { error("queued command executed") } }
            }
            scheduler.abortAll(fatal)
            scheduler.abortAll(IllegalArgumentException("must preserve first cause"))
            assertAwaitedFailure(fatal, running.await().exceptionOrNull())
            assertAwaitedFailure(fatal, queued.await().exceptionOrNull())
            scheduler.start()
            assertAwaitedFailure(fatal, runCatching { scheduler.run {} }.exceptionOrNull())
            assertNull(scheduler.tryRunFrame { 1 })
            assertEquals(0, scheduler.pendingUserOps())
            release.complete(Unit)
            Unit
        }
    }

    @Test fun `retained diagnostic result survives close entering before worker submits its value`() = runBlocking {
        withTimeout(5_000) {
            val scheduler = scheduler()
            val entered = gate()
            val allowFatal = gate()
            val fatalLatched = gate()
            val release = gate()
            val ioThread = CompletableDeferred<Thread>()
            val original = PtpTimeoutException("diagnostic fatal reported as a value")
            val result = Any()
            val queuedRuns = AtomicInteger()
            val drains = AtomicInteger()
            val closes = AtomicInteger()
            scheduler.setEventDrain { drains.incrementAndGet(); Unit }
            val active = async(start = CoroutineStart.UNDISPATCHED) {
                scheduler.run {
                    ioThread.complete(Thread.currentThread())
                    entered.complete(Unit)
                    allowFatal.await()
                    scheduler.abortAll(original, retainActiveResult = true)
                    fatalLatched.complete(Unit)
                    release.await()
                    result
                }
            }
            entered.await()
            val queued = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { scheduler.run { queuedRuns.incrementAndGet(); Unit } }
            }
            try {
                allowFatal.complete(Unit)
                fatalLatched.await()
                assertAwaitedFailure(original, queued.await().exceptionOrNull())
                // UNDISPATCHED enters close's lock and stopped.await before it
                // returns here, while the worker is still behind the return gate.
                val closing = async(start = CoroutineStart.UNDISPATCHED) {
                    scheduler.close {
                        assertSame(ioThread.await(), Thread.currentThread())
                        assertTrue(UsbIoThread.isIoThread())
                        closes.incrementAndGet()
                    }
                }
                assertFalse(closing.isCompleted)
                assertFalse(active.isCompleted)
                assertFalse(release.isCompleted)
                assertEquals(0, closes.get())
                // An ordinary later abort must not downgrade the retained flag.
                scheduler.abortAll(IllegalStateException("secondary abort"))
                assertFalse(active.isCompleted)
                assertSame(original, runCatching { scheduler.run {} }.exceptionOrNull())
                assertNull(scheduler.tryRunFrame { 1 })
                release.complete(Unit)
                assertSame(result, active.await())
                closing.await()
                assertEquals(0, queuedRuns.get())
                assertEquals(0, drains.get())
                assertEquals(1, closes.get())
                assertFalse(UsbIoThread.isBound())
                assertSame(original, runCatching { scheduler.run {} }.exceptionOrNull())
                assertEquals(0, UsbIoThread.violationCount())
            } finally { allowFatal.complete(Unit); release.complete(Unit) }
        }
    }

    @Test fun `retained active request submits its actual thrown exception after close already waits`() = runBlocking {
        withTimeout(5_000) {
            val scheduler = scheduler()
            val entered = gate()
            val release = gate()
            val original = PtpTimeoutException("latched diagnostic fatal")
            val actualFailure = IllegalArgumentException("block failed after retaining its result")
            val closes = AtomicInteger()
            val active = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching {
                    scheduler.run<Unit> {
                        scheduler.abortAll(original, retainActiveResult = true)
                        entered.complete(Unit)
                        release.await()
                        throw actualFailure
                    }
                }
            }
            entered.await()
            try {
                val closing = async(start = CoroutineStart.UNDISPATCHED) {
                    scheduler.close { assertTrue(UsbIoThread.isIoThread()); closes.incrementAndGet() }
                }
                assertFalse(closing.isCompleted)
                assertFalse(active.isCompleted)
                assertFalse(release.isCompleted)
                release.complete(Unit)
                assertAwaitedFailure(actualFailure, active.await().exceptionOrNull())
                closing.await()
                assertSame(original, runCatching { scheduler.run {} }.exceptionOrNull())
                assertEquals(1, closes.get())
                assertFalse(UsbIoThread.isBound())
            } finally { release.complete(Unit) }
        }
    }

    @Test fun `default abort still fails active immediately with the first cause while close waits for USB`() = runBlocking {
        withTimeout(5_000) {
            val scheduler = scheduler()
            val entered = gate()
            val allowFatal = gate()
            val release = gate()
            val completed = gate()
            val original = PtpTimeoutException("default first fatal")
            val secondary = IllegalStateException("later fatal must not replace the first")
            val closes = AtomicInteger()
            val active = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching {
                    scheduler.run {
                        entered.complete(Unit)
                        allowFatal.await()
                        scheduler.abortAll(original)
                        release.await()
                        completed.complete(Unit)
                        Any()
                    }
                }
            }
            entered.await()
            val queued = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { scheduler.run<Unit> { error("queued work must not execute") } }
            }
            try {
                allowFatal.complete(Unit)
                assertAwaitedFailure(original, active.await().exceptionOrNull())
                assertAwaitedFailure(original, queued.await().exceptionOrNull())
                scheduler.abortAll(secondary)
                val closing = async(start = CoroutineStart.UNDISPATCHED) {
                    scheduler.close { assertTrue(UsbIoThread.isIoThread()); closes.incrementAndGet() }
                }
                assertFalse(closing.isCompleted)
                assertFalse(release.isCompleted)
                assertFalse(completed.isCompleted)
                assertEquals(0, closes.get())
                assertSame(original, runCatching { scheduler.run {} }.exceptionOrNull())
                release.complete(Unit)
                closing.await()
                completed.await()
                assertAwaitedFailure(original, active.await().exceptionOrNull())
                assertEquals(1, closes.get())
                assertFalse(UsbIoThread.isBound())
            } finally { allowFatal.complete(Unit); release.complete(Unit) }
        }
    }

    @Test fun `close callback bypasses fatal and executes once on original thread`() = runBlocking {
        withTimeout(5_000) {
            val scheduler = scheduler()
            val ioThread = scheduler.run { Thread.currentThread() }
            val fatal = IllegalStateException("fatal")
            scheduler.abortAll(fatal)
            var closes = 0
            scheduler.close {
                assertSame(ioThread, Thread.currentThread())
                assertTrue(UsbIoThread.isIoThread())
                closes++
            }
            scheduler.close { closes++ }
            assertEquals(1, closes)
            assertFalse(UsbIoThread.isBound())
            assertSame(fatal, runCatching { scheduler.run {} }.exceptionOrNull())
            scheduler.start()
            val newThread = scheduler.run { Thread.currentThread() }
            assertTrue(ioThread !== newThread)
            assertEquals(0, UsbIoThread.violationCount())
        }
    }

    @Test fun `close waits for active block but rejects all queued work`() = runBlocking {
        withTimeout(5_000) {
            val scheduler = scheduler()
            val entered = gate()
            val release = gate()
            val order = Collections.synchronizedList(mutableListOf<String>())
            val running = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { scheduler.run { entered.complete(Unit); release.await(); order.add("usb done") } }
            }
            entered.await()
            val queued = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { scheduler.run { order.add("must not run") } }
            }
            val closing = async(start = CoroutineStart.UNDISPATCHED) {
                scheduler.close { order.add("release") }
            }
            assertFalse(closing.isCompleted)
            assertTrue(queued.await().isFailure)
            assertTrue(running.await().isFailure)
            release.complete(Unit)
            closing.await()
            assertEquals(listOf("usb done", "release"), order)
        }
    }

    @Test fun `a failed second scheduler cannot overwrite or unbind the active owner`() = runBlocking {
        withTimeout(5_000) {
            val first = scheduler()
            val second = scheduler()
            first.start()
            val thread = first.run { Thread.currentThread() }
            assertTrue(runCatching { second.start() }.exceptionOrNull() is IllegalStateException)
            runCatching { second.close() }
            first.run {
                assertSame(thread, Thread.currentThread())
                assertTrue(UsbIoThread.isIoThread())
            }
            assertEquals(0, UsbIoThread.violationCount())
        }
    }

    @Test fun `frame is serialized and only one outstanding frame is admitted`() = runBlocking {
        withTimeout(5_000) {
            val scheduler = scheduler()
            scheduler.start()
            val entered = gate()
            val release = gate()
            val frame: Deferred<Int>? = scheduler.tryRunFrame {
                assertTrue(UsbIoThread.isIoThread())
                entered.complete(Unit)
                release.await()
                7
            }
            assertNotNull(frame)
            entered.await()
            assertNull(scheduler.tryRunFrame { 8 })
            val command = async(start = CoroutineStart.UNDISPATCHED) { scheduler.run { 9 } }
            assertFalse(command.isCompleted)
            release.complete(Unit)
            assertEquals(7, frame!!.await())
            assertEquals(9, command.await())
        }
    }

    @Test fun `event drain is inline only after last non-frame request`() = runBlocking {
        withTimeout(5_000) {
            val scheduler = scheduler()
            val entered = gate()
            val release = gate()
            val drained = gate()
            val order = Collections.synchronizedList(mutableListOf<String>())
            scheduler.setEventDrain {
                assertTrue(UsbIoThread.isIoThread())
                order.add("drain")
                drained.complete(Unit)
            }
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                scheduler.run { entered.complete(Unit); release.await(); order.add("first") }
            }
            entered.await()
            val last = async(start = CoroutineStart.UNDISPATCHED) { scheduler.run { order.add("last") } }
            release.complete(Unit)
            first.await(); last.await(); drained.await()
            assertEquals(listOf("first", "last", "drain"), order)
        }
    }

    @Test fun `close cancels suspended event delivery without waiting for its collector`() = runBlocking {
        withTimeout(5_000) {
            val scheduler = scheduler()
            val draining = gate()
            val slowCollector = gate()
            var released = false
            scheduler.setEventDrain { draining.complete(Unit); slowCollector.await() }
            scheduler.run {}
            draining.await()
            scheduler.close {
                assertTrue(UsbIoThread.isIoThread())
                released = true
            }
            assertTrue(released)
            assertFalse(slowCollector.isCompleted)
            assertFalse(UsbIoThread.isBound())
        }
    }

    @Test fun `nested run fails fast instead of deadlocking the worker`() = runBlocking {
        withTimeout(5_000) {
            val scheduler = scheduler()
            val error = scheduler.run { runCatching { scheduler.run { 1 } }.exceptionOrNull() }
            assertTrue(error is IllegalStateException)
            assertEquals(0, UsbIoThread.violationCount())
        }
    }

    @Test fun `queue budget expires pending work before the active request finishes`() = runBlocking {
        withTimeout(5_000) {
            val scheduler = scheduler()
            val entered = gate()
            val release = gate()
            val active = async(start = CoroutineStart.UNDISPATCHED) {
                scheduler.run { entered.complete(Unit); release.await() }
            }
            entered.await()
            var called = false
            val queued = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { scheduler.runWithQueueBudget(PtpScheduler.Priority.PROBE, 75) { called = true } }
            }
            try {
                val error = withTimeout(1_500) { queued.await() }.exceptionOrNull()
                assertTrue(error is PtpScheduler.QueueBudgetExceededException)
                assertEquals(75L, (error as PtpScheduler.QueueBudgetExceededException).queueBudgetMs)
                assertFalse(active.isCompleted)
                assertFalse(release.isCompleted)
                assertFalse(called)
                assertEquals(1, scheduler.pendingUserOps())
            } finally { release.complete(Unit) }
            active.await()
            scheduler.run {}
            assertFalse(called)
        }
    }

    @Test fun `budget cannot time out an admitted USB request or change its original value`() = runBlocking {
        withTimeout(5_000) {
            val scheduler = scheduler()
            scheduler.start()
            val entered = gate()
            val release = gate()
            val value = Any()
            val active = async(start = CoroutineStart.UNDISPATCHED) {
                scheduler.runWithQueueBudget(PtpScheduler.Priority.USER, 100) {
                    assertTrue(UsbIoThread.isIoThread())
                    entered.complete(Unit)
                    release.await()
                    value
                }
            }
            entered.await()
            try {
                delay(160)
                assertFalse(active.isCompleted)
                assertFalse(release.isCompleted)
                assertEquals(1, scheduler.pendingUserOps())
            } finally { release.complete(Unit) }
            assertSame(value, active.await())
            assertEquals(7, scheduler.run { 7 })
        }
    }

    @Test fun `admitted USB failure retains its identity after the queue budget passes`() = runBlocking {
        withTimeout(5_000) {
            val scheduler = scheduler()
            scheduler.start()
            val entered = gate()
            val release = gate()
            val original = PtpTimeoutException("actual late USB timeout")
            val active = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching {
                    scheduler.runWithQueueBudget<Unit>(PtpScheduler.Priority.USER, 100) {
                        entered.complete(Unit)
                        release.await()
                        throw original
                    }
                }
            }
            entered.await()
            try { delay(160); assertFalse(active.isCompleted) }
            finally { release.complete(Unit) }
            assertAwaitedFailure(original, active.await().exceptionOrNull())
        }
    }

    @Test fun `cancelling a budgeted waiter removes it and cancels its timer without affecting USB`() = runBlocking {
        withTimeout(5_000) {
            val scheduler = scheduler()
            val entered = gate()
            val release = gate()
            val active = async(start = CoroutineStart.UNDISPATCHED) {
                scheduler.run { entered.complete(Unit); release.await() }
            }
            entered.await()
            var called = false
            val queued = async(start = CoroutineStart.UNDISPATCHED) {
                scheduler.runWithQueueBudget(PtpScheduler.Priority.PROBE, 60) { called = true }
            }
            try {
                assertEquals(2, scheduler.pendingUserOps())
                queued.cancelAndJoin()
                delay(120)
                assertTrue(queued.isCancelled)
                assertFalse(active.isCompleted)
                assertEquals(1, scheduler.pendingUserOps())
                assertFalse(called)
            } finally { release.complete(Unit) }
            active.await()
            assertEquals(9, scheduler.runWithQueueBudget(PtpScheduler.Priority.USER, 500) { 9 })
            assertFalse(called)
        }
    }

    @Test fun `fatal wins over the timer and preserves its original cause for budgeted waiters`() = runBlocking {
        withTimeout(5_000) {
            val scheduler = scheduler()
            val entered = gate()
            val release = gate()
            val active = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { scheduler.run { entered.complete(Unit); release.await() } }
            }
            entered.await()
            val original = PtpTimeoutException("fatal while probe queued")
            val queued = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { scheduler.runWithQueueBudget<Unit>(PtpScheduler.Priority.PROBE, 100) { error("must not execute") } }
            }
            try {
                scheduler.abortAll(original)
                assertAwaitedFailure(original, queued.await().exceptionOrNull())
                assertAwaitedFailure(original, active.await().exceptionOrNull())
                delay(160)
                assertAwaitedFailure(original, queued.await().exceptionOrNull())
                assertFalse(release.isCompleted)
                assertEquals(0, scheduler.pendingUserOps())
            } finally { release.complete(Unit) }
        }
    }

    @Test fun `zero queue budget removes a pending request without issuing its block`() = runBlocking {
        withTimeout(5_000) {
            val scheduler = scheduler()
            val entered = gate()
            val release = gate()
            val active = async(start = CoroutineStart.UNDISPATCHED) {
                scheduler.run { entered.complete(Unit); release.await() }
            }
            entered.await()
            try {
                val error = runCatching {
                    scheduler.runWithQueueBudget<Unit>(PtpScheduler.Priority.PROBE, 0) { error("must remain unissued") }
                }.exceptionOrNull()
                assertTrue(error is PtpScheduler.QueueBudgetExceededException)
                assertFalse(active.isCompleted)
                assertEquals(1, scheduler.pendingUserOps())
            } finally { release.complete(Unit) }
            active.await()
        }
    }

    @Test fun `nested queue budget call fails fast on the same worker`() = runBlocking {
        withTimeout(5_000) {
            val scheduler = scheduler()
            val error = scheduler.run {
                runCatching { scheduler.runWithQueueBudget(PtpScheduler.Priority.USER, 500) { 1 } }.exceptionOrNull()
            }
            assertTrue(error is IllegalStateException)
            assertTrue(error!!.message!!.contains("runWithQueueBudget cannot be nested"))
            assertEquals(0, UsbIoThread.violationCount())
        }
    }

    @Test fun `cleanup exception still releases binding and permits explicit reconnect`() = runBlocking {
        withTimeout(5_000) {
            val scheduler = scheduler()
            scheduler.start()
            val error = IllegalArgumentException("release failed")
            assertAwaitedFailure(error, runCatching { scheduler.close { throw error } }.exceptionOrNull())
            assertFalse(UsbIoThread.isBound())
            scheduler.start()
            assertTrue(scheduler.run { UsbIoThread.isIoThread() })
        }
    }
}
