package cn.alittlecookie.lutista.tether.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun TetherGallery(state: TetherUiState, onAction: (TetherUiAction) -> Unit, modifier: Modifier = Modifier) {
    val gallery = state.gallery
    var showDirectories by remember { mutableStateOf(false) }
    var requestId by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val gridState = rememberLazyGridState()
    val visibleItems = gallery.items.filter {
        (gallery.selectedStorageId == null || it.storageId == gallery.selectedStorageId) &&
            (gallery.selectedDirectoryHandle == null || it.directoryHandle == gallery.selectedDirectoryHandle)
    }

    LaunchedEffect(gridState, gallery.hasMore, gallery.loadingMore, visibleItems.size, gallery.selectedStorageId, gallery.selectedDirectoryHandle) {
        if (gallery.hasMore && !gallery.loadingMore) {
            snapshotFlow { (gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= visibleItems.size - 12 }
                .distinctUntilChanged().collect { nearEnd -> if (nearEnd) onAction(TetherUiAction.LoadMoreGallery) }
        }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val landscape = TetherLayoutSpec.fromSize(maxWidth.value, maxHeight.value).landscape
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(state.cameraName ?: "相机图库", fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.onSurface)
                    Text(
                        if (gallery.enumerating) "枚举中 ${gallery.enumeratedCount}/${gallery.totalCount ?: "—"}…"
                        else "${gallery.items.size} 项 · ${state.connectionSummary}",
                        fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                TetherButton(if (gallery.selectionMode) "取消" else "多选", {
                    onAction(TetherUiAction.SetSelectionMode(!gallery.selectionMode))
                }, enabled = gallery.items.isNotEmpty())
                if (!gallery.selectionMode) TetherButton("刷新", { onAction(TetherUiAction.RefreshGallery) }, enabled = state.connected && !gallery.enumerating)
            }
            if (gallery.storages.size > 1) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TetherButton("全部", { onAction(TetherUiAction.SelectStorage(null)) }, primary = gallery.selectedStorageId == null)
                    gallery.storages.forEach { storage ->
                        TetherButton(storage.label, { onAction(TetherUiAction.SelectStorage(storage.id)) }, primary = gallery.selectedStorageId == storage.id)
                    }
                }
            }
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val directory = gallery.directories.firstOrNull { it.handle == gallery.selectedDirectoryHandle }
                TetherButton(directory?.label ?: "所有目录", { showDirectories = true }, enabled = gallery.directories.isNotEmpty())
                Text(if (gallery.selectionMode) "已选 ${gallery.selectedHandles.size} 项" else "长按可多选", fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Spacer(Modifier.weight(1f))
                if (gallery.selectionMode) TetherButton("全选", { onAction(TetherUiAction.SelectAll) })
            }
            gallery.clockWarning?.let { warning ->
                TetherNotice(warning, Modifier.padding(horizontal = 12.dp), "知道了") { onAction(TetherUiAction.DismissClockWarning) }
            }
            gallery.directoryWarning?.let { warning ->
                TetherNotice(warning, Modifier.padding(horizontal = 12.dp))
            }
            gallery.error?.let { error ->
                if (state.connected) TetherNotice(error, Modifier.padding(horizontal = 12.dp), "重试") { onAction(TetherUiAction.RefreshGallery) }
                else TetherNotice("$error\n请重新接入 USB，并在相机上选择 PC(Tether)。", Modifier.padding(horizontal = 12.dp))
            }
            if (visibleItems.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when {
                        gallery.enumerating -> TetherBusy("正在读取相机文件…")
                        !state.connected -> TetherNotice("相机未连接。请接入 USB，并在相机上选择 PC(Tether)。", Modifier.padding(24.dp))
                        else -> Text("此目录暂无文件", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(if (landscape) 5 else 3),
                    state = gridState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(visibleItems, key = { it.handle }) { media ->
                        TetherMediaTile(media, gallery.selectionMode, media.handle in gallery.selectedHandles,
                            gallery.storages.firstOrNull { it.id == media.storageId }?.label.takeIf { gallery.storages.size > 1 },
                            onClick = {
                                if (gallery.selectionMode) onAction(TetherUiAction.ToggleSelection(media.handle))
                                else { requestId++; onAction(TetherUiAction.OpenPreview(media.handle, requestId)) }
                            },
                            onLongClick = {
                                if (!gallery.selectionMode) onAction(TetherUiAction.SetSelectionMode(true))
                                if (media.handle !in gallery.selectedHandles) onAction(TetherUiAction.ToggleSelection(media.handle))
                            },
                        )
                    }
                    if (gallery.hasMore || gallery.loadingMore) item(span = { GridItemSpan(maxLineSpan) }) {
                        TetherButton(if (gallery.loadingMore) "正在加载…" else "加载更多",
                            { onAction(TetherUiAction.LoadMoreGallery) }, Modifier.fillMaxWidth(), enabled = !gallery.loadingMore)
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "原片 → 主页「输入文件夹」；滤镜直出成品 → 主页「输出文件夹」。",
                    fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("滤镜直出", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurface)
                        Text(
                            if (state.directFilter) "回传或手动下载的原片，都会按主页全局滤镜/签名另存一张成品到主页输出文件夹（相册立即可见）。"
                            else "只回传相机原片，不套滤镜，也不会让主页重复处理。",
                            fontSize = 10.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        state.directFilterReason?.let { Text(it, fontSize = 10.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
                    }
                    Switch(checked = state.directFilter, onCheckedChange = { onAction(TetherUiAction.SetDirectFilter(it)) })
                }
                if (gallery.selectionMode) TetherButton(
                    "下载原片（${gallery.selectedHandles.size}）",
                    { onAction(TetherUiAction.Download(gallery.selectedHandles)) },
                    Modifier.fillMaxWidth(),
                    enabled = gallery.selectedHandles.isNotEmpty() && state.capabilities.canDownload,
                    primary = true,
                )
            }
        }
    }
    if (showDirectories) OverlayDialog(show = true, onDismissRequest = { showDirectories = false }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("相机目录", fontWeight = FontWeight.SemiBold, fontSize = 19.sp, color = MiuixTheme.colorScheme.onSurface)
            TetherButton("所有目录", { onAction(TetherUiAction.SelectDirectory(null)); showDirectories = false }, Modifier.fillMaxWidth(), primary = gallery.selectedDirectoryHandle == null)
            Column(Modifier.height(260.dp).fillMaxWidth()) {
                androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(gallery.directories.size) { index ->
                        val directory = gallery.directories[index]
                        TetherButton(directory.label, { onAction(TetherUiAction.SelectDirectory(directory.handle)); showDirectories = false }, Modifier.fillMaxWidth(), primary = directory.handle == gallery.selectedDirectoryHandle)
                    }
                }
            }
            TetherButton("取消", { showDirectories = false }, Modifier.fillMaxWidth())
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TetherMediaTile(
    media: TetherMediaUi,
    selectionMode: Boolean,
    selected: Boolean,
    storageLabel: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier.aspectRatio(1f).clip(shape)
            .background(MiuixTheme.colorScheme.secondaryContainer)
            .then(if (selected) Modifier.border(2.dp, MiuixTheme.colorScheme.primary, shape) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() })
            .semantics { contentDescription = "${media.name}，${media.originalLabel()}" },
    ) {
        if (media.thumbnail.bitmap != null || media.thumbnail.uri != null || media.thumbnail.source != null) {
            TetherImageView(media.thumbnail, media.name, Modifier.fillMaxSize())
        } else {
            Text(if (media.infoLoaded) when (media.kind) {
                TetherMediaKind.Raw -> "RAW"; TetherMediaKind.Video -> "视频"; else -> "缩略图"
            } else "读取中…", Modifier.align(Alignment.Center), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        storageLabel?.let { Text(it, Modifier.align(Alignment.TopStart).padding(5.dp).clip(RoundedCornerShape(5.dp)).background(Color.Black.copy(alpha = .6f)).padding(horizontal = 5.dp, vertical = 2.dp), fontSize = 10.sp, color = Color.White) }
        if (selectionMode) Checkbox(state = if (selected) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off, onClick = onClick, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp))
        if (media.kind == TetherMediaKind.Raw || media.kind == TetherMediaKind.Video || media.originalStatus != TetherOriginalStatus.OnCamera) {
            Text(
                when {
                    media.originalStatus == TetherOriginalStatus.Downloading -> "${((media.downloadProgress ?: 0f) * 100).toInt()}%"
                    media.originalStatus == TetherOriginalStatus.Downloaded -> "原片已保存"
                    media.originalStatus == TetherOriginalStatus.Failed -> "下载失败"
                    media.kind == TetherMediaKind.Raw -> "RAW · 内嵌预览"
                    else -> media.durationLabel ?: "视频"
                }, Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha = .55f)).padding(5.dp), fontSize = 10.sp, color = Color.White,
            )
        }
    }
}
