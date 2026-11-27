package cn.alittlecookie.lutista.tether.render

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** A synchronous, reentrant transaction: never suspend while holding this lease. */
class GpuFilterLease {
    private val lock = ReentrantLock()
    private var version = 0L

    val generation: Long get() = lock.withLock { version }

    fun invalidate(): Long = lock.withLock { ++version }

    fun <T> withLease(block: () -> T): T = lock.withLock(block)

    /** Preview yields immediately to a static-image transaction, including its uploads. */
    fun <T> tryWithLease(block: () -> T): T? {
        if (!lock.tryLock()) return null
        return try { block() } finally { lock.unlock() }
    }
}
