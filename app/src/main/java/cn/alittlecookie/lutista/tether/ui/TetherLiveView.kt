package cn.alittlecookie.lutista.tether.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun TetherLiveView(
    state: TetherUiState,
    onAction: (TetherUiAction) -> Unit,
    requestExit: (TetherExitDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    var collapsed by rememberSaveable { mutableStateOf(false) }
    var panelStage by rememberSaveable { mutableIntStateOf(0) }
    var selectedParameter by rememberSaveable { mutableStateOf<String?>(null) }
    var photoStyles by rememberSaveable { mutableStateOf(false) }
    var showFormats by remember { mutableStateOf(false) }
    var explanation by remember { mutableStateOf<String?>(null) }
    var filterExplanation by remember { mutableStateOf(false) }
    var previousLandscape by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val live = state.liveView
    val panelDragThreshold = with(LocalDensity.current) { 24.dp.toPx() }
    val openParameter: (TetherParameterKey) -> Unit = { key ->
        val descriptor = state.parameters.parameter(key)
        if (descriptor.enabled) { selectedParameter = key.name; photoStyles = false; panelStage = 2; collapsed = false }
        else { explanation = descriptor.unavailableReason; filterExplanation = false }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val spec = TetherLayoutSpec.fromSize(maxWidth.value, maxHeight.value, collapsed)
        val fullHeight = maxHeight.value
        LaunchedEffect(spec.landscape) {
            if (previousLandscape == true && !spec.landscape) { panelStage = 0; selectedParameter = null; photoStyles = false }
            previousLandscape = spec.landscape
        }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TetherButton("返回", { requestExit(TetherExitDestination.Back) }, enabled = !(live.isVideoMode && live.captureBusy))
                Column(Modifier.weight(1f)) {
                    Text("实时预览", fontWeight = FontWeight.SemiBold, fontSize = 19.sp, color = MiuixTheme.colorScheme.onSurface)
                    Text(listOfNotNull(state.cameraName, live.receivedDimensions,
                        live.receivedFps?.let { String.format(java.util.Locale.ROOT, "%.1f fps", it) }).joinToString(" · ").ifBlank { state.connectionSummary },
                        fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                state.batteryPercent?.let { Text("电量 ${it.coerceIn(0, 100)}%", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
            }
            if (spec.landscape) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.width(spec.leftPanelWidthDp.dp).fillMaxHeight().padding(start = 8.dp).clip(RoundedCornerShape(20.dp)).background(MiuixTheme.colorScheme.surfaceVariant)) {
                        if (collapsed) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.SpaceEvenly) {
                                Text(state.parameters.modeLabel, Modifier.padding(6.dp), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface)
                                ExposureKeys.forEach { key -> TetherExposureCell(key, state.parameters.parameter(key), { openParameter(key) }, compact = true) }
                            }
                        } else {
                            TetherParameterPanel(state, selectedParameter?.let(TetherParameterKey::valueOf), photoStyles,
                                onSelect = openParameter, onCloseChoice = { selectedParameter = null; photoStyles = false },
                                onPhotoStyles = { photoStyles = true; selectedParameter = null }, onAction = onAction,
                                modifier = Modifier.weight(1f).fillMaxWidth())
                        }
                        IconButton(onClick = { collapsed = !collapsed }, modifier = Modifier.fillMaxWidth().height(48.dp)
                            .semantics { contentDescription = if (collapsed) "展开参数栏" else "收起参数栏" }) {
                            Text(if (collapsed) "›" else "‹ 收起", fontSize = 17.sp, color = MiuixTheme.colorScheme.onSurface)
                        }
                    }
                    TetherLiveImage(state, onAction, Modifier.weight(1f).fillMaxHeight())
                    TetherShutterToolbar(state, onAction, requestExit, { showFormats = true },
                        onFilterUnavailable = { filterExplanation = true; explanation = live.filterUnavailableReason ?: "请先在主页全局设置中选择已启用的滤镜。" },
                        landscape = true, modifier = Modifier.width(spec.rightToolbarWidthDp.dp).fillMaxHeight())
                }
            } else {
                // Reserve the toolbar first. Expanded panels may cover the preview, never the shutter.
                Column(Modifier.weight(1f).fillMaxWidth()) {
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        val available = maxHeight.value.coerceAtLeast(0f)
                        val portrait = TetherLayoutSpec.portrait(available, fullHeight, panelStage)
                        val panelHeight = portrait.panelHeightDp
                        val previewHeight = portrait.previewHeightDp
                        TetherLiveImage(state, onAction, Modifier.align(Alignment.TopCenter).fillMaxWidth().height(previewHeight.dp).padding(horizontal = 12.dp))
                        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = panelHeight.dp).fillMaxWidth().height(portrait.modeHeightDp.dp)
                            .background(MiuixTheme.colorScheme.background).padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(state.parameters.modeLabel, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = MiuixTheme.colorScheme.onSurface)
                            Text("请拨动相机转盘", Modifier.weight(1f), fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            TetherButton("参数", { panelStage = (panelStage + 1) % 3; selectedParameter = null; photoStyles = false })
                        }
                        var dragAmount by remember { mutableFloatStateOf(0f) }
                        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(panelHeight.dp).padding(horizontal = 8.dp)
                            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).background(MiuixTheme.colorScheme.surfaceVariant)) {
                            Row(Modifier.fillMaxWidth().height(if (panelHeight < 56) 12.dp else 22.dp).pointerInput(panelDragThreshold) {
                                detectVerticalDragGestures(onDragStart = { dragAmount = 0f },
                                    onVerticalDrag = { change, amount -> change.consume(); dragAmount += amount },
                                    onDragEnd = { if (dragAmount < -panelDragThreshold) panelStage = (panelStage + 1).coerceAtMost(2) else if (dragAmount > panelDragThreshold) panelStage = (panelStage - 1).coerceAtLeast(0) })
                            }.clickable { panelStage = (panelStage + 1) % 3 }, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                Text(if (panelStage == 0) "参数 · 上拉调节" else "参数 · 下拉收起", fontSize = 10.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                            if (panelStage == 0) {
                                Row(Modifier.fillMaxWidth()) { ExposureKeys.forEach { key -> TetherExposureCell(key, state.parameters.parameter(key), { openParameter(key) }, Modifier.weight(1f), tiny = true) } }
                            } else TetherParameterPanel(state, selectedParameter?.let(TetherParameterKey::valueOf), photoStyles, openParameter,
                                { selectedParameter = null; photoStyles = false }, { photoStyles = true; selectedParameter = null }, onAction, Modifier.weight(1f))
                        }
                    }
                    TetherShutterToolbar(state, onAction, requestExit, { showFormats = true },
                        { filterExplanation = true; explanation = live.filterUnavailableReason ?: "请先在主页全局设置中选择已启用的滤镜。" }, false, Modifier.fillMaxWidth().height(112.dp))
                }
            }
        }
    }
    TetherFormatDialog(show = showFormats, state = state, onAction = onAction) { showFormats = false }
    explanation?.let { message -> TetherConfirmDialog("当前不可用", message, confirm = "知道了", dismiss = "关闭",
        onConfirm = { explanation = null }, onDismiss = { explanation = null },
        extra = if (filterExplanation) ({ TetherButton("前往全局滤镜设置", { explanation = null; requestExit(TetherExitDestination.GlobalFilterSettings) }, Modifier.fillMaxWidth()) }) else null) }
}

