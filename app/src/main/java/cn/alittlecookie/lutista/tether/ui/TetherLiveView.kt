package cn.alittlecookie.lutista.tether.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Filter
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val LiveAspectRatio = 1.4817f

private val ExposureKeys = listOf(
    TetherParameterKey.Aperture,
    TetherParameterKey.Shutter,
    TetherParameterKey.Iso,
    TetherParameterKey.ExposureCompensation,
)

private fun TetherParameterKey.label(): String = when (this) {
    TetherParameterKey.Iso -> "ISO"
    TetherParameterKey.Shutter -> "快门"
    TetherParameterKey.ExposureCompensation -> "EV"
    TetherParameterKey.Aperture -> "光圈"
    TetherParameterKey.WhiteBalance -> "白平衡"
}

private sealed interface LivePicker {
    data class Param(val key: TetherParameterKey) : LivePicker
    data object Format : LivePicker
    data object PhotoStyle : LivePicker
    data object TargetFps : LivePicker
}

@Composable
internal fun TetherLiveView(
    state: TetherUiState,
    onAction: (TetherUiAction) -> Unit,
    requestExit: (TetherExitDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val live = state.liveView
    var picker by remember { mutableStateOf<LivePicker?>(null) }
    // 取景是纯黑背景：状态栏 / 导航栏图标必须转成浅色，退出时恢复。
    UseLightSystemBarIcons()
    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black)) {
        val spec = TetherLayoutSpec.fromSize(maxWidth.value, maxHeight.value)
        if (spec.landscape) {
            LiveLandscape(state, onAction, requestExit) { picker = it }
        } else {
            LivePortrait(state, onAction, requestExit) { picker = it }
        }
    }
    LivePickerDialogs(state, picker, onAction) { picker = null }
}

/* ---------------------------------------------------------------- portrait */

