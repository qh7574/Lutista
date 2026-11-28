package cn.alittlecookie.lutista.tether.ui

import android.graphics.Bitmap
import android.widget.ImageView

/** A view-only binding. Attach/detach must never start/stop a camera session or change its format. */
interface TetherImageSource {
    fun attach(view: ImageView)
    fun detach(view: ImageView)
}

data class TetherImage(
    val bitmap: Bitmap? = null,
    val uri: String? = null,
    val source: TetherImageSource? = null,
)

enum class TetherPage { Gallery, Preview, LiveView }
enum class TetherMediaKind { Jpeg, Raw, Video, Unknown }
enum class TetherOriginalStatus { OnCamera, Downloading, Downloaded, Failed }
enum class TetherParameterKey { Iso, Shutter, ExposureCompensation, Aperture, WhiteBalance }
enum class TetherExitDestination { Back, Gallery, GlobalFilterSettings }
enum class TetherConnectionGuide { NoUsb, MtpDescriptor, WrongMode, UnknownDevice }

/** Unknown capabilities remain attemptable; unsupported capabilities render an explanatory unavailable state. */
data class TetherCapabilities(
    val canBrowse: Boolean = false,
    val canDownload: Boolean = false,
    val canLiveView: Boolean = false,
    val canSwitchLiveViewFormat: Boolean = false,
    val canCapture: Boolean = false,
    val canRecord: Boolean = false,
    val canReadParams: Boolean = false,
    val canWriteParams: Boolean = false,
    val canSetPhotoStyle: Boolean = false,
    val canFilterPreview: Boolean = false,
    val explanations: List<String> = emptyList(),
)

data class TetherStorageUi(val id: Long, val label: String, val itemCount: Int? = null, val usedBytes: Long? = null)
data class TetherDirectoryUi(val handle: Long, val label: String, val storageId: Long? = null)
data class TetherMediaUi(
    val handle: Long,
    val name: String = "正在读取…",
    val kind: TetherMediaKind = TetherMediaKind.Unknown,
    val storageId: Long? = null,
    val directoryHandle: Long? = null,
    val infoLoaded: Boolean = false,
    val sizeBytes: Long? = null,
    val dimensions: String? = null,
    val capturedAt: String? = null,
    val durationLabel: String? = null,
    val thumbnail: TetherImage = TetherImage(),
    val preview: TetherImage = TetherImage(),
    val originalStatus: TetherOriginalStatus = TetherOriginalStatus.OnCamera,
    val downloadProgress: Float? = null,
    val error: String? = null,
)

data class TetherGalleryUi(
    val items: List<TetherMediaUi> = emptyList(),
    val storages: List<TetherStorageUi> = emptyList(),
    val selectedStorageId: Long? = null,
    val directories: List<TetherDirectoryUi> = emptyList(),
    val selectedDirectoryHandle: Long? = null,
    val enumerating: Boolean = false,
    val enumeratedCount: Int = 0,
    val totalCount: Int? = null,
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
    val selectionMode: Boolean = false,
    val selectedHandles: Set<Long> = emptySet(),
    val clockWarning: String? = null,
    val error: String? = null,
    val directoryWarning: String? = null,
)

data class TetherPreviewUi(
    val handle: Long? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val videoLocalUri: String? = null,
    val videoPlaying: Boolean = false,
    val videoPositionMillis: Long = 0,
    val videoDurationMillis: Long = 0,
)

data class TetherParameterOption(val value: Long, val label: String)
/** Descriptor options are the actual snapshot, never a camera-mode-specific hardcoded list. */
data class TetherParameterUi(
    val value: Long? = null,
    val displayValue: String? = null,
    val options: List<TetherParameterOption> = emptyList(),
    val writable: Boolean = false,
    val unavailableReason: String = "相机未提供此参数",
    val writing: Boolean = false,
    val error: String? = null,
) {
    val displayText: String get() = displayValue?.takeIf { it.isNotBlank() } ?: "—"
    val enabled: Boolean get() = writable && options.isNotEmpty() && !writing
}

