package cn.alittlecookie.lutista.tether.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.ScreenMirroring
import top.yukonga.miuix.kmp.overlay.OverlayCascadingListPopup
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import cn.alittlecookie.lutista.ui.theme.getAppBarColor
import cn.alittlecookie.lutista.ui.theme.lutistaBlurEffect
import cn.alittlecookie.lutista.ui.theme.rememberLutistaBlurBackdrop


@Composable
internal fun TetherGallery(
    state: TetherUiState,
    onAction: (TetherUiAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gallery = state.gallery
    var showDirectories by remember { mutableStateOf(false) }
    var showConnection by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var requestId by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val gridState = rememberLazyGridState()
    // 与主页一致的顶栏：大标题随内容滚动收紧，滚动时上浮现模糊底。
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val backdrop = rememberLutistaBlurBackdrop(state.useBlur)
    val barColor = backdrop.getAppBarColor()

    val scopedItems = remember(gallery.items, gallery.selectedStorageId, gallery.selectedDirectoryHandle) {
        gallery.items.filter {
            (gallery.selectedStorageId == null || it.storageId == gallery.selectedStorageId) &&
                (gallery.selectedDirectoryHandle == null || it.directoryHandle == gallery.selectedDirectoryHandle)
        }
    }
    // 排序/筛选来自持久化状态（TetherGalleryUi.sort / types），不再用本地 remember。
    val visibleItems = remember(scopedItems, gallery.sort, gallery.types) {
        visibleTetherMedia(scopedItems, gallery.sort, gallery.types)
    }
    val filterSummary = TetherTypeOrder.filter { it in gallery.types }
        .joinToString(" · ") { it.typeLabel() }.ifBlank { "未选类型" }
    val filtersAreDefault = gallery.types == DefaultTetherGalleryTypes

    LaunchedEffect(gridState, gallery.hasMore, gallery.loadingMore, visibleItems.size, gallery.selectedStorageId, gallery.selectedDirectoryHandle) {
        if (gallery.hasMore && !gallery.loadingMore) {
            snapshotFlow { (gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= visibleItems.size - 12 }
                .distinctUntilChanged().collect { nearEnd -> if (nearEnd) onAction(TetherUiAction.LoadMoreGallery) }
        }
    }

    val menuItems = listOf(
        DropdownItem(
            text = "连接信息",
            summary = state.cameraName ?: "相机",
            onClick = { menuExpanded = false; showConnection = true },
        ),
        DropdownItem(
            text = "排序",
            summary = gallery.sort.summary(),
            children = TetherGallerySort.entries.map { option ->
                DropdownItem(
                    text = option.label(),
                    selected = gallery.sort == option,
                    onClick = {
                        menuExpanded = false
                        onAction(TetherUiAction.SetGallerySort(option))
                    },
                )
            },
        ),
        DropdownItem(
            text = "筛选",
            summary = filterSummary,
            children = TetherTypeOrder.map { kind ->
                DropdownItem(
                    text = "${kind.typeLabel()} · ${scopedItems.count { it.kind == kind }}",
                    selected = kind in gallery.types,
                    // 多选：不关闭菜单，逐项勾选即时生效。
                    onClick = { onAction(TetherUiAction.ToggleGalleryType(kind)) },
                )
            },
        ),
    )

    BoxWithConstraints(modifier.fillMaxSize()) {
        val landscape = TetherLayoutSpec.fromSize(maxWidth.value, maxHeight.value).landscape
        val subtitle = when {
            gallery.enumerating -> "枚举中 ${gallery.enumeratedCount}/${gallery.totalCount ?: "—"}…"
            visibleItems.size != gallery.items.size -> "${visibleItems.size} 项 · 共 ${gallery.items.size} · ${state.connectionSummary}"
            else -> "${visibleItems.size} 项 · ${state.connectionSummary}"
        }
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MiuixTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    modifier = Modifier.lutistaBlurEffect(backdrop),
                    color = barColor,
                    title = if (gallery.selectionMode) "已选 ${gallery.selectedHandles.size} 项" else "相机图库",
                    largeTitle = if (gallery.selectionMode) "已选 ${gallery.selectedHandles.size} 项" else "相机图库",
                    subtitle = subtitle,
                    scrollBehavior = scrollBehavior,
                    defaultWindowInsetsPadding = false,
                    navigationIcon = {
                        IconButton(onClick = {
                            if (gallery.selectionMode) onAction(TetherUiAction.SetSelectionMode(false)) else onBack()
                        }) {
                            Icon(MiuixIcons.Back, contentDescription = if (gallery.selectionMode) "取消多选" else "返回")
                        }
                    },
                    actions = {
                        if (gallery.selectionMode) {
                            TextButton("全选", { onAction(TetherUiAction.SelectAll(visibleItems.mapTo(linkedSetOf()) { it.handle })) })
                        } else {
                            if (state.capabilities.canLiveView) {
                                IconButton(
                                    onClick = { onAction(TetherUiAction.OpenLiveView) },
                                    enabled = state.connected && !state.busy,
                                ) { Icon(MiuixIcons.ScreenMirroring, contentDescription = "实时预览") }
                            }
                            // 弹层锚定在这枚按钮上，避免使用 Scaffold 级别的父边界导致跑偏。
                            Box {
                                IconButton(onClick = { menuExpanded = true }) {
                                    Icon(MiuixIcons.More, contentDescription = "更多")
                                }
                                OverlayCascadingListPopup(
                                    show = menuExpanded,
                                    entries = listOf(DropdownEntry(items = menuItems)),
                                    onDismissRequest = { menuExpanded = false },
                                    collapseOnSelection = false,
                                )
                            }
                        }
                    },
                )
            },
        ) { paddingValues ->
            val sortIsDefault = gallery.sort == TetherGallerySort.CapturedDesc
            val showSummary = gallery.items.isNotEmpty() && (!sortIsDefault || !filtersAreDefault)
            // 头部（摘要胶囊 / 存储卡 / 目录 / 提示）作为网格第一行，随列表滚动，
            // 这样 MiuixScrollBehavior 才能把顶栏的大标题一并收紧。
            val header: @Composable () -> Unit = {
                Column(Modifier.fillMaxWidth()) {
                    if (showSummary) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 只显示偏离默认的项，避免默认状态下顶栏下面挂一条无用信息。
                            if (!sortIsDefault) TetherChip("排序 · ${gallery.sort.summary()}") { menuExpanded = true }
                            if (!filtersAreDefault) TetherChip("筛选 · $filterSummary") { menuExpanded = true }
                        }
                    }
                    if (gallery.storages.size > 1) {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            TetherButton("全部", { onAction(TetherUiAction.SelectStorage(null)) }, primary = gallery.selectedStorageId == null)
                            gallery.storages.forEach { storage ->
                                TetherButton(storage.label, { onAction(TetherUiAction.SelectStorage(storage.id)) }, primary = gallery.selectedStorageId == storage.id)
                            }
                        }
                    }
                    Row(
                        Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val directory = gallery.directories.firstOrNull { it.handle == gallery.selectedDirectoryHandle }
                        TetherButton(directory?.label ?: "所有目录", { showDirectories = true }, enabled = gallery.directories.isNotEmpty())
                        Text(
                            if (gallery.selectionMode) "长按或点选可增减" else "长按可多选",
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton("刷新", { onAction(TetherUiAction.RefreshGallery) }, enabled = state.connected && !gallery.enumerating)
                    }
                    gallery.clockWarning?.let { warning ->
                        TetherNotice(warning, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), "知道了") { onAction(TetherUiAction.DismissClockWarning) }
                    }
                    gallery.directoryWarning?.let { warning ->
                        TetherNotice(warning, Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                    }
                    gallery.error?.let { error ->
                        if (state.connected) TetherNotice(error, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), "重试") { onAction(TetherUiAction.RefreshGallery) }
                        else TetherNotice("$error\n请重新接入 USB，并在相机上选择 PC(Tether)。", Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                    }
                }
            }
            Column(Modifier.fillMaxSize().padding(paddingValues)) {
                if (visibleItems.isEmpty()) {
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                        header()
                        Box(Modifier.fillMaxWidth().padding(vertical = 56.dp), contentAlignment = Alignment.Center) {
                            when {
                                gallery.enumerating -> TetherBusy("正在读取相机文件…")
                                !state.connected -> TetherNotice("相机未连接。请接入 USB，并在相机上选择 PC(Tether)。", Modifier.padding(24.dp))
                                scopedItems.isNotEmpty() -> Text("没有符合筛选条件的项目", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                else -> Text("此目录暂无文件", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(if (landscape) 5 else 3),
                        state = gridState,
                        modifier = Modifier.weight(1f).fillMaxWidth()
                            .then(backdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier)
                            .nestedScroll(scrollBehavior.nestedScrollConnection)
                            .overScrollVertical(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        item(span = { GridItemSpan(maxLineSpan) }) { header() }
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
                if (gallery.selectionMode) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("已选 ${gallery.selectedHandles.size} 项", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface)
                            Text("下载到 Pictures/Lutista", fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                        TetherButton(
                            if (gallery.selectedHandles.isEmpty()) "下载原片" else "下载原片（${gallery.selectedHandles.size}）",
                            { onAction(TetherUiAction.Download(gallery.selectedHandles)) },
                            enabled = gallery.selectedHandles.isNotEmpty() && state.capabilities.canDownload,
                            primary = true,
                        )
                    }
                } else {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("滤镜直出", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurface)
                                Text(
                                    if (state.directFilter) "回传或手动下载的原片，都会按主页全局滤镜另存一张成品到主页输出文件夹。"
                                    else "只回传相机原片，不套滤镜。",
                                    style = MiuixTheme.textStyles.footnote2,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                                state.directFilterReason?.let {
                                    Text(it, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                }
                            }
                            Switch(checked = state.directFilter, onCheckedChange = { onAction(TetherUiAction.SetDirectFilter(it)) })
                        }
                    }
                }
            }
        }
    }

    if (showDirectories) OverlayDialog(show = true, onDismissRequest = { showDirectories = false }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("相机目录", fontWeight = FontWeight.SemiBold, fontSize = 19.sp, color = MiuixTheme.colorScheme.onSurface)
            TetherButton("所有目录", { onAction(TetherUiAction.SelectDirectory(null)); showDirectories = false }, Modifier.fillMaxWidth(), primary = gallery.selectedDirectoryHandle == null)
            Column(Modifier.height(260.dp).fillMaxWidth()) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(gallery.directories.size) { index ->
                        val directory = gallery.directories[index]
                        TetherButton(directory.label, { onAction(TetherUiAction.SelectDirectory(directory.handle)); showDirectories = false }, Modifier.fillMaxWidth(), primary = directory.handle == gallery.selectedDirectoryHandle)
                    }
                }
            }
            TetherButton("取消", { showDirectories = false }, Modifier.fillMaxWidth())
        }
    }

    if (showConnection) OverlayDialog(show = true, onDismissRequest = { showConnection = false }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(state.cameraName ?: "相机信息", fontWeight = FontWeight.SemiBold, fontSize = 19.sp, color = MiuixTheme.colorScheme.onSurface)
            Text(state.connectionSummary, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            state.cameraDetails?.let { Text(it, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
            Text("图库项目 · ${gallery.items.size} 项", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface)
            state.todayJpegCount?.let { Text("自动回传 · 今日 $it 张", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface) }
            gallery.storages.forEach { storage ->
                Text(
                    listOfNotNull(storage.label, storage.itemCount?.let { "$it 项" }, storage.usedBytes?.let { formatTetherBytes(it) }).joinToString(" · "),
                    fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TetherButton("重新枚举", { onAction(TetherUiAction.RefreshGallery); showConnection = false }, Modifier.weight(1f), enabled = state.connected && !gallery.enumerating)
                TetherButton("断开连接", { onAction(TetherUiAction.SetEnabled(false)); showConnection = false }, Modifier.weight(1f))
            }
            TetherButton("完成", { showConnection = false }, Modifier.fillMaxWidth(), primary = true)
        }
    }
}

@Composable
private fun TetherChip(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MiuixTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
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
    val shape = RoundedCornerShape(14.dp)
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
        storageLabel?.let {
            Text(it, Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = .6f)).padding(horizontal = 5.dp, vertical = 2.dp), fontSize = 10.sp, color = Color.White)
        }
        if (media.kind == TetherMediaKind.Raw) {
            Text("RAW", Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = .55f)).padding(horizontal = 5.dp, vertical = 2.dp), fontSize = 10.sp, color = Color.White)
        }
        if (media.kind == TetherMediaKind.Video && media.durationLabel != null) {
            Text(media.durationLabel, Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = .55f)).padding(horizontal = 5.dp, vertical = 2.dp), fontSize = 10.sp, color = Color.White)
        }
        if (selectionMode) {
            Checkbox(
                state = if (selected) ToggleableState.On else ToggleableState.Off,
                onClick = onClick,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
            )
        } else if (media.originalStatus != TetherOriginalStatus.OnCamera || media.kind == TetherMediaKind.Video) {
            Text(
                when {
                    media.originalStatus == TetherOriginalStatus.Downloading -> "${((media.downloadProgress ?: 0f) * 100).toInt()}%"
                    media.originalStatus == TetherOriginalStatus.Downloaded -> "原片已保存"
                    media.originalStatus == TetherOriginalStatus.Failed -> "下载失败"
                    else -> media.durationLabel ?: "视频"
                },
                Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color.Black.copy(alpha = .55f)).padding(5.dp),
                fontSize = 10.sp, color = Color.White,
            )
        }
    }
}