@Composable
private fun LivePortrait(
    state: TetherUiState,
    onAction: (TetherUiAction) -> Unit,
    requestExit: (TetherExitDestination) -> Unit,
    openPicker: (LivePicker) -> Unit,
) {
    val live = state.liveView
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // 面板三档高度与原型一致：折叠 / 半展 / 全展。
    val heights = TetherPortraitPanelHeights
    val fullHeight = heights[2].dp
    val density = LocalDensity.current
    val closedOffsetPx = with(density) { (heights[2] - heights[0]).dp.toPx() }
    val peekOffsetPx = with(density) { (heights[2] - heights[1]).dp.toPx() }
    // offsetPx 表示面板相对「全展」向下平移的距离：0 = 全展，closed = 折叠。
    var offsetPx by rememberSaveable { mutableFloatStateOf(peekOffsetPx) }
    var stage by rememberSaveable { mutableIntStateOf(1) }
    val settle = remember { Animatable(peekOffsetPx) }
    val scope = rememberCoroutineScope()
    val currentOffset = if (settle.isRunning || settle.targetValue != settle.value) settle.value else offsetPx
    // 画面给「已吸附」的面板留位，避免展开时被面板压住；拖动过程中面板浮在画面上。
    val reserved by animateDpAsState(heights[stage.coerceIn(0, 2)].dp, tween(durationMillis = 220))

    fun settleTo(target: Float) {
        offsetPx = target
        stage = when (target) {
            closedOffsetPx -> 0
            peekOffsetPx -> 1
            else -> 2
        }
        scope.launch {
            settle.snapTo(target)
            settle.animateTo(target, spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow))
        }
    }

    val dragState = rememberDraggableState { delta ->
        offsetPx = (offsetPx + delta).coerceIn(0f, closedOffsetPx)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(start = 4.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { requestExit(TetherExitDestination.Back) }) {
                    Icon(MiuixIcons.Back, contentDescription = "退出实时预览", tint = Color.White)
                }
                Column(Modifier.weight(1f).padding(start = 2.dp)) {
                    Text("实时预览", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    Text(
                        liveSubtitle(state),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (live.isRecording) Color(0xFFFF6B60) else Color.White.copy(alpha = .6f),
                    )
                }
                state.batteryPercent?.let {
                    Text("$it%", fontSize = 12.sp, color = Color.White.copy(alpha = .6f))
                }
            }
            // 预览区与底部面板共用同一个容器：面板被裁剪在这里，永远不会压住下面的
            // 模式行和快门工具条，折叠时只露出面板顶部的一小条。
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                BoxWithConstraints(
                    Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, bottom = reserved),
                    contentAlignment = Alignment.Center,
                ) {
                    LiveFrame(state, onAction, fitModifier(maxWidth, maxHeight))
                }
                Column(
                    Modifier.align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(fullHeight)
                        .offset { IntOffset(0, currentOffset.roundToInt()) }
                        .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                        .background(MiuixTheme.colorScheme.surface)
                        .draggable(
                            state = dragState,
                            orientation = Orientation.Vertical,
                            onDragStopped = { velocity ->
                                val up = velocity < -900f
                                val down = velocity > 900f
                                val target = when {
                                    up -> if (offsetPx > peekOffsetPx + 1f) peekOffsetPx else 0f
                                    down -> if (offsetPx < peekOffsetPx - 1f) peekOffsetPx else closedOffsetPx
                                    else -> listOf(0f, peekOffsetPx, closedOffsetPx).minBy { abs(it - offsetPx) }
                                }
                                settleTo(target)
                            },
                        ),
                ) {
                    Box(Modifier.fillMaxWidth().height(22.dp), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp))
                                .background(MiuixTheme.colorScheme.onSurface.copy(alpha = .2f)),
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        QuickValue("ISO", live.readout(TetherParameterKey.Iso, state), Modifier.weight(1f)) { openPicker(LivePicker.Param(TetherParameterKey.Iso)) }
                        QuickValue("快门", live.readout(TetherParameterKey.Shutter, state), Modifier.weight(1f)) { openPicker(LivePicker.Param(TetherParameterKey.Shutter)) }
                        QuickValue("EV", live.readout(TetherParameterKey.ExposureCompensation, state), Modifier.weight(1f)) { openPicker(LivePicker.Param(TetherParameterKey.ExposureCompensation)) }
                        QuickValue("白平衡", live.readout(TetherParameterKey.WhiteBalance, state), Modifier.weight(1f)) { openPicker(LivePicker.Param(TetherParameterKey.WhiteBalance)) }
                    }
                    Column(Modifier.weight(1f)) {
                        TabsRow(tab) { tab = it }
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                            when (tab) {
                                0 -> ExposurePane(state, onAction, openPicker)
                                1 -> ColorPane(state, onAction, openPicker)
                                else -> MorePane(state, onAction, openPicker)
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    state.parameters.modeLabel,
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, Color.White.copy(alpha = .28f), RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = .08f))
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Text(
                    if (live.isRecording) "录制中 · ${formatTetherTime(live.recordingElapsedMillis)}"
                    else "请拨动相机转盘 · 上拉面板调参数",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = .48f),
                )
            }
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().height(104.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LiveSwitchColumn(state, onAction, Modifier.width(96.dp).fillMaxHeight())
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    ShutterButton(state, onAction, size = 72.dp)
                }
                // 竖屏不提供相册入口：右侧与左侧胶囊列同尺寸、同排布地放分辨率档位。
                LiveFormatStack(
                    state, onAction,
                    modifier = Modifier.width(96.dp).fillMaxHeight(),
                    align = Alignment.End,
                    boxed = true,
                    pillFontSize = 10.5.sp,
                )
            }
        }

    }
}

/* --------------------------------------------------------------- landscape */

