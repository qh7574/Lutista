package cn.alittlecookie.lutista.tether.session

import android.os.Handler
import android.os.HandlerThread
import cn.alittlecookie.lutista.tether.transport.UsbIoThread
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One worker owns every USB operation, including open and final resource release.
 *
 * A request remains the only active request across suspension. Caller cancellation
 * removes queued work, but never cancels an already issued USB transaction. Fatal
 * state is explicit: [abortAll] preserves the original cause until close + start.
 */
class PtpScheduler internal constructor(private val workerFactory: () -> IoWorker) {
    constructor() : this({ HandlerIoWorker() })

    enum class Priority(val rank: Int) { ABORT(0), USER(1), PROBE(2), LIVEVIEW(3) }

    class ClosedException : IllegalStateException(
        "PtpScheduler is closed; call start() before reconnecting",
    )

    class QueueBudgetExceededException(val queueBudgetMs: Long) : IllegalStateException(
        "PtpScheduler queue budget exceeded after " + queueBudgetMs + "ms; request never started",
    )

    /** Test seam: production always uses [HandlerThread], never a coroutine pool. */
    internal interface IoWorker {
        val dispatcher: CoroutineDispatcher
        fun shutdown()
    }

    private class HandlerIoWorker : IoWorker {
        private val thread = HandlerThread(UsbIoThread.THREAD_NAME).apply { start() }
        override val dispatcher: CoroutineDispatcher =
            Handler(thread.looper).asCoroutineDispatcher(UsbIoThread.THREAD_NAME)
        override fun shutdown() { thread.quitSafely() }
    }

