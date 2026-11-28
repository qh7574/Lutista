package cn.alittlecookie.lutista.ui.screen

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.alittlecookie.lutista.data.repository.FilterRepository
import cn.alittlecookie.lutista.data.repository.WatermarkRepository
import cn.alittlecookie.lutista.manager.SettingsManager
import cn.alittlecookie.lutista.service.FileMonitorService
import cn.alittlecookie.lutista.ui.theme.getAppBarColor
import cn.alittlecookie.lutista.ui.theme.lutistaBlurEffect
import cn.alittlecookie.lutista.ui.theme.rememberLutistaBlurBackdrop
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import top.yukonga.miuix.kmp.icon.extended.Album
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close2
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlaySpinnerPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical


@Composable
fun HomeScreen(
    launchPicker: () -> Unit = {},
    onMonitorFolderClick: () -> Unit = {},
    onOutputFolderClick: () -> Unit = {},
    tetherEnabled: Boolean = false,
    tetherBusy: Boolean = false,
    tetherSummary: String = "未连接",
    onTetherEnabledChange: (Boolean) -> Unit = {},
    onTetherClick: () -> Unit = {},
) {
    val filterRepository: FilterRepository = koinInject()
    val watermarkRepository: WatermarkRepository = koinInject()
    val settingsManager: SettingsManager = koinInject()
    val scope = remember { CoroutineScope(Dispatchers.Main) }

    var isMonitorRunning by remember { mutableStateOf(false) }
    var monitorRunningPersisted by remember { mutableStateOf(settingsManager.getMonitorRunningSync()) }
    var processedCount by remember { mutableIntStateOf(settingsManager.getProcessedCountSync()) }
    var pendingStartAfterPathSelect by remember { mutableStateOf(false) }

    var onlyNewFiles by remember { mutableStateOf(settingsManager.getOnlyNewFilesSync()) }

    var selectedFilterIndex by remember { mutableIntStateOf(0) }
    var selectedWatermarkIndex by remember { mutableIntStateOf(0) }

    val filtersFromDb by filterRepository.getEnabledFilters().collectAsState(initial = emptyList())
    val watermarksFromDb by watermarkRepository.getEnabledWatermarks().collectAsState(initial = emptyList())
    var monitorPath by remember { mutableStateOf(settingsManager.getMonitorPathSync()) }
    var outputPath by remember { mutableStateOf(settingsManager.getOutputPathSync()) }
    var globalFilterId by remember { mutableStateOf(settingsManager.getGlobalFilterIdSync()) }
    var globalWatermarkId by remember { mutableStateOf(settingsManager.getGlobalWatermarkIdSync()) }

    LaunchedEffect(Unit) {
        settingsManager.monitorRunning.collect { monitorRunningPersisted = it }
    }
    LaunchedEffect(Unit) {
        settingsManager.processedCount.collect { processedCount = it }
    }
    LaunchedEffect(Unit) {
        settingsManager.onlyNewFiles.collect { onlyNewFiles = it }
    }
    LaunchedEffect(Unit) {
        settingsManager.monitorPath.collect { monitorPath = it }
    }
    LaunchedEffect(Unit) {
        settingsManager.outputPath.collect { outputPath = it }
    }
    LaunchedEffect(Unit) {
        settingsManager.globalFilterId.collect { globalFilterId = it }
    }
    LaunchedEffect(Unit) {
        settingsManager.globalWatermarkId.collect { globalWatermarkId = it }
    }

    val filterOptions = remember(filtersFromDb) {
        buildList {
            add(DropdownItem(text = "无滤镜"))
            addAll(filtersFromDb.map { filter ->
                DropdownItem(text = filter.name)
            })
        }
    }

    val watermarkOptions = remember(watermarksFromDb) {
        buildList {
            add(DropdownItem(text = "无水印"))
            addAll(watermarksFromDb.map { DropdownItem(text = it.name) })
        }
    }

    LaunchedEffect(filtersFromDb, globalFilterId) {
        if (globalFilterId > 0L) {
            val idx = filtersFromDb.indexOfFirst { it.id == globalFilterId }
            if (idx >= 0) selectedFilterIndex = idx + 1
        }
    }

    LaunchedEffect(watermarksFromDb, globalWatermarkId) {
        if (globalWatermarkId != "none") {
            val id = globalWatermarkId.toLongOrNull()
            if (id != null) {
                val idx = watermarksFromDb.indexOfFirst { it.id == id }
                if (idx >= 0) selectedWatermarkIndex = idx + 1
            }
        }
    }

    val context = LocalContext.current

    fun startMonitorService() {
        val intent = Intent(context, FileMonitorService::class.java).apply {
            action = FileMonitorService.ACTION_START
            putExtra(FileMonitorService.EXTRA_PATH, monitorPath)
            putExtra(FileMonitorService.EXTRA_ONLY_NEW, onlyNewFiles)
        }
        context.startForegroundService(intent)
    }

    fun stopMonitorService() {
        val intent = Intent(context, FileMonitorService::class.java).apply {
            action = FileMonitorService.ACTION_STOP
        }
        context.startService(intent)
    }

    // Auto-start service when monitorPath becomes available after SAF selection
    LaunchedEffect(monitorPath) {
        if (pendingStartAfterPathSelect && monitorPath.isNotEmpty()) {
            pendingStartAfterPathSelect = false
            isMonitorRunning = true
            settingsManager.setMonitorRunning(true)
            startMonitorService()
        }
    }

    DisposableEffect(monitorRunningPersisted) {
        if (!monitorRunningPersisted) return@DisposableEffect onDispose { }

        val serviceIntent = Intent(context, FileMonitorService::class.java)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                isMonitorRunning = true
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                isMonitorRunning = false
                scope.launch { settingsManager.setMonitorRunning(false) }
            }
        }

        val bound = context.bindService(serviceIntent, conn, 0)
        if (bound) {
            onDispose { context.unbindService(conn) }
        } else {
            isMonitorRunning = false
            scope.launch { settingsManager.setMonitorRunning(false) }
            onDispose { }
        }
    }

    var useBlur by remember { mutableStateOf(settingsManager.getUseBlurSync()) }
    LaunchedEffect(Unit) {
        settingsManager.useBlur.collect { useBlur = it }
    }

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val backdrop = rememberLutistaBlurBackdrop(useBlur)
    val barColor = backdrop.getAppBarColor()

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.lutistaBlurEffect(backdrop),
                color = barColor,
                title = "主页",
                largeTitle = "主页",
                scrollBehavior = scrollBehavior
            )
        }
    ) { paddingValues ->
    LazyColumn(
        modifier = Modifier
            .then(backdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier)
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .overScrollVertical(),
        contentPadding = PaddingValues(
            top = paddingValues.calculateTopPadding() + 12.dp,
            bottom = 12.dp,
            start = 12.dp,
            end = 12.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            StatusSection(
                isMonitorRunning = isMonitorRunning,
                processedCount = processedCount,
                tetherEnabled = tetherEnabled,
                tetherBusy = tetherBusy,
                tetherSummary = tetherSummary,
                onMonitorToggle = {
                    if (!isMonitorRunning) {
                        val noFilter = selectedFilterIndex == 0
                        val noWatermark = selectedWatermarkIndex == 0
                        if (noFilter && noWatermark) {
                            Toast.makeText(context, "需要至少应用一个效果", Toast.LENGTH_SHORT).show()
                        } else if (monitorPath.isEmpty()) {
                            Toast.makeText(context, "请选择输入文件夹", Toast.LENGTH_SHORT).show()
                            pendingStartAfterPathSelect = true
                            onMonitorFolderClick()
                        } else {
                            isMonitorRunning = true
                            scope.launch {
                                settingsManager.setMonitorRunning(true)
                                startMonitorService()
                            }
                        }
                    } else {
                        isMonitorRunning = false
                        pendingStartAfterPathSelect = false
                        scope.launch {
                            settingsManager.setMonitorRunning(false)
                            stopMonitorService()
                        }
                    }
                },
                onTetherEnabledChange = onTetherEnabledChange,
                onTetherClick = onTetherClick,
                launchPicker = launchPicker
            )
        }

        item {
            MonitorSettingsCard(
                isMonitorRunning = isMonitorRunning,
                onlyNewFiles = onlyNewFiles,
                onOnlyNewFilesChange = {
                    onlyNewFiles = it
                    scope.launch {
                        settingsManager.setOnlyNewFiles(it)
                        if (isMonitorRunning && monitorPath.isNotEmpty()) {
                            startMonitorService()
                        }
                    }
                },
                onMonitorFolderClick = onMonitorFolderClick,
                onOutputFolderClick = onOutputFolderClick,
                monitorPath = monitorPath,
                outputPath = outputPath,
                filterOptions = filterOptions,
                selectedFilterIndex = selectedFilterIndex,
                onFilterSelected = { idx ->
                    selectedFilterIndex = idx
                    scope.launch {
                        if (idx == 0) {
                            settingsManager.setGlobalFilterId(0L)
                        } else {
                            val filter = filtersFromDb.getOrNull(idx - 1)
                            if (filter != null) settingsManager.setGlobalFilterId(filter.id)
                        }
                    }
                },
                watermarkOptions = watermarkOptions,
                selectedWatermarkIndex = selectedWatermarkIndex,
                onWatermarkSelected = { idx ->
                    selectedWatermarkIndex = idx
                    scope.launch {
                        if (idx == 0) {
                            settingsManager.setGlobalWatermarkId("none")
                        } else {
                            val wm = watermarksFromDb.getOrNull(idx - 1)
                            if (wm != null) settingsManager.setGlobalWatermarkId(wm.id.toString())
                        }
                    }
                }
            )
        }
    }
    }
}