@Composable
private fun LiveLandscape(
    state: TetherUiState,
    onAction: (TetherUiAction) -> Unit,
    requestExit: (TetherExitDestination) -> Unit,
    openPicker: (LivePicker) -> Unit,
) {
    val live = state.liveView
    HideSystemBarsInLandscape()
    Row(Modifier.fillMaxSize().background(Color.Black)) {
        Column(
            Modifier.width(TetherLayoutSpec.LEFT_RAIL_WIDTH_DP.dp).fillMaxHeight().background(Color(0xFF111114)).padding(horizontal = 6.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            IconButton(onClick = { requestExit(TetherExitDestination.Back) }, modifier = Modifier.fillMaxWidth().height(32.dp)) {
                Icon(MiuixIcons.Back, contentDescription = "退出实时预览", tint = Color.White.copy(alpha = .85f))
            }
            Box(
                Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(9.dp))
                    .background(Color.White.copy(alpha = .1f))
                    .border(1.dp, Color.White.copy(alpha = .22f), RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(state.parameters.modeLabel, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            RailTool("光圈", live.readout(TetherParameterKey.Aperture, state), Modifier, enabled = false) {}
            RailTool("快门", live.readout(TetherParameterKey.Shutter, state), Modifier,
                enabled = live.shutterWritable(state)) { openPicker(LivePicker.Param(TetherParameterKey.Shutter)) }
            RailTool("ISO", live.readout(TetherParameterKey.Iso, state), Modifier) { openPicker(LivePicker.Param(TetherParameterKey.Iso)) }
            RailTool("EV", live.readout(TetherParameterKey.ExposureCompensation, state), Modifier) {
                openPicker(LivePicker.Param(TetherParameterKey.ExposureCompensation))
            }
            RailTool("白平衡", live.readout(TetherParameterKey.WhiteBalance, state), Modifier) {
                openPicker(LivePicker.Param(TetherParameterKey.WhiteBalance))
            }
            RailTool("格调", state.parameters.photoStyle?.toString() ?: "—", Modifier) { openPicker(LivePicker.PhotoStyle) }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            LiveFrame(state, onAction, fitModifier(maxWidth, maxHeight))
            Text(
                liveSubtitle(state),
                Modifier.align(Alignment.BottomStart).padding(10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = .5f))
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = Color.White.copy(alpha = .86f),
            )
        }
        Column(
            Modifier.width(TetherLayoutSpec.RIGHT_RAIL_WIDTH_DP.dp).fillMaxHeight().background(Color(0xFF111114)).padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ShutterButton(state, onAction, size = 60.dp)
            IconButton(
                onClick = { onAction(TetherUiAction.SetPreviewFilter(!live.filterEnabled)) },
                modifier = Modifier.size(36.dp),
                enabled = live.filterAvailable,
                backgroundColor = if (live.filterEnabled) MiuixTheme.colorScheme.primary else Color.White.copy(alpha = .1f),
            ) {
                Icon(MiuixIcons.Filter, contentDescription = "预览滤镜", tint = Color.White, modifier = Modifier.size(18.dp))
            }
            LiveSwitchPill("回传", live.autoDownload, Modifier.fillMaxWidth(), enabled = live.autoDownloadAvailable) {
                onAction(TetherUiAction.SetAutoDownload(!live.autoDownload))
            }
            LiveSwitchPill("直出", state.directFilter, Modifier.fillMaxWidth(), enabled = state.directFilterAvailable) {
                onAction(TetherUiAction.SetDirectFilter(!state.directFilter))
            }
            Spacer(Modifier.weight(1f))
            LiveFormatStack(state, onAction, modifier = Modifier.fillMaxWidth(), align = Alignment.CenterHorizontally, pillHeight = 24.dp, pillFontSize = 9.sp)
        }
    }
}

/* ------------------------------------------------------------------ pieces */

@Composable
private fun LiveFrame(state: TetherUiState, onAction: (TetherUiAction) -> Unit, modifier: Modifier) {
    val live = state.liveView
    Box(
        modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFF0A0A0C)),
        contentAlignment = Alignment.Center,
    ) {
        if (live.image != null) {
            TetherImageView(live.image, "实时取景画面", Modifier.fillMaxSize())
        }
        when {
            live.starting && live.image == null -> LiveStatus("正在启动实时预览…", busy = true)
            live.switchingFormat -> LiveStatus("正在切换预览分辨率…", busy = true)
            live.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(live.error, fontSize = 12.sp, color = Color.White.copy(alpha = .8f))
                TextButton("重试", { onAction(TetherUiAction.RetryLiveView) })
            }
            !live.running && live.image == null -> LiveStatus("实时预览未启动")
            live.frozen -> LiveStatus("画面已冻结")
        }
        if (live.filterEnabled && live.filterAvailable) {
            Box(Modifier.fillMaxSize().background(Color(0x143482FF)))
        }
        if (live.isRecording) {
            Row(
                Modifier.align(Alignment.TopStart).padding(10.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color.Black.copy(alpha = .55f))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFFFF3B30)))
                Text(formatTetherTime(live.recordingElapsedMillis), fontSize = 10.sp, color = Color.White)
            }
        }
    }
}

