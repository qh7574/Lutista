package cn.alittlecookie.lutista.ui.screen

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.alittlecookie.lutista.data.database.entity.FilterEntity
import cn.alittlecookie.lutista.data.database.entity.WatermarkEntity
import cn.alittlecookie.lutista.data.repository.FilterRepository
import cn.alittlecookie.lutista.data.repository.WatermarkRepository
import cn.alittlecookie.lutista.domain.usecase.ExportFilterUseCase
import cn.alittlecookie.lutista.domain.usecase.ImportLutUseCase
import cn.alittlecookie.lutista.manager.BuiltinFilterManager
import cn.alittlecookie.lutista.manager.LogManager
import cn.alittlecookie.lutista.manager.SettingsManager
import cn.alittlecookie.lutista.ui.theme.getAppBarColor
import cn.alittlecookie.lutista.ui.theme.lutistaBlurEffect
import cn.alittlecookie.lutista.ui.theme.rememberLutistaBlurBackdrop
import cn.alittlecookie.lutista.watermark.model.BaseImageElement
import cn.alittlecookie.lutista.watermark.model.Scene
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.koin.compose.koinInject
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Import
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.window.WindowDialog
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

enum class RecipeTab(val label: String) {
    FILTER("滤镜"),
    WATERMARK("边框水印")
}

data class RecipeItem(
    val id: Long,
    val name: String,
    val isEnabled: Boolean = true,
    val type: RecipeTab,
    val isBuiltIn: Boolean = false
)

enum class MoreAction {
    TOGGLE_ENABLE,
    RENAME,
    COPY,
    EXPORT_LUT,
    EXPORT_CONFIG,
    EXPORT_WATERMARK,
    DELETE
}