private val ExposureKeys = listOf(TetherParameterKey.Iso, TetherParameterKey.Shutter, TetherParameterKey.ExposureCompensation, TetherParameterKey.Aperture)
internal fun TetherParameterKey.label(): String = when (this) {
    TetherParameterKey.Iso -> "ISO"; TetherParameterKey.Shutter -> "快门"; TetherParameterKey.ExposureCompensation -> "EV"; TetherParameterKey.Aperture -> "光圈"; TetherParameterKey.WhiteBalance -> "白平衡"
}

@Composable
private fun TetherExposureCell(key: TetherParameterKey, value: TetherParameterUi, onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false, tiny: Boolean = false) {
    Column(modifier.clickable(onClick = onClick).padding(horizontal = if (compact) 3.dp else 8.dp, vertical = if (tiny) 0.dp else 6.dp)
        .semantics { contentDescription = "${key.label()} ${value.displayText}${if (!value.enabled) "，不可调节：${value.unavailableReason}" else ""}" },
        horizontalAlignment = if (compact || tiny) Alignment.CenterHorizontally else Alignment.Start) {
        Text(key.label(), fontSize = if (tiny) 9.sp else 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(if (value.writing) "写入中…" else value.displayText, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontSize = if (compact || tiny) 11.sp else 16.sp, fontWeight = FontWeight.Medium,
            color = if (value.enabled) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = .55f))
    }
}