@Composable
private fun LiveStatus(text: String, busy: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (busy) CircularProgressIndicator(modifier = Modifier.size(24.dp))
        Text(text, fontSize = 12.sp, color = Color.White.copy(alpha = .72f))
    }
}

@Composable
private fun ShutterButton(state: TetherUiState, onAction: (TetherUiAction) -> Unit, size: androidx.compose.ui.unit.Dp) {
    val live = state.liveView
    val enabled = !live.captureBusy && state.capabilities.let { caps ->
        (live.isVideoMode && caps.canRecord) || (!live.isVideoMode && caps.canCapture)
    }
    Box(
        Modifier.size(size).clip(CircleShape)
            .border(3.dp, Color.White.copy(alpha = if (enabled) .9f else .4f), CircleShape)
            .background(Color.White.copy(alpha = .12f))
            .clickable(enabled = enabled) {
                if (live.isVideoMode) onAction(TetherUiAction.ToggleRecording) else onAction(TetherUiAction.Capture)
            },
        contentAlignment = Alignment.Center,
    ) {
        if (live.captureBusy) {
            CircularProgressIndicator(modifier = Modifier.size(size / 2))
        } else if (live.isRecording) {
            Box(Modifier.size(size * .34f).clip(RoundedCornerShape(size * .1f)).background(Color(0xFFFF3B30)))
        } else {
            Box(Modifier.size(size * .74f).clip(CircleShape).background(Color.White.copy(alpha = .92f)))
        }
    }
}

@Composable
private fun LiveSwitchColumn(state: TetherUiState, onAction: (TetherUiAction) -> Unit, modifier: Modifier) {
    val live = state.liveView
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = .08f)).padding(3.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        LiveSwitchPill("回传", live.autoDownload, Modifier.fillMaxWidth().weight(1f), enabled = live.autoDownloadAvailable, compact = true) {
            onAction(TetherUiAction.SetAutoDownload(!live.autoDownload))
        }
        LiveSwitchPill("滤镜预览", live.filterEnabled, Modifier.fillMaxWidth().weight(1f), enabled = live.filterAvailable, compact = true) {
            onAction(TetherUiAction.SetPreviewFilter(!live.filterEnabled))
        }
        LiveSwitchPill("滤镜直出", state.directFilter, Modifier.fillMaxWidth().weight(1f), enabled = state.directFilterAvailable, compact = true) {
            onAction(TetherUiAction.SetDirectFilter(!state.directFilter))
        }
    }
}

@Composable
private fun LiveSwitchPill(
    text: String,
    on: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .height(if (compact) 20.dp else 26.dp)
            .clip(RoundedCornerShape(if (compact) 10.dp else 13.dp))
            .background(if (on) MiuixTheme.colorScheme.primary else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = if (compact) 10.5.sp else 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = when {
                !enabled -> Color.White.copy(alpha = .4f)
                on -> Color.White
                else -> Color.White.copy(alpha = .74f)
            },
        )
    }
}

@Composable
private fun RailTool(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp))
            .background(Color.White.copy(alpha = .06f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Text(label, fontSize = 9.sp, color = Color.White.copy(alpha = if (enabled) .5f else .3f), lineHeight = 11.sp)
        Text(
            value,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = Color.White.copy(alpha = if (enabled) 1f else .42f),
        )
    }
}