@Composable
fun RecipeRoomScreen(
    modifier: Modifier = Modifier,
    onEditFilter: (Long) -> Unit = {},
    onEditWatermark: (Long) -> Unit = {},
    selectedSubTab: Int = 0,
    onSubTabChange: (Int) -> Unit = {},
    bottomBarHeight: Dp = 0.dp
) {
    val context = LocalContext.current
    val settingsManager: SettingsManager = koinInject()
    val filterRepository: FilterRepository = koinInject()
    val watermarkRepository: WatermarkRepository = koinInject()
    val builtinFilterManager: BuiltinFilterManager = koinInject()
    val exportFilterUseCase: ExportFilterUseCase = koinInject()
    val importLutUseCase: ImportLutUseCase = koinInject()
    val logger: LogManager = koinInject()
    val scope = rememberCoroutineScope()

    var useBlur by remember { mutableStateOf(settingsManager.getUseBlurSync()) }
    LaunchedEffect(Unit) {
        settingsManager.useBlur.collect { useBlur = it }
    }
    var internalImportTrigger by remember { mutableStateOf(0L) }
    var internalCreateTrigger by remember { mutableStateOf(0L) }

    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val backdrop = rememberLutistaBlurBackdrop(useBlur)
    val barColor = backdrop.getAppBarColor()

    val tabs = RecipeTab.entries.map { it.label }

    // Rename dialog state
    var renameItem by remember { mutableStateOf<RecipeItem?>(null) }
    var renameText by remember { mutableStateOf("") }
    var showTopBarMorePopup by remember { mutableStateOf(false) }

    val filtersFromDb by filterRepository.getAllFilters().collectAsState(initial = emptyList())
    val watermarksFromDb by watermarkRepository.getAllWatermarks().collectAsState(initial = emptyList())

    // `filters` / `watermarks` are session mirrors of the DB rows. Their instances MUST stay stable
    // for the whole lifetime of the screen: the previous `remember(filtersFromDb)` created a brand
    // new list on every Room emission, so the reorder handler mutated one instance while the
    // persistence watcher was still observing another (already discarded) one — nothing was ever
    // written back and the order reverted to the DB value after a restart.
    val filters = remember { mutableStateListOf<RecipeItem>() }
    val watermarks = remember { mutableStateListOf<RecipeItem>() }

    // Ids in the order the database currently stores them. Used to skip a write when a drag ends
    // exactly where it started.
    var persistedFilterOrder by remember { mutableStateOf<List<Long>>(emptyList()) }
    var persistedWatermarkOrder by remember { mutableStateOf<List<Long>>(emptyList()) }

    LaunchedEffect(filtersFromDb) {
        syncRecipeItems(
            target = filters,
            incoming = filtersFromDb.map {
                RecipeItem(
                    id = it.id,
                    name = it.name,
                    isEnabled = it.isEnabled,
                    type = RecipeTab.FILTER,
                    isBuiltIn = it.isBuiltIn
                )
            }
        )
        persistedFilterOrder = filtersFromDb.map { it.id }
    }
    LaunchedEffect(watermarksFromDb) {
        syncRecipeItems(
            target = watermarks,
            incoming = watermarksFromDb.map {
                RecipeItem(
                    id = it.id,
                    name = it.name,
                    isEnabled = it.isEnabled,
                    type = RecipeTab.WATERMARK,
                    isBuiltIn = false
                )
            }
        )
        persistedWatermarkOrder = watermarksFromDb.map { it.id }
    }

    // Persist the order on drag end instead of debouncing a watcher: the 600ms window used to be
    // lost whenever the user left the page or the process died right after a drag.
    fun persistFilterOrder() {
        val orderedIds = filters.map { it.id }
        if (!shouldPersistOrder(orderedIds, persistedFilterOrder)) return
        // Optimistic marker: the order is considered persisted *before* the write, so a second drag
        // ending on the same order is not written twice. Rolled back on failure below, because a
        // failed write produces no Room emission that could correct the marker.
        val previousOrder = persistedFilterOrder
        persistedFilterOrder = orderedIds
        // UNDISPATCHED: the body must start on the calling thread. `rememberCoroutineScope` is
        // cancelled as soon as the screen leaves the composition, and NonCancellable only protects
        // a coroutine that has already started — starting synchronously closes that window.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                // NonCancellable: leaving the screen right after the drag must not abort the batch
                // half-way and leave a partially applied order behind.
                withContext(NonCancellable) {
                    filterRepository.updateSortOrders(orderedIds)
                }
            } catch (e: Exception) {
                // Keep the local order; the next drag (or the next launch) retries from the DB.
                persistedFilterOrder = previousOrder
                logger.e("RecipeRoom", "持久化滤镜顺序失败", e)
            }
        }
    }

    fun persistWatermarkOrder() {
        val orderedIds = watermarks.map { it.id }
        if (!shouldPersistOrder(orderedIds, persistedWatermarkOrder)) return
        val previousOrder = persistedWatermarkOrder
        persistedWatermarkOrder = orderedIds
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(NonCancellable) {
                    watermarkRepository.updateSortOrders(orderedIds)
                }
            } catch (e: Exception) {
                persistedWatermarkOrder = previousOrder
                logger.e("RecipeRoom", "持久化边框水印顺序失败", e)
            }
        }
    }

    val currentList = if (selectedSubTab == 0) filters else watermarks
    val isWatermarkTab = selectedSubTab == 1

    // Auto-increment naming helper
    fun nextAutoName(baseName: String, existingNames: List<String>): String {
        val regex = Regex("^\\Q$baseName\\E(\\d+)$")
        val maxNum = existingNames
            .mapNotNull { regex.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: 0
        return "$baseName${maxNum + 1}"
    }

    // SAF file picker for import
    val importPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { handleImport(it, context, scope, importLutUseCase, filterRepository, watermarkRepository, logger) }
    }

    LaunchedEffect(internalImportTrigger) {
        if (internalImportTrigger > 0) {
            importPicker.launch(arrayOf("*/*"))
        }
    }

    LaunchedEffect(internalCreateTrigger) {
        if (internalCreateTrigger > 0) {
            if (selectedSubTab == 1) {
                val existingNames = watermarksFromDb.map { it.name }
                val name = nextAutoName("新边框水印", existingNames)
                scope.launch {
                    val entity = WatermarkEntity(
                        name = name,
                        configJson = watermarkRepository.serializeScene(
                            Scene(baseImage = BaseImageElement(imageUri = ""))
                        )
                    )
                    val newId = watermarkRepository.insert(entity)
                    onEditWatermark(newId)
                }
            } else {
                val existingNames = filtersFromDb.map { it.name }
                val name = nextAutoName("新滤镜", existingNames)
                scope.launch {
                    val entity = FilterEntity(name = name, sortOrder = -1)
                    val newId = filterRepository.insert(entity)
                    onEditFilter(newId)
                }
            }
        }
    }

    // SAF export state
    data class PendingExport(val filterId: Long, val type: ExportFilterUseCase.ExportType?)
    var pendingExport by remember { mutableStateOf<PendingExport?>(null) }

    val exportLutLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val pending = pendingExport ?: return@rememberLauncherForActivityResult
        pendingExport = null
        if (uri != null) {
            scope.launch {
                when (val result = exportFilterUseCase.exportUnifiedLutTo(pending.filterId, uri)) {
                    is ExportFilterUseCase.ExportResult.Success -> {
                        Toast.makeText(context, "已导出统一LUT", Toast.LENGTH_SHORT).show()
                    }
                    is ExportFilterUseCase.ExportResult.Error -> {
                        Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    val exportConfigLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val pending = pendingExport ?: return@rememberLauncherForActivityResult
        pendingExport = null
        if (uri != null) {
            scope.launch {
                val result = exportFilterUseCase.exportFilterConfigTo(pending.filterId, uri)
                when (result) {
                    is ExportFilterUseCase.ExportResult.Success -> {
                        Toast.makeText(context, "已导出滤镜配置", Toast.LENGTH_SHORT).show()
                    }
                    is ExportFilterUseCase.ExportResult.Error -> {
                        Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    val exportWatermarkLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val pending = pendingExport ?: return@rememberLauncherForActivityResult
        pendingExport = null
        if (uri != null) {
            scope.launch {
                exportWatermarkFromRecipe(context, pending.filterId, watermarkRepository, uri)
            }
        }
    }

    var headerHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val headerHeightDp = with(density) { headerHeightPx.toDp() }

    Box(modifier = modifier.fillMaxSize()) {
        // Recipe list fills the entire Box, including the area behind the header. When the
        // user scrolls, items pass under the header overlay — that scrolling content is exactly
        // what `layerBackdrop` captures and `textureBlur` on the header samples, so the blur
        // shows real list content rather than a blank surface color.
        key(selectedSubTab) {
            RecipeList(
                items = currentList,
                isWatermarkTab = isWatermarkTab,
                onMove = { from, to ->
                    if (selectedSubTab == 0) {
                        val item = filters.removeAt(from.index)
                        filters.add(to.index, item)
                    } else {
                        val item = watermarks.removeAt(from.index)
                        watermarks.add(to.index, item)
                    }
                },
                onReorderFinished = {
                    if (selectedSubTab == 0) persistFilterOrder() else persistWatermarkOrder()
                },
                onEditClick = { item ->
                    if (item.type == RecipeTab.WATERMARK) {
                        onEditWatermark(item.id)
                    } else if (item.isBuiltIn) {
                        scope.launch {
                            val copy = builtinFilterManager.copyBuiltinFilter(item.id)
                            if (copy != null) {
                                Toast.makeText(context, "已创建\"${copy.name}\"滤镜", Toast.LENGTH_SHORT).show()
                                onEditFilter(copy.id)
                            }
                        }
                    } else {
                        onEditFilter(item.id)
                    }
                },
                onMoreAction = { item, action ->
                    when (action) {
                        MoreAction.TOGGLE_ENABLE -> {
                            scope.launch {
                                if (selectedSubTab == 0) {
                                    filterRepository.getFilterById(item.id)?.let {
                                        filterRepository.update(it.copy(isEnabled = !it.isEnabled))
                                    }
                                } else {
                                    watermarkRepository.getWatermarkById(item.id)?.let {
                                        watermarkRepository.update(it.copy(isEnabled = !it.isEnabled))
                                    }
                                }
                            }
                        }
                        MoreAction.RENAME -> {
                            renameItem = item
                            renameText = item.name
                        }
                        MoreAction.COPY -> {
                            if (item.type == RecipeTab.WATERMARK) {
                                scope.launch {
                                    val entity = watermarkRepository.getWatermarkById(item.id)
                                    if (entity != null) {
                                        val newName = "${item.name}-拷贝"
                                        val newEntity = entity.copy(id = 0, name = newName, createdAt = System.currentTimeMillis())
                                        watermarkRepository.insert(newEntity)
                                        Toast.makeText(context, "已复制: $newName", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            } else {
                                scope.launch {
                                    val filterEntity = filterRepository.getFilterById(item.id)
                                    if (filterEntity != null) {
                                        if (filterEntity.isBuiltIn) {
                                            builtinFilterManager.copyBuiltinFilter(item.id)
                                        } else {
                                            val baseName = item.name
                                            val allFilters = filterRepository.getAllFilters().first()
                                            val copyNum = allFilters
                                                .mapNotNull { e ->
                                                    val prefix = "${baseName}-拷贝"
                                                    if (e.name.startsWith(prefix))
                                                        e.name.removePrefix(prefix).toIntOrNull()
                                                    else null
                                                }
                                                .maxOrNull()
                                                ?.plus(1) ?: 1
                                            val newName = "${baseName}-拷贝$copyNum"
                                            val newEntity = filterEntity.copy(
                                                id = 0,
                                                name = newName,
                                                isBuiltIn = false,
                                                createdAt = System.currentTimeMillis(),
                                                updatedAt = System.currentTimeMillis()
                                            )
                                             filterRepository.insert(newEntity)
                                        }
                                    }
                                }
                            }
                        }
                        MoreAction.EXPORT_LUT -> {
                            pendingExport = PendingExport(item.id, ExportFilterUseCase.ExportType.UNIFIED_LUT)
                            exportLutLauncher.launch("${item.name}_unified.cube")
                        }
                        MoreAction.EXPORT_CONFIG -> {
                            pendingExport = PendingExport(item.id, ExportFilterUseCase.ExportType.FILTER_CONFIG)
                            exportConfigLauncher.launch("${item.name}_config.zip")
                        }
                        MoreAction.EXPORT_WATERMARK -> {
                            pendingExport = PendingExport(item.id, null)
                            exportWatermarkLauncher.launch("${item.name}.zip")
                        }
                        MoreAction.DELETE -> {
                            if (!item.isBuiltIn) {
                                scope.launch {
                                    if (item.type == RecipeTab.WATERMARK) {
                                        watermarkRepository.deleteById(item.id)
                                    } else {
                                        filterRepository.deleteById(item.id)
                                    }
                                }
                            }
                        }
                    }
                },
                modifier = Modifier
                    .then(backdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier)
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    top = headerHeightDp,
                    bottom = bottomBarHeight + 16.dp
                )
            )
        }

        // Header overlay — TopAppBar + TabRow. Sibling of the LazyColumn inside the same Box,
        // so they share the coordinate space. `lutistaBlurEffect` samples the backdrop captured
        // on the LazyColumn; scrolling items pass under the header and show as blurred content.
        Column(
            modifier = Modifier
                .lutistaBlurEffect(backdrop)
                .background(barColor)
                .onGloballyPositioned { coordinates ->
                    headerHeightPx = coordinates.size.height
                }
        ) {
            TopAppBar(
                color = barColor,
                title = "配方室",
                largeTitle = "配方室",
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(onClick = { internalCreateTrigger++ }) {
                        Icon(
                            imageVector = MiuixIcons.Add,
                            contentDescription = "新建",
                            tint = MiuixTheme.colorScheme.onSurface
                        )
                    }
                    Box {
                        IconButton(onClick = { showTopBarMorePopup = true }) {
                            Icon(
                                imageVector = MiuixIcons.More,
                                contentDescription = "更多",
                                tint = MiuixTheme.colorScheme.onSurface
                            )
                        }
                        OverlayListPopup(
                            show = showTopBarMorePopup,
                            onDismissRequest = { showTopBarMorePopup = false },
                            alignment = PopupPositionProvider.Align.End
                        ) {
                            ListPopupColumn {
                                val importItem = DropdownItem(
                                    text = "智能导入",
                                    icon = { modifier ->
                                        Icon(
                                            imageVector = MiuixIcons.Import,
                                            contentDescription = null,
                                            modifier = modifier,
                                            tint = MiuixTheme.colorScheme.onSurface
                                        )
                                    }
                                )
                                DropdownImpl(
                                    item = importItem,
                                    optionSize = 1,
                                    isSelected = false,
                                    index = 0,
                                    onSelectedIndexChange = {
                                        showTopBarMorePopup = false
                                        internalImportTrigger++
                                    }
                                )
                            }
                        }
                    }
                }
            )
            TabRowWithContour(
                tabs = tabs,
                selectedTabIndex = selectedSubTab,
                onTabSelected = { onSubTabChange(it) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        // Rename dialog
        val renameTarget = renameItem
        if (renameTarget != null) {
            RenameDialog(
                currentName = renameText,
                onNameChange = { renameText = it },
                onConfirm = {
                    scope.launch {
                        if (renameTarget.type == RecipeTab.WATERMARK) {
                            val entity = watermarkRepository.getWatermarkById(renameTarget.id)
                            if (entity != null) {
                                watermarkRepository.update(entity.copy(name = renameText.trim()))
                            }
                        } else {
                            val entity = filterRepository.getFilterById(renameTarget.id)
                            if (entity != null) {
                                filterRepository.update(entity.copy(name = renameText.trim()))
                            }
                        }
                        renameItem = null
                    }
                },
                onDismiss = { renameItem = null }
            )
        }
    }
}

/**
 * Decides whether a finished drag has to be written back to the database.
 *
 * @param current ids in the order the user currently sees on screen.
 * @param persisted ids in the order the database is known to store; kept in sync by the DB
 *     emissions and updated optimistically when a write is started.
 * @return `true` only when there is something to write: order changed and the list is not empty.
 */
internal fun shouldPersistOrder(current: List<Long>, persisted: List<Long>): Boolean =
    current.isNotEmpty() && current != persisted

/**
 * Mirrors [incoming] (a database snapshot) into [target] (the list rendered on screen).
 *
 * The drag gesture mutates [target] directly and the resulting order is written back to the DB
 * afterwards, so that write-back itself produces a new database snapshot. Blindly replacing
 * [target] with every snapshot would fight the user's drag (and, with the old debounce watcher,
 * cancel an in-flight write halfway). Therefore only a *structural* change (rows added or removed)
 * adopts the database order; otherwise the current on-screen order is preserved and only the
 * per-row fields are refreshed.
 *
 * @param target session-local list rendered by the UI; mutated in place.
 * @param incoming rows read from the database, already ordered by `sortOrder`.
 */
internal fun syncRecipeItems(
    target: SnapshotStateList<RecipeItem>,
    incoming: List<RecipeItem>
) {
    val targetIds = target.map { it.id }
    val incomingIds = incoming.map { it.id }
    val structuralChange =
        targetIds.size != incomingIds.size || targetIds.toSet() != incomingIds.toSet()

    if (structuralChange) {
        target.clear()
        target.addAll(incoming)
        return
    }

    incoming.forEach { fresh ->
        val index = target.indexOfFirst { it.id == fresh.id }
        if (index < 0) return@forEach
        if (target[index] != fresh) target[index] = fresh
    }
}

private fun handleImport(
    uri: Uri,
    context: Context,
    scope: CoroutineScope,
    importLutUseCase: ImportLutUseCase,
    filterRepository: FilterRepository,
    watermarkRepository: WatermarkRepository,
    logger: LogManager
) {
    val fileName = uri.lastPathSegment ?: "unknown"
    when (val ext = fileName.substringAfterLast('.').lowercase()) {
        "cube", "vlt" -> {
            scope.launch {
                when (val result = importLutUseCase(uri, ImportLutUseCase.DuplicateAction.RENAME)) {
                    is ImportLutUseCase.ImportResult.Success -> {
                        Toast.makeText(context, "已导入: ${result.filterName}", Toast.LENGTH_SHORT).show()
                    }
                    is ImportLutUseCase.ImportResult.Error -> {
                        Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                    }
                    is ImportLutUseCase.ImportResult.DuplicateExists -> {
                        Toast.makeText(context, "滤镜 \"${result.filterName}\" 已存在", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        "zip" -> {
            scope.launch {
                try {
                    val content = context.contentResolver.openInputStream(uri) ?: return@launch
                    val zis = ZipInputStream(content)
                    var hasCubeOrVlt = false
                    var hasConfigJson = false
                    var isWatermark = false
                    var configName: String? = null
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val entryName = entry.name.lowercase()
                        if (entryName.endsWith(".cube") || entryName.endsWith(".vlt")) {
                            hasCubeOrVlt = true
                        }
                        if (entryName.endsWith(".json")) {
                            hasConfigJson = true
                            try {
                                val bytes = zis.readBytes()
                                val json = JSONObject(String(bytes))
                                configName = json.optString("name", "")
                                if ("watermark" == json.optString("type")) isWatermark = true
                            } catch (_: Exception) {}
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                    zis.close()
                    content.close()

                    if (isWatermark) {
                        importWatermarkPackage(context, uri, watermarkRepository, logger)
                    } else if (hasCubeOrVlt && hasConfigJson) {
                        // Filter package
                        importFilterPackage(context, uri, filterRepository, logger)
                    } else {
                        Toast.makeText(context, "无法识别的压缩包格式", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    logger.e("Import", "导入压缩包失败", e)
                    Toast.makeText(context, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        else -> {
            Toast.makeText(context, "不支持的文件格式: $ext", Toast.LENGTH_SHORT).show()
        }
    }
}

private suspend fun importFilterPackage(
    context: Context,
    uri: Uri,
    filterRepository: FilterRepository,
    logger: LogManager
) {
    try {
        val content = context.contentResolver.openInputStream(uri) ?: return
        val zis = ZipInputStream(content)
        val filterDir = File(context.filesDir, "filters")
        filterDir.mkdirs()

        var configJson: JSONObject? = null
        var lut1Path: String? = null
        var lut2Path: String? = null
        var filterName = "导入滤镜"

        var entry = zis.nextEntry
        while (entry != null) {
            val rawName = entry.name.substringAfterLast('/')
            if (rawName.isEmpty()) { zis.closeEntry(); entry = zis.nextEntry; continue }

            val bytes = zis.readBytes()

            if (rawName.endsWith(".json", ignoreCase = true)) {
                try {
                    configJson = JSONObject(String(bytes))
                    filterName = configJson.optString("name", filterName)
                } catch (_: Exception) {}
            } else if (rawName.endsWith(".cube", ignoreCase = true) || rawName.endsWith(".vlt", ignoreCase = true)) {
                val subDir = File(filterDir, "import_${System.currentTimeMillis()}")
                subDir.mkdirs()
                val destFile = File(subDir, rawName)
                FileOutputStream(destFile).use { it.write(bytes) }

                if (rawName.contains("1", ignoreCase = true) || lut1Path == null) {
                    lut1Path = destFile.absolutePath
                } else {
                    lut2Path = destFile.absolutePath
                }
            }

            zis.closeEntry()
            entry = zis.nextEntry
        }
        zis.close()
        content.close()

        if (configJson != null) {
            val json = configJson
            val entity = FilterEntity(
                name = filterName,
                lutPath1 = lut1Path,
                lutConcentration1 = json.optDouble("lutConcentration1", 1.0).toFloat(),
                lutPath2 = lut2Path,
                lutConcentration2 = json.optDouble("lutConcentration2", 1.0).toFloat(),
                // Was missing: exportFilterConfig() writes this key, so an
                // imported pack silently lost the global LUT strength.
                lutGlobalStrength = json.optDouble("lutGlobalStrength", 1.0).toFloat(),
                lutPrecision = json.optInt("lutPrecision", 33),
                exposure = json.optDouble("exposure", 0.0).toFloat(),
                contrast = json.optDouble("contrast", 0.0).toFloat(),
                highlights = json.optDouble("highlights", 0.0).toFloat(),
                shadows = json.optDouble("shadows", 0.0).toFloat(),
                saturation = json.optDouble("saturation", 0.0).toFloat(),
                colorTemperature = json.optDouble("colorTemperature", 0.0).toFloat(),
                tint = json.optDouble("tint", 0.0).toFloat(),
                curveJson = json.optString("curveJson", "{}"),
                lchJson = json.optString("lchJson", "{}"),
                vignetteStrength = json.optDouble("vignetteStrength", 0.0).toFloat(),
                grainJson = json.optString("grainJson", "{}"),
                isBuiltIn = false,
                sortOrder = -1,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            filterRepository.insert(entity)
            Toast.makeText(context, "已导入滤镜: $filterName", Toast.LENGTH_SHORT).show()
            logger.i("Import", "滤镜包导入成功: $filterName")
        } else {
            Toast.makeText(context, "压缩包中未找到配置文件", Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        logger.e("Import", "导入滤镜包失败", e)
        Toast.makeText(context, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

private suspend fun exportWatermarkFromRecipe(
    context: Context,
    watermarkId: Long,
    watermarkRepository: WatermarkRepository,
    uri: Uri
) = withContext(Dispatchers.IO) {
    try {
        val scene = watermarkRepository.getSceneById(watermarkId)
            ?: throw IllegalStateException("水印不存在")
        val entity = watermarkRepository.getWatermarkById(watermarkId)
        val configJson = cn.alittlecookie.lutista.watermark.util.SceneSerializer.toJson(scene)
        val wrapper = JSONObject().apply {
            put("type", "watermark")
            put("version", 1)
            put("name", entity?.name ?: "边框水印")
            put("config", JSONObject(configJson))
        }

        val refPattern = java.util.regex.Pattern.compile(""""(imageUri|fontUri)"\s*:\s*"([^"]+)"""")
        val assetsDir = File(context.filesDir, "watermark_assets")
        val referencedFiles = mutableListOf<File>()
        val matcher = refPattern.matcher(configJson)
        while (matcher.find()) {
            val fileName = matcher.group(2)?.substringAfterLast('/') ?: continue
            val file = File(assetsDir, fileName)
            if (file.isFile) referencedFiles.add(file)
        }

        context.contentResolver.openOutputStream(uri)?.use { output ->
            java.util.zip.ZipOutputStream(output).use { zos ->
                zos.putNextEntry(java.util.zip.ZipEntry("watermark_config.json"))
                zos.write(wrapper.toString(2).toByteArray())
                zos.closeEntry()

                for (file in referencedFiles) {
                    zos.putNextEntry(java.util.zip.ZipEntry("assets/${file.name}"))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "边框水印已导出", Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}

private suspend fun importWatermarkPackage(
    context: Context,
    uri: Uri,
    watermarkRepository: WatermarkRepository,
    logger: LogManager
) {
    try {
        val content = context.contentResolver.openInputStream(uri) ?: return
        val zis = ZipInputStream(content)
        val assetsDir = File(context.filesDir, "watermark_assets")
        assetsDir.mkdirs()

        var watermarkName = "导入边框水印"
        var configJsonString: String? = null

        var entry = zis.nextEntry
        while (entry != null) {
            val rawName = entry.name.substringAfterLast('/')
            if (rawName.isEmpty()) { zis.closeEntry(); entry = zis.nextEntry; continue }
            val bytes = zis.readBytes()

            if (rawName == "watermark_config.json") {
                try {
                    val wrapper = JSONObject(String(bytes))
                    watermarkName = wrapper.optString("name", watermarkName)
                    configJsonString = wrapper.getJSONObject("config").toString()
                } catch (_: Exception) {}
            } else if (rawName.isNotEmpty() && !rawName.endsWith(".json")) {
                val destFile = File(assetsDir, rawName)
                FileOutputStream(destFile).use { it.write(bytes) }
            }
            zis.closeEntry()
            entry = zis.nextEntry
        }
        zis.close()
        content.close()

        if (configJsonString == null) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "压缩包中未找到水印配置", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val entity = WatermarkEntity(
            name = watermarkName,
            configJson = configJsonString
        )
        watermarkRepository.insert(entity)
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "已导入边框水印: $watermarkName", Toast.LENGTH_SHORT).show()
        }
        logger.i("Import", "边框水印导入成功: $watermarkName")
    } catch (e: Exception) {
        logger.e("Import", "导入边框水印失败", e)
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
private fun RecipeList(
    items: List<RecipeItem>,
    isWatermarkTab: Boolean,
    onMove: suspend CoroutineScope.(from: LazyListItemInfo, to: LazyListItemInfo) -> Unit,
    onReorderFinished: () -> Unit,
    onEditClick: (RecipeItem) -> Unit,
    onMoreAction: (RecipeItem, MoreAction) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(bottom = 16.dp)
) {
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState, onMove = onMove)

    LazyColumn(
        state = lazyListState,
        modifier = modifier.overScrollVertical(),
        contentPadding = contentPadding
    ) {
        itemsIndexed(items, key = { _, item -> item.id }) { _, item ->
            ReorderableItem(reorderableState, key = item.id) { isDragging ->
                RecipeListItem(
                    item = item,
                    isDragging = isDragging,
                    isWatermarkTab = isWatermarkTab,
                    dragHandleModifier = Modifier.draggableHandle(
                        onDragStarted = {},
                        onDragStopped = { onReorderFinished() }
                    ),
                    onEditClick = { onEditClick(item) },
                    onMoreAction = { action -> onMoreAction(item, action) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }
    }
}


@Composable
private fun RecipeListItem(
    item: RecipeItem,
    isDragging: Boolean,
    isWatermarkTab: Boolean,
    dragHandleModifier: Modifier = Modifier,
    onEditClick: () -> Unit,
    onMoreAction: (MoreAction) -> Unit,
    modifier: Modifier = Modifier
) {
    var showMorePopup by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (isDragging) Modifier.border(2.dp, MiuixTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                else Modifier
            ),
        cornerRadius = 12.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 12.dp,
                    top = 8.dp,
                    end = 6.dp,
                    bottom = 8.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .then(dragHandleModifier),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = MiuixIcons.Demibold.Sort,
                    contentDescription = "拖拽排序",
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.name,
                        style = MiuixTheme.textStyles.body2,
                        fontWeight = FontWeight.Bold,
                        color = if (item.isEnabled) {
                            MiuixTheme.colorScheme.onSurface
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f)
                        }
                    )
                    if (item.isBuiltIn) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .background(
                                    color = MiuixTheme.colorScheme.primary.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "内置",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.primary
                            )
                        }
                    }
                }
                if (!item.isEnabled) {
                    Text(
                        text = "已禁用",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f)
                    )
                }
            }

            IconButton(
                onClick = onEditClick,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = MiuixIcons.Demibold.Tune,
                    contentDescription = "编辑",
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    modifier = Modifier.size(20.dp)
                )
            }

            Box {
                IconButton(
                    onClick = { showMorePopup = true },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = MiuixIcons.Demibold.More,
                        contentDescription = "更多",
                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                        modifier = Modifier.size(20.dp)
                    )
                }

                OverlayListPopup(
                    show = showMorePopup,
                    onDismissRequest = { showMorePopup = false },
                    alignment = PopupPositionProvider.Align.End
                ) {
                    ListPopupColumn {
                        val menuItems = buildList {
                            add(if (item.isEnabled) "禁用" else "启用")
                            if (!item.isBuiltIn || item.type == RecipeTab.WATERMARK) add("重命名")
                            add("拷贝")
                            if (isWatermarkTab) {
                                add("导出边框水印")
                            } else {
                                add("导出统一LUT")
                                add("导出滤镜配置")
                            }
                            if (!item.isBuiltIn || item.type == RecipeTab.WATERMARK) add("删除")
                        }

                        menuItems.forEachIndexed { index, text ->
                            val isDestructive = text == "删除"
                            DropdownImpl(
                                text = text,
                                optionSize = menuItems.size,
                                isSelected = false,
                                index = index,
                                dropdownColors = if (isDestructive) {
                                    DropdownDefaults.dropdownColors(
                                        contentColor = MiuixTheme.colorScheme.error
                                    )
                                } else {
                                    DropdownDefaults.dropdownColors()
                                },
                                onSelectedIndexChange = {
                                    showMorePopup = false
                                    when (text) {
                                        "禁用", "启用" -> onMoreAction(MoreAction.TOGGLE_ENABLE)
                                        "重命名" -> onMoreAction(MoreAction.RENAME)
                                        "拷贝" -> onMoreAction(MoreAction.COPY)
                                        "导出统一LUT" -> onMoreAction(MoreAction.EXPORT_LUT)
                                        "导出滤镜配置" -> onMoreAction(MoreAction.EXPORT_CONFIG)
                                        "导出边框水印" -> onMoreAction(MoreAction.EXPORT_WATERMARK)
                                        "删除" -> onMoreAction(MoreAction.DELETE)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RecipeRoomTopBar(
    onCreateClick: () -> Unit,
    onImportClick: () -> Unit
) {
    IconButton(onClick = onCreateClick) {
        Icon(
            imageVector = MiuixIcons.Add,
            contentDescription = "新建",
            tint = MiuixTheme.colorScheme.onSurface
        )
    }
    IconButton(onClick = onImportClick) {
        Icon(
            imageVector = MiuixIcons.Import,
            contentDescription = "导入",
            tint = MiuixTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun RenameDialog(
    currentName: String,
    onNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    WindowDialog(
        show = true,
        onDismissRequest = onDismiss
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "重命名",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface
            )
            TextField(
                value = currentName,
                onValueChange = onNameChange,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        color = MiuixTheme.colorScheme.secondary
                    )
                ) {
                    Text("取消", color = MiuixTheme.colorScheme.primary)
                }
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    enabled = currentName.trim().isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(
                        color = MiuixTheme.colorScheme.primary
                    )
                ) {
                    Text("确定", color = Color.White)
                }
            }
        }
    }
}
