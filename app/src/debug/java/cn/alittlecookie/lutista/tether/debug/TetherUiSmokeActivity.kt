package cn.alittlecookie.lutista.tether.debug

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.os.Bundle
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.alittlecookie.lutista.tether.ui.*
import cn.alittlecookie.lutista.ui.theme.LutistaTheme
import java.lang.ref.WeakReference
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text

/** Debug source-set only. No repository, USB, camera, renderer, or service is referenced. */
class TetherUiSmokeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val page = when (intent.getStringExtra("page")) {
            "liveview" -> TetherPage.LiveView
            "preview" -> TetherPage.Preview
            else -> TetherPage.Gallery
        }
        setContent { LutistaTheme { SmokeContent(page, ::finish) } }
    }
}

@Composable
private fun SmokeContent(initialPage: TetherPage, finish: () -> Unit) {
    val image = remember { SmokeBitmapSource() }
    DisposableEffect(image) { onDispose { image.close() } }
    var state by remember { mutableStateOf(smokeState(initialPage, image)) }
    LaunchedEffect(Unit) {
        delay(900)
        state = state.copy(gallery = state.gallery.copy(enumerating = false, enumeratedCount = 24,
            items = state.gallery.items.map { it.copy(infoLoaded = true, thumbnail = TetherImage(source = image)) }))
    }
    LaunchedEffect(state.liveView.isRecording) {
        while (state.liveView.isRecording) {
            delay(1000)
            state = state.copy(liveView = state.liveView.copy(recordingElapsedMillis = state.liveView.recordingElapsedMillis + 1000))
        }
    }
    Column(Modifier.fillMaxSize().background(Color(0xFFFFF3CC)).safeDrawingPadding()) {
        Text("模拟 UI · 无相机 / 无 USB / 不发送拍摄命令", Modifier.fillMaxWidth().padding(4.dp), color = Color(0xFF9B2222), fontSize = 12.sp)
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("图库" to TetherPage.Gallery, "取景" to TetherPage.LiveView, "预览" to TetherPage.Preview).forEach { (label, page) ->
                Button(onClick = { state = state.copy(page = page) }, modifier = Modifier.weight(1f)) { Text("模拟$label", fontSize = 12.sp) }
            }
            Button(onClick = { state = state.copy(liveView = state.liveView.copy(isVideoMode = !state.liveView.isVideoMode)) }) { Text("照片/录像", fontSize = 11.sp) }
        }
        TetherScreen(state, onAction = { state = reduceSmoke(state, it) }, onBack = finish)
    }
}