data class TetherParametersUi(
    val modeLabel: String = "—",
    val exposureSubMode: String? = null,
    val iso: TetherParameterUi = TetherParameterUi(),
    val shutter: TetherParameterUi = TetherParameterUi(),
    val exposureCompensation: TetherParameterUi = TetherParameterUi(),
    val aperture: TetherParameterUi = TetherParameterUi(),
    val whiteBalance: TetherParameterUi = TetherParameterUi(),
    val photoStyle: Long? = null,
    val originalPhotoStyle: Long? = null,
    val photoStyleValues: List<Long> = emptyList(),
    val photoStyleWriting: Boolean = false,
    val photoStyleError: String? = null,
) {
    fun parameter(key: TetherParameterKey): TetherParameterUi = when (key) {
        TetherParameterKey.Iso -> iso
        TetherParameterKey.Shutter -> shutter
        TetherParameterKey.ExposureCompensation -> exposureCompensation
        TetherParameterKey.Aperture -> aperture
        TetherParameterKey.WhiteBalance -> whiteBalance
    }
    val styleChanged: Boolean get() = originalPhotoStyle != null && photoStyle != originalPhotoStyle
}

data class TetherLiveViewFormatUi(val index: Int, val label: String, val dimensions: String, val note: String = "")
data class TetherLiveViewUi(
    val image: TetherImage = TetherImage(),
    val running: Boolean = false,
    val starting: Boolean = false,
    val frozen: Boolean = false,
    val error: String? = null,
    val formats: List<TetherLiveViewFormatUi> = emptyList(),
    /** Index of a successfully applied camera tuple; -1 means no confirmed selection yet. */
    val selectedFormatIndex: Int = -1,
    val switchingFormat: Boolean = false,
    /** Actual JPEG dimensions from the latest decoded camera frame, not advertised tuple hints. */
    val receivedDimensions: String? = null,
    val receivedFps: Float? = null,
    val processedFps: Float? = null,
    val targetFps: Int = 15,
    val filterEnabled: Boolean = false,
    val filterAvailable: Boolean = false,
    val filterUnavailableReason: String? = null,
    val globalFilterName: String? = null,
    val isVideoMode: Boolean = false,
    val isRecording: Boolean = false,
    val recordingElapsedMillis: Long = 0,
    val captureBusy: Boolean = false,
    val autoDownload: Boolean = false,
    val autoDownloadAvailable: Boolean = false,
    val autoDownloadReason: String? = null,
) {
    /** Preserve the camera's advertised index; never fall back to a different tuple. */
    val selectedFormat: TetherLiveViewFormatUi? get() = formats.firstOrNull { it.index == selectedFormatIndex }
}

/** Keep the resolution entry visible; explain why its advertised choices cannot be changed. */
internal fun TetherUiState.liveViewFormatUnavailableReason(): String? = when {
    !connected -> "相机未连接"
    liveView.switchingFormat -> "正在切换预览分辨率…"
    liveView.starting -> "正在读取相机支持的预览分辨率…"
    liveView.formats.isEmpty() -> "相机未返回可用的预览分辨率"
    liveView.formats.size == 1 -> "相机仅提供一种预览分辨率"
    !capabilities.canSwitchLiveViewFormat -> "当前相机能力未开放预览分辨率切换"
    liveView.isRecording -> "录像中无法切换预览分辨率"
    liveView.captureBusy -> "拍摄处理中，暂时无法切换预览分辨率"
    !liveView.running -> "实时预览启动后可切换分辨率"
    else -> null
}

data class TetherTransferItemUi(
    val handle: Long,
    val name: String,
    val progress: Float = 0f,
    val completed: Boolean = false,
    val error: String? = null,
)
data class TetherTransferUi(
    val active: Boolean = false,
    val items: List<TetherTransferItemUi> = emptyList(),
    val completedCount: Int = 0,
    val totalCount: Int = 0,
    val bytesWritten: Long = 0,
    val totalBytes: Long = 0,
    val error: String? = null,
)

