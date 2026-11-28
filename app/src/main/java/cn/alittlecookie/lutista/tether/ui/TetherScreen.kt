package cn.alittlecookie.lutista.tether.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.alittlecookie.lutista.ui.theme.getAppBarColor
import cn.alittlecookie.lutista.ui.theme.lutistaBlurEffect
import cn.alittlecookie.lutista.ui.theme.rememberLutistaBlurBackdrop
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Stateless camera UI. Repository/Route exclusively owns camera/USB lifetime and side effects.
 * Recomposition, resizing and image-view attachment only alter presentation.
 */
@Composable
fun TetherScreen(state: TetherUiState, onAction: (TetherUiAction) -> Unit, onBack: () -> Unit) {
    // The content owns safe-drawing insets; Scaffold supplies the host required by Miuix overlays.
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MiuixTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { _ ->
        TetherScreenContent(state, onAction, onBack)
    }
}

@Composable
private fun TetherScreenContent(state: TetherUiState, onAction: (TetherUiAction) -> Unit, onBack: () -> Unit) {
    val backdrop = rememberLutistaBlurBackdrop(state.useBlur)
    val barColor = backdrop.getAppBarColor()
    var pendingExit by rememberSaveable { mutableStateOf<String?>(null) }
    var restoreStyle by rememberSaveable { mutableStateOf(false) }
    var recordingNoticeAcknowledged by rememberSaveable { mutableStateOf(false) }
    var showRecordingNotice by rememberSaveable { mutableStateOf(false) }
    var showCameraInfo by remember { mutableStateOf(false) }
    LaunchedEffect(state.liveView.isVideoMode, state.page) {
        if (state.page == TetherPage.LiveView && state.liveView.isVideoMode && !recordingNoticeAcknowledged) showRecordingNotice = true
    }
    val requestExit: (TetherExitDestination) -> Unit = { destination ->
        // The recording toggle is still in flight: do not race it with stream stop/navigation.
        if (!(state.liveView.isVideoMode && state.liveView.captureBusy)) {
            if (state.liveView.isRecording || state.parameters.styleChanged) { pendingExit = destination.name; restoreStyle = false }
            else onAction(TetherUiAction.ExitLiveView(destination))
        }
    }
    val requestBack: () -> Unit = {
        when (state.page) {
            TetherPage.Gallery -> if (state.gallery.selectionMode) onAction(TetherUiAction.SetSelectionMode(false)) else onBack()
            TetherPage.Preview -> onAction(TetherUiAction.OpenGallery)
            TetherPage.LiveView -> requestExit(TetherExitDestination.Back)
        }
    }
    BackHandler {
        when {
            pendingExit != null -> pendingExit = null
            showRecordingNotice -> { showRecordingNotice = false; recordingNoticeAcknowledged = true }
            state.dialog != null -> onAction(TetherUiAction.DismissDialog)
            else -> requestBack()
        }
    }
    Column(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background).safeDrawingPadding()) {
        if (state.page != TetherPage.LiveView) {
            Row(Modifier.fillMaxWidth().lutistaBlurEffect(backdrop).background(barColor).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TetherButton("返回", requestBack)
                Text(if (state.page == TetherPage.Preview) "文件预览" else "联机模式", Modifier.weight(1f), fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold, color = MiuixTheme.colorScheme.onSurface)
                TetherButton("相机", { showCameraInfo = true })
                if (state.capabilities.canLiveView) TetherButton("实时预览", { onAction(TetherUiAction.OpenLiveView) }, enabled = state.connected && !state.busy)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().then(backdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier)) {
            when (state.page) {
                TetherPage.Gallery -> TetherGallery(state, onAction)
                TetherPage.Preview -> TetherPreview(state, onAction)
                TetherPage.LiveView -> TetherLiveView(state, onAction, requestExit)
            }
        }
        state.backgroundConnectionNotice?.let { TetherNotice(it, Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
    }
    TetherConnectionDialog(state.dialog, onAction)
    TetherTransferDialog(state.transfer, onAction)
    pendingExit?.let { destinationName ->
        val destination = TetherExitDestination.valueOf(destinationName)
        TetherConfirmDialog(
            if (state.liveView.isRecording) "停止录像后退出？" else "离开实时预览？",
            if (state.liveView.isRecording) "退出会先停止本次 App 发起的录像，再关闭取景。" else "照片格调已更改。可选择恢复进入取景前的值。",
            confirm = if (state.liveView.isRecording) "停止并退出" else "退出",
            dismiss = "继续取景",
            onConfirm = {
                pendingExit = null
                onAction(if (state.liveView.isRecording) TetherUiAction.StopRecordingAndExit(destination, restoreStyle)
                    else TetherUiAction.ExitLiveView(destination, restoreStyle))
            },
            onDismiss = { pendingExit = null },
            extra = if (state.parameters.styleChanged) ({
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("恢复原照片格调", Modifier.weight(1f), fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface)
                    Switch(checked = restoreStyle, onCheckedChange = { restoreStyle = it })
                }
            }) else null,
        )
    }
    if (showRecordingNotice) TetherConfirmDialog(
        "录像状态提示", "此处仅跟踪本次 App 发起的录像。相机物理按键开始或停止的录像，App 可能无法获知。",
        confirm = "知道了", dismiss = "关闭",
        onConfirm = { showRecordingNotice = false; recordingNoticeAcknowledged = true },
        onDismiss = { showRecordingNotice = false; recordingNoticeAcknowledged = true },
    )
    if (showCameraInfo) OverlayDialog(show = true, onDismissRequest = { showCameraInfo = false }) {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(state.cameraName ?: "相机信息", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = MiuixTheme.colorScheme.onSurface)
            Text(state.cameraDetails ?: state.connectionSummary, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            state.capabilities.explanations.forEach { Text(it, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
            state.gallery.storages.forEach { storage ->
                Text(listOfNotNull(storage.label, storage.itemCount?.let { "$it 项" }, storage.usedBytes?.let { "已枚举文件 ${formatTetherBytes(it)}" }).joinToString(" · "), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            TetherButton("关闭", { showCameraInfo = false }, Modifier.fillMaxWidth())
        }
    }
}

/** Also rendered above Home by MainScreen so enabling with no USB never silently fails. */
@Composable
fun TetherConnectionDialog(dialog: TetherDialogUi?, onAction: (TetherUiAction) -> Unit) {
    if (dialog == null) return
    OverlayDialog(show = true, onDismissRequest = { onAction(TetherUiAction.DismissDialog) }) {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(dialog.title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = MiuixTheme.colorScheme.onSurface)
            Text(dialog.message, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            if (dialog.connectionGuide != null) {
                Text("1  拔下 USB 数据线\n2  在相机中选择 PC(Tether)\n3  重新连接，并允许 USB 访问", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface)
                Text("PC(Storage)、MTP/PTP 不提供联机控制。确认后不会自动重试，请切换模式并重新插线。", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            if (dialog.retryFailedTransfers) TetherButton("重试失败项", { onAction(TetherUiAction.RetryFailedTransfers); onAction(TetherUiAction.DismissDialog) }, Modifier.fillMaxWidth())
            TetherButton("知道了", { onAction(TetherUiAction.DismissDialog) }, Modifier.fillMaxWidth(), primary = true)
        }
    }
}

/**
 * 下载进度只在对话框里呈现（用户要求「联机里的下载进度都改成对话框」）。
 *
 * 关掉对话框不会取消传输：传输由前台服务保活，重新进入联机界面还能看到进度；
 * 所以 `show` 只跟「还在下载」或「有失败」挂钩，点「关闭」只是清掉失败提示。
 */
@Composable
private fun TetherTransferDialog(transfer: TetherTransferUi, onAction: (TetherUiAction) -> Unit) {
    val active = transfer.active
    OverlayDialog(
        show = active || transfer.error != null,
        title = if (active) "正在下载原片" else "下载未完成",
        summary = "原片 → 输入文件夹；滤镜直出成品 → 输出文件夹",
        onDismissRequest = { if (!active) onAction(TetherUiAction.DismissTransferSummary) },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "已完成 ${transfer.completedCount}/${transfer.totalCount} · ${formatTetherBytes(transfer.bytesWritten)} / ${formatTetherBytes(transfer.totalBytes)}",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
            )
            LinearProgressIndicator(
                progress = if (transfer.totalBytes > 0) {
                    (transfer.bytesWritten.toFloat() / transfer.totalBytes).coerceIn(0f, 1f)
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                transfer.error ?: if (active) "切到后台后，连接和下载仍由通知保持。" else "可以继续挑选下一批。",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                if (active) {
                    TextButton("取消下载", { onAction(TetherUiAction.CancelTransfer) }, Modifier.weight(1f))
                } else {
                    TextButton("关闭", { onAction(TetherUiAction.DismissTransferSummary) }, Modifier.weight(1f))
                    TextButton(
                        text = "重试失败项",
                        onClick = { onAction(TetherUiAction.RetryFailedTransfers) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
    }
}
