package cn.alittlecookie.lutista.ui.screen

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.alittlecookie.lutista.data.repository.WatermarkRepository
import cn.alittlecookie.lutista.util.ExifUtils
import cn.alittlecookie.lutista.watermark.engine.UnitConverter
import cn.alittlecookie.lutista.watermark.manager.HistoryManager
import cn.alittlecookie.lutista.watermark.model.Anchor
import cn.alittlecookie.lutista.watermark.model.BorderWidth
import cn.alittlecookie.lutista.watermark.model.ContainerElement
import cn.alittlecookie.lutista.watermark.model.ContainerMode
import cn.alittlecookie.lutista.watermark.model.CrossAxisAlign
import cn.alittlecookie.lutista.watermark.model.Element
import cn.alittlecookie.lutista.watermark.model.ImageElement
import cn.alittlecookie.lutista.watermark.model.LayoutResult
import cn.alittlecookie.lutista.watermark.model.MainAxisAlign
import cn.alittlecookie.lutista.watermark.model.Margin
import cn.alittlecookie.lutista.watermark.model.Padding
import cn.alittlecookie.lutista.watermark.model.SizeMode
import cn.alittlecookie.lutista.watermark.model.TextAlign
import cn.alittlecookie.lutista.watermark.model.TextElement
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ColorPalette
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Backup
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Forward
import top.yukonga.miuix.kmp.icon.extended.Image
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Reset
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