data class TetherDialogUi(
    val title: String,
    val message: String,
    val connectionGuide: TetherConnectionGuide? = null,
    val retryFailedTransfers: Boolean = false,
)

data class TetherUiState(
    val page: TetherPage = TetherPage.Gallery,
    val enabled: Boolean = false,
    val busy: Boolean = false,
    val connected: Boolean = false,
    val connectionSummary: String = "未连接",
    val todayJpegCount: Int? = null,
    val cameraName: String? = null,
    val cameraDetails: String? = null,
    val batteryPercent: Int? = null,
    val backgroundConnectionNotice: String? = null,
    val capabilities: TetherCapabilities = TetherCapabilities(),
    val gallery: TetherGalleryUi = TetherGalleryUi(),
    val preview: TetherPreviewUi = TetherPreviewUi(),
    val parameters: TetherParametersUi = TetherParametersUi(),
    val liveView: TetherLiveViewUi = TetherLiveViewUi(),
    val transfer: TetherTransferUi = TetherTransferUi(),
    val dialog: TetherDialogUi? = null,
    val useBlur: Boolean = true,
    /** 相册与取景共用的同一个「滤镜直出」开关（持久化在设置里，不在会话里）。 */
    val directFilter: Boolean = false,
    val directFilterAvailable: Boolean = false,
    val directFilterReason: String? = null,
)

sealed interface TetherUiAction {
    data class SetEnabled(val enabled: Boolean) : TetherUiAction
    data object OpenGallery : TetherUiAction
    data object OpenLiveView : TetherUiAction
    data class OpenPreview(val handle: Long, val requestId: Long) : TetherUiAction
    data class ShowPreview(val handle: Long) : TetherUiAction
    data object RefreshGallery : TetherUiAction
    data object LoadMoreGallery : TetherUiAction
    data class SelectStorage(val storageId: Long?) : TetherUiAction
    data class SelectDirectory(val directoryHandle: Long?) : TetherUiAction
    data class SetSelectionMode(val enabled: Boolean) : TetherUiAction
    data class ToggleSelection(val handle: Long) : TetherUiAction
    data object SelectAll : TetherUiAction
    data object DismissClockWarning : TetherUiAction
    data class Download(val handles: Set<Long>) : TetherUiAction
    data object CancelTransfer : TetherUiAction
    data object RetryFailedTransfers : TetherUiAction
    data class SetParameter(val key: TetherParameterKey, val value: Long) : TetherUiAction
    data class SetPhotoStyle(val value: Long) : TetherUiAction
    data object RestorePhotoStyle : TetherUiAction
    data class SetLiveViewFormat(val index: Int) : TetherUiAction
    data class SetTargetFps(val fps: Int) : TetherUiAction
    data class SetPreviewFilter(val enabled: Boolean) : TetherUiAction
    data object OpenGlobalFilterSettings : TetherUiAction
    data class SetAutoDownload(val enabled: Boolean) : TetherUiAction
    data class SetDirectFilter(val enabled: Boolean) : TetherUiAction
    data object Capture : TetherUiAction
    data object ToggleRecording : TetherUiAction
    data object RetryLiveView : TetherUiAction
    /** Route stops streaming once, then changes the page/pops. Never dispatched by composition. */
    data class ExitLiveView(val destination: TetherExitDestination, val restoreOriginalStyle: Boolean = false) : TetherUiAction
    /** Route must send the recording toggle once, await it, then stop streaming and navigate. */
    data class StopRecordingAndExit(val destination: TetherExitDestination, val restoreOriginalStyle: Boolean = false) : TetherUiAction
    data class PlayVideo(val handle: Long) : TetherUiAction
    data object ToggleVideoPlayback : TetherUiAction
    data class SeekVideo(val positionMillis: Long) : TetherUiAction
    data object DismissDialog : TetherUiAction
}
