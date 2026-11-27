package cn.alittlecookie.lutista.tether.repository

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** The service and its ready/destroy callbacks belong to one enable attempt. */
internal class ForegroundServiceLease {
    internal class Lease(val token: Long) {
        val ready = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        internal var stopRequested = false
    }

    private var sequence = 0L
    private var current: Lease? = null

    @Synchronized fun current(): Lease? = current

    @Synchronized fun begin(): Lease {
        check(current == null) { "Previous foreground service has not stopped" }
        return Lease(++sequence).also { current = it }
    }

    @Synchronized fun accepts(token: Long): Boolean =
        current?.let { it.token == token && !it.stopRequested } == true

    @Synchronized fun started(token: Long): Boolean {
        val lease = current?.takeIf { it.token == token && !it.stopRequested } ?: return false
        lease.ready.complete(Unit)
        return true
    }

    @Synchronized fun failed(token: Long, error: Throwable): Boolean {
        val lease = current?.takeIf { it.token == token } ?: return false
        lease.ready.completeExceptionally(error)
        return !lease.stopRequested
    }

    @Synchronized fun requestStop(): Lease? = current?.also {
        it.stopRequested = true
        it.ready.completeExceptionally(CancellationException("Foreground service is stopping"))
    }

    /** Used only when Android rejected startForegroundService before accepting a start. */
    @Synchronized fun startRejected(token: Long, error: Throwable) {
        val lease = current?.takeIf { it.token == token } ?: return
        lease.ready.completeExceptionally(error)
        lease.stopped.complete(Unit)
        current = null
    }

    /** Returns true only for an unexpected death of the currently owned instance. */
    @Synchronized fun destroyed(token: Long): Boolean {
        val lease = current?.takeIf { it.token == token } ?: return false
        val unexpected = !lease.stopRequested
        lease.ready.completeExceptionally(CancellationException("Foreground service was destroyed"))
        lease.stopped.complete(Unit)
        current = null
        return unexpected
    }
}

/**
 * Register synchronously before enqueueing an event. The same concurrent set is given to
 * completeBaseline, including observations made while TransferEngine.startSession suspends.
 * A closed window can neither publish a source nor acknowledge work in a later connection.
 */
internal class SessionEventBarrier<D : Any, S : Any> {
    internal class Window<D : Any, S : Any>(val epoch: Long) {
        val ready = CompletableDeferred<S>()
        val observedHandles: MutableSet<Long> = ConcurrentHashMap.newKeySet()
        internal var driver: D? = null
        internal var generation: Long? = null
        internal var published = false
    }

    internal data class Registration<D : Any, S : Any>(
        val window: Window<D, S>, val driver: D, val generation: Long, val handle: Long,
    )

    private var sequence = 0L
    private var current: Window<D, S>? = null

    @Synchronized fun begin(): Window<D, S> {
        invalidate()
        return Window<D, S>(++sequence).also { current = it }
    }

    @Synchronized fun current(): Window<D, S>? = current

    @Synchronized fun register(driver: D, generation: Long, handle: Long): Registration<D, S>? {
        val window = current ?: return null
        if (window.driver != null && window.driver !== driver) return null
        if (window.generation != null && window.generation != generation) {
            // An initial OpenSession may reset the pump before the source is published.
            if (window.published) return null
            window.observedHandles.clear()
        }
        window.driver = driver
        window.generation = generation
        window.observedHandles.add(handle)
        return Registration(window, driver, generation, handle)
    }

    @Synchronized fun publish(window: Window<D, S>, driver: D, source: S): Boolean {
        if (current !== window || (window.driver != null && window.driver !== driver)) return false
        window.driver = driver
        window.published = true
        return window.ready.complete(source)
    }

    @Synchronized fun isCurrent(window: Window<D, S>): Boolean = current === window

    @Synchronized fun isCurrent(event: Registration<D, S>): Boolean =
        current === event.window && event.window.driver === event.driver &&
            event.window.generation == event.generation

    @Synchronized fun invalidate(window: Window<D, S>? = current) {
        if (window == null || current !== window) return
        current = null
        window.ready.completeExceptionally(CancellationException("Camera connection was closed"))
    }
}

internal data class TetherCleanupStep(val name: String, val run: suspend () -> Unit)
internal data class TetherCleanupFailure(val step: String, val cause: Throwable)

/** Shared by the real close path and JVM tests: every release runs even if a prior one fails. */
internal suspend fun runTetherCleanup(vararg steps: TetherCleanupStep): List<TetherCleanupFailure> =
    withContext(NonCancellable) {
        buildList {
            for (step in steps) {
                try { step.run() }
                catch (failure: Throwable) { add(TetherCleanupFailure(step.name, failure)) }
            }
        }
    }
