package cn.alittlecookie.lutista.tether.session

import cn.alittlecookie.lutista.tether.transport.PtpEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventPumpTest {
    private fun event(code: Int, vararg params: Long) = PtpEvent(code, params, byteArrayOf())
    private fun added(handle: Long) = event(0xC108, handle)

    @Test fun `every object event in a coalesced batch is delivered`() = runBlocking {
        withTimeout(5_000) {
            val pump = EventPump()
            val received = async(start = CoroutineStart.UNDISPATCHED) { pump.events.take(2).toList() }
            pump.accept(listOf(added(11), added(12)))
            assertEquals(listOf(11L, 12L), received.await().map { it.handle })
            assertEquals(2L, pump.stats.value.received)
            assertEquals(2L, pump.stats.value.objectAdded)
        }
    }

    @Test fun `all batches are consumed and duplicate handles are suppressed`() = runBlocking {
        withTimeout(5_000) {
            val pump = EventPump()
            val received = async(start = CoroutineStart.UNDISPATCHED) { pump.events.take(2).toList() }
            pump.accept(listOf(added(21), added(21)))
            pump.accept(listOf(added(22), added(22)))
            assertEquals(listOf(21L, 22L), received.await().map { it.handle })
            assertEquals(4L, pump.stats.value.received)
            assertEquals(2L, pump.stats.value.duplicates)
        }
    }

    @Test fun `C101 and C107 are noise while unknown codes never throw`() = runBlocking {
        val pump = EventPump()
        pump.accept(
            List(5) { event(0xC101) } + List(7) { event(0xC107) } +
                listOf(event(0xC102), event(0xC103), event(0xFFFF), event(0x4006)),
        )
        assertEquals(16L, pump.stats.value.received)
        assertEquals(12L, pump.stats.value.noise)
        assertEquals(3L, pump.stats.value.unknown)
        assertEquals(0L, pump.stats.value.objectAdded)
        assertEquals(1L, pump.devicePropertyChanges.value)
    }

    @Test fun `4006 updates state without producing a capture event`() = runBlocking {
        val pump = EventPump()
        pump.accept(listOf(event(0x4006, 0x5001), event(0x4006, 0x5002)))
        assertEquals(2L, pump.devicePropertyChanges.value)
        assertEquals(0L, pump.stats.value.objectAdded)
        assertEquals(0L, pump.stats.value.unknown)
    }

    @Test fun `reset remembers forwarded and explicitly processed handles across sessions`() = runBlocking {
        withTimeout(5_000) {
            val pump = EventPump()
            val first = async(start = CoroutineStart.UNDISPATCHED) { pump.events.take(1).toList() }
            pump.accept(listOf(added(31)))
            assertEquals(31L, first.await().single().handle)
            pump.markProcessed(32)
            pump.resetSession()
            val second = async(start = CoroutineStart.UNDISPATCHED) { pump.events.take(1).toList() }
            pump.accept(listOf(added(31), added(32), added(33)))
            assertEquals(33L, second.await().single().handle)
            assertEquals(2L, pump.stats.value.replayed)
            assertEquals(1L, pump.stats.value.objectAdded)
            assertEquals(2, pump.stats.value.processedHandles)
        }
    }

    @Test fun `new camera can clear replay history because handles are not stable identities`() = runBlocking {
        withTimeout(5_000) {
            val pump = EventPump()
            pump.markProcessed(41)
            pump.accept(listOf(added(41)))
            assertEquals(1L, pump.stats.value.replayed)
            pump.clearProcessed()
            val received = async(start = CoroutineStart.UNDISPATCHED) { pump.events.take(1).toList() }
            pump.accept(listOf(added(41)))
            assertEquals(41L, received.await().single().handle)
            assertEquals(0, pump.stats.value.processedHandles)
        }
    }

    @Test fun `buffered old acknowledgments cannot suppress a new camera using the same handle`() = runBlocking {
        withTimeout(5_000) {
            val pump = EventPump()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val oldAcknowledged = CompletableDeferred<Unit>()
            val received = mutableListOf<EventPump.ObjectAdded>()
            val acknowledgments = mutableListOf<Boolean>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                pump.events.take(3).collect { notification ->
                    received.add(notification)
                    if (received.size == 1) { entered.complete(Unit); release.await() }
                    acknowledgments.add(pump.markProcessed(notification))
                    if (received.size == 2) oldAcknowledged.complete(Unit)
                }
            }
            pump.accept(listOf(added(61)))
            entered.await()
            pump.accept(listOf(added(62)))
            assertEquals(listOf(61L), received.map { it.handle })
            assertEquals(EventPump.ObjectAdded(61), received.single())
            assertTrue(pump.isCurrent(received.single()))

            pump.clearProcessed()
            assertFalse(pump.isCurrent(received.single()))
            release.complete(Unit)
            oldAcknowledged.await()
            assertEquals(listOf(61L, 62L), received.map { it.handle })
            assertEquals(listOf(false, false), acknowledgments)
            assertEquals(0, pump.stats.value.processedHandles)

            pump.accept(listOf(added(62)))
            collector.join()
            assertEquals(listOf(61L, 62L, 62L), received.map { it.handle })
            assertFalse(pump.isCurrent(received[1]))
            assertTrue(pump.isCurrent(received[2]))
            assertEquals(listOf(false, false, true), acknowledgments)
            assertEquals(1, pump.stats.value.processedHandles)
            assertEquals(0L, pump.stats.value.replayed)
            pump.resetSession()
            pump.accept(listOf(added(62)))
            assertEquals(1L, pump.stats.value.replayed)
        }
    }

    @Test fun `reset rejects buffered old acknowledgments while retaining replay suppression`() = runBlocking {
        withTimeout(5_000) {
            val pump = EventPump()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val oldAcknowledged = CompletableDeferred<Unit>()
            val received = mutableListOf<EventPump.ObjectAdded>()
            val acknowledgments = mutableListOf<Boolean>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                pump.events.take(3).collect { notification ->
                    received.add(notification)
                    if (received.size == 1) { entered.complete(Unit); release.await() }
                    acknowledgments.add(pump.markProcessed(notification))
                    if (received.size == 2) oldAcknowledged.complete(Unit)
                }
            }
            pump.accept(listOf(added(71)))
            entered.await()
            pump.accept(listOf(added(72)))
            assertEquals(listOf(71L), received.map { it.handle })
            pump.resetSession()
            assertFalse(pump.isCurrent(received.single()))
            release.complete(Unit)
            oldAcknowledged.await()
            assertEquals(listOf(false, false), acknowledgments)
            assertEquals(2, pump.stats.value.processedHandles)
            pump.accept(listOf(added(72), added(73)))
            collector.join()
            assertEquals(listOf(71L, 72L, 73L), received.map { it.handle })
            assertFalse(pump.isCurrent(received[1]))
            assertTrue(pump.isCurrent(received[2]))
            assertEquals(listOf(false, false, true), acknowledgments)
            assertEquals(1L, pump.stats.value.replayed)
            assertEquals(3, pump.stats.value.processedHandles)
        }
    }

    @Test fun `malformed object events are counted instead of asserted`() = runBlocking {
        val pump = EventPump()
        pump.accept(listOf(event(0xC108), added(0), added(-1), added(0xFFFF_FFFFL)))
        assertEquals(4L, pump.stats.value.malformed)
        assertEquals(0L, pump.stats.value.objectAdded)
        assertEquals(4L, pump.stats.value.received)
    }

    @Test fun `discard counter uses observed deltas and tolerates transport reset`() = runBlocking {
        val pump = EventPump()
        pump.accept(emptyList(), discardedEventContainers = 5)
        pump.accept(emptyList(), discardedEventContainers = 5)
        assertEquals(5L, pump.stats.value.discardedEventContainers)
        pump.accept(emptyList(), discardedEventContainers = 0)
        pump.accept(emptyList(), discardedEventContainers = 2)
        assertEquals(7L, pump.stats.value.discardedEventContainers)
        pump.resetSession()
        assertEquals(0L, pump.stats.value.discardedEventContainers)
        pump.accept(emptyList(), discardedEventContainers = 1)
        assertEquals(1L, pump.stats.value.discardedEventContainers)
    }

    @Test fun `no active subscriber is counted explicitly instead of claimed as delivery`() = runBlocking {
        val warnings = mutableListOf<EventPump.Stats>()
        val pump = EventPump { warnings.add(it) }
        pump.accept(listOf(added(51), added(52)))
        assertEquals(2L, pump.stats.value.unobserved)
        assertEquals(2L, pump.stats.value.objectAdded)
        assertEquals(2, warnings.size)
    }

    @Test fun `slow collector creates visible bounded backpressure without losing batch entries`() = runBlocking {
        withTimeout(5_000) {
            var warnings = 0
            val pump = EventPump {
                warnings++
                // A diagnostic failure must not alter event delivery.
                error("diagnostic sink failure")
            }
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val handles = mutableListOf<Long>()
            val count = EventPump.EVENT_BUFFER_CAPACITY + 6
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                pump.events.take(count).collect {
                    handles.add(it.handle)
                    if (handles.size == 1) { entered.complete(Unit); release.await() }
                }
            }
            val producer = async(start = CoroutineStart.UNDISPATCHED) {
                pump.accept((1..count).map { added(it.toLong()) })
            }
            entered.await()
            assertFalse(producer.isCompleted)
            assertTrue(pump.stats.value.backpressure > 0)
            release.complete(Unit)
            producer.await()
            collector.join()
            assertEquals((1..count).map { it.toLong() }, handles)
            assertEquals(count.toLong(), pump.stats.value.objectAdded)
            assertTrue(warnings > 0)
        }
    }
}
