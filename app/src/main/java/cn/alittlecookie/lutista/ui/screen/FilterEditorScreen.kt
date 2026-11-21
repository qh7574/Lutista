package cn.alittlecookie.lutista.ui.screen

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.alittlecookie.lutista.data.model.FilterConfig
import cn.alittlecookie.lutista.data.model.FloatPoint
import cn.alittlecookie.lutista.data.model.GrainConfig
import cn.alittlecookie.lutista.data.model.LchAdjustment
import cn.alittlecookie.lutista.data.model.LchConfig
import cn.alittlecookie.lutista.data.repository.FilterRepository
import cn.alittlecookie.lutista.domain.engine.ImageProcessingEngine
import cn.alittlecookie.lutista.domain.usecase.ExportFilterUseCase
import cn.alittlecookie.lutista.manager.LogManager
import cn.alittlecookie.lutista.ui.component.CurveEditorCanvas
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.RangeSlider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.time.Duration.Companion.milliseconds

// ── Tab 枚举 ────────────────────────────────────────────────────────

private enum class EditorTab(val label: String) {
    LUT("LUT"),
    BRIGHTNESS("亮度"),
    COLOR("颜色"),
    CURVE("曲线"),
    LCH("LCH"),
    VIGNETTE("暗角"),
    GRAIN("颗粒")
}

// ── LCH 颜色枚举 ────────────────────────────────────────────────────

private enum class LchColor(val label: String, val color: Color) {
    RED("红", Color(0xFFE53935)),
    ORANGE("橙", Color(0xFFFF9800)),
    YELLOW("黄", Color(0xFFFFEB3B)),
    GREEN("绿", Color(0xFF4CAF50)),
    CYAN("青", Color(0xFF00BCD4)),
    BLUE("蓝", Color(0xFF2196F3)),
    PURPLE("紫", Color(0xFF9C27B0)),
    SKIN("肤", Color(0xFFFFCCBC))
}

// ── 曲线通道枚举 ────────────────────────────────────────────────────

private enum class CurveChannel(val label: String, val color: Color) {
    WHITE("W", Color.Gray),
    RED("R", Color(0xFFE53935)),
    GREEN("G", Color(0xFF4CAF50)),
    BLUE("B", Color(0xFF2196F3))
}

// ── 主入口 Composable ───────────────────────────────────────────────

