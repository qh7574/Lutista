package cn.alittlecookie.lutista.tether.driver

import cn.alittlecookie.lutista.tether.debug.CapabilityOverride
import cn.alittlecookie.lutista.tether.debug.TetherDebugConfig
import cn.alittlecookie.lutista.tether.debug.TetherDiagLogger
import cn.alittlecookie.lutista.tether.probe.CapabilityProbe
import cn.alittlecookie.lutista.tether.profile.CameraCapabilities
import cn.alittlecookie.lutista.tether.profile.CameraProfileRegistry
import cn.alittlecookie.lutista.tether.profile.profiles.GenericPtpProfile
import cn.alittlecookie.lutista.tether.session.CameraSession
import cn.alittlecookie.lutista.tether.store.CapabilityCache
import cn.alittlecookie.lutista.tether.store.CapabilityCacheKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** PTP connection and capabilities only; later delivery batches supply controllers. */
class PtpUsbDriver internal constructor(
    val session: CameraSession,
    private val sessionScope: CoroutineScope,
    private val registry: CameraProfileRegistry,
    private val cache: CapabilityCache,
    private val debugConfig: TetherDebugConfig,
    private val logger: TetherDiagLogger,
) : CameraDriver {
    @Volatile private var resolvedProfileId = ""
    override val profileId: String get() = resolvedProfileId
    override val capabilities: StateFlow<CameraCapabilities> = session.caps
    private val mutableBattery = MutableStateFlow<Int?>(null)
    private val mutableModePos = MutableStateFlow<Long?>(null)
    override val status: CameraStatusSource = object : CameraStatusSource {
        override val battery: StateFlow<Int?> = mutableBattery.asStateFlow()
        override val modePos: StateFlow<Long?> = mutableModePos.asStateFlow()
    }

    init {
        session.setCapabilityWork { current, warmCaps, force ->
            checkActive()
            val snapshot = debugConfig.snapshot()
            checkActive()
            val override = snapshot.capabilityOverride
            // Resolve before constructing/running a plan: UnknownPtp must never issue 94xx.
            val profile = if (override == CapabilityOverride.UnknownPtp) GenericPtpProfile()
                else registry.resolve(warmCaps.usbVendorId, warmCaps.usbProductId, warmCaps)
            checkActive()
            resolvedProfileId = profile.id
            val useCache = override == CapabilityOverride.None
            if (useCache && force) {
                cache.invalidate()
                checkActive()
            }
            val probe = CapabilityProbe(
                execute = current::readDiagnostic,
                isFatal = { current.isFatal },
                log = { logger.log("probe", it) },
            )
            val key = CapabilityCacheKey(
                warmCaps.model, warmCaps.serialNumber, warmCaps.deviceVersion,
                probeSchema = CapabilityCache.PROBE_SCHEMA,
            )
            val cached = if (useCache && !force) cache.load(key) else null
            checkActive()
            val observed = if (cached != null) probe.supplementP6(cached, warmCaps, profile)
                else probe.probe(warmCaps, profile, current.storageIds.value).capabilities
            val published = override.apply(observed)
            checkActive()
            resolvedProfileId = published.profileId
            mutableModePos.value = published.probedModePos
            current.publishCapabilities(published)
            if (useCache && cached == null) {
                sessionScope.launch {
                    checkActive()
                    // ModePos is a transient observation, never a cache fact or key component.
                    cache.save(observed.copy(probedModePos = null))
                }
            }
        }
    }

    override suspend fun connect(): ConnectOutcome = session.connect()

    /** Reusing a driver must not reuse a previous USB attachment's permission snapshot. */
    internal suspend fun connect(target: UsbTarget): ConnectOutcome = session.connect(target)
    override suspend fun disconnect(reason: CloseReason) = session.close(reason)

    /** Starts the session's asynchronous refresh; returning does not await new capabilities. */
    override suspend fun ensureCapabilities(force: Boolean) = session.reprobe()

    private suspend fun checkActive() {
        currentCoroutineContext().ensureActive()
        sessionScope.ensureActive()
    }
}