@Composable
fun WatermarkEditorScreen(
    watermarkId: Long,
    onBackClick: () -> Unit,
    onHasChangesChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val watermarkRepository: WatermarkRepository = koinInject()
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())

    val historyManager = remember { HistoryManager() }

    val state = remember(watermarkId) {
        WatermarkEditorViewModel(
            watermarkId = watermarkId,
            context = context,
            watermarkRepository = watermarkRepository,
            historyManager = historyManager,
            scope = scope
        )
    }

    LaunchedEffect(watermarkId) {
        kotlinx.coroutines.delay(1)
        state.load()
    }
    DisposableEffect(Unit) { onDispose { state.release() } }

    var showBackDialog by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }

    /**
     * 本页唯一的返回入口。
     *
     * 三条返回路径（顶栏返回箭头 / 系统返回键 / 外层下发的侧滑信号）都必须经过这里，
     * 保证「有未保存修改时先弹『保存更改?』」的守卫不会被任何一条路径绕过。
     * 尤其是侧滑：miuix-nav 的 swipeDismiss 提交直接调 NavDisplay.onBack，不经过 BackHandler。
     */
    fun requestBack() {
        if (state.hasChanges) showBackDialog = true else onBackClick()
    }

    // 有未保存修改时外层会关掉 miui 的侧滑关闭（防止绕过守卫），这里补一个页面级水平拖拽：
    // 命中后走同一个 requestBack()，所以「侧滑 → 弹框」在脏状态下依然成立；其余时候是空 Modifier。
    val dirtyBackSwipe = rememberDirtyBackSwipeModifier(
        enabled = state.hasChanges,
        onBack = { requestBack() }
    )

    val previewImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { state.loadPreviewImage(it) } }

    var pendingImageElementId by remember { mutableStateOf<String?>(null) }
    val imageElementPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val elementId = pendingImageElementId ?: return@rememberLauncherForActivityResult
        pendingImageElementId = null
        if (uri != null) {
            scope.launch {
                val localPath = state.importElementImage(uri)
                if (localPath != null) {
                    state.updateImageElement(elementId) { it.copy(imageUri = localPath) }
                }
            }
        }
    }

    var pendingFontElementId by remember { mutableStateOf<String?>(null) }
    val fontPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val elementId = pendingFontElementId ?: return@rememberLauncherForActivityResult
        pendingFontElementId = null
        if (uri != null) {
            scope.launch {
                val localPath = state.importFont(uri)
                if (localPath != null) {
                    state.updateTextElement(elementId) { it.copy(fontUri = localPath) }
                }
            }
        }
    }

    val exportWatermarkLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                exportWatermarkZip(context, state, uri)
            }
        }
    }

    Scaffold(
        modifier = dirtyBackSwipe,
        topBar = {
            SmallTopAppBar(
                title = state.watermarkName,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = { requestBack() }) {
                        Icon(MiuixIcons.Back, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { state.undo() }, enabled = state.canUndo) {
                        Icon(
                            Icons.AutoMirrored.Filled.Undo, "撤销",
                            tint = if (state.canUndo) MiuixTheme.colorScheme.onSurface
                            else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.38f)
                        )
                    }
                    IconButton(onClick = { state.redo() }, enabled = state.canRedo) {
                        Icon(
                            Icons.AutoMirrored.Filled.Redo, "重做",
                            tint = if (state.canRedo) MiuixTheme.colorScheme.onSurface
                            else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.38f)
                        )
                    }
                    Box {
                        IconButton(onClick = { showMoreMenu = true }) {
                            Icon(MiuixIcons.More, "更多")
                        }
                        OverlayListPopup(
                            show = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false },
                            alignment = PopupPositionProvider.Align.End
                        ) {
                            ListPopupColumn {
                                DropdownImpl("重命名", optionSize = 3, isSelected = false, index = 0,
                                    dropdownColors = DropdownDefaults.dropdownColors(),
                                    onSelectedIndexChange = {
                                        showMoreMenu = false
                                        renameText = state.watermarkName
                                        showRenameDialog = true
                                    })
                                DropdownImpl("导出边框水印", optionSize = 3, isSelected = false, index = 1,
                                    dropdownColors = DropdownDefaults.dropdownColors(),
                                    onSelectedIndexChange = {
                                        showMoreMenu = false
                                        exportWatermarkLauncher.launch("${state.watermarkName}.zip")
                                    })
                                DropdownImpl("保存", optionSize = 3, isSelected = false, index = 2,
                                    dropdownColors = DropdownDefaults.dropdownColors(),
                                    onSelectedIndexChange = {
                                        showMoreMenu = false
                                        scope.launch {
                                            if (state.save()) Toast.makeText(context, "已保存", Toast.LENGTH_SHORT).show()
                                            else Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
                                        }
                                    })
                            }
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues)
        ) {
            PreviewArea(
                state = state,
                onImageClick = { previewImagePicker.launch(arrayOf("image/*")) },
                modifier = Modifier.fillMaxWidth().weight(0.4f).padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // Bottom area: breadcrumb + content, fixed 60% ratio
            Column(modifier = Modifier.fillMaxWidth().weight(0.6f)) {
                if (state.navigationStack.isNotEmpty()) {
                    NavigationBreadcrumb(
                        containerId = state.navigationStack.last(), state = state,
                        onBack = { state.navigateBack() }
                    )
                }

                val selectedElement = state.selectedElementId?.let { state.findElementById(it) }
                val isBaseImageSelected = state.selectedElementId == "__baseImage__"

                if (selectedElement != null && selectedElement is ContainerElement) {
                    ContainerPropertyEditor(selectedElement, state,
                        modifier = Modifier.fillMaxSize())
                } else if (selectedElement != null && !isBaseImageSelected) {
                    PropertyEditor(selectedElement, state,
                        onPickImage = { elementId ->
                            pendingImageElementId = elementId
                            imageElementPicker.launch(arrayOf("image/*"))
                        },
                        onPickFont = { elementId ->
                            pendingFontElementId = elementId
                            fontPicker.launch(arrayOf("font/*", "application/*"))
                        },
                        modifier = Modifier.fillMaxSize())
                } else if (isBaseImageSelected) {
                    BaseImageEditor(state,
                        modifier = Modifier.fillMaxSize())
                } else {
                    NodeTree(state = state,
                        modifier = Modifier.fillMaxSize())
                }
            }
        }

        if (showRenameDialog) {
            RenameDialog(
                currentName = renameText, onNameChange = { renameText = it },
                onConfirm = {
                    val t = renameText.trim()
                    if (t.isNotEmpty()) state.updateName(t)
                    showRenameDialog = false
                },
                onDismiss = { showRenameDialog = false }
            )
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
                    Text("当前水印有未保存的更改", fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Button(onClick = {
                        showBackDialog = false
                        scope.launch {
                            if (state.save()) onBackClick()
                            else Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
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

@Composable
private fun PreviewArea(
    state: WatermarkEditorViewModel,
    onImageClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.clickable { onImageClick() }, cornerRadius = 16.dp) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val layout = state.layoutResult
            val baseBitmap = state.baseImageBitmap
            val converter = state.converter
            val appContext = LocalContext.current

            if (layout != null && baseBitmap != null && converter != null) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val scaleToFit = minOf(
                        size.width / layout.canvasWidth,
                        size.height / layout.canvasHeight
                    )
                    val drawScene = state.previewScene ?: state.scene

                    translate(
                        left = (size.width - layout.canvasWidth * scaleToFit) / 2,
                        top = (size.height - layout.canvasHeight * scaleToFit) / 2
                    ) {
                        clipRect {
                            // Border fill
                            drawRect(
                                color = Color(drawScene.baseImage.borderColor),
                                size = Size(
                                    layout.canvasWidth * scaleToFit,
                                    layout.canvasHeight * scaleToFit
                                )
                            )

                            // Base image
                            val borderPx = drawScene.baseImage.borderWidth.let { bw ->
                                Offset(
                                    converter.gmToPx(bw.left) * scaleToFit,
                                    converter.gmToPx(bw.top) * scaleToFit
                                )
                            }

                            drawImage(
                                image = baseBitmap,
                                dstOffset = androidx.compose.ui.unit.IntOffset(
                                    borderPx.x.toInt(), borderPx.y.toInt()
                                ),
                                dstSize = androidx.compose.ui.unit.IntSize(
                                    (converter.imageWidth * scaleToFit).toInt(),
                                    (converter.imageHeight * scaleToFit).toInt()
                                )
                            )

                    // Elements — render at preview resolution
                    val renderConverter = UnitConverter(
                        (converter.imageWidth * scaleToFit).toInt().coerceAtLeast(1),
                        (converter.imageHeight * scaleToFit).toInt().coerceAtLeast(1)
                    )
                    drawSceneElements(
                        state = state, layout = layout, converter = converter,
                        renderConverter = renderConverter, scaleToFit = scaleToFit, appCtx = appContext
                    )
                        }
                    }
                }
            } else {
                // Loading / no preview
                Text("加载中...", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }

            Box(
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("点击更换示例图", fontSize = 12.sp, color = Color.White)
            }
        }
    }
}

private fun DrawScope.drawSceneElements(
    state: WatermarkEditorViewModel, layout: LayoutResult, converter: UnitConverter,
    renderConverter: UnitConverter, scaleToFit: Float, appCtx: android.content.Context
) {
    val elements = state.previewElements ?: state.scene.elements
    elements.asReversed().forEach { element ->
        drawElementRecursive(element, layout, converter, renderConverter, scaleToFit, 1f, state, appCtx)
    }
}

private fun DrawScope.drawElementRecursive(
    element: Element, layout: LayoutResult, converter: UnitConverter,
    renderConverter: UnitConverter, scaleToFit: Float, parentOpacity: Float,
    state: WatermarkEditorViewModel, appCtx: android.content.Context
) {
    val bounds = layout.elementBounds[element.id]
    if (bounds == null) {
        android.util.Log.w("PreviewCanvas", "drawElementRecursive: MISSING bounds for ${element.id} (${element::class.simpleName})")
        return
    }
    val opacity = element.opacity * parentOpacity

    when (element) {
        is TextElement -> {
            val scaledBounds = bounds.copy(
                x = bounds.x * scaleToFit,
                y = bounds.y * scaleToFit,
                width = bounds.width * scaleToFit,
                height = bounds.height * scaleToFit,
                marginLeft = bounds.marginLeft * scaleToFit,
                marginTop = bounds.marginTop * scaleToFit,
                marginRight = bounds.marginRight * scaleToFit,
                marginBottom = bounds.marginBottom * scaleToFit
            )
            val textBitmap = state.getOrCreateTextBitmap(element, renderConverter, scaledBounds)
            if (textBitmap.width > 0 && textBitmap.height > 0) {
                drawImage(
                    image = textBitmap.asImageBitmap(),
                    dstOffset = androidx.compose.ui.unit.IntOffset(
                        (bounds.x * scaleToFit).toInt(),
                        (bounds.y * scaleToFit).toInt()
                    ),
                    dstSize = androidx.compose.ui.unit.IntSize(textBitmap.width, textBitmap.height),
                    alpha = opacity
                )
            }
        }
        is ImageElement -> {
            if (element.imageUri.isNotEmpty()) {
                try {
                    val inSampleSize = calculateInSampleSize(scaleToFit)
                    val opts = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
                    val imgBitmap = if (element.imageUri.startsWith("content://")) {
                        appCtx.contentResolver.openInputStream(android.net.Uri.parse(element.imageUri))?.use { stream ->
                            BitmapFactory.decodeStream(stream, null, opts)
                        }?.let { ExifUtils.correctOrientation(it, appCtx, android.net.Uri.parse(element.imageUri)) }
                    } else {
                        BitmapFactory.decodeFile(element.imageUri, opts)
                            ?.let { ExifUtils.correctOrientation(it, element.imageUri) }
                    }
                    if (imgBitmap != null) {
                        drawImage(
                            image = imgBitmap.asImageBitmap(),
                            dstOffset = androidx.compose.ui.unit.IntOffset(
                                (bounds.x * scaleToFit).toInt(),
                                (bounds.y * scaleToFit).toInt()
                            ),
                            dstSize = androidx.compose.ui.unit.IntSize(
                                (bounds.width * scaleToFit).toInt(),
                                (bounds.height * scaleToFit).toInt()
                            ),
                            alpha = opacity
                        )
                    }
                } catch (_: Exception) {}
            }
        }
        is ContainerElement -> {
            element.children.forEach {
                drawElementRecursive(it, layout, converter, renderConverter, scaleToFit, opacity, state, appCtx)
            }
        }
    }
}

@Composable
private fun NavigationBreadcrumb(
    containerId: String, state: WatermarkEditorViewModel, onBack: () -> Unit
) {
    val container = state.findElementById(containerId) as? ContainerElement ?: return
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(32.dp)) {
            Icon(MiuixIcons.Back, "返回上级", modifier = Modifier.size(18.dp),
                tint = MiuixTheme.colorScheme.primary)
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(state.getDisplayName(container), fontSize = 14.sp, fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.primary)
    }
}

@Composable
private fun NodeTree(
    state: WatermarkEditorViewModel, modifier: Modifier = Modifier
) {
    var showAddMenu by remember { mutableStateOf(false) }
    val elements = state.getCurrentLevelElements()
    val scope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()

    val reorderableState = rememberReorderableLazyListState(lazyListState, onMove = { from, to ->
        state.moveElement(from.index, to.index)
    })

    Box(modifier = modifier.padding(horizontal = 16.dp)) {
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(elements, key = { _, el -> el.id }) { index, el ->
                ReorderableItem(reorderableState, key = el.id) { isDragging ->
                    val dragModifier = Modifier.draggableHandle(
                        onDragStarted = { state.onDragStart() },
                        onDragStopped = { state.onDragEnd() }
                    )
                    NodeTreeItem(
                        element = el, state = state, index = index,
                        isDragging = isDragging,
                        dragHandleModifier = dragModifier
                    )
                }
            }
            if (state.navigationStack.isEmpty()) {
                item { BaseImageEntry(state) }
            }
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Box {
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable { showAddMenu = true }.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(MiuixIcons.Add, "添加", tint = MiuixTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("添加", fontSize = 14.sp, color = MiuixTheme.colorScheme.primary)
                    }
                    OverlayListPopup(
                        show = showAddMenu, onDismissRequest = { showAddMenu = false },
                        alignment = PopupPositionProvider.Align.End
                    ) {
                        ListPopupColumn {
                            DropdownImpl("添加文字", optionSize = 3, isSelected = false, index = 0,
                                dropdownColors = DropdownDefaults.dropdownColors(),
                                onSelectedIndexChange = { showAddMenu = false; state.addTextElement() })
                            DropdownImpl("添加图片", optionSize = 3, isSelected = false, index = 1,
                                dropdownColors = DropdownDefaults.dropdownColors(),
                                onSelectedIndexChange = { showAddMenu = false; state.addImageElement() })
                            DropdownImpl("添加容器", optionSize = 3, isSelected = false, index = 2,
                                dropdownColors = DropdownDefaults.dropdownColors(),
                                onSelectedIndexChange = { showAddMenu = false; state.addContainerElement() })
                        }
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun NodeTreeItem(
    element: Element, state: WatermarkEditorViewModel,
    index: Int = 0, isDragging: Boolean = false,
    dragHandleModifier: Modifier = Modifier
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .then(
                if (isDragging) Modifier.border(2.dp, MiuixTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                else Modifier
            ),
        cornerRadius = 12.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Drag handle (leftmost)
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .then(dragHandleModifier),
                contentAlignment = Alignment.Center
            ) {
                Icon(MiuixIcons.Sort, "拖拽排序",
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.width(8.dp))

            val (label, icon) = when (element) {
                is ContainerElement -> Pair(state.getDisplayName(element), MiuixIcons.Backup)
                is TextElement -> Pair(element.content.take(10).ifEmpty { "文字" }, MiuixIcons.Edit)
                is ImageElement -> Pair(element.imageUri.substringAfterLast('/').ifEmpty { "图片" }, MiuixIcons.Image)
            }
            Icon(icon, null, tint = MiuixTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(label, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                    .clickable {
                        when (element) {
                            is ContainerElement -> state.navigateInto(element.id)
                            else -> state.selectElement(element.id)
                        }
                    })
            if (element is ContainerElement) {
//                Icon(MiuixIcons.Forward, "进入", tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
//                    modifier = Modifier.size(18.dp))
//                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = { state.selectElement(element.id) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(MiuixIcons.Edit, "编辑容器属性", tint = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp))
                }
            } else {
                IconButton(onClick = { state.selectElement(element.id) }, modifier = Modifier.size(32.dp)) {
                    Icon(MiuixIcons.Edit, "编辑", tint = MiuixTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun BaseImageEntry(state: WatermarkEditorViewModel) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), cornerRadius = 12.dp) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .clickable { state.selectElement("__baseImage__") }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(MiuixIcons.Image, null, tint = MiuixTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text("底图-边框", fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f))
            IconButton(onClick = { state.selectElement("__baseImage__") }, modifier = Modifier.size(32.dp)) {
                Icon(MiuixIcons.Edit, "编辑", tint = MiuixTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun PropertyEditor(
    element: Element, state: WatermarkEditorViewModel,
    onPickImage: (String) -> Unit,
    onPickFont: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { state.deselectElement() }, modifier = Modifier.size(32.dp)) {
                    Icon(MiuixIcons.Back, "返回", modifier = Modifier.size(18.dp))
                }
                Text("编辑元素", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface)
            }
            IconButton(onClick = { state.deleteElement(element.id) }, modifier = Modifier.size(32.dp)) {
                Icon(MiuixIcons.Delete, "删除", tint = MiuixTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            }
        }

        // Type-specific properties (each type manages its own layout)
        when (element) {
            is TextElement -> TextPropertyContent(element, state, onPickFont)
            is ImageElement -> ImagePropertyContent(element, state, onPickImage)
            is ContainerElement -> {}
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun TextPropertyContent(
    element: TextElement, state: WatermarkEditorViewModel,
    onPickFont: (String) -> Unit
) {
    // 1. 文字内容
    SectionTitle("文字内容")
    var content by remember(element.content) { mutableStateOf(element.content) }
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), cornerRadius = 12.dp) {
        TextField(value = content, onValueChange = {
            content = it
            state.updateTextElement(element.id) { e -> e.copy(content = it) }
        }, singleLine = false, modifier = Modifier.fillMaxWidth().padding(8.dp))
    }

    // 2. 字号
    var fontSize by remember(element.fontSize) { mutableFloatStateOf(element.fontSize) }
    SliderWithValue(label = "字号 (gm)", value = fontSize, onValueChange = { fontSize = it.roundToInt().toFloat() },
        onValueChangeFinished = { state.updateTextElement(element.id) { it.copy(fontSize = fontSize) } },
        valueRange = 0f..600f, valueFormatter = { "%.0f".format(it) })

    // 2b. 字间距
    var letterSpacing by remember(element.letterSpacing) { mutableFloatStateOf(element.letterSpacing) }
    SliderWithValue(label = "字间距 (gm)", value = letterSpacing,
        onValueChange = { letterSpacing = (it / 5f).roundToInt() * 5f },
        onValueChangeFinished = { state.updateTextElement(element.id) { it.copy(letterSpacing = letterSpacing) } },
        valueRange = -200f..200f, valueFormatter = { "%.0f".format(it) })

    // 2c. 行间距
    var lineHeight by remember(element.lineHeight) { mutableFloatStateOf(element.lineHeight) }
    SliderWithValue(label = "行间距", value = lineHeight,
        onValueChange = { lineHeight = (it * 10f).roundToInt() / 10f },
        onValueChangeFinished = { state.updateTextElement(element.id) { it.copy(lineHeight = lineHeight) } },
        valueRange = 0f..5f, valueFormatter = { "%.1f".format(it) })

    // 3. 字体
    SectionTitle("字体")
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        cornerRadius = 12.dp) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(MiuixIcons.Edit, null, tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = element.fontUri?.substringAfterLast('/') ?: "默认字体",
                fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).clickable { onPickFont(element.id) },
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (!element.fontUri.isNullOrEmpty()) {
                IconButton(
                    onClick = { state.updateTextElement(element.id) { it.copy(fontUri = null) } },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(MiuixIcons.Delete, "恢复默认字体",
                        tint = MiuixTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                }
            }
            Icon(MiuixIcons.Forward, null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(16.dp))
        }
    }

    // 4. 颜色
    SectionTitle("颜色")
    var textColor by remember(element.color) { mutableIntStateOf(element.color) }
    ColorPickerButton(
        currentColor = textColor,
        onColorChange = {
            textColor = it
            state.updateTextElement(element.id) { e -> e.copy(color = it) }
        }
    )

    // 5. 透明度
    var opacity by remember(element.opacity) { mutableFloatStateOf(element.opacity * 100f) }
    SliderWithValue(label = "不透明度", value = opacity, onValueChange = { opacity = it },
        onValueChangeFinished = { state.updateTextElement(element.id) { it.copy(opacity = opacity / 100f) } },
        valueRange = 0f..100f, valueFormatter = { "%.0f%%".format(it) })

    // 6. 对齐方式
    SectionTitle("对齐方式")
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextAlign.entries.forEach { align ->
            val sel = element.textAlign == align
            Box(modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp))
                .background(if (sel) MiuixTheme.colorScheme.primary.copy(alpha = 0.15f) else MiuixTheme.colorScheme.surfaceVariant)
                .clickable { state.updateTextElement(element.id) { it.copy(textAlign = align) } }
                .padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                Text(when (align) { TextAlign.LEFT -> "左对齐"; TextAlign.CENTER -> "居中"; TextAlign.RIGHT -> "右对齐" },
                    fontSize = 13.sp, color = if (sel) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }

    // 7. 锚点
    SectionTitle("锚点")
    AnchorSelector(
        currentAnchor = element.anchor,
        onAnchorChange = { anchor -> state.updateTextElement(element.id) { it.copy(anchor = anchor) } }
    )

    // 8. 外边距
    SectionTitle("外边距 (gm)")
    MarginEditor(
        margin = element.margin,
        onMarginChange = { newMargin -> state.updateTextElement(element.id) { it.copy(margin = newMargin) } }
    )
}

@Composable
private fun ImagePropertyContent(element: ImageElement, state: WatermarkEditorViewModel, onPickImage: (String) -> Unit) {
    // 1. 图片选择
    SectionTitle("图片选择")
    Card(modifier = Modifier.fillMaxWidth().clickable { onPickImage(element.id) }.padding(vertical = 4.dp), cornerRadius = 12.dp) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(MiuixIcons.Image, null, tint = MiuixTheme.colorScheme.onSurfaceVariantActions, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (element.imageUri.isEmpty()) "点击选择图片" else element.imageUri.substringAfterLast('/'), fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurface, modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(MiuixIcons.Forward, null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.size(16.dp))
        }
    }

    // 2. 图片大小相关
    SectionTitle("图片大小基准")
    val sizeRefs = listOf("宽度", "高度", "对角线")
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        sizeRefs.forEachIndexed { idx, label ->
            val sel = element.imageSizeRef == idx
            Box(modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp))
                .background(if (sel) MiuixTheme.colorScheme.primary.copy(alpha = 0.15f) else MiuixTheme.colorScheme.surfaceVariant)
                .clickable { state.updateImageElement(element.id) { it.copy(imageSizeRef = idx) } }
                .padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                Text(label, fontSize = 12.sp,
                    color = if (sel) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }

    Spacer(Modifier.size(4.dp))

    var size by remember(element.width) { mutableFloatStateOf(element.width ?: 200f) }
    SliderWithValue(label = "大小 (gm)", value = size, onValueChange = { size = it.roundToInt().toFloat() },
        onValueChangeFinished = { state.updateImageElement(element.id) { it.copy(width = size) } },
        valueRange = 1f..1000f, valueFormatter = { "%.0f".format(it) })

    // 3. 不透明度
    var opacity by remember(element.opacity) { mutableFloatStateOf(element.opacity * 100f) }
    SliderWithValue(label = "不透明度", value = opacity, onValueChange = { opacity = it },
        onValueChangeFinished = { state.updateImageElement(element.id) { it.copy(opacity = opacity / 100f) } },
        valueRange = 0f..100f, valueFormatter = { "%.0f%%".format(it) })

    // 4. 锚点位置
    SectionTitle("锚点位置")
    AnchorSelector(
        currentAnchor = element.anchor,
        onAnchorChange = { anchor -> state.updateImageElement(element.id) { it.copy(anchor = anchor) } }
    )

    // 5. 外边距
    SectionTitle("外边距 (gm)")
    MarginEditor(
        margin = element.margin,
        onMarginChange = { newMargin -> state.updateImageElement(element.id) { it.copy(margin = newMargin) } }
    )
}

@Composable
private fun BaseImageEditor(
    state: WatermarkEditorViewModel, modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { state.deselectElement() }, modifier = Modifier.size(32.dp)) {
                Icon(MiuixIcons.Back, "返回", modifier = Modifier.size(18.dp))
            }
            Text("编辑底图", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurface)
        }

        SectionTitle("边框宽度 (gm)")
        var borderTop by remember(state.scene.baseImage.borderWidth) {
            mutableFloatStateOf(state.scene.baseImage.borderWidth.top)
        }
        var borderRight by remember(state.scene.baseImage.borderWidth) {
            mutableFloatStateOf(state.scene.baseImage.borderWidth.right)
        }
        var borderBottom by remember(state.scene.baseImage.borderWidth) {
            mutableFloatStateOf(state.scene.baseImage.borderWidth.bottom)
        }
        var borderLeft by remember(state.scene.baseImage.borderWidth) {
            mutableFloatStateOf(state.scene.baseImage.borderWidth.left)
        }
        fun updateBorder() {
            state.updateBaseImage {
                it.copy(borderWidth = BorderWidth(borderTop, borderRight, borderBottom, borderLeft))
            }
        }
        SliderWithValue(label = "上", value = borderTop, onValueChange = { borderTop = (it / 5f).roundToInt() * 5f },
            onValueChangeFinished = { updateBorder() }, valueRange = 0f..1000f)
        SliderWithValue(label = "右", value = borderRight, onValueChange = { borderRight = (it / 5f).roundToInt() * 5f },
            onValueChangeFinished = { updateBorder() }, valueRange = 0f..1000f)
        SliderWithValue(label = "下", value = borderBottom, onValueChange = { borderBottom = (it / 5f).roundToInt() * 5f },
            onValueChangeFinished = { updateBorder() }, valueRange = 0f..1000f)
        SliderWithValue(label = "左", value = borderLeft, onValueChange = { borderLeft = (it / 5f).roundToInt() * 5f },
            onValueChangeFinished = { updateBorder() }, valueRange = 0f..1000f)

        SectionTitle("边框颜色")
        var borderColor by remember(state.scene.baseImage.borderColor) {
            mutableIntStateOf(state.scene.baseImage.borderColor)
        }
        ColorPickerButton(
            currentColor = borderColor,
            onColorChange = {
                borderColor = it
                state.updateBaseImage { b -> b.copy(borderColor = it) }
            }
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun ContainerPropertyEditor(
    container: ContainerElement, state: WatermarkEditorViewModel, modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { state.deselectElement() }, modifier = Modifier.size(32.dp)) {
                    Icon(MiuixIcons.Back, "返回", modifier = Modifier.size(18.dp))
                }
                Text("编辑容器", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface)
            }
            IconButton(onClick = { state.deleteElement(container.id) }, modifier = Modifier.size(32.dp)) {
                Icon(MiuixIcons.Delete, "删除", tint = MiuixTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            }
        }

        // 1. 宽度
        Text("宽度", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(SizeMode.WRAP_CONTENT, SizeMode.FIXED).forEach { mode ->
                val sel = container.widthMode == mode
                Box(modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp))
                    .background(if (sel) MiuixTheme.colorScheme.primary.copy(alpha = 0.15f) else MiuixTheme.colorScheme.surfaceVariant)
                    .clickable {
                        state.updateContainerElement(container.id) {
                            it.copy(
                                widthMode = mode,
                                width = if (mode == SizeMode.FIXED) it.width ?: 300f else it.width
                            )
                        }
                    }
                    .padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Text(if (mode == SizeMode.WRAP_CONTENT) "自适应" else "固定", fontSize = 12.sp,
                        color = if (sel) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
        if (container.widthMode == SizeMode.FIXED) {
            var w by remember(container.width) { mutableFloatStateOf(container.width ?: 300f) }
            SliderWithValue(label = "宽度 (gm)", value = w, onValueChange = { w = it.roundToInt().toFloat() },
                onValueChangeFinished = { state.updateContainerElement(container.id) { it.copy(width = w) } },
                valueRange = 1f..1000f, valueFormatter = { "%.0f".format(it) })
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 2. 高度
        Text("高度", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(SizeMode.WRAP_CONTENT, SizeMode.FIXED).forEach { mode ->
                val sel = container.heightMode == mode
                Box(modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp))
                    .background(if (sel) MiuixTheme.colorScheme.primary.copy(alpha = 0.15f) else MiuixTheme.colorScheme.surfaceVariant)
                    .clickable {
                        state.updateContainerElement(container.id) {
                            it.copy(
                                heightMode = mode,
                                height = if (mode == SizeMode.FIXED) it.height ?: 300f else it.height
                            )
                        }
                    }
                    .padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Text(if (mode == SizeMode.WRAP_CONTENT) "自适应" else "固定", fontSize = 12.sp,
                        color = if (sel) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
        if (container.heightMode == SizeMode.FIXED) {
            var h by remember(container.height) { mutableFloatStateOf(container.height ?: 300f) }
            SliderWithValue(label = "高度 (gm)", value = h, onValueChange = { h = it.roundToInt().toFloat() },
                onValueChangeFinished = { state.updateContainerElement(container.id) { it.copy(height = h) } },
                valueRange = 1f..1000f, valueFormatter = { "%.0f".format(it) })
        }

        // 3. 锚点
        SectionTitle("锚点")
        AnchorSelector(
            currentAnchor = container.anchor,
            onAnchorChange = { anchor ->
                state.updateContainerElement(container.id) { it.copy(anchor = anchor) }
            }
        )

        // 4. 内部元素排列方式
        SectionTitle("内部元素排列方式")
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(ContainerMode.LINEAR_H, ContainerMode.LINEAR_V, ContainerMode.FREE).forEach { mode ->
                val sel = container.mode == mode
                Box(modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp))
                    .background(if (sel) MiuixTheme.colorScheme.primary.copy(alpha = 0.15f) else MiuixTheme.colorScheme.surfaceVariant)
                    .clickable { state.updateContainerElement(container.id) { it.copy(mode = mode) } }
                    .padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Text(when (mode) { ContainerMode.LINEAR_H -> "横向"; ContainerMode.LINEAR_V -> "垂直"; ContainerMode.FREE -> "自由" },
                        fontSize = 12.sp, color = if (sel) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }

        // 5. 主轴对齐
        SectionTitle("主轴对齐")
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MainAxisAlign.entries.forEach { align ->
                val sel = container.mainAxisAlign == align
                Box(modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp))
                    .background(if (sel) MiuixTheme.colorScheme.primary.copy(alpha = 0.15f) else MiuixTheme.colorScheme.surfaceVariant)
                    .clickable { state.updateContainerElement(container.id) { it.copy(mainAxisAlign = align) } }
                    .padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                    Text(when (align) { MainAxisAlign.START -> "开始"; MainAxisAlign.CENTER -> "居中"; MainAxisAlign.END -> "结束"; MainAxisAlign.SPACE_BETWEEN -> "两端"; MainAxisAlign.SPACE_EVENLY -> "均分" },
                        fontSize = 10.sp, color = if (sel) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }

        // 6. 交叉轴对齐
        SectionTitle("交叉轴对齐")
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            CrossAxisAlign.entries.forEach { align ->
                val sel = container.crossAxisAlign == align
                Box(modifier = Modifier.weight(1f).clip(RoundedCornerShape(6.dp))
                    .background(if (sel) MiuixTheme.colorScheme.primary.copy(alpha = 0.15f) else MiuixTheme.colorScheme.surfaceVariant)
                    .clickable { state.updateContainerElement(container.id) { it.copy(crossAxisAlign = align) } }
                    .padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                    Text(when (align) { CrossAxisAlign.START -> "开始"; CrossAxisAlign.CENTER -> "居中"; CrossAxisAlign.END -> "结束"; CrossAxisAlign.STRETCH -> "拉伸" },
                        fontSize = 10.sp, color = if (sel) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }

        Spacer(Modifier.size(4.dp))

        // 7. 不透明度
        var opacity by remember(container.opacity) { mutableFloatStateOf(container.opacity * 100f) }
        SliderWithValue(label = "不透明度", value = opacity, onValueChange = { opacity = it },
            onValueChangeFinished = { state.updateContainerElement(container.id) { it.copy(opacity = opacity / 100f) } },
            valueRange = 0f..100f, valueFormatter = { "%.0f%%".format(it) })

        // 8. 内边距
        SectionTitle("内边距 (gm)")
        PaddingEditor(
            padding = container.padding,
            onPaddingChange = { newPadding ->
                state.updateContainerElement(container.id) { it.copy(padding = newPadding) }
            }
        )

        // 9. 外边距
        SectionTitle("外边距 (gm)")
        MarginEditor(
            margin = container.margin,
            onMarginChange = { newMargin ->
                state.updateContainerElement(container.id) { it.copy(margin = newMargin) }
            }
        )
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun AnchorSelector(currentAnchor: Anchor?, onAnchorChange: (Anchor?) -> Unit) {
    val anchors = listOf(
        listOf(Anchor.TL, Anchor.TC, Anchor.TR),
        listOf(Anchor.ML, Anchor.MC, Anchor.MR),
        listOf(Anchor.BL, Anchor.BC, Anchor.BR)
    )
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 0.dp), cornerRadius = 12.dp) {
        Box(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                anchors.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { anchor ->
                            val sel = currentAnchor == anchor
                            Box(modifier = Modifier.size(44.dp, 36.dp).clip(RoundedCornerShape(6.dp))
                                .background(if (sel) MiuixTheme.colorScheme.primary.copy(alpha = 0.15f) else MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .clickable { onAnchorChange(anchor) }, contentAlignment = Alignment.Center) {
                                Text(anchor.name, fontSize = 12.sp,
                                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                    color = if (sel) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                        }
                    }
                }
            }
            if (currentAnchor != null) {
                IconButton(
                    onClick = { onAnchorChange(null) },
                    modifier = Modifier.align(Alignment.TopEnd).size(28.dp)
                ) {
                    Icon(MiuixIcons.Reset, "清除锚点",
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun MarginEditor(margin: Margin, onMarginChange: (Margin) -> Unit) {
    var top by remember(margin) { mutableFloatStateOf(margin.top) }
    var bottom by remember(margin) { mutableFloatStateOf(margin.bottom) }
    var left by remember(margin) { mutableFloatStateOf(margin.left) }
    var right by remember(margin) { mutableFloatStateOf(margin.right) }

    MarginSlider("上", top, { top = it }) { onMarginChange(Margin(top, right, bottom, left)) }
    MarginSlider("下", bottom, { bottom = it }) { onMarginChange(Margin(top, right, bottom, left)) }
    MarginSlider("左", left, { left = it }) { onMarginChange(Margin(top, right, bottom, left)) }
    MarginSlider("右", right, { right = it }) { onMarginChange(Margin(top, right, bottom, left)) }
}

@Composable
private fun PaddingEditor(padding: Padding, onPaddingChange: (Padding) -> Unit) {
    var top by remember(padding) { mutableFloatStateOf(padding.top) }
    var bottom by remember(padding) { mutableFloatStateOf(padding.bottom) }
    var left by remember(padding) { mutableFloatStateOf(padding.left) }
    var right by remember(padding) { mutableFloatStateOf(padding.right) }

    PaddingSlider("上", top, { top = it }) { onPaddingChange(Padding(top, right, bottom, left)) }
    PaddingSlider("下", bottom, { bottom = it }) { onPaddingChange(Padding(top, right, bottom, left)) }
    PaddingSlider("左", left, { left = it }) { onPaddingChange(Padding(top, right, bottom, left)) }
    PaddingSlider("右", right, { right = it }) { onPaddingChange(Padding(top, right, bottom, left)) }
}

@Composable
private fun PaddingSlider(label: String, value: Float, onValueChange: (Float) -> Unit, onValueChangeFinished: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface, modifier = Modifier.padding(end = 8.dp))
        Slider(value = value, onValueChange = { onValueChange(it.roundToInt().toFloat()) }, onValueChangeFinished = onValueChangeFinished,
            valueRange = 0f..200f, modifier = Modifier.weight(1f))
        Text("%.0f".format(value), fontSize = 14.sp, color = MiuixTheme.colorScheme.primary,
            modifier = Modifier.width(36.dp).padding(start = 4.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun MarginSlider(label: String, value: Float, onValueChange: (Float) -> Unit, onValueChangeFinished: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface, modifier = Modifier.padding(end = 8.dp))
        Slider(value = value, onValueChange = { onValueChange(it.roundToInt().toFloat()) }, onValueChangeFinished = onValueChangeFinished,
            valueRange = 0f..200f, modifier = Modifier.weight(1f))
        Text("%.0f".format(value), fontSize = 14.sp, color = MiuixTheme.colorScheme.primary,
            modifier = Modifier.width(36.dp).padding(start = 4.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp))
}

@Composable
private fun ColorPickerButton(
    currentColor: Int,
    onColorChange: (Int) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    var dialogColor by remember { mutableIntStateOf(currentColor) }

    LaunchedEffect(showDialog) {
        if (showDialog) dialogColor = currentColor
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(currentColor))
            .border(1.dp, MiuixTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .clickable { showDialog = true },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "#%06X".format(currentColor and 0xFFFFFF),
            fontSize = 14.sp,
            color = if (android.graphics.Color.luminance(currentColor) > 0.5) Color.Black else Color.White,
            fontWeight = FontWeight.Medium
        )
    }

    if (showDialog) {
        OverlayDialog(
            show = showDialog,
            onDismissRequest = { showDialog = false }
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("选择颜色", fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 8.dp))

                Spacer(modifier = Modifier.height(8.dp))

                ColorPalette(
                    color = Color(dialogColor),
                    onColorChanged = { newColor ->
                        dialogColor = newColor.toArgb()
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        onColorChange(dialogColor)
                        showDialog = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.primary)
                ) {
                    Text("确定", color = Color.White)
                }
            }
        }
    }
}

private fun calculateInSampleSize(scaleToFit: Float): Int {
    if (scaleToFit >= 1f) return 1
    var s = 1
    val target = (1f / scaleToFit).toInt()
    while (s * 2 <= target) s *= 2
    return s
}

@Composable
private fun SliderWithValue(
    label: String, value: Float, onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit, valueRange: ClosedFloatingPointRange<Float>,
    valueFormatter: (Float) -> String = { "%.0f".format(it) },
    steps: Int = 0
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(start = 0.dp, end = 0.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface)
            Text(valueFormatter(value), fontSize = 14.sp, color = MiuixTheme.colorScheme.primary)
        }
        Slider(value = value, onValueChange = onValueChange, onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange, steps = steps)
    }
}

private suspend fun exportWatermarkZip(
    context: android.content.Context,
    state: WatermarkEditorViewModel,
    uri: android.net.Uri
) = withContext(kotlinx.coroutines.Dispatchers.IO) {
    try {
        val scene = state.scene
        val configJson = cn.alittlecookie.lutista.watermark.util.SceneSerializer.toJson(scene)
        val wrapper = org.json.JSONObject().apply {
            put("type", "watermark")
            put("version", 1)
            put("name", state.watermarkName)
            put("config", org.json.JSONObject(configJson))
        }

        val refPattern = java.util.regex.Pattern.compile(""""(imageUri|fontUri)"\s*:\s*"([^"]+)"""")
        val assetsDir = java.io.File(context.filesDir, "watermark_assets")
        val referencedFiles = mutableListOf<java.io.File>()
        val matcher = refPattern.matcher(configJson)
        while (matcher.find()) {
            val fileName = matcher.group(2)?.substringAfterLast('/') ?: continue
            val file = java.io.File(assetsDir, fileName)
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
        withContext(kotlinx.coroutines.Dispatchers.Main) {
            Toast.makeText(context, "边框水印已导出", Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        withContext(kotlinx.coroutines.Dispatchers.Main) {
            Toast.makeText(context, "导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