@Composable
private fun QuickValue(label: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick).padding(vertical = 6.dp)) {
        Text(label, fontSize = 9.5.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(
            value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = MiuixTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun TabsRow(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.secondaryContainer)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        listOf("曝光", "色彩", "更多").forEachIndexed { index, title ->
            Text(
                title,
                Modifier.weight(1f).clip(RoundedCornerShape(9.dp))
                    .background(if (selected == index) MiuixTheme.colorScheme.surface else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(vertical = 6.dp),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                color = if (selected == index) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun ExposurePane(state: TetherUiState, onAction: (TetherUiAction) -> Unit, openPicker: (LivePicker) -> Unit) {
    val live = state.liveView
    SectionLabel("档位 · 请拨动相机转盘")
    Text(
        state.parameters.modeLabel + (state.parameters.exposureSubMode?.let { " · $it" } ?: ""),
        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MiuixTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 2.dp),
    )
    ParamRow("ISO", live.readout(TetherParameterKey.Iso, state)) { openPicker(LivePicker.Param(TetherParameterKey.Iso)) }
    ParamRow(
        "快门", live.readout(TetherParameterKey.Shutter, state),
        enabled = live.shutterWritable(state),
        valueColor = if (live.shutterWritable(state)) null else MiuixTheme.colorScheme.onSurfaceVariantSummary,
    ) { openPicker(LivePicker.Param(TetherParameterKey.Shutter)) }
    if (!live.shutterWritable(state)) {
        Text(
            if (live.isVideoMode) "录像时由相机决定快门" else "当前档位由相机自动决定快门",
            fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
    ParamRow("曝光补偿", live.readout(TetherParameterKey.ExposureCompensation, state)) {
        openPicker(LivePicker.Param(TetherParameterKey.ExposureCompensation))
    }
    ParamRow("光圈", live.readout(TetherParameterKey.Aperture, state), enabled = false)
}

@Composable
private fun ColorPane(state: TetherUiState, onAction: (TetherUiAction) -> Unit, openPicker: (LivePicker) -> Unit) {
    val live = state.liveView
    ParamRow("白平衡", live.readout(TetherParameterKey.WhiteBalance, state)) {
        openPicker(LivePicker.Param(TetherParameterKey.WhiteBalance))
    }
    ParamRow(
        "照片格调", state.parameters.photoStyle?.toString() ?: "—",
    ) { openPicker(LivePicker.PhotoStyle) }
    if (state.parameters.styleChanged) {
        TextButton("恢复进入取景前的格调", { onAction(TetherUiAction.RestorePhotoStyle) }, modifier = Modifier.fillMaxWidth())
    }
    Text(
        "格调由相机侧应用，实时预览画面不会随格调变化。",
        fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun MorePane(state: TetherUiState, onAction: (TetherUiAction) -> Unit, openPicker: (LivePicker) -> Unit) {
    val live = state.liveView
    SectionLabel("预览分辨率")
    LiveFormatStack(state, onAction, modifier = Modifier.fillMaxWidth(), align = Alignment.Start, pillHeight = 34.dp, pillFontSize = 12.sp, onDark = false, openPicker = openPicker)
    live.formats.getOrNull(live.selectedFormatIndex)?.let { format ->
        Text(
            listOfNotNull(format.dimensions, format.note).joinToString(" · "),
            fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    Text(
        "所有预览分辨率均可使用滤镜。",
        fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
    ParamRow("目标帧率", "${live.targetFps} fps") { openPicker(LivePicker.TargetFps) }
    SectionLabel("回传")
    ToggleRow("拍摄后自动回传 JPEG", live.autoDownload, live.autoDownloadAvailable) {
        onAction(TetherUiAction.SetAutoDownload(!live.autoDownload))
    }
    live.autoDownloadReason?.let {
        Text(it, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
    ToggleRow("滤镜直出", state.directFilter, state.directFilterAvailable) {
        onAction(TetherUiAction.SetDirectFilter(!state.directFilter))
    }
    Text(
        if (state.directFilter) "回传的照片会按主页全局滤镜另存一张成品到主页输出文件夹。"
        else "只回传相机原片，不套滤镜。",
        fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
    state.directFilterReason?.let { Text(it, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
    state.cameraDetails?.let {
        SectionLabel("相机")
        Text(it, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun LiveFormatStack(
    state: TetherUiState,
    onAction: (TetherUiAction) -> Unit,
    modifier: Modifier = Modifier,
    align: Alignment.Horizontal = Alignment.Start,
    pillHeight: Dp = 24.dp,
    pillFontSize: TextUnit = 9.sp,
    boxed: Boolean = false,
    onDark: Boolean = true,
    openPicker: ((LivePicker) -> Unit)? = null,
) {
    val live = state.liveView
    val canSwitch = state.capabilities.canSwitchLiveViewFormat && !live.switchingFormat && !live.isRecording
    val shape = RoundedCornerShape(if (boxed) 10.dp else 8.dp)
    // 三档时与外层胶囊列同样按权重均分，档位少时退回固定高度并贴底，避免单档被拉成一整条。
    val distribute = boxed && live.formats.size == 3
    Column(
        modifier
            .then(
                if (boxed) {
                    Modifier.clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = .08f)).padding(3.dp)
                } else {
                    Modifier
                },
            ),
        horizontalAlignment = align,
        verticalArrangement = if (distribute) Arrangement.spacedBy(2.dp) else Arrangement.spacedBy(3.dp, Alignment.Bottom),
    ) {
        live.formats.forEachIndexed { index, format ->
            val selected = index == live.selectedFormatIndex
            val rowModifier = if (distribute) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().height(pillHeight)
            Box(
                rowModifier
                    .clip(shape)
                    .background(
                        when {
                            selected && onDark -> Color.White.copy(alpha = .92f)
                            selected -> MiuixTheme.colorScheme.primary
                            else -> Color.Transparent
                        },
                    )
                    .then(
                        if (selected) Modifier else Modifier.border(
                            1.dp,
                            if (onDark) Color.White.copy(alpha = .16f) else MiuixTheme.colorScheme.onSurface.copy(alpha = .16f),
                            shape,
                        ),
                    )
                    .clickable(enabled = canSwitch) { onAction(TetherUiAction.SetLiveViewFormat(index)) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    format.label,
                    fontSize = pillFontSize,
                    fontWeight = if (boxed) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    color = when {
                        selected && onDark -> Color(0xFF111111)
                        selected -> Color.White
                        onDark -> Color.White.copy(alpha = if (canSwitch) .8f else .4f)
                        else -> MiuixTheme.colorScheme.onSurface.copy(alpha = if (canSwitch) .8f else .4f)
                    },
                )
            }
        }
    }
}

@Composable
private fun ParamRow(
    label: String,
    value: String,
    enabled: Boolean = true,
    valueColor: Color? = null,
    onClick: () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface)
        Spacer(Modifier.weight(1f))
        Text(
            value, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            color = valueColor ?: if (enabled) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun ToggleRow(label: String, on: Boolean, available: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = available, onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Box(
            Modifier.size(width = 40.dp, height = 24.dp).clip(RoundedCornerShape(12.dp))
                .background(
                    when {
                        !available -> MiuixTheme.colorScheme.secondaryContainer
                        on -> MiuixTheme.colorScheme.primary
                        else -> MiuixTheme.colorScheme.secondaryContainer
                    },
                ),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                Modifier.padding(2.dp).size(20.dp).clip(CircleShape)
                    .background(if (on) Color.White else MiuixTheme.colorScheme.surface),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        Modifier.padding(top = 12.dp, bottom = 2.dp),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}

/* ----------------------------------------------------------------- pickers */

@Composable
private fun LivePickerDialogs(
    state: TetherUiState,
    picker: LivePicker?,
    onAction: (TetherUiAction) -> Unit,
    dismiss: () -> Unit,
) {
    val live = state.liveView
    when (picker) {
        null -> Unit
        is LivePicker.Param -> {
            val parameter = state.parameters.parameter(picker.key)
            PickerDialog(
                title = picker.key.label(),
                options = parameter.options.map { it.value.toString() to it.label },
                selected = parameter.value?.toString(),
                onSelect = { onAction(TetherUiAction.SetParameter(picker.key, it.toLongOrNull() ?: 0L)); dismiss() },
                onDismiss = dismiss,
            )
        }
        LivePicker.Format -> PickerDialog(
            title = "预览分辨率",
            options = live.formats.mapIndexed { index, format -> index.toString() to format.label },
            selected = live.selectedFormatIndex.toString(),
            onSelect = { onAction(TetherUiAction.SetLiveViewFormat(it.toIntOrNull() ?: 0)); dismiss() },
            onDismiss = dismiss,
        )
        LivePicker.PhotoStyle -> PickerDialog(
            title = "照片格调",
            options = state.parameters.photoStyleValues.map { it.toString() to "格调 $it" },
            selected = state.parameters.photoStyle?.toString() ?: "",
            onSelect = { onAction(TetherUiAction.SetPhotoStyle(it.toLongOrNull() ?: 0L)); dismiss() },
            onDismiss = dismiss,
        )
        LivePicker.TargetFps -> PickerDialog(
            title = "目标帧率",
            options = listOf("10" to "10 fps", "15" to "15 fps", "30" to "30 fps"),
            selected = live.targetFps.toString(),
            onSelect = { onAction(TetherUiAction.SetTargetFps(it.toIntOrNull() ?: 15)); dismiss() },
            onDismiss = dismiss,
        )
    }
}

@Composable
private fun PickerDialog(
    title: String,
    options: List<Pair<String, String>>,
    selected: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    OverlayDialog(show = true, title = title, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
            options.forEach { (value, label) ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .clickable { onSelect(value) }
                        .padding(horizontal = 4.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        label, fontSize = 15.sp,
                        color = if (value == selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (value == selected) {
                        Box(Modifier.size(18.dp).clip(CircleShape).background(MiuixTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                            Text("✓", fontSize = 11.sp, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ helpers */

private fun liveSubtitle(state: TetherUiState): String {
    val live = state.liveView
    val parts = buildList {
        state.cameraName?.let(::add)
        live.receivedDimensions?.let(::add)
        (live.receivedFps ?: live.processedFps)?.let { add("$it fps") }
        live.selectedFormat?.note?.let(::add)
        if (live.isRecording) add("录制中 ${formatTetherTime(live.recordingElapsedMillis)}")
    }
    return parts.joinToString(" · ").ifBlank { "等待相机画面" }
}

/** 参数读数：优先取 parameters，其次退回 liveView 的格式信息。 */
private fun TetherLiveViewUi.readout(key: TetherParameterKey, state: TetherUiState): String =
    state.parameters.parameter(key).displayText

private fun TetherLiveViewUi.shutterWritable(state: TetherUiState): Boolean =
    state.parameters.parameter(TetherParameterKey.Shutter).enabled

/** 横屏取景时沉浸：隐藏状态栏与导航栏，退出取景即恢复。 */
@Composable
private fun HideSystemBarsInLandscape() {
    val view = LocalView.current
    DisposableEffect(view) {
        val activity = view.context.findActivity()
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/** 黑色取景界面：系统栏图标用浅色；离开取景时恢复调用前的取值。 */
@Composable
private fun UseLightSystemBarIcons() {
    val view = LocalView.current
    DisposableEffect(view) {
        val controller = view.context.findActivity()?.window?.let { WindowCompat.getInsetsController(it, view) }
        val previousStatus = controller?.isAppearanceLightStatusBars
        val previousNavigation = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            if (previousStatus != null && controller != null) controller.isAppearanceLightStatusBars = previousStatus
            if (previousNavigation != null && controller != null) controller.isAppearanceLightNavigationBars = previousNavigation
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** 在给定空间内按 3:2 等比铺满，尽量放大取景画面且不裁切。 */
private fun fitModifier(maxWidth: androidx.compose.ui.unit.Dp, maxHeight: androidx.compose.ui.unit.Dp): Modifier =
    if (maxWidth.value / maxHeight.value > LiveAspectRatio) {
        Modifier.height(maxHeight).aspectRatio(LiveAspectRatio)
    } else {
        Modifier.width(maxWidth).aspectRatio(LiveAspectRatio)
    }
