package cn.alittlecookie.lutista.tether.render

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class LatestFrameProcessorTest {
    private class Owned(val number: Int) : AutoCloseable {
        var closes = 0
        override fun close() { closes++ }
    }

    @Test fun `slow render retains only latest pending input`() = runBlocking {
        withTimeout(5_000) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val processed = mutableListOf<Int>()
            val drops = AtomicInteger()
            val processor = LatestFrameProcessor<Int, Owned>(this, { drops.incrementAndGet() }) {
                processed.add(it)
                if (it == 1) { entered.complete(Unit); release.await() }
                Owned(it)
            }
            try {
                val received = async(start = CoroutineStart.UNDISPATCHED) { processor.frames.take(2).toList() }
                assertTrue(processor.submit(1))
                entered.await()
                for (number in 2..100) assertTrue(processor.submit(number))
                release.complete(Unit)
                val results = received.await()
                assertEquals(listOf(1, 100), processed)
                assertEquals(listOf(1, 100), results.map { it.number })
                assertEquals(98, drops.get())
                results.forEach { assertEquals(0, it.closes); it.close() }
            } finally { release.complete(Unit); processor.close() }
        }
    }

    @Test fun `only unreceived outputs are closed on overwrite and shutdown`() = runBlocking {
        withTimeout(5_000) {
            val made = mutableListOf<Owned>()
            val processor = LatestFrameProcessor<Int, Owned>(this) { Owned(it).also(made::add) }
            try {
                processor.submit(1); yield()
                processor.submit(2); yield()
                assertEquals(1, made[0].closes)
                val displayed = processor.frames.first()
                assertEquals(2, displayed.number)
                processor.submit(3); yield()
                processor.close()
                assertEquals(0, displayed.closes) // still visible in AndroidView until consumer releases it
                assertEquals(1, made[2].closes)
                displayed.close()
                assertEquals(1, displayed.closes)
                assertFalse(processor.submit(4))
            } finally { processor.close() }
        }
    }

    @Test fun `one failed render does not kill latest frame worker`() = runBlocking {
        withTimeout(5_000) {
            val attempted = CompletableDeferred<Unit>()
            val processor = LatestFrameProcessor<Int, Owned>(this) {
                if (it == 1) { attempted.complete(Unit); error("bad JPEG") }
                Owned(it)
            }
            try {
                processor.submit(1)
                attempted.await()
                processor.submit(2)
                val good = processor.frames.first()
                assertEquals(2, good.number)
                good.close()
            } finally { processor.close() }
        }
    }
}
