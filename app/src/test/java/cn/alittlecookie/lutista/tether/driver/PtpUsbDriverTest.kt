package cn.alittlecookie.lutista.tether.driver

import cn.alittlecookie.lutista.BuildConfig
import cn.alittlecookie.lutista.tether.debug.CapabilityOverride
import cn.alittlecookie.lutista.tether.debug.TetherDebugConfig
import cn.alittlecookie.lutista.tether.profile.CameraProfile
import cn.alittlecookie.lutista.tether.profile.CameraProfileRegistry
import cn.alittlecookie.lutista.tether.profile.Feature
import cn.alittlecookie.lutista.tether.profile.profiles.GenericPtpProfile
import cn.alittlecookie.lutista.tether.profile.profiles.LumixS1Profile
import cn.alittlecookie.lutista.tether.protocol.Availability
import cn.alittlecookie.lutista.tether.protocol.CapabilityEvidence
import cn.alittlecookie.lutista.tether.protocol.PtpConstants
import cn.alittlecookie.lutista.tether.protocol.PtpConstants.Op
import cn.alittlecookie.lutista.tether.protocol.PtpConstants.PanasonicOp
import cn.alittlecookie.lutista.tether.session.TetherPhase
import cn.alittlecookie.lutista.tether.store.CapabilityCacheEntry
import cn.alittlecookie.lutista.tether.store.CapabilityCacheKey
import cn.alittlecookie.lutista.tether.store.CachedCapability
import cn.alittlecookie.lutista.tether.transport.PtpDeviceCaps
import cn.alittlecookie.lutista.tether.transport.PtpTimeoutException
import cn.alittlecookie.lutista.tether.transport.UsbIoThread
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class PtpUsbDriverTest {
    @Test fun missUsesRealQueueWarmIdentityAndConcreteStorageThenPublishesTransientMode() = runBlocking {
        val store = DriverCache()
        withDriverRig(store = store) { rig ->
            assertSame(rig.session.caps, rig.driver.capabilities)
            assertNull(rig.driver.status.battery.value)
            assertNull(rig.driver.status.modePos.value)
            assertEquals(ConnectOutcome.Connected, rig.driver.connect())
            val result = rig.published()
            assertEquals("lumix-s1", rig.driver.profileId)
            assertEquals(rig.link.warmCaps.model, result.model)
            assertEquals(rig.link.warmCaps.serialNumber, result.serial)
            assertEquals(rig.link.warmCaps.deviceVersion, result.firmware)
            assertEquals(Availability.Supported, result[Feature.LiveView].availability)
            assertEquals(Availability.Supported, result[Feature.ObjectBrowse].availability)
            assertTrue(result[Feature.ConfigWrite].writable)
            assertEquals(5L, rig.driver.status.modePos.value)
            assertNull(rig.driver.status.battery.value)
            val handles = rig.link.ops.filter { it.code == Op.GET_OBJECT_HANDLES }
            assertEquals(listOf(PtpConstants.ALL_STORAGE_IDS, rig.link.storageId), handles.map { it.params[0] })
            assertTrue(handles.all { it.params.contentEquals(longArrayOf(it.params[0], 0L, PtpConstants.ROOT_PARENT_HANDLE)) })
            assertEquals(listOf(Op.OPEN_SESSION, Op.GET_DEVICE_INFO, Op.GET_STORAGE_IDS), rig.link.ops.take(3).map { it.code })
            // requireIoThread in DriverTransport checks the bound physical owner;
            // coroutine debug mode only decorates that same thread's visible name.
            assertTrue(rig.link.threads.all { it.substringBefore(" @coroutine#") == UsbIoThread.THREAD_NAME })
            assertEquals(0, UsbIoThread.violationCount())
            withTimeout(5_000) { store.saved.await() }
            val restored = store.cache.load(key(rig.link.warmCaps))!!
            assertNull(restored.probedModePos)
            assertFalse(restored[Feature.ConfigWrite].writable)
        }
        // The same persisted facts may serve the next session, but its current mode stays unknown.
        withDriverRig(store = store) { rig ->
            assertEquals(ConnectOutcome.Connected, rig.driver.connect())
            val cached = rig.published()
            assertEquals(0L, cached.totalProbeMs)
            assertNull(cached.probedModePos)
            assertNull(rig.driver.status.modePos.value)
            assertNull(rig.driver.status.battery.value)
            assertEquals(listOf(Op.OPEN_SESSION, Op.GET_DEVICE_INFO, Op.GET_STORAGE_IDS), rig.link.ops.map { it.code })
        }
    }

    @Test fun cacheHitRunsOnlyP6WithNoDiagnosticUsbAndResolvesProfileAgain() = runBlocking {
        val link = DriverTransport()
        val store = DriverCache().apply { seed(entry(link.warmCaps)) }
        withDriverRig(link = link, store = store) { rig ->
            assertEquals(ConnectOutcome.Connected, rig.driver.connect())
            val result = rig.published()
            assertEquals(123L, result.probedAt)
            assertEquals(0L, result.totalProbeMs)
            assertEquals("lumix-s1", result.profileId)
            assertEquals(Availability.Supported, result[Feature.ConfigWrite].availability)
            assertFalse(result[Feature.ConfigWrite].writable)
            assertEquals(CapabilityEvidence.STATIC_OPS, result[Feature.Thumbnail].evidence)
            assertEquals(CapabilityEvidence.PROFILE_ASSERTED, result[Feature.CapturePhoto].evidence)
            assertNull(rig.driver.status.modePos.value)
            assertEquals(1, store.reads.get())
            assertTrue(store.writes.isEmpty())
            assertEquals(listOf(Op.OPEN_SESSION, Op.GET_DEVICE_INFO, Op.GET_STORAGE_IDS), link.ops.map { it.code })
        }
    }

    @Test fun serialFirmwareModelAndSchemaEachParticipateInCacheKey() = runBlocking {
        val warm = DriverTransport().warmCaps
        val correct = key(warm)
        for (wrong in listOf(
            correct.copy(serial = "other-serial"), correct.copy(firmware = "other-firmware"),
            correct.copy(model = "other-model"), correct.copy(probeSchema = 2),
        )) {
            val store = DriverCache().apply { seed(entry(warm).copy(key = wrong)) }
            withDriverRig(store = store) { rig ->
                assertEquals(ConnectOutcome.Connected, rig.driver.connect())
                val result = rig.published()
                assertTrue(result.probedAt != 123L)
                assertTrue(rig.link.ops.any { it.code == PanasonicOp.GET_LIVEVIEW_FORMATS })
            }
        }
    }

    @Test fun registryReceivesWarmCapsAndPreservedUsbIdsRatherThanStalePreflightOrTargetIds() = runBlocking {
        val seen = CopyOnWriteArrayList<Triple<Int, Int, PtpDeviceCaps>>()
        val profile = object : CameraProfile by LumixS1Profile() {
            override val id = "registry-supplied-profile"
            override fun match(vid: Int, pid: Int, caps: PtpDeviceCaps): Boolean {
                seen += Triple(vid, pid, caps)
                return vid == 0x04DA && pid == 0x2382 && caps.model == "DC-S1"
            }
        }
        withDriverRig(registry = CameraProfileRegistry(listOf(profile, GenericPtpProfile()))) { rig ->
            assertEquals(ConnectOutcome.Connected, rig.driver.connect())
            assertEquals(profile.id, rig.published().profileId)
            assertEquals(profile.id, rig.driver.profileId)
            assertTrue(seen.isNotEmpty())
            assertTrue(seen.all { (vid, pid, caps) ->
                vid == 0x04DA && pid == 0x2382 && caps.serialNumber == rig.link.warmCaps.serialNumber &&
                    caps.deviceVersion == rig.link.warmCaps.deviceVersion && caps.model == rig.link.warmCaps.model
            })
        }
    }

    @Test fun snapshotCompletesBeforeAny94xxAndUnknownPtpBypassesRegistryAndEntireCache() = runBlocking {
        assumeTrue(BuildConfig.DEBUG)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val link = DriverTransport()
        val store = DriverCache().apply { seed(entry(link.warmCaps)) }
        val registryCalls = AtomicInteger()
        val profile = object : CameraProfile by LumixS1Profile() {
            override fun match(vid: Int, pid: Int, caps: PtpDeviceCaps): Boolean {
                registryCalls.incrementAndGet()
                return true
            }
        }
        withDriverRig(link, store, CameraProfileRegistry(listOf(profile)), snapshot = {
            entered.complete(Unit)
            release.await()
            TetherDebugConfig.Snapshot(enabled = true, capabilityOverride = CapabilityOverride.UnknownPtp)
        }) { rig ->
            try {
                assertEquals(ConnectOutcome.Connected, rig.driver.connect())
                withTimeout(5_000) { entered.await() }
                assertEquals(TetherPhase.Connected, rig.session.state.value.phase)
                assertEquals(0, store.reads.get())
                assertFalse(link.ops.any { it.code >= 0x9000 })
                release.complete(Unit)
                val result = rig.published()
                assertEquals("ptp-generic", result.profileId)
                assertEquals("ptp-generic", rig.driver.profileId)
                assertTrue(Feature.entries.all { result[it].availability == Availability.Unknown && !result[it].writable })
                assertTrue(Feature.entries.all { result[it].detail?.contains("controlled_simulation=true") == true })
                assertFalse(link.ops.any { it.code >= 0x9000 })
                assertEquals(0, registryCalls.get())
                assertEquals(0, store.reads.get())
                assertTrue(store.writes.isEmpty())
                assertNull(rig.driver.status.modePos.value)
            } finally { release.complete(Unit) }
        }
    }

    @Test fun everyDebugOverrideSkipsCacheAndAppliesOnlyToPublishedFacts() = runBlocking {
        assumeTrue(BuildConfig.DEBUG)
        for (override in listOf(CapabilityOverride.BrowseOnly, CapabilityOverride.NoConfig, CapabilityOverride.NoLiveView)) {
            val store = DriverCache()
            withDriverRig(store = store, snapshot = {
                TetherDebugConfig.Snapshot(enabled = true, capabilityOverride = override)
            }) { rig ->
                assertEquals(ConnectOutcome.Connected, rig.driver.connect())
                val result = rig.published()
                val suppressed = if (override == CapabilityOverride.NoConfig) Feature.ConfigRead else Feature.LiveView
                assertEquals(Availability.Unsupported, result[suppressed].availability)
                assertTrue(result[suppressed].detail!!.contains("override=" + override.name))
                assertEquals(Availability.Supported, result[Feature.ObjectBrowse].availability)
                assertEquals(0, store.reads.get())
                assertTrue(store.writes.isEmpty())
                assertTrue(rig.link.ops.any { it.code == PanasonicOp.GET_LIVEVIEW_FORMATS })
            }
        }
    }

    @Test fun blockedCacheReadCannotDelayConnectedAndCancelledScopeCannotPublishOrSave() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = DriverCache().apply {
            beforeRead = {
                entered.complete(Unit)
                // Simulates a non-cooperative storage read; the driver's explicit scope check is required.
                withContext(NonCancellable) { release.await() }
            }
        }
        withDriverRig(store = store) { rig ->
            try {
                assertEquals(ConnectOutcome.Connected, withTimeout(5_000) { rig.driver.connect() })
                withTimeout(5_000) { entered.await() }
                assertEquals(TetherPhase.Connected, rig.session.state.value.phase)
                assertEquals(0L, rig.driver.capabilities.value.probedAt)
                rig.scope.cancel()
                release.complete(Unit)
                withTimeout(5_000) { rig.scope.coroutineContext[Job]!!.join() }
                assertEquals(0L, rig.driver.capabilities.value.probedAt)
                assertNull(rig.driver.status.modePos.value)
                assertTrue(store.writes.isEmpty())
                assertFalse(rig.link.ops.any { it.code >= 0x9000 })
            } finally { release.complete(Unit) }
        }
    }

    @Test fun asyncSaveDoesNotDelayCapabilitiesAndCancelledScopePreventsItsWrite() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = DriverCache().apply {
            beforeWrite = { entered.complete(Unit); release.await() }
        }
        withDriverRig(store = store) { rig ->
            try {
                assertEquals(ConnectOutcome.Connected, rig.driver.connect())
                assertEquals(Availability.Supported, rig.published()[Feature.LiveView].availability)
                withTimeout(5_000) { entered.await() }
                assertEquals(TetherPhase.Connected, rig.session.state.value.phase)
                assertTrue(store.writes.isEmpty())
                rig.scope.cancel()
                release.complete(Unit)
                withTimeout(5_000) { rig.scope.coroutineContext[Job]!!.join() }
                assertTrue(store.writes.isEmpty())
            } finally { release.complete(Unit) }
        }
    }

    @Test fun forceStartsAsyncReprobeInvalidatesAndDoesNotAwaitItsResult() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val snapshots = AtomicInteger()
        val link = DriverTransport()
        val store = DriverCache().apply { seed(entry(link.warmCaps)) }
        withDriverRig(link = link, store = store, snapshot = {
            if (snapshots.incrementAndGet() > 1) { entered.complete(Unit); release.await() }
            TetherDebugConfig.Snapshot()
        }) { rig ->
            try {
                assertEquals(ConnectOutcome.Connected, rig.driver.connect())
                val cached = rig.published()
                assertEquals(123L, cached.probedAt)
                withTimeout(5_000) { rig.driver.ensureCapabilities(force = true) }
                withTimeout(5_000) { entered.await() }
                assertSame(cached, rig.driver.capabilities.value)
                release.complete(Unit)
                val refreshed = rig.published { it.probedAt != 0L && it.probedAt != 123L }
                assertEquals(5L, refreshed.probedModePos)
                withTimeout(5_000) { store.saved.await() }
                assertEquals("", store.writes.first())
                assertTrue(link.ops.any { it.code == PanasonicOp.GET_LIVEVIEW_FORMATS })
            } finally { release.complete(Unit) }
        }
    }

    @Test fun fatalReadStopsDriverProbeWithoutPublishingOrSavingPartialCapabilities() = runBlocking {
        val link = DriverTransport().apply {
            onRead = { op ->
                if (op.code == PanasonicOp.GET_LIVEVIEW_FORMATS) {
                    isFatal = true
                    throw PtpTimeoutException("scripted driver bulk timeout")
                }
                null
            }
        }
        withDriverRig(link = link) { rig ->
            assertEquals(ConnectOutcome.Connected, rig.driver.connect())
            withTimeout(5_000) { rig.session.state.first { it.phase == TetherPhase.Fatal } }
            assertEquals(0L, rig.driver.capabilities.value.probedAt)
            assertNull(rig.driver.status.modePos.value)
            assertTrue(rig.store.writes.isEmpty())
            assertFalse(link.ops.any { it.code == PanasonicOp.GET_PROP_VALUE || it.code == PanasonicOp.GET_PROP_DESC || it.code == Op.GET_OBJECT_HANDLES })
        }
    }

    private fun key(caps: PtpDeviceCaps) = CapabilityCacheKey(caps.model, caps.serialNumber, caps.deviceVersion, probeSchema = 1)
    private fun entry(caps: PtpDeviceCaps) = CapabilityCacheEntry(
        key(caps), "outdated-cached-profile", 123L,
        mapOf(Feature.ConfigWrite to CachedCapability(Availability.Supported, CapabilityEvidence.PTP_OK, "count=29")),
    )
}