@Composable
private fun TetherParameterPanel(
    state: TetherUiState, selected: TetherParameterKey?, photoStyles: Boolean,
    onSelect: (TetherParameterKey) -> Unit, onCloseChoice: () -> Unit, onPhotoStyles: () -> Unit,
    onAction: (TetherUiAction) -> Unit, modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        // The four exposure readouts stay mounted even when another descriptor/style list is open.
        Row(Modifier.fillMaxWidth()) {
            ExposureKeys.forEach { key -> TetherExposureCell(key, state.parameters.parameter(key), { onSelect(key) }, Modifier.weight(1f), tiny = true) }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (selected != null) {
            TetherButton("‹ ${selected.label()}", onCloseChoice, Modifier.fillMaxWidth())
            val parameter = state.parameters.parameter(selected)
            parameter.options.forEach { option ->
                TetherButton(option.label, { onAction(TetherUiAction.SetParameter(selected, option.value)) }, Modifier.fillMaxWidth(), enabled = parameter.enabled, primary = option.value == parameter.value)
            }
            parameter.error?.let { Text(it, fontSize = 11.sp, color = MiuixTheme.colorScheme.error) }
        } else if (photoStyles) {
            TetherButton("‹ 照片格调", onCloseChoice, Modifier.fillMaxWidth())
            state.parameters.photoStyleValues.forEach { value ->
                TetherButton("格调 $value", { onAction(TetherUiAction.SetPhotoStyle(value)) }, Modifier.fillMaxWidth(), enabled = !state.parameters.photoStyleWriting, primary = value == state.parameters.photoStyle)
            }
            state.parameters.photoStyleError?.let { Text(it, fontSize = 11.sp, color = MiuixTheme.colorScheme.error) }
        } else {
            Text("${state.parameters.modeLabel} · 请拨动相机转盘", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            state.parameters.exposureSubMode?.let { Text(it, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
            TetherExposureCell(TetherParameterKey.WhiteBalance, state.parameters.whiteBalance, { onSelect(TetherParameterKey.WhiteBalance) }, Modifier.fillMaxWidth())
            if (state.capabilities.canSetPhotoStyle && state.parameters.photoStyleValues.isNotEmpty()) {
                TetherButton(state.parameters.photoStyle?.let { "照片格调 · 格调 $it" } ?: "照片格调 · —", onPhotoStyles, Modifier.fillMaxWidth(), enabled = !state.parameters.photoStyleWriting)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("拍摄后自动回传 JPEG", Modifier.weight(1f), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurface)
                Switch(checked = state.liveView.autoDownload, onCheckedChange = { onAction(TetherUiAction.SetAutoDownload(it)) }, enabled = state.liveView.autoDownloadAvailable)
            }
            Text(state.liveView.autoDownloadDestinationLabel, fontSize = 10.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Text("未启用处理效果时保留原片；有效效果将按主页设置处理。", fontSize = 10.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            state.liveView.autoDownloadReason?.let { Text(it, fontSize = 10.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
        }
        }
    }
}

@Composable
private fun TetherLiveImage(state: TetherUiState, onAction: (TetherUiAction) -> Unit, modifier: Modifier) {
    val live = state.liveView
    Box(modifier.clip(RoundedCornerShape(20.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
        TetherImageView(live.image, "相机实时取景", Modifier.fillMaxSize().alpha(if (live.switchingFormat) .35f else 1f))
        when {
            live.switchingFormat -> TetherBusy("正在切换预览分辨率…")
            live.starting -> TetherBusy("正在启动实时预览…")
            live.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                Text(live.error, color = Color.White, fontSize = 12.sp)
                TetherButton("重试", { onAction(TetherUiAction.RetryLiveView) }, enabled = state.connected)
            }
            live.frozen -> Text("画面已暂停", color = Color.White, fontSize = 12.sp)
            !live.running -> Text("等待相机画面", color = Color.LightGray, fontSize = 12.sp)
        }
    }
}

@Composable
private fun TetherShutterToolbar(
    state: TetherUiState, onAction: (TetherUiAction) -> Unit, requestExit: (TetherExitDestination) -> Unit,
    onFormats: () -> Unit, onFilterUnavailable: () -> Unit, landscape: Boolean, modifier: Modifier,
) {
    val live = state.liveView
    val shutter: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Button(onClick = { onAction(if (live.isVideoMode) TetherUiAction.ToggleRecording else TetherUiAction.Capture) },
                modifier = Modifier.size(64.dp), cornerRadius = 32.dp, minWidth = 64.dp, minHeight = 64.dp,
                insideMargin = androidx.compose.foundation.layout.PaddingValues(0.dp),
                enabled = state.connected && !live.captureBusy && !live.switchingFormat && (if (live.isVideoMode) state.capabilities.canRecord else state.capabilities.canCapture),
                colors = ButtonDefaults.buttonColors(color = if (live.isVideoMode) Color(0xFFE34D59) else Color.White)) {
                if (live.isRecording) Box(Modifier.size(22.dp).clip(RoundedCornerShape(5.dp)).background(Color.White))
                else Text(if (live.captureBusy) "…" else if (live.isVideoMode) "录像" else "拍照", fontSize = 12.sp, color = if (live.isVideoMode) Color.White else Color.DarkGray)
            }
            if (live.isRecording) Text("REC ${formatTetherTime(live.recordingElapsedMillis)}", fontSize = 10.sp, color = MiuixTheme.colorScheme.error)
        }
    }
    val filter: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(enabled = !live.filterAvailable, onClick = onFilterUnavailable)) {
            Text("预览滤镜", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Switch(checked = live.filterEnabled, onCheckedChange = { onAction(TetherUiAction.SetPreviewFilter(it)) }, enabled = live.filterAvailable && !live.switchingFormat)
        }
    }
    if (landscape) {
        Column(modifier.padding(horizontal = 4.dp, vertical = 8.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            shutter(); filter()
            TetherButton("图库", { requestExit(TetherExitDestination.Gallery) }, Modifier.fillMaxWidth(), enabled = !(live.isVideoMode && live.captureBusy))
            TetherButton("分辨率", onFormats, Modifier.fillMaxWidth())
        }
    } else {
        Row(modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            TetherButton("图库", { requestExit(TetherExitDestination.Gallery) }, enabled = !(live.isVideoMode && live.captureBusy)); filter(); shutter()
            TetherButton("分辨率", onFormats)
        }
    }
}

@Composable
private fun TetherFormatDialog(show: Boolean, state: TetherUiState, onAction: (TetherUiAction) -> Unit, onDismiss: () -> Unit) {
    val live = state.liveView
    OverlayDialog(show = show, onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("预览分辨率", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = MiuixTheme.colorScheme.onSurface)
            TetherResolutionDropdown(state, onAction)
            Text("选项来自相机的预览格式枚举，不影响拍摄文件分辨率。", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            live.receivedDimensions?.let { Text("当前接收画面：$it", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
            Text("预览滤镜目标帧率", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(10, 15, 30).forEach { fps ->
                TetherButton("$fps fps", { onAction(TetherUiAction.SetTargetFps(fps)) }, Modifier.weight(1f), primary = live.targetFps == fps)
            } }
            Text("相机接收帧率与滤镜处理帧率分别统计。所有预览分辨率均可使用滤镜；处理不及时会跳过旧帧并提示一次，滤镜保持开启。", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            live.processedFps?.let { Text(String.format(java.util.Locale.ROOT, "滤镜处理 %.1f fps", it), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
            TetherButton("完成", onDismiss, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun TetherResolutionDropdown(state: TetherUiState, onAction: (TetherUiAction) -> Unit) {
    val live = state.liveView
    val selected = live.selectedFormat
    val unavailableReason = state.liveViewFormatUnavailableReason()
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(unavailableReason) { if (unavailableReason != null) expanded = false }
    Box(Modifier.fillMaxWidth()) {
        TetherButton(selected?.dimensions?.let { "$it ▾" } ?: "选择相机分辨率 ▾", { expanded = true },
            Modifier.fillMaxWidth().semantics { contentDescription = "预览分辨率 ${selected?.dimensions ?: "尚未选择"}" },
            enabled = unavailableReason == null)
        OverlayListPopup(show = expanded && unavailableReason == null, onDismissRequest = { expanded = false },
            alignment = PopupPositionProvider.Align.End) {
            ListPopupColumn {
                live.formats.forEachIndexed { position, format ->
                    DropdownImpl(text = "${format.dimensions} · ${format.label}", optionSize = live.formats.size,
                        isSelected = format.index == live.selectedFormatIndex, index = position,
                        dropdownColors = DropdownDefaults.dropdownColors(),
                        onSelectedIndexChange = {
                            expanded = false
                            if (unavailableReason == null && format.index != live.selectedFormatIndex) {
                                onAction(TetherUiAction.SetLiveViewFormat(format.index))
                            }
                        })
                }
            }
        }
    }
    unavailableReason?.let { Text(it, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
    selected?.note?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
}
