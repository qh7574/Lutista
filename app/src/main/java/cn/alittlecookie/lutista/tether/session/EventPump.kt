package cn.alittlecookie.lutista.tether.session

import cn.alittlecookie.lutista.tether.transport.PtpEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Consumes complete batches already decoded by the transport; it performs no USB
 * reads and does not reinterpret PtpEvent.raw as URB bytes. Active collectors must
 * subscribe before draining. Delivery is bounded-buffer + backpressure, never a
 * successful-looking tryEmit drop. Slow collectors must move expensive work out
 * of their collect callback. [onBackpressure] reports both buffer saturation and
 * emission without an active subscriber; the latter is counted as unobserved,
 * never claimed as consumer delivery. Connect this callback to diagnostics.
 */
class EventPump(private val onBackpressure: (Stats) -> Unit = {}) {
    data class ObjectAdded(val handle: Long, val generation: Long = 0L)

    data class Stats(
        val received: Long = 0L,
        val objectAdded: Long = 0L,
        val duplicates: Long = 0L,
        val replayed: Long = 0L,
        val noise: Long = 0L,
        val unknown: Long = 0L,
        val malformed: Long = 0L,
        val discardedEventContainers: Long = 0L,
        val backpressure: Long = 0L,
        val unobserved: Long = 0L,
        val processedHandles: Int = 0,
    )

    private val lock = Any()
    private val acceptLock = Mutex()
    private val seen = mutableSetOf<Long>()
    private val processed = mutableSetOf<Long>()
    private var lastDiscarded = 0
    private var generation = 0L
    private val mutableEvents = MutableSharedFlow<ObjectAdded>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)
    private val mutableChanges = MutableStateFlow(0L)
    private val mutableStats = MutableStateFlow(Stats())

    val events: SharedFlow<ObjectAdded> = mutableEvents.asSharedFlow()
    /** 0x4006 is a state invalidation only, never an object/capture event. */
    val devicePropertyChanges: StateFlow<Long> = mutableChanges.asStateFlow()
    val stats: StateFlow<Stats> = mutableStats.asStateFlow()

    /**
     * Process every C108 in the batch, including coalesced/cross-round events.
     * [discardedEventContainers] is the real transport cumulative snapshot; a
     * counter rollback begins a new transport epoch, not a negative delta.
     */
    suspend fun accept(
        events: List<PtpEvent>,
        discardedEventContainers: Int = 0,
    ) = acceptLock.withLock {
        synchronized(lock) {
            val current = discardedEventContainers.coerceAtLeast(0)
            val delta = if (current >= lastDiscarded) current - lastDiscarded else current
            lastDiscarded = current
            mutableStats.value = mutableStats.value.copy(
                discardedEventContainers = mutableStats.value.discardedEventContainers + delta,
            )
        }
        for (event in events) {
            val added = synchronized(lock) {
                val previous = mutableStats.value
                mutableStats.value = previous.copy(received = previous.received + 1)
                when (event.code) {
                    OBJECT_ADDED -> acceptHandleLocked(event)
                    DEVICE_PROPERTY_CHANGED -> {
                        mutableChanges.value += 1L
                        null
                    }
                    NOISE_C101, NOISE_C107 -> {
                        mutableStats.value = mutableStats.value.copy(noise = mutableStats.value.noise + 1)
                        null
                    }
                    else -> {
                        mutableStats.value = mutableStats.value.copy(unknown = mutableStats.value.unknown + 1)
                        null
                    }
                }
            } ?: continue

            try {
                if (mutableEvents.subscriptionCount.value == 0) {
                    val snapshot = synchronized(lock) {
                        mutableStats.value = mutableStats.value.copy(
                            unobserved = mutableStats.value.unobserved + 1,
                        )
                        mutableStats.value
                    }
                    runCatching { onBackpressure(snapshot) }
                }
                if (!mutableEvents.tryEmit(added)) {
                    val snapshot = synchronized(lock) {
                        mutableStats.value = mutableStats.value.copy(
                            backpressure = mutableStats.value.backpressure + 1,
                        )
                        mutableStats.value
                    }
                    // A broken diagnostic sink must not discard a camera event.
                    runCatching { onBackpressure(snapshot) }
                    mutableEvents.emit(added)
                }
                synchronized(lock) {
                    mutableStats.value = mutableStats.value.copy(
                        objectAdded = mutableStats.value.objectAdded + 1,
                    )
                }
            } catch (cancelled: CancellationException) {
                synchronized(lock) {
                    if (added.generation == generation) seen.remove(added.handle)
                }
                throw cancelled
            }
        }
    }

    private fun acceptHandleLocked(event: PtpEvent): ObjectAdded? {
        val handle = event.params.firstOrNull()
        if (handle == null || handle !in 1L..0xFFFF_FFFEL) {
            mutableStats.value = mutableStats.value.copy(malformed = mutableStats.value.malformed + 1)
            return null
        }
        if (handle in seen) {
            mutableStats.value = mutableStats.value.copy(duplicates = mutableStats.value.duplicates + 1)
            return null
        }
        if (handle in processed) {
            mutableStats.value = mutableStats.value.copy(replayed = mutableStats.value.replayed + 1)
            return null
        }
        seen.add(handle)
        return ObjectAdded(handle, generation)
    }

    /** Buffered notifications may outlive the session that accepted them. */
    fun isCurrent(event: ObjectAdded): Boolean = synchronized(lock) {
        event.generation == generation
    }

    /** Returns false without changing replay history for an old notification. */
    fun markProcessed(event: ObjectAdded): Boolean = synchronized(lock) {
        if (event.generation != generation) return@synchronized false
        markProcessed(event.handle)
        true
    }

    /** Explicit legacy acknowledgment for the current generation. */
    fun markProcessed(handle: Long) {
        synchronized(lock) {
            processed.add(handle)
            mutableStats.value = mutableStats.value.copy(processedHandles = processed.size)
        }
    }

    /**
     * Call between drain batches, after stopping the old session. Remember every
     * forwarded handle so residual events replayed by OpenSession are ignored.
     * This is replay suppression, not a durable object identity or download index.
     */
    fun resetSession() {
        synchronized(lock) {
            generation += 1L
            processed.addAll(seen)
            seen.clear()
            lastDiscarded = 0
            mutableChanges.value = 0L
            mutableStats.value = Stats(processedHandles = processed.size)
        }
    }

    /** New physical camera / re-numbering boundary: handles are not stable IDs. */
    fun clearProcessed() {
        synchronized(lock) {
            generation += 1L
            processed.clear()
            seen.clear()
            mutableStats.value = mutableStats.value.copy(processedHandles = 0)
        }
    }

    companion object {
        const val EVENT_BUFFER_CAPACITY = 64
        private const val OBJECT_ADDED = 0xC108
        private const val DEVICE_PROPERTY_CHANGED = 0x4006
        private const val NOISE_C101 = 0xC101
        private const val NOISE_C107 = 0xC107
    }
}