private fun smokeState(page: TetherPage, source: TetherImageSource): TetherUiState {
    val image = TetherImage(source = source)
    fun parameter(value: Long, text: String, vararg options: Pair<Long, String>) = TetherParameterUi(
        value = value, displayValue = text, options = options.map { TetherParameterOption(it.first, it.second) }, writable = true)
    return TetherUiState(page = page, enabled = true, connected = true, cameraName = "模拟相机 · UI 演示",
        connectionSummary = "模拟连接，不代表硬件验收", cameraDetails = "仅验证真实 Compose / miuix 布局，不含任何相机会话。",
        batteryPercent = 72, backgroundConnectionNotice = "模拟：后台连接通知文案预览，不启动服务。",
        capabilities = TetherCapabilities(canBrowse = true, canDownload = true, canLiveView = true,
            canSwitchLiveViewFormat = true, canCapture = true, canRecord = true, canReadParams = true,
            canWriteParams = true, canSetPhotoStyle = true, canFilterPreview = true,
            explanations = listOf("模拟能力：不探测硬件", "只读光圈与缺失 EV 用于灰态验证")),
        gallery = TetherGalleryUi(items = (1L..24L).map { handle ->
            val kind = when ((handle % 3).toInt()) { 0 -> TetherMediaKind.Video; 1 -> TetherMediaKind.Jpeg; else -> TetherMediaKind.Raw }
            val extension = when (kind) { TetherMediaKind.Jpeg -> "JPG"; TetherMediaKind.Raw -> "RW2"; else -> "MOV" }
            TetherMediaUi(handle, "模拟_${handle.toString().padStart(4, '0')}.$extension", kind,
                storageId = if (handle <= 12) 1L else 2L, directoryHandle = if (handle <= 12) 100L else 200L,
                infoLoaded = handle <= 20, sizeBytes = handle * 1024L * 1024L, dimensions = "6000 × 4000",
                capturedAt = "模拟拍摄时间", durationLabel = if (kind == TetherMediaKind.Video) "00:12" else null,
                thumbnail = if (handle <= 20) image else TetherImage(), preview = image,
                originalStatus = if (handle == 1L) TetherOriginalStatus.Downloaded else TetherOriginalStatus.OnCamera)
        }, storages = listOf(TetherStorageUi(1, "模拟卡 1", 12), TetherStorageUi(2, "模拟卡 2", 12)),
            directories = listOf(TetherDirectoryUi(100, "模拟 100PHOTO", 1), TetherDirectoryUi(200, "模拟 200PHOTO", 2)),
            enumerating = true, enumeratedCount = 20, totalCount = 24, destination = "模拟 / Pictures/Lutista（不写文件）",
            clockWarning = "模拟时钟提示：这里只演示可关闭提示，不检查相机时间。"),
        preview = TetherPreviewUi(handle = 1),
        parameters = TetherParametersUi(modeLabel = "模拟 M", exposureSubMode = "模拟 descriptor 快照",
            iso = parameter(100, "100", 100L to "100", 400L to "400", 800L to "800"),
            shutter = parameter(125, "1/125", 60L to "1/60", 125L to "1/125", 250L to "1/250"),
            exposureCompensation = TetherParameterUi(unavailableReason = "模拟缺值：descriptor 未返回 EV"),
            aperture = TetherParameterUi(value = 28, displayValue = "F2.8", unavailableReason = "模拟只读：请使用相机转盘"),
            whiteBalance = parameter(0, "自动", 0L to "自动", 1L to "日光", 2L to "阴天"),
            photoStyle = 1, originalPhotoStyle = 1, photoStyleValues = listOf(1, 2, 3, 8)),
        liveView = TetherLiveViewUi(image = image, running = true, receivedFps = 29.8f, processedFps = 15f,
            formats = listOf(TetherLiveViewFormatUi(0, "模拟标准", "640 × 432"), TetherLiveViewFormatUi(1, "模拟高清", "1024 × 768")),
            filterAvailable = true, globalFilterName = "模拟全局滤镜", autoDownloadAvailable = true))
}

