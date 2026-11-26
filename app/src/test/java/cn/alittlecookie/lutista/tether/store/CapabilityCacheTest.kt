package cn.alittlecookie.lutista.tether.store

import cn.alittlecookie.lutista.tether.profile.CameraCapabilities
import cn.alittlecookie.lutista.tether.profile.Feature
import cn.alittlecookie.lutista.tether.probe.CapabilityProbe
import cn.alittlecookie.lutista.tether.probe.ProbeFixtures
import cn.alittlecookie.lutista.tether.protocol.Availability
import cn.alittlecookie.lutista.tether.protocol.Capability
import cn.alittlecookie.lutista.tether.protocol.CapabilityEvidence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class CapabilityCacheTest {
    private class Memory {
        var json = ""
        var writes = 0
        val logs = mutableListOf<String>()
        val cache = CapabilityCache(
            read = { json },
            write = { json = it; writes++ },
            log = { logs += it },
            codec = JvmCacheJsonCodec,
        )
    }
    private fun caps(model: String = "DC-S1", serial: String = "serial", firmware: String = "2.4") =
        CameraCapabilities.empty().copy(model = model, serial = serial, firmware = firmware, profileId = "lumix-s1", probedAt = 1234L, totalProbeMs = 39L, probedModePos = 3L)
            .with(Feature.ConfigWrite, Capability.ok(CapabilityEvidence.PTP_OK, "historical count=29; display only", writable = true))
            .with(Feature.LiveView, Capability.unknown(CapabilityEvidence.TIMEOUT, "previous queue timeout"))
    private fun key(model: String = "DC-S1", serial: String = "serial", firmware: String = "2.4", schema: Int = 1) = CapabilityCacheKey(model, serial, firmware, schema)

    @Test fun roundTripPersistsOnlyFactsAndAlwaysClearsWritableAndMode() = runBlocking {
        val memory = Memory()
        val original = caps()
        memory.cache.save(original)
        assertFalse(memory.json.contains("writable"))
        assertFalse(memory.json.contains("modePos", ignoreCase = true))
        assertFalse(memory.json.contains("probedModePos"))
        val loaded = memory.cache.load(key())!!
        assertEquals(original[Feature.ConfigWrite].availability, loaded[Feature.ConfigWrite].availability)
        assertEquals(original[Feature.ConfigWrite].evidence, loaded[Feature.ConfigWrite].evidence)
        assertEquals(original[Feature.ConfigWrite].detail, loaded[Feature.ConfigWrite].detail)
        assertFalse(loaded[Feature.ConfigWrite].writable)
        assertTrue(original[Feature.ConfigWrite].writable)
        assertNull(loaded.probedModePos)
        assertEquals(0L, loaded.totalProbeMs)
        assertEquals(1234L, loaded.probedAt)
        assertEquals(CapabilityEvidence.TIMEOUT, loaded[Feature.LiveView].evidence)
    }

    @Test fun cacheKeyMatchesModelSerialFirmwareAndSchemaIndependently() = runBlocking {
        val memory = Memory()
        memory.cache.save(caps())
        assertNotNull(memory.cache.load(key()))
        assertNull(memory.cache.load(key(model = "DC-S1R")))
        assertNull(memory.cache.load(key(serial = "another")))
        assertNull(memory.cache.load(key(firmware = "2.5")))
        assertNull(memory.cache.load(key(schema = 2)))
    }

    @Test fun loadPromotesEntryAndFifthBodyEvictsLeastRecentlyUsed() = runBlocking {
        val memory = Memory()
        for (model in listOf("A", "B", "C", "D")) memory.cache.save(caps(model))
        assertNotNull(memory.cache.load(key("A")))
        memory.cache.save(caps("E"))
        assertEquals(listOf("E", "A", "D", "C"), JvmCacheJsonCodec.decode(memory.json).entries.map { it.key.model })
        assertNull(memory.cache.load(key("B")))
        assertNotNull(memory.cache.load(key("A")))
        assertEquals(4, JvmCacheJsonCodec.decode(memory.json).entries.size)
    }

    @Test fun savingSameKeyReplacesFactsWithoutConsumingAnotherSlot() = runBlocking {
        val memory = Memory()
        memory.cache.save(caps())
        memory.cache.save(caps().with(Feature.ConfigWrite, Capability.no(CapabilityEvidence.PTP_REJECTED, "new reject")))
        assertEquals(1, JvmCacheJsonCodec.decode(memory.json).entries.size)
        val restored = memory.cache.load(key())!![Feature.ConfigWrite]
        assertEquals(Availability.Unsupported, restored.availability)
        assertEquals("new reject", restored.detail)
    }

    @Test fun malformedAndFutureSchemaJsonAreCacheMissesAndNextSaveRecovers() = runBlocking {
        val memory = Memory()
        memory.json = "{not-json"
        assertNull(memory.cache.load(key()))
        memory.json = "{\"schema\":2,\"entries\":[]}"
        assertNull(memory.cache.load(key()))
        memory.cache.save(caps())
        assertNotNull(memory.cache.load(key()))
        assertEquals(1, JvmCacheJsonCodec.decode(memory.json).schema)
    }

    @Test fun maliciousLegacyWritableAndModeFieldsCannotGrantAuthority() = runBlocking {
        val memory = Memory()
        memory.cache.save(caps())
        memory.json = memory.json.replace("\"availability\":\"Supported\"", "\"writable\":true,\"availability\":\"Supported\"")
            .replace("\"profileId\":", "\"probedModePos\":5,\"modePos\":5,\"profileId\":")
        val loaded = memory.cache.load(key())!!
        assertFalse(loaded[Feature.ConfigWrite].writable)
        assertNull(loaded.probedModePos)
        assertEquals("historical count=29; display only", loaded[Feature.ConfigWrite].detail)
    }

    @Test fun invalidationClearsPersistedCacheAndLogsSchemaAndSize() = runBlocking {
        val memory = Memory()
        memory.cache.save(caps())
        memory.cache.invalidate()
        assertEquals("", memory.json)
        assertNull(memory.cache.load(key()))
        assertTrue(memory.logs.any { it.contains("cache invalidate") && it.contains("schema=1") && it.contains("lruCount=0") })
        assertTrue(memory.logs.any { it.contains("cache miss") && it.contains("DC-S1") && it.contains("serial") })
    }

    @Test fun persistenceFailuresDoNotPreventConnectionAndAreDiagnosed() = runBlocking {
        val logs = mutableListOf<String>()
        val cache = CapabilityCache(read = { throw IllegalStateException("synthetic read failure") }, write = { throw IllegalStateException("synthetic write failure") }, log = { logs += it }, codec = JvmCacheJsonCodec)
        assertNull(cache.load(key()))
        cache.save(caps())
        cache.invalidate()
        assertTrue(logs.any { it.contains("cache read failed") })
        assertTrue(logs.any { it.contains("cache write failed") })
        assertTrue(logs.any { it.contains("cache invalidate failed") })
    }

    @Test fun cacheCancellationIsNotSwallowedByFailureFallback() = runBlocking {
        val expected = CancellationException("cache scope closed")
        val cache = CapabilityCache(read = { throw expected }, write = { fail("must not write") }, codec = JvmCacheJsonCodec)
        try { cache.load(key()); fail("must propagate cancellation") }
        catch (actual: CancellationException) { assertSame(expected, actual) }
    }

    @Test fun concurrentSavesDoNotLoseEntriesAcrossSuspendingLambdas() = runBlocking {
        var json = ""
        val cache = CapabilityCache(read = { yield(); json }, write = { yield(); json = it }, codec = JvmCacheJsonCodec)
        (1..4).map { model -> launch { cache.save(caps(model.toString())) } }.joinAll()
        assertEquals(setOf("1", "2", "3", "4"), JvmCacheJsonCodec.decode(json).entries.map { it.key.model }.toSet())
    }

    @Test fun cacheHitSupplementsP6WithoutIssuingAnyProbeTransaction() = runBlocking {
        val memory = Memory()
        memory.cache.save(caps())
        val device = ProbeFixtures.caps()
        val probe = CapabilityProbe(execute = { _, _ -> error("Cache hit must bypass P1..P5") }, isFatal = { false })
        val result = probe.supplementP6(memory.cache.load(key())!!, device, ProbeFixtures.profile(device))
        assertEquals(0L, result.totalProbeMs)
        assertFalse(result[Feature.ConfigWrite].writable)
        assertEquals(Availability.Supported, result[Feature.Battery].availability)
        assertEquals(Availability.Unknown, result[Feature.LiveView].availability)
        assertNull(result.probedModePos)
    }
}
