package cn.alittlecookie.lutista.tether.repository

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import cn.alittlecookie.lutista.data.database.dao.CameraObjectDao
import cn.alittlecookie.lutista.data.database.entity.CameraObjectEntity
import cn.alittlecookie.lutista.data.database.entity.toEntity
import cn.alittlecookie.lutista.data.model.FilterConfig
import cn.alittlecookie.lutista.data.repository.FilterRepository
import cn.alittlecookie.lutista.domain.engine.ImageProcessingEngine
import cn.alittlecookie.lutista.manager.SettingsManager
import cn.alittlecookie.lutista.tether.config.*
import cn.alittlecookie.lutista.tether.driver.*
import cn.alittlecookie.lutista.tether.liveview.LiveViewPhase
import cn.alittlecookie.lutista.tether.objects.*
import cn.alittlecookie.lutista.tether.profile.Feature
import cn.alittlecookie.lutista.tether.protocol.Availability
import cn.alittlecookie.lutista.tether.render.*
import cn.alittlecookie.lutista.tether.service.TetherService
import cn.alittlecookie.lutista.tether.session.*
import cn.alittlecookie.lutista.tether.transfer.*
import cn.alittlecookie.lutista.tether.ui.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/** Process owner of the formal USB session. Recomposition/rotation never creates a transport. */
class TetherRepository(
    private val context: Context,
    private val runtime: TetherRuntime,
    private val settings: SettingsManager,
    private val objects: CameraObjectDao,
    private val transfers: TransferEngine,
    private val engine: ImageProcessingEngine,
    filters: FilterRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lifecycle = Mutex()
    private val liveViewLock = Mutex()
    private val mutableUi = MutableStateFlow(TetherUiState())
    val uiState: StateFlow<TetherUiState> = mutableUi.asStateFlow()
    private val mutablePreviewNotices = MutableSharedFlow<PreviewRenderNotice>(extraBufferCapacity = 1)
    val previewNotices: SharedFlow<PreviewRenderNotice> = mutablePreviewNotices.asSharedFlow()
    val enabled: StateFlow<Boolean> = settings.tetherEnabled
    private var enableJob: Job? = null
    private val serviceLease = ForegroundServiceLease()
    private val eventBarrier = SessionEventBarrier<PtpUsbDriver, CameraObjectSource>()
    private val eventJobs: MutableSet<Job> = ConcurrentHashMap.newKeySet()
    private var opening = false
    private var closing = false
    private var commandEpoch = 0L
    private var sessionJob: Job? = null
    private var listingJob: Job? = null
    private var galleryJob: Job? = null
    private var reconciliationJob: Job? = null
    private var autoReconciler: AutoTransferReconciler? = null
    private var controllerJobs = mutableListOf<Job>()
    private var liveJobs = mutableListOf<Job>()
    private var source: CameraObjectSource? = null
    private var activeDriver: PtpUsbDriver? = null
    private var pipeline: LatestLiveViewRenderer? = null
    private val liveImage = LiveViewImageBinding { frame -> pipeline?.onFramePresented(frame) }
    private val sessionEpoch = AtomicLong()
    private val pageLimit = MutableStateFlow(100)
    private val galleryFilter = MutableStateFlow<Pair<Long?, Long?>>(null to null)
    private var visibleEntities = emptyList<CameraObjectEntity>()
    private var previewEntity: CameraObjectEntity? = null
    private var currentFilter: FilterConfig? = null
    private var previewRenderingFailed = false
    private var originalStyle: Long? = null
    private val mediaProgress = mutableMapOf<Long, TetherTransferItemUi>()
    private val originalPaths = mutableMapOf<Long, String>()
    private val localOriginalPaths = mutableMapOf<Long, String>()
    private data class QueuedObjectEvent(
        val registration: SessionEventBarrier.Registration<PtpUsbDriver, CameraObjectSource>,
        val event: EventPump.ObjectAdded,
    )
    private val eventQueue = Channel<QueuedObjectEvent>(64)
    private val loader = buildTetherImageLoader(context) { source }
    private var lastPreviewRequest = Long.MIN_VALUE

    init {
        // Registration precedes the first suspension: baseline sees even a backpressured event.
        runtime.setObjectEventConsumer { driver, event ->
            if (runtime.driver.value === driver && driver.session.eventPump.isCurrent(event)) {
                eventBarrier.register(driver, event.generation, event.handle)?.let { registration ->
                    eventQueue.send(QueuedObjectEvent(registration, event))
                }
            }
        }
        // Bounded consumers keep a failed handle from monopolizing the entire event inbox.
        repeat(4) { scope.launch {
            for (queued in eventQueue) {
                val work = scope.async(start = CoroutineStart.LAZY) { processObjectEvent(queued) }
                eventJobs.add(work)
                try { work.await() }
                catch (cancel: CancellationException) { currentCoroutineContext().ensureActive() }
                finally { eventJobs.remove(work) }
            }
        } }
        scope.launch {
            runtime.state.collect { state ->
                update { it.copy(connected = state.phase == TetherPhase.Connected,
                    busy = state.phase in setOf(TetherPhase.Connecting, TetherPhase.Recovering, TetherPhase.PermissionNeeded),
                    connectionSummary = state.message, cameraName = state.model.takeIf(String::isNotBlank) ?: it.cameraName) }
                if (state.phase == TetherPhase.Connected && enabled.value && !closing) initializeSession()
                else if (!opening && !closing && enabled.value && state.phase in setOf(
                        TetherPhase.Detached, TetherPhase.Fatal, TetherPhase.Idle,
                        TetherPhase.NotAttached, TetherPhase.WrongMode)) {
                    val window = eventBarrier.current()
                    sessionJob?.cancel()
                    scope.launch { lifecycle.withLock {
                        if (window != null && eventBarrier.isCurrent(window) && runtime.state.value.phase == state.phase) {
                            val reason = if (state.phase == TetherPhase.Detached) CloseReason.DETACHED else CloseReason.FATAL
                            closeSessionLocked(reason, finishRecording = false, summary = state.message.ifBlank { "连接已中断" })
                            if (state.phase == TetherPhase.Fatal) update { it.copy(dialog = TetherDialogUi("连接已中断", "USB 传输发生错误，请重新连接相机。")) }
                        }
                    } }
                }
            }
        }
        scope.launch { runtime.caps.collect { updateCapabilities() } }
        scope.launch { runtime.connectionNotice.collect { notice ->
            if (notice != null) update { it.copy(dialog = TetherDialogUi(notice.title, notice.message,
                when (notice) {
                    TetherConnectionNotice.NoUsableUsb -> TetherConnectionGuide.NoUsb
                    TetherConnectionNotice.MtpUsbMode -> TetherConnectionGuide.MtpDescriptor
                    TetherConnectionNotice.WrongPtpMode -> TetherConnectionGuide.WrongMode
                })) }
        } }
        scope.launch { settings.useBlur.collect { value -> update { it.copy(useBlur = value) } } }
        scope.launch { settings.monitorPath.collect { path ->
            update { it.copy(gallery = it.gallery.copy(destination = path.ifBlank { "请先在主页设置监控文件夹" }),
                liveView = it.liveView.copy(autoDownloadDestination = path)) }
        } }
        scope.launch { settings.tetherAutoTransfer.collect { value -> updateLive { it.copy(autoDownload = value) } } }
        scope.launch { settings.tetherLiveViewFilterEnabled.distinctUntilChanged().collect { value ->
            val effective = value && !previewRenderingFailed
            pipeline?.setFilterEnabled(effective)
            updateLive { it.copy(filterEnabled = effective) }
        } }
        scope.launch { settings.tetherLiveViewFilterFps.distinctUntilChanged().collect { value -> pipeline?.setTargetFps(value); updateLive { it.copy(targetFps = value) } } }
        scope.launch { LiveViewFilterSource(settings, filters).filters(flowOf(true)).collect { filter ->
            currentFilter = filter
            updateLive { it.copy(filterAvailable = filter != null, globalFilterName = filter?.name,
                filterUnavailableReason = if (filter == null) "请先选择有效的全局滤镜" else null) }
        } }
        scope.launch { combine(transfers.transfers, transfers.progress, transfers.batches) { items, progress, batches ->
            Triple(items.values.toList(), progress, batches)
        }.collect { (items, progress, batches) -> applyTransferProgress(items, progress, batches) }
        }
    }

    fun requestEnable() {
        if (enableJob?.isActive == true) return
        val command = ++commandEpoch
        enableJob = scope.launch {
            lifecycle.withLock {
                if (command != commandEpoch) return@withLock
                if (uiState.value.connected && enabled.value && source != null && !closing &&
                    serviceLease.current()?.let { serviceLease.accepts(it.token) } == true) return@withLock
                opening = true
                try {
                    if (source != null || eventBarrier.current() != null ||
                        serviceLease.current()?.stopRequested == false) {
                        closeSessionLocked(CloseReason.USER, finishRecording = true)
                    }
                    // stopService is asynchronous. A new start must wait for the old onDestroy.
                    serviceLease.current()?.let { old ->
                        check(old.stopRequested) { "前台服务仍在运行，请先关闭联机模式" }
                        withTimeout(5_000) { old.stopped.await() }
                    }
                    settings.setTetherEnabled(true)
                    eventBarrier.begin()
                    sessionEpoch.incrementAndGet()
                    update { it.copy(enabled = true, busy = true, dialog = null, connectionSummary = "正在检查 USB 权限…") }
                    val device = runtime.hub.enumerate().firstOrNull { !it.isVerifiedLumixMtpMode() && it.hasTetherBulkInterface() }
                    if (device == null) {
                        runtime.connect() // Centralized MTP/no-device guide; opens no endpoint.
                        closeSessionLocked(CloseReason.USER, finishRecording = false, summary = runtime.state.value.message)
                        return@withLock
                    }
                    if (!runtime.hub.hasPermission(device) && !runtime.hub.requestPermission(device)) {
                        error("USB 权限未授予，请重新开启联机模式并允许访问相机")
                    }
                    val lease = serviceLease.begin()
                    try {
                        ContextCompat.startForegroundService(context, Intent(context, TetherService::class.java)
                            .putExtra(TetherService.EXTRA_TOKEN, lease.token))
                    } catch (failure: Throwable) {
                        serviceLease.startRejected(lease.token, failure)
                        throw failure
                    }
                    withTimeout(5_000) { lease.ready.await() }
                    when (val result = runtime.connect()) {
                        ConnectOutcome.Connected -> Unit
                        ConnectOutcome.AlreadyConnecting -> error("相机连接正在进行，请关闭后重试")
                        is ConnectOutcome.Failed -> throw result.cause
                        else -> closeSessionLocked(CloseReason.USER, finishRecording = false, summary = runtime.state.value.message)
                    }
                } catch (timeout: TimeoutCancellationException) {
                    closeSessionLocked(CloseReason.USER, finishRecording = false)
                    showError("联机开启超时", timeout)
                } catch (cancel: CancellationException) {
                    closeSessionLocked(CloseReason.USER, finishRecording = false)
                    throw cancel
                } catch (failure: Throwable) {
                    closeSessionLocked(CloseReason.USER, finishRecording = false)
                    showError("无法开启联机模式", failure)
                } finally { opening = false }
            }
        }
    }

    fun requestDisable() {
        val command = ++commandEpoch
        enableJob?.cancel()
        sessionJob?.cancel()
        scope.launch { lifecycle.withLock {
            if (command == commandEpoch) closeSessionLocked(CloseReason.USER, finishRecording = true)
        } }
    }

    fun onUsbAttached() { if (enabled.value && !closing && !opening) requestEnable() }
    fun pendingForegroundToken(): Long? = serviceLease.current()?.token
    fun acceptsForegroundToken(token: Long): Boolean = serviceLease.accepts(token)
    fun onForegroundStarted(token: Long): Boolean = serviceLease.started(token)
    fun onForegroundFailed(token: Long, error: Throwable) {
        if (serviceLease.failed(token, error)) {
            showError("前台服务启动失败", error)
            requestDisable()
        }
    }
    fun onForegroundDestroyed(token: Long) {
        if (serviceLease.destroyed(token) && enabled.value) requestDisable()
    }
    fun requestDisableFromService(token: Long) {
        if (serviceLease.accepts(token)) requestDisable()
    }

    private suspend fun processObjectEvent(queued: QueuedObjectEvent) {
        val registered = queued.registration
        val driver = registered.driver
        val current = registered.window.ready.await()
        var failures = 0
        while (eventBarrier.isCurrent(registered) && source === current &&
            driver.session.eventPump.isCurrent(queued.event) && !driver.session.isFatal) {
            try {
                transfers.onObjectAdded(current.sessionKey, queued.event.handle)
                currentCoroutineContext().ensureActive()
                if (!eventBarrier.isCurrent(registered) || source !== current ||
                    !driver.session.eventPump.isCurrent(queued.event)) return
                val info = current.getObjectInfo(queued.event.handle)
                currentCoroutineContext().ensureActive()
                if (!eventBarrier.isCurrent(registered) || source !== current) return
                objects.upsertAll(listOf(info.toEntity(current.sessionKey, System.currentTimeMillis())))
                currentCoroutineContext().ensureActive()
                if (eventBarrier.isCurrent(registered) && source === current) {
                    // Eligible AUTO is durable before this point; policy ignores are intentional.
                    driver.session.eventPump.markProcessed(queued.event)
                }
                return
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Throwable) {
                if (!eventBarrier.isCurrent(registered) || driver.session.isFatal) return
                failures++
                showError("新增文件读取失败，正在重试", failure)
                delay((failures.coerceAtMost(5) * 1_000L))
            }
        }
    }

    private fun initializeSession() {
        if (sessionJob?.isActive == true || source != null || closing) return
        val driver = runtime.driver.value ?: return
        val window = eventBarrier.current() ?: return
        sessionJob = scope.launch {
            lifecycle.withLock {
                try {
                    // Connected precedes the asynchronous profile probe. Reuse only its root seed.
                    withTimeout(10_000) { driver.capabilities.first { it.profileId.isNotBlank() } }
                    if (!enabled.value || !eventBarrier.isCurrent(window) || runtime.driver.value !== driver ||
                        runtime.state.value.phase != TetherPhase.Connected) return@withLock
                    val profile = driver.profile ?: error("机型配置尚未就绪")
                    val device = driver.session.deviceCaps.value ?: error("相机信息尚未就绪")
                    val key = CameraSessionKey(device.serialNumber.ifBlank { "${device.manufacturer}:${device.model}" }, System.currentTimeMillis())
                    val current = PtpObjectSource(driver.session, profile.quirks, key, driver.gallerySeed?.gallerySeed)
                    transfers.startSession(current)
                    runCatching { transfers.sweepOrphanedStaging() }
                    currentCoroutineContext().ensureActive()
                    check(eventBarrier.isCurrent(window)) { "相机会话已更换" }
                    activeDriver = driver
                    source = current
                    autoReconciler = AutoTransferReconciler(key)
                    driver.bindObjectSource(current)
                    mediaProgress.clear(); originalPaths.clear(); localOriginalPaths.clear(); originalStyle = null
                    check(eventBarrier.publish(window, driver, current)) { "相机会话已关闭" }
                    settings.setTetherLastCameraModel(device.model)
                    update { it.copy(cameraName = device.model, cameraDetails = "${device.manufacturer} · ${device.deviceVersion}",
                        gallery = TetherGalleryUi(destination = it.gallery.destination), parameters = TetherParametersUi(),
                        preview = TetherPreviewUi(), transfer = TetherTransferUi()) }
                    updateCapabilities()
                    observeGallery(current)
                    bindControllers(driver)
                    enumerate(current)
                    startAutoReconciliation(current, driver)
                } catch (timeout: TimeoutCancellationException) {
                    closeSessionLocked(CloseReason.USER, finishRecording = false)
                    showError("相机初始化超时", timeout)
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: Throwable) {
                    closeSessionLocked(CloseReason.USER, finishRecording = false)
                    showError("相机初始化失败", failure)
                }
            }
        }
    }

    private fun observeGallery(current: CameraObjectSource) {
        galleryJob?.cancel()
        galleryJob = scope.launch {
            launch { combine(pageLimit, galleryFilter) { limit, filter -> Triple(limit, filter.first, filter.second) }
                .flatMapLatest { (limit, storage, parent) -> objects.observeFilteredPage(current.sessionKey.id, storage, parent, limit + 1) }
                .collect { rows ->
                    val limit = pageLimit.value
                    visibleEntities = rows.take(limit)
                    update { it.copy(gallery = it.gallery.copy(items = visibleEntities.map(::media), hasMore = rows.size > limit, loadingMore = false)) }
                }
            }
            launch { objects.observeDirectories(current.sessionKey.id).collect { rows ->
                update { it.copy(gallery = it.gallery.copy(directories = rows.map { row -> TetherDirectoryUi(row.handle, row.name, row.storageId) })) }
            } }
            launch { objects.observeStorageCounts(current.sessionKey.id).collect { counts ->
                update { it.copy(gallery = it.gallery.copy(storages = counts.mapIndexed { i, c -> TetherStorageUi(c.storageId, "存储卡 ${i + 1}", c.count) })) }
            } }
        }
    }

    private fun enumerate(current: CameraObjectSource, background: Boolean = false) {
        if (listingJob?.isActive == true || source !== current || closing) return
        val window = eventBarrier.current() ?: return
        listingJob = scope.launch {
            val baseline = mutableListOf<CameraObject>()
            if (!background) updateGallery { it.copy(enumerating = true, enumeratedCount = 0, error = null, directoryWarning = null) }
            try {
                current.listTree().collect { page -> when (page) {
                    is ObjectPage.Batch -> {
                        ensureActive()
                        if (source !== current || !eventBarrier.isCurrent(window)) return@collect
                        objects.upsertAll(page.items.map { it.toEntity(current.sessionKey, System.currentTimeMillis()) })
                        ensureActive()
                        if (source !== current || !eventBarrier.isCurrent(window)) return@collect
                        baseline += page.items
                        if (!background) updateGallery { it.copy(enumeratedCount = baseline.count { item -> !item.isDir }) }
                    }
                    is ObjectPage.Complete -> {
                        if (source !== current || !eventBarrier.isCurrent(window)) return@collect
                        // Admit new JPEGs before the baseline pass records them as pre-existing,
                        // otherwise the AUTO admission would be rejected by its own BASELINE row.
                        autoReconciler?.reconcile(baseline, page.unavailableDirectories,
                            settings.tetherAutoTransfer.first()) { added ->
                            if (source !== current || !eventBarrier.isCurrent(window)) throw CancellationException("Stale reconciliation")
                            transfers.onObjectAdded(current.sessionKey, added.handle)
                            Timber.i("New JPEG admitted by directory reconciliation; handle=0x${added.handle.toString(16)}")
                        }
                        transfers.completeBaseline(current.sessionKey, baseline, window.observedHandles)
                        ensureActive()
                        if (source === current && eventBarrier.isCurrent(window)) {
                            val skipped = page.unavailableDirectories
                            val warning = skipped.takeIf { it.isNotEmpty() }?.let {
                                val names = it.map { entry -> entry.directory.name }.distinct().take(3).joinToString("、")
                                "相机未允许读取 ${it.size} 个目录（$names），已继续加载其他目录。"
                            }
                            updateGallery { it.copy(enumerating = false, totalCount = page.total, directoryWarning = warning) }
                        }
                    }
                } }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Throwable) { updateGallery { it.copy(enumerating = false, error = failure.message ?: "相册读取失败") } }
            finally { baseline.clear() }
        }
    }

    /** C108 remains primary; serialized complete snapshots compensate for a camera omitting it. */
    private fun startAutoReconciliation(current: CameraObjectSource, driver: PtpUsbDriver) {
        reconciliationJob?.cancel()
        reconciliationJob = scope.launch {
            while (isActive && source === current && !driver.session.isFatal) {
                listingJob?.join()
                delay(5_000)
                if (source !== current || closing || driver.session.isFatal) break
                if (transfers.progress.value.queued > 0 || uiState.value.liveView.isRecording) continue
                enumerate(current, background = true)
            }
        }
    }

    private fun bindControllers(driver: PtpUsbDriver) {
        controllerJobs.forEach(Job::cancel); controllerJobs.clear()
        driver.config?.let { config ->
            controllerJobs += scope.launch { config.catalog.filterNotNull().collect { snapshot ->
                if (originalStyle == null) originalStyle = snapshot.properties[CameraProperty.PHOTO_STYLE]?.value
                update { it.copy(parameters = mapParameters(snapshot)) }
            } }
            // Poll real mode/descriptors at a modest rate; no property scans or guessed writes.
            controllerJobs += scope.launch {
                while (isActive && activeDriver === driver) {
                    try { config.refreshCatalog() }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Throwable) { if (driver.session.isFatal) return@launch }
                    delay(2_000)
                }
            }
        }
        driver.capture?.let { capture -> controllerJobs += scope.launch { capture.ui.collect { value ->
            updateLive { it.copy(captureBusy = value.busy, isRecording = value.recording,
                error = value.lastError ?: it.error) }
        } } }
        controllerJobs += scope.launch { driver.status.battery.collect { value -> update { it.copy(batteryPercent = value) } } }
        controllerJobs += scope.launch { driver.status.modePos.collect { value ->
            updateLive { it.copy(isVideoMode = value == driver.profile?.quirks?.videoModePos) }
        } }
        controllerJobs += scope.launch {
            var start: Long? = null
            while (isActive) {
                if (uiState.value.liveView.isRecording) {
                    if (start == null) start = android.os.SystemClock.elapsedRealtime()
                    updateLive { it.copy(recordingElapsedMillis = android.os.SystemClock.elapsedRealtime() - start!!) }
                } else { start = null; updateLive { it.copy(recordingElapsedMillis = 0) } }
                delay(250)
            }
        }
    }

    private fun updateCapabilities() {
        val caps = runtime.caps.value
        fun can(feature: Feature) = runtime.state.value.phase == TetherPhase.Connected && caps[feature].availability != Availability.Unsupported
        update { it.copy(capabilities = TetherCapabilities(
            canBrowse = can(Feature.ObjectBrowse), canDownload = can(Feature.ObjectDownload),
            canLiveView = can(Feature.LiveView), canSwitchLiveViewFormat = can(Feature.LiveViewFormatSwitch),
            canCapture = can(Feature.CapturePhoto), canRecord = can(Feature.CaptureMovie),
            canReadParams = can(Feature.ConfigRead), canWriteParams = can(Feature.ConfigWrite),
            canSetPhotoStyle = can(Feature.PhotoStyle), canFilterPreview = can(Feature.LiveViewFilterPreview),
            explanations = caps.unsupported.map { feature -> "${feature.uiName}：相机不支持" }),
            liveView = it.liveView.copy(autoDownloadAvailable = can(Feature.ObjectDownload),
                autoDownloadReason = if (!can(Feature.ObjectDownload)) "相机未提供文件下载" else null)) }
    }

    fun onAction(action: TetherUiAction) {
        when (action) {
            is TetherUiAction.SetEnabled -> if (action.enabled) requestEnable() else requestDisable()
            TetherUiAction.DismissDialog -> { runtime.dismissConnectionNotice(); update { it.copy(dialog = null) } }
            TetherUiAction.OpenGallery -> update { it.copy(page = TetherPage.Gallery) }
            TetherUiAction.OpenLiveView, TetherUiAction.RetryLiveView -> launchAction { startLiveView() }
            is TetherUiAction.OpenPreview -> if (lastPreviewRequest != action.requestId) { lastPreviewRequest = action.requestId; launchAction { showPreview(action.handle) } }
            is TetherUiAction.ShowPreview -> launchAction { showPreview(action.handle) }
            TetherUiAction.RefreshGallery -> source?.let(::enumerate)
            TetherUiAction.LoadMoreGallery -> { updateGallery { it.copy(loadingMore = true) }; pageLimit.value += 100 }
            is TetherUiAction.SelectStorage -> { pageLimit.value = 100; galleryFilter.value = action.storageId to null
                updateGallery { it.copy(selectedStorageId = action.storageId, selectedDirectoryHandle = null, selectedHandles = emptySet()) } }
            is TetherUiAction.SelectDirectory -> { pageLimit.value = 100; galleryFilter.value = galleryFilter.value.first to action.directoryHandle
                updateGallery { it.copy(selectedDirectoryHandle = action.directoryHandle, selectedHandles = emptySet()) } }
            is TetherUiAction.SetSelectionMode -> updateGallery { it.copy(selectionMode = action.enabled, selectedHandles = emptySet()) }
            is TetherUiAction.ToggleSelection -> updateGallery { it.copy(selectionMode = true, selectedHandles = if (action.handle in it.selectedHandles) it.selectedHandles - action.handle else it.selectedHandles + action.handle) }
            TetherUiAction.SelectAll -> updateGallery { it.copy(selectionMode = true, selectedHandles = it.items.mapTo(linkedSetOf()) { item -> item.handle }) }
            TetherUiAction.DismissClockWarning -> updateGallery { it.copy(clockWarning = null) }
            is TetherUiAction.Download -> launchAction { download(action.handles) }
            TetherUiAction.CancelTransfer -> launchAction { transfers.cancelForeground() }
            TetherUiAction.RetryFailedTransfers -> launchAction { download(mediaProgress.values.filter { it.error != null }.mapTo(linkedSetOf()) { it.handle }) }
            is TetherUiAction.SetParameter -> launchAction { writeProperty(parameterId(action.key), action.value) }
            is TetherUiAction.SetPhotoStyle -> launchAction { writeProperty(CameraProperty.PHOTO_STYLE, action.value) }
            TetherUiAction.RestorePhotoStyle -> launchAction { reportWrite(activeDriver?.config?.restorePhotoStyle()) }
            is TetherUiAction.SetLiveViewFormat -> launchAction { liveViewLock.withLock {
                uiState.value.liveViewFormatUnavailableReason()?.let { error(it) }
                val camera = activeDriver?.liveView ?: error("实时取景不可用")
                val applied = camera.setFormat(action.index)
                val actualIndex = camera.formats.value.indexOf(applied)
                check(actualIndex >= 0) { "相机未返回实际使用的预览分辨率" }
                settings.setTetherLiveViewFormatIndex(actualIndex)
                updateLive { it.copy(selectedFormatIndex = actualIndex) }
            } }
            is TetherUiAction.SetTargetFps -> launchAction { require(action.fps > 0); settings.setTetherLiveViewFilterFps(action.fps) }
            is TetherUiAction.SetPreviewFilter -> launchAction {
                if (action.enabled && previewRenderingFailed) pipeline?.setFilterEnabled(false)
                previewRenderingFailed = false
                pipeline?.setFilterEnabled(action.enabled)
                updateLive { it.copy(filterEnabled = action.enabled) }
                settings.setTetherLiveViewFilterEnabled(action.enabled)
            }
            is TetherUiAction.SetAutoDownload -> launchAction {
                if (action.enabled) check(settings.monitorPath.first().isNotBlank()) {
                    "请先在主页面设置监控文件夹，再接收相机照片"
                }
                settings.setTetherAutoTransfer(action.enabled)
            }
            TetherUiAction.Capture, TetherUiAction.ToggleRecording -> launchAction { pressCapture() }
            is TetherUiAction.PlayVideo -> launchAction { showPreview(action.handle); loadVideo(action.handle) }
            TetherUiAction.ToggleVideoPlayback -> update { it.copy(preview = it.preview.copy(videoPlaying = !it.preview.videoPlaying)) }
            is TetherUiAction.SeekVideo -> update { it.copy(preview = it.preview.copy(videoPositionMillis = action.positionMillis.coerceAtLeast(0))) }
            is TetherUiAction.ExitLiveView -> launchAction { leaveLiveView(action.destination, action.restoreOriginalStyle) }
            is TetherUiAction.StopRecordingAndExit -> launchAction { leaveLiveView(action.destination, action.restoreOriginalStyle) }
            // Android directory picker/global settings navigation belong to the Route.
            TetherUiAction.ChooseDownloadDirectory, TetherUiAction.OpenGlobalFilterSettings -> Unit
        }
    }

    private suspend fun startLiveView() = liveViewLock.withLock {
        check(enabled.value && uiState.value.connected && !closing) { "请先连接相机" }
        val driver = activeDriver ?: error("相机会话尚未就绪")
        val camera = driver.liveView ?: error("此相机尚未提供可用的实时取景协议")
        check(driver.capture?.resumePresses() != false) { "快门操作仍在收尾，请稍后重试" }
        update { it.copy(page = TetherPage.LiveView, liveView = it.liveView.copy(starting = true, error = null)) }
        if (pipeline == null) {
            val renderer = LatestLiveViewRenderer(scope, VulkanLiveViewRenderer(engine))
            pipeline = renderer
            renderer.setTargetFps(settings.tetherLiveViewFilterFps.first())
            renderer.setFilterEnabled(settings.tetherLiveViewFilterEnabled.first() && !previewRenderingFailed)
            liveJobs += scope.launch { renderer.notices.collect { notice -> mutablePreviewNotices.emit(notice) } }
            liveJobs += scope.launch { renderer.frames.collect { frame -> liveImage.offer(frame) } }
            liveJobs += scope.launch { renderer.stats.collect { stats ->
                if (stats.degradeReason == DegradeReason.RenderingFailure) previewRenderingFailed = true
                updateLive { it.copy(processedFps = stats.processedFps,
                    receivedFps = stats.presentedFps, frozen = false,
                    filterEnabled = if (previewRenderingFailed) false else it.filterEnabled,
                    filterUnavailableReason = when {
                        previewRenderingFailed -> "预览滤镜渲染失败，可手动重新开启"
                        currentFilter == null -> "请先选择有效的全局滤镜"
                        else -> null
                    }) } } }
            liveJobs += scope.launch { camera.latestFrame.collect { frame ->
                updateLive { it.copy(receivedDimensions = frame?.let { value -> "${value.width} × ${value.height}" }) }
                if (frame != null) renderer.submit(frame, currentFilter)
            } }
            liveJobs += scope.launch { combine(camera.state, camera.formats) { state, formats -> state to formats }
                .collect { (state, formats) -> updateLive { it.copy(
                    running = state.phase == LiveViewPhase.Running, starting = state.phase == LiveViewPhase.Starting,
                    switchingFormat = state.phase == LiveViewPhase.Switching, frozen = state.phase == LiveViewPhase.Failed,
                    selectedFormatIndex = if (state.phase == LiveViewPhase.Running) formats.indexOf(state.format) else it.selectedFormatIndex,
                    formats = formats.mapIndexed { index, value ->
                        TetherLiveViewFormatUi(index, "档位 ${index + 1}", "${value.width} × ${value.height}") },
                    error = state.detail) } } }
        }
        updateLive { it.copy(image = TetherImage(source = liveImage)) }
        try {
            val preferred = settings.tetherLiveViewFormatIndex.first()
            try { camera.start(preferred) } catch (badIndex: IllegalArgumentException) {
                if (preferred == 0) throw badIndex
                camera.start(0); settings.setTetherLiveViewFormatIndex(0)
            }
        } catch (failure: Throwable) { updateLive { it.copy(starting = false, error = failure.message) }; throw failure }
    }

    suspend fun leaveLiveView(destination: TetherExitDestination, restoreOriginalStyle: Boolean = false): Boolean = liveViewLock.withLock {
        try {
            check(!closing) { "联机会话正在关闭" }
            val driver = activeDriver
            // Always drain admitted starts, even while recording is still false in the UI.
            driver?.capture?.finishRecording()
            stopLiveView(driver)
            if (restoreOriginalStyle) reportWrite(driver?.config?.restorePhotoStyle())
            update { it.copy(page = TetherPage.Gallery) }
            true
        } catch (cancel: CancellationException) { throw cancel }
        catch (failure: Throwable) { showError("暂时无法退出取景", failure); false }
    }

    private suspend fun stopLiveView(driver: PtpUsbDriver? = activeDriver, writeCamera: Boolean = true) {
        try {
            if (writeCamera) driver?.liveView?.stop() else driver?.liveView?.abandon()
        } finally {
            val jobs = liveJobs.toList()
            liveJobs.clear()
            jobs.forEach(Job::cancel)
            jobs.joinAll()
            try { pipeline?.close() }
            finally {
                pipeline = null
                liveImage.close()
                updateLive { it.copy(running = false, starting = false, image = TetherImage(), switchingFormat = false,
                    selectedFormatIndex = -1, receivedDimensions = null) }
            }
        }
    }

    private suspend fun pressCapture() {
        check(!closing && enabled.value && uiState.value.page == TetherPage.LiveView) { "请先进入实时取景" }
        when (val result = (activeDriver?.capture ?: error("此相机未确认快门控制")).press()) {
            is PressResult.Accepted -> schedulePostCaptureRefresh()
            is PressResult.Ignored -> error(result.reason)
            is PressResult.Rejected -> error("相机拒绝快门：0x${result.responseCode.toString(16)}")
        }
    }

    /**
     * C108 is not reliable on every body, so an event-only design can miss the shot entirely.
     * Re-enumerate shortly after the shutter instead of waiting for the next 5s poll.
     */
    private fun schedulePostCaptureRefresh() {
        val current = source ?: return
        scope.launch {
            delay(1_500)
            if (source === current && !closing) enumerate(current, background = true)
        }
    }

    private suspend fun writeProperty(id: Long, value: Long) {
        val config = activeDriver?.config ?: error("参数调整不可用")
        reportWrite(config.writeProperty(id, value))
    }
    private fun reportWrite(result: WriteResult?) {
        when (result) {
            null, is WriteResult.Applied -> Unit
            is WriteResult.Disabled -> error(result.reason)
            is WriteResult.Refused -> error("相机未接受该值；已保留实际回读结果")
        }
    }

    private suspend fun showPreview(handle: Long) {
        val entity = findObject(handle)
        previewEntity = entity
        update { it.copy(page = TetherPage.Preview, preview = TetherPreviewUi(handle = handle)) }
    }
    private suspend fun findObject(handle: Long): CameraObjectEntity {
        val current = source ?: error("相机会话已关闭")
        return objects.findByHandle(current.sessionKey.id, handle).singleOrNull()
            ?: error("文件不在当前会话，或存储卡句柄有歧义，请刷新相册")
    }
    private suspend fun download(handles: Set<Long>) {
        val current = source ?: error("请先连接相机")
        val selected = handles.map { findObject(it).toCameraObject() }
        require(selected.isNotEmpty()) { "请先选择文件" }
        transfers.enqueueManual(current.sessionKey, selected)
    }
    private suspend fun loadVideo(handle: Long) {
        val path = originalPaths[handle] ?: error("视频需先手动下载原片，再从本地播放")
        update { it.copy(preview = it.preview.copy(videoLocalUri = path, videoPlaying = true)) }
    }

    private fun media(entity: CameraObjectEntity): TetherMediaUi {
        val current = source ?: return TetherMediaUi(entity.handle)
        val kind = when { entity.format == 0x3801L -> TetherMediaKind.Jpeg
            entity.name.endsWith(".rw2", true) || entity.name.endsWith(".raw", true) -> TetherMediaKind.Raw
            entity.name.endsWith(".mov", true) || entity.name.endsWith(".mp4", true) -> TetherMediaKind.Video
            else -> TetherMediaKind.Unknown }
        val previewKind = if (kind == TetherMediaKind.Jpeg) CameraThumbKey.Kind.FULL_JPEG else CameraThumbKey.Kind.RAW_PREVIEW_1920
        val progress = mediaProgress[entity.handle]
        return TetherMediaUi(entity.handle, entity.name, kind, entity.storageId, entity.parent, true, entity.size,
            capturedAt = entity.dateTaken.takeIf(String::isNotBlank), durationLabel = entity.durationMs?.let { "${it / 60000}:${(it / 1000 % 60).toString().padStart(2, '0')}" },
            thumbnail = TetherImage(source = CameraImageBinding(context, loader, CameraThumbKey(current.sessionKey, entity.storageId, entity.handle))),
            preview = TetherImage(source = CameraImageBinding(context, loader, CameraThumbKey(current.sessionKey, entity.storageId, entity.handle,
                if (kind == TetherMediaKind.Video) CameraThumbKey.Kind.THUMB_160 else previewKind))),
            originalStatus = when { progress?.completed == true -> TetherOriginalStatus.Downloaded
                progress?.error != null -> TetherOriginalStatus.Failed
                progress != null -> TetherOriginalStatus.Downloading
                else -> TetherOriginalStatus.OnCamera }, downloadProgress = progress?.progress, error = progress?.error)
    }

    private fun mapParameters(snapshot: PropertyCatalogSnapshot): TetherParametersUi {
        fun parameter(id: Long): TetherParameterUi {
            val p = snapshot.properties[id] ?: return TetherParameterUi()
            val codec = activeDriver?.profile?.propertyCatalogSeed?.get(id)?.codec
            return TetherParameterUi(p.value, p.label, p.descriptor?.values.orEmpty().map { value ->
                TetherParameterOption(value, codec?.let { PropertyCodec.label(it, value) } ?: value.toString()) },
                p.access == PropertyAccess.Writable, p.detail ?: if (p.access == PropertyAccess.ReadOnly) "当前模式只读" else "相机不支持此参数")
        }
        val style = snapshot.properties[CameraProperty.PHOTO_STYLE]
        val mode = snapshot.mode.mode
        return TetherParametersUi(modeLabel = when (mode) { 0L -> "P"; 1L -> "A"; 2L -> "S"; 3L -> "M"; null -> "—"; else -> "视频" },
            exposureSubMode = snapshot.mode.submode?.let { listOf("P", "A", "S", "M").getOrNull(it) },
            iso = parameter(CameraProperty.ISO), shutter = parameter(CameraProperty.SHUTTER),
            exposureCompensation = parameter(CameraProperty.EV), aperture = parameter(CameraProperty.APERTURE),
            whiteBalance = parameter(CameraProperty.WHITE_BALANCE), photoStyle = style?.value,
            originalPhotoStyle = originalStyle, photoStyleValues = style?.descriptor?.values.orEmpty())
    }

    private fun applyTransferProgress(
        items: List<TransferProgress>,
        progress: TransferProgress,
        batches: Map<Long, TransferBatchProgress>,
    ) {
        val key = source?.sessionKey ?: return
        val current = items.filter { it.sessionKey == key && it.handle != null }
        for ((handle, states) in current.groupBy { requireNotNull(it.handle) }) {
            // A running entry publishes its destination hint before the copy exists; only a committed
            // state has a path that can be played back or reported as downloaded.
            val committed = states.lastOrNull { it.state in committedTransferStates }
            val item = committed ?: states.last()
            val fraction = if (item.totalBytes > 0) (item.bytesDone.toDouble() / item.totalBytes).toFloat().coerceIn(0f, 1f) else 0f
            mediaProgress[handle] = TetherTransferItemUi(handle, item.name, fraction, committed != null, item.error)
            committed?.targetPath?.takeIf(String::isNotBlank)?.let { originalPaths[handle] = it }
        }
        val batch = selectedBatch(batches)
        update { it.copy(transfer = TetherTransferUi(
            active = batch?.active ?: current.any { item -> item.state in setOf(TransferState.RUNNING, TransferState.PENDING, TransferState.SUBMITTING) },
            items = mediaProgress.values.toList(),
            // The user selected a batch; its own counters answer "current / total". Aggregating every
            // handle this session has ever seen made the denominator grow by one with each item.
            completedCount = batch?.currentIndex ?: mediaProgress.values.count { item -> item.completed },
            totalCount = batch?.totalCount ?: mediaProgress.size,
            bytesWritten = batch?.bytesDone ?: progress.bytesDone,
            totalBytes = batch?.totalBytes ?: progress.totalBytes,
            error = progress.error),
            gallery = it.gallery.copy(items = visibleEntities.map(::media))) }
    }

    /** Prefer the newest still-running batch; after it settles keep its final counters visible. */
    private fun selectedBatch(batches: Map<Long, TransferBatchProgress>): TransferBatchProgress? {
        val key = source?.sessionKey ?: return null
        val session = batches.values.filter { it.sessionKey == key }
        if (session.isEmpty()) return null
        return session.filter { it.active }.maxByOrNull { it.id } ?: session.maxByOrNull { it.id }
    }

    /** Caller holds lifecycle. None of the release stages can skip a later release. */
    private suspend fun closeSessionLocked(
        reason: CloseReason,
        finishRecording: Boolean,
        summary: String = "已关闭",
    ) {
        if (closing) return
        val caller = currentCoroutineContext()[Job]
        closing = true
        val old = source
        val driver = activeDriver ?: runtime.driver.value
        eventBarrier.invalidate()
        sessionEpoch.incrementAndGet()
        source = null
        activeDriver = null
        update { it.copy(enabled = false, busy = true) }
        try {
            val failures = runTetherCleanup(
                TetherCleanupStep("关闭联机设置") { settings.setTetherEnabled(false) },
                TetherCleanupStep("停止会话初始化") {
                    val job = sessionJob
                    sessionJob = null
                    if (job !== caller) job?.cancelAndJoin()
                },
                TetherCleanupStep("停止事件消费者") {
                    val jobs = eventJobs.toList()
                    jobs.forEach(Job::cancel)
                    jobs.joinAll()
                },
                TetherCleanupStep("停止回传目录核对") { reconciliationJob?.cancelAndJoin(); reconciliationJob = null },
                TetherCleanupStep("停止相册枚举") { listingJob?.cancelAndJoin(); listingJob = null },
                TetherCleanupStep("停止相册观察") { galleryJob?.cancelAndJoin(); galleryJob = null },
                TetherCleanupStep("停止控制器观察") {
                    val jobs = controllerJobs.toList()
                    controllerJobs.clear()
                    jobs.forEach(Job::cancel)
                    jobs.joinAll()
                },
                TetherCleanupStep("停止录像") {
                    if (finishRecording && reason !in setOf(CloseReason.DETACHED, CloseReason.FATAL)) {
                        liveViewLock.withLock { driver?.capture?.finishRecording() }
                    }
                },
                TetherCleanupStep("停止文件传输") { transfers.stopSession() },
                TetherCleanupStep("停止实时取景") {
                    liveViewLock.withLock {
                        stopLiveView(driver, writeCamera = reason !in setOf(CloseReason.DETACHED, CloseReason.FATAL))
                    }
                },
                TetherCleanupStep("解除对象源") { driver?.bindObjectSource(null) },
                TetherCleanupStep("关闭 USB") { runtime.disconnect(reason) },
                TetherCleanupStep("清理会话缓存") { clearSession(old) },
                TetherCleanupStep("停止前台服务") { stopForegroundService() },
            )
            update { it.copy(enabled = false, connected = false, busy = false,
                connectionSummary = summary.ifBlank { "已关闭" }) }
            if (failures.isNotEmpty()) {
                val recording = failures.any { it.step == "停止录像" }
                val detail = failures.joinToString("\n") { "${it.step}：${it.cause.message ?: it.cause.javaClass.simpleName}" }
                update { it.copy(dialog = TetherDialogUi("联机已关闭，部分收尾未确认",
                    (if (recording) "录像停止未确认，相机可能仍在录制，请在相机上确认。\n" else "") + detail)) }
            }
        } finally { closing = false }
    }

    private suspend fun stopForegroundService() {
        val lease = serviceLease.requestStop() ?: return
        context.stopService(Intent(context, TetherService::class.java))
        // A timeout remains a stopping lease. New enable cannot reuse this service instance.
        withTimeout(5_000) { lease.stopped.await() }
    }

    private suspend fun clearSession(old: CameraObjectSource?) {
        visibleEntities = emptyList(); previewEntity = null
        mediaProgress.clear(); originalPaths.clear(); localOriginalPaths.clear(); originalStyle = null
        autoReconciler = null
        pageLimit.value = 100
        galleryFilter.value = null to null
        update { it.copy(gallery = it.gallery.copy(items = emptyList(), enumerating = false, selectedHandles = emptySet()),
            parameters = TetherParametersUi(), liveView = it.liveView.copy(image = TetherImage(), running = false, starting = false, isRecording = false),
            preview = TetherPreviewUi()) }
        try { loader.memoryCache?.clear() }
        finally { if (old != null) objects.clearSession(old.sessionKey.id) }
    }

    private fun parameterId(key: TetherParameterKey) = when (key) {
        TetherParameterKey.Iso -> CameraProperty.ISO; TetherParameterKey.Shutter -> CameraProperty.SHUTTER
        TetherParameterKey.ExposureCompensation -> CameraProperty.EV; TetherParameterKey.Aperture -> CameraProperty.APERTURE
        TetherParameterKey.WhiteBalance -> CameraProperty.WHITE_BALANCE
    }
    private fun launchAction(block: suspend () -> Unit) { scope.launch { try { block() }
        catch (cancel: CancellationException) { throw cancel }
        catch (failure: Throwable) { showError("操作未完成", failure) } } }
    private fun showError(title: String, failure: Throwable) { update { it.copy(dialog = TetherDialogUi(title, failure.message ?: failure.javaClass.simpleName)) } }
    private inline fun update(transform: (TetherUiState) -> TetherUiState) { mutableUi.update(transform) }
    private inline fun updateGallery(transform: (TetherGalleryUi) -> TetherGalleryUi) { update { it.copy(gallery = transform(it.gallery)) } }
    private inline fun updateLive(transform: (TetherLiveViewUi) -> TetherLiveViewUi) { update { it.copy(liveView = transform(it.liveView)) } }
}