    private class WorkerContext(val scheduler: PtpScheduler) :
        AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<WorkerContext>
    }

    private class Req(
        val priority: Priority,
        val seq: Long,
        val done: CompletableDeferred<Any?>,
        val block: suspend () -> Any?,
    ) {
        // Accessed only while holding the scheduler lock; once true, never downgraded.
        var retainActiveResult = false
    }

    private inner class Generation(val worker: IoWorker) {
        val ready = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + worker.dispatcher + WorkerContext(this@PtpScheduler))
        val queue = PriorityQueue<Req>(compareBy({ it.priority.rank }, { it.seq }))
        val signal = Channel<Unit>(Channel.CONFLATED)
        var seq = 0L
        var active: Req? = null
        var draining: Deferred<Unit>? = null
        var fatal: Throwable? = null
        var closing = false
        var closeTask: suspend () -> Unit = {}
    }

    private val lock = Any()
    private var generation: Generation? = null
    private var idleFailure: Throwable? = null
    private var eventDrain: (suspend () -> Unit)? = null

    /** Active start is idempotent and does not clear a fatal latch. */
    suspend fun start() {
        checkNotWorker("start")
        ensureStarted(allowRestart = true)
    }

    /** No transport is retained here: the session installs its per-connection drain. */
    fun setEventDrain(drain: (suspend () -> Unit)?) {
        synchronized(lock) { eventDrain = drain }
    }

    suspend fun <T> run(
        priority: Priority = Priority.USER,
        block: suspend () -> T,
    ): T = submit("run", priority, null, block)

    /**
     * Limit waiting in the queue, never the duration of an admitted USB request.
     * Once active, the request must return its original result or exception.
     */
    suspend fun <T> runWithQueueBudget(
        priority: Priority,
        queueBudgetMs: Long,
        block: suspend () -> T,
    ): T {
        require(queueBudgetMs >= 0) { "queueBudgetMs must be non-negative" }
        return submit("runWithQueueBudget", priority, queueBudgetMs, block)
    }

    private suspend fun <T> submit(
        operation: String,
        priority: Priority,
        queueBudgetMs: Long?,
        block: suspend () -> T,
    ): T {
        checkNotWorker(operation)
        val gen = ensureStarted(allowRestart = false)
        return coroutineScope {
            val req = synchronized(lock) {
                checkAccepting(gen)
                Req(priority, gen.seq++, CompletableDeferred(), block).also { gen.queue.add(it) }
            }
            removeOnCancellation(gen, req)
            // This structured child only removes pending work. Consumer admission
            // (queue.poll + active assignment) uses this same lock, so a timeout
            // cannot race admission into cancelling a real USB transaction.
            val timer = queueBudgetMs?.let { budget ->
                launch(start = CoroutineStart.UNDISPATCHED) {
                    delay(budget)
                    synchronized(lock) {
                        if (gen.active !== req && gen.queue.remove(req)) {
                            req.done.completeExceptionally(QueueBudgetExceededException(budget))
                        }
                    }
                }
            }
            gen.signal.trySend(Unit)
            try {
                @Suppress("UNCHECKED_CAST")
                val value = req.done.await() as T
                value
            } catch (cancelled: CancellationException) {
                // This deferred has no caller Job parent. Cancelling it cannot cancel USB.
                req.done.cancel(cancelled)
                throw cancelled
            } finally {
                timer?.cancel()
            }
        }
    }

    /** At most one frame; frames use exactly the same queue and worker as commands. */
    fun <T> tryRunFrame(block: suspend () -> T): Deferred<T>? {
        val (gen, req) = synchronized(lock) {
            val current = generation ?: return null
            if (!current.ready.isCompleted || current.stopped.isCompleted ||
                current.closing || current.fatal != null ||
                current.active != null || current.queue.isNotEmpty()
            ) return null
            val request = Req(Priority.LIVEVIEW, current.seq++, CompletableDeferred(), block)
            current.queue.add(request)
            current to request
        }
        removeOnCancellation(gen, req)
        gen.signal.trySend(Unit)
        @Suppress("UNCHECKED_CAST")
        return req.done as Deferred<T>
    }

    /**
     * Fail waiters immediately, while the current block safely finishes on its worker.
     * A diagnostic block that reports fatal as a value may retain only its active
     * result. Later abort/close cannot replace it; cancelled waiters are never revived.
     */
    fun abortAll(cause: Throwable, retainActiveResult: Boolean = false) {
        val gen = synchronized(lock) {
            val current = generation
            if (current == null) {
                if (idleFailure == null) idleFailure = cause
                return
            }
            if (current.fatal == null) current.fatal = cause
            if (retainActiveResult) current.active?.retainActiveResult = true
            failWaitersLocked(current, current.fatal!!)
            current.draining?.cancel()
            current
        }
        gen.signal.trySend(Unit)
    }

    /** Includes the currently running non-frame request, not only queued requests. */
    fun pendingUserOps(): Int = synchronized(lock) {
        val gen = generation ?: return 0
        if (gen.closing || gen.fatal != null || gen.stopped.isCompleted) return 0
        gen.queue.count { it.priority != Priority.LIVEVIEW } +
            if (gen.active?.priority?.let { it != Priority.LIVEVIEW } == true) 1 else 0
    }

    /**
     * Stop admission, fail waiters, wait for the active USB block, then release on
     * its original worker. [onIoClose] bypasses the fatal queue and is run once.
     * A blocking bulkTransfer needs a real transaction timeout, not withTimeout.
     */
    suspend fun close(onIoClose: suspend () -> Unit = {}) {
        checkNotWorker("close")
        withContext(NonCancellable) {
            val gen = synchronized(lock) {
                val current = generation
                if (current == null) {
                    idleFailure = idleFailure ?: ClosedException()
                    eventDrain = null
                    return@withContext
                }
                if (!current.closing && !current.stopped.isCompleted) {
                    current.closing = true
                    current.closeTask = onIoClose
                    current.fatal = current.fatal ?: ClosedException()
                    failWaitersLocked(current, current.fatal!!)
                    current.draining?.cancel()
                    eventDrain = null
                }
                current
            }
            gen.signal.trySend(Unit)
            gen.stopped.await()
        }
    }

    private suspend fun ensureStarted(allowRestart: Boolean): Generation {
        while (true) {
            val gen = synchronized(lock) {
                val current = generation
                when {
                    current == null -> {
                        if (!allowRestart) idleFailure?.let { throw it }
                        newGenerationLocked()
                    }
                    current.stopped.isCompleted -> {
                        if (!allowRestart) throw current.fatal ?: ClosedException()
                        newGenerationLocked()
                    }
                    current.closing -> {
                        if (!allowRestart) throw current.fatal ?: ClosedException()
                        current
                    }
                    else -> current
                }
            }
            if (synchronized(lock) { gen.closing }) {
                gen.stopped.await()
                continue
            }
            gen.ready.await()
            return gen
        }
    }

    /** Called with lock held; binding is the first operation of the only worker. */
    private fun newGenerationLocked(): Generation {
        val gen = Generation(workerFactory())
        generation = gen
        idleFailure = null
        gen.scope.launch { consume(gen) }
        return gen
    }

    private fun checkAccepting(gen: Generation) {
        gen.fatal?.let { throw it }
        check(generation === gen && !gen.closing && !gen.stopped.isCompleted) {
            "PtpScheduler is no longer accepting requests"
        }
    }

    private fun removeOnCancellation(gen: Generation, req: Req) {
        req.done.invokeOnCompletion { cause ->
            if (cause is CancellationException) synchronized(lock) { gen.queue.remove(req) }
        }
    }

    private fun failWaitersLocked(gen: Generation, cause: Throwable) {
        while (true) (gen.queue.poll() ?: break).done.completeExceptionally(cause)
        gen.active?.takeUnless { it.retainActiveResult }?.done?.completeExceptionally(cause)
    }

    private suspend fun checkNotWorker(operation: String) {
        check(currentCoroutineContext()[WorkerContext]?.scheduler !== this) {
            "PtpScheduler.$operation cannot be nested inside its worker request"
        }
    }

    private suspend fun consume(gen: Generation) {
        var ownsBinding = false
        var failure: Throwable? = null
        try {
            // UsbIoThread.bind permits overwriting, so acquire a process-wide owner first.
            check(owner.compareAndSet(null, gen)) { "Another PtpScheduler owns ptp-io" }
            check(!UsbIoThread.isBound()) { "UsbIoThread is already bound by another owner" }
            UsbIoThread.bind(Thread.currentThread())
            ownsBinding = true
            gen.ready.complete(Unit)
            while (true) {
                val req = synchronized(lock) {
                    if (gen.closing || gen.fatal != null) null
                    else gen.queue.poll()?.also { gen.active = it }
                }
                if (req == null) {
                    if (synchronized(lock) { gen.closing }) break
                    if (gen.signal.receiveCatching().isClosed) break
                    continue
                }
                try {
                    if (!req.done.isCompleted) {
                        UsbIoThread.requireIoThread("PtpScheduler.run")
                        req.done.complete(req.block())
                    }
                } catch (t: Throwable) {
                    req.done.completeExceptionally(t)
                } finally {
                    synchronized(lock) { gen.active = null }
                }
                val drain = synchronized(lock) {
                    if (req.priority != Priority.LIVEVIEW && gen.queue.isEmpty() &&
                        !gen.closing && gen.fatal == null
                    ) eventDrain else null
                }
                if (drain != null) {
                    // Await on this same worker: no queued request may re-enter. A
                    // close can cancel suspended downstream delivery, while a real
                    // blocking drainEvents still returns on its own USB timeout.
                    val delivery = gen.scope.async(start = CoroutineStart.LAZY) {
                        UsbIoThread.requireIoThread("PtpScheduler.eventDrain")
                        drain()
                    }
                    val admitted = synchronized(lock) {
                        if (gen.closing || gen.fatal != null) false
                        else { gen.draining = delivery; true }
                    }
                    if (!admitted) delivery.cancel()
                    else {
                        try {
                            delivery.start()
                            delivery.await()
                        } catch (t: Throwable) {
                            synchronized(lock) {
                                if (gen.fatal == null) gen.fatal = t
                                failWaitersLocked(gen, gen.fatal!!)
                            }
                        } finally {
                            synchronized(lock) { gen.draining = null }
                        }
                    }
                }
            }
            UsbIoThread.requireIoThread("PtpScheduler.close")
            gen.closeTask()
        } catch (t: Throwable) {
            failure = t
            gen.ready.completeExceptionally(t)
            synchronized(lock) {
                if (gen.fatal == null) gen.fatal = t
                failWaitersLocked(gen, gen.fatal!!)
            }
        } finally {
            // Never let a failed/old scheduler unbind a different scheduler's thread.
            if (ownsBinding && owner.get() === gen && UsbIoThread.isIoThread()) {
                UsbIoThread.unbind()
            }
            owner.compareAndSet(gen, null)
            gen.signal.close()
            try { gen.worker.shutdown() } catch (t: Throwable) {
                if (failure == null) failure = t else failure.addSuppressed(t)
            }
            gen.scope.cancel()
            if (failure == null) gen.stopped.complete(Unit)
            else gen.stopped.completeExceptionally(failure)
        }
    }

    private companion object {
        val owner = AtomicReference<Any?>(null)
    }
}