@Composable
private fun StatusSection(
    isMonitorRunning: Boolean,
    processedCount: Int,
    tetherEnabled: Boolean,
    tetherBusy: Boolean,
    tetherSummary: String,
    onMonitorToggle: () -> Unit,
    onTetherEnabledChange: (Boolean) -> Unit,
    onTetherClick: () -> Unit,
    launchPicker: () -> Unit = {},
) {
    val dark = colorScheme.background.luminance() < .5f
    val activeText = if (dark) colorScheme.onPrimaryContainer else Color(0xFF1A3825)
    val activeSub = if (dark) colorScheme.onPrimaryContainer.copy(alpha = .72f) else Color(0xFF1A3825).copy(alpha = .66f)
    val activeIconBackground = if (dark) colorScheme.onPrimaryContainer.copy(alpha = .14f) else Color(0xFF1F9D45).copy(alpha = .16f)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 半格 1：实时处理（右上角开关与联机卡对齐）
        Row(Modifier.fillMaxWidth().height(118.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                colors = CardDefaults.defaultColors(color = if (isMonitorRunning) {
                    if (dark) colorScheme.primaryContainer else Color(0xFFDFFAE4)
                } else colorScheme.surfaceVariant),
                onClick = onMonitorToggle,
                showIndication = true,
            ) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    Icon(
                        modifier = Modifier.size(96.dp).offset(x = maxWidth * .58f, y = 46.dp),
                        imageVector = if (isMonitorRunning) Icons.Rounded.AutoAwesome else MiuixIcons.Demibold.Close2,
                        tint = if (isMonitorRunning) Color(0xFF36D167).copy(alpha = .2f) else Color(0xFFFF4444).copy(alpha = .1f),
                        contentDescription = null,
                    )
                    Column(Modifier.fillMaxSize().padding(13.dp), verticalArrangement = Arrangement.SpaceBetween) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(30.dp).clip(RoundedCornerShape(10.dp))
                                    .background(if (isMonitorRunning) activeIconBackground else colorScheme.surface.copy(alpha = .45f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    modifier = Modifier.size(17.dp),
                                    imageVector = if (isMonitorRunning) Icons.Rounded.AutoAwesome else MiuixIcons.Demibold.Close2,
                                    tint = if (isMonitorRunning) Color(0xFF1F9D45).let { if (dark) Color(0xFF36D167) else it } else colorScheme.onSurfaceVariantSummary,
                                    contentDescription = null,
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            Switch(checked = isMonitorRunning, onCheckedChange = { onMonitorToggle() })
                        }
                        Column {
                            Text("实时处理", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1,
                                color = if (isMonitorRunning) activeText else colorScheme.onSurface)
                            Text(if (isMonitorRunning) "已处理 $processedCount 张" else "点击开启",
                                fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (isMonitorRunning) activeSub else colorScheme.onSurfaceVariantSummary.copy(alpha = .66f))
                        }
                    }
                }
            }
            // 半格 2：从相册选择
            Card(modifier = Modifier.weight(1f).fillMaxHeight(), onClick = launchPicker, showIndication = true) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    Icon(
                        modifier = Modifier.size(64.dp).offset(x = maxWidth * .6f, y = 54.dp),
                        imageVector = MiuixIcons.Album,
                        tint = colorScheme.primary.copy(alpha = .12f),
                        contentDescription = null,
                    )
                    Column(Modifier.fillMaxSize().padding(13.dp), verticalArrangement = Arrangement.SpaceBetween) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(colorScheme.primary.copy(alpha = .12f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(MiuixIcons.Album, contentDescription = null, modifier = Modifier.size(17.dp), tint = colorScheme.primary)
                            }
                            Spacer(Modifier.weight(1f))
                            Icon(MiuixIcons.ChevronForward, contentDescription = null, modifier = Modifier.size(15.dp), tint = colorScheme.onSurfaceVariantSummary.copy(alpha = .8f))
                        }
                        Column {
                            Text("从相册选择", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, color = colorScheme.onSurface)
                            Text("批量处理已有照片", fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = colorScheme.onSurfaceVariantSummary.copy(alpha = .66f))
                        }
                    }
                }
            }
        }
        // 全宽：联机模式（开关收到右上角，与半格卡一致）
        Card(modifier = Modifier.fillMaxWidth().height(108.dp), insideMargin = PaddingValues(0.dp)) {
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxSize().clickable(enabled = !tetherBusy, onClick = onTetherClick)
                        .padding(start = 16.dp, top = 13.dp, bottom = 12.dp, end = 78.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(
                            Modifier.size(8.dp).clip(CircleShape).background(
                                when {
                                    tetherBusy -> Color(0xFFB26A00)
                                    tetherEnabled -> Color(0xFF1F9D45)
                                    else -> colorScheme.onSurfaceVariantSummary.copy(alpha = .4f)
                                }
                            )
                        )
                        Text("联机模式", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = colorScheme.onSurface)
                        Icon(MiuixIcons.ChevronForward, contentDescription = null, modifier = Modifier.size(13.dp), tint = colorScheme.onSurfaceVariantSummary.copy(alpha = .3f))
                    }
                    Column {
                        Text(tetherSummary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = colorScheme.onSurfaceVariantSummary)
                        Text(if (tetherBusy) "正在处理，请稍候…" else if (tetherEnabled) "点击进入相机图库" else "插线并选择 PC(Tether)",
                            fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = colorScheme.onSurfaceVariantSummary.copy(alpha = .7f))
                    }
                }
                Box(Modifier.align(Alignment.TopEnd).padding(top = 13.dp, end = 14.dp)) {
                    if (tetherBusy) CircularProgressIndicator(size = 26.dp)
                    else Switch(checked = tetherEnabled, onCheckedChange = onTetherEnabledChange, enabled = !tetherBusy)
                }
            }
        }
    }
}

