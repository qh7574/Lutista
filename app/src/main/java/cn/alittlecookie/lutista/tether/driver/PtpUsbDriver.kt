package cn.alittlecookie.lutista.tether.driver

import cn.alittlecookie.lutista.tether.debug.CapabilityOverride
import cn.alittlecookie.lutista.tether.debug.TetherDebugConfig
import cn.alittlecookie.lutista.tether.debug.TetherDiagLogger
import cn.alittlecookie.lutista.tether.probe.CapabilityProbe
import cn.alittlecookie.lutista.tether.probe.ProbeResult
import cn.alittlecookie.lutista.tether.config.CameraConfig
import cn.alittlecookie.lutista.tether.objects.CameraObjectSource
import cn.alittlecookie.lutista.tether.liveview.LiveViewSource
import cn.alittlecookie.lutista.tether.profile.CameraProfile
import cn.alittlecookie.lutista.tether.profile.Feature
import cn.alittlecookie.lutista.tether.protocol.Availability
import cn.alittlecookie.lutista.tether.session.CaptureController
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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

/** Controllers are created on demand only after this connection's profile/capability probe. */
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
    @Volatile private var resolvedProfile: CameraProfile? = null
    val profile: CameraProfile? get() = resolvedProfile
    @Volatile private var boundObjects: CameraObjectSource? = null
    override val objects: CameraObjectSource? get() = if (readyProfile() != null) boundObjects else null
    /** The object repository owns construction/session keys; driver only exposes the binding. */
    fun bindObjectSource(source: CameraObjectSource?) { boundObjects = source }
    /** Root seed only, never an assertion that all objects have been enumerated. */
    @Volatile var gallerySeed: ProbeResult? = null
        private set
    private val controllerLock = Any()
    @Volatile private var controllerDisposing = false
    private var liveViewController: LiveViewSource? = null
    private var configController: CameraConfig? = null
    private var captureController: CaptureController? = null
    private fun readyProfile(): CameraProfile? = resolvedProfile?.takeIf {
        !controllerDisposing && session.isOpen && !session.isFatal && capabilities.value.profileId == it.id
    }
    private fun usable(feature: Feature) = resolvedProfile?.allowsController(feature, capabilities.value) == true
    override val liveView: LiveViewSource? get() = synchronized(controllerLock) {
        val profile = readyProfile() ?: return@synchronized null
        if (!usable(Feature.LiveView)) return@synchronized null
        liveViewController ?: LiveViewSource(session, sessionScope, profile.quirks,
            onReconnectRequired = { logger.log("liveview", it) }).also { liveViewController = it }
    }
    override val config: CameraConfig? get() = synchronized(controllerLock) {
        val profile = readyProfile() ?: return@synchronized null
        if (profile.propertyCatalogSeed.isEmpty() || !usable(Feature.ConfigRead)) return@synchronized null
        configController ?: CameraConfig(session, profile, onModeObserved = { mutableModePos.value = it }) {
            liveViewController?.discardNextFrames()
        }.also { configController = it }
    }
    override val capture: CaptureController? get() = synchronized(controllerLock) {
        val profile = readyProfile() ?: return@synchronized null
        if (!(profile.capturePhotoSupported && usable(Feature.CapturePhoto)) &&
            !(profile.captureMovieSupported && usable(Feature.CaptureMovie))) return@synchronized null
        captureController ?: CaptureController(session, status, profile, sessionScope,
            onModeObserved = { mutableModePos.value = it }).also { captureController = it }
    }
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
            if (force || resolvedProfile?.id != profile.id) disposeControllers(restore = true)
            resolvedProfile = profile
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
            val result = if (cached != null) ProbeResult(probe.supplementP6(cached, warmCaps, profile), gallerySeed?.gallerySeed)
                else probe.probe(warmCaps, profile, current.storageIds.value)
            gallerySeed = result
            val observed = result.capabilities
            val published = override.apply(observed)
            checkActive()
            resolvedProfileId = published.profileId
            mutableModePos.value = published.probedModePos
            current.publishCapabilities(published)
            controllerDisposing = false
            if (useCache && cached == null) {
                sessionScope.launch {
                    checkActive()
                    // ModePos is a transient observation, never a cache fact or key component.
                    cache.save(observed.copy(probedModePos = null))
                }
            }
        }
    }

    override suspend fun connect(): ConnectOutcome {
        prepareConnection()
        return session.connect()
    }

    /** Reusing a driver must not reuse a previous USB attachment's permission snapshot. */
    internal suspend fun connect(target: UsbTarget): ConnectOutcome {
        prepareConnection()
        return session.connect(target)
    }
    override suspend fun disconnect(reason: CloseReason) = withContext(NonCancellable) {
        try {
            disposeControllers(restore = reason == CloseReason.USER || reason == CloseReason.ACTIVITY_DESTROYED || reason == CloseReason.RECONNECT)
        } finally {
            boundObjects = null
            gallerySeed = null
            resolvedProfile = null
            mutableModePos.value = null
            session.close(reason)
        }
    }

    private suspend fun prepareConnection() {
        if (session.isOpen) return
        disposeControllers(restore = false)
        gallerySeed = null
        boundObjects = null
        resolvedProfile = null
        resolvedProfileId = ""
        mutableModePos.value = null
    }

    private suspend fun disposeControllers(restore: Boolean) {
        val previous = synchronized(controllerLock) {
            controllerDisposing = true
            Triple(liveViewController, configController, captureController).also {
                liveViewController = null; configController = null; captureController = null
            }
        }
        if (restore && session.isOpen && !session.isFatal && previous.third?.exitRequested == false) {
            try { previous.third?.finishRecording() }
            catch (failure: Throwable) { logger.log("capture", "stop: ${failure.message}") }
        }
        previous.third?.close()
        try {
            if (restore && session.isOpen && !session.isFatal) previous.first?.stop() else previous.first?.abandon()
        } catch (failure: Throwable) { logger.log("liveview", "stop: ${failure.message}") }
        if (restore && !session.isFatal && session.isOpen) {
            try { previous.second?.restorePhotoStyle() }
            catch (failure: Throwable) { logger.log("config", "restore: ${failure.message}") }
        }
    }

    /** Starts the session's asynchronous refresh; returning does not await new capabilities. */
    override suspend fun ensureCapabilities(force: Boolean) = session.reprobe()

    private suspend fun checkActive() {
        currentCoroutineContext().ensureActive()
        sessionScope.ensureActive()
    }
}

/** Unknown is tryable, but never grants a protocol absent from the resolved profile. */
internal fun CameraProfile.allowsController(feature: Feature, caps: CameraCapabilities): Boolean {
    if (caps[feature].availability == Availability.Unsupported) return false
    return when (feature) {
        Feature.LiveView -> probePlan.any { 0x9414 in it.ops }
        Feature.ConfigRead, Feature.ConfigWrite -> propertyCatalogSeed.isNotEmpty() && quirks.supportsPropertyDescriptor
        Feature.CapturePhoto -> capturePhotoSupported
        Feature.CaptureMovie -> captureMovieSupported && quirks.captureMovieIsToggleInVideoMode
        else -> false
    }
}