/** Local presentation reducer: intentionally exhaustive fallthrough; never delegates side effects. */
private fun reduceSmoke(state: TetherUiState, action: TetherUiAction): TetherUiState {
    fun message(text: String) = state.copy(dialog = TetherDialogUi("模拟操作", "$text\n未发送任何相机命令，也不写入文件。"))
    fun exit(destination: TetherExitDestination, restore: Boolean): TetherUiState = state.copy(
        page = TetherPage.Gallery, liveView = state.liveView.copy(isRecording = false),
        parameters = if (restore) state.parameters.copy(photoStyle = state.parameters.originalPhotoStyle) else state.parameters,
        dialog = if (destination == TetherExitDestination.GlobalFilterSettings) TetherDialogUi("模拟全局设置", "真实模式会返回主页选择全局滤镜。") else null)
    return when (action) {
        TetherUiAction.OpenGallery -> state.copy(page = TetherPage.Gallery)
        TetherUiAction.OpenLiveView -> state.copy(page = TetherPage.LiveView)
        is TetherUiAction.OpenPreview -> state.copy(page = TetherPage.Preview, preview = TetherPreviewUi(handle = action.handle))
        is TetherUiAction.ShowPreview -> state.copy(preview = TetherPreviewUi(handle = action.handle))
        is TetherUiAction.SelectStorage -> state.copy(gallery = state.gallery.copy(selectedStorageId = action.storageId, selectedDirectoryHandle = null))
        is TetherUiAction.SelectDirectory -> state.copy(gallery = state.gallery.copy(selectedDirectoryHandle = action.directoryHandle))
        is TetherUiAction.SetSelectionMode -> state.copy(gallery = state.gallery.copy(selectionMode = action.enabled, selectedHandles = emptySet()))
        is TetherUiAction.ToggleSelection -> state.copy(gallery = state.gallery.copy(selectedHandles = if (action.handle in state.gallery.selectedHandles) state.gallery.selectedHandles - action.handle else state.gallery.selectedHandles + action.handle))
        TetherUiAction.SelectAll -> state.copy(gallery = state.gallery.copy(selectedHandles = state.gallery.items.filter { (state.gallery.selectedStorageId == null || it.storageId == state.gallery.selectedStorageId) && (state.gallery.selectedDirectoryHandle == null || it.directoryHandle == state.gallery.selectedDirectoryHandle) }.map { it.handle }.toSet()))
        TetherUiAction.DismissClockWarning -> state.copy(gallery = state.gallery.copy(clockWarning = null))
        is TetherUiAction.Download -> state.copy(gallery = state.gallery.copy(items = state.gallery.items.map { if (it.handle in action.handles) it.copy(originalStatus = TetherOriginalStatus.Downloaded) else it }), dialog = TetherDialogUi("模拟下载完成", "只修改 UI 状态，未读取相机或创建文件。"))
        is TetherUiAction.SetParameter -> {
            val old = state.parameters.parameter(action.key)
            val value = old.copy(value = action.value, displayValue = old.options.firstOrNull { it.value == action.value }?.label)
            val parameters = when (action.key) {
                TetherParameterKey.Iso -> state.parameters.copy(iso = value)
                TetherParameterKey.Shutter -> state.parameters.copy(shutter = value)
                TetherParameterKey.ExposureCompensation -> state.parameters.copy(exposureCompensation = value)
                TetherParameterKey.Aperture -> state.parameters.copy(aperture = value)
                TetherParameterKey.WhiteBalance -> state.parameters.copy(whiteBalance = value)
            }
            state.copy(parameters = parameters)
        }
        is TetherUiAction.SetPhotoStyle -> state.copy(parameters = state.parameters.copy(photoStyle = action.value))
        TetherUiAction.RestorePhotoStyle -> state.copy(parameters = state.parameters.copy(photoStyle = state.parameters.originalPhotoStyle))
        is TetherUiAction.SetLiveViewFormat -> state.copy(liveView = state.liveView.copy(selectedFormatIndex = action.index, filterEnabled = false, filterAvailable = action.index == 0, filterUnavailableReason = if (action.index == 0) null else "模拟：高清格式不可用预览滤镜"))
        is TetherUiAction.SetTargetFps -> state.copy(liveView = state.liveView.copy(targetFps = action.fps, processedFps = action.fps.toFloat()))
        is TetherUiAction.SetPreviewFilter -> state.copy(liveView = state.liveView.copy(filterEnabled = action.enabled))
        is TetherUiAction.SetAutoDownload -> state.copy(liveView = state.liveView.copy(autoDownload = action.enabled))
        TetherUiAction.ToggleRecording -> state.copy(liveView = state.liveView.copy(isRecording = !state.liveView.isRecording, recordingElapsedMillis = 0))
        is TetherUiAction.ExitLiveView -> exit(action.destination, action.restoreOriginalStyle)
        is TetherUiAction.StopRecordingAndExit -> exit(action.destination, action.restoreOriginalStyle)
        TetherUiAction.DismissDialog -> state.copy(dialog = null)
        is TetherUiAction.SetEnabled -> state.copy(enabled = action.enabled)
        else -> message("操作 ${action.javaClass.simpleName} 仅用于界面演示。")
    }
}

/** Clears every borrowed ImageView before recycling the single owner bitmap, including rotation. */
private class SmokeBitmapSource : TetherImageSource, AutoCloseable {
    private val views = mutableListOf<WeakReference<ImageView>>()
    private var bitmap: Bitmap? = Bitmap.createBitmap(640, 432, Bitmap.Config.ARGB_8888).also { bitmap ->
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val colors = intArrayOf(AndroidColor.WHITE, AndroidColor.YELLOW, AndroidColor.CYAN, AndroidColor.GREEN, AndroidColor.MAGENTA, AndroidColor.RED, AndroidColor.BLUE)
        colors.forEachIndexed { index, color ->
            paint.color = color
            canvas.drawRect(index * 640f / colors.size, 0f, (index + 1) * 640f / colors.size, 432f, paint)
        }
        paint.color = AndroidColor.BLACK; canvas.drawRect(0f, 290f, 640f, 370f, paint)
        paint.color = AndroidColor.WHITE; paint.textSize = 34f
        canvas.drawText("SIMULATED UI - NO CAMERA", 34f, 340f, paint)
    }
    override fun attach(view: ImageView) {
        views.removeAll { it.get() == null || it.get() === view }
        views += WeakReference(view)
        view.setImageBitmap(bitmap)
    }
    override fun detach(view: ImageView) {
        views.removeAll { it.get() == null || it.get() === view }
        view.setImageDrawable(null)
    }
    override fun close() {
        views.forEach { it.get()?.setImageDrawable(null) }; views.clear()
        bitmap?.recycle(); bitmap = null
    }
}