@Composable
private fun MonitorSettingsCard(
    isMonitorRunning: Boolean = false,
    onlyNewFiles: Boolean,
    onOnlyNewFilesChange: (Boolean) -> Unit,
    onMonitorFolderClick: () -> Unit,
    onOutputFolderClick: () -> Unit,
    monitorPath: String = "",
    outputPath: String = "",
    filterOptions: List<DropdownItem>,
    selectedFilterIndex: Int,
    onFilterSelected: (Int) -> Unit,
    watermarkOptions: List<DropdownItem>,
    selectedWatermarkIndex: Int,
    onWatermarkSelected: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            SwitchPreference(
                title = "仅处理新增文件",
                summary = if (isMonitorRunning) "服务运行中，不可更改" else "开启后只处理输入文件夹中新增的图片",
                checked = onlyNewFiles,
                enabled = !isMonitorRunning,
                onCheckedChange = onOnlyNewFilesChange
            )
            ArrowPreference(
                title = "输入文件夹",
                summary = if (monitorPath.isNotEmpty()) decodeSafPath(monitorPath) else "点击设置输入文件夹",
                enabled = !isMonitorRunning,
                onClick = if (isMonitorRunning) ({}) else onMonitorFolderClick
            )

            ArrowPreference(
                title = "输出文件夹",
                summary = if (outputPath.isNotEmpty()) decodeSafPath(outputPath) else "Pictures/Lutista",
                enabled = !isMonitorRunning,
                onClick = if (isMonitorRunning) ({}) else onOutputFolderClick
            )

            OverlaySpinnerPreference(
                title = "滤镜选择",
                summary = "选择要应用的滤镜",
                enabled = !isMonitorRunning,
                items = filterOptions,
                selectedIndex = selectedFilterIndex,
                onSelectedIndexChange = if (isMonitorRunning) ({ _ -> }) else onFilterSelected
            )

            OverlaySpinnerPreference(
                title = "边框水印选择",
                summary = "选择要应用的边框水印",
                enabled = !isMonitorRunning,
                items = watermarkOptions,
                selectedIndex = selectedWatermarkIndex,
                onSelectedIndexChange = if (isMonitorRunning) ({ _ -> }) else onWatermarkSelected
            )
        }
    }
}

private fun decodeSafPath(uri: String): String {
    if (!uri.startsWith("content://")) return uri.substringAfterLast('/').ifEmpty { uri }
    val path = Uri.decode(uri.substringAfterLast("tree/").substringBefore('?'))
    val trimmed = path.removePrefix("primary:").removePrefix("/")
    return if (trimmed.length > 30) "...${trimmed.takeLast(28)}" else trimmed
}