@Composable
fun FilterEditorScreen(
    filterId: Long,
    onBackClick: () -> Unit,
    onHasChangesChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val filterRepository: FilterRepository = koinInject()
    val engine: ImageProcessingEngine = koinInject()
    val logger: LogManager = koinInject()
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val exportFilterUseCase: ExportFilterUseCase = koinInject()

    // ViewModel
    val state = remember(filterId) {
        FilterEditorViewModel(
            filterId = filterId,
            context = context,
            filterRepository = filterRepository,
            engine = engine,
            logger = logger,
            scope = scope
        )
    }

    // 加载滤镜 & 初始化预览
    LaunchedEffect(filterId) {
        kotlinx.coroutines.delay(1.milliseconds)
        state.loadFilter()
        state.initPreview()
    }

    // 释放资源
    DisposableEffect(Unit) {
        onDispose { state.release() }
    }

    // UI 状态
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var showBackDialog by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var renameDialogVisible by remember { mutableStateOf(false) }
    var renameDialogText by remember { mutableStateOf("") }

    var pendingExportType by remember { mutableStateOf<ExportFilterUseCase.ExportType?>(null) }

    /**
     * 本页唯一的返回入口。
     *
     * 三条返回路径（顶栏返回箭头 / 系统返回键 / 外层下发的侧滑信号）都必须经过这里，
     * 保证「有未保存修改时先弹『保存更改?』」的守卫不会被任何一条路径绕过。
     * 尤其是侧滑：miuix-nav 的 swipeDismiss 提交直接调 NavDisplay.onBack，不经过 BackHandler。
     */
    fun requestBack() {
        if (state.hasChanges) {
            showBackDialog = true
        } else {
            onBackClick()
        }
    }

    // 有未保存修改时外层会关掉 miui 的侧滑关闭（防止绕过守卫），这里补一个页面级水平拖拽：
    // 命中后走同一个 requestBack()，所以「侧滑 → 弹框」在脏状态下依然成立；其余时候是空 Modifier。
    val dirtyBackSwipe = rememberDirtyBackSwipeModifier(
        enabled = state.hasChanges,
        onBack = { requestBack() }
    )
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val type = pendingExportType ?: return@rememberLauncherForActivityResult
        pendingExportType = null
        if (uri != null) {
            scope.launch {
                when (type) {
                    ExportFilterUseCase.ExportType.UNIFIED_LUT -> {
                        when (val result = exportFilterUseCase.exportUnifiedLutTo(filterId, uri)) {
                            is ExportFilterUseCase.ExportResult.Success ->
                                Toast.makeText(context, "已导出统一LUT", Toast.LENGTH_SHORT).show()
                            is ExportFilterUseCase.ExportResult.Error ->
                                Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                        }
                    }
                    ExportFilterUseCase.ExportType.FILTER_CONFIG -> {
                        when (val result = exportFilterUseCase.exportFilterConfigTo(filterId, uri)) {
                            is ExportFilterUseCase.ExportResult.Success ->
                                Toast.makeText(context, "已导出滤镜配置", Toast.LENGTH_SHORT).show()
                            is ExportFilterUseCase.ExportResult.Error ->
                                Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }
    var showResetDialog by remember { mutableStateOf(false) }
    var filterNameEditing by remember { mutableStateOf("") }
    var isEditingName by remember { mutableStateOf(false) }

    // 初始化名称编辑
    LaunchedEffect(state.filterName) {
        if (!isEditingName) {
            filterNameEditing = state.filterName
        }
    }

    // SAF: 选择预览图片
    val previewImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { state.loadPreviewImage(it) }
    }

    // SAF: 选择 LUT 1 文件
    val lut1Picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { state.importLut(1, it) }
    }

    // SAF: 选择 LUT 2 文件
    val lut2Picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { state.importLut(2, it) }
    }

    Scaffold(
        modifier = dirtyBackSwipe,
        topBar = {
            SmallTopAppBar(
                title = state.filterName.ifEmpty { "滤镜编辑" },
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = { requestBack() }) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    // 撤销
                    IconButton(
                        onClick = { state.undo() },
                        enabled = state.canUndo
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Undo,
                            contentDescription = "撤销",
                            tint = if (state.canUndo) {
                                MiuixTheme.colorScheme.onSurface
                            } else {
                                MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.38f)
                            }
                        )
                    }
                    // 重做
                    IconButton(
                        onClick = { state.redo() },
                        enabled = state.canRedo
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Redo,
                            contentDescription = "重做",
                            tint = if (state.canRedo) {
                                MiuixTheme.colorScheme.onSurface
                            } else {
                                MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.38f)
                            }
                        )
                    }
                    // 更多菜单
                    Box {
                        IconButton(onClick = { showMoreMenu = !showMoreMenu }) {
                            Icon(
                                imageVector = MiuixIcons.More,
                                contentDescription = "更多"
                            )
                        }
                        OverlayListPopup(
                            show = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false },
                            alignment = PopupPositionProvider.Align.End
                        ) {
                            ListPopupColumn {
                                DropdownImpl(
                                    text = "重命名",
                                    optionSize = 5,
                                    isSelected = false,
                                    index = 0,
                                    dropdownColors = DropdownDefaults.dropdownColors(),
                                    onSelectedIndexChange = {
                                        showMoreMenu = false
                                        renameDialogVisible = true
                                        renameDialogText = state.filterName
                                    }
                                )
                                DropdownImpl(
                                    text = "保存",
                                    optionSize = 5,
                                    isSelected = false,
                                    index = 1,
                                    dropdownColors = DropdownDefaults.dropdownColors(),
                                    onSelectedIndexChange = {
                                        showMoreMenu = false
                                        scope.launch {
                                            if (state.save()) Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                                            else Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                                DropdownImpl(
                                    text = "导出统一LUT",
                                    optionSize = 5,
                                    isSelected = false,
                                    index = 2,
                                    dropdownColors = DropdownDefaults.dropdownColors(),
                                    onSelectedIndexChange = {
                                        showMoreMenu = false
                                        pendingExportType = ExportFilterUseCase.ExportType.UNIFIED_LUT
                                        exportLauncher.launch("${state.filterName}_unified.cube")
                                    }
                                )
                                DropdownImpl(
                                    text = "导出滤镜配置",
                                    optionSize = 5,
                                    isSelected = false,
                                    index = 3,
                                    dropdownColors = DropdownDefaults.dropdownColors(),
                                    onSelectedIndexChange = {
                                        showMoreMenu = false
                                        pendingExportType = ExportFilterUseCase.ExportType.FILTER_CONFIG
                                        exportLauncher.launch("${state.filterName}_config.zip")
                                    }
                                )
                                DropdownImpl(
                                    text = "恢复默认",
                                    optionSize = 5,
                                    isSelected = false,
                                    index = 4,
                                    dropdownColors = DropdownDefaults.dropdownColors(),
                                    onSelectedIndexChange = {
                                        showMoreMenu = false
                                        showResetDialog = true
                                    }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // ── 预览区域 (约 40% 屏幕高度) ───────────────────────
            PreviewArea(
                state = state,
                onImageClick = {
                    previewImagePicker.launch(arrayOf("image/*"))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.6f)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // ── Tab 行 ───────────────────────────────────────────
            val tabs = EditorTab.entries.map { it.label }
            TabRowWithContour(
                tabs = tabs,
                selectedTabIndex = selectedTabIndex,
                onTabSelected = { selectedTabIndex = it },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // ── 详情调节区域 (可滚动) ────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.4f)
            ) {
                when (EditorTab.entries[selectedTabIndex]) {
                    EditorTab.LUT -> LutTabContent(
                        config = state.filterConfig,
                        onConfigChange = { transform -> state.updateConfig(transform) },
                        onPickLut1 = { lut1Picker.launch(arrayOf("*/*")) },
                        onPickLut2 = { lut2Picker.launch(arrayOf("*/*")) }
                    )

                    EditorTab.BRIGHTNESS -> BrightnessTabContent(
                        config = state.filterConfig,
                        onConfigChange = { transform -> state.updateConfig(transform) }
                    )

                    EditorTab.COLOR -> ColorTabContent(
                        config = state.filterConfig,
                        onConfigChange = { transform -> state.updateConfig(transform) }
                    )

                    EditorTab.CURVE -> CurveTabContent(
                        config = state.filterConfig,
                        onCurveChannelChange = { ch, pts -> state.updateCurveChannel(ch, pts) },
                        onCurveChannelPreview = { ch, pts -> state.updateCurveChannelPreview(ch, pts) }
                    )

                    EditorTab.LCH -> LchTabContent(
                        config = state.filterConfig,
                        onConfigChange = { transform -> state.updateConfig(transform) }
                    )

                    EditorTab.VIGNETTE -> VignetteTabContent(
                        config = state.filterConfig,
                        onConfigChange = { transform -> state.updateConfig(transform) }
                    )

                    EditorTab.GRAIN -> GrainTabContent(
                        config = state.filterConfig,
                        onConfigChange = { transform -> state.updateConfig(transform) }
                    )
                }
            }
        }

        // 重命名对话框
        if (renameDialogVisible) {
            RenameDialog(
                currentName = renameDialogText,
                onNameChange = { renameDialogText = it },
                onConfirm = {
                    val trimmed = renameDialogText.trim()
                    if (trimmed.isNotEmpty()) {
                        state.updateName(trimmed)
                    }
                    renameDialogVisible = false
                },
                onDismiss = { renameDialogVisible = false }
            )
        }

        if (showResetDialog) {
            OverlayDialog(
                show = showResetDialog,
                onDismissRequest = { showResetDialog = false }
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Text("即将重置所有参数（仅保留LUT）", fontSize = 16.sp,
                        color = MiuixTheme.colorScheme.onSurface)
                    Row {
                        Button(
                            onClick = {
                                state.resetToDefault()
                                showResetDialog = false
                            }, modifier = Modifier.weight(0.5f),
                            colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.secondary)
                        ) {
                            Text("重置", color = MiuixTheme.colorScheme.error)
                        }
                        Spacer(Modifier.size(8.dp))
                        Button(
                            onClick = { showResetDialog = false },
                            modifier = Modifier.weight(0.5f),
                            colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.secondary)
                        ) {
                            Text("取消", color = MiuixTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }

        if (showBackDialog) {
            OverlayDialog(
                show = showBackDialog,
                onDismissRequest = { showBackDialog = false }
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("保存更改?", fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.onSurface)
                    Text("当前滤镜有未保存的更改", fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Button(onClick = {
                        showBackDialog = false
                        scope.launch {
                            if (state.save()) onBackClick()
                        }
                    }, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.primary)) {
                        Text("保存修改", color = Color.White)
                    }
                    Button(onClick = {
                        showBackDialog = false
                        state.discardChanges()
                        onBackClick()
                    }, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.secondaryContainer)) {
                        Text("放弃修改", color = MiuixTheme.colorScheme.error)
                    }
                    Button(onClick = { showBackDialog = false }, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.secondaryContainer)) {
                        Text("继续修改", color = MiuixTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }

    // 返回处理：系统返回键（manifest 关闭了 OnBackInvokedCallback，走 legacy 路径命中这里）
    BackHandler { requestBack() }

    // 把「是否有未保存修改」上报给外层。外层据此决定侧滑关闭能不能用：有改动时关掉手势，
    // 避免 swipeDismiss 的提交直接调 NavDisplay.onBack 绕过下面的 requestBack() 守卫。
    LaunchedEffect(state.hasChanges) {
        onHasChangesChange(state.hasChanges)
    }
}

// ── 预览区域 ────────────────────────────────────────────────────────

@Composable
private fun PreviewArea(
    state: FilterEditorViewModel,
    onImageClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val previewBitmap = state.previewBitmap
    val isLongPressed = state.isLongPressed

    Card(
        modifier = modifier,
        cornerRadius = 16.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        val longPressResult = withTimeoutOrNull(400L) {
                            waitForUpOrCancellation()
                        }
                        if (longPressResult == null) {
                            // 长按开始
                            state.isLongPressed = true
                            waitForUpOrCancellation()
                            state.isLongPressed = false
                        } else {
                            // 短按 → 更换预览图
                            onImageClick()
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (isLongPressed) {
                // 长按显示原图
                AsyncImage(
                    model = state.previewImageUri ?: cn.alittlecookie.lutista.R.raw.example,
                    contentDescription = "原图",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else if (previewBitmap != null) {
                AsyncImage(
                    model = previewBitmap,
                    contentDescription = "滤镜预览",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // 加载中
                Text(
                    text = "加载中...",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }

            // 底部提示标签
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = if (isLongPressed) "原图" else "长按查看原图 | 点击更换预览",
                    fontSize = 12.sp,
                    color = Color.White
                )
            }
        }
    }
}

// ── SliderWithValue 通用组件 ────────────────────────────────────────

@Composable
private fun SliderWithValue(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    valueFormatter: (Float) -> String = { "%.0f".format(it) }
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 0.dp, end = 0.dp, top = 0.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                label,
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurface
            )
            Text(
                valueFormatter(value),
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange
        )
    }
}

// ── 分区标题 ────────────────────────────────────────────────────────

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

// ── LUT Tab ─────────────────────────────────────────────────────────

@Composable
private fun LutTabContent(
    config: FilterConfig,
    onConfigChange: ((FilterConfig) -> FilterConfig) -> Unit,
    onPickLut1: () -> Unit,
    onPickLut2: () -> Unit
) {
    // 全局浓度滑块本地状态
    var globalStrength by remember(config.lutGlobalStrength) {
        mutableFloatStateOf(config.lutGlobalStrength * 100f)
    }
    var lut1Strength by remember(config.lutConcentration1) {
        mutableFloatStateOf(config.lutConcentration1 * 100f)
    }
    var lut2Strength by remember(config.lutConcentration2) {
        mutableFloatStateOf(config.lutConcentration2 * 100f)
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            // 全局 LUT 浓度
            SectionTitle("全局 LUT 浓度")
            SliderWithValue(
                label = "浓度",
                value = globalStrength,
                onValueChange = { globalStrength = it },
                onValueChangeFinished = {
                    onConfigChange { it.copy(lutGlobalStrength = globalStrength / 100f) }
                },
                valueRange = 0f..100f,
                valueFormatter = { "%.0f%%".format(it) }
            )
        }

        item {
            // LUT 1
            SectionTitle("LUT 1")
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                cornerRadius = 12.dp
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = config.lutPath1?.substringAfterLast('/') ?: "未选择文件",
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = onPickLut1,
                            colors = ButtonDefaults.buttonColors(
                                color = MiuixTheme.colorScheme.primary
                            )
                        ) {
                            Text("选择文件", fontSize = 12.sp, color = Color.White)
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "LUT 1 强度",
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                        Text(
                            "%.0f%%".format(lut1Strength),
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = lut1Strength,
                        onValueChange = { lut1Strength = it },
                        onValueChangeFinished = {
                            onConfigChange { it.copy(lutConcentration1 = lut1Strength / 100f) }
                        },
                        valueRange = 0f..100f,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }

        item {
            // LUT 2
            SectionTitle("LUT 2")
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                cornerRadius = 12.dp
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = config.lutPath2?.substringAfterLast('/') ?: "未选择文件",
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = onPickLut2,
                            colors = ButtonDefaults.buttonColors(
                                color = MiuixTheme.colorScheme.primary
                            )
                        ) {
                            Text("选择文件", fontSize = 12.sp, color = Color.White)
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "LUT 2 强度",
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurface
                        )
                        Text(
                            "%.0f%%".format(lut2Strength),
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = lut2Strength,
                        onValueChange = { lut2Strength = it },
                        onValueChangeFinished = {
                            onConfigChange { it.copy(lutConcentration2 = lut2Strength / 100f) }
                        },
                        valueRange = 0f..100f,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

// ── 亮度 Tab ────────────────────────────────────────────────────────

@Composable
private fun BrightnessTabContent(
    config: FilterConfig,
    onConfigChange: ((FilterConfig) -> FilterConfig) -> Unit
) {
    var exposure by remember(config.exposure) { mutableFloatStateOf(config.exposure) }
    var contrast by remember(config.contrast) { mutableFloatStateOf(config.contrast) }
    var highlights by remember(config.highlights) { mutableFloatStateOf(config.highlights) }
    var shadows by remember(config.shadows) { mutableFloatStateOf(config.shadows) }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            SliderWithValue(
                label = "曝光",
                value = exposure,
                onValueChange = { exposure = it },
                onValueChangeFinished = {
                    onConfigChange { it.copy(exposure = exposure) }
                },
                valueRange = -50f..50f,
                valueFormatter = { "%.1f EV".format(it / 10f) }
            )
        }
        item {
            SliderWithValue(
                label = "对比度",
                value = contrast,
                onValueChange = { contrast = it },
                onValueChangeFinished = {
                    onConfigChange { it.copy(contrast = contrast) }
                },
                valueRange = -100f..100f
            )
        }
        item {
            SliderWithValue(
                label = "高光",
                value = highlights,
                onValueChange = { highlights = it },
                onValueChangeFinished = {
                    onConfigChange { it.copy(highlights = highlights) }
                },
                valueRange = -100f..100f
            )
        }
        item {
            SliderWithValue(
                label = "阴影",
                value = shadows,
                onValueChange = { shadows = it },
                onValueChangeFinished = {
                    onConfigChange { it.copy(shadows = shadows) }
                },
                valueRange = -100f..100f
            )
        }
        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

// ── 颜色 Tab ────────────────────────────────────────────────────────

@Composable
private fun ColorTabContent(
    config: FilterConfig,
    onConfigChange: ((FilterConfig) -> FilterConfig) -> Unit
) {
    var saturation by remember(config.saturation) { mutableFloatStateOf(config.saturation) }
    var colorTemperature by remember(config.colorTemperature) {
        mutableFloatStateOf(config.colorTemperature)
    }
    var tint by remember(config.tint) { mutableFloatStateOf(config.tint) }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            SliderWithValue(
                label = "饱和度",
                value = saturation,
                onValueChange = { saturation = it },
                onValueChangeFinished = {
                    onConfigChange { it.copy(saturation = saturation) }
                },
                valueRange = -100f..100f
            )
        }
        item {
            SliderWithValue(
                label = "色温",
                value = colorTemperature,
                onValueChange = { colorTemperature = it },
                onValueChangeFinished = {
                    onConfigChange { it.copy(colorTemperature = colorTemperature) }
                },
                valueRange = -100f..100f
            )
        }
        item {
            SliderWithValue(
                label = "色调",
                value = tint,
                onValueChange = { tint = it },
                onValueChangeFinished = {
                    onConfigChange { it.copy(tint = tint) }
                },
                valueRange = -100f..100f
            )
        }
        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

// ── 曲线 Tab ────────────────────────────────────────────────────────

@Composable
private fun CurveTabContent(
    config: FilterConfig,
    onCurveChannelChange: (channelIndex: Int, points: List<FloatPoint>) -> Unit,
    onCurveChannelPreview: (channelIndex: Int, points: List<FloatPoint>) -> Unit
) {
    var selectedChannel by remember { mutableIntStateOf(0) }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // 左侧通道选择按钮
        Column(
            modifier = Modifier
                .width(48.dp)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CurveChannel.entries.forEachIndexed { index, channel ->
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            if (selectedChannel == index) {
                                channel.color.copy(alpha = 0.3f)
                            } else {
                                Color.Transparent
                            }
                        )
                        .then(
                            if (selectedChannel == index) {
                                Modifier.border(2.dp, channel.color, CircleShape)
                            } else {
                                Modifier
                            }
                        )
                        .clickable { selectedChannel = index },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = channel.label,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (selectedChannel == index) {
                            channel.color
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // 右侧曲线编辑画布
        val currentPoints = when (selectedChannel) {
            0 -> config.curveConfig.whiteChannel
            1 -> config.curveConfig.redChannel
            2 -> config.curveConfig.greenChannel
            3 -> config.curveConfig.blueChannel
            else -> config.curveConfig.whiteChannel
        }

        CurveEditorCanvas(
            points = currentPoints,
            onPointsChanged = { newPoints ->
                // newPoints already stripped of endpoints by CurveEditorCanvas
                onCurveChannelChange(selectedChannel, newPoints)
            },
            onPointsPreview = { newPoints ->
                // newPoints already stripped of endpoints by CurveEditorCanvas
                onCurveChannelPreview(selectedChannel, newPoints)
            },
            channelColor = CurveChannel.entries[selectedChannel].color,
            modifier = Modifier
                .weight(1f)
                .padding(start = 0.dp, end = 16.dp, top = 16.dp, bottom = 32.dp)
                .fillMaxSize()
        )
    }
}

// ── LCH Tab ─────────────────────────────────────────────────────────

@Composable
private fun LchTabContent(
    config: FilterConfig,
    onConfigChange: ((FilterConfig) -> FilterConfig) -> Unit
) {
    var selectedColorIndex by remember { mutableIntStateOf(0) }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            // 颜色选择行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                LchColor.entries.forEachIndexed { index, color ->
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(color.color)
                            .then(
                                if (selectedColorIndex == index) {
                                    Modifier.border(
                                        3.dp,
                                        MiuixTheme.colorScheme.onSurface,
                                        CircleShape
                                    )
                                } else {
                                    Modifier
                                }
                            )
                            .clickable { selectedColorIndex = index },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = color.label,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
        }

        // 获取当前选中颜色的 LCH 调整值
        item {
            val currentAdjustment = getLchAdjustment(config.lchConfig, selectedColorIndex)

            // Lightness
            var lightness by remember(currentAdjustment) {
                mutableFloatStateOf(currentAdjustment.lightness)
            }
            SliderWithValue(
                label = "明度",
                value = lightness,
                onValueChange = { lightness = it },
                onValueChangeFinished = {
                    onConfigChange {
                        it.copy(
                            lchConfig = setLchAdjustment(
                                it.lchConfig, selectedColorIndex,
                                LchAdjustment(lightness, currentAdjustment.chroma, currentAdjustment.hue)
                            )
                        )
                    }
                },
                valueRange = -100f..100f
            )
        }

        item {
            val currentAdjustment = getLchAdjustment(config.lchConfig, selectedColorIndex)

            // Chroma
            var chroma by remember(currentAdjustment) {
                mutableFloatStateOf(currentAdjustment.chroma)
            }
            SliderWithValue(
                label = "色度",
                value = chroma,
                onValueChange = { chroma = it },
                onValueChangeFinished = {
                    onConfigChange {
                        it.copy(
                            lchConfig = setLchAdjustment(
                                it.lchConfig, selectedColorIndex,
                                LchAdjustment(currentAdjustment.lightness, chroma, currentAdjustment.hue)
                            )
                        )
                    }
                },
                valueRange = -100f..100f
            )
        }

        item {
            val currentAdjustment = getLchAdjustment(config.lchConfig, selectedColorIndex)

            // Hue
            var hue by remember(currentAdjustment) {
                mutableFloatStateOf(currentAdjustment.hue)
            }
            SliderWithValue(
                label = "色相",
                value = hue,
                onValueChange = { hue = it },
                onValueChangeFinished = {
                    onConfigChange {
                        it.copy(
                            lchConfig = setLchAdjustment(
                                it.lchConfig, selectedColorIndex,
                                LchAdjustment(currentAdjustment.lightness, currentAdjustment.chroma, hue)
                            )
                        )
                    }
                },
                valueRange = -100f..100f
            )
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

/**
 * 根据索引获取 LchConfig 中对应颜色的 LchAdjustment
 */
private fun getLchAdjustment(config: LchConfig, index: Int): LchAdjustment {
    return when (index) {
        0 -> config.red
        1 -> config.orange
        2 -> config.yellow
        3 -> config.green
        4 -> config.cyan
        5 -> config.blue
        6 -> config.purple
        7 -> config.skin
        else -> config.red
    }
}

/**
 * 根据索引设置 LchConfig 中对应颜色的 LchAdjustment
 */
private fun setLchAdjustment(
    config: LchConfig,
    index: Int,
    adjustment: LchAdjustment
): LchConfig {
    return when (index) {
        0 -> config.copy(red = adjustment)
        1 -> config.copy(orange = adjustment)
        2 -> config.copy(yellow = adjustment)
        3 -> config.copy(green = adjustment)
        4 -> config.copy(cyan = adjustment)
        5 -> config.copy(blue = adjustment)
        6 -> config.copy(purple = adjustment)
        7 -> config.copy(skin = adjustment)
        else -> config
    }
}

// ── 暗角 Tab ────────────────────────────────────────────────────────

@Composable
private fun VignetteTabContent(
    config: FilterConfig,
    onConfigChange: ((FilterConfig) -> FilterConfig) -> Unit
) {
    var vignetteStrength by remember(config.vignetteStrength) {
        mutableFloatStateOf(config.vignetteStrength)
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            SliderWithValue(
                label = "暗角强度",
                value = vignetteStrength,
                onValueChange = { vignetteStrength = it },
                onValueChangeFinished = {
                    onConfigChange { it.copy(vignetteStrength = vignetteStrength) }
                },
                valueRange = -100f..100f
            )
        }
        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

// ── 颗粒 Tab ────────────────────────────────────────────────────────

@Composable
private fun GrainTabContent(
    config: FilterConfig,
    onConfigChange: ((FilterConfig) -> FilterConfig) -> Unit
) {
    // 本地滑块状态
    var globalStrength by remember(config.grainConfig.globalStrength) {
        mutableFloatStateOf(config.grainConfig.globalStrength * 100f)
    }
    var grainSize by remember(config.grainConfig.grainSize) {
        mutableFloatStateOf(config.grainConfig.grainSize * 10f) // 0.1-10.0 → 1-100
    }
    var shadowThreshold by remember(config.grainConfig.shadowThreshold) {
        mutableFloatStateOf(config.grainConfig.shadowThreshold)
    }
    var highlightThreshold by remember(config.grainConfig.highlightThreshold) {
        mutableFloatStateOf(config.grainConfig.highlightThreshold)
    }
    var toneRange by remember(shadowThreshold, highlightThreshold) {
        mutableStateOf(shadowThreshold..highlightThreshold)
    }
    var shadowGrainRatio by remember(config.grainConfig.shadowGrainRatio) {
        mutableFloatStateOf(config.grainConfig.shadowGrainRatio * 100f)
    }
    var midtoneGrainRatio by remember(config.grainConfig.midtoneGrainRatio) {
        mutableFloatStateOf(config.grainConfig.midtoneGrainRatio * 100f)
    }
    var highlightGrainRatio by remember(config.grainConfig.highlightGrainRatio) {
        mutableFloatStateOf(config.grainConfig.highlightGrainRatio * 100f)
    }
    var shadowSizeRatio by remember(config.grainConfig.shadowSizeRatio) {
        mutableFloatStateOf(config.grainConfig.shadowSizeRatio * 100f)
    }
    var midtoneSizeRatio by remember(config.grainConfig.midtoneSizeRatio) {
        mutableFloatStateOf(config.grainConfig.midtoneSizeRatio * 100f)
    }
    var highlightSizeRatio by remember(config.grainConfig.highlightSizeRatio) {
        mutableFloatStateOf(config.grainConfig.highlightSizeRatio * 100f)
    }
    var redChannelRatio by remember(config.grainConfig.redChannelRatio) {
        mutableFloatStateOf(config.grainConfig.redChannelRatio * 100f)
    }
    var blueChannelRatio by remember(config.grainConfig.blueChannelRatio) {
        mutableFloatStateOf(config.grainConfig.blueChannelRatio * 100f)
    }
    var channelCorrelation by remember(config.grainConfig.channelCorrelation) {
        mutableFloatStateOf(config.grainConfig.channelCorrelation * 100f)
    }
    var colorPreservation by remember(config.grainConfig.colorPreservation) {
        mutableFloatStateOf(config.grainConfig.colorPreservation * 100f)
    }

    // 合并 GrainConfig
    fun buildGrainConfig(): GrainConfig {
        return GrainConfig(
            globalStrength = globalStrength / 100f,
            grainSize = grainSize / 10f,
            shadowThreshold = shadowThreshold,
            highlightThreshold = highlightThreshold,
            shadowGrainRatio = shadowGrainRatio / 100f,
            midtoneGrainRatio = midtoneGrainRatio / 100f,
            highlightGrainRatio = highlightGrainRatio / 100f,
            shadowSizeRatio = shadowSizeRatio / 100f,
            midtoneSizeRatio = midtoneSizeRatio / 100f,
            highlightSizeRatio = highlightSizeRatio / 100f,
            redChannelRatio = redChannelRatio / 100f,
            blueChannelRatio = blueChannelRatio / 100f,
            channelCorrelation = channelCorrelation / 100f,
            colorPreservation = colorPreservation / 100f
        )
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            // 全局控制
            SectionTitle("全局控制")
            SliderWithValue(
                label = "颗粒强度",
                value = globalStrength,
                onValueChange = { globalStrength = it },
                onValueChangeFinished = {
                    onConfigChange { it.copy(grainConfig = buildGrainConfig()) }
                },
                valueRange = 0f..100f,
                valueFormatter = { "%.0f%%".format(it) }
            )
        }

        item {
            SliderWithValue(
                label = "颗粒大小",
                value = grainSize,
                onValueChange = { grainSize = it },
                onValueChangeFinished = {
                    onConfigChange { it.copy(grainConfig = buildGrainConfig()) }
                },
                valueRange = 1f..100f,
                valueFormatter = { "%.1f".format(it / 10f) }
            )
        }

        item {
            // 影调阈值
            SectionTitle("影调阈值")
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 0.dp, end = 0.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("阴影: ${toneRange.start.toInt()}", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface)
                    Text("中间调", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Text("高光: ${toneRange.endInclusive.toInt()}", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface)
                }
                RangeSlider(
                    value = toneRange,
                    onValueChange = { toneRange = it },
                    onValueChangeFinished = {
                        shadowThreshold = toneRange.start
                        highlightThreshold = toneRange.endInclusive
                        onConfigChange { it.copy(grainConfig = buildGrainConfig()) }
                    },
                    valueRange = 0f..255f
                )
            }
        }

        item {
            // 影调分区颗粒强度
            SectionTitle("影调分区颗粒强度")
            SliderWithValue(
                label = "阴影颗粒比",
                value = shadowGrainRatio,
                onValueChange = { shadowGrainRatio = it },
                onValueChangeFinished = {
                    onConfigChange { it.copy(grainConfig = buildGrainConfig()) }
                },
                valueRange = 0f..200f,
                valueFormatter = { "%.0f%%".format(it) }
            )
        }

        item {
            SliderWithValue(
                label = "中间调颗粒比",
                value = midtoneGrainRatio,
                onValueChange = { midtoneGrainRatio = it },
                onValueChangeFinished = {
                    onConfigChange { it.copy(grainConfig = buildGrainConfig()) }
                },
                valueRange = 0f..200f,
                valueFormatter = { "%.0f%%".format(it) }
            )
        }

        item {
            SliderWithValue(
                label = "高光颗粒比",
                value = highlightGrainRatio,
                onValueChange = { highlightGrainRatio = it },
                onValueChangeFinished = {
                    onConfigChange { it.copy(grainConfig = buildGrainConfig()) }
                },
                valueRange = 0f..200f,
                valueFormatter = { "%.0f%%".format(it) }
            )
        }

        item {
            // 影调分区尺寸系数
            SectionTitle("影调分区尺寸系数")
            SliderWithValue(
                label = "阴影尺寸比",
                value = shadowSizeRatio,
                onValueChange = { shadowSizeRatio = it },
                onValueChangeFinished = {
                    onConfigChange { it.copy(grainConfig = buildGrainConfig()) }
                },
                valueRange = 0f..200f,
                valueFormatter = { "%.0f%%".format(it) }
            )
        }

        item {
            SliderWithValue(
                label = "中间调尺寸比",
                value = midtoneSizeRatio,
                onValueChange = { midtoneSizeRatio = it },
                onValueChangeFinished = {
                    onConfigChange { it.copy(grainConfig = buildGrainConfig()) }
                },
                valueRange = 0f..200f,
                valueFormatter = { "%.0f%%".format(it) }
            )
        }

        item {
            SliderWithValue(
                label = "高光尺寸比",
                value = highlightSizeRatio,
                onValueChange = { highlightSizeRatio = it },
                onValueChangeFinished = {
                    onConfigChange { it.copy(grainConfig = buildGrainConfig()) }
                },
                valueRange = 0f..200f,
                valueFormatter = { "%.0f%%".format(it) }
            )
        }

        item {
            // 色彩通道差异
            SectionTitle("色彩通道差异")
            SliderWithValue(
                label = "红色通道比",
                value = redChannelRatio,
                onValueChange = { redChannelRatio = it },
                onValueChangeFinished = {
                    onConfigChange { it.copy(grainConfig = buildGrainConfig()) }
                },
                valueRange = 0f..200f,
                valueFormatter = { "%.0f%%".format(it) }
            )
        }

        item {
            SliderWithValue(
                label = "蓝色通道比",
                value = blueChannelRatio,
                onValueChange = { blueChannelRatio = it },
                onValueChangeFinished = {
                    onConfigChange { it.copy(grainConfig = buildGrainConfig()) }
                },
                valueRange = 0f..200f,
                valueFormatter = { "%.0f%%".format(it) }
            )
        }

        item {
            // 高级设置
            SectionTitle("高级设置")
            SliderWithValue(
                label = "通道关联度",
                value = channelCorrelation,
                onValueChange = { channelCorrelation = it },
                onValueChangeFinished = {
                    onConfigChange { it.copy(grainConfig = buildGrainConfig()) }
                },
                valueRange = 0f..100f,
                valueFormatter = { "%.0f%%".format(it) }
            )
        }

        item {
            SliderWithValue(
                label = "色彩保留度",
                value = colorPreservation,
                onValueChange = { colorPreservation = it },
                onValueChangeFinished = {
                    onConfigChange { it.copy(grainConfig = buildGrainConfig()) }
                },
                valueRange = 0f..100f,
                valueFormatter = { "%.0f%%".format(it) }
            )
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}
